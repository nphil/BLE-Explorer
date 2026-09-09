package dev.nphil.blueshark.ui.relay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.model.AttOperation
import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.EventDirection
import dev.nphil.blueshark.model.GattDatabase
import dev.nphil.blueshark.relay.RelayPhase
import dev.nphil.blueshark.relay.asciiGutter
import dev.nphil.blueshark.relay.groupedHex
import dev.nphil.blueshark.relay.shortUuid
import dev.nphil.blueshark.ui.theme.MonoFamily

/**
 * Screen for the man-in-the-middle GATT relay: configure a target, mirror it, and watch the vendor
 * app's traffic. Two panes on a tablet (controls beside the live timeline), one scrolling list on a
 * phone.
 */
@Composable
fun RelayScreen(
    container: AppContainer,
    expanded: Boolean,
    bluetoothGranted: Boolean,
    requestPermissions: () -> Unit,
) {
    if (!bluetoothGranted) {
        PermissionGate(requestPermissions)
        return
    }
    val model: RelayViewModel = viewModel(factory = remember(container) { RelayViewModel.factory(container) })
    val ui by model.ui.collectAsStateWithLifecycle()

    if (expanded) {
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(0.44f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RelayControls(ui, model)
            }
            VerticalDivider()
            LazyColumn(
                modifier = Modifier.weight(0.56f).fillMaxHeight(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                timelineItems(ui, model)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RelayControls(ui, model)
                }
            }
            timelineItems(ui, model)
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
        Icon(
            imageVector = Icons.Filled.Bluetooth,
            contentDescription = null,
            modifier = Modifier.height(48.dp),
        )
        Text("Bluetooth permissions required", style = MaterialTheme.typography.titleMedium)
        Text(
            "The relay needs Nearby devices access to connect to the target, to advertise as the " +
                "target and to host a local GATT server.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = requestPermissions,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
            Text("Grant permissions")
        }
    }
}

@Composable
private fun RelayControls(ui: RelayUiState, model: RelayViewModel) {
    ExperimentalNotice()
    ui.message?.let { text -> MessageCard(text, model::dismissMessage) }
    TargetCard(ui, model)
    ControlCard(ui, model)
    StatusCard(ui)
    ui.gatt?.let { database -> MirrorCard(ui, database, model) }
    SaveCard(ui, model)
}

@Composable
private fun ExperimentalNotice() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Experimental: a relay is not a clone", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "This phone advertises its own MAC address. Android cannot spoof it, so a vendor app " +
                    "that pins the device address will not accept the relay.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "The vendor phone must forget or remove the real device first: a cached GATT database, " +
                    "an existing bond or a pairing requirement will make it talk to the real device or " +
                    "refuse the relay outright. iOS is especially strict.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "The real device has to be powered off or out of range while the relay runs, otherwise " +
                    "the vendor app simply connects to it instead.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "An alias renames this phone's Bluetooth adapter for the duration of the run; the " +
                    "previous name is restored on stop.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MessageCard(text: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Info, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.defaultMinSize(48.dp, 48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss message")
            }
        }
    }
}

@Composable
private fun TargetCard(ui: RelayUiState, model: RelayViewModel) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Target", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = ui.address,
                onValueChange = model::onAddressChange,
                label = { Text("Device address") },
                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                singleLine = true,
                isError = ui.address.isNotEmpty() && !ui.addressValid,
                supportingText = {
                    Text(
                        if (ui.address.isEmpty() || ui.addressValid) {
                            "Six hexadecimal octets separated by colons"
                        } else {
                            "Not a Bluetooth address yet"
                        },
                    )
                },
                enabled = !ui.running,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ui.alias,
                onValueChange = model::onAliasChange,
                label = { Text("Advertised name") },
                placeholder = { Text(ui.adapterName ?: "Bluetooth adapter name") },
                singleLine = true,
                supportingText = { Text("Leave empty to keep this phone's Bluetooth name") },
                enabled = !ui.running,
                modifier = Modifier.fillMaxWidth(),
            )
            ui.advertisePlanText?.let { plan ->
                Text(plan, style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = model::previewTargetDevice,
                    enabled = ui.addressValid && !ui.previewing && !ui.running,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Preview target")
                }
                if (ui.previewing) {
                    CircularProgressIndicator(Modifier.height(24.dp).width(24.dp))
                    Text("Connecting", style = MaterialTheme.typography.bodySmall)
                }
            }
            val previewName = ui.previewName
            if (previewName != null || ui.previewMtu != null) {
                Text(
                    "Preview: ${previewName ?: "unnamed"}, ATT MTU ${ui.previewMtu ?: "?"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ControlCard(ui: RelayUiState, model: RelayViewModel) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = ui.acknowledged,
                    onCheckedChange = model::onAcknowledgedChange,
                    enabled = !ui.running,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "I understand the relay broadcasts this phone's address and may be rejected by the " +
                        "vendor app, and that the real device must be out of range.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = model::start,
                    enabled = ui.canStart,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).weight(1f),
                ) {
                    Text("Start relay")
                }
                OutlinedButton(
                    onClick = model::stop,
                    enabled = ui.running,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).weight(1f),
                ) {
                    Text("Stop relay")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(ui: RelayUiState) {
    val relay = ui.relay
    val phase = relay.phase
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (phase) {
                is RelayPhase.Failed -> MaterialTheme.colorScheme.errorContainer
                is RelayPhase.VendorConnected -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = when (phase) {
                is RelayPhase.Failed -> MaterialTheme.colorScheme.onErrorContainer
                is RelayPhase.VendorConnected -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(phase.label, style = MaterialTheme.typography.titleMedium)
            if (phase is RelayPhase.Failed) {
                Text(phase.reason, style = MaterialTheme.typography.bodyMedium)
            }
            relay.vendorAddress?.let { address ->
                Text("Vendor device $address", style = MaterialTheme.typography.bodyMedium, fontFamily = MonoFamily)
            }
            Text(
                "Target MTU ${relay.targetMtu} - vendor MTU ${relay.vendorMtu}",
                style = MaterialTheme.typography.bodySmall,
            )
            relay.advertiseSummary?.let { summary ->
                Text(summary, style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Counter("Reads", relay.counters.reads)
                Counter("Writes", relay.counters.writes)
                Counter("Notifies", relay.counters.notifies)
                Counter("Errors", relay.counters.errors)
            }
            if (relay.log.isNotEmpty()) {
                HorizontalDivider()
                relay.log.asReversed().take(8).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                    )
                }
            }
        }
    }
}

