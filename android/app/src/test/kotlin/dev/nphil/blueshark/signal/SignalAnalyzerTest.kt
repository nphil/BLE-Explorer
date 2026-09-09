package dev.nphil.blueshark.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The placement maths. Every timeline here is one the operator can actually walk into: a well
 * behaved advertiser, one being eaten by 2.4 GHz traffic, one that goes silent because something
 * else connected to it, and one whose history has aged out from under the window.
 */
class SignalAnalyzerTest {

    @Test
    fun `a steady advertiser reports its own interval and loses nothing`() {
        val analyzer = SignalAnalyzer()
        for (i in 0 until 60) {
            analyzer.add(SignalSample(ms(i * 100L), -65, SignalSource.ADVERTISEMENT))
        }

        val stats = analyzer.stats(ms(5_900))

        assertEquals(100.0, stats.advertisingIntervalMs!!, 0.001)
        assertEquals(0.0, stats.lossPercent!!, 0.001)
        assertEquals(10.0, stats.packetsPerSecond, 0.01)
        assertEquals(0, stats.dropouts)
        assertEquals(100L, stats.longestGapMs)
        assertEquals(60, stats.sampleCount)
        assertEquals(-65, stats.medianRssi)
        assertEquals(0.0, stats.stdDevDb!!, 0.001)
        assertEquals(0L, stats.sinceLastMs)
        // A comfortable -65 dBm on this tablet is only -73 dBm to the proxy that has to hold the
        // link, which is FAIR rather than GOOD. That handicap is the whole point of the margin.
        assertEquals(SignalGrade.FAIR, stats.grade)
    }

    @Test
    fun `one advertisement in four missing reads as a quarter lost`() {
        val analyzer = SignalAnalyzer()
        // 41 slots 100 ms apart with every fourth one never arriving: 31 land, and the 10 double
        // gaps each account for exactly one missed packet, so 10 / (31 + 10) = 24.4 %.
        for (i in 0..40) {
            if (i % 4 == 3) continue
            analyzer.add(SignalSample(ms(i * 100L), -70, SignalSource.ADVERTISEMENT))
        }

        val stats = analyzer.stats(ms(4_000))

        assertEquals(24.4, stats.lossPercent!!, 0.05)
        // The clustering is what keeps this honest: the 200 ms gaps must not read as a 200 ms
        // advertising interval.
        assertEquals(100.0, stats.advertisingIntervalMs!!, 0.001)
        assertEquals(0, stats.dropouts)
        assertEquals(31, stats.sampleCount)
    }

    @Test
    fun `twelve seconds of silence is a dropout, not packet loss`() {
        val analyzer = SignalAnalyzer()
        for (i in 0..9) analyzer.add(SignalSample(ms(i * 100L), -60, SignalSource.ADVERTISEMENT))
        for (i in 0..9) analyzer.add(SignalSample(ms(12_900 + i * 100L), -60, SignalSource.ADVERTISEMENT))
        val now = ms(13_800)

        val stats = analyzer.stats(now)

        assertEquals(1, stats.dropouts)
        assertEquals(12_000L, stats.longestGapMs)
        assertEquals(0.0, stats.lossPercent!!, 0.001)
        assertEquals(100.0, stats.advertisingIntervalMs!!, 0.001)

        // The timeline is what separates "gone quiet" from "weak": buckets 48..58 are empty, and
        // the two bursts land in 46..47 and 59 (the newest packet sits exactly on `now`).
        val presence = analyzer.presence(now)
        assertEquals(60, presence.size)
        assertEquals(listOf(46, 47, 59), presence.indices.filter { presence[it] })
    }

    @Test
    fun `samples older than the window stop counting`() {
        val analyzer = SignalAnalyzer()
        for (i in 0 until 30) analyzer.add(SignalSample(ms(i * 1_000L), -40, SignalSource.ADVERTISEMENT))

        val early = analyzer.stats(ms(29_000))
        assertEquals(30, early.sampleCount)
        assertEquals(-40, early.maxRssi)

        for (i in 30..89) analyzer.add(SignalSample(ms(i * 1_000L), -80, SignalSource.ADVERTISEMENT))
        val now = ms(90_000)
        val late = analyzer.stats(now)

        assertEquals(60, late.sampleCount)
        assertEquals(-80, late.maxRssi)
        assertEquals(-80, late.medianRssi)
        assertEquals(60, analyzer.history(now).size)
        assertEquals(ms(30_000), analyzer.history(now).first().atNanos)
    }

