package dev.nphil.blueshark.signal

import java.util.Locale

/** How much the operator should care. [BAD] is "this spot will not work", [INFO] is context. */
enum class HintSeverity { INFO, WARN, BAD }

/** One evidence-backed reading of the window. [detail] always names the number that triggered it. */
data class Hint(
    val severity: HintSeverity,
    val title: String,
    val detail: String,
)

/**
 * Turns a window of numbers into the handful of sentences worth reading while standing in a room.
 *
 * Every hint quotes the measurement that produced it, because the operator is going to act on it —
 * move a proxy, re-orient a light, change a Wi-Fi channel — and a verdict with no number behind it
 * cannot be checked afterwards from the next waypoint.
 *
 * Hints come back most actionable first, which also puts [HintSeverity.BAD] at the top.
 */
object SignalDiagnosis {

    /** Spread past this is multipath rather than distance. */
    const val UNSTABLE_STDDEV_DB = 8.0

    /** Loss past this is congestion worth chasing, not the usual few dropped packets. */
    const val HIGH_LOSS_PERCENT = 20.0

    /** One dropout is bad luck; this many is a pattern. */
    const val REPEATED_DROPOUTS = 2

    /** An interval past this makes a duty-cycled proxy look broken even when the link is fine. */
    const val SLOW_INTERVAL_MS = 1_000.0

    /** Buckets of silence before a gap counts as "gone" rather than "between packets". */
    private const val SILENT_TAIL_BUCKETS = 2

    /** Buckets of advertising needed before the silence that follows means anything. */
    private const val STEADY_RUN_BUCKETS = 2

