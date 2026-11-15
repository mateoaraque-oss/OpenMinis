package com.openminis.app.speech

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream

/**
 * [T-orion-kokoro-tts] One-time download + extraction of the offline TTS voice
 * model.
 *
 * Model: `vits-piper-es_ES-miro-high-int8` from the sherpa-onnx `tts-models`
 * release (MIT; piper voices are trained on public-domain data). A real male
 * Spanish voice from Spain — the JARVIS greeting needs a natural "oye orión"
 * reply, not a "chica" voice. int8 keeps the download at ~21 MB instead of the
 * ~67 MB of the fp32 build.
 *
 * Piper was chosen over Kokoro after Kokoro proved unusable for Spanish:
 * sherpa-onnx only phonemizes English and Chinese for kokoro-multi-lang-v1_0,
 * so Spanish text fell through to espeak-ng's default voice and came out with
 * a French "r". Piper ships full espeak-ng data for its language.
 *
 * The archive is a tar.bz2 with:
 *  - es_ES-miro-high.onnx (~60 MB fp32 → ~21 MB int8) the acoustic model
 *  - tokens.txt           (~1 KB)  phoneme vocabulary
 *  - espeak-ng-data/      (~8 MB)  phonemization data for Spanish
 *
 * Downloaded via the system DownloadManager (survives process death, shows a
 * progress notification), then extracted to `files/piper/` and the archive
 * deleted. Everything lives in the app's private storage; nothing is in the
 * APK because the model cannot reasonably ship inside it — this is the
 * one-time "voice is included" step, after which all speech is fully offline.
 */
object KokoroModelDownloader {

    private const val TAG = "OrionKokoroDownload"
    // New prefs name: the old "kokoro_model_download" prefs still point at the
    // finished Kokoro download id, which would report NEEDS_EXTRACT forever.
    private const val PREFS = "piper_model_download"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val ARCHIVE_NAME = "vits-piper-es_ES-miro-high-int8.tar.bz2"

    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" + ARCHIVE_NAME

    /** Archive shipped inside the APK (single-download installs). */
    private const val ASSET_ARCHIVE = "piper-es_ES-miro-high-int8.tar.bz2"

    /** Files that must exist for the engine to load. */
    val REQUIRED_FILES = listOf(
        "es_ES-miro-high.onnx",
        "tokens.txt",
    )

    /** Where the extracted model lives: `files/piper/`. */
    fun assetsDir(context: Context): File = File(context.filesDir, "piper")

    private fun archiveFile(context: Context): File =
        File(
            context.getExternalFilesDir(null),
            ARCHIVE_NAME,
        )

    /** True when the model is downloaded AND extracted. */
    fun isInstalled(context: Context): Boolean {
        val dir = assetsDir(context)
        val espeak = File(dir, "espeak-ng-data")
        return REQUIRED_FILES.all { File(dir, it).isFile } &&
            espeak.isDirectory && espeak.listFiles()?.isNotEmpty() == true
    }

    /** True when an archive has landed but not been extracted yet. */
    fun isArchiveReady(context: Context): Boolean =
        !isInstalled(context) && archiveFile(context).isFile &&
            archiveFile(context).length() > 10_000_000L // ~21 MB int8, sanity floor

    /** True when this APK bundles the voice model in its assets. */
    fun hasBundledModel(context: Context): Boolean = runCatching {
        context.assets.open(ASSET_ARCHIVE).close()
        true
    }.getOrDefault(false)

    fun status(context: Context): Status {
        if (isInstalled(context)) return Status.READY
        if (isArchiveReady(context)) return Status.NEEDS_EXTRACT
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_DOWNLOAD_ID, -1L)
        if (id < 0) return Status.IDLE
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor: Cursor? = dm.query(DownloadManager.Query().setFilterById(id))
        cursor?.use {
            if (it.moveToFirst()) {
                when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> return Status.NEEDS_EXTRACT
                    DownloadManager.STATUS_FAILED -> return Status.FAILED
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED,
                    -> return Status.DOWNLOADING
                }
            }
        }
        return Status.IDLE
    }

    enum class Status {
        IDLE, DOWNLOADING, NEEDS_EXTRACT, READY, FAILED,
    }

    /**
     * Enqueue the model download via DownloadManager. Visible notification so
     * the user sees the one-time ~130 MB voice download. Returns the id.
     */
    fun startDownload(context: Context): Long {
        val request = DownloadManager.Request(Uri.parse(MODEL_URL)).apply {
            setTitle(context.getString(R.string.kokoro_model_download_title))
            setDescription(context.getString(R.string.kokoro_model_download_body))
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            // App-private external dir: always readable without storage
            // permissions, and deleted right after extraction.
            setDestinationInExternalFilesDir(context, null, ARCHIVE_NAME)
            setAllowedOverMetered(true)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        AppLogger.info(TAG, "Kokoro model download enqueued id=$id")
        return id
    }

    /**
     * Extract the downloaded piper tar.bz2 into `files/piper/` and delete the
     * archive. Returns true on success.
     */
    suspend fun extract(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled(context)) return@withContext true
        val src = archiveFile(context)
        if (!src.isFile) {
            AppLogger.error(TAG, "Piper archive missing: ${src.absolutePath}")
            return@withContext false
        }
        val dst = assetsDir(context)
        try {
            FileInputStream(src).use { extractTar(it, dst) }
            val ok = isInstalled(context)
            AppLogger.info(TAG, "Piper extract finished ok=$ok")
            if (ok) {
                src.delete()
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_DOWNLOAD_ID).apply()
            }
            ok
        } catch (t: Throwable) {
            AppLogger.error(TAG, "Piper extract failed: ${t.message}")
            dst.deleteRecursively()
            false
        }
    }

    /**
     * [T-orion-piper-tts] Unpack the archive bundled in the APK assets into
     * `files/piper/` — the single-download install path: a fresh install
     * speaks with the Orion voice after one APK download, no second fetch.
     * One-time cost is seconds of bz2 I/O. Returns true on success.
     */
    suspend fun installFromAssets(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled(context)) return@withContext true
        val dst = assetsDir(context)
        try {
            context.assets.open(ASSET_ARCHIVE).use { extractTar(it, dst) }
            val ok = isInstalled(context)
            AppLogger.info(TAG, "Piper asset unpack finished ok=$ok")
            if (!ok) dst.deleteRecursively()
            ok
        } catch (t: Throwable) {
            AppLogger.error(TAG, "Piper asset unpack failed: ${t.message}")
            dst.deleteRecursively()
            false
        }
    }

    /** Shared tar.bz2 → [dst] unpack; strips the archive's root folder. */
    private fun extractTar(raw: java.io.InputStream, dst: File) {
        dst.mkdirs()
        TarArchiveInputStream(BZip2CompressorInputStream(raw)).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                // The archive wraps everything in a single root folder
                // (vits-piper-es_ES-miro-high/...); strip it so the model
                // files land directly in files/piper/.
                val relative = entry.name.removePrefix("./").let {
                    val i = it.indexOf('/')
                    when {
                        i < 0 -> it
                        i == it.length - 1 -> "" // the root dir entry itself
                        else -> it.substring(i + 1)
                    }
                }
                if (relative.isBlank()) {
                    entry = tar.nextTarEntry
                    continue
                }
                val out = File(dst, relative)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
                entry = tar.nextTarEntry
            }
        }
    }
}
