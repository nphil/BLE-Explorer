package dev.nphil.blestudio.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nphil.blestudio.model.CaptureSession
import dev.nphil.blestudio.model.GattServiceRecord
import dev.nphil.blestudio.model.WriteType
import dev.nphil.blestudio.ui.theme.MonoFamily

@Composable
internal fun DeviceTab(
    session: CaptureSession,
    expanded: Boolean,
    viewModel: SessionsViewModel,
    modifier: Modifier = Modifier,
) {
    if (expanded) {
        Row(modifier) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                identitySection(session, viewModel)
                environmentSection(session)
                gattSection(session)
            }
            VerticalDivider()
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                connectionSection(session, viewModel)
                protocolSection(session, viewModel)
            }
        }
    } else {
        LazyColumn(modifier, contentPadding = PaddingValues(bottom = 32.dp)) {
            identitySection(session, viewModel)
            connectionSection(session, viewModel)
            protocolSection(session, viewModel)
            environmentSection(session)
            gattSection(session)
        }
    }
}

private fun LazyListScope.identitySection(session: CaptureSession, viewModel: SessionsViewModel) {
    item(key = "identity") {
        Column {
            SectionHeader("Device identity", "Captured facts; only the alias is yours to set")
            Column(Modifier.padding(horizontal = 16.dp)) {
                EvidenceTextField(
                    resetKey = session.id,
                    label = "Alias (used as the Home Assistant device name)",
                    value = session.device.alias.orEmpty(),
                    onChange = viewModel::updateDeviceAlias,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                LabeledValue("Advertised name", session.device.name ?: "unknown")
                LabeledValue("Address", session.device.address.ifEmpty { "unknown" }, mono = true)
                LabeledValue("Address type", session.device.addressType ?: "unknown")
                LabeledValue("Bonded", if (session.device.bonded) "yes" else "no")
                session.device.manufacturer?.let { LabeledValue("Manufacturer", it) }
                session.device.model?.let { LabeledValue("Model", it) }
                session.device.firmware?.let { LabeledValue("Firmware", it) }
                session.device.appearance?.let { LabeledValue("Appearance", "0x%04X".format(it)) }
                if (session.device.advertisedServiceUuids.isNotEmpty()) {
                    LabeledValue(
                        "Advertised services",
                        session.device.advertisedServiceUuids.joinToString(", ") { shortUuid(it) },
                        mono = true,
                    )
                }
                session.device.manufacturerData.forEach { (company, hex) ->
                    LabeledValue("Mfr data 0x%04X".format(company), hexGrouped(hex, 12), mono = true)
                }
                session.device.serviceData.forEach { (uuid, hex) ->
                    LabeledValue("Svc data ${shortUuid(uuid)}", hexGrouped(hex, 12), mono = true)
                }
                LabeledValue("Advertisement samples", session.advertisements.size.toString())
                EvidenceTextField(
                    resetKey = session.id,
                    label = "Session notes",
                    value = session.notes,
                    onChange = viewModel::updateSessionNotes,
                    lines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

private fun LazyListScope.connectionSection(session: CaptureSession, viewModel: SessionsViewModel) {
    item(key = "connection") {
        val facts = session.connection
        Column {
            SectionHeader("Connection facts", "What the radio actually did, plus the limits you measured")
            Column(Modifier.padding(horizontal = 16.dp)) {
                LabeledValue("Negotiated MTU", facts.negotiatedMtu?.toString() ?: "unknown")
                LabeledValue(
                    "PHY",
                    listOfNotNull(
                        facts.txPhy?.let { "tx $it" },
                        facts.rxPhy?.let { "rx $it" },
                    ).joinToString(" · ").ifEmpty { "unknown" },
                )
                LabeledValue("Slowest response", facts.maxObservedResponseMs?.let { "$it ms" } ?: "unknown")
                if (facts.reconnectSamplesMs.isNotEmpty()) {
                    LabeledValue(
                        "Reconnect samples",
                        facts.reconnectSamplesMs.joinToString(", ") { "$it ms" },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LongField(
                        resetKey = session.id,
                        label = "Idle disconnect (ms)",
                        value = facts.idleDisconnectMs,
                        onChange = { value -> viewModel.updateConnection { it.copy(idleDisconnectMs = value) } },
                        modifier = Modifier.weight(1f),
                    )
                    LongField(
                        resetKey = session.id,
                        label = "Min spacing (ms)",
                        value = facts.minInterCommandMs,
                        onChange = { value -> viewModel.updateConnection { it.copy(minInterCommandMs = value) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                SwitchRow(
                    label = "Write-without-response verified to take effect",
                    checked = facts.writeWithoutResponseVerified,
                    onCheckedChange = { verified ->
                        viewModel.updateConnection { it.copy(writeWithoutResponseVerified = verified) }
                    },
                )
                TristateRow(
                    label = "Pairing required before commands work",
                    value = facts.pairingRequired,
                    onChange = { value -> viewModel.updateConnection { it.copy(pairingRequired = value) } },
                )
                Text(
                    "Preferred write type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Four chips ("Unknown" plus three write types) do not fit a 360 dp phone in a Row.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = facts.preferredWriteType == null,
                        onClick = { viewModel.updateConnection { it.copy(preferredWriteType = null) } },
                        label = { Text("Unknown") },
                    )
                    WriteType.entries.forEach { type ->
                        FilterChip(
                            selected = facts.preferredWriteType == type,
                            onClick = { viewModel.updateConnection { it.copy(preferredWriteType = type) } },
                            label = { Text(writeTypeLabel(type)) },
                        )
                    }
                }
                EvidenceTextField(
                    resetKey = session.id,
                    label = "Connection notes",
                    value = facts.notes,
                    onChange = { notes -> viewModel.updateConnection { it.copy(notes = notes) } },
                    lines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (facts.connectAttempts.isNotEmpty()) {
                    Text(
                        "Connect attempts",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    facts.connectAttempts.takeLast(8).forEach { attempt ->
                        LabeledValue(
                            label = formatDateTime(attempt.startedEpochMs),
                            value = buildString {
                                append(if (attempt.success) "connected" else "failed")
                                append(" in ").append(attempt.durationMs).append(" ms · ")
                                append(attempt.source.name)
                                attempt.error?.let { append(" · ").append(it) }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.protocolSection(session: CaptureSession, viewModel: SessionsViewModel) {
    item(key = "protocol") {
        val protocol = session.protocol
        Column {
            SectionHeader(
                "Protocol hypotheses",
                "Framing guesses stay separate from tested commands and are never exported as fact",
            )
            Column(Modifier.padding(horizontal = 16.dp)) {
                EvidenceTextField(
                    resetKey = session.id,
                    label = "Framing notes",
                    value = protocol.framingNotes,
                    onChange = { notes -> viewModel.updateProtocol { it.copy(framingNotes = notes) } },
                    lines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                HexField(
                    resetKey = session.id,
                    label = "Header bytes",
                    value = protocol.headerHex,
                    onChange = { hex -> viewModel.updateProtocol { it.copy(headerHex = hex) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    IntField(
                        resetKey = session.id,
                        label = "Length offset",
                        value = protocol.lengthByteOffset,
                        onChange = { value -> viewModel.updateProtocol { it.copy(lengthByteOffset = value) } },
                        modifier = Modifier.weight(1f),
                    )
                    IntField(
                        resetKey = session.id,
                        label = "Sequence offset",
                        value = protocol.sequenceByteOffset,
                        onChange = { value -> viewModel.updateProtocol { it.copy(sequenceByteOffset = value) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                TristateRow(
                    label = "Length field counts the header",
                    value = protocol.lengthIncludesHeader,
                    onChange = { value -> viewModel.updateProtocol { it.copy(lengthIncludesHeader = value) } },
                )
                TristateRow(
                    label = "Multi-byte fields are little-endian",
                    value = protocol.littleEndian,
                    onChange = { value -> viewModel.updateProtocol { it.copy(littleEndian = value) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    EvidenceTextField(
                        resetKey = session.id,
                        label = "Checksum hypothesis",
                        value = protocol.checksumHypothesis.orEmpty(),
                        onChange = { text ->
                            viewModel.updateProtocol {
                                it.copy(checksumHypothesis = text.trim().ifEmpty { null })
                            }
                        },
                        modifier = Modifier.weight(1.6f),
                    )
                    IntField(
                        resetKey = session.id,
                        label = "Checksum offset",
                        value = protocol.checksumByteOffset,
                        onChange = { value -> viewModel.updateProtocol { it.copy(checksumByteOffset = value) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                TristateRow(
                    label = "Handshake requires pairing first",
                    value = protocol.requiresPairing,
                    onChange = { value -> viewModel.updateProtocol { it.copy(requiresPairing = value) } },
                )
                Text(
                    "Handshake commands, in order",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (session.commands.isEmpty()) {
                    Text(
                        "Catalogue a command first, then mark the ones that must be sent on connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                session.commands.forEach { command ->
                    val position = protocol.handshakeCommandIds.indexOf(command.id)
                    FilterChip(
                        selected = position >= 0,
                        onClick = {
                            viewModel.updateProtocol { current ->
                                val ids = current.handshakeCommandIds
                                current.copy(
                                    handshakeCommandIds = if (position >= 0) {
                                        ids - command.id
                                    } else {
                                        ids + command.id
                                    },
                                )
                            }
                        },
                        label = {
                            Text(
                                if (position >= 0) {
                                    "${position + 1}. ${command.name}"
                                } else {
                                    command.name.ifBlank { "(unnamed)" }
                                },
                            )
                        },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                EvidenceTextField(
                    resetKey = session.id,
                    label = "Encryption notes",
                    value = protocol.encryptionNotes,
                    onChange = { notes -> viewModel.updateProtocol { it.copy(encryptionNotes = notes) } },
                    lines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

private fun LazyListScope.environmentSection(session: CaptureSession) {
    val environment = session.environment ?: return
    item(key = "environment") {
        Column {
            SectionHeader("Capture environment", "Recorded so the capture can be reproduced")
            Column(Modifier.padding(horizontal = 16.dp)) {
                LabeledValue("Android", "${environment.androidRelease} (API ${environment.androidSdk})")
                LabeledValue("Device", environment.deviceModel)
                LabeledValue("Build", environment.buildFingerprint, mono = true)
                environment.vendorAppPackage?.let { LabeledValue("Vendor app", it, mono = true) }
                environment.vendorAppVersion?.let { LabeledValue("Vendor app version", it) }
                environment.hciSnoopMode?.let { LabeledValue("HCI snoop mode", it) }
            }
        }
    }
}

private fun LazyListScope.gattSection(session: CaptureSession) {
    val gatt = session.gatt
    item(key = "gatt-header") {
        SectionHeader(
            "GATT database",
            if (gatt == null) {
                "Not discovered yet"
            } else {
                "${gatt.services.size} services · captured ${formatDateTime(gatt.capturedAtEpochMs)}"
            },
        )
    }
    if (gatt == null) {
        item(key = "gatt-empty") {
            EmptyHint(
                title = "No GATT database",
                body = "Connect to the device on the Capture screen to discover services and characteristics.",
            )
        }
        return
    }
    items(gatt.services.size, key = { index -> "svc-${gatt.services[index].uuid}-$index" }) { index ->
        GattServiceCard(gatt.services[index])
    }
}

@Composable
private fun GattServiceCard(service: GattServiceRecord) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${shortUuid(service.uuid)} · ${service.type}",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = MonoFamily,
            )
            Text(
                service.uuid,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            service.characteristics.forEach { characteristic ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(
                    shortUuid(characteristic.uuid),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = MonoFamily,
                )
                Text(
                    characteristic.properties.joinToString(" · ").ifEmpty { "no properties reported" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (characteristic.descriptors.isNotEmpty()) {
                    Text(
                        "descriptors: ${characteristic.descriptors.joinToString(", ") { shortUuid(it.uuid) }}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
