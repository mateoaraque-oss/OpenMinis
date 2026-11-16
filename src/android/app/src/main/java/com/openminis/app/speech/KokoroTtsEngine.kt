package com.openminis.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-orion-kokoro-tts] Offline TTS backed by sherpa-onnx + VITS piper.
 *
 * This replaces the system Google-TTS voice entirely: piper runs on-device,
 * needs no network and no engine selection, and ships a real male Spanish
 * voice (`es_ES-miro`, high quality) so the JARVIS greeting no longer sounds
 * like a "chica" voice.
 *
 * [T-orion-voice-mediaplayer] Playback goes through a 16-bit WAV file played by
 * MediaPlayer, not a streaming AudioTrack. The Nubia's UNISOC HAL produced a
 * constant "radio static" on float PCM at 22050 Hz, and calling AudioTrack.stop
 * right after writing chopped the tail off every utterance ("¿Me llamó?" with
 * no "señor"). MediaPlayer resamples with the platform's tested path, plays the
 * whole file to completion, and routes through the media volume stream (louder
 * than USAGE_ASSISTANT on this ROM).
 *
 * Piper was chosen over Kokoro after Kokoro proved unusable for Spanish:
 * sherpa-onnx only phonemizes English and Chinese for kokoro-multi-lang-v1_0,
 * so Spanish fell through to espeak-ng's default voice with a French "r".
 *
 * The model ships inside the APK (see [KokoroModelDownloader], ~21 MB int8) and
 * is unpacked to `files/piper/` on first use. Everything here is a no-op that
 * returns false until it is installed, so callers fall back to the system
 * engine transparently.
 */
object KokoroVoice {

    private const val TAG = "OrionKokoroTts"

    // [T-orion-voice-volume] El modelo es_ES-miro-high int8 entrega el audio con
    // un pico muy bajo (~0,13), así que sonaba bajísimo y obligaba a subir el
    // volumen del teléfono al máximo (donde el amplificador mete su propio
    // ruido). En lugar de una ganancia fija, se normaliza cada frase a
    // TARGET_PEAK, con un tope de MAX_GAIN para no amplificar el fondo de un
    // audio casi mudo.
    private const val TARGET_PEAK = 0.95f
    private const val MAX_GAIN = 20f

    private var context: Context? = null
    private var tts: OfflineTts? = null
    private var player: MediaPlayer? = null
    private val loadMutex = Mutex()
    private val speakMutex = Mutex()
    private val generation = AtomicInteger(0)
    private var sampleRate = 22_050

    @Volatile private var currentDone: CompletableDeferred<Boolean>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(appContext: Context) {
        if (context == null) context = appContext.applicationContext
    }

    /** True when the downloaded model files are on disk. */
    fun isModelReady(): Boolean =
        context?.let { KokoroModelDownloader.isInstalled(it) } ?: false

    /** Download status flow for diagnostics. */
    fun downloadStatus(): KokoroModelDownloader.Status? =
        context?.let { KokoroModelDownloader.status(it) }

    /** Enqueue the one-time model download. */
    fun startDownload(): Long? = context?.let { KokoroModelDownloader.startDownload(it) }

    /**
     * Precarga el motor de voz (modelo + nativo) para que el primer saludo no
     * pague los segundos de carga. Debe llamarse desde un scope propio (p. ej.
     * el del servicio de despertar) para no bloquear el hilo que lo invoque.
     */
    suspend fun preload() = ensureLoaded()

