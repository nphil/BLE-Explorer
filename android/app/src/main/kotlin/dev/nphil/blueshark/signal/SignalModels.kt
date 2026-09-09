package dev.nphil.blueshark.signal

/**
 * Where one RSSI reading came from.
 *
 * Both sources are dBm off the same radio, but only advertisements carry timing evidence: they
 * arrive on the schedule the peripheral chose, so their spacing measures the advertising interval
 * and their absence measures loss. Connection reads happen whenever the central asks, so they say
 * nothing about rate — and a peripheral normally stops advertising for as long as a connection is
 * up, which is why the two are never mixed when counting packets.
 */
enum class SignalSource { ADVERTISEMENT, CONNECTION }

/**
 * One RSSI reading.
 *
 * [atNanos] is a monotonic clock (`SystemClock.elapsedRealtimeNanos()` on Android) so the maths
 * survives wall-clock jumps; [txPower] is the peripheral's claimed output at 1 m when it publishes
 * one, which is the only thing that turns a raw RSSI into path loss.
 */
data class SignalSample(
    val atNanos: Long,
    val rssi: Int,
    val source: SignalSource,
    val txPower: Int? = null,
)

/** Placement verdict for the current window. [LOST] means nothing arrived recently at all. */
enum class SignalGrade { EXCELLENT, GOOD, FAIR, WEAK, LOST }

/** Median at or above this (after the proxy margin) is [SignalGrade.EXCELLENT]. */
const val GRADE_EXCELLENT_DBM = -60

/** Median at or above this (after the proxy margin) is [SignalGrade.GOOD]. */
const val GRADE_GOOD_DBM = -70

/** Median at or above this (after the proxy margin) is [SignalGrade.FAIR]; below it is weak. */
const val GRADE_FAIR_DBM = -80

/** Silence longer than this grades [SignalGrade.LOST] whatever the last RSSI was. */
const val SIGNAL_LOST_MS = 5_000L

/** A gap longer than this is a dropout rather than a run of missed packets. */
const val DROPOUT_MS = 5_000L

/**
 * Decibels subtracted from the tablet's RSSI before grading.
 *
 * The tablet has a far better antenna than the ESP32 proxy that will actually hold the link, so a
 * spot that reads "good" here can be marginal there. Grading the handicapped number keeps the
 * verdict honest about the hardware that has to live in that spot.
 */
const val DEFAULT_PROXY_MARGIN_DB = 8

/** One minute of history: long enough to catch a dropout, short enough to follow a walk. */
const val DEFAULT_WINDOW_NANOS = 60_000_000_000L

/** EMA weight for the newest sample. 0.25 settles in ~10 packets: readable while still lively. */
const val DEFAULT_SMOOTHING_ALPHA = 0.25

/**
 * Path-loss exponent for the log-distance model. 2.0 is free space; 2.7 is the usual indoor
 * compromise for a home with furniture and plasterboard walls.
 */
const val PATH_LOSS_EXPONENT = 2.7

/**
 * Everything one window of samples proves about a spot.
 *
 * Nullable fields are "not measured yet" rather than zero: an empty window must not read as a
 * perfect 0 dB, 0 % link. The RSSI figures ([latestRssi] through [stdDevDb], and [sampleCount])
 * cover every sample in the window; the timing figures ([packetsPerSecond] through [longestGapMs])
 * cover advertisements only, because only they arrive on a schedule. [dropouts] and [lossPercent]
 * are deliberately separate numbers — see [SignalAnalyzer].
 */
data class SignalStats(
    val latestRssi: Int?,
    val smoothedRssi: Double?,
    val medianRssi: Int?,
    val minRssi: Int?,
    val maxRssi: Int?,
    val stdDevDb: Double?,
    val sampleCount: Int,
    val packetsPerSecond: Double,
    val advertisingIntervalMs: Double?,
    val lossPercent: Double?,
    val dropouts: Int,
    val longestGapMs: Long,
    val sinceLastMs: Long?,
    val pathLossDb: Int?,
    val estimatedDistanceM: Double?,
    val grade: SignalGrade,
    val proxyMarginDb: Int,
    val windowMs: Long,
)

/**
 * Grades [medianRssi] after handing [proxyMarginDb] back to the proxy.
 *
 * The median rather than the latest reading: one reflection-cancelled packet must not repaint the
 * whole verdict while the operator is standing still.
 */
fun gradeFor(medianRssi: Int?, sinceLastMs: Long?, proxyMarginDb: Int): SignalGrade {
    if (medianRssi == null || sinceLastMs == null || sinceLastMs > SIGNAL_LOST_MS) return SignalGrade.LOST
    val graded = medianRssi - proxyMarginDb
    return when {
        graded >= GRADE_EXCELLENT_DBM -> SignalGrade.EXCELLENT
        graded >= GRADE_GOOD_DBM -> SignalGrade.GOOD
        graded >= GRADE_FAIR_DBM -> SignalGrade.FAIR
        else -> SignalGrade.WEAK
    }
}

/** A labelled spot the operator marked, with the window that was live when they marked it. */
data class Waypoint(
    val id: String,
    val label: String,
    val capturedAtEpochMs: Long,
    val stats: SignalStats,
)
