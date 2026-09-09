package dev.nphil.blueshark.ui.signal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.ble.ScannedDevice
import dev.nphil.blueshark.signal.SignalGrade
import dev.nphil.blueshark.ui.scan.Pill
import dev.nphil.blueshark.ui.scan.RssiChip
import dev.nphil.blueshark.ui.theme.MonoFamily

/**
 * Placement diagnostics for one peripheral: walk around with the tablet and watch what the radio
 * actually hears. The numbers are graded through a configurable proxy margin, because the device
 * that has to hold this link in production is usually a small ESP32 with a worse antenna than the
 * tablet in your hands.
 */
@Composable
fun SignalScreen(
    container: AppContainer,
    expanded: Boolean,
    bluetoothGranted: Boolean,
    requestPermissions: () -> Unit,
    initialAddress: String? = null,
) {
    if (!bluetoothGranted) {
        PermissionGate(requestPermissions)
        return
    }
    val model: SignalViewModel = viewModel(factory = remember(container) { SignalViewModel.factory(container) })
    val ui by model.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(model) {
        model.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    // Deep link from the Scan tab's "Signal" button; re-running it for the same address is a no-op.
    LaunchedEffect(initialAddress) {
        if (!initialAddress.isNullOrBlank()) model.setTarget(initialAddress, null)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        val address = ui.address
        if (address == null) {
            TargetPicker(ui, model, Modifier.padding(padding))
        } else {
            SignalBody(address, ui, model, expanded, Modifier.padding(padding))
        }
    }
}

@Composable
private fun PermissionGate(requestPermissions: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.height(48.dp))
        Text("Bluetooth permissions required", style = MaterialTheme.typography.titleMedium)
        Text(
            "Measuring a device's signal needs Nearby devices access: the advertisements are read " +
                "from a scan, and the connected reading from a GATT link.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = requestPermissions, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text("Grant permissions")
        }
    }
}

// ---- picker -------------------------------------------------------------------------------

@Composable
private fun TargetPicker(ui: SignalUiState, model: SignalViewModel, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { model.ensureScanning() }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pick the device you are placing", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Then walk the room. The dial shows what this tablet hears, graded as a " +
                        "proxy with a worse antenna would hear it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "search") {
            OutlinedTextField(
                value = ui.picker.query,
                onValueChange = model::setQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search name or address") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )
        }
        item(key = "status") {
            Text(
                text = if (ui.picker.scanning) {
                    "Scanning — ${ui.picker.totalSeen} devices heard"
                } else {
                    "Scanner idle — ${ui.picker.totalSeen} devices held"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(items = ui.picker.devices, key = { it.address }) { device ->
            CandidateRow(device) { model.setTarget(device.address, device.name) }
        }
        if (ui.picker.devices.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "Nothing heard yet. Keep the target powered and within a few metres to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CandidateRow(device: ScannedDevice, onPick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = device.name?.takeIf { it.isNotBlank() } ?: "Unnamed device",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RssiChip(device.rssi)
        }
    }
}

// ---- measuring ----------------------------------------------------------------------------

@Composable
private fun SignalBody(
    address: String,
    ui: SignalUiState,
    model: SignalViewModel,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val stats = ui.snapshot.stats
    val cells = remember(stats, ui.snapshot.connectedRssi) { statCells(stats, ui.snapshot.connectedRssi) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") { TargetHeader(address, ui, model) }

        item(key = "dial") {
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    DialBlock(ui, model, Modifier.weight(1f))
                    StatGrid(cells, columns = 3, modifier = Modifier.weight(1.2f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DialBlock(ui, model, Modifier.fillMaxWidth())
                    StatGrid(cells, columns = 2)
                }
            }
        }

        item(key = "trend") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Last ${stats.windowMs / 1_000} s", "dots raw · line smoothed · dashes fair floor")
                SignalSparkline(
                    history = ui.snapshot.history,
                    windowMs = stats.windowMs,
                    sinceLastMs = stats.sinceLastMs,
                    proxyMarginDb = ui.proxyMarginDb,
                )
            }
        }

        item(key = "presence") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Advertising presence", "one cell per second")
                PresenceStrip(ui.snapshot.presence)
                Text(
                    text = "Hollow cells are seconds with no advertisement. A device that goes quiet " +
                        "while a connection is up is being held by another central — a different fault " +
                        "from a weak link.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (ui.hints.isNotEmpty()) {
            item(key = "hints") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionTitle("What this looks like")
                        for (hint in ui.hints) HintRow(hint)
                    }
                }
            }
        }

        item(key = "waypoints") { WaypointSection(ui, model) }

        item(key = "margin") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                ProxyMarginSlider(
                    marginDb = ui.proxyMarginDb,
                    onChange = model::setProxyMargin,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        item(key = "actions") { ActionRow(ui, model) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetHeader(address: String, ui: SignalUiState, model: SignalViewModel) {
    val snapshot = ui.snapshot
    val phyLabels = remember(snapshot.phy) { snapshot.phy?.labels().orEmpty() }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = Icons.Filled.Radar,
                    contentDescription = null,
                    tint = gradeColor(snapshot.stats.grade),
                    modifier = Modifier.size(28.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = ui.name?.takeIf { it.isNotBlank() } ?: "Unnamed device",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = model::clearTarget) { Text("Change") }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LiveChip(ui)
                if (snapshot.connected) {
                    Pill(
                        text = "Connected",
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                        icon = Icons.Filled.Link,
                    )
                }
                snapshot.connectable?.let { Pill(if (it) "Connectable" else "Broadcast only") }
                for (label in phyLabels) Pill(label)
                snapshot.stats.sinceLastMs?.let { Pill("Last packet ${duration(it)}", mono = true) }
            }
        }
    }
}

