package com.openminis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.localmodel.LlamaServer
import com.openminis.app.localmodel.LocalServerStatus
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * [T-orion-local-model] Keeps the local llama.cpp server alive while Orion is
 * in the background.
 *
 * A loopback HTTP server owned by the app is still the app's process: once the
 * activity is gone the OS is free to freeze or reclaim it, and a model load
 * takes long enough that this used to happen mid-start. This service raises
 * the process priority, holds a partial wake lock so generation continues with
 * the screen off, and gives the user a notification with a stop action.
 *
 * `mediaPlayback` follows the same decision already made for
 * [AgentForegroundService]: `dataSync` has a 6 h/24 h cumulative cap on
 * Android 14+ and the system force-kills the process when it is reached, which
 * would be a crash in the middle of a conversation. A sideloaded app is not
 * bound by the Play policy about playing actual media.
 */
class LocalModelService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LlamaServer.stop(this)
            return START_NOT_STICKY
        }
        // startForegroundService() must be answered within ~5 s.
        goForeground()
        acquireWakeLock()
        if (observing == null) observing = observeStatus()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observing?.cancel()
        observing = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun observeStatus() = scope.launch {
        LlamaServer.status.collect { status ->
            when (status) {
                is LocalServerStatus.Starting -> {
                    notify(notification("Cargando ${shortName(status.modelPath)}…", loading = true))
                }
                is LocalServerStatus.Running -> {
                    notify(notification("${shortName(status.modelPath)} en marcha", loading = false))
                }
                is LocalServerStatus.Failed -> {
                    // Detach rather than remove: the foreground notification is
                    // replaced below by a plain, dismissible failure notice.
                    stopForegroundCompat(detach = true)
                    notify(
                        FINAL_NOTIFICATION_ID,
                        notification(
                            "El modelo no arrancó",
                            loading = false,
                            text = status.message.take(140),
                        ),
                    )
                    releaseWakeLock()
                    stopSelf()
                }
                LocalServerStatus.Stopped -> {
                    stopForegroundCompat()
                    releaseWakeLock()
                    stopSelf()
                }
            }
        }
    }

    private fun goForeground() {
        ensureChannel(this)
        val n = notification(
            when (val s = LlamaServer.status.value) {
                is LocalServerStatus.Running -> "${shortName(s.modelPath)} en marcha"
                is LocalServerStatus.Starting -> "Cargando ${shortName(s.modelPath)}…"
                else -> "Iniciando motor local…"
            },
            loading = LlamaServer.status.value !is LocalServerStatus.Running,
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        }.onFailure {
            AppLogger.error(TAG, "startForeground refused: ${it.message}")
            stopSelf()
        }
    }

    private fun notify(n: Notification) = notify(NOTIFICATION_ID, n)

    private fun notify(id: Int, n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun notification(
        title: String,
        loading: Boolean,
        text: String? = null,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LocalModelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_orion_notification)
            .setContentTitle(title)
            .setContentText(text ?: "Orion ejecuta el modelo en tu teléfono, sin conexión.")
            .setContentIntent(open)
            .setOngoing(loading)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Detener", stop)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = runCatching {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Orion:LocalModel").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun stopForegroundCompat(detach: Boolean = false) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(if (detach) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(!detach)
            }
        }
    }

    private fun shortName(path: String): String =
        path.substringAfterLast('/').removeSuffix(".gguf").take(48)

    companion object {
        private const val TAG = "OrionLocalModel"
        private const val CHANNEL_ID = "orion_local_model"
        private const val NOTIFICATION_ID = 0x0A11
        private const val FINAL_NOTIFICATION_ID = 0x0A12
        private const val ACTION_STOP = "com.openminis.app.action.STOP_LOCAL_MODEL"

        fun start(context: Context) {
            val intent = Intent(context, LocalModelService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { AppLogger.warning(TAG, "service start failed: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LocalModelService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Modelo local de Orion",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mantiene el modelo .gguf en marcha mientras hablas con Orion."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
