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
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.MainActivity
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.speech.TextToSpeechManager
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.speech.VoiceReplyFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [T-orion-wake-word] Always-listening wake phrase foreground service.
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
 *    opened once. Saying the wake phrase is worth that one tap.
 *
 * [T-orion-custom-wake] The phrase is configurable. Vosk decodes the mic with
 * a grammar restricted to the user's phrase (see [VoskWakeWord]), so any
 * Spanish sentence works with no account, key or training. If the bundled
 * model is missing the service falls back to the older fixed "Hey Jarvis"
 * detector so an update can never leave the user without a wake word.
 *
 * [T-orion-wake-panel] On a match the service shows a translucent bar over the
 * current app (like Gemini), captures the command with the system recognizer,
 * and hands the transcript to a fresh Orion chat. When the overlay permission
 * or the speech recognizer is unavailable it falls back to the full-screen
 * ACTION_ASSIST voice flow.
 */
class WakeWordService : Service(), LifecycleEventObserver {

    companion object {
        private const val TAG = "OrionWakeWord"
        private const val CHANNEL_ID = "orion_wake_word"
        private const val NOTIFICATION_ID = 8420

        const val ACTION_START = "com.openminis.app.wakeword.START"
        const val ACTION_STOP = "com.openminis.app.wakeword.STOP"
        const val ACTION_TEST_WAV = "com.openminis.app.wakeword.TEST_WAV"
        const val ACTION_TEST_PANEL = "com.openminis.app.wakeword.TEST_PANEL"
        const val EXTRA_TEXT = "text"

        private const val PREFS = "orion_wake_word"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PHRASE = "phrase"
        private const val KEY_VOICE_REPLIES = "voice_replies"

        /**
         * [T-orion-voice-actions] When true (default) a wake-phrase command is
         * executed straight from the panel — no chat screen — and only the
         * final answer is spoken. When false the old behaviour applies: the
         * transcript is handed to a full voice chat and the user keeps talking
         * there.
         */
        private const val KEY_VOICE_ACTIONS = "voice_actions"

        /** Session the voice commands accumulate in, so follow-ups have context. */
        private const val KEY_VOICE_SESSION = "voice_session_id"

        /**
         * [T-orion-voice-actions] Ceiling for one headless voice turn. Generous:
         * a command may open apps, tap through screens or wait on the network,
         * and cutting it at the first pause would tell the user it failed while
         * it was still working.
         */
        private const val VOICE_TURN_TIMEOUT_MS = 240_000L

        const val DEFAULT_PHRASE = "oye orión"

        private const val RETRIGGER_MS = 6_000L
        private const val PANEL_TIMEOUT_MS = 12_000L

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        /** The phrase the user typed in Settings; never blank. */
        fun getPhrase(context: Context): String =
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_PHRASE, DEFAULT_PHRASE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_PHRASE

        /**
         * Persist a new phrase. The caller restarts the service with [restart]
         * so the Vosk grammar is rebuilt; the old recognizer is bound to the
         * old phrase.
         */
        fun setPhrase(context: Context, phrase: String) {
            val clean = phrase.trim().replace(Regex("\\s+"), " ")
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(KEY_PHRASE, clean.ifEmpty { DEFAULT_PHRASE }).apply()
        }

        /**
         * Whether Orion should read its answers out loud after a wake-phrase
         * invocation. Default true: the whole point of invoking by voice is a
         * spoken answer. Users who prefer silent replies can turn it off in
         * Settings, which leaves the app's own "Read replies" setting intact.
         */
        fun speaksReplies(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_VOICE_REPLIES, true)

        fun setSpeaksReplies(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_VOICE_REPLIES, enabled).apply()
        }

        /**
         * [T-orion-voice-actions] Whether a wake-phrase command should be carried
         * out on the panel instead of opening the chat screen. Default true:
         * the user asked for a floating assistant that acts and reports back.
         */
        fun runsActionsByVoice(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_VOICE_ACTIONS, true)

