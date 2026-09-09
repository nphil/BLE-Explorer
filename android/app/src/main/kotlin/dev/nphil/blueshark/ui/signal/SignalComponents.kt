package dev.nphil.blueshark.ui.signal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nphil.blueshark.signal.Hint
import dev.nphil.blueshark.signal.HintSeverity
import dev.nphil.blueshark.signal.SignalGrade
import dev.nphil.blueshark.signal.SignalStats
import dev.nphil.blueshark.signal.Waypoint
import dev.nphil.blueshark.ui.scan.Pill
import dev.nphil.blueshark.ui.theme.MonoFamily

/** One read-out of the stats grid. */
class StatCell(val label: String, val value: String, val mono: Boolean = true)

/** How the four grades map onto the palette; every colour comes from the active scheme. */
@Composable
fun gradeColor(grade: SignalGrade): Color = when (grade) {
    SignalGrade.EXCELLENT, SignalGrade.GOOD -> MaterialTheme.colorScheme.primary
    SignalGrade.FAIR -> MaterialTheme.colorScheme.tertiary
    SignalGrade.WEAK, SignalGrade.LOST -> MaterialTheme.colorScheme.error
}

fun gradeWord(grade: SignalGrade): String = when (grade) {
    SignalGrade.EXCELLENT -> "Excellent"
    SignalGrade.GOOD -> "Good"
    SignalGrade.FAIR -> "Fair"
    SignalGrade.WEAK -> "Weak"
    SignalGrade.LOST -> "Lost"
}

@Composable
fun GradeChip(grade: SignalGrade, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val container = when (grade) {
        SignalGrade.EXCELLENT, SignalGrade.GOOD -> scheme.primaryContainer
        SignalGrade.FAIR -> scheme.tertiaryContainer
        SignalGrade.WEAK, SignalGrade.LOST -> scheme.errorContainer
    }
    val content = when (grade) {
        SignalGrade.EXCELLENT, SignalGrade.GOOD -> scheme.onPrimaryContainer
        SignalGrade.FAIR -> scheme.onTertiaryContainer
        SignalGrade.WEAK, SignalGrade.LOST -> scheme.onErrorContainer
    }
    Pill(text = gradeWord(grade), modifier = modifier, container = container, content = content)
}

@Composable
fun StatTile(cell: StatCell, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = cell.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = cell.value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = if (cell.mono) MonoFamily else null,
                maxLines = 1,
            )
        }
    }
}

/**
 * Fixed-column grid. Deliberately built from plain rows rather than a lazy grid: it is a dozen
 * tiles inside an already-scrolling page, and nesting a scrollable would break the outer one.
 */
