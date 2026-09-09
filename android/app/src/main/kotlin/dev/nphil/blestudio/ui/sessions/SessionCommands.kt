package dev.nphil.blestudio.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nphil.blestudio.export.CommandAnalyzer
import dev.nphil.blestudio.export.HaProfileBuilder
import dev.nphil.blestudio.export.SuggestedCommand
import dev.nphil.blestudio.model.CaptureSession
import dev.nphil.blestudio.model.CommandSpec
import dev.nphil.blestudio.model.EvidenceStage
import dev.nphil.blestudio.model.WriteType
import dev.nphil.blestudio.ui.theme.MonoFamily

@Composable
internal fun CommandsTab(
    state: SessionsUiState,
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
                suggestionSection(state, session, viewModel)
            }
            VerticalDivider()
            LazyColumn(
                modifier = Modifier.weight(1.4f).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                catalogueSection(session, viewModel)
            }
        }
    } else {
        LazyColumn(modifier, contentPadding = PaddingValues(bottom = 32.dp)) {
            suggestionSection(state, session, viewModel)
            catalogueSection(session, viewModel)
        }
    }
}

private fun LazyListScope.suggestionSection(
    state: SessionsUiState,
    session: CaptureSession,
    viewModel: SessionsViewModel,
) {
    item(key = "suggested-header") {
        SectionHeader(
            title = "Suggested from capture",
            subtitle = "Repeated writes grouped by attribute and payload, newest evidence first",
        )
    }
    if (state.suggestions.isEmpty()) {
        item(key = "suggested-empty") {
            EmptyHint(
                title = "Nothing to suggest",
                body = "No outbound writes have been captured in this session yet.",
            )
        }
    }
    items(state.suggestions, key = { "${it.characteristicUuid}:${it.attributeHandle}:${it.payloadHex}" }) { suggestion ->
        SuggestionCard(
            suggestion = suggestion,
            promoted = isPromoted(session, suggestion),
            onPromote = { viewModel.promote(suggestion) },
        )
    }
}

private fun LazyListScope.catalogueSection(session: CaptureSession, viewModel: SessionsViewModel) {
    item(key = "catalogue-header") {
        SectionHeader(
            title = "Command catalogue",
            subtitle = "${session.commands.size} commands · " +
                "${session.commands.count { it.stage == EvidenceStage.DEVICE_TESTED }} device-tested",
        )
    }
    if (session.commands.isEmpty()) {
        item(key = "catalogue-empty") {
            EmptyHint(
                title = "No commands yet",
                body = "Promote a suggestion, or open an event on the timeline and create a command from it.",
            )
        }
    }
    items(session.commands, key = { it.id }) { command ->
        CommandCard(
            command = command,
            onEdit = { transform -> viewModel.editCommand(command.id, transform) },
            onStage = { stage, verified -> viewModel.setStage(command.id, stage, verified) },
            onReplay = { viewModel.incrementReplayCount(command.id) },
            onDelete = { viewModel.deleteCommand(command.id) },
        )
    }
}