    /**
     * Lazy-load the model. Safe to call repeatedly; returns false when the
     * model is not downloaded yet or the native library is unavailable.
     */
    private suspend fun ensureLoaded(): Boolean = loadMutex.withLock {
        val appContext = context ?: return false
        if (tts != null) return true
        if (!KokoroModelDownloader.isInstalled(appContext)) {
            // [T-orion-piper-tts] Bundled-APK builds carry the model in
            // assets: unpack it right here (seconds, one time) instead of
            // falling back to the system voice on the first utterance.
            val unpacked = runCatching {
                KokoroModelDownloader.installFromAssets(appContext)
            }.getOrDefault(false)
            if (!unpacked) {
                AppLogger.info(TAG, "Piper model not installed yet — system TTS fallback")
                return false
            }
        }
        return try {
            withContext(Dispatchers.Default) {
                val dir = KokoroModelDownloader.assetsDir(appContext)
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = File(dir, "es_ES-miro-high.onnx").absolutePath,
                            tokens = File(dir, "tokens.txt").absolutePath,
                            // Piper phonemizes Spanish through espeak-ng using
                            // the per-language data shipped in the package.
                            dataDir = File(dir, "espeak-ng-data").absolutePath,
                        ),
                        numThreads = 4,
                        debug = false,
                        provider = "cpu",
                    ),
                    maxNumSentences = 1,
                )
                val engine = OfflineTts(config = config)
                sampleRate = engine.sampleRate()
                tts = engine
                AppLogger.info(
                    TAG,
                    "Piper loaded: sampleRate=$sampleRate speakers=${engine.numSpeakers()}",
                )
            }
            true
        } catch (t: Throwable) {
            // ClassNotFound (AAR missing) and UnsatisfiedLink (so missing) land
            // here too; never crash the app over a voice.
            AppLogger.error(TAG, "Kokoro init failed: ${t.javaClass.simpleName}: ${t.message}")
            tts = null
            false
        }
    }

    /**
     * Speak [text] with the male Spanish voice. Generates the whole utterance,
     * writes it to a WAV file and plays it through MediaPlayer until the last
     * sample has been heard. Returns false (nothing played) when the model is
     * unavailable or playback fails — callers fall back to the system engine.
     */
    suspend fun speak(text: String, speed: Float = 1.0f): Boolean {
        if (text.isBlank()) return false
        if (!ensureLoaded()) return false
        val engine = tts ?: return false
        return speakMutex.withLock {
            try {
                withContext(Dispatchers.Default) {
                    val id = generation.incrementAndGet()
                    // [T-orion-kokoro-jni-callback] A plain lambda here desugars
                    // into a D8 synthetic class whose only `invoke` takes
                    // (Object) — sherpa's native side looks up the concrete
                    // `invoke([F)Ljava/lang/Integer;` by reflection, misses it,
                    // and the pending NoSuchMethodError + NewFloatArray aborts
                    // the whole process (SIGSEGV mid-greeting). An object
                    // expression compiles to a real class with exactly that
                    // method, so the JNI lookup succeeds.
                    //
                    // sherpa-onnx is pinned to 1.x, whose Kotlin API only
                    // offers generateWithCallback(text, sid, speed, callback)
                    // — 1.x is required because it is the last line with
                    // espeak-ng phonemization for piper models (Spanish!).
                    val chunks = mutableListOf<FloatArray>()
                    engine.generateWithCallback(
                        text = text,
                        sid = 0,
                        speed = speed,
                        callback = object : (FloatArray) -> Int {
                            override fun invoke(samples: FloatArray): Int {
                                // Return 1 to keep generating, 0 to stop.
                                if (generation.get() != id) return 0
                                chunks.add(samples.copyOf(samples.size))
                                return 1
                            }
                        },
                    )
                    if (generation.get() != id || chunks.isEmpty()) {
                        AppLogger.info(TAG, "Kokoro speak cancelled")
                        return@withContext false
                    }
                    // Concatena todos los trozos en una sola pista.
                    val total = chunks.sumOf { it.size }
                    val all = FloatArray(total)
                    var off = 0
                    for (c in chunks) {
                        System.arraycopy(c, 0, all, off, c.size)
                        off += c.size
                    }
                    playAll(all, engine.sampleRate())
                }
            } catch (t: Throwable) {
                AppLogger.error(TAG, "Kokoro speak failed: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
    }

    /** Interrupt the current utterance, if any. */
    fun stop() {
        generation.incrementAndGet()
        currentDone?.complete(false)
        mainHandler.post {
            runCatching { player?.stop() }
        }
    }

    /**
     * Escribe [samples] como WAV 16-bit mono y lo reproduce hasta el final con
     * MediaPlayer. El limitador suave (tanh) sube el volumen sin clips duros.
     */
    private suspend fun playAll(samples: FloatArray, rate: Int): Boolean {
        val appContext = context ?: return false
        val file = File(appContext.cacheDir, "orion_tts.wav")
        writeWav(file, samples, rate)

        val done = CompletableDeferred<Boolean>()
        val mp = MediaPlayer()
        currentDone = done
        val started = withContext(Dispatchers.Main) {
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        // El stream de media tiene más volumen que USAGE_ASSISTANT
                        // en esta ROM y es el que el usuario controla con los
                        // botones de volumen.
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { done.complete(true) }
                mp.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    done.complete(false)
                    true
                }
                mp.prepare()
                player = mp
                mp.start()
                Log.w(TAG, "MediaPlayer playing rate=$rate samples=${samples.size}")
                true
            } catch (t: Throwable) {
                AppLogger.error(TAG, "MediaPlayer start failed: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
        if (!started) {
            runCatching { mp.release() }
            if (player === mp) player = null
            currentDone = null
            return false
        }
        val ok = withTimeoutOrNull(60_000) { done.await() } ?: false
        withContext(Dispatchers.Main) {
            runCatching { mp.release() }
        }
        if (player === mp) player = null
        currentDone = null
        return ok
    }

    /** WAV PCM 16-bit mono con ganancia y limitador suave. */
    private fun writeWav(file: File, samples: FloatArray, rate: Int) {
        val dataBytes = samples.size * 2
        val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        try {
            fun le16(v: Int) {
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }
            fun le32(v: Int) {
                le16(v and 0xFFFF)
                le16((v shr 16) and 0xFFFF)
            }
            fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))

            ascii("RIFF")
            le32(36 + dataBytes)
            ascii("WAVE")
            ascii("fmt ")
            le32(16)
            le16(1)              // PCM
            le16(1)              // mono
            le32(rate)
            le32(rate * 2)       // byte rate
            le16(2)              // block align
            le16(16)             // bits per sample
            ascii("data")
            le32(dataBytes)
            // Normaliza al pico para que suene fuerte a volumen medio.
            var peak = 0f
            for (s in samples) {
                val a = kotlin.math.abs(s)
                if (a > peak) peak = a
            }
            val gain = if (peak > 1e-4f) {
                (TARGET_PEAK / peak).coerceAtMost(MAX_GAIN)
            } else {
                1f
            }
            Log.w(TAG, "WAV gain=${"%.2f".format(gain)} peak=${"%.3f".format(peak)}")
            for (s in samples) {
                val pcm = (s * gain).coerceIn(-1f, 1f).times(32767f).toInt()
                    .coerceIn(-32768, 32767)
                le16(pcm)
            }
        } finally {
            out.close()
        }
    }
}
