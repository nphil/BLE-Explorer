package dev.nphil.blueshark.hci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoopCapabilitiesTest {

    private val getprop = """
        [persist.bluetooth.btsnoopdefaultmode]: [full]
        [ro.build.fingerprint]: [Xiaomi/whatever]
        [ro.debuggable]: [0]
        [persist.vendor.service.bt.adv_snoop]: []
        [persist.sys.usb.config]: [mtp,adb]
    """.trimIndent()

    private val logcat = """
        09-08 23:30:01.100  I bluetooth: Snoop Logs disabled
        09-08 23:30:01.101  I bluetooth: something else
        09-08 23:41:12.400  I bluetooth: Snoop Logs full mode enabled
    """.trimIndent()

    @Test
    fun `stack announcement beats the property view`() {
        val caps = SnoopCapabilities(
            snoopMode = "",
            snoopProperties = snoopPropertyLines(getprop),
            stackSnoopLog = latestStackSnoopLine(logcat),
        )
        assertEquals("full", caps.stackSnoopMode)
        assertTrue(caps.snoopModeIsFull)
    }

    @Test
    fun `newest announcement wins`() {
        val reversed = logcat.lines().reversed().joinToString("\n")
        assertEquals("disabled", SnoopCapabilities(stackSnoopLog = latestStackSnoopLine(reversed)).stackSnoopMode)
    }

    @Test
    fun `without an announcement the logmode property decides`() {
        val caps = SnoopCapabilities(snoopMode = "filtered", snoopProperties = snoopPropertyLines(getprop))
        assertEquals("filtered", caps.effectiveSnoopMode)
        assertFalse(caps.snoopModeIsFull)
    }

    @Test
    fun `defaultmode only applies on debuggable builds`() {
        val userBuild = SnoopCapabilities(snoopProperties = snoopPropertyLines(getprop))
        assertEquals("", userBuild.effectiveSnoopMode)

        val debuggable = SnoopCapabilities(
            snoopProperties = snoopPropertyLines(getprop.replace("[ro.debuggable]: [0]", "[ro.debuggable]: [1]")),
        )
        assertEquals("full", debuggable.effectiveSnoopMode)
        assertEquals(
            "filtered",
            SnoopCapabilities(snoopProperties = listOf("[ro.debuggable]: [1]")).effectiveSnoopMode,
        )
    }

    @Test
    fun `property filter keeps snoop lines and the debuggable flag`() {
        assertEquals(
            listOf(
                "[persist.bluetooth.btsnoopdefaultmode]: [full]",
                "[persist.vendor.service.bt.adv_snoop]: []",
                "[ro.debuggable]: [0]",
            ),
            snoopPropertyLines(getprop),
        )
    }

    @Test
    fun `service setting from dumpsys beats stack and property views`() {
        val dump = """
            [ ]: snoop_logger_tracing
            sSnoopLogSettingAtEnable = FULL
            sDefaultSnoopLogSettingAtEnable = null
        """.trimIndent()
        assertEquals("FULL", serviceSnoopSetting(dump))
        assertEquals("", serviceSnoopSetting("nothing here"))

        val caps = SnoopCapabilities(
            snoopMode = "",
            serviceSnoopSetting = serviceSnoopSetting(dump),
            stackSnoopLog = "Snoop Logs disabled",
        )
        assertEquals("full", caps.effectiveSnoopMode)
        assertTrue(caps.snoopModeIsFull)
        // An unknown token (e.g. "empty") must not mask the other signals.
        assertEquals("disabled", caps.copy(serviceSnoopSetting = "empty").effectiveSnoopMode)
    }
}
