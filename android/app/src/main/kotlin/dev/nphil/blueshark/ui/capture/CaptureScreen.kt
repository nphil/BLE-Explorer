package dev.nphil.blueshark.ui.capture

import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.coroutines.delay
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.guide.ControlLabeler
import dev.nphil.blueshark.guide.ScreenCoverage
import dev.nphil.blueshark.hci.CollectProgress
import dev.nphil.blueshark.hci.CollectStage
import dev.nphil.blueshark.hci.SnoopMode
import dev.nphil.blueshark.shell.ShizukuState
import dev.nphil.blueshark.ui.theme.MonoFamily
import java.util.Locale

private val IMPORT_TYPES = arrayOf("*/*")
private const val APP_ROWS = 60

/**
 * Xiaomi's HyperOS (and MIUI before it) suspends accessibility services of apps that are not in
 * the foreground unless autostart is granted, so the guided take-over needs an extra hint there.
 */
private val OEM_KILLS_SERVICES: Boolean =
    setOf("xiaomi", "redmi", "poco").any {
        Build.MANUFACTURER.equals(it, ignoreCase = true) || Build.BRAND.equals(it, ignoreCase = true)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    container: AppContainer,
    expanded: Boolean,
    bluetoothGranted: Boolean,
    requestPermissions: () -> Unit,
) {
    val viewModel: CaptureViewModel =
        viewModel(factory = remember(container) { CaptureViewModel.factory(container) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    // Coming back from Developer options or Accessibility settings: read both again so step 1 and
    // the guided take-over reflect the toggles the operator just flipped.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            viewModel.refreshGuide()
            if (state.shellReady && !state.working) viewModel.probe()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFile(uri)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HCI capture")
                        Text(
                            text = state.sessionName.ifBlank { "No session selected" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(IMPORT_TYPES) }, enabled = !state.working) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Import a btsnoop or bugreport file")
                    }
                    IconButton(onClick = viewModel::toggleLogcat, enabled = state.shellReady) {
                        Icon(
                            imageVector = if (state.logcatRunning) Icons.Filled.Stop else Icons.Outlined.Terminal,
                            contentDescription = if (state.logcatRunning) "Stop the Bluetooth log stream" else "Stream the Bluetooth log",
                            tint = if (state.logcatRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = viewModel::probe, enabled = state.shellReady && !state.probing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Re-run the capability probe")
                    }
                },
            )
        },
    ) { padding ->
        if (expanded) {
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    modifier = Modifier.weight(0.46f).fillMaxHeight(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    captureSteps(state, viewModel, bluetoothGranted, requestPermissions)
                }
                VerticalDivider()
                LazyColumn(
                    modifier = Modifier.weight(0.54f).fillMaxHeight(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    timelineSection(state, viewModel)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                captureSteps(state, viewModel, bluetoothGranted, requestPermissions)
                timelineSection(state, viewModel)
            }
        }
    }
}

// ---------------------------------------------------------------------- steps pane

