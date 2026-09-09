package dev.nphil.blueshark.signal

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Gaps used for the clustering reference. 32 is ~3 s of a fast advertiser: recent, still robust. */
private const val GAP_MEDIAN_SAMPLES = 32

/** A gap this far past the running median is more than one advertising interval. */
private const val INTERVAL_CLUSTER_FACTOR = 3.0

/** A gap this far past the running median already implies a missed packet. */
private const val LOSS_GAP_FACTOR = 1.5

/** A single gap may not claim more loss than this: a stalled radio is a dropout, not 10 000 misses. */
private const val MAX_MISSED_PER_GAP = 50

/** Below this many advertisements the loss estimate is noise, so it is reported as unknown. */
private const val MIN_LOSS_SAMPLES = 5

private const val NANOS_PER_MILLI = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val DROPOUT_NANOS = DROPOUT_MS * NANOS_PER_MILLI
private const val INITIAL_CAPACITY = 256
private const val NO_TIME = Long.MIN_VALUE
private const val NO_GAP = 0L

/**
 * Folds a stream of RSSI readings into the numbers that decide where a BLE device (or the proxy
 * that talks to it) should live.
 *
 * ### Loss and dropouts are different faults
 * A run of missed advertisements between packets that otherwise arrive on schedule is *loss*:
 * the radio is transmitting and something — congestion, distance, a body in the way — is eating
 * packets. A gap of several seconds is a *dropout*, and the most common cause is not a weak link
 * at all: a connectable peripheral stops advertising for as long as any central holds a
 * connection, so a device that vanishes while another controller owns it looks identical to one
 * that walked out of range. Folding those seconds into a loss percentage would blame the airtime
 * for something that is a topology problem, so gaps longer than [DROPOUT_MS] are counted as
 * dropouts and excluded from loss accounting. [presence] then shows *when* the silence happened,
 * which is what tells the two apart.
 *
 * ### Cost
 * [add] is O(1) amortised: samples land in a growable ring, anything older than the window is
 * evicted from the front, and the only per-sample work is a 32-element median of recent gaps.
 * Nothing is allocated per sample beyond the [SignalSample] the caller already made. [stats] is
 * one pass over the window plus a sort of reused scratch arrays, cheap enough for a 10 Hz UI with
 * a minute of a 50 packet/s advertiser in the ring.
 *
 * Not thread-safe: confine one analyzer to the coroutine that owns the scan, as
 * [dev.nphil.blueshark.ble.ScannerRepository] already does for its own fold.
 */
