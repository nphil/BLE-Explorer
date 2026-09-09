package dev.nphil.blueshark.signal

/** Nanosecond timestamp from a millisecond one, so the timelines below stay readable. */
internal fun ms(value: Long): Long = value * 1_000_000L

/**
 * A plausible healthy window, with one named argument per thing a test wants to break. Defaults
 * describe a spot that needs no advice at all, which is what makes a single overridden field the
 * only possible cause of a hint.
 */
internal fun statsOf(
    latestRssi: Int? = -62,
    smoothedRssi: Double? = -62.0,
    medianRssi: Int? = -62,
    minRssi: Int? = -66,
    maxRssi: Int? = -58,
    stdDevDb: Double? = 2.0,
    sampleCount: Int = 300,
    packetsPerSecond: Double = 9.8,
    advertisingIntervalMs: Double? = 100.0,
    lossPercent: Double? = 1.0,
    dropouts: Int = 0,
    longestGapMs: Long = 200,
    sinceLastMs: Long? = 100,
    pathLossDb: Int? = null,
    estimatedDistanceM: Double? = null,
    grade: SignalGrade = SignalGrade.GOOD,
    proxyMarginDb: Int = DEFAULT_PROXY_MARGIN_DB,
    windowMs: Long = 60_000,
): SignalStats = SignalStats(
    latestRssi = latestRssi,
    smoothedRssi = smoothedRssi,
    medianRssi = medianRssi,
    minRssi = minRssi,
    maxRssi = maxRssi,
    stdDevDb = stdDevDb,
    sampleCount = sampleCount,
    packetsPerSecond = packetsPerSecond,
    advertisingIntervalMs = advertisingIntervalMs,
    lossPercent = lossPercent,
    dropouts = dropouts,
    longestGapMs = longestGapMs,
    sinceLastMs = sinceLastMs,
    pathLossDb = pathLossDb,
    estimatedDistanceM = estimatedDistanceM,
    grade = grade,
    proxyMarginDb = proxyMarginDb,
    windowMs = windowMs,
)