private fun LazyListScope.captureSteps(
    state: CaptureUiState,
    viewModel: CaptureViewModel,
    bluetoothGranted: Boolean,
    requestPermissions: () -> Unit,
) {
    item(key = "shizuku") { ShizukuCard(state, viewModel) }
    item(key = "privacy") { PrivacyCard() }
    if (!bluetoothGranted) {
        item(key = "permissions") {
            NoticeCard(
                icon = Icons.Filled.Bluetooth,
                title = "Bluetooth permission not granted",
                body = "The capture itself runs through Shizuku, but BlueShark needs the Bluetooth permissions to " +
                    "read adapter state while restarting it and to use the other tabs.",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                action = "Grant" to requestPermissions,
            )
        }
    }
    item(key = "capabilities") { CapabilityCard(state, viewModel) }
    item(key = "session") { SessionCard(state, viewModel) }
    item(key = "step-logging") {
        StepCard(1, CaptureStep.LOGGING, state) {
            Text(
                text = "Developer options > \"Enable Bluetooth HCI snoop log\" > Enabled (not \"Enabled Filtered\": that " +
                    "strips the vendor bytes you are after). The Bluetooth service reads the setting only when the " +
                    "adapter starts, so step 2 comes next and verifies this one: it turns green when the service " +
                    "reports FULL after the restart. Without root nothing but Settings can change the mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.snoopModeDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.snoopDenied) {
                    Button(onClick = viewModel::openDeveloperOptions) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Developer options")
                    }
                    OutlinedButton(
                        onClick = { viewModel.setSnoopMode(SnoopMode.FULL) },
                        enabled = state.shellReady && !state.working,
                    ) { Text("Retry setprop") }
                } else {
                    Button(
                        onClick = { viewModel.setSnoopMode(SnoopMode.FULL) },
                        enabled = state.shellReady && !state.working,
                    ) { Text("Enable full logging") }
                    OutlinedButton(onClick = viewModel::openDeveloperOptions) { Text("Developer options") }
                }
            }
        }
    }
    item(key = "step-restart") {
        StepCard(2, CaptureStep.RESTART, state) {
            Text(
                text = "The stack only picks up a new snoop mode when the adapter is cycled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.restartSteps.forEach { step ->
                Text(step, style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily)
            }
            Button(
                onClick = viewModel::restartBluetooth,
                enabled = state.shellReady && !state.working,
            ) { Text("Restart Bluetooth") }
        }
    }
    item(key = "step-app") { VendorAppCard(state, viewModel) }
    item(key = "step-markers") { MarkerCard(state, viewModel) }
    item(key = "step-collect") { CollectCard(state, viewModel) }
    item(key = "step-cleanup") {
        StepCard(6, CaptureStep.CLEANUP, state) {
            Text(
                text = "HCI logging keeps recording every Bluetooth device you own until you turn it off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.setSnoopMode(SnoopMode.DISABLED) },
                    enabled = state.shellReady && !state.working,
                ) { Text("Disable HCI logging") }
                if (state.snoopDenied) {
                    OutlinedButton(onClick = viewModel::openDeveloperOptions) { Text("Developer options") }
                }
            }
        }
    }
    if (state.attempts.isNotEmpty() || state.summary != null || state.error != null) {
        item(key = "results") { ResultCard(state, viewModel) }
    }
}

@Composable
private fun ShizukuCard(state: CaptureUiState, viewModel: CaptureViewModel) {
    val shizuku = state.shizuku
    val container = when (shizuku) {
        is ShizukuState.Ready -> MaterialTheme.colorScheme.secondaryContainer
        is ShizukuState.PermissionNeeded -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (shizuku) {
        is ShizukuState.Ready -> MaterialTheme.colorScheme.onSecondaryContainer
        is ShizukuState.PermissionNeeded -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Privileged shell", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when (shizuku) {
                    is ShizukuState.NotInstalled ->
                        "Shizuku is not installed. HCI capture needs shell privileges: install Shizuku and start it over " +
                            "wireless debugging or ADB."

                    is ShizukuState.NotRunning -> shizuku.detail
                    is ShizukuState.PermissionNeeded -> "Shizuku is running. Authorise BlueShark to use it."
                    is ShizukuState.Ready ->
                        "Shell ready as uid ${shizuku.uid}${if (shizuku.uid == 2000) " (adb shell)" else if (shizuku.uid == 0) " (root)" else ""}" +
                            ", Shizuku API v${shizuku.version}."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (shizuku) {
                    is ShizukuState.NotInstalled -> {
                        Button(onClick = viewModel::openShizukuDownload) { Text("Get Shizuku") }
                        OutlinedButton(onClick = viewModel::refreshShizuku) { Text("Re-check") }
                    }

                    is ShizukuState.NotRunning -> {
                        Button(onClick = viewModel::openShizuku) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Open Shizuku")
                        }
                        OutlinedButton(onClick = viewModel::refreshShizuku) { Text("Re-check") }
                    }

                    is ShizukuState.PermissionNeeded ->
                        Button(onClick = viewModel::requestShizukuPermission) { Text("Authorise BlueShark") }

                    is ShizukuState.Ready ->
                        OutlinedButton(onClick = viewModel::probe, enabled = !state.probing) { Text("Re-probe") }
                }
            }
        }
    }
}

