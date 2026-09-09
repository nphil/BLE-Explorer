package dev.nphil.blueshark.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pasteable report. It is read in a monospace block next to a Home Assistant issue, so the
 * columns have to line up and a fact that was never measured must not print as a zero.
 */
class SignalReportTest {

    private val waypoints = listOf(
        Waypoint(
            id = "1",
            label = "Tank hood",
            capturedAtEpochMs = 1_700_000_000_000,
            stats = statsOf(
                medianRssi = -63,
                stdDevDb = 2.8,
                lossPercent = 4.2,
                packetsPerSecond = 9.8,
                dropouts = 1,
                grade = SignalGrade.GOOD,
            ),
        ),
        Waypoint(
            id = "2",
            label = "Cabinet behind the glass",
            capturedAtEpochMs = 1_700_000_060_000,
            stats = statsOf(
                medianRssi = -84,
                stdDevDb = 6.1,
                lossPercent = 22.0,
                packetsPerSecond = 7.1,
                dropouts = 3,
                grade = SignalGrade.WEAK,
            ),
        ),
        Waypoint(
            id = "3",
            label = "Hallway",
            capturedAtEpochMs = 1_700_000_120_000,
            stats = statsOf(
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
                sinceLastMs = null,
                grade = SignalGrade.LOST,
            ),
        ),
    )

    @Test
    fun `every waypoint appears with its own numbers`() {
        val text = render()

        for (waypoint in waypoints) {
            assertTrue(waypoint.label, text.contains(waypoint.label))
        }
        assertTrue(text.contains("Fluval Aquasky (F4:B3:01:AA:BB:CC)"))
        assertTrue(text.contains("GOOD"))
        assertTrue(text.contains("WEAK"))
        assertTrue(text.contains("LOST"))
        assertTrue(text.contains("-84"))
        assertTrue(text.contains("22.0"))
        assertTrue(text.contains("proxy margin 8 dB · window 60 s"))
    }

    @Test
    fun `the waypoint columns line up under their headings`() {
        val lines = render().lines()
        val heading = lines.single { it.contains("median") && it.contains("dropouts") }
        val hood = lines.single { it.contains("Tank hood") }
        val cabinet = lines.single { it.contains("Cabinet behind the glass") }

        val dropoutEdge = heading.indexOf("dropouts") + "dropouts".length
        assertEquals(dropoutEdge, hood.indexOf("GOOD") - 2)
        assertEquals(dropoutEdge, cabinet.indexOf("WEAK") - 2)
        val medianEdge = heading.indexOf("median") + "median".length
        assertEquals(medianEdge, hood.indexOf("-63") + "-63".length)
        assertEquals(medianEdge, cabinet.indexOf("-84") + "-84".length)
    }

    @Test
    fun `a waypoint that measured nothing prints dashes rather than zeroes`() {
        val hallway = render().lines().single { it.contains("Hallway") }

        assertEquals(3, hallway.split(Regex("\\s+")).count { it == "-" })
        assertTrue(hallway.trimEnd().endsWith("LOST"))
    }

    @Test
    fun `a fact that was never measured gets no line at all`() {
        val withoutTxPower = render()
        assertFalse(withoutTxPower.contains("path loss"))

        val withTxPower = SignalReport.render(
            targetAddress = "F4:B3:01:AA:BB:CC",
            targetName = "Fluval Aquasky",
            current = statsOf(medianRssi = -72, pathLossDb = 13, estimatedDistanceM = 3.0),
            waypoints = emptyList(),
        )
        assertTrue(withTxPower.contains("path loss    13 dB (distance ~3.0 m at n=2.7)"))
    }

    @Test
    fun `the current window is spelled out above the table`() {
        val text = render()

        assertTrue(text.contains("rssi         -62 dBm (smoothed -62.0, median -62, min -66, max -58)"))
        assertTrue(text.contains("spread       2.0 dB"))
        assertTrue(text.contains("rate         9.8 /s (interval 100 ms)"))
        assertTrue(text.contains("loss         1.0 %"))
        assertTrue(text.contains("dropouts     0 (longest gap 0.2 s)"))
        assertTrue(text.contains("last packet  0.1 s ago"))
        assertTrue(text.contains("samples      300 in 60 s"))
        assertTrue(text.contains("grade        GOOD (-70 dBm after 8 dB proxy margin)"))
    }

    @Test
    fun `an unnamed target with nowhere marked yet still renders`() {
        val text = SignalReport.render(
            targetAddress = "F4:B3:01:AA:BB:CC",
            targetName = null,
            current = statsOf(),
            waypoints = emptyList(),
        )

        assertTrue(text.startsWith("BlueShark signal report\ntarget       F4:B3:01:AA:BB:CC\n"))
        assertTrue(text.contains("none marked"))
    }

    private fun render(): String = SignalReport.render(
        targetAddress = "F4:B3:01:AA:BB:CC",
        targetName = "Fluval Aquasky",
        current = statsOf(),
        waypoints = waypoints,
    )
}