@Composable
fun StatGrid(cells: List<StatCell>, columns: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in cells.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (cell in row) StatTile(cell, Modifier.weight(1f))
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One cell per second of the window: filled where an advertisement was heard, hollow where the
 * device went quiet. A connected peripheral usually stops advertising, so a solid block of hollow
 * cells beside a healthy connected RSSI is the signature of "another central holds it", not of a
 * weak link.
 */
@Composable
fun PresenceStrip(presence: List<Boolean>, modifier: Modifier = Modifier) {
    val heard = MaterialTheme.colorScheme.primary
    val silent = MaterialTheme.colorScheme.surfaceVariant
    val cells = presence.size.coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(28.dp)) {
        val gap = 1.5.dp.toPx()
        val width = ((size.width - gap * (cells - 1)) / cells).coerceAtLeast(1f)
        val radius = CornerRadius(1.5.dp.toPx())
        for (index in presence.indices) {
            drawRoundRect(
                color = if (presence[index]) heard else silent,
                topLeft = Offset(index * (width + gap), 0f),
                size = Size(width, size.height),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
fun HintRow(hint: Hint, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tint = when (hint.severity) {
        HintSeverity.INFO -> scheme.primary
        HintSeverity.WARN -> scheme.tertiary
        HintSeverity.BAD -> scheme.error
    }
    val icon: ImageVector = when (hint.severity) {
        HintSeverity.INFO -> Icons.Outlined.Info
        HintSeverity.WARN -> Icons.Outlined.Warning
        HintSeverity.BAD -> Icons.Outlined.Error
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column {
            Text(hint.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = hint.detail,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Header of the waypoint table; the columns match [WaypointRow] exactly. */
@Composable
fun WaypointHeader(modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelSmall
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Spot", style = style, color = colour, modifier = Modifier.weight(2.2f))
        Text("Median", style = style, color = colour, modifier = Modifier.weight(1.2f))
        Text("±σ", style = style, color = colour, modifier = Modifier.weight(1f))
        Text("Loss", style = style, color = colour, modifier = Modifier.weight(1f))
        Text("Rate", style = style, color = colour, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(88.dp))
    }
}

@Composable
fun WaypointRow(
    waypoint: Waypoint,
    best: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = waypoint.stats
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (best) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(2.2f)) {
                Text(
                    text = waypoint.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (best) {
                    Text(
                        text = "best so far",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            TableValue(dbm(stats.medianRssi), Modifier.weight(1.2f))
            TableValue(oneDecimal(stats.stdDevDb), Modifier.weight(1f))
            TableValue(percent(stats.lossPercent), Modifier.weight(1f))
            TableValue(oneDecimal(stats.packetsPerSecond), Modifier.weight(1f))
            GradeChip(stats.grade)
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, contentDescription = "Remove ${waypoint.label}")
            }
        }
    }
}

@Composable
private fun TableValue(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = MonoFamily,
        maxLines = 1,
    )
}

/**
 * The margin an ESP32 proxy is assumed to lose against this tablet. Grading subtracts it before
 * choosing a word, so a spot that reads "good" here is judged as the proxy would hear it.
 */
@Composable
fun ProxyMarginSlider(marginDb: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Proxy margin", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "$marginDb dB",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = MonoFamily,
            )
        }
        Text(
            text = "ESP32 proxies hear roughly this much less than this tablet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = marginDb.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = MIN_PROXY_MARGIN_DB.toFloat()..MAX_PROXY_MARGIN_DB.toFloat(),
            steps = MAX_PROXY_MARGIN_DB - MIN_PROXY_MARGIN_DB - 1,
        )
    }
}

/** Section title used between the page's cards. */
@Composable
fun SectionTitle(text: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Every read-out on the screen; `—` wherever the analyzer has nothing to say yet. */
fun statCells(stats: SignalStats, connectedRssi: Int?): List<StatCell> = listOf(
    StatCell("Latest raw", dbm(stats.latestRssi)),
    StatCell("Median", dbm(stats.medianRssi)),
    StatCell("Spread ±σ", oneDecimal(stats.stdDevDb)),
    StatCell("Min–Max", if (stats.minRssi == null || stats.maxRssi == null) "—" else "${stats.minRssi} / ${stats.maxRssi}"),
    StatCell("Rate pkt/s", oneDecimal(stats.packetsPerSecond)),
    StatCell("Interval ms", oneDecimal(stats.advertisingIntervalMs)),
    StatCell("Loss %", percent(stats.lossPercent)),
    StatCell("Dropouts", "${stats.dropouts}"),
    StatCell("Longest gap", duration(stats.longestGapMs)),
    StatCell("Path loss dB", dbm(stats.pathLossDb)),
    StatCell("~Distance m", oneDecimal(stats.estimatedDistanceM)),
    StatCell("Since last", stats.sinceLastMs?.let { duration(it) } ?: "—"),
    StatCell("Samples", "${stats.sampleCount}"),
    StatCell("Link RSSI", dbm(connectedRssi)),
)

fun dbm(value: Int?): String = value?.toString() ?: "—"

fun oneDecimal(value: Double?): String = value?.let { "%.1f".format(it) } ?: "—"

fun percent(value: Double?): String = value?.let { "%.1f".format(it) } ?: "—"

/** `640 ms` under a second, `12.4 s` above it: placement work never cares about minutes. */
fun duration(millis: Long?): String = when {
    millis == null -> "—"
    millis < 1_000 -> "$millis ms"
    else -> "%.1f s".format(millis / 1_000.0)
}
