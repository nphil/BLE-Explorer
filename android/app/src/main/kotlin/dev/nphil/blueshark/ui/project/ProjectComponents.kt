package dev.nphil.blueshark.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nphil.blueshark.ble.shortUuid
import dev.nphil.blueshark.learn.ChecklistAction
import dev.nphil.blueshark.learn.ChecklistItem
import dev.nphil.blueshark.learn.CommandMapBuilder
import dev.nphil.blueshark.model.EvidenceStage
import dev.nphil.blueshark.model.MappedCommandRecord
import dev.nphil.blueshark.model.WriteType
import dev.nphil.blueshark.ui.scan.Pill
import dev.nphil.blueshark.ui.scan.groupHex
import dev.nphil.blueshark.ui.theme.MonoFamily

// ---------------------------------------------------------------------------
// Stage card
// ---------------------------------------------------------------------------

/**
 * One stage of the funnel: a status line, the evidence behind it, one primary action, and a body
 * the operator can fold away.
 *
 * A satisfied stage collapses to its header, because the point of the funnel is that a finished
 * step stops asking for attention - the operator's eye should land on the first thing still
 * undone, not on four cards of equal weight.
 */
@Composable
fun StageCard(
    stage: ProjectStage,
    satisfied: Boolean,
    status: String,
    open: Boolean,
    onToggle: () -> Unit,
    primaryLabel: String?,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (satisfied) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = if (satisfied) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (satisfied) "done" else "not done yet",
                    tint = if (satisfied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(stage.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (open) 4 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "collapse" else "expand",
                )
            }
            if (open) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    body()
                    if (primaryLabel != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(onClick = onPrimary, enabled = primaryEnabled && !busy) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(primaryLabel, Modifier.padding(start = 8.dp))
                            }
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Evidence, one bullet per line, quoted exactly as the rule that produced it wrote it. */
@Composable
fun EvidenceBullets(lines: List<String>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in lines) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------

/** How far one project has got, as the list and the project header both show it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressChips(
    identified: Boolean,
    probedFrames: Int,
    commandCount: Int,
    testedCount: Int,
    exported: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Pill(
            text = if (identified) "identified" else "not identified",
            container = if (identified) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            content = if (identified) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (probedFrames > 0) {
            Pill(
                text = "probed $probedFrames",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        if (commandCount > 0) {
            Pill(
                text = if (commandCount == 1) "1 command" else "$commandCount commands",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        if (testedCount > 0) {
            Pill(
                text = "$testedCount tested",
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        if (exported) {
            Pill(
                text = "exported",
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun stageContainer(stage: EvidenceStage): Color = when (stage) {
    EvidenceStage.DEVICE_TESTED -> MaterialTheme.colorScheme.primaryContainer
    EvidenceStage.HYPOTHESIS -> MaterialTheme.colorScheme.tertiaryContainer
    EvidenceStage.OBSERVED -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun stageContent(stage: EvidenceStage): Color = when (stage) {
    EvidenceStage.DEVICE_TESTED -> MaterialTheme.colorScheme.onPrimaryContainer
    EvidenceStage.HYPOTHESIS -> MaterialTheme.colorScheme.onTertiaryContainer
    EvidenceStage.OBSERVED -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun stageWord(stage: EvidenceStage): String = when (stage) {
    EvidenceStage.DEVICE_TESTED -> "device-tested"
    EvidenceStage.HYPOTHESIS -> "hypothesis"
    EvidenceStage.OBSERVED -> "observed"
}

fun stageExplanation(stage: EvidenceStage): String = when (stage) {
    EvidenceStage.DEVICE_TESTED -> "BlueShark wrote these bytes and something observable happened"
    EvidenceStage.HYPOTHESIS -> "the device answered, but nobody has seen this frame do anything"
    EvidenceStage.OBSERVED -> "seen going by in the vendor app's traffic, never replayed"
}

@Composable
fun StageChip(stage: EvidenceStage, modifier: Modifier = Modifier) {
    Pill(
        text = stageWord(stage),
        modifier = modifier,
        container = stageContainer(stage),
        content = stageContent(stage),
        contentDescription = "${stageWord(stage)}: ${stageExplanation(stage)}",
    )
}

// ---------------------------------------------------------------------------
// Command map
// ---------------------------------------------------------------------------

/** Column headings for the wide layout; they match [CommandMapRow] exactly. */
@Composable
fun CommandMapHeader(modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelSmall
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Control", style = style, color = colour, modifier = Modifier.weight(2.2f))
        Text("Characteristic", style = style, color = colour, modifier = Modifier.weight(1f))
        Text("Payload", style = style, color = colour, modifier = Modifier.weight(1.6f))
        Text("Decoded", style = style, color = colour, modifier = Modifier.weight(1f))
        Text("Conf.", style = style, color = colour, modifier = Modifier.weight(0.6f))
        Text("Write", style = style, color = colour, modifier = Modifier.weight(0.8f))
        Text("Evidence", style = style, color = colour, modifier = Modifier.weight(1f))
        Text("", style = style, modifier = Modifier.widthIn(min = 72.dp))
    }
}

