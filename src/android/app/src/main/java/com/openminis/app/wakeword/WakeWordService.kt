package com.openminis.app.wakeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.MainActivity
import com.openminis.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [T-orion-wake-word] Always-listening "Hey Jarvis" foreground service.
 *
 * Android only gives the OEM a true low-power hotword (that is why "Ok Google"
 * works with the screen off on a 1 %-an-hour budget). A third-party app has to
 * keep the microphone open and run a tiny model on the audio, so this service:
 *
 *  - shows an ongoing notification (required, and honest: the mic is on);
 *  - releases the microphone as soon as Orion itself is on screen, and while
 *    the phone is in a call (AudioRecord refuses to open);
 *  - asks the user to whitelist the app from battery optimisation, otherwise
 *    the OS stops the service after a few minutes;
 *  - does not restart itself after a reboot — Android 15 blocks starting a
 *    microphone foreground service from the background, so the app has to be
 *    opened once. Saying the wait word is worth that one tap.
 */
class WakeWordService : Service(), DefaultLifecycleObserver {

    companion object {
        private const val TAG = "OrionWakeWord"
        private const val CHANNEL_ID = "orion_wake_word"
        private const val NOTIFICATION_ID = 8420

        const val ACTION_START = "com.openminis.app.wakeword.START"
        const val ACTION_STOP = "com.openminis.app.wakeword.STOP"
        const val ACTION_TEST_WAV = "com.openminis.app.wakeword.TEST_WAV"

        private const val PREFS = "orion_wake_word"
        private const val KEY_ENABLED = "enabled"

        private const val RETRIGGER_MS = 4_000L

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun hasMic(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /** Start from the UI (or an app launch); a no-op when the toggle is off. */
        fun startIfEnabled(context: Context) {
            if (!isEnabled(context) || !hasMic(context)) return
            start(context)
        }

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                Log.w(TAG, "cannot start wake word service: ${t.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val pm = context.getSystemService(PowerManager::class.java) ?: return false
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var detector: OpenWakeWord? = null
    @Volatile private var paused = false
    @Volatile private var lastTrigger = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST_WAV -> {
                val path = intent.getStringExtra("wav")
                // Debug-only harness: score a WAV instead of the microphone so
                // the pipeline can be validated over adb without shouting at
                // the phone. Harmless in the field (the action is not exported).
                startForegroundNotification()
                scope.launch { runWavTest(path) }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForegroundNotification()
        if (!hasMic(this)) {
            Log.w(TAG, "RECORD_AUDIO not granted — stopping")
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) {
            job = scope.launch { listenLoop() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        paused = true
        job?.cancel()
        scope.cancel()
        runCatching { detector?.close() }
        detector = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── lifecycle: never listen while Orion is on screen ────────────────
    override fun onStart(owner: LifecycleOwner) {
        // The app is visible: it may capture audio for a voice message, and a
        // wake word firing on its own reply would loop.
        paused = true
        Log.d(TAG, "paused (app in foreground)")
    }

    override fun onStop(owner: LifecycleOwner) {
        paused = false
        Log.d(TAG, "resumed (app in background)")
    }

    // ── microphone loop ─────────────────────────────────────────────────
    private suspend fun listenLoop() {
        val buffer = ShortArray(1_280)
        while (scope.isActive) {
            if (paused) {
                delay(250)
                continue
            }
            if (detector == null) {
                detector = try {
                    OpenWakeWord(this)
                } catch (t: Throwable) {
                    Log.e(TAG, "detector init failed: ${t.message}", t)
                    delay(10_000)
                    continue
                }
            }
            val record = openRecord() ?: run {
                delay(5_000)
                continue
            }
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    delay(2_000)
                    continue
                }
                Log.i(TAG, "listening")
                var filled = 0
                while (scope.isActive && !paused) {
                    val n = record.read(buffer, filled, buffer.size - filled)
                    if (n <= 0) {
                        delay(50)
                        continue
                    }
                    filled += n
                    if (filled < buffer.size) continue
                    filled = 0
                    val score = detector?.push(buffer) ?: 0f
                    if (score >= OpenWakeWord.THRESHOLD) fire()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "mic loop: ${t.message}")
                delay(1_000)
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
            }
        }
    }

    private fun openRecord(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(16_000)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(min, 1_280 * 2 * 8))
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord unavailable: ${t.message}")
            null
        }
    }

    private fun fire() {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < RETRIGGER_MS) return
        lastTrigger = now
        Log.i(TAG, "wake word detected")
        paused = true
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_ASSIST)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot open Orion: ${t.message}")
            paused = false
        }
    }

    // ── debug: score a WAV through the same pipeline ────────────────────
    private suspend fun runWavTest(path: String?) {
        if (path.isNullOrBlank()) {
            Log.w(TAG, "TEST_WAV without path")
            return
        }
        try {
            val pcm = readWav(File(path))
            val detector = OpenWakeWord(this)
            val chunk = ShortArray(1_280)
            var best = 0f
            var bestAt = 0
            var i = 0
            while (i + chunk.size <= pcm.size) {
                System.arraycopy(pcm, i, chunk, 0, chunk.size)
                val s = detector.push(chunk)
                if (s > best) {
                    best = s
                    bestAt = i
                }
                if (s > 0.2f) Log.i(TAG, "TEST t=${"%.2f".format(i / 16_000f)}s score=${"%.3f".format(s)}")
                i += chunk.size
            }
            detector.close()
            Log.i(TAG, "TEST DONE max=${"%.3f".format(best)} at ${"%.2f".format(bestAt / 16_000f)}s")
            stopSelf()
        } catch (t: Throwable) {
            Log.e(TAG, "TEST failed: ${t.message}", t)
            stopSelf()
        }
    }

    /** Minimal RIFF reader for 16 kHz mono PCM16 WAV files. */
    private fun readWav(file: File): ShortArray {
        val bytes = file.readBytes()
        require(bytes.size > 44) { "too short" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.getInt(0) == 0x46464952) { "not RIFF" } // "RIFF"
        var pos = 12
        var dataAt = -1
        var dataLen = 0
        var channels = 1
        var rate = 16_000
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = buf.getInt(pos)
            val len = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                0x20746D66 -> { // "fmt "
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                0x61746164 -> { // "data"
                    dataAt = body
                    dataLen = len
                    break
                }
            }
            pos = body + len + (len and 1)
        }
        require(dataAt > 0) { "no data chunk" }
        require(channels == 1 && rate == 16_000 && bits == 16) {
            "expected 16 kHz mono PCM16, got $channels ch / $rate Hz / $bits bit"
        }
        val count = minOf(dataLen, bytes.size - dataAt) / 2
        val out = ShortArray(count)
        for (i in 0 until count) out[i] = buf.getShort(dataAt + i * 2)
        return out
    }

    // ── notification ────────────────────────────────────────────────────
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.orion_wake_word_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    private fun startForegroundNotification() {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.orion_wake_word_notification_title))
            .setContentText(getString(R.string.orion_wake_word_notification_body))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tap)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