        fun setRunsActionsByVoice(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_VOICE_ACTIONS, enabled).apply()
        }

        /** Stop and start the service so the detector picks up a new phrase. */
        fun restart(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({
                if (isEnabled(context) && hasMic(context)) start(context)
            }, 400)
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
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var vosk: VoskWakeWord? = null
    private var oww: OpenWakeWord? = null
    @Volatile private var paused = false
    @Volatile private var lastTrigger = 0L

    private var panel: WakePanelController? = null
    private var speech: SpeechRecognizer? = null
    private val panelTimeout = Runnable { closePanel(resume = true) }

    /**
     * [T-orion-voice-actions] Engine used to speak the answer of a panel
     * command. Separate from the chat's read-aloud player: the panel has no
     * ChatScreen behind it, so nothing else would ever speak the reply.
     */
    private var replyTts: TextToSpeechManager? = null

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
                //
                // [T-orion-wake-fgs-guard] No startForeground here: the harness
                // never opens the mic, and promoting to a microphone foreground
                // service from a debug `am startservice` is exactly the call
                // Android 15 rejects with a SecurityException. It used to take
                // the whole process down before a single WAV was decoded.
                scope.launch { runWavTest(path) }
                return START_NOT_STICKY
            }
            ACTION_TEST_PANEL -> {
                // Debug-only: `text` skips the microphone and exercises the
                // transcript -> pending prompt -> new chat bridge.
                val text = intent.getStringExtra(EXTRA_TEXT)
                main.post {
                    if (text.isNullOrBlank()) openWakePanel() else deliverPrompt(text)
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!startForegroundNotification()) {
            Log.w(TAG, "stopping: microphone foreground service not allowed right now")
            stopSelf()
            return START_NOT_STICKY
        }
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
        job = null
        main.removeCallbacks(panelTimeout)
        runCatching { closePanel(resume = false) }
        runCatching { replyTts?.shutdown() }
        replyTts = null
        scope.cancel()
        runCatching { vosk?.close() }
        runCatching { oww?.close() }
        vosk = null
        oww = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── lifecycle: never listen while Orion is on screen ────────────────
    //
    // LifecycleEventObserver rather than DefaultLifecycleObserver: the latter
    // also declares onCreate/onDestroy, which makes a plain `super.onCreate()`
    // ambiguous inside a Service.
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> {
                // The app is visible: it may capture audio for a voice message,
                // and a wake word firing on its own reply would loop.
                paused = true
                if (panel?.isShowing == true) main.post { closePanel(resume = false) }
                Log.w(TAG, "paused (app in foreground)")
            }
            Lifecycle.Event.ON_STOP -> {
                paused = false
                Log.w(TAG, "resumed (app in background)")
            }
            else -> Unit
        }
    }

    // ── microphone loop ─────────────────────────────────────────────────
    private suspend fun listenLoop() {
        val buffer = ShortArray(1_280)
        while (scope.isActive) {
            if (paused) {
                delay(250)
                continue
            }
            if (!ensureEngine()) {
                delay(10_000)
                continue
            }
            val record = openRecord()
            if (record == null) {
                // Mic busy (call, recorder) or refused: retry without spinning.
                delay(5_000)
                continue
            }
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    delay(2_000)
                    continue
                }
                Log.w(TAG, "listening (${engineName()}, phrase=\"${getPhrase(this)}\")")
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
                    if (detect(buffer)) {
                        fire()
                        // Leave the inner loop so `finally` releases the mic
                        // before the panel's recognizer opens it again.
                        break
                    }
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

    /**
     * Build the detector for the current phrase. Vosk is preferred; the old
     * openWakeWord model stays as a fallback when the bundled Vosk asset is
     * missing (for example a build without the CI asset step).
     */
    private suspend fun ensureEngine(): Boolean {
        if (vosk != null || oww != null) return true
        val phrase = getPhrase(this)

        if (!VoskWakeWord.isReady(this)) {
            Log.w(TAG, "installing Vosk model (${phrase})…")
            runCatching { VoskWakeWord.ensureInstalled(this) }
                .onFailure { Log.w(TAG, "Vosk model install failed: ${it.message}") }
        }
        if (VoskWakeWord.isReady(this)) {
            try {
                vosk = VoskWakeWord(this, phrase)
                Log.w(TAG, "engine=vosk phrase=\"$phrase\"")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "Vosk init failed: ${t.message}; falling back to openWakeWord")
            }
        }

        return try {
            oww = OpenWakeWord(this)
            Log.w(TAG, "engine=openWakeWord (built-in \"Hey Jarvis\")")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "detector init failed: ${t.message}", t)
            false
        }
    }

    private fun engineName(): String = when {
        vosk != null -> "vosk"
        oww != null -> "openWakeWord"
        else -> "none"
    }

    /** One 80 ms chunk through whichever engine is active. */
    private fun detect(buffer: ShortArray): Boolean {
        vosk?.let { return it.push(buffer, buffer.size) }
        val score = oww?.push(buffer) ?: 0f
        return score >= OpenWakeWord.THRESHOLD
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
                .setBufferSizeInBytes(if (min > 1_280 * 2 * 8) min else 1_280 * 2 * 8)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord unavailable: ${t.message}")
            null
        }
    }

    // ── wake: panel over the current app (Gemini style) ─────────────────
    private fun fire() {
        // [T-orion-wake-strict] Never stack panels: while the previous command
        // is still being heard, answered or executed, another match is either
        // an echo of Orion itself or background speech.
        if (panel?.isShowing == true) return
        val now = System.currentTimeMillis()
        if (now - lastTrigger < RETRIGGER_MS) return
        lastTrigger = now
        Log.w(TAG, "wake phrase detected")
        paused = true
        // Audible acknowledgement, JARVIS-style two-note chime. With the
        // screen off the overlay is not visible, so this is the only way to
        // tell the user the phrase landed.
        playWakeChime()
        main.post { openWakePanel() }
    }

    /**
     * Short acknowledgement tone. Kept separate from TTS: speaking a greeting
     * into the microphone while the recognizer is starting would be decoded as
     * the user's command. ToneGenerator runs on its own stream and is released
     * right away.
     */
    private fun playWakeChime() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 160)
            main.postDelayed({ runCatching { tone.release() } }, 600)
        } catch (t: Throwable) {
            Log.w(TAG, "wake chime unavailable: ${t.message}")
        }
    }

    private fun openWakePanel() {
        val canOverlay = Settings.canDrawOverlays(this)
        val canRecognize = SpeechRecognizer.isRecognitionAvailable(this)
        if (!canOverlay || !canRecognize) {
            Log.w(TAG, "panel unavailable (overlay=$canOverlay asr=$canRecognize); opening voice chat")
            openAssistActivity()
            return
        }
        val controller = panel ?: WakePanelController(this).also { panel = it }
        controller.onClose = { closePanel(resume = true) }
        controller.show(
            getString(R.string.orion_wake_panel_title),
            getString(R.string.orion_wake_panel_hint),
        )
        controller.setState(WakePanelController.State.LISTENING)
        startPanelSpeech()
        main.removeCallbacks(panelTimeout)
        main.postDelayed(panelTimeout, PANEL_TIMEOUT_MS)
    }

    private fun startPanelSpeech() {
        destroySpeech()
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot create SpeechRecognizer: ${t.message}")
            closePanel(resume = false)
            openAssistActivity()
            return
        }
        speech = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                panel?.setState(WakePanelController.State.LISTENING)
                panel?.setStatus(getString(R.string.orion_wake_panel_listening))
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB arrives roughly in [-2, 10]; map it to 0..1 for the
                // waveform so quiet rooms still show a live, low bar.
                panel?.setAmplitude(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                panel?.setState(WakePanelController.State.PROCESSING)
                panel?.setStatus(getString(R.string.orion_wake_panel_processing))
            }

            override fun onError(error: Int) {
                Log.w(TAG, "panel speech error $error")
                main.removeCallbacks(panelTimeout)
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    panel?.setState(WakePanelController.State.ERROR)
                    panel?.setStatus(getString(R.string.orion_wake_panel_no_match))
                    main.postDelayed({ closePanel(resume = true) }, 900)
                } else {
                    // [T-orion-wake-strict] Was: close the panel and open the
                    // full-screen assistant. With false wake-ups that turned
                    // every stray word into the assistant menu. A recogniser
                    // hiccup is not a reason to take over the screen; report it
                    // on the panel and go back to listening.
                    panel?.setState(WakePanelController.State.ERROR)
                    panel?.setStatus(getString(R.string.orion_wake_panel_no_match))
                    main.postDelayed({ closePanel(resume = true) }, 900)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) {
                    deliverPrompt(text)
                } else {
                    onError(SpeechRecognizer.ERROR_NO_MATCH)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) {
                    main.removeCallbacks(panelTimeout)
                    panel?.setStatus(getString(R.string.orion_wake_panel_heard))
                    panel?.setTranscript(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
        }
        try {
            recognizer.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "startListening failed: ${t.message}")
            panel?.setState(WakePanelController.State.ERROR)
            panel?.setStatus(getString(R.string.orion_wake_panel_no_match))
            main.postDelayed({ closePanel(resume = true) }, 900)
        }
    }

    /** Transcript ready: execute it on the panel, or open a chat when asked to. */
    private fun deliverPrompt(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        Log.w(TAG, "panel transcript: ${clean.take(80)}")
        main.removeCallbacks(panelTimeout)
        destroySpeech()
        if (runsActionsByVoice(this)) {
            runVoiceCommand(clean)
        } else {
            openChatWithPrompt(clean)
        }
    }

    /**
     * [T-orion-voice-actions] Carry the command out in the background and report
     * the outcome out loud.
     *
     * This is the difference the user asked for: the panel no longer hands the
     * transcript to a chat screen, so Orion performs the action over whatever
     * app is in front and answers when it is done. Only the final assistant
     * message is spoken ([VoiceReplyFormatter] strips code, links and status
     * chatter, and the tool activity never reaches the engine at all).
     */
    private fun runVoiceCommand(prompt: String) {
        val app = applicationContext as? MinisApp
        if (app == null) {
            Log.w(TAG, "MinisApp missing — falling back to the chat screen")
            openChatWithPrompt(prompt)
            return
        }
        paused = true
        panel?.setState(WakePanelController.State.PROCESSING)
        panel?.setStatus(getString(R.string.orion_wake_panel_working))
        scope.launch {
            val sessionId = ensureVoiceSession(app)
            if (sessionId == null) {
                Log.w(TAG, "no provider for voice commands — falling back to the chat screen")
                main.post { openChatWithPrompt(prompt) }
                return@launch
            }
            val started = System.currentTimeMillis()
            val result = runCatching {
                HeadlessChatRunner.prompt(
                    context = this@WakeWordService,
                    sessionId = sessionId,
                    text = prompt,
                    wait = true,
                    timeoutMs = VOICE_TURN_TIMEOUT_MS,
                )
            }.getOrNull()
            val reply = VoiceReplyFormatter.forSpeech(result?.responseText)
            Log.w(
                TAG,
                "voice command done status=${result?.status} timedOut=${result?.timedOut} " +
                    "chars=${reply.length} in ${System.currentTimeMillis() - started}ms",
            )
            if (reply.isEmpty()) {
                main.post {
                    panel?.setState(WakePanelController.State.ERROR)
                    panel?.setStatus(getString(R.string.orion_wake_panel_failed))
                    main.postDelayed({ closePanel(resume = true) }, 1_400)
                }
                return@launch
            }
            main.post {
                panel?.setState(WakePanelController.State.PROCESSING)
                panel?.setStatus(getString(R.string.orion_wake_panel_ready))
                panel?.setTranscript(reply.take(160))
            }
            speakReply(reply)
            main.post { closePanel(resume = true) }
        }
    }

    /**
     * [T-orion-voice-actions] The session voice commands accumulate in, created
     * on first use. One persistent session (rather than one per command) is what
     * lets «y ahora abre WhatsApp» resolve against what was just done.
     */
    private suspend fun ensureVoiceSession(app: MinisApp): String? {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = prefs.getString(KEY_VOICE_SESSION, null)
        if (saved != null && app.chatRepository.getSession(saved) != null) return saved
        val modelId = runCatching {
            app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val session = runCatching {
            app.chatRepository.createSession(
                modelId = modelId,
                title = getString(R.string.orion_voice_session_title),
                memoryEnabled = com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(app),
            )
        }.getOrNull() ?: return null
        prefs.edit().putString(KEY_VOICE_SESSION, session.id).apply()
        Log.w(TAG, "voice session ${session.id.take(8)} created")
        return session.id
    }

    /** [T-orion-voice-actions] Say [text] and wait for the engine to finish. */
    private suspend fun speakReply(text: String) {
        if (!speaksReplies(this)) {
            Log.w(TAG, "voice replies disabled — answer stays on the panel")
            delay(1_200)
            return
        }
        val manager = withContext(Dispatchers.Main) {
            val existing = replyTts ?: TextToSpeechManager().also { replyTts = it }
            if (!existing.isInitialized) {
                existing.init(this@WakeWordService)
                existing.awaitReady()
            }
            existing
        }
        if (!manager.isInitialized) {
            Log.w(TAG, "TTS unavailable — answer stays on the panel")
            delay(1_200)
            return
        }
        withContext(Dispatchers.Main) { manager.speak(text) }
        manager.awaitSpeechEnd(timeoutMs = 15_000L + text.length * 110L)
    }

    /** Legacy path: hand the transcript to a full voice chat. */
    private fun openChatWithPrompt(clean: String) {
        DeepLinkCoordinator.setPendingPrompt(clean)
        // [T-orion-voice-first] Launch the VOICE chat entry point, not the
        // plain one. MainActivity maps it to ChatAction.START_VOICE, which
        // puts ChatScreen in voice mode; that is what enables the streaming
        // read-aloud effect (its pill/effect only run while voice is active).
        // Without this the answer was always text-only.
        if (speaksReplies(this)) {
            runCatching {
                VoiceOutputState.init(this)
                if (!VoiceOutputState.isMuted.value) VoiceOutputState.setEnabled(true)
            }.onFailure { Log.w(TAG, "cannot enable read-replies: ${it.message}") }
        }
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("minis://action/voice_chat"))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot open chat: ${t.message}")
            paused = false
        }
    }

    /** Full-screen fallback when overlays or speech recognition are missing. */
    private fun openAssistActivity() {
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

    private fun closePanel(resume: Boolean) {
        main.removeCallbacks(panelTimeout)
        destroySpeech()
        panel?.hide()
        if (resume) paused = false
    }

    private fun destroySpeech() {
        val current = speech ?: return
        speech = null
        runCatching { current.cancel() }
        runCatching { current.destroy() }
    }

    // ── debug: score a WAV through VoskWakeWord ─────────────────────────
    private suspend fun runWavTest(path: String?) {
        if (path.isNullOrBlank()) {
            Log.w(TAG, "TEST_WAV without path")
            return
        }
        try {
            if (!VoskWakeWord.isReady(this)) VoskWakeWord.ensureInstalled(this)
            val phrase = getPhrase(this)
            val pcm = readWav(File(path))
            val detector = VoskWakeWord(this, phrase)
            val chunk = ShortArray(1_280)
            var hits = 0
            var chunks = 0
            var i = 0
            while (i + chunk.size <= pcm.size) {
                System.arraycopy(pcm, i, chunk, 0, chunk.size)
                if (detector.push(chunk, chunk.size)) {
                    hits++
                    Log.w(TAG, "TEST MATCH t=${"%.2f".format(i / 16_000f)}s")
                }
                chunks++
                i += chunk.size
            }
            detector.close()
            Log.w(TAG, "TEST DONE engine=vosk phrase=\"$phrase\" hits=$hits chunks=$chunks")
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

    /**
     * [T-orion-wake-fgs-guard] Promote to a microphone foreground service.
     *
     * Android 12+ refuses `startForeground(..., TYPE_MICROPHONE)` when the
     * start did not come from a visible app: the mic is a while-in-use
     * permission, so the process must already be in the foreground. Any
     * explicit start from outside the UI (a debug `am startservice`, an OEM
     * "restart my services" helper, a stale start request the system replays)
     * therefore throws. Uncaught, that exception kills the process — and the
     * system, seeing a crashed start-requested service, restarts it, which
     * crashes again: a silent crash loop that also takes the accessibility
     * service down with it. Report and stop instead; the service comes back
     * the next time the user opens Orion.
     */
    private fun startForegroundNotification(): Boolean {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.orion_wake_word_notification_title, getPhrase(this)))
            .setContentText(getString(R.string.orion_wake_word_notification_body))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tap)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "cannot start microphone foreground service: ${t.message}")
            false
        }
    }
}
