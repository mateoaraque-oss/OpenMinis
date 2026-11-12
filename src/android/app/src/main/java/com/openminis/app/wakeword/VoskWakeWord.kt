package com.openminis.app.wakeword

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * [T-orion-custom-wake] Offline keyword spotting with Vosk.
 *
 * The phrase is whatever the user types, not a model that had to be trained
 * for it. A small Spanish acoustic model (~40 MB, shipped in assets) decodes
 * the microphone with a grammar restricted to the phrase plus `[unk]`, which
 * keeps the decoder cheap and stops it from producing unrelated sentences.
 *
 * Why Vosk and not openWakeWord: openWakeWord can only hear the phrases its
 * classifier was trained on, and training a new one needs a Colab run and an
 * hour of synthetic audio. Vosk hears words, so any Spanish phrase works with
 * no account, no key and no training — at the cost of more CPU/battery.
 *
 * `[unk]` in the grammar is what makes silence and unrelated speech decode as
 * `[unk]` instead of being forced into the phrase. The fuzzy matcher below
 * tolerates the small model's spelling ("oye ori ene" for "oye orión").
 *
 * Not thread-safe: the service drives it from one coroutine.
 */
class VoskWakeWord(
    context: Context,
    phrase: String,
) {
    companion object {
        private const val TAG = "OrionWakeWord"
        const val SAMPLE_RATE = 16_000

        /** Bump when the bundled model changes so phones re-copy it. */
        private const val MODEL_VERSION = "vosk-model-small-es-0.42"

        private const val ASSET_DIR = "vosk/model-es"
        private const val INSTALL_DIR = "vosk-es"

        fun installDir(context: Context): File = File(context.filesDir, INSTALL_DIR)

        fun isReady(context: Context): Boolean {
            val marker = File(installDir(context), ".ready")
            return marker.isFile && marker.readText().trim() == MODEL_VERSION
        }

        /**
         * Copy the model out of assets once. Vosk can memory-map assets only
         * after they exist as real files, and the copy is ~60 MB, so the
         * version marker keeps every later start instant.
         */
        fun ensureInstalled(context: Context): File {
            val dir = installDir(context)
            if (isReady(context)) return dir
            dir.deleteRecursively()
            dir.mkdirs()
            copyAssetTree(context.assets, ASSET_DIR, dir)
            File(dir, ".ready").writeText(MODEL_VERSION)
            Log.w(TAG, "vosk model installed in ${dir.absolutePath}")
            return dir
        }

        private fun copyAssetTree(assets: AssetManager, assetPath: String, dest: File) {
            // `list()` returns an empty array for both files and empty dirs,
            // so probe the file first and treat FileNotFoundException as a
            // directory.
            try {
                assets.open(assetPath).use { input ->
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                return
            } catch (_: FileNotFoundException) {
                // Directory.
            }
            dest.mkdirs()
            for (child in assets.list(assetPath).orEmpty()) {
                copyAssetTree(assets, "$assetPath/$child", File(dest, child))
            }
        }

        /** Lowercase, strip accents and punctuation, collapse whitespace. */
        fun normalize(text: String): String {
            val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            val noMarks = buildString {
                for (c in decomposed) {
                    if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) append(c)
                }
            }
            val cleaned = buildString {
                for (c in noMarks) append(if (c.isLetterOrDigit()) c else ' ')
            }
            return cleaned.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        }

        private fun levenshtein(a: String, b: String, cap: Int): Int {
            if (abs(a.length - b.length) > cap) return cap + 1
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                    rowMin = min(rowMin, cur[j])
                }
                if (rowMin > cap) return cap + 1
                val tmp = prev
                prev = cur
                cur = tmp
            }
            return prev[b.length]
        }
    }

    private val target = normalize(phrase)
    private val targetWords = target.split(' ').filter { it.isNotEmpty() }

    /**
     * [T-orion-wake-strict] Edit budget for a near match.
     *
     * The previous values (3 for a long phrase, 2 for a medium one) plus a
     * ±2-char sliding window made the matcher accept almost any short Spanish
     * utterance: "oye orión" is nine characters, so any 7–11 character decode
     * within two edits fired the panel. That is why the assistant opened on
     * nearly every word the user said.
     */
    private val maxDistance = when {
        target.length >= 14 -> 2
        target.length >= 8 -> 1
        else -> 0
    }

    /** Minimum mean per-word acoustic confidence for an accepted decode. */
    private val minMeanConfidence = 0.75

    /** Nothing below this is a word Vosk should be trusted on. */
    private val minWordConfidence = 0.45

    private val model: Model = Model(installDir(context).absolutePath)
    private var recognizer: Recognizer = newRecognizer(phrase)
    @Volatile private var closed = false

    private fun newRecognizer(rawPhrase: String): Recognizer {
        // Build the grammar JSON by hand so we don't need the org.json jar in
        // the main source set (it's only a testImplementation dep). Escape the
        // phrase just in case the user typed quotes or backslashes.
        val escaped = rawPhrase.trim().lowercase()
            .replace("\\", "\\\\").replace("\"", "\\\"")
        val grammar = "[\"$escaped\", \"[unk]\"]"
        return try {
            Recognizer(model, SAMPLE_RATE.toFloat(), grammar)
        } catch (t: Throwable) {
            Log.w(TAG, "vosk grammar rejected (${t.message}), falling back to free decoding")
            Recognizer(model, SAMPLE_RATE.toFloat())
        }
    }

    /** Extracts a JSON string field without org.json (not a main dep). */
    private fun jsonStringField(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groupValues?.getOrNull(1) ?: ""
    }

    /** Feed one chunk of 16 kHz mono PCM16; true when the phrase was heard. */
    fun push(samples: ShortArray, length: Int): Boolean {
        if (length <= 0 || closed) return false
        val bytes = ByteArray(length * 2)
        for (i in 0 until length) {
            val v = samples[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        // The service closes the engine from onDestroy while the listener
        // coroutine may still be inside a blocking AudioRecord.read; without
        // this lock the native recognizer could be freed under a decode.
        synchronized(this) {
            if (closed) return false
            return try {
                if (recognizer.acceptWaveForm(bytes, bytes.size)) {
                    val result = recognizer.result
                    val text = jsonStringField(result, "text")
                    if (matches(text) && confident(result)) {
                        Log.i(TAG, "wake match: '${normalize(text)}' ${confidenceSummary(result)}")
                        recognizer.reset()
                        true
                    } else {
                        if (text.isNotBlank()) {
                            Log.d(TAG, "ignored '${normalize(text)}' ${confidenceSummary(result)}")
                        }
                        // A final segment that was not the phrase is ordinary
                        // speech: start a fresh segment so the next words are
                        // not glued onto it.
                        if (text.isNotBlank()) recognizer.reset()
                        false
                    }
                } else {
                    // [T-orion-wake-strict] Partials are NOT matched. Vosk
                    // rewrites the tail of a partial every frame, so matching
                    // them fired on fragments that later decoded differently —
                    // with the tiny [phrase, unk] grammar the fragment is often
                    // forced into the phrase itself. Only a final result is a
                    // transcription worth acting on.
                    false
                }
            } catch (t: Throwable) {
                Log.w(TAG, "vosk decode: ${t.message}")
                false
            }
        }
    }

    /** Mean and minimum per-word confidence of a Vosk result, or null if absent. */
    private fun confidences(json: String?): Pair<Double, Double>? {
        if (json.isNullOrBlank()) return null
        val values = Regex("\"conf\"\\s*:\\s*([0-9.]+)")
            .findAll(json)
            .mapNotNull { it.groupValues.getOrNull(1)?.toDoubleOrNull() }
            .toList()
        if (values.isEmpty()) return null
        return values.average() to (values.minOrNull() ?: 0.0)
    }

    private fun confidenceSummary(json: String?): String {
        val (mean, min) = confidences(json) ?: return "(no confidence)"
        return "conf mean=${"%.2f".format(mean)} min=${"%.2f".format(min)}"
    }

    /**
     * [T-orion-wake-strict] Reject decodes the acoustic model was not sure
     * about.
     *
     * With a two-entry grammar (`phrase`, `[unk]`) Vosk is forced to explain
     * every sound with one of them, so "did it recognise the phrase" is not a
     * useful signal by itself — a muttered sentence can decode as the phrase
     * with low confidence. The per-word `conf` values are what separate a real
     * "oye orión" from background speech.
     */
    private fun confident(json: String?): Boolean {
        val (mean, min) = confidences(json) ?: return true
        return mean >= minMeanConfidence && min >= minWordConfidence
    }

    /**
     * Visible for the WAV harness: does this transcript contain the phrase?
     *
     * [T-orion-wake-strict] Strict on purpose — an exact occurrence of the
     * phrase anywhere in the transcript ("oye orión abre whatsapp" still
     * counts), or the whole transcript within a one-to-two edit budget. The
     * sliding-window search is gone: it let any 7–11 character fragment match.
     */
    fun matches(rawText: String): Boolean {
        if (target.isEmpty()) return false
        val text = normalize(rawText)
        if (text.isEmpty()) return false
        if (text.contains(target)) return true
        if (targetWords.size < 2) return false
        return abs(text.length - target.length) <= 1 &&
            levenshtein(text, target, maxDistance) <= maxDistance
    }

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            runCatching { recognizer.close() }
            runCatching { model.close() }
        }
    }
}
