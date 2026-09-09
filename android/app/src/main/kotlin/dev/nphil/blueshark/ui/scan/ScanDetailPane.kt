package dev.nphil.blueshark.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nphil.blueshark.ble.CharacteristicRef
import dev.nphil.blueshark.ble.GattClient
import dev.nphil.blueshark.ble.ScannedDevice
import dev.nphil.blueshark.ble.displayName
import dev.nphil.blueshark.ble.shortUuid
import dev.nphil.blueshark.model.GattCharacteristicRecord
import dev.nphil.blueshark.model.GattServiceRecord
import dev.nphil.blueshark.model.toHex
import dev.nphil.blueshark.ui.theme.MonoFamily

private const val VISIBLE_EVENTS = 200

/** Actions the detail pane can raise; keeps the composable free of the ViewModel type. */
class DeviceDetailActions(
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onToggleService: (Int) -> Unit,
    val onRead: (CharacteristicRef) -> Unit,
    val onWrite: (CharacteristicRef, List<String>) -> Unit,
    val onSubscribe: (CharacteristicRef, Boolean) -> Unit,
    val onSaveToSession: () -> Unit,
    /** Hands this address to the Signal screen for placement diagnostics. */
    val onSignal: () -> Unit,
)

@Composable
fun DeviceDetailPane(
    address: String,
    device: ScannedDevice?,
    connection: ConnectionUiState,
    actions: DeviceDetailActions,
    modifier: Modifier = Modifier,
) {
    val connected = connection.phase == ConnectionPhase.CONNECTED && connection.address == address
    val connecting = connection.phase == ConnectionPhase.CONNECTING && connection.address == address
    val database = connection.database.takeIf { connected }
    val events = connection.events

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
    ) {
        item(key = "header") {
            DetailHeader(
                address = address,
                device = device,
                connected = connected,
                connecting = connecting,
                busyLabel = connection.busyLabel,
                failureReason = connection.failureReason.takeIf { connection.address == address },
                actions = actions,
            )
        }
        item(key = "facts") { ConnectionFactsCard(connection, connected) }

        if (database != null) {
            for (service in database.services) {
                item(key = "svc-${service.instanceId}") {
                    ServiceHeader(
                        service = service,
                        expanded = service.instanceId in connection.expandedServices,
                        onToggle = { actions.onToggleService(service.instanceId) },
                    )
                }
                if (service.instanceId in connection.expandedServices) {
                    items(
                        items = service.characteristics,
                        key = { "chr-${service.instanceId}-${it.instanceId}" },
                    ) { characteristic ->
                        CharacteristicCard(
                            ref = CharacteristicRef(
                                serviceUuid = service.uuid,
                                serviceInstanceId = service.instanceId,
                                uuid = characteristic.uuid,
                                instanceId = characteristic.instanceId,
                            ),
                            characteristic = characteristic,
                            lastValue = connection.values[characteristic.instanceId],
                            subscribed = connection.subscriptions.any { it.instanceId == characteristic.instanceId },
                            enabled = connected && !connection.busy,
                            actions = actions,
                        )
                    }
                }
            }
        }

        if (events.isNotEmpty()) {
            item(key = "events-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Live traffic", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${events.size} events",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(
                items = events.takeLast(VISIBLE_EVENTS).asReversed(),
                key = { it.id },
            ) { event -> EventRow(event, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun DetailHeader(
    address: String,
    device: ScannedDevice?,
    connected: Boolean,
    connecting: Boolean,
    busyLabel: String?,
    failureReason: String?,
    actions: DeviceDetailActions,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = device?.name?.takeIf { it.isNotBlank() } ?: "Unnamed device",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = address,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (device != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RssiChip(device.rssi)
                    Pill(if (device.connectable) "Connectable" else "Broadcast only")
                    Pill(if (device.legacy) "Legacy adv" else "Extended adv")
                    device.txPower?.let { Pill("Tx $it dBm") }
                    Pill("${device.packetCount} packets")
                    if (device.serviceUuids.isNotEmpty()) Pill("${device.serviceUuids.size} adv services")
                }
                if (device.manufacturerData.isNotEmpty()) {
                    for ((company, bytes) in device.manufacturerData) {
                        Column {
                            Text(
                                text = "Manufacturer 0x%04X".format(company),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HexPayload(bytes.toHex(), showAscii = false)
                        }
                    }
                }
            }
            if (failureReason != null && !connected && !connecting) {
                Text(
                    text = failureReason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // "Disconnect" next to "Save to session" overruns a 360 dp phone at larger font scales.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (connected) {
                    FilledTonalButton(
                        onClick = actions.onDisconnect,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Disconnect", modifier = Modifier.padding(start = 8.dp))
                    }
                } else {
                    Button(
                        onClick = actions.onConnect,
                        enabled = !connecting,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(if (connecting) "Connecting…" else "Connect", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                FilledTonalButton(
                    onClick = actions.onSignal,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Signal", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = actions.onSaveToSession,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Save to session", modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (connecting || busyLabel != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = busyLabel ?: "Connecting…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionFactsCard(connection: ConnectionUiState, connected: Boolean) {
    val facts = connection.facts
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Connection facts", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FactColumn("MTU", facts.negotiatedMtu?.let { "$it bytes" } ?: "—")
                FactColumn("PHY tx", facts.txPhy?.let { GattClient.phyName(it) } ?: "—")
                FactColumn("PHY rx", facts.rxPhy?.let { GattClient.phyName(it) } ?: "—")
                FactColumn("Slowest response", facts.maxObservedResponseMs?.let { "$it ms" } ?: "—")
                FactColumn("Connect attempts", facts.connectAttempts.size.toString())
                FactColumn(
                    "Last connect",
                    facts.reconnectSamplesMs.lastOrNull()?.let { "$it ms" } ?: "—",
                )
                FactColumn(
                    "Pairing",
                    when (facts.pairingRequired) {
                        true -> "Required"
                        false -> "Not required"
                        null -> "Unknown"
                    },
                )
                FactColumn(
                    "Write w/o response",
                    if (facts.writeWithoutResponseVerified) "Verified" else "Untested",
                )
            }
            if (!connected && connection.phase != ConnectionPhase.CONNECTING) {
                Text(
                    text = "Connect to read the attribute database.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ServiceHeader(service: GattServiceRecord, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = if (expanded) "Collapse service" else "Expand service", onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName(service.uuid),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = service.uuid,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Pill("${service.characteristics.size} chr")
    }
}

@Composable
private fun CharacteristicCard(
    ref: CharacteristicRef,
    characteristic: GattCharacteristicRecord,
    lastValue: String?,
    subscribed: Boolean,
    enabled: Boolean,
    actions: DeviceDetailActions,
) {
    val canRead = "READ" in characteristic.properties
    val canWrite = "WRITE" in characteristic.properties || "WRITE_NO_RESPONSE" in characteristic.properties
    val canSubscribe = "NOTIFY" in characteristic.properties || "INDICATE" in characteristic.properties

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = displayName(characteristic.uuid),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = characteristic.uuid,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (property in characteristic.properties) PropertyChip(property)
                if (characteristic.properties.isEmpty()) Pill("No properties")
            }
            if (characteristic.descriptors.isNotEmpty()) {
                Column {
                    Text(
                        text = "Descriptors",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (descriptor in characteristic.descriptors) {
                        Text(
                            text = "${shortUuid(descriptor.uuid)}  ${displayName(descriptor.uuid)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = MonoFamily,
                        )
                    }
                }
            }
            if (lastValue != null) {
                HorizontalDivider()
                Column {
                    Text(
                        text = "Last value (${lastValue.length / 2} bytes)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HexPayload(lastValue)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRead) {
                    OutlinedButton(
                        onClick = { actions.onRead(ref) },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Read", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (canWrite) {
                    OutlinedButton(
                        onClick = { actions.onWrite(ref, characteristic.properties) },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Write", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (canSubscribe) {
                    OutlinedButton(
                        onClick = { actions.onSubscribe(ref, !subscribed) },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            imageVector = if (subscribed) Icons.Filled.NotificationsOff else Icons.Filled.NotificationsActive,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(if (subscribed) "Unsubscribe" else "Subscribe", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun DetailPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Pick a device to inspect its GATT database.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

