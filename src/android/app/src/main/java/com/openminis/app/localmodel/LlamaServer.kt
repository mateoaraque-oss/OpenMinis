package com.openminis.app.localmodel

import android.content.Context
import android.util.Log
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.LocalModelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/** [T-orion-local-model] What the bundled llama.cpp engine is doing. */
sealed interface LocalServerStatus {
    data object Stopped : LocalServerStatus

    data class Starting(
        val modelPath: String,
        val port: Int,
    ) : LocalServerStatus

    data class Running(
        val modelPath: String,
        val port: Int,
        val modelId: String,
        val alias: String,
    ) : LocalServerStatus {
        val baseUrl: String get() = "http://127.0.0.1:$port"
    }

    data class Failed(
        val modelPath: String,
        val message: String,
        val logTail: String = "",
    ) : LocalServerStatus
}

/**
 * [T-orion-local-model] Tunables a phone actually cares about. Everything else
 * is fixed: one slot, no GPU offload, loopback only.
 */
data class LocalModelConfig(
    val contextSize: Int = 4096,
    val threads: Int = 4,
    /** Off by default: mmap over Android's FUSE `/sdcard` is slow and, on some
     *  ROMs, outright fails. Reading the weights into anonymous memory is
     *  faster here and the model fits in 8 GB. */
    val useMmap: Boolean = false,
)

/**
 * [T-orion-local-model] Runs `llama-server` from the binaries bundled in the
 * APK and exposes it as an OpenAI-compatible endpoint on loopback.
 *
 * Why the binaries live in `jniLibs` instead of `assets`: since Android 10 an
 * app targeting API 29+ may not `execve` a file it can write (the app's home
 * directory). The native-library directory is installed read-only and is
 * exempt, so the executable is shipped as `libllama-server.so` — the `lib…so`
 * name is what makes AGP package it and what makes the installer extract it
 * with the right label. `extractNativeLibs="true"` is already set in the
 * manifest for exactly this reason.
 *
 * If the extracted file lacks the execute bit, [spawn] retries through
 * `/system/bin/linker64`, which maps the PIE image itself and only needs read
 * access.
 *
 * The server is intentionally a process-wide singleton: it must survive
 * navigation, and the foreground service keeps the process alive while it
 * generates. It dies with the app, which is the correct lifetime for a
 * loopback server nobody else can reach.
 */
object LlamaServer {

    private const val TAG = "OrionLocalModel"
    private const val HEALTH_TIMEOUT_MS = 240_000L
    private const val POLL_INTERVAL_MS = 700L
    private const val LOG_FILE = "llama-server.log"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<LocalServerStatus>(LocalServerStatus.Stopped)
    val status: StateFlow<LocalServerStatus> = _status.asStateFlow()

    @Volatile private var process: Process? = null
    @Volatile private var startJob: Job? = null

    val isRunning: Boolean get() = _status.value is LocalServerStatus.Running
    val isBusy: Boolean
        get() = _status.value is LocalServerStatus.Starting ||
            _status.value is LocalServerStatus.Running

    /** The OpenAI base URL while the server is up, else null. */
    fun baseUrl(): String? = (_status.value as? LocalServerStatus.Running)?.baseUrl

    fun modelId(): String? = (_status.value as? LocalServerStatus.Running)?.modelId

    /**
     * Start the engine for [model]. Idempotent while a start is in flight, and
     * returns immediately: progress is reported through [status].
     */
    fun start(
        context: Context,
        model: LocalModelFile,
        config: LocalModelConfig = LocalModelConfig(),
    ) {
        if (startJob?.isActive == true) return
        if (isRunning) return
        stopQuietly()

        val app = context.applicationContext
        resetLog(app)

        // Pick the port and publish Starting BEFORE the service observes the
        // flow: otherwise its first collect sees Stopped and stops itself.
        val port = runCatching { findFreePort() }.getOrElse {
            fail(model.path, app, "No hay ningún puerto libre en 127.0.0.1.")
            return
        }
        _status.value = LocalServerStatus.Starting(model.path, port)
        // Raise the process priority before the (possibly 60 s) model load.
        runCatching { LocalModelService.start(app) }

        startJob = scope.launch {
            val nativeDir = app.applicationInfo.nativeLibraryDir
            val server = File(nativeDir, "libllama-server.so")
            if (!server.isFile) {
                fail(model.path, app, "El motor llama.cpp no está en este APK (falta $server).")
                return@launch
            }
            val alias = aliasFor(model)
            // Best effort: native lib dir is usually already executable. If
            // the ROM extracted the stub read-only, spawn() falls back to
            // linker64, which only needs read access.
            runCatching { server.setExecutable(true, false) }

            val command = buildCommand(server.absolutePath, model.path, alias, port, config)
            Log.i(TAG, "starting: ${command.joinToString(" ")}")
            val started = runCatching { spawn(app, server, command, nativeDir) }
            val proc = started.getOrElse { e ->
                fail(
                    model.path,
                    app,
                    "No se pudo ejecutar el motor: ${e.javaClass.simpleName}: ${e.message}",
                )
                return@launch
            }
            process = proc

            val ready = waitForHealth(proc, port)
            if (!isActive) {
                // The user pressed Stop while the model was loading; do not
                // publish a stale Running/Failed state over the new one.
                stopQuietly()
                _status.value = LocalServerStatus.Stopped
                runCatching { LocalModelService.stop(app) }
                return@launch
            }
            if (!ready) {
                val log = logTail(app)
                stopQuietly()
                _status.value = LocalServerStatus.Failed(
                    modelPath = model.path,
                    message = "El motor terminó o no respondió. Revisa el registro: " +
                        log.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty(),
                    logTail = log,
                )
                runCatching { LocalModelService.stop(app) }
                return@launch
            }

            val id = runCatching { fetchModelId(port) }.getOrNull() ?: alias
            _status.value = LocalServerStatus.Running(
                modelPath = model.path,
                port = port,
                modelId = id,
                alias = alias,
            )
            AppLogger.info(TAG, "llama-server ready on $port model=$id")
        }
    }

