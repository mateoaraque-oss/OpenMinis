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
 * [T-orion-kokoro-tts] One-time download + extraction of the offline Kokoro
 * TTS voice model.
 *
 * Model: `kokoro-int8-multi-lang-v1_0` from the sherpa-onnx `tts-models`
 * release (Apache-2.0 code; the base model is from hexgrad/Kokoro-82M, apache
 * license as well). int8 quantization keeps it at ~126 MB packed / ~130 MB on
 * disk instead of the 334 MB of the fp32 build. It ships Spanish voices,
 * including a male one (`em_alex`, speaker id 29) which is what the JARVIS
 * greeting needs — no more "chica" voice.
 *
 * The archive is a tar.bz2 with:
 *  - model.int8.onnx   (~82 MB) the acoustic model
 *  - voices.bin        (~26 MB) the 53 speaker embeddings
 *  - tokens.txt        (~1 KB)  phoneme vocabulary
 *  - espeak-ng-data/   (~8 MB)  phonemization data for non-lexicon languages,
 *                               including Spanish
 *  - lexicon-us-en.txt, lexicon-zh.txt (grapheme→phoneme maps for en/zh)
 *
 * Downloaded via the system DownloadManager (survives process death, shows a
 * progress notification), then extracted to `files/kokoro/` and the archive
 * deleted. Everything lives in the app's private storage; nothing is in the
 * APK because ~130 MB cannot reasonably ship inside it — this is the one-time
 * "voice is included" step, after which all speech is fully offline.
 */
object KokoroModelDownloader {

    private const val TAG = "OrionKokoroDownload"
    private const val PREFS = "kokoro_model_download"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val ARCHIVE_NAME = "kokoro-int8-multi-lang-v1_0.tar.bz2"

    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" + ARCHIVE_NAME

    /** Files that must exist for the engine to load. */
    val REQUIRED_FILES = listOf(
        "model.int8.onnx",
        "voices.bin",
        "tokens.txt",
    )

    /** Where the extracted model lives: `files/kokoro/`. */
    fun assetsDir(context: Context): File = File(context.filesDir, "kokoro")

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
            archiveFile(context).length() > 50_000_000L // ~126 MB, sanity floor

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
     * Extract `Downloads/kokoro-int8-multi-lang-v1_0.tar.bz2` into
     * `files/kokoro/` and delete the archive. Returns true on success.
     */
    suspend fun extract(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled(context)) return@withContext true
        val src = archiveFile(context)
        if (!src.isFile) {
            AppLogger.error(TAG, "Kokoro archive missing: ${src.absolutePath}")
            return@withContext false
        }
        val dst = assetsDir(context)
        dst.mkdirs()
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(src))).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    // The archive wraps everything in a single root folder
                    // (kokoro-int8-multi-lang-v1_0/...); strip it so the model
                    // files land directly in files/kokoro/.
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
            val ok = isInstalled(context)
            AppLogger.info(TAG, "Kokoro extract finished ok=$ok")
            if (ok) {
                src.delete()
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_DOWNLOAD_ID).apply()
            }
            ok
        } catch (t: Throwable) {
            AppLogger.error(TAG, "Kokoro extract failed: ${t.message}")
            dst.deleteRecursively()
            false
        }
    }
}