@Composable
private fun SuggestionCard(
    suggestion: SuggestedCommand,
    promoted: Boolean,
    onPromote: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channelLabel(
                        suggestion.characteristicUuid
                            ?: suggestion.attributeHandle?.let { "#%04X".format(it) }
                            ?: UNKNOWN_CHANNEL,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "  ×${suggestion.count}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (promoted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                        Text("In catalogue", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    FilledTonalButton(onClick = onPromote, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Promote")
                    }
                }
            }
            Text(
                hexGrouped(suggestion.payloadHex, 24),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = MonoFamily,
            )
            Text(
                buildString {
                    append(writeTypeLabel(suggestion.writeType))
                    append(" · first ").append(formatClockMicros(suggestion.firstSeenEpochMicros))
                    suggestion.nearestMarkerLabel?.let { append(" · marker \"").append(it).append('"') }
                    if (suggestion.observedLatenciesMs.isNotEmpty()) {
                        append(" · response ")
                        append(suggestion.observedLatenciesMs.min())
                        append('–')
                        append(suggestion.observedLatenciesMs.max())
                        append(" ms")
                    }
                    suggestion.response?.payloadPrefixHex?.let {
                        append(" · reply starts ").append(hexGrouped(it, 8))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CommandCard(
    command: CommandSpec,
    onEdit: ((CommandSpec) -> CommandSpec) -> Unit,
    onStage: (EvidenceStage, Boolean) -> Unit,
    onReplay: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember(command.id) { mutableStateOf(false) }
    var verified by remember(command.id) { mutableStateOf(command.stage == EvidenceStage.DEVICE_TESTED) }
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        command.name.ifBlank { "(unnamed)" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${shortUuid(command.characteristicUuid)} · ${hexGrouped(command.payloadHex, 8)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StageBadge(command.stage)
                IconButton(onClick = { open = !open }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (open) "Collapse ${command.name}" else "Edit ${command.name}",
                    )
                }
            }
            if (!open) return@Column

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            EvidenceTextField(
                resetKey = command.id,
                label = "Name",
                value = command.name,
                onChange = { name -> onEdit { it.copy(name = name) } },
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            )
            LabeledValue("Service", shortUuid(command.serviceUuid), mono = true)
            LabeledValue("Characteristic", shortUuid(command.characteristicUuid), mono = true)
            LabeledValue("Payload", hexGrouped(command.payloadHex), mono = true)
            LabeledValue("Observed", "${command.observedCount}×", mono = false)

            Text(
                "Write type",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                WriteType.entries.forEach { type ->
                    FilterChip(
                        selected = command.writeType == type,
                        onClick = { onEdit { it.copy(writeType = type) } },
                        label = { Text(writeTypeLabel(type)) },
                    )
                }
            }

            Text(
                "Evidence stage",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EvidenceStage.entries.forEach { stage ->
                    FilterChip(
                        selected = command.stage == stage,
                        enabled = stage != EvidenceStage.DEVICE_TESTED || verified,
                        onClick = { onStage(stage, verified) },
                        label = { Text(stageLabel(stage)) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = verified,
                    onCheckedChange = { ticked ->
                        verified = ticked
                        if (!ticked && command.stage == EvidenceStage.DEVICE_TESTED) {
                            onStage(EvidenceStage.HYPOTHESIS, true)
                        }
                    },
                )
                Text(
                    "I physically verified this on the device",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }

            DropdownField(
                label = "Proposed Home Assistant entity",
                selected = command.proposedHomeAssistantEntity,
                options = HA_ENTITY_KINDS,
                onSelect = { kind -> onEdit { it.copy(proposedHomeAssistantEntity = kind) } },
                modifier = Modifier.padding(top = 8.dp),
            )

            EvidenceTextField(
                resetKey = command.id,
                label = "Notes (shipped to Home Assistant)",
                value = command.notes,
                onChange = { notes -> onEdit { it.copy(notes = notes) } },
                lines = 2,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
            )

            command.response?.let { response ->
                LabeledValue("Reply on", shortUuid(response.characteristicUuid), mono = true)
                response.payloadPrefixHex?.let { LabeledValue("Reply prefix", hexGrouped(it), mono = true) }
                if (response.observedLatenciesMs.isNotEmpty()) {
                    LabeledValue(
                        "Reply latency",
                        "${response.observedLatenciesMs.min()}–${response.observedLatenciesMs.max()} ms",
                    )
                }
            }

            // Replay button, the synthetic toggle and Delete together exceed a 360 dp phone; wrap.
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onReplay, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Replay, contentDescription = null, Modifier.size(18.dp))
                    Text("  Replayed (${command.successfulReplayCount})")
                }
                Text(
                    "Synthetic",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Checkbox(
                    checked = command.synthetic,
                    onCheckedChange = { synthetic -> onEdit { it.copy(synthetic = synthetic) } },
                )
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = null, Modifier.size(18.dp))
                    Text("Delete")
                }
            }
        }
    }
}

internal fun writeTypeLabel(type: WriteType): String = when (type) {
    WriteType.WITH_RESPONSE -> "Acknowledged"
    WriteType.WITHOUT_RESPONSE -> "No response"
    WriteType.SIGNED -> "Signed"
}

private fun isPromoted(session: CaptureSession, suggestion: SuggestedCommand): Boolean =
    session.commands.any { command ->
        HaProfileBuilder.normalizeHex(command.payloadHex) == suggestion.payloadHex &&
            CommandAnalyzer.groupingUuid(command.characteristicUuid) == suggestion.characteristicUuid
    }
