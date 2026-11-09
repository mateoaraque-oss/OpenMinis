package com.openminis.app.wakeword

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter

/**
 * [T-orion-wake-word] On-device "Hey Jarvis" detector — openWakeWord.
 *
 * Three tiny TFLite models rather than a cloud hotword:
 *
 *  - `melspectrogram.tflite` turns 80 ms of 16 kHz PCM into 8 mel frames;
 *    its input is dynamic (`[1, samples]`) and always receives the last
 *    1 760 samples (1 280 new + 480 of look-back), mirroring the reference
 *    `openwakeword/utils.py::AudioFeatures._streaming_features`.
 *  - `embedding_model.tflite` turns the last 76 mel frames into one 96-float
 *    speech embedding (Google's speech_embedding network, Apache-2.0).
 *  - `hey_jarvis_v0.1.tflite` scores the last 16 embeddings: one number per
 *    80 ms, > 0.5 means the wake word.
 *
 * Everything stays on the phone; nothing is uploaded and no account or key
 * is involved. The pretrained models are CC BY-NC-SA 4.0 (openWakeWord), so
 * this is fine for personal use but not for a store build.
 *
 * The class is not thread-safe: the service drives it from one coroutine.
 */
class OpenWakeWord(
    context: Context,
    private val onScore: ((Float) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "OrionWakeWord"

        private const val SAMPLE_RATE = 16_000
        private const val STEP_SAMPLES = 1_280        // 80 ms
        private const val MEL_LOOKBACK = 480          // 3 mel frames of context
        private const val MEL_WINDOW = STEP_SAMPLES + MEL_LOOKBACK
        private const val MEL_BINS = 32
        private const val MEL_FRAMES_PER_STEP = 8
        private const val MEL_MAX_ROWS = 970          // ~9.7 s
        private const val EMB_WINDOW_FRAMES = 76
        private const val EMB_DIM = 96
        private const val FEATURES_PER_STEP = 16      // 16 x 80 ms = 1.28 s
        private const val RAW_SECONDS = 10

        /** Score above which the wake word fires. */
        const val THRESHOLD = 0.55f
    }

    private val mel: Interpreter
    private val emb: Interpreter
    private val wake: Interpreter

    // ── rolling raw audio, last 10 s ────────────────────────────────────
    private val raw = FloatArray(SAMPLE_RATE * RAW_SECONDS)
    private var rawPos = 0
    private var rawCount = 0

    private val melInput: Array<FloatArray>
    private val embInput: Array<Array<Array<FloatArray>>> =
        Array(1) { Array(EMB_WINDOW_FRAMES) { Array(MEL_BINS) { FloatArray(1) } } }

    /** Embedding output laid out exactly as the tensor expects. */
    private val embOutput: Any

    /** Mel output buffer; rank differs between the ONNX (3-D) and TFLite (4-D) builds. */
    private val melOutput: Any

    /** Flattened mel frames for the step that just ran. */
    private val melFlat = FloatArray(MEL_FRAMES_PER_STEP * MEL_BINS)

    private val wakeInput: Array<Array<FloatArray>> =
        Array(1) { Array(FEATURES_PER_STEP) { FloatArray(EMB_DIM) } }
    private val wakeOutput = Array(1) { FloatArray(1) }

    // ── mel ring buffer (rows of 32 bins) ───────────────────────────────
    private val melBuf = Array(MEL_MAX_ROWS) { FloatArray(MEL_BINS).also { it.fill(1f) } }
    private var melHead = 0
    private var melCount = EMB_WINDOW_FRAMES

    // ── embedding ring buffer ───────────────────────────────────────────
    private val featureBuf = Array(FEATURES_PER_STEP) { FloatArray(EMB_DIM) }
    private var featureNext = 0
    private var featureCount = 0

    init {
        mel = Interpreter(loadModel(context, "wakeword/melspectrogram.tflite"))
        emb = Interpreter(loadModel(context, "wakeword/embedding_model.tflite"))
        wake = Interpreter(loadModel(context, "wakeword/hey_jarvis_v0.1.tflite"))

        // The mel model's input length is dynamic: pin it to one window and
        // let TFLite compute the 8-frame output.
        mel.resizeInput(0, intArrayOf(1, MEL_WINDOW))
        mel.allocateTensors()
        val melShape = mel.getOutputTensor(0).shape()
        // TFLite builds of the same model report [1,1,8,32] where the ONNX
        // export reports [8,1,32]: batch and channel axes differ, but the
        // last two dimensions are always (frames, bins).
        require(melShape.size >= 3 &&
            melShape[melShape.size - 2] == MEL_FRAMES_PER_STEP &&
            melShape[melShape.size - 1] == MEL_BINS
        ) {
            "mel output shape ${melShape.joinToString()} (expected [..,$MEL_FRAMES_PER_STEP,$MEL_BINS])"
        }
        melInput = Array(1) { FloatArray(MEL_WINDOW) }
        melOutput = when (melShape.size) {
            3 -> Array(1) { Array(MEL_FRAMES_PER_STEP) { FloatArray(MEL_BINS) } }
            4 -> Array(1) { Array(1) { Array(MEL_FRAMES_PER_STEP) { FloatArray(MEL_BINS) } } }
            else -> error("mel output rank ${melShape.size} unsupported")
        }

        val embIn = emb.getInputTensor(0).shape()
        require(embIn.size == 4 && embIn[1] == EMB_WINDOW_FRAMES && embIn[2] == MEL_BINS) {
            "embedding input shape ${embIn.joinToString()} unexpected"
        }
        val embOutShape = emb.getOutputTensor(0).shape()
        embOutput = when (embOutShape.size) {
            4 -> Array(1) { Array(1) { Array(1) { FloatArray(EMB_DIM) } } }
            3 -> Array(1) { Array(1) { FloatArray(EMB_DIM) } }
            else -> Array(1) { FloatArray(EMB_DIM) }
        }

        val wakeIn = wake.getInputTensor(0).shape()
        require(wakeIn.size == 3 && wakeIn[1] == FEATURES_PER_STEP && wakeIn[2] == EMB_DIM) {
            "wake input shape ${wakeIn.joinToString()} unexpected"
        }
        Log.w(
            TAG,
            "models ready: mel ${melShape.joinToString()} emb ${embOutShape.joinToString()} " +
                "wake ${wake.getOutputTensor(0).shape().joinToString()}",
        )
    }

    private fun loadModel(context: Context, asset: String): java.nio.ByteBuffer {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        val buffer = java.nio.ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()
        return buffer
    }

    /**
     * Feed one 80 ms chunk (1 280 samples of 16-bit PCM) and return the wake
     * word score for the window that ends here.
     */
    fun push(samples: ShortArray, length: Int = samples.size): Float {
        for (i in 0 until length) {
            raw[rawPos] = samples[i].toFloat()
            rawPos = (rawPos + 1) % raw.size
            if (rawCount < raw.size) rawCount++
        }
        if (rawCount < MEL_WINDOW) return 0f

        // Last MEL_WINDOW samples, oldest first; zero-padded only at startup.
        val have = minOf(MEL_WINDOW, rawCount)
        var idx = (rawPos - have + raw.size) % raw.size
        val pad = MEL_WINDOW - have
        for (i in 0 until pad) melInput[0][i] = 0f
        for (i in 0 until have) {
            melInput[0][pad + i] = raw[idx]
            idx = (idx + 1) % raw.size
        }

        mel.run(melInput, melOutput)

        // `_get_melspectrogram` applies x/10 + 2, the transform that makes the
        // small ONNX/TFLite frontend match Google's TFHub implementation.
        flattenMel(melOutput, melFlat)
        for (f in 0 until MEL_FRAMES_PER_STEP) {
            val base = f * MEL_BINS
            val row = FloatArray(MEL_BINS) { melFlat[base + it] / 10f + 2f }
            appendMel(row)
        }

        // One embedding per 80 ms from the last 76 mel rows.
        fillEmbeddingInput()
        emb.run(embInput, embOutput)
        appendFeature(readEmbedding())

        // Score the last 16 embeddings.
        val n = minOf(featureCount, FEATURES_PER_STEP)
        val missing = FEATURES_PER_STEP - n
        for (r in 0 until missing) wakeInput[0][r].fill(0f)
        val start = (featureNext - n + FEATURES_PER_STEP) % FEATURES_PER_STEP
        for (r in 0 until n) {
            System.arraycopy(
                featureBuf[(start + r) % FEATURES_PER_STEP],
                0,
                wakeInput[0][missing + r],
                0,
                EMB_DIM,
            )
        }
        wake.run(wakeInput, wakeOutput)
        val score = wakeOutput[0][0]
        if (score > 0.15f) Log.d(TAG, "score ${"%.3f".format(score)}")
        onScore?.invoke(score)
        return score
    }

    private fun appendMel(row: FloatArray) {
        if (melCount < MEL_MAX_ROWS) {
            melBuf[(melHead + melCount) % MEL_MAX_ROWS] = row
            melCount++
        } else {
            melBuf[melHead] = row
            melHead = (melHead + 1) % MEL_MAX_ROWS
        }
    }

    private fun fillEmbeddingInput() {
        val first = (melHead + melCount - EMB_WINDOW_FRAMES + MEL_MAX_ROWS) % MEL_MAX_ROWS
        for (r in 0 until EMB_WINDOW_FRAMES) {
            val row = melBuf[(first + r) % MEL_MAX_ROWS]
            for (c in 0 until MEL_BINS) embInput[0][r][c][0] = row[c]
        }
    }

    /** Copy a nested TFLite output tensor into [dst], in row-major order. */
    private fun flattenMel(src: Any, dst: FloatArray) {
        var k = 0
        fun walk(node: Any?) {
            when (node) {
                is FloatArray -> {
                    System.arraycopy(node, 0, dst, k, node.size)
                    k += node.size
                }
                is Array<*> -> for (child in node) walk(child)
                else -> Unit
            }
        }
        walk(src)
    }

    private fun appendFeature(embedding: FloatArray) {
        System.arraycopy(embedding, 0, featureBuf[featureNext], 0, EMB_DIM)
        featureNext = (featureNext + 1) % FEATURES_PER_STEP
        if (featureCount < FEATURES_PER_STEP) featureCount++
    }

    private fun readEmbedding(): FloatArray {
        val out = FloatArray(EMB_DIM)
        var k = 0
        fun walk(node: Any?) {
            when (node) {
                is FloatArray -> for (v in node) out[k++] = v
                is Array<*> -> for (child in node) walk(child)
                else -> Unit
            }
        }
        walk(embOutput)
        return out
    }

    fun close() {
        mel.close()
        emb.close()
        wake.close()
    }
}
