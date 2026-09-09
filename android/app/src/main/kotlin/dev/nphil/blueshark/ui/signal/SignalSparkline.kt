package dev.nphil.blueshark.ui.signal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.nphil.blueshark.signal.DEFAULT_SMOOTHING_ALPHA
import dev.nphil.blueshark.signal.GRADE_FAIR_DBM
import dev.nphil.blueshark.signal.SignalSample
import dev.nphil.blueshark.signal.SignalSource

/** Plot bounds in dBm: the top of the chart is a device in your hand, the bottom is silence. */
private const val TOP_DBM = -30f
private const val BOTTOM_DBM = -100f

/** The analyzer's own smoothing weight, so the line on screen is the number in the stats grid. */
private val EMA_ALPHA = DEFAULT_SMOOTHING_ALPHA.toFloat()

/** Above this many raw samples the dots are thinned: 60 s of a fast advertiser is hundreds. */
private const val MAX_DOTS = 300

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * The measured window as a strip chart: raw packets as dots, the EMA as a line, and a dashed
 * guide at the weakest RSSI that would still grade FAIR through a proxy carrying
 * [proxyMarginDb] dB less.
 *
 * The x axis is the window itself, not the samples: the right edge is *now*, derived from the
 * newest sample plus [sinceLastMs]. A device that goes quiet therefore watches its own trace slide
 * left off the chart, which is the whole point — stretching the held samples across the full width
 * would hide exactly the silence the operator is hunting.
 *
 * The list is replaced ten times a second, which is smoother than any per-point animation would
 * be; the [Path] is allocated once and rewound per frame.
 */
@Composable
fun SignalSparkline(
    history: List<SignalSample>,
    windowMs: Long,
    sinceLastMs: Long?,
    proxyMarginDb: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val line = remember { Path() }
    val density = LocalDensity.current
    val dashes = remember(density) {
        val dash = with(density) { 4.dp.toPx() }
        PathEffect.dashPathEffect(floatArrayOf(dash, dash))
    }

    Box(modifier.fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawGuides(scheme.onSurfaceVariant, scheme.tertiary, proxyMarginDb, dashes)
            if (history.size < 2) return@Canvas

            val span = (windowMs * NANOS_PER_MILLI).coerceAtLeast(1L).toFloat()
            val now = history.last().atNanos + (sinceLastMs ?: 0L) * NANOS_PER_MILLI
            val start = now - windowMs * NANOS_PER_MILLI
            val stride = if (history.size > MAX_DOTS) history.size / MAX_DOTS + 1 else 1
            val dotRadius = 1.6.dp.toPx()

            var index = 0
            while (index < history.size) {
                val sample = history[index]
                val colour = if (sample.source == SignalSource.CONNECTION) scheme.secondary else scheme.primary
                drawCircle(
                    color = colour.copy(alpha = 0.5f),
                    radius = dotRadius,
                    center = Offset(
                        x = (sample.atNanos - start) / span * size.width,
                        y = yOf(sample.rssi.toFloat()),
                    ),
                )
                index += stride
            }

            line.rewind()
            var ema = history.first().rssi.toFloat()
            for (position in history.indices) {
                val sample = history[position]
                ema += EMA_ALPHA * (sample.rssi - ema)
                val x = (sample.atNanos - start) / span * size.width
                val y = yOf(ema)
                if (position == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            drawPath(path = line, color = scheme.primary, style = Stroke(width = 2.dp.toPx()))
        }
        if (history.size < 2) {
            Text(
                text = "Waiting for packets",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Frame, the −50/−70/−90 gridlines, and the margin-shifted FAIR guide. */
private fun DrawScope.drawGuides(
    grid: Color,
    guide: Color,
    proxyMarginDb: Int,
    dashes: PathEffect,
) {
    val hairline = 1.dp.toPx()
    for (dbm in intArrayOf(-50, -70, -90)) {
        val y = yOf(dbm.toFloat())
        drawLine(grid.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), hairline)
    }
    val fair = yOf((GRADE_FAIR_DBM + proxyMarginDb).toFloat())
    drawLine(
        color = guide.copy(alpha = 0.8f),
        start = Offset(0f, fair),
        end = Offset(size.width, fair),
        strokeWidth = hairline,
        pathEffect = dashes,
    )
}

private fun DrawScope.yOf(dbm: Float): Float {
    val clamped = dbm.coerceIn(BOTTOM_DBM, TOP_DBM)
    return (TOP_DBM - clamped) / (TOP_DBM - BOTTOM_DBM) * size.height
}