@Composable
private fun LiveChip(ui: SignalUiState) {
    val scheme = MaterialTheme.colorScheme
    val lost = ui.snapshot.stats.grade == SignalGrade.LOST
    val text = when {
        !ui.monitoring -> "Paused"
        lost -> "Lost"
        else -> "Live"
    }
    Pill(
        text = text,
        container = if (lost || !ui.monitoring) scheme.errorContainer else scheme.primaryContainer,
        content = if (lost || !ui.monitoring) scheme.onErrorContainer else scheme.onPrimaryContainer,
    )
}

@Composable
private fun DialBlock(ui: SignalUiState, model: SignalViewModel, modifier: Modifier = Modifier) {
    val stats = ui.snapshot.stats
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SignalGauge(
            smoothedRssi = stats.smoothedRssi,
            grade = stats.grade,
            history = ui.snapshot.history,
            pulses = model.pulses,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stats.medianRssi?.let { "Through a ${ui.proxyMarginDb} dB proxy: ${it - ui.proxyMarginDb} dBm" }
                ?: "Nothing heard yet",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MonoFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.monitoring) {
            TextButton(onClick = model::stopMonitoring) { Text("Pause measuring") }
        } else {
            TextButton(onClick = model::startMonitoring) { Text("Resume measuring") }
        }
    }
}

@Composable
private fun WaypointSection(ui: SignalUiState, model: SignalViewModel) {
    val best = remember(ui.waypoints) {
        ui.waypoints.filter { it.stats.medianRssi != null }.maxByOrNull { it.stats.medianRssi!! }?.id
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Marked spots", "${ui.waypoints.size} recorded")
            Text(
                text = "Stand where the proxy would live, wait a few seconds, then mark the spot. " +
                    "Compare medians, not single readings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = ui.label,
                    onValueChange = model::setLabel,
                    modifier = Modifier.weight(1f),
                    label = { Text("Label") },
                    placeholder = { Text(defaultLabel(ui.waypoints.size)) },
                    singleLine = true,
                )
                Button(
                    onClick = { model.markSpot(ui.label) },
                    modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 132.dp),
                ) {
                    Icon(Icons.Filled.PushPin, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Mark spot", modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (ui.waypoints.isNotEmpty()) {
                HorizontalDivider()
                WaypointHeader()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (waypoint in ui.waypoints) {
                        WaypointRow(
                            waypoint = waypoint,
                            best = waypoint.id == best,
                            onRemove = { model.removeWaypoint(waypoint.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(ui: SignalUiState, model: SignalViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.snapshot.connected) {
                FilledTonalButton(onClick = model::disconnect, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Disconnect", modifier = Modifier.padding(start = 8.dp))
                }
            } else {
                FilledTonalButton(onClick = model::connect, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Connected RSSI", modifier = Modifier.padding(start = 8.dp))
                }
            }
            OutlinedButton(onClick = model::copyReport, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Copy report", modifier = Modifier.padding(start = 8.dp))
            }
            if (ui.ntfyEnabled) {
                OutlinedButton(onClick = model::sendReport, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Send to ntfy", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Text(
            text = "Connecting reads the RSSI from the link itself. Most peripherals stop advertising " +
                "while connected, so the advertisement stats freeze until you disconnect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}