@Composable
private fun PrivacyCard() {
    NoticeCard(
        icon = Icons.Filled.Warning,
        title = "HCI logging records every Bluetooth device",
        body = "While full snoop logging is on, the phone captures traffic for all Bluetooth links: headsets, watches, " +
            "car kits, keyboards. Bugreports add device-wide diagnostics. Keep the window short, capture only what you " +
            "need, and turn logging back off in step 6.",
        container = MaterialTheme.colorScheme.surfaceVariant,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NoticeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    action: Pair<String, () -> Unit>? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
                action?.let { (label, onClick) ->
                    Button(onClick = onClick) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun CapabilityCard(state: CaptureUiState, viewModel: CaptureViewModel) {
    val capabilities = state.capabilities
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What this device allows", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.probing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (!capabilities.probed) {
                Text(
                    text = "Probe once Shizuku is ready to see the shell identity, the snoop mode and which tools exist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = viewModel::probe, enabled = state.shellReady && !state.probing) { Text("Probe now") }
            } else {
                FactRow("Shell identity", capabilities.shellIdentity, mono = true)
                FactRow(
                    label = "Effective snoop mode",
                    value = capabilities.effectiveSnoopMode.ifBlank { "(unset)" },
                    mono = true,
                    tint = if (capabilities.snoopModeIsFull) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                FactRow(
                    label = "Service setting at enable",
                    value = capabilities.serviceSnoopSetting.ifBlank { "(no sSnoopLogSettingAtEnable line in dumpsys)" },
                    mono = true,
                )
                FactRow(
                    label = "Stack reports",
                    value = capabilities.stackSnoopLog.ifBlank { "(no \"Snoop Logs\" line in logcat yet; restart Bluetooth in step 2, then re-probe)" },
                    mono = true,
                )
                FactRow(
                    label = "Snoop properties",
                    value = capabilities.snoopProperties.joinToString("\n").ifBlank { "(none set)" },
                    mono = true,
                )
                FactRow("Log directory", capabilities.logDirectory.take(400), mono = true)
                FactRow("cmd bluetooth_manager", if (capabilities.bluetoothManagerShell) "available" else "not available")
                FactRow("dumpsys bluetooth_manager", if (capabilities.bluetoothManagerDumpsys) "available" else "not available")
                FactRow("bugreportz", capabilities.bugreportz ?: "not available", mono = true)
                capabilities.error?.let { FactRow("Probe error", it, tint = MaterialTheme.colorScheme.error) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = viewModel::copyDiagnostics) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy diagnostics")
                    }
                    if (state.ntfyEnabled) {
                        OutlinedButton(onClick = viewModel::sendDiagnostics) { Text("Send to ntfy") }
                    }
                }
                if (capabilities.diagnostics.isNotBlank()) {
                    var showDiagnostics by remember { mutableStateOf(false) }
                    TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                        Text(if (showDiagnostics) "Hide Bluetooth diagnostics" else "Show Bluetooth diagnostics")
                    }
                    if (showDiagnostics) {
                        Text(
                            text = capabilities.diagnostics,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = MonoFamily,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(state: CaptureUiState, viewModel: CaptureViewModel) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Session", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Events and markers are written into one capture session, which the Sessions tab exports.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.sessionName,
                onValueChange = viewModel::setSessionName,
                label = { Text("Session name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.createSession(state.sessionName) }) { Text("New session") }
            }
            if (state.sessions.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.sessions.take(6).forEach { option ->
                        FilterChip(
                            selected = option.id == state.sessionId,
                            onClick = { viewModel.chooseSession(option.id) },
                            label = { Text("${option.name} · ${option.eventCount}") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VendorAppCard(state: CaptureUiState, viewModel: CaptureViewModel) {
    var picking by rememberSaveable { mutableStateOf(false) }
    StepCard(3, CaptureStep.VENDOR_APP, state) {
        Text(
            text = "HCI records carry no app identity. Run one vendor app at a time so the traffic is attributable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.vendorApp?.let { app ->
            FactRow("Selected", "${app.label} (${app.packageName}${app.versionName?.let { " $it" } ?: ""})")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picking = !picking }) { Text(if (picking) "Hide apps" else "Pick an app") }
            state.vendorApp?.let { app ->
                FilledTonalButton(onClick = { viewModel.launchApp(app) }) { Text("Launch again") }
            }
        }
        if (picking) {
            OutlinedTextField(
                value = state.appQuery,
                onValueChange = viewModel::setAppQuery,
                label = { Text("Search installed apps") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            val needle = state.appQuery.trim().lowercase(Locale.ROOT)
            val matches = remember(state.apps, needle) {
                state.apps
                    .asSequence()
                    .filter {
                        needle.isEmpty() ||
                            it.label.lowercase(Locale.ROOT).contains(needle) ||
                            it.packageName.lowercase(Locale.ROOT).contains(needle)
                    }
                    .take(APP_ROWS)
                    .toList()
            }
            if (state.appsLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Reading the launcher list", style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
            ) {
                matches.forEach { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = MonoFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { viewModel.selectApp(app) }) { Text("Select") }
                        Button(onClick = {
                            viewModel.launchApp(app)
                            picking = false
                        }) { Text("Launch") }
                    }
                    HorizontalDivider()
                }
                if (matches.isEmpty() && !state.appsLoading) {
                    Text(
                        text = "No installed app matches that search.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider()
        GuidedTakeoverSection(state, viewModel)
    }
}

/**
 * The guided take-over: turn on the observer, and the vendor app's own controls become the
 * checklist. Every switch here is persisted by the guide, not by this composition.
 */
@Composable
private fun GuidedTakeoverSection(state: CaptureUiState, viewModel: CaptureViewModel) {
    val guide = state.guideState
    val progress = guide.progress
    Text("Guided take-over", style = MaterialTheme.typography.titleSmall)
    Text(
        text = "With the observer on, tapping a control in the vendor app drops a marker labelled with that " +
            "control, and every actionable control it finds becomes a checklist item so you can see what is " +
            "still unmapped.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "It watches only the app you selected above, records control labels, ids and positions, and never " +
            "subscribes to text-change events: nothing you type is ever read.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when {
        guide.serviceConnected -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = guide.targetPackage?.let { "Watching $it" } ?: "Observer running; launch an app to point it at one",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (state.guideEnabledInSettings) {
                    "The observer is enabled in Accessibility settings but is not running. Android stops " +
                        "accessibility services it thinks are idle, and some OEM builds kill them outright: " +
                        "toggle it off and on again."
                } else {
                    "The observer is an accessibility service, so Android requires you to enable it by hand. " +
                        "Until you do, markers stay manual."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (OEM_KILLS_SERVICES) {
                Text(
                    text = "On ${Build.MANUFACTURER}/HyperOS also allow autostart for BlueShark and set its " +
                        "battery saver to \"No restrictions\", or the observer will be killed as soon as you " +
                        "switch to the vendor app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = viewModel::openAccessibilitySettings) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Enable in Accessibility settings")
            }
        }
    }
    GuideSwitch(
        label = "Auto-mark taps",
        detail = "Every control you drive becomes a marker in the timeline.",
        checked = guide.autoMark,
        onChange = viewModel::setGuideAutoMark,
    )
    GuideSwitch(
        label = "Overlay guide",
        detail = "A draggable pill over the vendor app with progress, the last marker and a Mark button.",
        checked = guide.overlay,
        onChange = viewModel::setGuideOverlay,
    )
    GuideSwitch(
        label = "Highlight unmapped controls",
        detail = "Dashed outlines around controls you have not driven yet.",
        checked = guide.highlights,
        onChange = viewModel::setGuideHighlights,
    )
    Text(
        text = "${progress.touchedCount} of ${progress.total} controls mapped across " +
            "${progress.screens.size} screen${if (progress.screens.size == 1) "" else "s"}",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    if (progress.total > 0) {
        LinearProgressIndicator(
            progress = { progress.touchedCount.toFloat() / progress.total.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    progress.screens.forEach { coverage -> ScreenCoverageRow(coverage) }
    guide.lastInteraction?.let { control ->
        FactRow("Last control", "${control.screen} · ${control.viewId.substringAfterLast('/')}", mono = true)
    }
}

@Composable
private fun ScreenCoverageRow(coverage: ScreenCoverage) {
    var expanded by rememberSaveable(coverage.screen) { mutableStateOf(false) }
    val labeler = remember { ControlLabeler() }
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${coverage.screen}: ${coverage.mapped}/${coverage.controls.size}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (coverage.untouched.isEmpty()) "all mapped" else "${coverage.untouched.size} left",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (expanded) {
            if (coverage.untouched.isEmpty()) {
                Text(
                    text = "Every control on this screen has been driven at least once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                )
            }
            coverage.untouched.forEach { control ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = labeler.describe(control),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = control.viewId.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideSwitch(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MarkerCard(state: CaptureUiState, viewModel: CaptureViewModel) {
    StepCard(4, CaptureStep.MARKERS, state) {
        Text(
            text = "Press Mark the moment you press a button in the vendor app. Markers are saved immediately.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.markerLabel,
            onValueChange = viewModel::setMarkerLabel,
            label = { Text("What are you about to do?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Button(
            onClick = { viewModel.addMarker() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Icon(Icons.Filled.Flag, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("  Mark", style = MaterialTheme.typography.titleMedium)
        }
        if (state.recentLabels.isNotEmpty()) {
            Text("Repeat a label", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.recentLabels.forEach { label ->
                    AssistChip(onClick = { viewModel.addMarker(label) }, label = { Text(label) })
                }
            }
        }
        if (state.markers.isNotEmpty()) {
            Text(
                text = "${state.markers.size} marker${if (state.markers.size == 1) "" else "s"} in this session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CollectCard(state: CaptureUiState, viewModel: CaptureViewModel) {
    StepCard(5, CaptureStep.COLLECT, state) {
        Text(
            text = "Reads the snoop log directly when SELinux allows it, then falls back to dumpsys and to a full " +
                "bugreport, decoding the embedded btsnooz stream.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ProgressPanel(state.progress, working = state.working, busy = state.busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::collect, enabled = state.shellReady && !state.working) {
                Text("Stop and collect")
            }
            if (state.working) {
                OutlinedButton(onClick = viewModel::cancelWork) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun ResultCard(state: CaptureUiState, viewModel: CaptureViewModel) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Collection report", style = MaterialTheme.typography.titleMedium)
            state.collectedFrom?.let { FactRow("Source", it, mono = true) }
            state.summary?.let { summary ->
                FactRow("HCI records", summary.records.toString())
                FactRow("ATT events", summary.attEvents.toString())
                FactRow("Link events", summary.systemEvents.toString())
                FactRow("Connections", summary.connections.toString())
                FactRow("Counted only", summary.countedOnly.toString())
                FactRow("Not decoded", summary.unsupported.toString())
                FactRow("Truncated payloads", summary.truncated.toString())
                summary.connectionsByPeer.values.forEach { peer ->
                    FactRow(
                        label = "Peer ${peer.address}",
                        value = peerDetail(peer),
                        mono = true,
                    )
                }
            }
            if (state.peerChoices.size > 1) {
                HorizontalDivider()
                Text(
                    "Which address is the gadget? Picking one fills the session's device, MTU and " +
                        "pairing facts from the capture.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.peerChoices.forEach { peer ->
                        FilterChip(
                            selected = state.selectedPeer == peer.address,
                            onClick = { viewModel.choosePeer(peer.address) },
                            label = { Text(peer.address, fontFamily = MonoFamily) },
                        )
                    }
                }
            }
            if (state.attempts.isNotEmpty()) {
                HorizontalDivider()
                state.attempts.forEach { attempt ->
                    FactRow(
                        label = attempt.label,
                        value = attempt.detail,
                        mono = true,
                        tint = if (attempt.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
            state.warnings.forEach { warning ->
                Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (state.artifacts.isNotEmpty()) {
                FactRow("Kept in cache", state.artifacts.joinToString(", "), mono = true)
            }
            state.error?.let { error ->
                HorizontalDivider()
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::copyReport) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy report")
                }
                if (state.error != null) TextButton(onClick = viewModel::clearError) { Text("Dismiss error") }
            }
            if (state.logcatFile != null) {
                FactRow("Log stream", "${state.logcatLines} lines → ${state.logcatFile}", mono = true)
            }
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    step: CaptureStep,
    state: CaptureUiState,
    content: @Composable () -> Unit,
) {
    val done = step in state.completed
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (done) "Step $number done" else "Step $number not done yet",
                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$number. ${step.title}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

// ---------------------------------------------------------------------- timeline pane

private fun LazyListScope.timelineSection(state: CaptureUiState, viewModel: CaptureViewModel) {
    item(key = "timeline-header") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Timeline", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    text = if (state.timelineTrimmed) {
                        "last ${state.timelineShown} of ${state.timelineTotal}"
                    } else {
                        "${state.timelineTotal} events"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.connectionHandles.size > 1 || state.handleFilter != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.handleFilter == null,
                        onClick = { viewModel.setHandleFilter(null) },
                        label = { Text("All") },
                    )
                    state.connectionHandles.forEach { handle ->
                        FilterChip(
                            selected = state.handleFilter == handle,
                            onClick = { viewModel.setHandleFilter(handle) },
                            label = { Text(formatHandle(handle), fontFamily = MonoFamily) },
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
    if (state.timeline.isEmpty()) {
        item(key = "timeline-empty") {
            Text(
                text = "Nothing decoded yet. Run steps 1 to 5, or import a btsnoop log or bugreport with the folder " +
                    "button in the app bar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
    items(items = state.timeline, key = { it.key }) { row ->
        when (row) {
            is TimelineRow.Connection -> ConnectionHeaderRow(row.handle)
            is TimelineRow.Marker -> MarkerRow(row.marker)
            is TimelineRow.Event -> EventRow(
                event = row.event,
                selected = state.selectedEventId == row.event.id,
                dissection = state.dissection,
                onClick = { viewModel.selectEvent(row.event.id) },
            )
        }
    }
}

/** Stages in the order collect() tries them; DONE/FAILED are terminal, not steps. */
private val COLLECT_STEPS = listOf(
    CollectStage.DIRECT_FILE to "Direct read",
    CollectStage.DUMPSYS to "dumpsys",
    CollectStage.BUGREPORT to "Bugreport",
    CollectStage.EXTRACT to "Extract",
    CollectStage.DECODE to "Decode",
)

/**
 * Honest progress: a determinate bar only when a total is known, otherwise bytes so far and a
 * live elapsed clock, plus which stage of the pipeline is running. Shown for every long action.
 */
@Composable
private fun ProgressPanel(progress: CollectProgress?, working: Boolean, busy: String?) {
    if (!working && progress == null) return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(working, progress?.startedAtMs) {
        while (working) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (working) Text(busy ?: "Working", style = MaterialTheme.typography.bodyMedium)
        if (progress != null && progress.stage != CollectStage.FAILED) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val currentIndex = COLLECT_STEPS.indexOfFirst { it.first == progress.stage }
                COLLECT_STEPS.forEachIndexed { index, (stage, label) ->
                    val done = progress.stage == CollectStage.DONE || index < currentIndex
                    val active = stage == progress.stage
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
        if (working) {
            val percent = progress?.percent
            if (percent != null) {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val elapsed = ((now - (progress?.startedAtMs ?: now)) / 1000).coerceAtLeast(0)
            val parts = buildList {
                percent?.let { add("$it%") }
                progress?.bytes?.let { add(String.format(Locale.ROOT, "%.1f MiB", it / 1048576.0)) }
                add(String.format(Locale.ROOT, "%d:%02d elapsed", elapsed / 60, elapsed % 60))
            }
            Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily)
        }
        progress?.let {
            Text(
                text = it.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                color = if (it.stage == CollectStage.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
