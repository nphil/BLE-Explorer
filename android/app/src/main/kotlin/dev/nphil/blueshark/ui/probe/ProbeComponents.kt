package dev.nphil.blueshark.ui.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nphil.blueshark.probe.ProbeVerdict
import dev.nphil.blueshark.ui.scan.HexPayload
import dev.nphil.blueshark.ui.scan.Pill
import dev.nphil.blueshark.ui.sessions.EvidenceTextField
import dev.nphil.blueshark.ui.theme.MonoFamily

/** What the device's answer proved, in the operator's words rather than the driver's. */
fun verdictLabel(verdict: ProbeVerdict): String = when (verdict) {
    ProbeVerdict.ACCEPTED -> "Accepted"
    ProbeVerdict.REJECTED_UNKNOWN_ID -> "No such opcode"
    ProbeVerdict.REJECTED_OTHER -> "Rejected"
    ProbeVerdict.NO_RESPONSE -> "Silence"
    ProbeVerdict.ERROR -> "Failed"
}

fun verdictExplanation(verdict: ProbeVerdict): String = when (verdict) {
    ProbeVerdict.ACCEPTED -> "the device answered SUCCESS — this opcode exists. It does not say what it does, " +
        "and it does not say it is safe: a clear or a reset answers SUCCESS too"
    ProbeVerdict.REJECTED_UNKNOWN_ID -> "the device answered DATA_ID_ERROR — no such command id"
    ProbeVerdict.REJECTED_OTHER -> "the device rejected the frame for another reason; the opcode may still exist"
    ProbeVerdict.NO_RESPONSE -> "nothing came back inside the window — proves nothing on its own"
    ProbeVerdict.ERROR -> "the write itself failed; nothing was probed"
}

