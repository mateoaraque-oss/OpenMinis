package com.openminis.app.localmodel

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * [T-orion-model-catalog] Downloads a catalog GGUF with the system
 * DownloadManager.
 *
 * Why DownloadManager and not an OkHttp loop: it survives the app being
 * backgrounded or killed, resumes after a network drop, shows the byte count
 * in the notification shade, and writes to a path we can hand straight to
 * `llama-server`. The alternative — a coroutine writing to a temp file — is
 * the usual reason "download your first model" fails on a phone that locks the
 * screen halfway through a 1 GB file.
 *
 * Destination is `getExternalFilesDir(null)/models`, which
 * [LocalModelScanner] already walks, so the file is visible to the picker the
 * moment the download completes without copying anything.
 */
object ModelDownloader {

    private const val TAG = "OrionModelDownload"
    private const val PREFS = "orion_model_download"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_MODEL_ID = "catalog_id"

    enum class Status { RUNNING, DONE, FAILED, CANCELLED }

    data class DownloadState(
        val downloadId: Long,
        val model: CatalogModel,
        val bytes: Long,
        val total: Long,
        val status: Status,
        val reason: Int = 0,
    ) {
        /** 0f..1f, or 0f when the server did not send a Content-Length. */
        val fraction: Float
            get() {
                if (total <= 0L) return 0f
                return (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            }
    }

    /** The models folder the downloader writes to and the scanner reads. */
    fun modelsDir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, "models") }

    fun destination(context: Context, model: CatalogModel): File? =
        modelsDir(context)?.let { File(it, model.fileName) }

    /**
     * Enqueue [model] unless an identical file is already complete.
     * Returns the DownloadManager id, or null when the enqueue failed.
     */
    fun start(context: Context, model: CatalogModel): Long? {
        // A half-written file from an earlier cancelled attempt would make the
        // scanner offer a corrupt model, so clear our own leftovers first.
        destination(context, model)?.takeIf { it.isFile && it.length() < model.sizeBytes }?.delete()
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_DOWNLOAD_ID)
                .remove(KEY_MODEL_ID)
                .apply()
        }
        return runCatching {
            // DownloadManager does not create missing parent folders.
            modelsDir(context)?.mkdirs()
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(model.url))
                .setTitle(context.getString(R.string.orion_model_download_title, model.displayName))
                .setDescription(context.getString(R.string.orion_model_download_body))
                .setMimeType("application/octet-stream")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                // null dirType = root of getExternalFilesDir(), which is
                // exactly the "models" folder LocalModelScanner walks.
                .setDestinationInExternalFilesDir(
                    context,
                    null,
                    "models/${model.fileName}",
                )
            val id = dm.enqueue(request)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_MODEL_ID, model.id)
                .apply()
            AppLogger.info(TAG, "enqueued ${model.id} as $id")
            id
        }.onFailure {
            AppLogger.warning(TAG, "enqueue failed for ${model.id}: ${it.message}")
        }.getOrNull()
    }

    /**
     * The persisted download, if any. Returns a DONE state as soon as the file
     * on disk has the advertised size, even before DownloadManager's status
     * poll catches up, so the UI can start the engine immediately.
     */
    fun status(context: Context): DownloadState? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id <= 0L) return null
        val model = prefs.getString(KEY_MODEL_ID, null)?.let(ModelCatalog::byId)
        if (model == null) {
            clear(context)
            return null
        }

        val file = destination(context, model)
        if (file != null && file.isFile && file.length() >= model.sizeBytes * 9 / 10) {
            return DownloadState(
                downloadId = id,
                model = model,
                bytes = file.length(),
                total = model.sizeBytes,
                status = Status.DONE,
            )
        }

        return runCatching {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) {
                    // The download row is gone: either the user cleared it or
                    // the system dropped it. Treat it as cancelled so the card
                    // goes back to offering a fresh download.
                    return@use DownloadState(
                        downloadId = id,
                        model = model,
                        bytes = 0L,
                        total = model.sizeBytes,
                        status = Status.CANCELLED,
                    )
                }
                val bytes = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                )
                val total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                ).takeIf { it > 0L } ?: model.sizeBytes
                val statusCode = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
                )
                val reason = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                )
                val status = when (statusCode) {
                    DownloadManager.STATUS_SUCCESSFUL -> Status.DONE
                    DownloadManager.STATUS_FAILED -> Status.FAILED
                    DownloadManager.STATUS_PAUSED -> Status.RUNNING
                    else -> Status.RUNNING
                }
                DownloadState(id, model, bytes, total, status, reason)
            }
        }.getOrElse {
            AppLogger.warning(TAG, "status query failed: ${it.message}")
            null
        }
    }

    fun cancel(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id > 0L) {
            runCatching {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.remove(id)
            }
        }
        clear(context)
    }

    /** Forget the persisted id. Does NOT delete the downloaded file. */
    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_DOWNLOAD_ID)
                .remove(KEY_MODEL_ID)
                .apply()
        }
    }

    /** Delete a catalog model's file (used by the "free space" action). */
    fun delete(context: Context, model: CatalogModel) {
        runCatching { destination(context, model)?.delete() }
    }
}
