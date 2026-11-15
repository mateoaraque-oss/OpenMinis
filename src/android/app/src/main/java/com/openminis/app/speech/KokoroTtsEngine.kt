package com.openminis.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-orion-kokoro-tts] Offline TTS backed by sherpa-onnx + VITS piper.
 *
 * This replaces the system Google-TTS voice entirely: piper runs on-device,
 * needs no network and no engine selection, and ships a real male Spanish
 * voice (`es_ES-miro`, high quality) so the JARVIS greeting no longer sounds
 * like a "chica" voice. Because `generateWithCallbackAndCallback` streams
 * samples to an AudioTrack WHILE the model is still generating, speech starts
 * long before synthesis completes — the perceived latency is roughly the first
 * sentence, not the whole reply.
 *
 * Piper was chosen over Kokoro after Kokoro proved unusable for Spanish:
 * sherpa-onnx only phonemizes English and Chinese for kokoro-multi-lang-v1_0
 * (its G2P pipeline ships misaki us/gb + a zh lexicon only), so Spanish text
 * fell through to espeak-ng's default voice and came out with a French "r".
 * Piper models use espeak-ng with full per-language data, so Spanish works.
 *
 * The model is a one-time download (see [KokoroModelDownloader], ~21 MB int8
 * in `files/piper/`). Everything here is a no-op that returns false until it
 * is installed, so callers fall back to the system engine transparently.
 *
 * A single app-scoped instance is shared by the wake-word service (greeting +
 * spoken replies outside the app) and [ReadAloudPlayer] (in-app voice): model
 * load takes seconds, so it must never be re-created per utterance.
 */
object KokoroVoice {

    private const val TAG = "OrionKokoroTts"

    // [T-orion-voice-volume] La voz masculina española de piper se oye baja en
    // este HAL UNISOC; una ganancia de salida moderada la sube sin hacer clip
    // (muestras >1,0 clampeadas = distorsión/estática). 2.0f es el valor seguro;
    // si se oye bajo, subir el volumen físico del teléfono.
    private const val OUTPUT_GAIN = 2.0f

    private var context: Context? = null
    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null
    private val loadMutex = Mutex()
    private val speakMutex = Mutex()
    private val generation = AtomicInteger(0)
    private var sampleRate = 22_050

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
                            model = java.io.File(dir, "es_ES-miro-high.onnx").absolutePath,
                            tokens = java.io.File(dir, "tokens.txt").absolutePath,
                            // Piper phonemizes Spanish through espeak-ng using
                            // the per-language data shipped in the package.
                            dataDir = java.io.File(dir, "espeak-ng-data").absolutePath,
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
     * Speak [text] with the male Spanish voice. Streams audio while it
     * generates; suspends until playback has drained. Returns false (nothing
     * played) when the model is unavailable or generation fails — callers fall
     * back to the system engine.
     */
    suspend fun speak(text: String, speed: Float = 1.0f): Boolean {
        if (text.isBlank()) return false
        if (!ensureLoaded()) return false
        val engine = tts ?: return false
        return speakMutex.withLock {
            try {
                withContext(Dispatchers.Default) {
                    val id = generation.incrementAndGet()
                    val session = openTrack(engine.sampleRate()) ?: run {
                        AppLogger.error(TAG, "No playable AudioTrack config for this HAL")
                        return@withContext false
                    }
                    track = session.track
                    session.track.play()
                    // [T-orion-kokoro-jni-callback] A plain lambda here desugars
                    // into a D8 synthetic class whose only `invoke` takes
                    // (Object) — sherpa's native side looks up the concrete
                    // `invoke([F)Ljava/lang/Integer;` by reflection, misses it,
                    // and the pending NoSuchMethodError + NewFloatArray aborts
                    // the whole process (SIGSEGV mid-greeting). An object
                    // expression compiles to a real class with exactly that
                    // method, so the JNI lookup succeeds.
                    //
                    // [T-orion-piper-tts] sherpa-onnx is pinned to 1.x, whose
                    // Kotlin API only offers generateWithCallback(text, sid,
                    // speed, callback) — the 2.x generateWithConfigAndCallback
                    // does not exist there, and 1.x is required anyway because
                    // it is the last line with espeak-ng phonemization for
                    // piper models (Spanish!). With maxNumSentences=1 the
                    // callback fires once per sentence, so speech still starts
                    // before the whole reply is synthesized.
                    val audio = engine.generateWithCallback(
                        text = text,
                        sid = 0,
                        speed = speed,
                        callback = object : (FloatArray) -> Int {
                            override fun invoke(samples: FloatArray): Int {
                                // Return 1 to keep generating, 0 to stop.
                                if (generation.get() != id) return 0
                                writeChunk(session, samples)
                                return 1
                            }
                        },
                    )
                    if (generation.get() == id) {
                        // Callbacks covered the whole stream; let the track
                        // drain what was already written.
                        val silence = FloatArray(session.modelRate / 10)
                        writeChunk(session, silence)
                    }
                    session.track.stop()
                    session.track.release()
                    track = null
                    val ok = audio.samples.isNotEmpty() && generation.get() == id
                    if (!ok) AppLogger.info(TAG, "Kokoro speak cancelled")
                    ok
                }
            } catch (t: Throwable) {
                AppLogger.error(TAG, "Kokoro speak failed: ${t.javaClass.simpleName}: ${t.message}")
                releaseTrack()
                false
            }
        }
    }