@Composable
private fun verdictContainer(verdict: ProbeVerdict): Color = when (verdict) {
    ProbeVerdict.ACCEPTED -> MaterialTheme.colorScheme.primaryContainer
    ProbeVerdict.REJECTED_UNKNOWN_ID -> MaterialTheme.colorScheme.tertiaryContainer
    ProbeVerdict.REJECTED_OTHER -> MaterialTheme.colorScheme.secondaryContainer
    ProbeVerdict.NO_RESPONSE -> MaterialTheme.colorScheme.surfaceVariant
    ProbeVerdict.ERROR -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun verdictContent(verdict: ProbeVerdict): Color = when (verdict) {
    ProbeVerdict.ACCEPTED -> MaterialTheme.colorScheme.onPrimaryContainer
    ProbeVerdict.REJECTED_UNKNOWN_ID -> MaterialTheme.colorScheme.onTertiaryContainer
    ProbeVerdict.REJECTED_OTHER -> MaterialTheme.colorScheme.onSecondaryContainer
    ProbeVerdict.NO_RESPONSE -> MaterialTheme.colorScheme.onSurfaceVariant
    ProbeVerdict.ERROR -> MaterialTheme.colorScheme.onErrorContainer
}

@Composable
fun VerdictChip(verdict: ProbeVerdict, modifier: Modifier = Modifier) {
    Pill(
        text = verdictLabel(verdict),
        modifier = modifier,
        container = verdictContainer(verdict),
        content = verdictContent(verdict),
        contentDescription = "${verdictLabel(verdict)}: ${verdictExplanation(verdict)}",
    )
}

/** Legend for the verdict colours; a chip nobody can read is not evidence. */
@Composable
fun VerdictLegend(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (verdict in ProbeVerdict.entries) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VerdictChip(verdict)
                Text(
                    text = verdictExplanation(verdict),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "Step 7 of N" plus the bar; determinate, because the plan's length is known up front.
 *
 * The denominator is always the live plan's size and never a literal: the opcode table behind
 * `ProbePlans` is evidence that gets revised, and a hardcoded total would quietly start lying the
 * first time an opcode moves onto or off the deny list.
 */
@Composable
fun SweepProgress(stepNumber: Int, stepTotal: Int, stepLabel: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Step $stepNumber of $stepTotal", style = MaterialTheme.typography.titleSmall)
            Text(
                text = stepLabel,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LinearProgressIndicator(
            progress = { if (stepTotal <= 0) 0f else stepNumber.toFloat() / stepTotal.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * One probed step: what went out, what came back, and what the operator saw happen.
 *
 * @param resetKey identity of the run this row belongs to, so the effect field's local draft is
 *   dropped when a new sweep replaces the results rather than carrying a stale note across.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutcomeRow(
    index: Int,
    opcode: Int,
    label: String,
    canary: Boolean,
    sentHex: String,
    responseHex: String?,
    verdict: ProbeVerdict,
    statusByte: Int?,
    elapsedMs: Long,
    note: String,
    observedEffect: String,
    lateCount: Int,
    expanded: Boolean,
    resetKey: Any?,
    onEffectChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (canary) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill(
                    text = "#${index + 1}",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Pill(text = "0x%02X".format(opcode), mono = true)
                VerdictChip(verdict)
                if (statusByte != null) {
                    Pill(
                        text = "status 0x%02X".format(statusByte),
                        mono = true,
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Pill(
                    text = "$elapsedMs ms",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (canary) {
                    Pill(
                        text = "canary",
                        icon = Icons.Filled.Science,
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (lateCount > 0) {
                    Pill(
                        text = if (lateCount == 1) "1 late frame" else "$lateCount late frames",
                        icon = Icons.Filled.Warning,
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Text(label, style = MaterialTheme.typography.bodyMedium)

            if (expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    FrameBlock("Sent", sentHex, Modifier.weight(1f))
                    FrameBlock("Response", responseHex, Modifier.weight(1f))
                }
            } else {
                FrameBlock("Sent", sentHex, Modifier.fillMaxWidth())
                FrameBlock("Response", responseHex, Modifier.fillMaxWidth())
            }

            if (note.isNotBlank()) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            EvidenceTextField(
                resetKey = resetKey to index,
                label = "What the device did",
                value = observedEffect,
                onChange = onEffectChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FrameBlock(title: String, hex: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hex.isNullOrEmpty()) {
            Text(
                text = "—",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            HexPayload(hex, showAscii = false)
        }
    }
}

/** One frame that missed its step's window; never folded into a step's own response. */
@Composable
fun LateResponseRow(late: LateResponse, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = if (late.stepIndex == null) {
                "Unsolicited — answered no step"
            } else {
                "Missed step ${late.stepIndex + 1} (${late.stepLabel}) by ${late.afterMs} ms"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HexPayload(late.hex, showAscii = false)
    }
}

/** Single-select chip row; Material's segmented buttons cap out well below a service's characteristics. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipChoice(
    label: String,
    options: List<T>,
    selected: T?,
    enabled: Boolean,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (option in options) {
                FilterChip(
                    selected = option == selected,
                    enabled = enabled,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option)) },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
        }
    }
}

/**
 * The gate in front of every sweep.
 *
 * Names the device, the characteristic and the step count, and says plainly that an unknown opcode
 * may change a setting on the device — because it may, and the operator is the only one who can
 * decide that is acceptable.
 */
@Composable
fun SweepConfirmDialog(
    confirm: ProbeConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text(confirm.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "About to write ${confirm.stepCount} frames to " +
                        "${confirm.characteristicLabel} on ${confirm.deviceLabel}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "An opcode nobody has documented can do anything the firmware allows: " +
                        "change the mode, the brightness or a stored setting, or write it to flash. " +
                        "Only sweep a device you are willing to reconfigure by hand afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (confirm.riskNotes.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "This plan includes opcodes known to be destructive:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    for (risk in confirm.riskNotes) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = risk,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Write ${confirm.stepCount} frames") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
