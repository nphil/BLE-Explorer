package dev.nphil.blestudio.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import dev.nphil.blestudio.ui.capture.shortUuid as captureShortUuid
import dev.nphil.blestudio.ui.sessions.shortUuid as sessionShortUuid

/**
 * One session mixes UUID spellings: the live-GATT client and the btsnoop parser store them
 * lowercase, the MITM relay stores them uppercase, and both end up in the same timeline. Every
 * display helper therefore has to collapse either spelling, which is what regressed before.
 */
class UuidDisplayTest {

    @Test
    fun `the session timeline collapses assigned uuids in either case`() {
        assertEquals("FFF1", sessionShortUuid("0000fff1-0000-1000-8000-00805f9b34fb"))
        assertEquals("FFF1", sessionShortUuid("0000FFF1-0000-1000-8000-00805F9B34FB"))
    }

    @Test
    fun `the session timeline keeps vendor uuids whole and marks missing ones`() {
        assertEquals(
            "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
            sessionShortUuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        )
        assertEquals("—", sessionShortUuid(null))
        assertEquals("—", sessionShortUuid("   "))
    }

    @Test
    fun `the capture timeline collapses assigned uuids in either case`() {
        assertEquals("0xFA02", captureShortUuid("0000fa02-0000-1000-8000-00805f9b34fb"))
        assertEquals("0xFA02", captureShortUuid("0000FA02-0000-1000-8000-00805F9B34FB"))
        assertEquals("0x12342A00", captureShortUuid("12342a00-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun `the capture timeline leaves a vendor uuid exactly as captured`() {
        val vendor = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        assertEquals(vendor, captureShortUuid(vendor))
    }
}