    @Test
    fun `the smoothed value follows an outlier and the median ignores it`() {
        val analyzer = SignalAnalyzer(smoothingAlpha = 0.25)
        for (i in 0 until 20) analyzer.add(SignalSample(ms(i * 100L), -60, SignalSource.ADVERTISEMENT))
        analyzer.add(SignalSample(ms(2_000), -100, SignalSource.ADVERTISEMENT))

        val stats = analyzer.stats(ms(2_000))

        assertEquals(-60, stats.medianRssi)
        assertEquals(-100, stats.latestRssi)
        assertEquals(-100, stats.minRssi)
        // -60 + 0.25 * (-100 - -60)
        assertEquals(-70.0, stats.smoothedRssi!!, 0.001)
    }

    @Test
    fun `the proxy margin can push a spot down a grade`() {
        assertEquals(SignalGrade.EXCELLENT, gradeOf(-60, margin = 0))
        assertEquals(SignalGrade.GOOD, gradeOf(-60, margin = 8))
        assertEquals(SignalGrade.GOOD, gradeOf(-70, margin = 0))
        assertEquals(SignalGrade.FAIR, gradeOf(-70, margin = 8))
        assertEquals(SignalGrade.FAIR, gradeOf(-80, margin = 0))
        assertEquals(SignalGrade.WEAK, gradeOf(-80, margin = 8))
    }

    @Test
    fun `retuning the margin regrades the window in place`() {
        val analyzer = SignalAnalyzer(proxyMarginDb = 8)
        for (i in 0 until 10) analyzer.add(SignalSample(ms(i * 100L), -55, SignalSource.ADVERTISEMENT))

        assertEquals(SignalGrade.GOOD, analyzer.stats(ms(900)).grade)
        analyzer.proxyMarginDb = 0
        val regraded = analyzer.stats(ms(900))
        assertEquals(SignalGrade.EXCELLENT, regraded.grade)
        assertEquals(0, regraded.proxyMarginDb)
    }

    @Test
    fun `silence past the cutoff grades lost whatever the last packet said`() {
        val analyzer = SignalAnalyzer()
        for (i in 0 until 10) analyzer.add(SignalSample(ms(i * 100L), -50, SignalSource.ADVERTISEMENT))

        assertEquals(SignalGrade.EXCELLENT, analyzer.stats(ms(900)).grade)

        val lost = analyzer.stats(ms(5_901))
        assertEquals(SignalGrade.LOST, lost.grade)
        assertEquals(5_001L, lost.sinceLastMs)
        // The silence still running is as real a gap as one a later packet closed.
        assertEquals(1, lost.dropouts)
        assertEquals(5_001L, lost.longestGapMs)
    }

    @Test
    fun `an empty window claims nothing`() {
        val stats = SignalAnalyzer().stats(ms(1_000))

        assertEquals(0, stats.sampleCount)
        assertNull(stats.latestRssi)
        assertNull(stats.medianRssi)
        assertNull(stats.smoothedRssi)
        assertNull(stats.stdDevDb)
        assertNull(stats.lossPercent)
        assertNull(stats.advertisingIntervalMs)
        assertNull(stats.sinceLastMs)
        assertNull(stats.pathLossDb)
        assertNull(stats.estimatedDistanceM)
        assertEquals(0.0, stats.packetsPerSecond, 0.0)
        assertEquals(SignalGrade.LOST, stats.grade)
        assertEquals(60_000L, stats.windowMs)
    }

    @Test
    fun `fewer than five advertisements is not enough to claim a loss rate`() {
        val analyzer = SignalAnalyzer()
        for (i in 0 until 4) analyzer.add(SignalSample(ms(i * 100L), -60, SignalSource.ADVERTISEMENT))

        assertNull(analyzer.stats(ms(300)).lossPercent)

        analyzer.add(SignalSample(ms(400), -60, SignalSource.ADVERTISEMENT))
        assertEquals(0.0, analyzer.stats(ms(400)).lossPercent!!, 0.001)
    }

