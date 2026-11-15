package com.openminis.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-orion-kokoro-tts] Offline TTS backed by sherpa-onnx + Kokoro (int8).
 *
 * This replaces the system Google-TTS voice entirely: Kokoro runs on-device,
 * needs no network and no engine selection, and ships a real male Spanish
 * voice (`em_alex`, speaker 29) so the JARVIS greeting no longer sounds like a
 * "chica" voice. Because `generateWithCallbackAndCallback` streams samples to
 * an AudioTrack WHILE the model is still generating, speech starts long before
 * synthesis completes — the perceived latency is roughly the first sentence,
 * not the whole reply.
 *
 * The model is a one-time download (see [KokoroModelDownloader], ~130 MB in
 * `files/kokoro/`). Everything here is a no-op that returns false until it is
 * installed, so callers fall back to the system engine transparently.
 *
 * A single app-scoped instance is shared by the wake-word service (greeting +
 * spoken replies outside the app) and [ReadAloudPlayer] (in-app voice): model
 * load takes seconds, so it must never be re-created per utterance.
 */
object KokoroVoice {

    private const val TAG = "OrionKokoroTts"

    /**
     * Speaker id 29 = `em_alex` — the male Spanish voice of
     * kokoro-multi-lang-v1_0 (28 = ef_dora female; the rest are en/zh/…).
     */
    const val SPANISH_MALE_SID = 29

    private var context: Context? = null
    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null
    private val loadMutex = Mutex()
    private val speakMutex = Mutex()
    private val generation = AtomicInteger(0)
    private var sampleRate = 24_000

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
     * Lazy-load the model. Safe to call repeatedly; returns false when the
     * model is not downloaded yet or the native library is unavailable.
     */
    private suspend fun ensureLoaded(): Boolean = loadMutex.withLock {
        val appContext = context ?: return false
        if (tts != null) return true
        if (!KokoroModelDownloader.isInstalled(appContext)) {
            AppLogger.info(TAG, "Kokoro model not installed yet — system TTS fallback")
            return false
        }
        return try {
            withContext(Dispatchers.Default) {
                val dir = KokoroModelDownloader.assetsDir(appContext)
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = java.io.File(dir, "model.int8.onnx").absolutePath,
                            voices = java.io.File(dir, "voices.bin").absolutePath,
                            tokens = java.io.File(dir, "tokens.txt").absolutePath,
                            // Spanish (and every non-lexicon language) is
                            // phonemized from the espeak-ng data shipped in
                            // the model package.
                            dataDir = java.io.File(dir, "espeak-ng-data").absolutePath,
                            lexicon = listOf(
                                java.io.File(dir, "lexicon-us-en.txt").absolutePath,
                                java.io.File(dir, "lexicon-zh.txt").absolutePath,
                            ).joinToString(","),
                        ),
                        numThreads = 2,
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
                    "Kokoro loaded: sampleRate=$sampleRate speakers=${engine.numSpeakers()}",
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
                    val t = openTrack(engine.sampleRate())
                    track = t
                    t.play()
                    val audio = engine.generateWithConfigAndCallback(
                        text = text,
                        config = GenerationConfig(sid = SPANISH_MALE_SID, speed = speed),
                        // [T-orion-kokoro-jni-callback] A plain lambda here desugars
                        // into a D8 synthetic class whose only `invoke` takes
                        // (Object) — sherpa's native side looks up the concrete
                        // `invoke([F)Ljava/lang/Integer;` by reflection, misses it,
                        // and the pending NoSuchMethodError + NewFloatArray aborts
                        // the whole process (SIGSEGV mid-greeting). An object
                        // expression compiles to a real class with exactly that
                        // method, so the JNI lookup succeeds.
                        callback = object : (FloatArray) -> Int {
                            override fun invoke(samples: FloatArray): Int {
                                // Return 1 to keep generating, 0 to stop.
                                if (generation.get() != id) return 0
                                t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                                return 1
                            }
                        },
                    )
                    if (generation.get() == id) {
                        // Callbacks covered the whole stream; let the track
                        // drain what was already written.
                        val silence = FloatArray(sampleRate / 10)
                        t.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                    }
                    t.stop()
                    t.release()
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

    private fun openTrack(rate: Int): AudioTrack {
        val buf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(rate / 2) // ≥0.5 s: keeps WRITE_BLOCKING smooth mid-word
        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(rate)
            .build()
        return AudioTrack(attrs, format, buf, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
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
