package com.openminis.app.localmodel

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [T-orion-autostart] Zero-tap local model.
 *
 * The one-tap flow in [com.openminis.app.ui.settings.LocalModelScreen] stores
 * the model a user launched. When Orion is invoked as the phone's assistant
 * (assist gesture, headset button, Android's assistant hand-off) this object
 * brings that same model back without a screen tap, which is what makes Orion
 * usable as a real assistant after a reboot or a force-stop.
 *
 * It deliberately does nothing when:
 *  - the user turned the toggle off in Settings > Local model;
 *  - the engine is already starting/running;
 *  - no model was ever launched;
 *  - the remembered `.gguf` is gone;
 *  - the user's last selected model is a cloud provider (no reason to burn
 *    1-3 GB of RAM behind their back).
 */
object LocalModelBoot {

    private const val TAG = "OrionLocalModel"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var booting = false

    /** Called from MainActivity when an assist/voice-command intent arrives. */
    fun onAssistInvocation(context: Context) {
        val app = context.applicationContext
        if (!LlamaServer.autoStartEnabled(app)) return
        if (LlamaServer.isBusy) return
        if (booting) return
        val launch = LlamaServer.lastLaunch(app) ?: return

        booting = true
        scope.launch {
            try {
                val repo = (app as? MinisApp)?.providerRepositoryOrNull
                if (repo != null) {
                    repo.awaitConfigLoaded()
                    if (!localEntrySelected(repo)) {
                        AppLogger.info(TAG, "assist: cloud model selected, not warming the local engine")
                        return@launch
                    }
                }
                LlamaServer.start(app, launch.model, launch.config)
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        Toast.makeText(
                            app,
                            app.getString(com.openminis.app.R.string.orion_local_autostart_loading),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                val terminal = withTimeoutOrNull(300_000L) {
                    LlamaServer.status.first { st ->
                        (st is LocalServerStatus.Running && st.modelPath == launch.model.path) ||
                            (st is LocalServerStatus.Failed && st.modelPath == launch.model.path)
                    }
                } ?: return@launch
                val running = terminal as? LocalServerStatus.Running ?: return@launch
                if (repo != null) {
                    // The port can differ from the previous session, so the
                    // persisted provider/entry is re-pointed at the live one.
                    LocalModelProvider.ensureProvider(
                        repo = repo,
                        port = running.port,
                        modelId = running.modelId,
                        displayName = running.alias.replace('_', ' ').removeSuffix(".gguf"),
                        contextWindow = launch.config.contextSize,
                    )
                }
                AppLogger.info(TAG, "assist: local engine ready on ${running.port}")
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "assist auto-start failed: ${t.message}")
            } finally {
                booting = false
            }
        }
    }

    /** True when Orion's last selected model belongs to [LocalModelProvider]. */
    private fun localEntrySelected(repo: ProviderRepository): Boolean {
        val last = repo.lastUsedEntryId ?: return true
        val instance = repo.instances.firstOrNull { it.id == last.substringBefore('/') }
            ?: return true
        return instance.label.equals(LocalModelProvider.LABEL, ignoreCase = true)
    }
}
