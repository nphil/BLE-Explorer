package dev.nphil.blestudio.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nphil.blestudio.model.BleEvent
import dev.nphil.blestudio.model.CaptureSession
import dev.nphil.blestudio.model.EventDirection
import dev.nphil.blestudio.ui.theme.MonoFamily

private val OFFSET_WIDTH = 56.dp
private val HEX_WIDTH = 44.dp
private val ASCII_WIDTH = 44.dp
private val NUMBER_WIDTH = 60.dp
private val WIDE_NUMBER_WIDTH = 72.dp

@Composable
internal fun TimelineTab(
    state: SessionsUiState,
    session: CaptureSession,
    expanded: Boolean,
    onFilter: (TimelineFilter) -> Unit,
    onClearFilter: () -> Unit,
    onInspect: (BleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filtersOpen by remember { mutableStateOf(false) }
    val filter = state.filter
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = { onFilter(filter.copy(query = it)) },
                modifier = Modifier.weight(1f),
                label = { Text("Search hex or text") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.query.isNotEmpty()) {
                        IconButton(
                            onClick = { onFilter(filter.copy(query = "")) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
            )
            IconButton(
                onClick = { filtersOpen = !filtersOpen },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.FilterList,
                    contentDescription = if (filtersOpen) "Hide filters" else "Show filters",
                    tint = if (filter.activeCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (filtersOpen) {
            TimelineFilterChips(state, session, onFilter)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${state.timeline.size} of ${session.events.size} events",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (filter.activeCount > 0) {
                TextButton(onClick = onClearFilter) { Text("Clear ${filter.activeCount} filters") }
            }
        }
        HorizontalDivider()
        if (state.timeline.isEmpty()) {
            EmptyHint(
                title = if (session.events.isEmpty()) "No traffic captured yet" else "Nothing matches",
                body = if (session.events.isEmpty()) {
                    "Run a live GATT session, an HCI snoop capture or a relay run against this session."
                } else {
                    "Loosen the filters or clear the search to see the rest of the capture."
                },
            )
            return@Column
        }
        val first = state.timeline.first().timestampEpochMicros
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(state.timeline, key = { it.id }) { event ->
                TimelineRow(
                    event = event,
                    relativeToMicros = first,
                    expanded = expanded,
                    marked = event.markerId != null,
                    onClick = { onInspect(event) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun TimelineFilterChips(
    state: SessionsUiState,
    session: CaptureSession,
    onFilter: (TimelineFilter) -> Unit,
) {
    val filter = state.filter
    Column(Modifier.padding(bottom = 4.dp)) {
        if (state.facets.directions.isNotEmpty()) {
            ChipRow(label = "Direction") {
                items(state.facets.directions, key = { it.name }) { direction ->
                    FilterChip(
                        selected = direction in filter.directions,
                        onClick = { onFilter(filter.copy(directions = filter.directions.toggle(direction))) },
                        label = { Text(directionLabel(direction)) },
                    )
                }
            }
        }
        if (state.facets.operations.isNotEmpty()) {
            ChipRow(label = "Operation") {
                items(state.facets.operations, key = { it.name }) { operation ->
                    FilterChip(
                        selected = operation in filter.operations,
                        onClick = { onFilter(filter.copy(operations = filter.operations.toggle(operation))) },
                        label = { Text(operationLabel(operation)) },
                    )
                }
            }
        }
        if (state.facets.channels.isNotEmpty()) {
            ChipRow(label = "Attribute") {
                items(state.facets.channels, key = { it }) { channel ->
                    FilterChip(
                        selected = channel in filter.channels,
                        onClick = { onFilter(filter.copy(channels = filter.channels.toggle(channel))) },
                        label = { Text(channelLabel(channel), fontFamily = MonoFamily) },
                    )
                }
            }
        }
        if (session.markers.isNotEmpty()) {
            ChipRow(label = "Near marker (±3 s)") {
                items(session.markers, key = { it.id }) { marker ->
                    FilterChip(
                        selected = marker.id in filter.markerIds,
                        onClick = { onFilter(filter.copy(markerIds = filter.markerIds.toggle(marker.id))) },
                        label = { Text(marker.label) },
                        leadingIcon = {
                            Icon(Icons.Default.Bookmark, contentDescription = null, Modifier.size(16.dp))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipRow(
    label: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun TimelineRow(
    event: BleEvent,
    relativeToMicros: Long,
    expanded: Boolean,
    marked: Boolean,
    onClick: () -> Unit,
) {
    val outbound = event.direction == EventDirection.PHONE_TO_DEVICE ||
        event.direction == EventDirection.LOCAL_TO_DEVICE
    val accent = if (outbound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val previewBytes = if (expanded) 24 else 10
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(if (expanded) 128.dp else 96.dp)) {
            Text(
                formatClockMicros(event.timestampEpochMicros),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = MonoFamily,
            )
            Text(
                formatOffsetMicros(event.timestampEpochMicros - relativeToMicros),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            directionArrow(event.direction),
            style = MaterialTheme.typography.titleMedium,
            color = accent,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(operationLabel(event.operation), style = MaterialTheme.typography.labelMedium, color = accent)
                Text(
                    channelLabel(eventChannelKey(event)),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (marked) {
                    Icon(
                        Icons.Default.Bookmark,
                        contentDescription = "Has a capture marker",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
                event.status?.takeIf { it != 0 }?.let { status ->
                    Text(
                        "status $status",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                hexGrouped(event.payloadHex, previewBytes).ifEmpty { "(no payload)" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (event.payloadHex.isNotEmpty()) {
                Text(
                    asciiOf(event.payloadHex, previewBytes),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventInspectorSheet(
    event: BleEvent,
    session: CaptureSession?,
    onDismiss: () -> Unit,
    onCreateCommand: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val rows = remember(event.id) { byteRows(event.payloadHex) }
    val horizontal = rememberScrollState()
    val marker = session?.markers?.firstOrNull { it.id == event.markerId }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Event inspector", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(8.dp))
            InspectorFact("When", "${formatClockMicros(event.timestampEpochMicros)} (${event.source.name})")
            InspectorFact("What", "${directionLabel(event.direction)} · ${operationLabel(event.operation)}")
            InspectorFact("Service", shortUuid(event.serviceUuid))
            InspectorFact("Characteristic", shortUuid(event.characteristicUuid))
            event.attributeHandle?.let { InspectorFact("Handle", "0x%04X".format(it)) }
            event.status?.let { InspectorFact("Status", it.toString()) }
            marker?.let { InspectorFact("Marker", it.label) }
            if (event.note.isNotBlank()) InspectorFact("Note", event.note)
            InspectorFact("Length", "${event.payloadHex.length / 2} bytes")
            Spacer(Modifier.size(12.dp))
            if (rows.isEmpty()) {
                Text(
                    "This event carries no payload.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontal)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .padding(vertical = 6.dp, horizontal = 8.dp),
                ) {
                    InspectorHeaderCell("Offset", OFFSET_WIDTH)
                    InspectorHeaderCell("Hex", HEX_WIDTH)
                    InspectorHeaderCell("ASCII", ASCII_WIDTH)
                    InspectorHeaderCell("Dec", NUMBER_WIDTH)
                    InspectorHeaderCell("Int8", NUMBER_WIDTH)
                    InspectorHeaderCell("U16 LE", WIDE_NUMBER_WIDTH)
                    InspectorHeaderCell("U16 BE", WIDE_NUMBER_WIDTH)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(rows, key = { it.offset }) { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(horizontal)
                                .padding(vertical = 6.dp, horizontal = 8.dp),
                        ) {
                            InspectorCell("0x%04X".format(row.offset), OFFSET_WIDTH)
                            InspectorCell(row.hex, HEX_WIDTH)
                            InspectorCell(row.ascii, ASCII_WIDTH)
                            InspectorCell(row.unsigned.toString(), NUMBER_WIDTH)
                            InspectorCell(row.signed.toString(), NUMBER_WIDTH)
                            InspectorCell(row.uint16LittleEndian?.toString() ?: "—", WIDE_NUMBER_WIDTH)
                            InspectorCell(row.uint16BigEndian?.toString() ?: "—", WIDE_NUMBER_WIDTH)
                        }
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onCreateCommand,
                    enabled = event.payloadHex.isNotBlank(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("Create command from this")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun InspectorFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = MonoFamily)
    }
}

@Composable
private fun InspectorHeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun InspectorCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = MonoFamily,
        modifier = Modifier.width(width),
    )
}

internal fun <T> Set<T>.toggle(value: T): Set<T> =
    if (contains(value)) this - value else this + value