/**
 * One mapped command.
 *
 * The payload is the bytes a replay must send, framing included; the decoded column is what the
 * family's codec reads inside that framing, and is blank rather than guessed when no codec claims
 * the frame. "Test" writes exactly the payload column - never the decoded one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandMapRow(
    command: MappedCommandRecord,
    expanded: Boolean,
    canTest: Boolean,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val characteristic = command.characteristicUuid?.let(::shortUuid) ?: "—"
    val decoded = command.decodedHex?.let(::groupHex) ?: "—"
    val confidence = command.confidence?.let { "%.2f".format(it) } ?: "—"
    val write = writeTypeWord(command.writeType)
    if (expanded) {
        Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(2.2f)) {
                Text(command.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = sourceWord(command.source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MonoCell(characteristic, Modifier.weight(1f))
            MonoCell(groupHex(command.payloadHex), Modifier.weight(1.6f))
            MonoCell(decoded, Modifier.weight(1f))
            MonoCell(confidence, Modifier.weight(0.6f))
            MonoCell(write, Modifier.weight(0.8f))
            StageChip(command.stage, Modifier.weight(1f))
            OutlinedButton(onClick = onTest, enabled = canTest, modifier = Modifier.widthIn(min = 72.dp)) {
                Text("Test")
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(command.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                StageChip(command.stage)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(
                    text = characteristic,
                    mono = true,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Pill(
                    text = sourceWord(command.source),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Pill(
                    text = write,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (command.confidence != null) {
                    Pill(
                        text = "confidence $confidence",
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LabelledHex("payload", groupHex(command.payloadHex))
            if (command.decodedHex != null) LabelledHex("decoded", decoded)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest, enabled = canTest) { Text("Test") }
            }
        }
    }
}

@Composable
private fun MonoCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = MonoFamily,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LabelledHex(label: String, hex: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 60.dp),
        )
        Text(
            text = hex,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MonoFamily,
        )
    }
}

/**
 * How the bytes have to go out.
 *
 * A dash means nobody recorded it, which matters: a write request to a characteristic that only
 * offers WRITE_NO_RESPONSE is rejected outright, so an unknown here is a gap in the evidence and
 * not a harmless default.
 */
fun writeTypeWord(writeType: WriteType?): String = when (writeType) {
    WriteType.WITH_RESPONSE -> "with response"
    WriteType.WITHOUT_RESPONSE -> "no response"
    WriteType.SIGNED -> "signed"
    null -> "—"
}

fun sourceWord(source: String): String = when (source) {
    CommandMapBuilder.SOURCE_LEARNED -> "learned from a tap"
    CommandMapBuilder.SOURCE_PROBE -> "found by the prober"
    CommandMapBuilder.SOURCE_MANUAL -> "entered by hand"
    else -> source
}