    fun stop(context: Context? = null) {
        val app = context?.applicationContext
        startJob?.cancel()
        startJob = null
        stopQuietly()
        _status.value = LocalServerStatus.Stopped
        if (app != null) runCatching { LocalModelService.stop(app) }
    }

    /** Last [lines] lines of the engine log, for the in-app diagnostics panel. */
    fun logTail(context: Context, lines: Int = 60): String {
        val file = logFile(context)
        if (!file.isFile) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val readFrom = (length - 96_000L).coerceAtLeast(0L)
                raf.seek(readFrom)
                val bytes = ByteArray((length - readFrom).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8).lines().takeLast(lines).joinToString("\n")
            }
        }.getOrDefault("")
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun buildCommand(
        serverPath: String,
        modelPath: String,
        alias: String,
        port: Int,
        config: LocalModelConfig,
    ): List<String> = buildList {
        add(serverPath)
        add("-m"); add(modelPath)
        add("-a"); add(alias)
        add("-c"); add(config.contextSize.toString())
        add("-t"); add(config.threads.toString())
        add("-np"); add("1")
        add("-ngl"); add("0")
        add("--host"); add("127.0.0.1")
        add("--port"); add(port.toString())
        add("--jinja")
        if (!config.useMmap) add("--no-mmap")
        // Small batches keep the first token latency low on a phone and cap
        // the compute buffer, which is what actually runs out of memory.
        add("-b"); add("512")
        add("-ub"); add("512")
    }

    private fun spawn(
        context: Context,
        server: File,
        command: List<String>,
        nativeDir: String,
    ): Process {
        fun launch(cmd: List<String>): Process {
            val pb = ProcessBuilder(cmd)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile(context)))
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = nativeDir
            env["HOME"] = context.filesDir.absolutePath
            env["TMPDIR"] = context.cacheDir.absolutePath
            return pb.start()
        }

        return try {
            launch(command)
        } catch (e: IOException) {
            // Most likely EACCES: the installer extracted the stub without the
            // execute bit. bionic's linker can map a PIE image itself, so ask
            // it to be the program.
            AppLogger.warning(
                TAG,
                "direct exec failed (${e.message}); retrying through linker64",
            )
            val linker = File("/system/bin/linker64")
            if (!linker.isFile) throw e
            launch(listOf(linker.absolutePath) + command)
        }
    }

    private fun waitForHealth(proc: Process, port: Int): Boolean {
        val deadline = System.currentTimeMillis() + HEALTH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) return false
            val code = runCatching { httpCode("http://127.0.0.1:$port/health") }.getOrNull()
            if (code == 200) return true
            // 503 = still loading the model; anything else may be the server
            // not being up yet. Keep polling either way.
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun fetchModelId(port: Int): String? {
        val body = runCatching {
            val conn = URL("http://127.0.0.1:$port/v1/models").openConnection() as HttpURLConnection
            conn.connectTimeout = 2_000
            conn.readTimeout = 4_000
            conn.requestMethod = "GET"
            conn.useCaches = false
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull() ?: return null
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return null
        if (data.length() == 0) return null
        return data.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
    }

    private fun httpCode(url: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 1_500
            conn.readTimeout = 1_500
            conn.requestMethod = "GET"
            conn.useCaches = false
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun findFreePort(): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        for (candidate in 8080..8099) {
            try {
                ServerSocket(candidate, 1, loopback).use { return candidate }
            } catch (_: IOException) {
                // taken
            }
        }
        ServerSocket(0, 1, loopback).use { return it.localPort }
    }

    private fun stopQuietly() {
        val proc = process ?: return
        process = null
        runCatching {
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(2, TimeUnit.SECONDS)
            }
        }.onFailure { AppLogger.warning(TAG, "stop failed: ${it.message}") }
    }

    private fun fail(modelPath: String, context: Context, message: String) {
        AppLogger.error(TAG, message)
        _status.value = LocalServerStatus.Failed(
            modelPath = modelPath,
            message = message,
            logTail = logTail(context),
        )
        runCatching { LocalModelService.stop(context) }
    }

    private fun logFile(context: Context) = File(context.filesDir, LOG_FILE)

    private fun resetLog(context: Context) {
        runCatching { logFile(context).writeText("") }
    }

    private fun aliasFor(model: LocalModelFile): String {
        val stem = model.file.name.removeSuffix(".gguf").removeSuffix(".GGUF")
        return stem.ifBlank { "orion-local" }
    }
}
