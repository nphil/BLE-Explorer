package dev.nphil.blueshark.ui.signal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nphil.blueshark.signal.SignalGrade
import dev.nphil.blueshark.signal.SignalSample
import dev.nphil.blueshark.ui.theme.MonoFamily
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Strongest and weakest RSSI the dial can show; everything is a fraction of this span. */
private const val STRONG_DBM = -30.0
private const val WEAK_DBM = -100.0
private const val SPAN_DB = STRONG_DBM - WEAK_DBM

/** Rings the operator reads distances off. */
private val RING_LABELS = intArrayOf(-50, -70, -90)

/** Dots kept behind the live one, and the bearing each step costs. */
private const val TRAIL_DOTS = 20
private const val TRAIL_STEP_DEGREES = 8f

/** Live reading sits at twelve o'clock; the trail sweeps back anticlockwise from there. */
private const val LIVE_BEARING = -90f

/** More rings than this on screen at once reads as noise, and each one costs a stroke per frame. */
private const val MAX_PINGS = 6
private const val PING_MS = 600
private const val SWEEP_MS = 4_000

/**
 * Radar dial: distance from the centre is path loss, so a strong device sits in the middle and a
 * dying one drifts to the rim. The rotating wedge and the per-packet ping rings exist to make
 * "still hearing it" obvious from arm's length while the operator walks.
 *
 * Everything that depends only on the canvas size — brush, ring radii, measured labels — is built
 * in [drawWithCache]; the animated values are read inside the draw lambda, so a frame costs no
 * recomposition and no allocation.
 */
@Composable
fun SignalGauge(
    smoothedRssi: Double?,
    grade: SignalGrade,
    history: List<SignalSample>,
    pulses: Flow<Unit>,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = gradeColor(grade)
    val dim = grade == SignalGrade.LOST
    val measurer = rememberTextMeasurer()

    val sweep by rememberInfiniteTransition(label = "radar").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val reach by animateFloatAsState(
        targetValue = gaugeFraction(smoothedRssi),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "reach",
    )

    val pings = remember { mutableStateListOf<Animatable<Float, AnimationVector1D>>() }
    LaunchedPings(pulses, pings)

    val labelStyle = remember(scheme.onSurfaceVariant) {
        TextStyle(color = scheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = MonoFamily)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .aspectRatio(1f)
                .drawWithCache {
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val rim = size.minDimension / 2f * 0.92f
                    val ringRadii = FloatArray(RING_LABELS.size) { rim * fraction(RING_LABELS[it].toDouble()) }
                    val labels = Array(RING_LABELS.size) { measurer.measure("${RING_LABELS[it]}", labelStyle) }
                    val wedge = Brush.sweepGradient(
                        0f to accent.copy(alpha = 0.28f),
                        0.10f to accent.copy(alpha = 0.06f),
                        0.26f to Color.Transparent,
                        1f to Color.Transparent,
                        center = centre,
                    )
                    val grid = scheme.onSurfaceVariant.copy(alpha = 0.22f)
                    val hairline = Stroke(width = 1.dp.toPx())
                    val ringStroke = Stroke(width = 1.5.dp.toPx())
                    val dotRadius = 5.dp.toPx()
                    val trailRadius = 2.5.dp.toPx()

                    onDrawBehind {
                        drawCircle(color = scheme.surfaceVariant.copy(alpha = 0.35f), radius = rim, center = centre)
                        for (radius in ringRadii) {
                            drawCircle(color = grid, radius = radius, center = centre, style = hairline)
                        }
                        drawCircle(color = grid, radius = rim, center = centre, style = ringStroke)
                        drawLine(grid, Offset(centre.x - rim, centre.y), Offset(centre.x + rim, centre.y), hairline.width)
                        drawLine(grid, Offset(centre.x, centre.y - rim), Offset(centre.x, centre.y + rim), hairline.width)

                        rotate(degrees = sweep, pivot = centre) {
                            drawCircle(brush = wedge, radius = rim, center = centre)
                        }

                        for (index in labels.indices) {
                            val layout = labels[index]
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(
                                    centre.x + ringRadii[index] - layout.size.width / 2f,
                                    centre.y - layout.size.height - 2.dp.toPx(),
                                ),
                            )
                        }

                        val trail = minOf(TRAIL_DOTS, history.size)
                        for (step in trail - 1 downTo 1) {
                            val sample = history[history.size - 1 - step]
                            val bearing = LIVE_BEARING - step * TRAIL_STEP_DEGREES
                            val fade = 0.40f * (1f - step.toFloat() / TRAIL_DOTS)
                            drawCircle(
                                color = accent.copy(alpha = fade),
                                radius = trailRadius,
                                center = polar(centre, rim * fraction(sample.rssi.toDouble()), bearing),
                            )
                        }

                        val liveRadius = rim * reach
                        for (ping in pings) {
                            val progress = ping.value
                            drawCircle(
                                color = accent.copy(alpha = 0.40f * (1f - progress)),
                                radius = liveRadius + progress * (rim - liveRadius) * 0.9f,
                                center = centre,
                                style = ringStroke,
                            )
                        }

                        val live = polar(centre, liveRadius, LIVE_BEARING)
                        drawCircle(color = accent.copy(alpha = if (dim) 0.16f else 0.30f), radius = dotRadius * 2.4f, center = live)
                        drawCircle(color = accent.copy(alpha = if (dim) 0.40f else 1f), radius = dotRadius, center = live)
                    }
                },
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = smoothedRssi?.let { "%.0f".format(it) } ?: "—",
                style = MaterialTheme.typography.displayMedium,
                fontFamily = MonoFamily,
                fontWeight = FontWeight.Medium,
                color = accent.copy(alpha = if (dim) 0.5f else 1f),
            )
            Text(
                text = "dBm smoothed",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = gradeWord(grade),
                style = MaterialTheme.typography.titleMedium,
                color = accent.copy(alpha = if (dim) 0.7f else 1f),
            )
        }
    }
}

/**
 * One expanding ring per received packet, capped: a 20 ms advertiser would otherwise start a
 * hundred animations a second and the oldest ring is the one nobody is looking at.
 */
@Composable
private fun LaunchedPings(pulses: Flow<Unit>, pings: MutableList<Animatable<Float, AnimationVector1D>>) {
    LaunchedEffect(pulses) {
        val scope = this
        pulses.collect {
            if (pings.size >= MAX_PINGS) pings.removeAt(0)
            val ring = Animatable(0f)
            pings.add(ring)
            scope.launch {
                ring.animateTo(1f, tween(PING_MS, easing = LinearEasing))
                pings.remove(ring)
            }
        }
    }
}

/** Distance from the dial's centre, 0 at [STRONG_DBM] and 1 at [WEAK_DBM] or worse. */
private fun fraction(dbm: Double): Float = (((STRONG_DBM - dbm) / SPAN_DB).coerceIn(0.0, 1.0)).toFloat()

/** A missing reading parks the dot on the rim, where "nothing heard" belongs. */
private fun gaugeFraction(dbm: Double?): Float = if (dbm == null) 1f else fraction(dbm)

private fun polar(centre: Offset, radius: Float, degrees: Float): Offset {
    val radians = degrees * (PI / 180.0)
    return Offset(centre.x + radius * cos(radians).toFloat(), centre.y + radius * sin(radians).toFloat())
}
