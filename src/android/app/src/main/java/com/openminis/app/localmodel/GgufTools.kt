package com.openminis.app.localmodel

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * [T-orion-local-tools] Does this `.gguf` declare tool-calling support?
 *
 * Orion gives on-device models the same phone-control tools a cloud model
 * gets. llama.cpp applies the model's own Jinja chat template, and when a
 * template has no `tools` handling the server refuses the whole request
 * ("This model does not support tools") �? so exposing the schemas to a
 * model that cannot use them breaks the chat instead of enabling it.
 *
 * The GGUF header carries the template verbatim under the
 * `tokenizer.chat_template` key, so the answer comes from the file itself and
 * not from a filename guess: Qwen 2.5 and Llama 3.x ship a template that
 * starts with `{%- if tools %}`, Gemma 3 ships one that does not.
 *
 * The template key lives in the metadata block, which for a 1-3B model sits
 * a few megabytes in (after the tokenizer). [MAX_SCAN_BYTES] covers that with
 * room to spare while keeping the one-time read cheap; anything beyond it is
 * reported as unknown rather than risking a full-file scan of the weights.
 */
object GgufTools {

    private const val KEY = "tokenizer.chat_template"
    private const val MAX_SCAN_BYTES = 64L * 1024 * 1024
    private const val MAX_TEMPLATE_BYTES = 1 * 1024 * 1024
    private const val CHUNK = 1 shl 20

    /** Cached per file version; null means "could not tell". */
    private val cache = HashMap<String, Boolean?>()

    fun chatTemplateSupportsTools(file: File): Boolean? {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val result = runCatching { readChatTemplate(file)?.let(::templateSupportsTools) }
            .getOrNull()
        synchronized(cache) { cache[key] = result }
        return result
    }

    /**
     * True only when the template gates on `tools`; templates that merely
     * mention the word in prose are rejected by requiring the Jinja tag.
     */
    internal fun templateSupportsTools(template: String): Boolean =
        Regex("""\{%-?\s*if\s+tools\b""").containsMatchIn(template)

    private fun readChatTemplate(file: File): String? {
        if (!file.isFile || !file.canRead()) return null
        val offset = findKeyOffset(file) ?: return null
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset + KEY.length)
            if (readU32(raf) != 8L) return null // not a string value
            val length = readU64(raf)
            if (length <= 0 || length > MAX_TEMPLATE_BYTES) return null
            val bytes = ByteArray(length.toInt())
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            // Sanity check: a chat template is Jinja, not binary noise.
            return text.takeIf { it.contains("{{") || it.contains("{%") }
        }
    }

    /**
     * Byte offset of the `tokenizer.chat_template` key inside the metadata
     * block. The key is stored as length-prefixed ASCII, followed by a
     * uint32 value type and the value itself; the caller reads from the end
     * of the key.
     */
    private fun findKeyOffset(file: File): Long? {
        val needle = KEY.toByteArray(Charsets.US_ASCII)
        val limit = minOf(file.length(), MAX_SCAN_BYTES)
        if (file.length() < 32) return null
        FileInputStream(file).use { input ->
            val buffer = ByteArray(CHUNK + needle.size)
            var filled = 0
            var readTotal = 0L
            while (readTotal < limit) {
                val n = input.read(buffer, filled, CHUNK)
                if (n <= 0) break
                readTotal += n
                val available = filled + n
                val last = available - needle.size
                var i = 0
                while (i <= last) {
                    if (matchesAt(buffer, i, needle)) {
                        return readTotal - available + i
                    }
                    i++
                }
                val keep = minOf(needle.size - 1, available)
                System.arraycopy(buffer, available - keep, buffer, 0, keep)
                filled = keep
            }
        }
        return null
    }

    private fun matchesAt(buffer: ByteArray, offset: Int, needle: ByteArray): Boolean {
        for (j in needle.indices) {
            if (buffer[offset + j] != needle[j]) return false
        }
        return true
    }

    private fun readU32(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
    }

    private fun readU64(raf: RandomAccessFile): Long {
        val lo = readU32(raf)
        val hi = readU32(raf)
        return lo or (hi shl 32)
    }
}