    @Test
    fun `path loss and distance come from the advertised tx power`() {
        val atOneMetre = SignalAnalyzer()
        for (i in 0 until 10) {
            atOneMetre.add(SignalSample(ms(i * 100L), -59, SignalSource.ADVERTISEMENT, txPower = -59))
        }
        val near = atOneMetre.stats(ms(900))
        assertEquals(0, near.pathLossDb)
        assertEquals(1.0, near.estimatedDistanceM!!, 0.001)

        val far = SignalAnalyzer()
        for (i in 0 until 10) {
            far.add(SignalSample(ms(i * 100L), -86, SignalSource.ADVERTISEMENT, txPower = -59))
        }
        val distant = far.stats(ms(900))
        assertEquals(27, distant.pathLossDb)
        // 27 dB of loss over 10 * n dB per decade with n = 2.7 is exactly one decade.
        assertEquals(10.0, distant.estimatedDistanceM!!, 0.001)
    }

    @Test
    fun `without a tx power there is no distance to guess at`() {
        val analyzer = SignalAnalyzer()
        for (i in 0 until 10) analyzer.add(SignalSample(ms(i * 100L), -70, SignalSource.ADVERTISEMENT))

        val stats = analyzer.stats(ms(900))
        assertNull(stats.pathLossDb)
        assertNull(stats.estimatedDistanceM)
    }

    @Test
    fun `connection reads keep the rssi alive without faking advertisements`() {
        val analyzer = SignalAnalyzer()
        for (i in 0 until 10) analyzer.add(SignalSample(ms(i * 1_000L), -60, SignalSource.ADVERTISEMENT))
        for (i in 10 until 20) analyzer.add(SignalSample(ms(i * 1_000L), -60, SignalSource.CONNECTION))
        val now = ms(19_000)

        val stats = analyzer.stats(now)

        assertEquals(20, stats.sampleCount)
        assertEquals(0L, stats.sinceLastMs)
        assertEquals(SignalGrade.GOOD, stats.grade)
        // Ten seconds without an advertisement is still a dropout in the advertising timeline,
        // even though the link itself is fine: that is the fault this whole screen exists for.
        assertEquals(0.0, stats.lossPercent!!, 0.001)
        assertEquals(1, stats.dropouts)
        assertEquals(10_000L, stats.longestGapMs)

        val presence = analyzer.presence(now)
        assertEquals(10, presence.count { it })
        assertTrue(presence.takeLast(9).none { it })
    }

    @Test
    fun `a minute of a fast advertiser survives the ring growing`() {
        val analyzer = SignalAnalyzer()
        // 50 packets a second for a full window: 3 000 samples through several ring resizes.
        for (i in 0 until 3_000) {
            analyzer.add(SignalSample(ms(i * 20L), -65 + i % 3, SignalSource.ADVERTISEMENT))
        }
        val now = ms(59_980)

        val stats = analyzer.stats(now)

        assertEquals(3_000, stats.sampleCount)
        assertEquals(50.0, stats.packetsPerSecond, 0.05)
        assertEquals(20.0, stats.advertisingIntervalMs!!, 0.001)
        assertEquals(0.0, stats.lossPercent!!, 0.001)
        assertEquals(-65, stats.minRssi)
        assertEquals(-63, stats.maxRssi)
        assertEquals(-64, stats.medianRssi)
        assertEquals(3_000, analyzer.history(now).size)
        assertTrue(analyzer.presence(now).all { it })
    }

    @Test
    fun `reset forgets the previous target instead of blending into it`() {
        val analyzer = SignalAnalyzer()
        for (i in 0 until 10) analyzer.add(SignalSample(ms(i * 100L), -50, SignalSource.ADVERTISEMENT))

        analyzer.reset()

        val cleared = analyzer.stats(ms(900))
        assertEquals(0, cleared.sampleCount)
        assertNull(cleared.medianRssi)
        assertEquals(SignalGrade.LOST, cleared.grade)
        assertTrue(analyzer.history(ms(900)).isEmpty())
        assertTrue(analyzer.presence(ms(900)).none { it })

        analyzer.add(SignalSample(ms(1_000), -80, SignalSource.ADVERTISEMENT))
        assertEquals(-80.0, analyzer.stats(ms(1_000)).smoothedRssi!!, 0.001)
    }

    private fun gradeOf(rssi: Int, margin: Int): SignalGrade {
        val analyzer = SignalAnalyzer(proxyMarginDb = margin)
        for (i in 0 until 10) analyzer.add(SignalSample(ms(i * 100L), rssi, SignalSource.ADVERTISEMENT))
        return analyzer.stats(ms(900)).grade
    }
}
