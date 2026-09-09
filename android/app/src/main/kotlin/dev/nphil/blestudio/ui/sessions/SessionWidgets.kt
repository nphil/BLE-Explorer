package dev.nphil.blestudio.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nphil.blestudio.export.HaProfileBuilder
import dev.nphil.blestudio.model.EvidenceStage
import dev.nphil.blestudio.ui.theme.MonoFamily

@Composable
internal fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun LabeledValue(
    label: String,
    value: String,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(148.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) MonoFamily else null,
        )
    }
}

/** Numeric field that only reports whole values and an empty field as "unknown". */
@Composable
internal fun LongField(
    resetKey: Any?,
    label: String,
    value: Long?,
    onChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(resetKey) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(12)
            text = digits
            onChange(digits.toLongOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Signed integer field used for byte offsets; blank means "no hypothesis". */
@Composable
internal fun IntField(
    resetKey: Any?,
    label: String,
    value: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(resetKey) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(4)
            text = digits
            onChange(digits.toIntOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/**
 * Offset field that accepts a leading `-`, because a byte range measured from the end of the
 * frame is how every scheme addresses a trailing counter or MIC.
 */
@Composable
internal fun SignedIntField(
    resetKey: Any?,
    label: String,
    value: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(resetKey) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val sign = if (raw.startsWith("-")) "-" else ""
            val cleaned = sign + raw.filter(Char::isDigit).take(5)
            text = cleaned
            onChange(cleaned.toIntOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
internal fun EvidenceTextField(
    resetKey: Any?,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    lines: Int = 1,
) {
    var text by remember(resetKey) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        textStyle = if (mono) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily)
        } else {
            MaterialTheme.typography.bodyMedium
        },
        modifier = modifier,
    )
}

/**
 * Hex field that only ever commits whole bytes. Invalid text stays visible and flagged rather
 * than being silently dropped, so a half-typed header is never mistaken for evidence.
 */
@Composable
internal fun HexField(
    resetKey: Any?,
    label: String,
    value: String?,
    onChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(resetKey) { mutableStateOf(value.orEmpty()) }
    val normalized = HaProfileBuilder.normalizeHex(text)
    val invalid = text.isNotBlank() && (normalized == null || normalized.isEmpty())
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            val whole = HaProfileBuilder.normalizeHex(raw)
            when {
                raw.isBlank() -> onChange(null)
                whole != null && whole.isNotEmpty() -> onChange(whole)
            }
        },
        label = { Text(label) },
        singleLine = true,
        isError = invalid,
        supportingText = {
            Text(if (invalid) "Needs whole hexadecimal bytes, e.g. A5 01" else "Uppercase hex, spaces optional")
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
        modifier = modifier,
    )
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Three-state answer for evidence that may simply be unknown. */
@Composable
internal fun TristateRow(
    label: String,
    value: Boolean?,
    onChange: (Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = value == null,
                onClick = { onChange(null) },
                label = { Text("Unknown") },
            )
            FilterChip(
                selected = value == true,
                onClick = { onChange(true) },
                label = { Text("Yes") },
            )
            FilterChip(
                selected = value == false,
                onClick = { onChange(false) },
                label = { Text("No") },
            )
        }
    }
}

/** Stable-API dropdown: a button that opens a menu of [options] plus an explicit "not set". */
@Composable
internal fun DropdownField(
    label: String,
    selected: String?,
    options: List<String>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    emptyLabel: String = "Not proposed",
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(selected ?: emptyLabel)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Change $label")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(emptyLabel) },
                    onClick = {
                        open = false
                        onSelect(null)
                    },
                )
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun StageBadge(stage: EvidenceStage, modifier: Modifier = Modifier) {
    val container = when (stage) {
        EvidenceStage.OBSERVED -> MaterialTheme.colorScheme.surfaceVariant
        EvidenceStage.HYPOTHESIS -> MaterialTheme.colorScheme.secondaryContainer
        EvidenceStage.DEVICE_TESTED -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when (stage) {
        EvidenceStage.OBSERVED -> MaterialTheme.colorScheme.onSurfaceVariant
        EvidenceStage.HYPOTHESIS -> MaterialTheme.colorScheme.onSecondaryContainer
        EvidenceStage.DEVICE_TESTED -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            stageLabel(stage),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

internal fun stageLabel(stage: EvidenceStage): String = when (stage) {
    EvidenceStage.OBSERVED -> "Observed"
    EvidenceStage.HYPOTHESIS -> "Hypothesis"
    EvidenceStage.DEVICE_TESTED -> "Device-tested"
}
