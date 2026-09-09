package dev.nphil.blueshark.ui.probe

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.probe.FrameCodec
import dev.nphil.blueshark.probe.FrameCodecs
import dev.nphil.blueshark.ui.scan.Pill
import dev.nphil.blueshark.ui.scan.RssiChip
import dev.nphil.blueshark.ui.sessions.DropdownField
import dev.nphil.blueshark.ui.sessions.SwitchRow
import dev.nphil.blueshark.ui.signal.SectionTitle
import dev.nphil.blueshark.ui.theme.MonoFamily

/**
 * Command Prober: write candidate frames to one characteristic and let the device's own framed
 * status say which opcodes exist.
 *
 * The panel is not the oracle here - the reply is. A device from this family answers every frame
 * with a status byte, and DATA_ID_ERROR versus SUCCESS separates a real opcode from a nonexistent
 * one without anybody having to watch the hardware. What the operator does watch, and types into
 * each row afterwards, is the *effect*: which of the accepted opcodes changed the mode, the
 * brightness or nothing at all.
 */
@Composable
fun ProbeScreen(
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
    // One runner per process, shared with the device-project funnel that hosts its own copy of
    // this view model. Two instances would mean two response windows and two session selections
    // over a single radio, and the operator would have no way to tell which one held their run.
    // The activity's store outlives both routes, so navigating between them lands on the same one.
    val owner = LocalActivity.current as? ViewModelStoreOwner
        ?: checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner for the Probe screen" }
    val model: ProbeViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = PROBE_RUNNER_KEY,
        factory = remember(container) { ProbeViewModel.factory(container) },
    )
    val ui by model.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(model) {
        model.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    // Deep link from the Scan tab's "Probe" button; re-running it for the same address is a no-op.
    LaunchedEffect(initialAddress) {
        if (!initialAddress.isNullOrBlank()) model.setTarget(initialAddress, null)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        val address = ui.address
        if (address == null) {
            TargetPicker(ui, model, Modifier.padding(padding))
        } else {
            ProbeBody(address, ui, model, expanded, Modifier.padding(padding))
        }
    }

    ui.confirm?.let { confirm ->
        SweepConfirmDialog(
            confirm = confirm,
            onConfirm = model::confirmSweep,
            onDismiss = model::dismissConfirmation,
        )
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
            "Probing a device needs Nearby devices access: the frames go out over a GATT link and " +
                "the answers come back as notifications on the same characteristic.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = requestPermissions, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text("Grant permissions")
        }
    }
}

// ---- picker -------------------------------------------------------------------------------

@Composable
private fun TargetPicker(ui: ProbeUiState, model: ProbeViewModel, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { model.ensureScanning() }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pick the device to probe", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "${ui.picker.devices.size} of ${ui.picker.totalSeen} seen" +
                        if (ui.picker.scanning) " · scanning" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "search") {
            OutlinedTextField(
                value = ui.picker.query,
                onValueChange = model::setQuery,
                label = { Text("Name or address") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(ui.picker.devices, key = { it.address }) { candidate ->
            CandidateRow(candidate) { model.setTarget(candidate.address, candidate.name) }
        }
    }
}

@Composable
private fun CandidateRow(candidate: ProbeCandidate, onPick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = candidate.name?.takeIf(String::isNotBlank) ?: "Unnamed device",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = candidate.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!candidate.connectable) Pill("Broadcast only")
            RssiChip(candidate.rssi)
        }
    }
}

// ---- probing ------------------------------------------------------------------------------