    /** Interrupt the current utterance, if any. */
    fun stop() {
        generation.incrementAndGet()
        releaseTrack()
    }

    /**
     * [T-orion-piper-tts] Playback config chosen defensively. The Nubia's
     * UNISOC audio HAL throws IllegalArgumentException("Invalid audio buffer
     * size") for float PCM at 22050 Hz (piper's rate) — Kokoro's 24000 Hz
     * float worked, so the failure was invisible until the voice swap. Order:
     * float at the model rate → 16-bit at the model rate → 16-bit at 48 kHz
     * with in-callback resampling.
     */
    private class PlaySession(
        val track: AudioTrack,
        val encoding: Int,
        val modelRate: Int,
    ) {
        val trackRate: Int = track.sampleRate
    }

    private fun openTrack(modelRate: Int): PlaySession? {
        tryOpenTrack(modelRate, AudioFormat.ENCODING_PCM_FLOAT, modelRate)?.let { return it }
        tryOpenTrack(modelRate, AudioFormat.ENCODING_PCM_16BIT, modelRate)?.let { return it }
        return tryOpenTrack(48_000, AudioFormat.ENCODING_PCM_16BIT, modelRate)
    }

    private fun tryOpenTrack(rate: Int, encoding: Int, modelRate: Int): PlaySession? = try {
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val floor = rate * bytesPerSample // ≥1 s: evita underruns/estática
        var buf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, encoding)
        if (buf <= 0) {
            Log.w(TAG, "getMinBufferSize($rate, enc=$encoding) failed — using 0.5 s fallback")
            buf = floor
        } else {
            buf = buf.coerceAtLeast(floor)
        }
        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(rate)
            .build()
        val t = AudioTrack(attrs, format, buf, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack($rate, enc=$encoding) uninitialized (state=${t.state})")
            t.release()
            null
        } else {
            Log.w(TAG, "AudioTrack playing rate=$rate enc=$encoding")
            PlaySession(t, encoding, modelRate)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "AudioTrack($rate, enc=$encoding) failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** Write one generated chunk, converting/resampling to the track format. */
    private fun writeChunk(session: PlaySession, samples: FloatArray) {
        if (session.encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            // Ganancia de salida con clamp para que la voz se oiga clara sin
            // saturar (clamp a [-1, 1] tras amplificar).
            for (i in samples.indices) samples[i] = (samples[i] * OUTPUT_GAIN).coerceIn(-1f, 1f)
            session.track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            return
        }
        val resampled = if (session.trackRate != session.modelRate) {
            resample(samples, session.modelRate, session.trackRate)
        } else {
            samples
        }
        val shorts = ShortArray(resampled.size) { i ->
            ((resampled[i] * OUTPUT_GAIN).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        session.track.write(shorts, 0, shorts.size, AudioTrack.WRITE_BLOCKING)
    }

    /** Naive linear-interpolation resampler — speech quality is fine with it. */
    private fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || samples.isEmpty()) return samples
        val outLen = (samples.size.toLong() * to / from).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * from.toDouble() / to
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val a = samples[i0.coerceAtMost(samples.size - 1)]
            val b = samples[(i0 + 1).coerceAtMost(samples.size - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    private fun releaseTrack() {
        try {
            track?.stop()
        } catch (_: Throwable) {
        }
        try {
            track?.release()
        } catch (_: Throwable) {
        }
        track = null
    }
}
