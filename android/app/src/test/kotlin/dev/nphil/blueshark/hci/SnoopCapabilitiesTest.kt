package dev.nphil.blueshark.hci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoopCapabilitiesTest {

    private val getprop = """
        [persist.bluetooth.btsnoopdefaultmode]: [full]
        [ro.build.fingerprint]: [Xiaomi/whatever]
        [persist.bluetooth.btsnoopenable]: [false]
        [persist.sys.usb.config]: [mtp,adb]
    """.trimIndent()

    @Test
    fun `logmode wins over defaultmode`() {
        val caps = SnoopCapabilities(snoopMode = "filtered", snoopProperties = snoopPropertyLines(getprop))
        assertEquals("filtered", caps.effectiveSnoopMode)
        assertFalse(caps.snoopModeIsFull)
    }

    @Test
    fun `unset logmode falls back to defaultmode`() {
        val caps = SnoopCapabilities(snoopMode = "", snoopProperties = snoopPropertyLines(getprop))
        assertEquals("full", caps.effectiveSnoopMode)
        assertTrue(caps.snoopModeIsFull)
    }

    @Test
    fun `legacy boolean enable means full when nothing else is set`() {
        val caps = SnoopCapabilities(snoopProperties = listOf("[persist.bluetooth.btsnoopenable]: [true]"))
        assertTrue(caps.snoopModeIsFull)
        assertFalse(SnoopCapabilities(snoopProperties = listOf("[persist.bluetooth.btsnoopenable]: [false]")).snoopModeIsFull)
    }

    @Test
    fun `property filter keeps only snoop lines`() {
        assertEquals(
            listOf("[persist.bluetooth.btsnoopdefaultmode]: [full]", "[persist.bluetooth.btsnoopenable]: [false]"),
            snoopPropertyLines(getprop),
        )
    }
}