class SignalAnalyzer(
    private val windowNanos: Long = DEFAULT_WINDOW_NANOS,
    private val smoothingAlpha: Double = DEFAULT_SMOOTHING_ALPHA,
    proxyMarginDb: Int = DEFAULT_PROXY_MARGIN_DB,
) {
    /** Handicap applied to the median before grading; the operator can retune it live. */
    var proxyMarginDb: Int = proxyMarginDb

    private var samples = arrayOfNulls<SignalSample>(INITIAL_CAPACITY)

    /**
     * Per-sample gap bookkeeping, attributed to the *later* sample of each pair: `adjustedGap` is
     * the clustered per-packet interval that gap implies and `missedBefore` the packets it says
     * were lost. Writing it once at insert keeps [add] O(1); a gap whose earlier sample has been
     * evicted is skipped in [stats], so only gaps that lie wholly inside the window are counted.
     */
    private var adjustedGap = LongArray(INITIAL_CAPACITY)
    private var missedBefore = IntArray(INITIAL_CAPACITY)

    private var head = 0
    private var size = 0

    /** Newest advertisement ever seen, window or not: the reference for the next gap. */
    private var lastAdvertisementAt = NO_TIME

    private val gapRing = LongArray(GAP_MEDIAN_SAMPLES)
    private val gapScratch = LongArray(GAP_MEDIAN_SAMPLES)
    private var gapRingSize = 0
    private var gapRingNext = 0

    private var ema = Double.NaN

    private var rssiScratch = IntArray(INITIAL_CAPACITY)
    private var intervalScratch = LongArray(INITIAL_CAPACITY)

    /** Feeds one reading. Samples are expected in time order; a backwards one is simply ignored for timing. */
    fun add(sample: SignalSample) {
        evict(sample.atNanos)

        // The EMA is re-seeded whenever the window is empty: an average carried over from before a
        // minute of silence describes a place the operator has already walked away from.
        ema = if (size == 0 || ema.isNaN()) sample.rssi.toDouble() else ema + smoothingAlpha * (sample.rssi - ema)

        if (size == samples.size) grow()
        var slot = head + size
        if (slot >= samples.size) slot -= samples.size
        samples[slot] = sample

        var adjusted = NO_GAP
        var missed = 0
        if (sample.source == SignalSource.ADVERTISEMENT) {
            val previous = lastAdvertisementAt
            if (previous != NO_TIME) {
                val gap = sample.atNanos - previous
                if (gap > 0) {
                    val median = runningGapMedian()
                    adjusted = clusterAdjust(gap, median)
                    missed = missedPackets(gap, median)
                    pushGap(adjusted)
                }
            }
            if (previous == NO_TIME || sample.atNanos > previous) lastAdvertisementAt = sample.atNanos
        }
        adjustedGap[slot] = adjusted
        missedBefore[slot] = missed
        size++
    }

    /** Everything the current window proves, as of [nowNanos]. */
    fun stats(nowNanos: Long): SignalStats {
        evict(nowNanos)
        val margin = proxyMarginDb
        val windowMs = windowNanos / NANOS_PER_MILLI
        if (size == 0) {
            return SignalStats(
                latestRssi = null,
                smoothedRssi = null,
                medianRssi = null,
                minRssi = null,
                maxRssi = null,
                stdDevDb = null,
                sampleCount = 0,
                packetsPerSecond = 0.0,
                advertisingIntervalMs = null,
                lossPercent = null,
                dropouts = 0,
                longestGapMs = 0L,
                sinceLastMs = null,
                pathLossDb = null,
                estimatedDistanceM = null,
                grade = SignalGrade.LOST,
                proxyMarginDb = margin,
                windowMs = windowMs,
            )
        }

        var minRssi = Int.MAX_VALUE
        var maxRssi = Int.MIN_VALUE
        var sum = 0.0
        var sumSquares = 0.0
        var latestRssi = 0
        var latestAt = 0L
        var txPower: Int? = null
        var advertisements = 0
        var firstAdvertisementAt = NO_TIME
        var previousAdvertisementAt = NO_TIME
        var longestGap = 0L
        var dropouts = 0
        var missed = 0
        var intervals = 0

        val capacity = samples.size
        var slot = head
        for (i in 0 until size) {
            val sample = samples[slot]!!
            val rssi = sample.rssi
            rssiScratch[i] = rssi
            if (rssi < minRssi) minRssi = rssi
            if (rssi > maxRssi) maxRssi = rssi
            val value = rssi.toDouble()
            sum += value
            sumSquares += value * value
            val sampleTxPower = sample.txPower
            if (sampleTxPower != null) txPower = sampleTxPower
            latestRssi = rssi
            latestAt = sample.atNanos

            if (sample.source == SignalSource.ADVERTISEMENT) {
                advertisements++
                if (previousAdvertisementAt == NO_TIME) {
                    firstAdvertisementAt = sample.atNanos
                } else {
                    val gap = sample.atNanos - previousAdvertisementAt
                    if (gap > 0) {
                        if (gap > longestGap) longestGap = gap
                        if (gap > DROPOUT_NANOS) dropouts++
                        missed += missedBefore[slot]
                        val adjusted = adjustedGap[slot]
                        if (adjusted > NO_GAP) intervalScratch[intervals++] = adjusted
                    }
                }
                previousAdvertisementAt = sample.atNanos
            }

            slot++
            if (slot == capacity) slot = 0
        }

        // The silence still running when stats are taken is as real a gap as one already closed by
        // a later packet, and it is the one the operator is standing in.
        if (previousAdvertisementAt != NO_TIME) {
            val trailing = nowNanos - previousAdvertisementAt
            if (trailing > longestGap) longestGap = trailing
            if (trailing > DROPOUT_NANOS) dropouts++
        }

        rssiScratch.sort(0, size)
        val median = medianOf(rssiScratch, size)

        val interval = if (intervals == 0) {
            null
        } else {
            intervalScratch.sort(0, intervals)
            medianOf(intervalScratch, intervals) / NANOS_PER_MILLI
        }

        val stdDev = if (size < 2) {
            null
        } else {
            sqrt(((sumSquares - sum * sum / size) / size).coerceAtLeast(0.0))
        }

        val spanNanos = if (firstAdvertisementAt == NO_TIME) 0L else nowNanos - firstAdvertisementAt
        val rate = if (advertisements >= 2 && spanNanos > 0) {
            (advertisements - 1) * NANOS_PER_SECOND / spanNanos
        } else {
            0.0
        }

        val loss = if (advertisements < MIN_LOSS_SAMPLES) {
            null
        } else {
            100.0 * missed / (advertisements + missed)
        }

        val sinceLast = ((nowNanos - latestAt) / NANOS_PER_MILLI).coerceAtLeast(0L)
        val pathLoss = txPower?.minus(median)
        val distance = pathLoss?.let { 10.0.pow(it / (10.0 * PATH_LOSS_EXPONENT)) }

        return SignalStats(
            latestRssi = latestRssi,
            smoothedRssi = ema,
            medianRssi = median,
            minRssi = minRssi,
            maxRssi = maxRssi,
            stdDevDb = stdDev,
            sampleCount = size,
            packetsPerSecond = rate,
            advertisingIntervalMs = interval,
            lossPercent = loss,
            dropouts = dropouts,
            longestGapMs = longestGap / NANOS_PER_MILLI,
            sinceLastMs = sinceLast,
            pathLossDb = pathLoss,
            estimatedDistanceM = distance,
            grade = gradeFor(median, sinceLast, margin),
            proxyMarginDb = margin,
            windowMs = windowMs,
        )
    }

    /** The window's samples, oldest first — the chart's input. */
    fun history(nowNanos: Long): List<SignalSample> {
        evict(nowNanos)
        val out = ArrayList<SignalSample>(size)
        val capacity = samples.size
        var slot = head
        for (i in 0 until size) {
            out.add(samples[slot]!!)
            slot++
            if (slot == capacity) slot = 0
        }
        return out
    }

    /**
     * One bucket per [bucketNanos] across the window, oldest first, true where at least one
     * advertisement arrived. The last bucket ends at [nowNanos].
     *
     * This is the timeline that separates "too far away" from "someone else is connected to it":
     * a device that is merely weak keeps peppering the buckets, one that has been claimed goes
     * flatly, cleanly silent.
     */
    fun presence(nowNanos: Long, bucketNanos: Long = 1_000_000_000L): List<Boolean> {
        require(bucketNanos > 0) { "bucketNanos must be positive" }
        evict(nowNanos)
        val buckets = (windowNanos / bucketNanos).toInt().coerceAtLeast(1)
        val out = BooleanArray(buckets)
        val start = nowNanos - buckets.toLong() * bucketNanos
        val capacity = samples.size
        var slot = head
        for (i in 0 until size) {
            val sample = samples[slot]!!
            if (sample.source == SignalSource.ADVERTISEMENT) {
                val index = (sample.atNanos - start) / bucketNanos
                if (index >= 0) {
                    // A packet landing exactly on `now` belongs to the last bucket, not past the end.
                    out[if (index >= buckets) buckets - 1 else index.toInt()] = true
                }
            }
            slot++
            if (slot == capacity) slot = 0
        }
        return out.toList()
    }

    /** Forgets everything: a new target, or the operator asking for a clean read of this spot. */
    fun reset() {
        samples.fill(null)
        head = 0
        size = 0
        lastAdvertisementAt = NO_TIME
        gapRingSize = 0
        gapRingNext = 0
        ema = Double.NaN
    }

    private fun evict(nowNanos: Long) {
        val cutoff = nowNanos - windowNanos
        while (size > 0) {
            val oldest = samples[head] ?: break
            if (oldest.atNanos >= cutoff) break
            samples[head] = null
            head++
            if (head == samples.size) head = 0
            size--
        }
    }

    private fun grow() {
        val capacity = samples.size
        val next = capacity * 2
        val grownSamples = arrayOfNulls<SignalSample>(next)
        val grownAdjusted = LongArray(next)
        val grownMissed = IntArray(next)
        var slot = head
        for (i in 0 until size) {
            grownSamples[i] = samples[slot]
            grownAdjusted[i] = adjustedGap[slot]
            grownMissed[i] = missedBefore[slot]
            slot++
            if (slot == capacity) slot = 0
        }
        samples = grownSamples
        adjustedGap = grownAdjusted
        missedBefore = grownMissed
        head = 0
        rssiScratch = IntArray(next)
        intervalScratch = LongArray(next)
    }

    private fun pushGap(gapNanos: Long) {
        gapRing[gapRingNext] = gapNanos
        gapRingNext++
        if (gapRingNext == GAP_MEDIAN_SAMPLES) gapRingNext = 0
        if (gapRingSize < GAP_MEDIAN_SAMPLES) gapRingSize++
    }

    /**
     * Median of the recent gaps, 0.0 until the first one is seen.
     *
     * A median, not a mean: dropouts and doubled gaps are exactly what this has to survive, and
     * because each gap is stored already divided by the packets it implies, a device that loses
     * half its advertisements still reports its true interval. Sorting 32 longs per packet is
     * cheaper than the machinery to avoid it, and reuses one scratch array so it never allocates.
     */
    private fun runningGapMedian(): Double {
        val n = gapRingSize
        if (n == 0) return 0.0
        gapRing.copyInto(gapScratch, 0, 0, n)
        gapScratch.sort(0, n)
        val mid = n / 2
        return if (n % 2 == 1) {
            gapScratch[mid].toDouble()
        } else {
            (gapScratch[mid - 1] + gapScratch[mid]) / 2.0
        }
    }

    /** Splits a gap that spans several advertising intervals back into one interval. */
    private fun clusterAdjust(gapNanos: Long, medianNanos: Double): Long {
        if (medianNanos <= 0.0 || gapNanos <= INTERVAL_CLUSTER_FACTOR * medianNanos) return gapNanos
        val packets = (gapNanos / medianNanos).roundToInt().coerceAtLeast(1)
        return gapNanos / packets
    }

    /** Packets a gap says were never received. Dropouts return 0 — see the class KDoc. */
    private fun missedPackets(gapNanos: Long, medianNanos: Double): Int {
        if (gapNanos > DROPOUT_NANOS || medianNanos <= 0.0) return 0
        if (gapNanos <= LOSS_GAP_FACTOR * medianNanos) return 0
        return ((gapNanos / medianNanos).roundToInt() - 1).coerceIn(0, MAX_MISSED_PER_GAP)
    }
}

/** Median of the first [n] entries of an already-sorted array. */
private fun medianOf(sorted: IntArray, n: Int): Int {
    val mid = n / 2
    return if (n % 2 == 1) sorted[mid] else ((sorted[mid - 1] + sorted[mid]) / 2.0).roundToInt()
}

/** Median of the first [n] entries of an already-sorted array. */
private fun medianOf(sorted: LongArray, n: Int): Double {
    val mid = n / 2
    return if (n % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
}
