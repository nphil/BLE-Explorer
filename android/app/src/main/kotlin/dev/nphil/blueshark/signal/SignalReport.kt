package dev.nphil.blueshark.signal

import java.util.Locale

/** Field label column in the current-stats block; "last packet" is the longest one. */
private const val FIELD_WIDTH = 13

private const val COL_MEDIAN = 7
private const val COL_SPREAD = 8
private const val COL_LOSS = 8
private const val COL_RATE = 8
private const val COL_DROPOUTS = 10

/** Printed where a number has not been measured, rather than a misleading zero. */
private const val NONE = "-"

/**
 * Renders a window and its waypoints as plain text the operator can paste into an issue, a note or
 * a Home Assistant comment.
 *
 * Columns are padded rather than separated by tabs: this lands in monospace everywhere it matters
 * (the app renders it with [dev.nphil.blueshark.ui.theme.MonoFamily]) and stays readable in a
 * terminal or a code block. Facts that were never measured are omitted line-by-line instead of
 * printing "null", the same way the capture report does.
 */
object SignalReport {

    fun render(
        targetAddress: String,
        targetName: String?,
        current: SignalStats,
        waypoints: List<Waypoint>,
    ): String {
        val out = StringBuilder(512)
        out.append("BlueShark signal report\n")
        out.append("target".padEnd(FIELD_WIDTH))
        if (targetName.isNullOrBlank()) out.append(targetAddress) else out.append("$targetName ($targetAddress)")
        out.append("\n\n")

        out.append("current\n")
        out.field("rssi", rssiLine(current))
        current.stdDevDb?.let { out.field("spread", one(it) + " dB") }
        out.field("rate", rateLine(current))
        current.lossPercent?.let { out.field("loss", one(it) + " %") }
        out.field("dropouts", dropoutLine(current))
        current.sinceLastMs?.let { out.field("last packet", seconds(it) + " ago") }
        pathLossLine(current)?.let { out.field("path loss", it) }
        out.field("samples", "${current.sampleCount} in ${current.windowMs / 1_000L} s")
        out.field("grade", gradeLine(current))

        out.append("\nwaypoints\n")
        if (waypoints.isEmpty()) {
            out.append("  none marked\n")
        } else {
            val labelWidth = waypoints.maxOf { it.label.length }.coerceAtLeast("label".length) + 2
            out.row(labelWidth, "label", "median", "spread", "loss%", "rate", "dropouts", "grade")
            for (waypoint in waypoints) {
                val stats = waypoint.stats
                out.row(
                    labelWidth,
                    waypoint.label,
                    stats.medianRssi?.toString() ?: NONE,
                    stats.stdDevDb?.let(::one) ?: NONE,
                    stats.lossPercent?.let(::one) ?: NONE,
                    one(stats.packetsPerSecond),
                    stats.dropouts.toString(),
                    stats.grade.name,
                )
            }
        }

        out.append("\nproxy margin ${current.proxyMarginDb} dB · window ${current.windowMs / 1_000L} s\n")
        return out.toString()
    }

    private fun StringBuilder.field(label: String, value: String) {
        append("  ")
        append(label.padEnd(FIELD_WIDTH))
        append(value)
        append('\n')
    }

    private fun StringBuilder.row(
        labelWidth: Int,
        label: String,
        median: String,
        spread: String,
        loss: String,
        rate: String,
        dropouts: String,
        grade: String,
    ) {
        append("  ")
        append(label.padEnd(labelWidth))
        append(median.padStart(COL_MEDIAN))
        append(spread.padStart(COL_SPREAD))
        append(loss.padStart(COL_LOSS))
        append(rate.padStart(COL_RATE))
        append(dropouts.padStart(COL_DROPOUTS))
        append("  ")
        append(grade)
        append('\n')
    }

    private fun rssiLine(stats: SignalStats): String {
        val latest = stats.latestRssi ?: return NONE
        val parts = ArrayList<String>(4)
        stats.smoothedRssi?.let { parts += "smoothed ${one(it)}" }
        stats.medianRssi?.let { parts += "median $it" }
        stats.minRssi?.let { parts += "min $it" }
        stats.maxRssi?.let { parts += "max $it" }
        return "$latest dBm (${parts.joinToString(", ")})"
    }

    private fun rateLine(stats: SignalStats): String {
        val rate = "${one(stats.packetsPerSecond)} /s"
        val interval = stats.advertisingIntervalMs ?: return rate
        return "$rate (interval ${String.format(Locale.ROOT, "%.0f", interval)} ms)"
    }

    private fun dropoutLine(stats: SignalStats): String =
        "${stats.dropouts} (longest gap ${seconds(stats.longestGapMs)})"

    private fun pathLossLine(stats: SignalStats): String? {
        val pathLoss = stats.pathLossDb ?: return null
        val distance = stats.estimatedDistanceM
            ?: return "$pathLoss dB"
        return "$pathLoss dB (distance ~${one(distance)} m at n=$PATH_LOSS_EXPONENT)"
    }

    private fun gradeLine(stats: SignalStats): String {
        val median = stats.medianRssi ?: return stats.grade.name
        return "${stats.grade.name} (${median - stats.proxyMarginDb} dBm after ${stats.proxyMarginDb} dB proxy margin)"
    }

    private fun one(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1f s", millis / 1_000.0)
}
