package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.R
import com.openminis.app.localmodel.LlamaServer
import com.openminis.app.localmodel.LocalModelConfig
import com.openminis.app.localmodel.CatalogModel
import com.openminis.app.localmodel.DeviceCapacity
import com.openminis.app.localmodel.LocalModelFile
import com.openminis.app.localmodel.LocalModelProvider
import com.openminis.app.localmodel.LocalModelScanner
import com.openminis.app.localmodel.LocalServerStatus
import com.openminis.app.localmodel.ModelCatalog
import com.openminis.app.localmodel.ModelDownloader
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.OrionMood
import com.openminis.app.ui.components.OrionOrb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-orion-local-model] "Modelo local": find the `.gguf` already on the phone
 * and run it with one tap.
 *
 * The screen is deliberately a single decision — which file — because
 * everything after that is bookkeeping the app can do itself: spawn the
 * bundled engine, wait for it to answer, create the provider, select the
 * model, open a chat. The status card at the top is the same orb language as
 * the rest of the app, so the phone's own model feels like part of Orion and
 * not like a settings page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val prefs = remember {
        context.getSharedPreferences("orion_local_model", android.content.Context.MODE_PRIVATE)
    }
    var contextSize by remember { mutableIntStateOf(prefs.getInt("ctx", 4096)) }
    var threads by remember { mutableIntStateOf(prefs.getInt("threads", 4)) }
    var useMmap by remember { mutableStateOf(prefs.getBoolean("mmap", false)) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("auto_start", true)) }

    var models by remember { mutableStateOf<List<LocalModelFile>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var hasScanned by remember { mutableStateOf(false) }
    var allFiles by remember { mutableStateOf(LocalModelScanner.hasAllFilesAccess()) }
    var manualPath by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    var linkedModelId by remember { mutableStateOf<String?>(null) }

    // [T-orion-model-catalog] The download card is the first-run path when the
    // phone has no .gguf yet. The catalog is computed once per screen: the
    // recommendation depends only on total RAM and free storage.
    val capacity = remember { ModelCatalog.capacity(context) }
    val recommended = remember(capacity) { ModelCatalog.recommended(context) }
    val alternatives = remember(capacity, recommended.id) {
        ModelCatalog.alternatives(context, recommended)
    }
    var download by remember { mutableStateOf(ModelDownloader.status(context)) }
    var downloadBusy by remember { mutableStateOf(false) }
    var consumedDownloadId by remember { mutableStateOf(-1L) }
    var showCatalog by remember { mutableStateOf(false) }

    val status by LlamaServer.status.collectAsState()

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { LocalModelScanner.scan(context) }
            models = found
            hasScanned = true
            scanning = false
            allFiles = LocalModelScanner.hasAllFilesAccess()
        }
    }

    // First scan on entry: the common case is "I already downloaded it".
    LaunchedEffect(Unit) {
        if (!hasScanned && !scanning) scan()
    }

    // Coming back from the system all-files page must re-probe the grant and
    // re-scan; otherwise the user grants access and still sees "no models".
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = LocalModelScanner.hasAllFilesAccess()
                val gained = !allFiles && now
                allFiles = now
                if (gained) scan()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    // Once the engine answers, wire it into the provider store automatically.
    // Keyed by the running model so a different file re-links.
    val running = status as? LocalServerStatus.Running
    LaunchedEffect(running?.modelId, running?.port) {
        val r = running ?: return@LaunchedEffect
        if (linkedModelId == r.modelId) return@LaunchedEffect
        val linked = withContext(Dispatchers.IO) {
            LocalModelProvider.ensureProvider(
                repo = providerRepository,
                port = r.port,
                modelId = r.modelId,
                displayName = r.alias.replace('_', ' ').removeSuffix(".gguf"),
                contextWindow = contextSize,
            )
        }
        // Only claim success when the entry is actually in the store and
        // selected: the button below depends on this.
        linkedModelId = if (linked != null) r.modelId else null
        snackbar.showSnackbar(
            context.getString(
                if (linked != null) R.string.orion_local_connected
                else R.string.orion_local_link_failed,
            ),
        )
    }

    fun start(model: LocalModelFile) {
        prefs.edit()
            .putInt("ctx", contextSize)
            .putInt("threads", threads)
            .putBoolean("mmap", useMmap)
            .apply()
        LlamaServer.start(
            context = context,
            model = model,
            config = LocalModelConfig(
                contextSize = contextSize,
                threads = threads,
                useMmap = useMmap,
            ),
        )
    }

    fun beginDownload(model: CatalogModel) {
        if (downloadBusy) return
        downloadBusy = true
        scope.launch {
            val id = withContext(Dispatchers.IO) { ModelDownloader.start(context, model) }
            downloadBusy = false
            if (id == null) {
                snackbar.showSnackbar(context.getString(R.string.orion_model_download_failed))
            } else {
                download = ModelDownloader.DownloadState(
                    downloadId = id,
                    model = model,
                    bytes = 0L,
                    total = model.sizeBytes,
                    status = ModelDownloader.Status.RUNNING,
                )
            }
        }
    }

    // Poll DownloadManager while a file is in flight. Keyed on the status so
    // the loop stops as soon as the download leaves RUNNING.
    LaunchedEffect(download?.downloadId, download?.status) {
        val current = download ?: return@LaunchedEffect
        if (current.status != ModelDownloader.Status.RUNNING) return@LaunchedEffect
        while (true) {
            delay(800)
            val next = withContext(Dispatchers.IO) { ModelDownloader.status(context) }
            download = next
            if (next == null || next.status != ModelDownloader.Status.RUNNING) break
        }
    }

    // A finished download becomes a running model with no extra tap: rescan,
    // pick the new file and start the engine. The provider is linked by the
    // `running` effect above, and `start` also records it for next launch.
    LaunchedEffect(download?.downloadId, download?.status) {
        val finished = download ?: return@LaunchedEffect
        if (finished.status != ModelDownloader.Status.DONE) return@LaunchedEffect
        if (consumedDownloadId == finished.downloadId) return@LaunchedEffect
        consumedDownloadId = finished.downloadId
        val found = withContext(Dispatchers.IO) { LocalModelScanner.scan(context) }
        models = found
        hasScanned = true
        val target = found.firstOrNull {
            it.file.name.equals(finished.model.fileName, ignoreCase = true)
        } ?: ModelCatalog.localFile(context, finished.model)
            ?.let { LocalModelScanner.fromPath(it.absolutePath) }
        if (target != null) {
            start(target)
            snackbar.showSnackbar(context.getString(R.string.orion_model_downloaded))
            ModelDownloader.clear(context)
            download = null
        } else {
            snackbar.showSnackbar(context.getString(R.string.orion_model_download_failed))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.orion_local_model)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            StatusCard(
                status = status,
                connected = running != null && linkedModelId == running.modelId,
                onOpenChat = onOpenChat,
            )

            if (!allFiles) {
                Spacer(Modifier.height(14.dp))
                FilesAccessCard(
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                            runCatching { context.startActivity(intent) }
                                .onFailure {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                                    )
                                }
                        }
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.orion_local_models_found),
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    TextButton(onClick = { scan() }) {
                        Text(stringResource(R.string.orion_local_scan), fontSize = 13.sp)
                    }
                }
            }

            if (hasScanned && models.isEmpty()) {
                Text(
                    text = stringResource(R.string.orion_local_none),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            // [T-orion-model-catalog] No model on the phone? Offer the one
            // Orion recommends for this device instead of sending the user to
            // a browser to guess which GGUF is safe.
            if (models.isEmpty() || showCatalog) {
                Spacer(Modifier.height(12.dp))
                ModelCatalogCard(
                    recommended = recommended,
                    alternatives = alternatives,
                    capacity = capacity,
                    download = download,
                    busy = downloadBusy,
                    onDownload = { beginDownload(it) },
                    onCancel = {
                        ModelDownloader.cancel(context)
                        download = null
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            models.forEach { model ->
                ModelRow(
                    model = model,
                    isRunning = running?.modelPath == model.path,
                    isStarting = (status as? LocalServerStatus.Starting)?.modelPath == model.path,
                    onStart = { start(model) },
                )
            }

            if (models.isNotEmpty()) {
                TextButton(onClick = { showCatalog = !showCatalog }) {
                    Text(stringResource(R.string.orion_model_get_another), fontSize = 13.sp)
                }
            }

            // Rejection path: the user may have granted access to a folder we
            // don't scan, or the file has an odd extension. A path field is the
            // escape hatch, and it is cheap.
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = manualPath,
                onValueChange = { manualPath = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.orion_local_manual)) },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val described = LocalModelScanner.fromPath(manualPath)
                            if (described == null) {
                                scope.launch {
                                    snackbar.showSnackbar(
                                        context.getString(R.string.orion_local_bad_path),
                                    )
                                }
                            } else {
                                models = (models + described)
                                    .distinctBy { it.path }
                                    .sortedByDescending { it.sizeBytes }
                                hasScanned = true
                                manualPath = ""
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    }
                },
            )

            Spacer(Modifier.height(20.dp))
            AdvancedCard(
                contextSize = contextSize,
                threads = threads,
                useMmap = useMmap,
                autoStart = autoStart,
                enabled = !LlamaServer.isBusy,
                onContextChange = { contextSize = it },
                onThreadsChange = { threads = it },
                onMmapChange = { useMmap = it },
                onAutoStartChange = {
                    autoStart = it
                    LlamaServer.setAutoStart(context, it)
                },
            )

            Text(
                text = stringResource(R.string.orion_local_size_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )

            if (status is LocalServerStatus.Failed) {
                TextButton(onClick = { showLog = !showLog }) {
                    Text(stringResource(R.string.orion_local_log))
                }
                if (showLog) {
                    val log = remember(status) { LlamaServer.logTail(context) }
                    Text(
                        text = log.ifBlank { "—" },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(8.dp),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatusCard(
    status: LocalServerStatus,
    connected: Boolean,
    onOpenChat: () -> Unit,
) {
    val context = LocalContext.current
    val mood = when (status) {
        is LocalServerStatus.Running -> OrionMood.LISTENING
        is LocalServerStatus.Starting -> OrionMood.THINKING
        else -> OrionMood.STANDBY
    }
    val title = when (status) {
        is LocalServerStatus.Running -> stringResource(R.string.orion_local_running)
        is LocalServerStatus.Starting -> stringResource(R.string.orion_local_starting)
        is LocalServerStatus.Failed -> stringResource(R.string.orion_local_failed)
        LocalServerStatus.Stopped -> stringResource(R.string.orion_local_idle)
    }
    val subtitle = when (status) {
        is LocalServerStatus.Running -> status.modelId
        is LocalServerStatus.Starting -> status.modelPath.substringAfterLast('/')
        is LocalServerStatus.Failed -> status.message
        LocalServerStatus.Stopped -> stringResource(R.string.orion_local_model_subtitle)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrionOrb(
                levels = emptyList(),
                isActive = false,
                mood = mood,
                diameter = 66.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                if (status is LocalServerStatus.Running) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = onOpenChat,
                            enabled = connected,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 14.dp,
                                vertical = 6.dp,
                            ),
                        ) {
                            Text(stringResource(R.string.orion_local_open_chat), fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { LlamaServer.stop(context) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 6.dp,
                            ),
                        ) {
                            Text(stringResource(R.string.orion_local_stop), fontSize = 13.sp)
                        }
                    }
                }
                if (status is LocalServerStatus.Starting) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { LlamaServer.stop(context) }) {
                        Text(stringResource(R.string.orion_local_stop), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesAccessCard(onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.orion_local_allow_files),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.orion_local_allow_files_body),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onGrant) {
                Text(stringResource(R.string.orion_local_allow_files_action))
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: LocalModelFile,
    isRunning: Boolean,
    isStarting: Boolean,
    onStart: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable(enabled = !isRunning && !isStarting) { onStart() },
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Memory,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(model.displayName, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = model.summary(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                isStarting -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                isRunning -> Icon(
                    Icons.Filled.Stop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                else -> Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelCatalogCard(
    recommended: CatalogModel,
    alternatives: List<CatalogModel>,
    capacity: DeviceCapacity,
    download: ModelDownloader.DownloadState?,
    busy: Boolean,
    onDownload: (CatalogModel) -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            val active = download?.takeIf { it.status == ModelDownloader.Status.RUNNING }
            if (active != null) {
                Text(
                    text = stringResource(R.string.orion_model_downloading, active.model.displayName),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { active.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${CatalogModel.formatSize(active.bytes)} / " +
                            CatalogModel.formatSize(active.total),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.orion_model_cancel), fontSize = 13.sp)
                    }
                }
            } else {
                if (download?.status == ModelDownloader.Status.FAILED) {
                    Text(
                        text = stringResource(R.string.orion_model_download_failed),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = stringResource(R.string.orion_models_empty_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.orion_models_empty_body),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        R.string.orion_model_device,
                        String.format(java.util.Locale.US, "%.0f GB", capacity.totalRamGb),
                        String.format(java.util.Locale.US, "%.0f GB", capacity.freeStorageGb),
                    ),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.orion_model_recommended),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = recommended.displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = recommended.summary(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onDownload(recommended) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.orion_model_download_start))
                }

                if (alternatives.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.orion_model_other_options),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    alternatives.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(model.displayName, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    text = model.summary(),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onDownload(model) }, enabled = !busy) {
                                Text(
                                    text = stringResource(R.string.orion_model_download_simple),
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedCard(
    contextSize: Int,
    threads: Int,
    useMmap: Boolean,
    autoStart: Boolean,
    enabled: Boolean,
    onContextChange: (Int) -> Unit,
    onThreadsChange: (Int) -> Unit,
    onMmapChange: (Boolean) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.orion_local_advanced),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.orion_local_context), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2048, 4096, 8192).forEach { value ->
                    FilterChip(
                        selected = contextSize == value,
                        onClick = { if (enabled) onContextChange(value) },
                        label = { Text("$value") },
                        enabled = enabled,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.orion_local_threads), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 4, 6, 8).forEach { value ->
                    FilterChip(
                        selected = threads == value,
                        onClick = { if (enabled) onThreadsChange(value) },
                        label = { Text("$value") },
                        enabled = enabled,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.orion_local_mmap),
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                )
                Switch(
                    checked = useMmap,
                    onCheckedChange = { if (enabled) onMmapChange(it) },
                    enabled = enabled,
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.orion_local_autostart_title),
                        fontSize = 12.sp,
                    )
                    Text(
                        text = stringResource(R.string.orion_local_autostart_subtitle),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoStart,
                    onCheckedChange = onAutoStartChange,
                )
            }
        }
    }
}
