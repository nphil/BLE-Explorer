package dev.nphil.blueshark.learn

import dev.nphil.blueshark.hci.SnoopCapabilities
import dev.nphil.blueshark.shell.ShizukuState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnReadinessTest {
    private fun caps(service: String = "", stack: String = "", property: String = "", probed: Boolean = true) =
        SnoopCapabilities(
            serviceSnoopSetting = service,
            stackSnoopLog = stack,
            snoopMode = property,
            probedAtEpochMs = if (probed) 1L else 0L,
        )

    private fun snoopRow(r: Readiness) = r.items.first { it.id == "snoop" }

    @Test
    fun `a stack already logging in full mode is never asked to restart`() {
        val r = LearnReadiness.evaluate(
            TrafficSource.HCI_SNOOP,
            caps(service = "FULL", stack = "SnoopLogger: Snoop Logs full mode enabled"),
            ShizukuState.Ready(2000, 13),
            observerEnabled = true,
            vendorAppLabel = "CoolLED1248",
        )
        assertTrue(snoopRow(r).satisfied)
        assertEquals(ChecklistAction.NONE, snoopRow(r).action)
        assertTrue(r.canStart)
    }

    @Test
    fun `toggle on but stack started before it means exactly one restart`() {
        val r = LearnReadiness.evaluate(
            TrafficSource.HCI_SNOOP,
            caps(service = "DISABLED", stack = "SnoopLogger: Snoop Logs disabled", property = "full"),
            ShizukuState.Ready(2000, 13),
            observerEnabled = true,
            vendorAppLabel = "CoolLED1248",
        )
        assertEquals(ChecklistAction.RESTART_BLUETOOTH, snoopRow(r).action)
        assertFalse(r.canStart)
    }

    @Test
    fun `toggle off sends the operator to developer options, not to a restart`() {
        val r = LearnReadiness.evaluate(
            TrafficSource.HCI_SNOOP,
            // A readable property implies a working shell probe, so Shizuku must be ready here.
            caps(service = "DISABLED", stack = "SnoopLogger: Snoop Logs disabled", property = "disabled"),
            ShizukuState.Ready(2000, 13),
            observerEnabled = true,
            vendorAppLabel = "CoolLED1248",
        )
        assertEquals(ChecklistAction.OPEN_DEVELOPER_OPTIONS, snoopRow(r).action)
        assertEquals(ChecklistAction.NONE, snoopRow(r).secondaryAction)
    }

    @Test
    fun `an OEM that hides the property gets both fixes offered, neither guessed`() {
        // HyperOS: getprop returns nothing for the snoop property, dumpsys says DISABLED.
        val r = LearnReadiness.evaluate(
            TrafficSource.HCI_SNOOP,
            caps(service = "DISABLED", stack = "", property = ""),
            ShizukuState.Ready(2000, 13),
            observerEnabled = true,
            vendorAppLabel = "CoolLED1248",
        )
        assertEquals(ChecklistAction.OPEN_DEVELOPER_OPTIONS, snoopRow(r).action)
        assertEquals(ChecklistAction.RESTART_BLUETOOTH, snoopRow(r).secondaryAction)
        assertTrue(snoopRow(r).detail.contains("hides the toggle"))
    }

    @Test
    fun `missing Shizuku slows collection but does not block the session`() {
        val r = LearnReadiness.evaluate(
            TrafficSource.HCI_SNOOP,
            caps(service = "FULL", stack = "SnoopLogger: Snoop Logs full mode enabled"),
            ShizukuState.NotInstalled,
            observerEnabled = true,
            vendorAppLabel = "CoolLED1248",
        )
        val collect = r.items.first { it.id == "collect" }
        assertFalse(collect.satisfied)
        assertTrue(collect.optional)
        assertTrue(r.canStart)
        assertTrue(r.blocking.isEmpty())
    }

    @Test
    fun `a relay session needs neither snoop nor the observer`() {
        val r = LearnReadiness.evaluate(
            TrafficSource.RELAY,
            caps(probed = false),
            ShizukuState.NotInstalled,
            observerEnabled = false,
            vendorAppLabel = null,
            relayRunning = true,
        )
        assertTrue(r.items.none { it.id == "snoop" || it.id == "collect" })
        assertTrue(r.canStart)
    }

    @Test
    fun `the observer and the app are the blocking rows for a snoop session`() {
        val r = LearnReadiness.evaluate(
            TrafficSource.HCI_SNOOP,
            caps(service = "FULL", stack = "SnoopLogger: Snoop Logs full mode enabled"),
            ShizukuState.Ready(2000, 13),
            observerEnabled = false,
            vendorAppLabel = null,
        )
        assertEquals(listOf("app", "observer"), r.blocking.map { it.id })
    }
}

class LearnReadinessUnobservableTest {
    @Test
    fun `without Shizuku the snoop row is the operator's word, and it can be satisfied`() {
        val unconfirmed = LearnReadiness.evaluate(
            TrafficSource.HCI_SNOOP,
            SnoopCapabilities(probedAtEpochMs = 1L, error = "Shizuku is not installed"),
            ShizukuState.NotInstalled,
            observerEnabled = true,
            vendorAppLabel = "CoolLED1248",
        )
        val row = unconfirmed.items.first { it.id == "snoop" }
        assertEquals(ChecklistAction.CONFIRM_SNOOP, row.action)
        assertEquals(ChecklistAction.OPEN_DEVELOPER_OPTIONS, row.secondaryAction)
        assertFalse(unconfirmed.canStart)

        val confirmed = LearnReadiness.evaluate(
            TrafficSource.HCI_SNOOP,
            SnoopCapabilities(probedAtEpochMs = 1L, error = "Shizuku is not installed"),
            ShizukuState.NotInstalled,
            observerEnabled = true,
            vendorAppLabel = "CoolLED1248",
            snoopConfirmedByOperator = true,
        )
        assertTrue(confirmed.items.first { it.id == "snoop" }.satisfied)
        assertTrue(confirmed.canStart)
    }
}
