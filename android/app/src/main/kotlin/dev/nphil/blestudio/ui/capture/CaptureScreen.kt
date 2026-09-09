package dev.nphil.blestudio.ui.capture

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
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
import dev.nphil.blestudio.AppContainer
import dev.nphil.blestudio.hci.CollectStage
import dev.nphil.blestudio.hci.SnoopMode
import dev.nphil.blestudio.shell.ShizukuState
import dev.nphil.blestudio.ui.theme.MonoFamily
import java.util.Locale

private val IMPORT_TYPES = arrayOf("*/*")
private const val APP_ROWS = 60

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
                body = "The capture itself runs through Shizuku, but BLE Studio needs the Bluetooth permissions to " +
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
                text = "Only \"full\" keeps whole ACL payloads. \"filtered\" truncates exactly the vendor bytes you are after.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.snoopModeDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.setSnoopMode(SnoopMode.FULL) },
                    enabled = state.shellReady && !state.working,
                ) { Text("Enable full logging") }
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
            OutlinedButton(
                onClick = { viewModel.setSnoopMode(SnoopMode.DISABLED) },
                enabled = state.shellReady && !state.working,
            ) { Text("Disable HCI logging") }
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
                    is ShizukuState.PermissionNeeded -> "Shizuku is running. Authorise BLE Studio to use it."
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
                        Button(onClick = viewModel::requestShizukuPermission) { Text("Authorise BLE Studio") }

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
                    label = "Snoop mode",
                    value = capabilities.snoopMode,
                    mono = true,
                    tint = if (capabilities.snoopModeIsFull) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                FactRow("Log directory", capabilities.logDirectory.take(400), mono = true)
                FactRow("cmd bluetooth_manager", if (capabilities.bluetoothManagerShell) "available" else "not available")
                FactRow("dumpsys bluetooth_manager", if (capabilities.bluetoothManagerDumpsys) "available" else "not available")
                FactRow("bugreportz", capabilities.bugreportz ?: "not available", mono = true)
                capabilities.error?.let { FactRow("Probe error", it, tint = MaterialTheme.colorScheme.error) }
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
        val progress = state.progress
        if (state.working) {
            Text(state.busy ?: "Working", style = MaterialTheme.typography.bodyMedium)
            val percent = progress?.percent
            if (percent != null) {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        progress?.let {
            Text(
                text = "${it.stage.name.lowercase(Locale.ROOT).replace('_', ' ')}: ${it.message}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                color = if (it.stage == CollectStage.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
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
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
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
