package dev.nphil.blueshark.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advice. Each case is judged on two things: that the right hint fires, and that it quotes the
 * measurement that fired it — advice with no number behind it cannot be checked from the next spot.
 */
class SignalDiagnosisTest {

    private val heard = List(60) { true }

    @Test
    fun `a healthy window has nothing to say`() {
        assertTrue(SignalDiagnosis.hints(statsOf(), connectable = true, presence = heard).isEmpty())
    }

    @Test
    fun `a device that goes quiet after advertising steadily is called claimed, not weak`() {
        // 45 s of packets, then 15 s of nothing at all: the shape a peripheral makes when another
        // central connects to it.
        val presence = List(60) { it < 45 }

        val hints = SignalDiagnosis.hints(
            statsOf(medianRssi = -62, sinceLastMs = 15_000, grade = SignalGrade.LOST),
            connectable = true,
            presence = presence,
        )

        val first = hints.first()
        assertEquals(HintSeverity.BAD, first.severity)
        assertEquals("Device went silent", first.title)
        assertTrue(first.detail.contains("15.0 s"))
        assertTrue(first.detail.contains("45 s"))
        assertTrue(first.detail.contains("-62 dBm"))
        assertTrue(first.detail.contains("connection"))
    }

    @Test
    fun `silence with no advertising behind it is only reported as no signal`() {
        val never = SignalDiagnosis.hints(
            statsOf(sinceLastMs = null, medianRssi = null, grade = SignalGrade.LOST),
            connectable = true,
            presence = List(60) { false },
        )
        assertEquals("No signal here", never.first().title)

        // A device that never said it was connectable cannot be blamed on another central.
        val unknown = SignalDiagnosis.hints(
            statsOf(sinceLastMs = 15_000, grade = SignalGrade.LOST),
            connectable = null,
            presence = List(60) { it < 45 },
        )
        assertEquals("No signal here", unknown.first().title)
        assertTrue(unknown.none { it.title == "Device went silent" })
    }

    @Test
    fun `a weak median names the raw number, the margin and the floor`() {
        val hints = SignalDiagnosis.hints(
            statsOf(medianRssi = -85, grade = SignalGrade.WEAK),
            connectable = true,
            presence = heard,
        )

        val hint = hints.single { it.title == "Too weak for a proxy" }
        assertEquals(HintSeverity.BAD, hint.severity)
        assertTrue(hint.detail.contains("-85 dBm"))
        assertTrue(hint.detail.contains("-93 dBm"))
        assertTrue(hint.detail.contains("8 dB proxy margin"))
    }

    @Test
    fun `a swinging signal over a usable median is called multipath`() {
        val hints = SignalDiagnosis.hints(
            statsOf(medianRssi = -65, minRssi = -85, maxRssi = -55, stdDevDb = 9.4),
            connectable = true,
            presence = heard,
        )

        val hint = hints.single { it.title.startsWith("Signal swinging") }
        assertEquals(HintSeverity.WARN, hint.severity)
        assertTrue(hint.detail.contains("9.4 dB"))
        assertTrue(hint.detail.contains("-85 dBm"))
        assertTrue(hint.detail.contains("Water and metal"))
    }

    @Test
    fun `a weak spot is told to move, not to rotate`() {
        val hints = SignalDiagnosis.hints(
            statsOf(medianRssi = -85, stdDevDb = 9.4, grade = SignalGrade.WEAK),
            connectable = true,
            presence = heard,
        )

        assertEquals("Too weak for a proxy", hints.first().title)
        assertTrue(hints.none { it.title.startsWith("Signal swinging") })
    }

    @Test
    fun `loss at a usable rssi is blamed on the band, not the distance`() {
        val hints = SignalDiagnosis.hints(
            statsOf(lossPercent = 27.0, packetsPerSecond = 7.3),
            connectable = true,
            presence = heard,
        )

        val hint = hints.single { it.title == "Packets being eaten" }
        assertEquals(HintSeverity.WARN, hint.severity)
        assertTrue(hint.detail.contains("27.0 %"))
        assertTrue(hint.detail.contains("7.3"))
        assertTrue(hint.detail.contains("2426"))
    }

    @Test
    fun `repeated dropouts are reported with the longest gap`() {
        val hints = SignalDiagnosis.hints(
            statsOf(dropouts = 3, longestGapMs = 8_200),
            connectable = true,
            presence = heard,
        )

        val hint = hints.single { it.title == "3 dropouts in 60 s" }
        assertEquals(HintSeverity.WARN, hint.severity)
        assertTrue(hint.detail.contains("8.2 s"))

        assertTrue(
            SignalDiagnosis.hints(statsOf(dropouts = 1), connectable = true, presence = heard)
                .none { it.title.endsWith("dropouts in 60 s") },
        )
    }

    @Test
    fun `a slow advertiser is explained in terms of what a proxy hears`() {
        val hints = SignalDiagnosis.hints(
            statsOf(advertisingIntervalMs = 1_500.0, packetsPerSecond = 0.7),
            connectable = true,
            presence = heard,
        )

        val hint = hints.single { it.title == "Slow advertiser" }
        assertEquals(HintSeverity.WARN, hint.severity)
        assertTrue(hint.detail.contains("1500 ms"))
    }

    @Test
    fun `a non-connectable target is flagged as watch-only`() {
        val hints = SignalDiagnosis.hints(statsOf(), connectable = false, presence = heard)

        val hint = hints.single { it.title == "Not connectable" }
        assertEquals(HintSeverity.WARN, hint.severity)
        assertTrue(hint.detail.contains("watched but not written to"))
    }

    @Test
    fun `the distance estimate always arrives with its caveat`() {
        val hints = SignalDiagnosis.hints(
            statsOf(medianRssi = -72, pathLossDb = 13, estimatedDistanceM = 3.0),
            connectable = true,
            presence = heard,
        )

        val hint = hints.single { it.severity == HintSeverity.INFO }
        assertEquals("Roughly 3.0 m away", hint.title)
        assertTrue(hint.detail.contains("n=2.7"))
        assertTrue(hint.detail.contains("13 dB"))
        assertTrue(hint.detail.contains("Indicative only"))
    }

    @Test
    fun `the worst finding is offered first`() {
        val hints = SignalDiagnosis.hints(
            statsOf(
                medianRssi = -85,
                lossPercent = 31.0,
                dropouts = 4,
                stdDevDb = 9.9,
                pathLossDb = 26,
                estimatedDistanceM = 8.9,
                grade = SignalGrade.WEAK,
            ),
            connectable = false,
            presence = heard,
        )

        assertEquals(HintSeverity.BAD, hints.first().severity)
        assertEquals(HintSeverity.INFO, hints.last().severity)
    }
}
