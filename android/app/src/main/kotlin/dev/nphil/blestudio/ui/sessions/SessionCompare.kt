package dev.nphil.blestudio.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nphil.blestudio.crypto.DecryptCache
import dev.nphil.blestudio.export.ByteDiff
import dev.nphil.blestudio.export.CommandAnalyzer
import dev.nphil.blestudio.export.HaProfileBuilder
import dev.nphil.blestudio.model.BleEvent
import dev.nphil.blestudio.ui.theme.MonoFamily

private val CELL_WIDTH = 36.dp
private val ROW_LABEL_WIDTH = 96.dp

@Composable
internal fun CompareTab(
    state: SessionsUiState,
    viewModel: SessionsViewModel,
    decrypt: DecryptCache?,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader(
            title = "Compare payloads",
            subtitle = "Pick one attribute, then 2–6 payloads; columns that move between them are highlighted",
        )
        if (decrypt != null && !decrypt.empty) {
            SwitchRow(
                label = "Use decrypted payloads",
                checked = state.compareDecrypted,
                onCheckedChange = viewModel::setCompareDecrypted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (state.facets.channels.isEmpty()) {
            EmptyHint(
                title = "Nothing to compare",
                body = "This session has no captured traffic yet.",
            )
            return@Column
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.facets.channels, key = { it }) { channel ->
                FilterChip(
                    selected = state.compareChannel == channel,
                    onClick = {
                        viewModel.setCompareChannel(channel.takeIf { it != state.compareChannel })
                    },
                    label = { Text(channelLabel(channel), fontFamily = MonoFamily) },
                )
            }
        }
        HorizontalDivider()
        if (state.compareChannel == null) {
            EmptyHint(
                title = "Choose an attribute",
                body = "Comparing only makes sense within one characteristic or handle.",
            )
            return@Column
        }
        // Diffing the plaintext is the point of decryption: on the wire, an AEAD frame differs in
        // every byte, so the ciphertext grid says nothing about which byte carries the parameter.
        val payloads = remember(state.compareSelection, state.compareDecrypted, decrypt) {
            state.compareSelection.map { event ->
                val bytes = if (state.compareDecrypted && decrypt != null) {
                    decrypt.payloadFor(event)
                } else {
                    event.payloadHex
                }
                HaProfileBuilder.normalizeHex(bytes).orEmpty()
            }
        }
        if (expanded) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                CandidateList(state, viewModel, decrypt, Modifier.weight(1f).fillMaxHeight())
                VerticalDivider()
                DiffPanel(state, viewModel, payloads, Modifier.weight(1.6f).fillMaxHeight())
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                CandidateList(state, viewModel, decrypt, Modifier.fillMaxWidth().heightIn(max = 260.dp))
                HorizontalDivider()
                DiffPanel(state, viewModel, payloads, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CandidateList(
    state: SessionsUiState,
    viewModel: SessionsViewModel,
    decrypt: DecryptCache?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "candidate-header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${state.compareSelection.size} of ${state.compareCandidates.size} selected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.compareSelection.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearCompareSelection) { Text("Clear") }
                }
            }
        }
        items(state.compareCandidates, key = { it.id }) { event ->
            val checked = state.compareSelection.any { it.id == event.id }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { viewModel.toggleCompareSelection(event) })
                Column(Modifier.weight(1f)) {
                    Text(
                        formatClockMicros(event.timestampEpochMicros),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val shown = if (state.compareDecrypted && decrypt != null) {
                        decrypt.payloadFor(event)
                    } else {
                        event.payloadHex
                    }
                    Text(
                        hexGrouped(shown, 12).ifEmpty { "(no payload)" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    operationLabel(event.operation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun DiffPanel(
    state: SessionsUiState,
    viewModel: SessionsViewModel,
    payloads: List<String>,
    modifier: Modifier = Modifier,
) {
    val selection = state.compareSelection
    if (selection.size < 2) {
        EmptyHint(
            modifier = modifier,
            title = "Select at least two payloads",
            body = "Two to six payloads on the same attribute reveal which byte carries the parameter.",
        )
        return
    }
    val diff = remember(payloads) { CommandAnalyzer.diff(payloads) }
    val horizontal = rememberScrollState()
    Column(modifier) {
        Text(
            summaryOf(diff),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(horizontal).padding(horizontal = 12.dp),
        ) {
            GridCell("offset", ROW_LABEL_WIDTH, header = true)
            for (offset in 0 until diff.byteLength) {
                GridCell("%02d".format(offset), CELL_WIDTH, header = true)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(selection.size, key = { index -> selection[index].id }) { index ->
                val event = selection[index]
                val hex = payloads[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontal)
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GridCell(formatClockMicros(event.timestampEpochMicros), ROW_LABEL_WIDTH, header = true)
                    for (offset in 0 until diff.byteLength) {
                        val present = (offset + 1) * 2 <= hex.length
                        GridCell(
                            text = if (present) hex.substring(offset * 2, offset * 2 + 2) else "··",
                            width = CELL_WIDTH,
                            varying = !diff.stableBytes.get(offset),
                            missing = !present,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            item(key = "varying-values") {
                Column(Modifier.padding(16.dp)) {
                    Text("Values per varying offset", style = MaterialTheme.typography.labelLarge)
                    if (diff.varyingRanges.isEmpty()) {
                        Text(
                            "Every byte is identical across the selection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    diff.varyingRanges.forEach { range ->
                        for (offset in range) {
                            LabeledValue(
                                label = "offset %02d".format(offset),
                                value = diff.perOffsetValues.getOrNull(offset)?.joinToString(" ").orEmpty(),
                                mono = true,
                            )
                        }
                    }
                    TextButton(onClick = viewModel::clearCompareSelection) { Text("Clear selection") }
                }
            }
        }
    }
}

@Composable
private fun GridCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    header: Boolean = false,
    varying: Boolean = false,
    missing: Boolean = false,
) {
    val background = when {
        header -> MaterialTheme.colorScheme.surface
        missing -> MaterialTheme.colorScheme.surfaceVariant
        varying -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Text(
        text = text,
        style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
        fontFamily = MonoFamily,
        textAlign = if (header) TextAlign.Start else TextAlign.Center,
        color = if (varying && !missing) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .width(width)
            .padding(horizontal = 1.dp, vertical = 2.dp)
            .background(background, RoundedCornerShape(4.dp))
            .padding(vertical = 6.dp),
        maxLines = 1,
    )
}

private fun summaryOf(diff: ByteDiff): String = buildString {
    append(diff.byteLength).append(" byte columns · ")
    val varyingCount = diff.varyingRanges.sumOf { it.last - it.first + 1 }
    append(varyingCount).append(" varying")
    if (diff.ragged) append(" · lengths differ")
    if (diff.ignoredSamples > 0) append(" · ${diff.ignoredSamples} unreadable payload(s) skipped")
}
