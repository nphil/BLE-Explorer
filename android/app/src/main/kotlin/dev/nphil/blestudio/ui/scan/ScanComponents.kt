package dev.nphil.blestudio.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nphil.blestudio.model.AttOperation
import dev.nphil.blestudio.model.BleEvent
import dev.nphil.blestudio.model.EventDirection
import dev.nphil.blestudio.ui.theme.MonoFamily
import java.util.TimeZone

/** Compact, non-interactive label. Material's chips are all clickable; these are pure read-outs. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    icon: ImageVector? = null,
    mono: Boolean = false,
    contentDescription: String? = null,
) {
    Surface(
        modifier = modifier.then(
            if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier,
        ),
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = if (mono) MonoFamily else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun RssiChip(rssi: Int, modifier: Modifier = Modifier) {
    val icon = when {
        rssi >= STRONG_RSSI -> Icons.Filled.SignalCellularAlt
        rssi >= FAIR_RSSI -> Icons.Filled.SignalCellularAlt2Bar
        else -> Icons.Filled.SignalCellularAlt1Bar
    }
    val container = when {
        rssi >= STRONG_RSSI -> MaterialTheme.colorScheme.primaryContainer
        rssi >= FAIR_RSSI -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        rssi >= STRONG_RSSI -> MaterialTheme.colorScheme.onPrimaryContainer
        rssi >= FAIR_RSSI -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Pill(
        text = "$rssi dBm",
        modifier = modifier,
        container = container,
        content = content,
        icon = icon,
        contentDescription = "Signal strength $rssi decibel-milliwatts",
    )
}

@Composable
fun PropertyChip(property: String, modifier: Modifier = Modifier) {
    val emphasised = property == "NOTIFY" || property == "INDICATE"
    Pill(
        text = property,
        modifier = modifier,
        container = if (emphasised) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        content = if (emphasised) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun FactColumn(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Column(modifier = modifier.widthIn(min = 88.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) MonoFamily else null,
        )
    }
}

/** `01 A4 FF` — grouped so long payloads stay readable. */
fun groupHex(hex: String): String {
    if (hex.isEmpty()) return ""
    val builder = StringBuilder(hex.length + hex.length / 2)
    var index = 0
    while (index < hex.length - 1) {
        if (index > 0) builder.append(' ')
        builder.append(hex, index, index + 2)
        index += 2
    }
    if (index < hex.length) builder.append(hex[index])
    return builder.toString()
}

/** Printable ASCII rendering of a hex payload; anything else becomes a dot. */
fun hexToAscii(hex: String): String {
    if (hex.length < 2) return ""
    val builder = StringBuilder(hex.length / 2)
    var index = 0
    while (index + 1 < hex.length) {
        val code = hex.substring(index, index + 2).toIntOrNull(16)
        builder.append(if (code != null && code in 0x20..0x7E) code.toChar() else '.')
        index += 2
    }
    return builder.toString()
}

@Composable
fun HexPayload(hex: String, modifier: Modifier = Modifier, showAscii: Boolean = true) {
    if (hex.isEmpty()) return
    // A notification stream recomposes the owning card on every packet; re-deriving both strings
    // for every unchanged payload is pure waste on the frame budget.
    val grouped = remember(hex) { groupHex(hex) }
    val ascii = remember(hex, showAscii) { if (showAscii) hexToAscii(hex) else "" }
    Column(modifier = modifier) {
        Text(
            text = grouped,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MonoFamily,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (showAscii) {
            Text(
                text = ascii,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun EventRow(event: BleEvent, modifier: Modifier = Modifier) {
    val accent = when (event.direction) {
        EventDirection.LOCAL_TO_DEVICE, EventDirection.PHONE_TO_DEVICE -> MaterialTheme.colorScheme.primary
        EventDirection.DEVICE_TO_LOCAL, EventDirection.DEVICE_TO_PHONE -> MaterialTheme.colorScheme.tertiary
        EventDirection.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val arrow = when (event.direction) {
        EventDirection.LOCAL_TO_DEVICE, EventDirection.PHONE_TO_DEVICE -> "→"
        EventDirection.DEVICE_TO_LOCAL, EventDirection.DEVICE_TO_PHONE -> "←"
        EventDirection.SYSTEM -> "·"
    }
    Row(modifier = modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = arrow,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            modifier = Modifier
                .width(20.dp)
                .clearAndSetSemantics { },
        )
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = operationLabel(event.operation),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )
                Text(
                    text = formatMicros(event.timestampEpochMicros),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (event.payloadHex.isNotEmpty()) {
                HexPayload(event.payloadHex, modifier = Modifier.padding(top = 2.dp))
            }
            if (event.note.isNotBlank()) {
                Text(
                    text = event.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}

fun operationLabel(operation: AttOperation): String = when (operation) {
    AttOperation.READ_REQUEST -> "Read"
    AttOperation.READ_RESPONSE -> "Read response"
    AttOperation.WRITE_REQUEST -> "Write"
    AttOperation.WRITE_RESPONSE -> "Write ack"
    AttOperation.WRITE_COMMAND -> "Write (no response)"
    AttOperation.NOTIFICATION -> "Notification"
    AttOperation.INDICATION -> "Indication"
    AttOperation.CONFIRMATION -> "Confirmation"
    AttOperation.DISCOVERY -> "Discovery"
    AttOperation.ERROR -> "Error"
    AttOperation.OTHER -> "Event"
}

/** `12:04:07.813` in the device's local time; micros are truncated to milliseconds for display. */
fun formatMicros(timestampEpochMicros: Long): String {
    val totalMillis = timestampEpochMicros / 1_000L
    val local = totalMillis + TimeZone.getDefault().getOffset(totalMillis)
    val millisOfDay = Math.floorMod(local, DAY_MILLIS)
    val hours = millisOfDay / 3_600_000
    val minutes = (millisOfDay / 60_000) % 60
    val seconds = (millisOfDay / 1_000) % 60
    val millis = millisOfDay % 1_000
    return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
}

private const val DAY_MILLIS = 86_400_000L
private const val STRONG_RSSI = -65
private const val FAIR_RSSI = -80
