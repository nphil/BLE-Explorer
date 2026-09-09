package dev.nphil.blueshark.probe

import dev.nphil.blueshark.model.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The CoolLED framing is the one thing here that was verified against real hardware, so the
 * verified vector is asserted byte for byte. Everything else in the prober reads the panel through
 * this codec: if the framing drifts, every verdict it produces is a fiction.
 */
class FrameCodecTest {

    /**
     * Captured from the operator's iLedClock (service 0000fff0, characteristic 0000fff1): writing
     * payload `08 FF` framed as `010204020608ff03` changed the panel's mode. Three bytes of the
     * `00 02` length prefix are below 0x04 and therefore doubled, which is why two payload bytes
     * leave as eight.
     */
    @Test
    fun `the hardware-verified vector encodes exactly`() {
        val frame = CoolLedCodec.encode(byteArrayOf(0x08, 0xFF.toByte()))
        assertEquals("010204020608FF03", frame.toHex())
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x04, 0x02, 0x06, 0x08, 0xFF.toByte(), 0x03),
            frame,
        )
    }

    @Test
    fun `every byte that collides with a marker is escaped and nothing else is`() {
        // 0x00..0x03 double; 0x04 and 0xFF pass through. Prefix 00 06: only the high byte doubles.
        val frame = CoolLedCodec.encode(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0xFF.toByte()))
        assertEquals("01020406020402050206020704FF03", frame.toHex())
    }

    @Test
    fun `an empty payload still carries a well-formed length prefix`() {
        val frame = CoolLedCodec.encode(ByteArray(0))
        assertEquals("010204020403", frame.toHex())
        assertArrayEquals(ByteArray(0), CoolLedCodec.decode(frame))
    }

    @Test
    fun `every payload round-trips including the bytes that need escaping`() {
        val payloads = listOf(
            byteArrayOf(0x08, 0xFF.toByte()),
            byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05),
            byteArrayOf(0x23, 0x01),
            byteArrayOf(0x02),
            byteArrayOf(0x00),
            ByteArray(0),
            ByteArray(300) { (it and 0xFF).toByte() },
        )
        for (payload in payloads) {
            val decoded = CoolLedCodec.decode(CoolLedCodec.encode(payload))
            assertArrayEquals("round trip of ${payload.toHex()}", payload, decoded)
        }
    }

    @Test
    fun `a payload longer than 255 bytes fills the high length byte`() {
        val frame = CoolLedCodec.encode(ByteArray(0x0104) { 0x40 })
        // len = 0x0104: 0x01 escapes to 02 05, 0x04 passes through.
        assertEquals("01020504", frame.toHex().take(8))
        assertEquals(0x0104, CoolLedCodec.decode(frame)?.size)
    }

    @Test
    fun `a truncated frame does not decode`() {
        val frame = CoolLedCodec.encode(byteArrayOf(0x08, 0xFF.toByte()))
        for (length in 0 until frame.size) {
            assertNull("prefix of $length bytes", CoolLedCodec.decode(frame.copyOf(length)))
        }
    }

    @Test
    fun `a declared length that disagrees with the payload does not decode`() {
        // Frame says three payload bytes, carries two.
        assertNull(CoolLedCodec.decode(byteArrayOf(0x01, 0x02, 0x04, 0x02, 0x07, 0x08, 0xFF.toByte(), 0x03)))
        // Frame says one payload byte, carries two.
        assertNull(CoolLedCodec.decode(byteArrayOf(0x01, 0x02, 0x04, 0x02, 0x05, 0x08, 0xFF.toByte(), 0x03)))
    }

    @Test
    fun `a frame without its terminator does not decode`() {
        assertNull(CoolLedCodec.decode(byteArrayOf(0x01, 0x02, 0x04, 0x02, 0x06, 0x08, 0xFF.toByte())))
    }

    @Test
    fun `a frame without its start marker does not decode`() {
        assertNull(CoolLedCodec.decode(byteArrayOf(0x02, 0x04, 0x02, 0x06, 0x08, 0xFF.toByte(), 0x03)))
    }

    @Test
    fun `an escape marker with nothing to escape does not decode`() {
        // Trailing 0x02 immediately before the terminator: the escaped byte is missing.
        assertNull(CoolLedCodec.decode(byteArrayOf(0x01, 0x02, 0x04, 0x02, 0x06, 0x08, 0x02, 0x03)))
    }

    @Test
    fun `a length prefix alone with no payload decodes to an empty payload rather than failing`() {
        assertArrayEquals(ByteArray(0), CoolLedCodec.decode(byteArrayOf(0x01, 0x02, 0x04, 0x02, 0x04, 0x03)))
    }

    @Test
    fun `the raw codec writes and reads the payload verbatim`() {
        val payload = byteArrayOf(0x08, 0xFF.toByte(), 0x00)
        assertSame(payload, RawCodec.encode(payload))
        assertArrayEquals(payload, RawCodec.decode(payload))
    }

    @Test
    fun `codecs are addressable by the id that gets persisted`() {
        assertSame(CoolLedCodec, FrameCodecs.byId("coolled"))
        assertSame(RawCodec, FrameCodecs.byId("raw"))
        assertNull(FrameCodecs.byId("nope"))
        assertEquals(FrameCodecs.all.size, FrameCodecs.all.map { it.id }.distinct().size)
    }
}
