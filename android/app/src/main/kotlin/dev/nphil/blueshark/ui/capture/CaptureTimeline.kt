package dev.nphil.blueshark.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nphil.blueshark.hci.DissectionNode
import dev.nphil.blueshark.model.AttOperation
import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.CaptureMarker
import dev.nphil.blueshark.ui.theme.MonoFamily

@Composable
internal fun ConnectionHeaderRow(handle: Int?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.SwapHoriz,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = if (handle == null) "No connection handle" else "Connection handle ${formatHandle(handle)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = MonoFamily,
        )
    }
}

@Composable
internal fun MarkerRow(marker: CaptureMarker, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Flag,
            contentDescription = "Capture marker",
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = marker.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatCaptureTime(marker.timestampEpochMicros),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MonoFamily,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/**
 * One timeline row. Tapping it selects the event, and a selected row shows the dissection tree the
 * parser built from the packet's own bytes.
 */
@Composable
internal fun EventRow(
    event: BleEvent,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    dissection: DissectionNode? = null,
    onClick: (() -> Unit)? = null,
) {
    val outbound = event.direction.outbound()
    val accent = when {
        event.operation == AttOperation.ERROR -> MaterialTheme.colorScheme.error
        outbound -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (outbound) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = event.direction.describe(),
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.operation.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = formatCaptureTime(event.timestampEpochMicros),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = buildString {
                    append("handle ").append(formatHandle(event.attributeHandle))
                    event.characteristicUuid?.let { append("  char ").append(shortUuid(it)) }
                    event.serviceUuid?.let { append("  svc ").append(shortUuid(it)) }
                    event.status?.let { append("  status 0x%02X".format(it)) }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (event.payloadHex.isNotEmpty()) {
                Text(
                    text = formatHexPayload(event.payloadHex),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (event.note.isNotBlank()) {
                Text(
                    text = event.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) DissectionSection(dissection)
        }
    }
}

/**
 * The selected packet's dissection, shown as an indented tree. It is deliberately plain: the value
 * of this panel is the decoded field names, not decoration.
 */
@Composable
private fun DissectionSection(root: DissectionNode?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Dissection",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (root == null) {
            Text(
                text = "The raw packet for this event is not retained.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        DissectionRows(root, depth = 0)
    }
}

@Composable
private fun DissectionRows(node: DissectionNode, depth: Int) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = (depth * 12).dp, top = 2.dp)) {
        Text(
            text = node.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = node.value ?: "",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MonoFamily,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.58f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    node.children.forEach { child -> DissectionRows(child, depth + 1) }
}

/** Small key/value line used by the capability and result cards. */
@Composable
internal fun FactRow(label: String, value: String, mono: Boolean = false, tint: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) MonoFamily else null,
            color = tint ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.62f),
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