/** Writes the correlator could not blame on any interaction; still evidence, just not commands. */
@Composable
fun UnattributedWrites(writes: List<UnattributedWrite>, modifier: Modifier = Modifier) {
    if (writes.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Writes with no tap", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "The app sent these without an interaction in front of them - a handshake, a poll, " +
                "or a control the observer never saw. They are not offered as commands.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (write in writes) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = groupHex(write.payloadHex),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = write.characteristicUuid?.let(::shortUuid) ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (write.count > 1) {
                    Pill(
                        text = "×${write.count}",
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Readiness checklist
// ---------------------------------------------------------------------------

fun actionLabel(action: ChecklistAction): String = when (action) {
    ChecklistAction.NONE -> ""
    ChecklistAction.PICK_APP -> "Pick app"
    ChecklistAction.OPEN_ACCESSIBILITY -> "Accessibility"
    ChecklistAction.OPEN_DEVELOPER_OPTIONS -> "Developer options"
    ChecklistAction.RESTART_BLUETOOTH -> "Restart Bluetooth"
    ChecklistAction.INSTALL_SHIZUKU -> "Get Shizuku"
    ChecklistAction.START_SHIZUKU -> "Open Shizuku"
    ChecklistAction.GRANT_SHIZUKU -> "Allow Shizuku"
    ChecklistAction.OPEN_RELAY -> "Open Relay"
    ChecklistAction.CONFIRM_SNOOP -> "I have done this"
}

/**
 * One readiness row.
 *
 * Both actions are offered when the facts cannot tell which fix applies - a row that guesses one
 * of two and hides the other is how an operator ends up stuck on an OEM build that will not
 * report its own snoop setting.
 *
 * [ChecklistAction.CONFIRM_SNOOP] is a checkbox rather than a button, because it is not a thing
 * the app can do - it is the operator asserting a fact nothing on this build can observe. A button
 * would read as "BlueShark will handle it", and it cannot; a tick reads as a claim, which is what
 * it is, and it is untickable again for the same reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChecklistRow(
    item: ChecklistItem,
    onAction: (ChecklistAction) -> Unit,
    onConfirm: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val confirmable = item.action == ChecklistAction.CONFIRM_SNOOP
    val tint = when {
        item.satisfied -> MaterialTheme.colorScheme.primary
        item.optional -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (confirmable) {
            Checkbox(
                checked = item.satisfied,
                onCheckedChange = onConfirm,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(
                imageVector = when {
                    item.satisfied -> Icons.Filled.CheckCircle
                    item.optional -> Icons.Filled.RadioButtonUnchecked
                    else -> Icons.Filled.Warning
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val buttons = listOfNotNull(
                item.action.takeIf { it != ChecklistAction.NONE && it != ChecklistAction.CONFIRM_SNOOP },
                item.secondaryAction.takeIf { it != ChecklistAction.NONE && it != ChecklistAction.CONFIRM_SNOOP },
            )
            if (!item.satisfied && buttons.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (action in buttons) {
                        OutlinedButton(onClick = { onAction(action) }) { Text(actionLabel(action)) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Replay dialogs
// ---------------------------------------------------------------------------

/**
 * The gate in front of replaying one mapped command, and the question that follows it.
 *
 * Same shape as the prober's sweep gate on purpose: this is the other place in the app where
 * BlueShark writes bytes to somebody's hardware, and it says the same thing about what that may
 * cost. The follow-up matters just as much - a write that returned without error proves the link,
 * not the meaning, so only the operator's answer promotes the row.
 */
@Composable
fun CommandTestDialog(
    test: CommandTest,
    onConfirm: () -> Unit,
    onEffect: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    when (test.phase) {
        TestPhase.CONFIRM, TestPhase.WRITING -> AlertDialog(
            onDismissRequest = { if (test.phase == TestPhase.CONFIRM) onDismiss() },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Replay \"${test.name}\"?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "About to write these bytes to ${test.characteristicLabel} on ${test.deviceLabel}:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = groupHex(test.payloadHex),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = MonoFamily,
                    )
                    Text(
                        text = "A frame learned from the vendor app does whatever the vendor app made it do - " +
                            "which may be a setting written to flash. Only replay on a device you are " +
                            "willing to reconfigure by hand afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (test.phase == TestPhase.WRITING) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp))
                            Text("Connecting and writing…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onConfirm, enabled = test.phase == TestPhase.CONFIRM) { Text("Write it") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = test.phase == TestPhase.CONFIRM) { Text("Cancel") }
            },
        )

        TestPhase.ASK_EFFECT -> AlertDialog(
            onDismissRequest = { onEffect(false) },
            icon = { Icon(Icons.Filled.Science, contentDescription = null) },
            title = { Text("Did the device do anything?") },
            text = {
                Text(
                    text = "The write went out without an error. That proves the link, not the meaning: only " +
                        "an effect you can see makes \"${test.name}\" device-tested.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { Button(onClick = { onEffect(true) }) { Text("Yes, it reacted") } },
            dismissButton = { TextButton(onClick = { onEffect(false) }) { Text("Nothing happened") } },
        )

        TestPhase.FAILED -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("The write failed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = test.error ?: "The stack gave no reason.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = MonoFamily,
                    )
                    Text(
                        text = HELD_BY_ANOTHER_CENTRAL,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
        )
    }
}

/**
 * The one hint worth giving for a refused link.
 *
 * A peripheral holds one central at a time, so "connection failed" on a device that was
 * advertising a moment ago is almost always somebody else already talking to it - and during this
 * work that somebody is usually a Home Assistant Bluetooth proxy or the vendor app itself.
 */
const val HELD_BY_ANOTHER_CENTRAL: String =
    "Something else may be connected (a Home Assistant proxy, the vendor app). A peripheral accepts " +
        "one central at a time: close the vendor app, or stop the proxy, and try again."

/** A link error, in the stack's own words, with the hint underneath rather than instead of it. */
@Composable
fun ConnectErrorCard(error: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    text = "The link was refused",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            HorizontalDivider()
            Text(
                text = HELD_BY_ANOTHER_CENTRAL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** A link out of the funnel: the full Probe page, Advanced capture, the evidence timeline. */
@Composable
fun StageLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(text, Modifier.padding(start = 8.dp))
    }
}
