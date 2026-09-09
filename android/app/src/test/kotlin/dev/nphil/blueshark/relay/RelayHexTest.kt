package dev.nphil.blueshark.relay

import dev.nphil.blueshark.model.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The relay encodes every packet with its own hex writer instead of the shared `toHex`, which
 * formats byte by byte. Identical output is the whole point, so it is asserted here rather than
 * assumed.
 */
class RelayHexTest {

    @Test
    fun `the fast encoder matches the canonical hex of every byte value`() {
        val all = ByteArray(256) { it.toByte() }
        assertEquals(all.toHex(), all.toHexFast())
        assertEquals("", ByteArray(0).toHexFast())
        assertEquals("00FF7F80", byteArrayOf(0, -1, 127, -128).toHexFast())
    }

    @Test
    fun `grouping inserts one space between byte pairs and nowhere else`() {
        assertEquals("", "".groupedHex())
        assertEquals("A1", "A1".groupedHex())
        assertEquals("A1 02 FF", "A102FF".groupedHex())
        assertEquals("00 00", "0000".groupedHex())
    }

    @Test
    fun `the ascii gutter keeps printable bytes and dots the rest`() {
        assertEquals("AT+", "41542B".asciiGutter())
        assertEquals("..", "000A".asciiGutter())
        assertEquals("~.", "7E7F".asciiGutter())
        assertEquals("", "".asciiGutter())
    }

    @Test
    fun `assigned uuids collapse to their short form and vendor uuids do not`() {
        assertEquals("0x2A00", "00002A00-0000-1000-8000-00805F9B34FB".shortUuid())
        assertEquals("0x12342A00", "12342A00-0000-1000-8000-00805F9B34FB".shortUuid())
        assertEquals(
            "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e".shortUuid(),
        )
    }
}