    fun hints(stats: SignalStats, connectable: Boolean?, presence: List<Boolean>): List<Hint> {
        val out = ArrayList<Hint>(4)
        val windowSeconds = stats.windowMs / 1_000L
        val steadyRunBuckets = silenceAfterAdvertising(presence)

        if (stats.grade == SignalGrade.LOST) {
            if (steadyRunBuckets != null && connectable == true) {
                out += Hint(
                    HintSeverity.BAD,
                    "Device went silent",
                    "Nothing for ${seconds(stats.sinceLastMs)} after $steadyRunBuckets s of steady " +
                        "advertising, and it advertised as connectable. A connectable peripheral stops " +
                        "advertising for as long as any central holds a connection, so this is most likely " +
                        "another central — a second proxy, a phone, a stale link the device never dropped — " +
                        "owning the device rather than a weak signal here. Median while it was talking was " +
                        "${dbm(stats.medianRssi)}. Check for a second controller before moving anything.",
                )
            } else {
                out += Hint(
                    HintSeverity.BAD,
                    if (stats.sinceLastMs == null) "Nothing heard yet" else "No signal here",
                    if (stats.sinceLastMs == null) {
                        "No packet in the last $windowSeconds s. A peripheral that is connected to any " +
                            "central — a Home Assistant proxy holding its slot, the vendor app, a hub — " +
                            "does not advertise at all, and from here that is indistinguishable from being " +
                            "out of range. Before surveying, make sure nothing is connected to it (disable or " +
                            "reload its Home Assistant entry, close the vendor app), then confirm packets " +
                            "arrive right next to the device."
                    } else {
                        "Last packet ${seconds(stats.sinceLastMs)} ago, past the $SIGNAL_LOST_MS ms cutoff. " +
                            "A proxy in this spot would already have marked the entity unavailable."
                    },
                )
            }
        }

        val median = stats.medianRssi
        val graded = median?.minus(stats.proxyMarginDb)
        if (graded != null && graded < GRADE_FAIR_DBM) {
            out += Hint(
                HintSeverity.BAD,
                "Too weak for a proxy",
                "Median ${dbm(median)} is ${dbm(graded)} once the ${stats.proxyMarginDb} dB proxy margin " +
                    "comes off, below the $GRADE_FAIR_DBM dBm floor. This tablet's antenna is better than " +
                    "the ESP32's, so a link that limps here will not hold there. Move the proxy toward the " +
                    "device (or the device toward the proxy) and re-mark: every 6 dB gained roughly halves " +
                    "the distance the radio has to cover.",
            )
        }

        val loss = stats.lossPercent
        if (loss != null && loss > HIGH_LOSS_PERCENT) {
            out += Hint(
                HintSeverity.WARN,
                "Packets being eaten",
                "${percent(loss)} of the expected advertisements never arrived (${rate(stats.packetsPerSecond)} " +
                    "received per second, ${millis(stats.advertisingIntervalMs)} nominal interval) while the " +
                    "median held at ${dbm(median)}. Loss at a usable RSSI is 2.4 GHz congestion, not distance: " +
                    "BLE advertises on 2402, 2426 and 2480 MHz, and the first two sit underneath Wi-Fi channels " +
                    "1 and 6. Check what is on those channels near either end and move the Wi-Fi, not the light.",
            )
        }

        if (stats.dropouts >= REPEATED_DROPOUTS) {
            out += Hint(
                HintSeverity.WARN,
                "${stats.dropouts} dropouts in $windowSeconds s",
                "${stats.dropouts} gaps longer than $DROPOUT_MS ms, longest ${seconds(stats.longestGapMs)}. " +
                    "Home Assistant marks an entity unavailable on gaps like these even when the RSSI either " +
                    "side looks healthy. Repeated dropouts with a good median usually mean the device is being " +
                    "connected to by something else, or its own radio is resetting.",
            )
        }

        val stdDev = stats.stdDevDb
        if (stdDev != null && stdDev > UNSTABLE_STDDEV_DB && (graded == null || graded >= GRADE_FAIR_DBM)) {
            out += Hint(
                HintSeverity.WARN,
                "Signal swinging ${db(stdDev)}",
                "Spread of ${db(stdDev)} between ${dbm(stats.minRssi)} and ${dbm(stats.maxRssi)} around a " +
                    "median of ${dbm(median)}. That is multipath: reflections arriving out of phase with the " +
                    "direct path and cancelling it. Water and metal are the worst offenders at 2.4 GHz, so an " +
                    "aquarium controller under a metal hood or behind a full tank swings like this. Lift or " +
                    "rotate the device a few centimetres, get it off the metal and out from behind the water, " +
                    "and re-mark: a stable ${dbm(median)} beats a peaky ${dbm(stats.maxRssi)}.",
            )
        }

        val interval = stats.advertisingIntervalMs
        if (interval != null && interval > SLOW_INTERVAL_MS) {
            out += Hint(
                HintSeverity.WARN,
                "Slow advertiser",
                "One advertisement every ${millis(interval)}, ${rate(stats.packetsPerSecond)} per second. A " +
                    "proxy that scans in bursts hears roughly one packet per interval, so state lags by " +
                    "seconds and two missed packets are enough for it to declare the device unavailable. " +
                    "Shorten the interval if the device allows it, otherwise widen the proxy's availability " +
                    "timeout instead of chasing the RSSI.",
            )
        }

        if (connectable == false) {
            out += Hint(
                HintSeverity.WARN,
                "Not connectable",
                "The advertisements are non-connectable, so from here the device can be watched but not " +
                    "written to. Either it is in a broadcast-only mode, or a central already holds its one " +
                    "connection slot. No amount of placement will make it controllable while this is true.",
            )
        }

        val distance = stats.estimatedDistanceM
        if (distance != null) {
            out += Hint(
                HintSeverity.INFO,
                "Roughly ${metres(distance)} away",
                "Log-distance path loss with n=$PATH_LOSS_EXPONENT over ${stats.pathLossDb} dB of loss " +
                    "(median ${dbm(median)} against the advertised tx power at 1 m). Indicative only — a wall, " +
                    "a tank of water or the device's own orientation moves this by a factor of two, so compare " +
                    "waypoints against each other rather than trusting the metres.",
            )
        }

        return out
    }

    /**
     * Length in buckets of the advertising run that the trailing silence interrupted, or null when
     * the timeline shows no such shape (never heard, or still being heard).
     */
    private fun silenceAfterAdvertising(presence: List<Boolean>): Int? {
        val lastHeard = presence.indexOfLast { it }
        if (lastHeard < 0) return null
        val silentBuckets = presence.size - 1 - lastHeard
        if (silentBuckets < SILENT_TAIL_BUCKETS) return null
        var run = 0
        var index = lastHeard
        while (index >= 0 && presence[index]) {
            run++
            index--
        }
        if (run < STEADY_RUN_BUCKETS) return null
        return run
    }

    private fun dbm(value: Int?): String = if (value == null) "unknown" else "$value dBm"

    private fun db(value: Double): String = String.format(Locale.ROOT, "%.1f dB", value)

    private fun percent(value: Double): String = String.format(Locale.ROOT, "%.1f %%", value)

    private fun rate(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private fun millis(value: Double?): String =
        if (value == null) "unknown" else String.format(Locale.ROOT, "%.0f ms", value)

    private fun metres(value: Double): String = String.format(Locale.ROOT, "%.1f m", value)

    private fun seconds(millis: Long?): String =
        if (millis == null) "unknown" else String.format(Locale.ROOT, "%.1f s", millis / 1_000.0)
}
