package com.openminis.app.localmodel

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.Locale

/**
 * [T-orion-local-model] One `.gguf` on the device, described well enough for
 * the picker to be useful without opening the file.
 *
 * The app does not need to parse the GGUF header to run a model: llama-server
 * reads the metadata itself. What the UI wants is the shape a person
 * recognises — parameter count and quantisation — and both are part of the
 * filename in every release worth downloading. [quantization] and
 * [parameterHint] are therefore best-effort filename parses, never a promise.
 */
data class LocalModelFile(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val quantization: String?,
    val parameterHint: String?,
) {
    val path: String get() = file.absolutePath

    /** e.g. "Qwen2.5 1.5B · Q4_K_M · 1.1 GB". */
    fun summary(): String {
        val bits = buildList {
            parameterHint?.let { add(it) }
            quantization?.let { add(it) }
            add(formatSize(sizeBytes))
        }
        return bits.joinToString(" · ")
    }

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "?"
            val gb = bytes / 1_073_741_824.0
            if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
            val mb = bytes / 1_048_576.0
            return String.format(Locale.US, "%.0f MB", mb)
        }
    }
}

/**
 * [T-orion-local-model] Finds the `.gguf` files already on the phone.
 *
 * Why a folder scan and not the Storage Access Framework: llama-server is a
 * native process. It opens a PATH, and a SAF `content://` URI is not one. The
 * app already holds `MANAGE_EXTERNAL_STORAGE` for its Linux sandbox, so a
 * direct read is both possible and the only shape that works.
 *
 * The scan is deliberately narrow. Walking all of `/sdcard` on a phone with
 * years of WhatsApp media takes seconds and finds nothing; the model the user
 * downloaded lives in Download, Documents or a folder they named after it.
 * Depth 4 covers `Download/models/qwen/file.gguf`, which is the deepest layout
 * people actually use.
 */
object LocalModelScanner {

    private const val MAX_DEPTH = 4
    private const val MAX_RESULTS = 300

    /** Folders worth descending into, in the order a person would guess. */
    private val SHALLOW_DIRS = listOf(
        "Download",
        "Downloads",
        "Documents",
        "Models",
        "models",
        "LLM",
        "llm",
        "AI",
        "Orion",
    )

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    /**
     * Every `.gguf` this app can see right now.
     *
     * Sources, in order:
     *  - the app's own private model folder (`files/models`), so a user who
     *    copied a model into Orion finds it even without the all-files grant;
     *  - the app's external files folder, which file managers label
     *    `Android/data/com.openminis.app/files/models`;
     *  - the shared-storage folders in [SHALLOW_DIRS];
     *  - the root of shared storage, non-recursively.
     */
    fun scan(context: Context): List<LocalModelFile> {
        val found = LinkedHashMap<String, LocalModelFile>()

        fun consider(f: File) {
            if (found.size >= MAX_RESULTS) return
            if (!f.isFile) return
            val name = f.name
            if (!name.endsWith(".gguf", ignoreCase = true)) return
            if (name.endsWith(".gguf.part", ignoreCase = true)) return
            if (name.startsWith(".")) return
            found[f.absolutePath] = describe(f)
        }

        fun walk(dir: File?, depth: Int) {
            if (dir == null || !dir.isDirectory || !dir.canRead()) return
            if (found.size >= MAX_RESULTS) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                if (found.size >= MAX_RESULTS) return
                if (child.isDirectory) {
                    if (depth >= MAX_DEPTH) continue
                    if (child.name.startsWith(".")) continue
                    // Skip the two trees that are either media noise or
                    // off-limits to MANAGE_EXTERNAL_STORAGE.
                    if (child.name == "Android") continue
                    if (child.name == "DCIM" || child.name == "Movies") continue
                    walk(child, depth + 1)
                } else {
                    consider(child)
                }
            }
        }

        // Private folders first: they work without any permission.
        walk(File(context.filesDir, "models"), 0)
        walk(context.getExternalFilesDir("models"), 0)

        val shared = Environment.getExternalStorageDirectory()
        // Root: direct files only. Descending from here would walk Music,
        // WhatsApp, Telegram... and never find a model.
        runCatching { shared.listFiles() }
            .getOrNull()
            ?.filter { it.isFile }
            ?.forEach { consider(it) }
        for (name in SHALLOW_DIRS) {
            walk(File(shared, name), 0)
        }

        return found.values.sortedWith(
            compareByDescending<LocalModelFile> { it.sizeBytes }
                .thenBy { it.displayName.lowercase(Locale.ROOT) },
        )
    }

    /**
     * Resolve a path the user typed. Returns null when it is not a readable
     * `.gguf`, so the screen can say so instead of spawning a process that
     * dies on an unpackable file.
     */
    fun fromPath(path: String): LocalModelFile? {
        val file = File(path.trim())
        if (!file.isFile || !file.canRead()) return null
        if (!file.name.endsWith(".gguf", ignoreCase = true)) return null
        return describe(file)
    }

    private fun describe(file: File): LocalModelFile {
        val lower = file.name.lowercase(Locale.ROOT)
        val quant = Regex(
            """(iq\d[a-z0-9_]*|q\d[a-z0-9_]*|bf16|f16|fp16|f32)""",
        ).find(lower)?.value?.uppercase(Locale.ROOT)
        val params = Regex("""(?:^|[^a-z0-9])(\d+(?:\.\d+)?)b(?:[^a-z0-9]|$)""")
            .find(lower)?.groupValues?.getOrNull(1)?.let { "${it}B" }
        return LocalModelFile(
            file = file,
            displayName = prettyName(file.name),
            sizeBytes = runCatching { file.length() }.getOrDefault(0L),
            quantization = quant,
            parameterHint = params,
        )
    }

    /** "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf" -> "Qwen2.5 1.5B Instruct Q4 K M". */
    private fun prettyName(fileName: String): String {
        val base = fileName.removeSuffix(".GGUF").removeSuffix(".gguf")
        return base.replace('_', ' ').replace('-', ' ').replace('.', ' ')
            .split(' ').filter { it.isNotBlank() }.joinToString(" ")
    }
}