@Composable
private fun ProbeBody(
    address: String,
    ui: ProbeUiState,
    model: ProbeViewModel,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val lateByStep = remember(ui.lateResponses) { ui.lateResponses.groupingCountByStep() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") { TargetHeader(address, ui, model) }

        item(key = "setup") {
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    SetupCard(ui, model, Modifier.weight(1f))
                    TimingCard(ui, model, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SetupCard(ui, model, Modifier.fillMaxWidth())
                    TimingCard(ui, model, Modifier.fillMaxWidth())
                }
            }
        }

        item(key = "run") { RunCard(ui, model) }

        if (ui.outcomes.isNotEmpty()) {
            item(key = "results-title") {
                SectionTitle(
                    text = "Results",
                    trailing = "${ui.acceptedOpcodes.size} accepted · ${ui.outcomes.size} steps",
                )
            }
            itemsIndexed(ui.outcomes, key = { index, _ -> "${ui.runId}-$index" }) { index, outcome ->
                OutcomeRow(
                    index = index,
                    opcode = outcome.step.opcode,
                    label = outcome.step.label,
                    canary = outcome.step.canary,
                    sentHex = outcome.sentHex,
                    responseHex = outcome.responseHex,
                    verdict = outcome.verdict,
                    statusByte = outcome.statusByte,
                    elapsedMs = outcome.elapsedMs,
                    note = outcome.note,
                    observedEffect = outcome.observedEffect,
                    lateCount = lateByStep[index] ?: 0,
                    expanded = expanded,
                    resetKey = ui.runId,
                    onEffectChange = { text -> model.setObservedEffect(index, text) },
                )
            }
            item(key = "legend") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("What the verdicts mean")
                        VerdictLegend()
                    }
                }
            }
        }

        if (ui.lateResponses.isNotEmpty()) item(key = "late") { LateResponsesCard(ui) }

        item(key = "value-sweep") { ValueSweepCard(ui, model) }

        item(key = "actions") { ActionsCard(ui, model) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetHeader(address: String, ui: ProbeUiState, model: ProbeViewModel) {
    val target = ui.target
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = ui.name?.takeIf(String::isNotBlank) ?: "Unnamed device",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = address,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill(
                    text = when (ui.link) {
                        ProbeLinkPhase.CONNECTED -> "Connected"
                        ProbeLinkPhase.CONNECTING -> "Connecting"
                        ProbeLinkPhase.FAILED -> "Link failed"
                        ProbeLinkPhase.IDLE -> "Not connected"
                    },
                    container = if (ui.connected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    content = if (ui.connected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (target != null) {
                    Pill(text = "${target.serviceLabel} / ${target.label}", mono = true)
                    for (property in target.properties) {
                        Pill(
                            text = property,
                            container = MaterialTheme.colorScheme.surfaceVariant,
                            content = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!target.canNotify) {
                        Pill(
                            text = "No notify — cannot probe",
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            if (ui.failureReason != null && !ui.connected) {
                Text(ui.failureReason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.connected) {
                    FilledTonalButton(onClick = model::disconnect, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Disconnect", modifier = Modifier.padding(start = 8.dp))
                    }
                } else {
                    Button(
                        onClick = model::connect,
                        enabled = ui.link != ProbeLinkPhase.CONNECTING,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = if (ui.link == ProbeLinkPhase.CONNECTING) "Connecting…" else "Connect",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                OutlinedButton(onClick = model::clearTarget, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Change device")
                }
            }
        }
    }
}

@Composable
private fun SetupCard(ui: ProbeUiState, model: ProbeViewModel, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Where the frames go")
            if (!ui.connected) {
                Text(
                    text = "Connect to read the attribute database; the characteristic list comes from " +
                        "this device's own discovery, never from a guess.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (ui.targets.isEmpty()) {
                Text(
                    text = "This device exposes no writable characteristic — there is nothing to probe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                ChipChoice(
                    label = "Characteristic",
                    options = ui.targets,
                    selected = ui.target,
                    enabled = !ui.running,
                    optionLabel = { option ->
                        buildString {
                            append(option.serviceLabel).append('/').append(option.label)
                            if (!option.canNotify) append(" ⚠")
                        }
                    },
                    onSelect = { option -> model.selectCharacteristic(option.ref) },
                )
            }
            ChipChoice(
                label = "Framing",
                options = FrameCodecs.all,
                selected = ui.codec,
                enabled = !ui.running,
                optionLabel = FrameCodec::label,
                onSelect = { codec -> model.setCodec(codec.id) },
            )
            HorizontalDivider()
            SwitchRow(
                label = "Include risky opcodes",
                checked = ui.includeRisky,
                onCheckedChange = model::setIncludeRisky,
            )
            Text(
                text = "Off by default. The built-in sweep never contains them; switching this on only " +
                    "unlocks aiming a value sweep at one, and the confirmation names it first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimingCard(ui: ProbeUiState, model: ProbeViewModel, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Timing")
            SliderRow(
                label = "Response window",
                value = "${ui.responseTimeoutMs} ms",
                position = ui.responseTimeoutMs,
                range = MIN_RESPONSE_TIMEOUT_MS..MAX_RESPONSE_TIMEOUT_MS,
                stepMs = 100L,
                enabled = !ui.running,
                onChange = model::setResponseTimeout,
            )
            SliderRow(
                label = "Silence between steps",
                value = "${ui.interStepDelayMs} ms",
                position = ui.interStepDelayMs,
                range = MIN_INTER_STEP_DELAY_MS..MAX_INTER_STEP_DELAY_MS,
                stepMs = 100L,
                enabled = !ui.running,
                onChange = model::setInterStepDelay,
            )
            Text(
                text = "One step at a time, always. A frame that arrives after its own window has " +
                    "closed is listed as a late response against the step it missed — it is never " +
                    "counted as the next step's answer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    position: Long,
    range: LongRange,
    stepMs: Long,
    enabled: Boolean,
    onChange: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val notches = ((range.last - range.first) / stepMs).toInt() - 1
        Slider(
            value = position.toFloat(),
            onValueChange = { picked -> onChange(picked.toLong()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = notches.coerceAtLeast(0),
            enabled = enabled,
        )
    }
}

@Composable
private fun RunCard(ui: ProbeUiState, model: ProbeViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(
                text = "Opcode sweep",
                trailing = if (ui.runLabel.isBlank()) null else ui.runLabel,
            )
            Text(
                text = "Every candidate opcode is written once with a harmless argument, and the " +
                    "device's status byte decides: SUCCESS means the opcode exists, DATA_ID_ERROR " +
                    "means it does not. Known-good canary frames are interleaved so a dead link can " +
                    "never masquerade as a wall of nonexistent opcodes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.running) SweepProgress(ui.stepNumber, ui.stepTotal, ui.stepLabel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ui.running) {
                    Button(
                        onClick = model::stopSweep,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Stop", modifier = Modifier.padding(start = 8.dp))
                    }
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Button(
                        onClick = model::requestOpcodeSweep,
                        enabled = ui.canStart,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Start sweep", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (!ui.running && !ui.canStart) {
                    Icon(
                        Icons.Filled.NotificationsActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Needs a connected, writable characteristic that can notify.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LateResponsesCard(ui: ProbeUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Late responses", "${ui.lateResponses.size} frames")
            Text(
                text = "These arrived after the window of the step they were awaited under had already " +
                    "closed. They are kept here rather than credited to any step: guessing which " +
                    "frame answered which write is how a sweep produces a wrong opcode map.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (late in ui.lateResponses) LateResponseRow(late)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValueSweepCard(ui: ProbeUiState, model: ProbeViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Value sweep", "one opcode, many values")
            Text(
                text = "Once an opcode is accepted, this is how its argument gets a meaning: send the " +
                    "same opcode across a value list and write down what the device did each time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.acceptedOpcodes.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Accepted:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (opcode in ui.acceptedOpcodes) {
                        Pill(
                            text = "0x%02X".format(opcode),
                            mono = true,
                            modifier = Modifier.clickable { model.setValueOpcode("%02X".format(opcode)) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = ui.valueOpcodeHex,
                onValueChange = model::setValueOpcode,
                label = { Text("Opcode (hex byte)") },
                placeholder = { Text("08") },
                singleLine = true,
                enabled = !ui.running,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ui.valueList,
                onValueChange = model::setValueList,
                label = { Text("Values (hex, comma-separated)") },
                placeholder = { Text("0x00,0x40,0x80,0xFF") },
                singleLine = true,
                enabled = !ui.running,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = model::requestValueSweep,
                enabled = ui.canStart,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Sweep values", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsCard(ui: ProbeUiState, model: ProbeViewModel) {
    val labelled = remember(ui.sessions) {
        ui.sessions.associateBy { "${it.name} · ${it.id.take(8)}" }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Keep the evidence")
            DropdownField(
                label = "Save into session",
                selected = labelled.entries.firstOrNull { it.value.id == ui.sessionId }?.key,
                options = labelled.keys.toList(),
                onSelect = { key -> model.selectSession(key?.let { labelled[it]?.id }) },
                emptyLabel = "New session",
            )
            if (ui.savedTo != null) {
                Text(
                    text = "Last run saved to \"${ui.savedTo}\" — it exports with the rest of the bundle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = model::saveRun,
                    enabled = ui.outcomes.isNotEmpty() && !ui.saving,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (ui.saving) "Saving…" else "Save run", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = model::copyReport,
                    enabled = ui.outcomes.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Copy report", modifier = Modifier.padding(start = 8.dp))
                }
                if (ui.ntfyEnabled) {
                    TextButton(
                        onClick = model::sendReport,
                        enabled = ui.outcomes.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Send to ntfy")
                    }
                }
            }
        }
    }
}

/** Late frames per step index, so a row can show how many it missed without rescanning the list. */
private fun List<LateResponse>.groupingCountByStep(): Map<Int, Int> {
    val counts = HashMap<Int, Int>()
    for (late in this) {
        val index = late.stepIndex ?: continue
        counts[index] = (counts[index] ?: 0) + 1
    }
    return counts
}
