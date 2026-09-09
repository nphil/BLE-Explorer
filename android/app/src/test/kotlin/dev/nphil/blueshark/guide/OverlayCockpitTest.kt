package dev.nphil.blueshark.guide

import dev.nphil.blueshark.model.ControlRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cockpit's two promises: it never sits on top of the controls the operator has to drive, and
 * every line it shows is either a fact or an admission that the fact is not knowable yet.
 */
class OverlayCockpitTest {

    private fun control(top: Int, bottom: Int) = ControlRef(
        packageName = "com.vendor.app",
        screen = "MainActivity",
        className = "android.widget.Button",
        bounds = listOf(0, top, 1080, bottom),
    )

    @Test
    fun `the cockpit docks to the emptier edge`() {
        val bottomHeavy = listOf(control(1600, 1700), control(1800, 1900), control(900, 1000))
        assertEquals(Dock.TOP, chooseDock(bottomHeavy, SCREEN))

        val topHeavy = listOf(control(10, 100), control(200, 300))
        assertEquals(Dock.BOTTOM, chooseDock(topHeavy, SCREEN))
    }

    @Test
    fun `a tie, an empty screen and an unmeasured one all stay put`() {
        // A full-height control is behind either dock, so there is nothing to gain by moving.
        assertEquals(Dock.TOP, chooseDock(listOf(control(0, SCREEN)), SCREEN))
        assertEquals(Dock.TOP, chooseDock(emptyList(), SCREEN))
        assertEquals(Dock.TOP, chooseDock(listOf(control(10, 100)), 0))
        // Geometry the observer could not read must not be counted as evidence either way.
        assertEquals(Dock.TOP, chooseDock(listOf(control(10, 100).copy(bounds = emptyList())), SCREEN))
    }

    @Test
    fun `the session line carries the app and the clock`() {
        assertEquals("Learning · Mi Home · 0:07", sessionLine("Mi Home", 7_400L))
        assertEquals("Learning · 1:02:03", sessionLine("  ", 3_723_000L))
        assertEquals("Learning · Mi Home · 0:00", sessionLine("Mi Home", -5L))
    }

    @Test
    fun `a device that goes quiet mid-session is reported as held, not as missing`() {
        val silent = TargetStatus(
            name = "iLedClock",
            address = ADDRESS,
            presence = TargetPresence.SILENT,
            lastSeenMs = 5_000L,
        )

        assertEquals("iLedClock · held by a central (the app?)", targetLine(silent, sessionStartedAtMs = 1_000L))
        // Heard only before this session began: that is not evidence of the app connecting.
        assertEquals("iLedClock · not advertising", targetLine(silent, sessionStartedAtMs = 6_000L))
        assertEquals("iLedClock · not advertising", targetLine(silent.copy(lastSeenMs = 0L), 1_000L))
        assertEquals("iLedClock · advertising", targetLine(silent.copy(presence = TargetPresence.ADVERTISING), 1_000L))
        // No name yet: the address is who it is.
        assertEquals("$ADDRESS · presence unknown", targetLine(TargetStatus(address = ADDRESS), 1_000L))
        assertEquals("no target device picked", targetLine(TargetStatus(), 1_000L))
    }

    @Test
    fun `presence follows the last advertisement, and only while something is listening`() {
        assertEquals(TargetPresence.UNKNOWN, targetPresence(scanning = false, lastSeenMs = 9_000L, nowMs = 9_100L))
        assertEquals(
            TargetPresence.ADVERTISING,
            targetPresence(scanning = true, lastSeenMs = 9_000L, nowMs = 9_000L + TARGET_SILENCE_MS),
        )
        assertEquals(
            TargetPresence.SILENT,
            targetPresence(scanning = true, lastSeenMs = 9_000L, nowMs = 9_001L + TARGET_SILENCE_MS),
        )
        assertEquals(TargetPresence.SILENT, targetPresence(scanning = true, lastSeenMs = 0L, nowMs = 9_000L))
        // A clock that jumped backwards must not turn a live device silent.
        assertEquals(TargetPresence.ADVERTISING, targetPresence(scanning = true, lastSeenMs = 9_000L, nowMs = 8_000L))
    }

    @Test
    fun `the observer is only called quiet once the silence is suspicious`() {
        assertEquals("", quietLine(0L))
        assertEquals("", quietLine(OBSERVER_QUIET_WARN_S - 1L))
        assertEquals("observer quiet for $OBSERVER_QUIET_WARN_S s", quietLine(OBSERVER_QUIET_WARN_S))
        // Nothing delivered yet is not silence; the session may have only just started.
        assertEquals(0L, quietSeconds(lastEventAtMs = 0L, nowMs = 90_000L))
        assertEquals(42L, quietSeconds(lastEventAtMs = 10_000L, nowMs = 52_000L))
    }

    private companion object {
        const val SCREEN = 2_000
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