@Composable
private fun Counter(label: String, value: Int) {
    Column {
        Text("$value", style = MaterialTheme.typography.titleMedium, fontFamily = MonoFamily)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MirrorCard(ui: RelayUiState, database: GattDatabase, model: RelayViewModel) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mirrored database", style = MaterialTheme.typography.titleMedium)
            val skipped = ui.relay.skippedServiceUuids.ifEmpty { ui.previewSkipped }
            if (skipped.isNotEmpty()) {
                Text(
                    "Not mirrored (published by the local stack): ${skipped.joinToString { it.shortUuid() }}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Tap a service to include or exclude it from the advertising payload.",
                style = MaterialTheme.typography.bodySmall,
            )
            val advertised = ui.advertisedServiceUuids
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.mirroredServiceUuids.forEach { uuid ->
                    FilterChip(
                        selected = uuid in advertised,
                        onClick = { model.toggleAdvertised(uuid) },
                        label = { Text(uuid.shortUuid(), fontFamily = MonoFamily) },
                        enabled = !ui.running,
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            HorizontalDivider()
            database.services.forEach { service ->
                Text(
                    service.uuid.shortUuid(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.SemiBold,
                )
                service.characteristics.forEach { characteristic ->
                    Text(
                        "  ${characteristic.uuid.shortUuid()}  ${characteristic.properties.joinToString(",")}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveCard(ui: RelayUiState, model: RelayViewModel) {
    var menuOpen by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Evidence", style = MaterialTheme.typography.titleMedium)
            Text(
                "${ui.timeline.size} event(s) shown, ${ui.relay.counters.reads + ui.relay.counters.writes + ui.relay.counters.notifies} relayed packet(s) this run.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = ui.newSessionName,
                onValueChange = model::onNewSessionNameChange,
                label = { Text("New session name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = model::saveToNewSession,
                    enabled = !ui.saving,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Save to session")
                }
                Column {
                    OutlinedButton(
                        onClick = { menuOpen = true },
                        enabled = !ui.saving && ui.sessions.isNotEmpty(),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text("Append to...")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        ui.sessions.forEach { session ->
                            DropdownMenuItem(
                                text = { Text(session.name) },
                                onClick = {
                                    menuOpen = false
                                    model.appendToSession(session.id)
                                },
                            )
                        }
                    }
                }
            }
            TextButton(
                onClick = model::clearTimeline,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Clear timeline")
            }
        }
    }
}

private fun LazyListScope.timelineItems(
    ui: RelayUiState,
    model: RelayViewModel,
) {
    item {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Live timeline", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = model::clearTimeline) { Text("Clear") }
        }
    }
    if (ui.timeline.isEmpty()) {
        item {
            Text(
                "No relayed packets yet. Start the relay, then open the vendor app and let it connect " +
                    "to this phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        items(items = ui.timeline.asReversed(), key = { event -> event.id }) { event ->
            EventRow(event)
        }
    }
}

@Composable
private fun EventRow(event: BleEvent) {
    val toDevice = event.direction == EventDirection.PHONE_TO_DEVICE ||
        event.direction == EventDirection.LOCAL_TO_DEVICE
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (event.direction) {
                    EventDirection.PHONE_TO_DEVICE, EventDirection.LOCAL_TO_DEVICE -> Icons.AutoMirrored.Filled.ArrowForward
                    EventDirection.DEVICE_TO_PHONE, EventDirection.DEVICE_TO_LOCAL -> Icons.AutoMirrored.Filled.ArrowBack
                    EventDirection.SYSTEM -> Icons.Filled.Info
                },
                contentDescription = if (toDevice) "vendor app to device" else "device to vendor app",
                modifier = Modifier.height(18.dp).width(18.dp),
                tint = if (event.operation == AttOperation.ERROR) {
                    MaterialTheme.colorScheme.error
                } else if (toDevice) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                event.operation.name.lowercase().replace('_', ' '),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                event.characteristicUuid?.shortUuid() ?: "-",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = MonoFamily,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (event.payloadHex.isNotEmpty()) {
            // The timeline republishes every 150 ms while a relay is busy; the two derived strings
            // only change when the payload does.
            val grouped = remember(event.payloadHex) { event.payloadHex.groupedHex() }
            val gutter = remember(event.payloadHex) { "|${event.payloadHex.asciiGutter()}|" }
            Text(
                grouped,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                modifier = Modifier.padding(start = 26.dp),
            )
            Text(
                gutter,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 26.dp),
            )
        }
        val detail = buildString {
            if (event.note.isNotEmpty()) append(event.note)
            event.status?.let { status ->
                if (isNotEmpty()) append(" - ")
                append("status 0x")
                append(Integer.toHexString(status).uppercase())
            }
        }
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 26.dp),
            )
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}
