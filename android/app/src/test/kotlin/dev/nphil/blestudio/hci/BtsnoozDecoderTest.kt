package dev.nphil.blestudio.hci

import dev.nphil.blestudio.model.AttOperation
import dev.nphil.blestudio.model.EventDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

class BtsnoozDecoderTest {

    private val epochDeltaMs = 0x00dcddb30f2f8000L / 1000
    private val unixMs = 1_714_521_600_000L

    // ------------------------------------------------------------------ builders

    private fun snoozV2(lastTimestampMs: Long, records: List<Record>): ByteArray = snooz(2, lastTimestampMs, records)

    private fun snoozV1(lastTimestampMs: Long, records: List<Record>): ByteArray = snooz(1, lastTimestampMs, records)

    private class Record(val type: Int, val packet: ByteArray, val deltaMs: Long, val originalLength: Int = packet.size + 1)

    private fun snooz(version: Int, lastTimestampMs: Long, records: List<Record>): ByteArray {
        val body = ByteArrayOutputStream()
        records.forEach { record ->
            val length = record.packet.size + 1 // the type byte counts toward the record length
            body.writeLe16(length)
            if (version == 2) body.writeLe16(record.originalLength)
            body.writeLe32(record.deltaMs)
            body.write(record.type)
            body.write(record.packet)
        }
        val raw = body.toByteArray()
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (!deflater.finished()) {
            val produced = deflater.deflate(chunk)
            compressed.write(chunk, 0, produced)
        }
        deflater.end()

        val out = ByteArrayOutputStream()
        out.write(version)
        for (shift in 0 until 64 step 8) out.write(((lastTimestampMs ushr shift) and 0xFF).toInt())
        out.write(compressed.toByteArray())
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Long) {
        for (shift in 0 until 32 step 8) write(((value ushr shift) and 0xFF).toInt())
    }

    private fun decode(snooz: ByteArray): Pair<BtsnoozResult, ByteArray> {
        val out = ByteArrayOutputStream()
        val result = BtsnoozDecoder.decodeTo(snooz, out)
        return result to out.toByteArray()
    }

    /** Outgoing ACL packet (btsnooz type 0x21) carrying a complete ATT PDU. */
    private fun outgoingAtt(handle: Int, pdu: ByteArray) = Record(0x21, Fixtures.att(handle, pdu).drop(1).toByteArray(), 0)

    private fun incomingAtt(handle: Int, pdu: ByteArray, deltaMs: Long) =
        Record(0x11, Fixtures.att(handle, pdu).drop(1).toByteArray(), deltaMs)

    // ------------------------------------------------------------------ tests

    @Test
    fun `decodes a v2 stream into a btsnoop file the parser accepts`() {
        val write = outgoingAtt(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x03, 0x01)))
        val notify = incomingAtt(0x0040, Fixtures.notification(0x0028, byteArrayOf(0x64)), deltaMs = 20)

        val (result, btsnoop) = decode(snoozV2(unixMs + epochDeltaMs, listOf(write, notify)))

        assertEquals(2, result.version)
        assertEquals(2, result.records)
        assertEquals("btsnoop epoch delta", result.timestampBase)

        val parsed = BtsnoopParser().parse(btsnoop.inputStream())
        assertEquals(2, parsed.summary.records)
        assertEquals(2, parsed.events.size)

        val first = parsed.events[0]
        assertEquals(AttOperation.WRITE_COMMAND, first.operation)
        assertEquals(EventDirection.PHONE_TO_DEVICE, first.direction)
        assertEquals("0301", first.payloadHex)
        assertEquals((unixMs - 20) * 1000, first.timestampEpochMicros)

        val second = parsed.events[1]
        assertEquals(AttOperation.NOTIFICATION, second.operation)
        assertEquals(EventDirection.DEVICE_TO_PHONE, second.direction)
        assertEquals(unixMs * 1000, second.timestampEpochMicros)
    }

    @Test
    fun `decodes a v1 stream, which has no separate original length`() {
        val write = outgoingAtt(0x0041, Fixtures.writeCommand(0x0031, byteArrayOf(0x7F)))

        val (result, btsnoop) = decode(snoozV1(unixMs + epochDeltaMs, listOf(write)))

        assertEquals(1, result.version)
        assertEquals(1, result.records)
        val parsed = BtsnoopParser().parse(btsnoop.inputStream())
        assertEquals(0x0031, parsed.events.single().attributeHandle)
        assertEquals(0, parsed.summary.truncated)
    }

    @Test
    fun `rejects an unsupported btsnooz version`() {
        val error = assertThrows(BtsnoopFormatException::class.java) {
            decode(snooz(3, unixMs + epochDeltaMs, listOf(outgoingAtt(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x01))))))
        }
        assertTrue(error.message!!.contains("version 3"))
    }

    @Test
    fun `rejects a payload that is not zlib data`() {
        val broken = ByteArray(9) + byteArrayOf(0x41, 0x42, 0x43, 0x44)
        broken[0] = 2
        assertThrows(BtsnoopFormatException::class.java) { decode(broken) }
    }

    @Test
    fun `anchors times and warns when the timestamp base is not a clock`() {
        val (result, _) = decode(snoozV2(0, listOf(outgoingAtt(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x01))))))

        assertEquals("anchored to extraction time", result.timestampBase)
        assertTrue(result.warnings.any { it.contains("not a recognisable clock") })
    }

    @Test
    fun `accepts a base stored as plain unix milliseconds`() {
        val (result, _) = decode(snoozV2(unixMs, listOf(outgoingAtt(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x01))))))

        assertEquals("Unix milliseconds", result.timestampBase)
    }

    @Test
    fun `stops at the last complete record when the block is cut short`() {
        val complete = snoozV2(unixMs + epochDeltaMs, listOf(outgoingAtt(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x01)))))
        // Rebuild with a record that claims more bytes than the block holds.
        val body = ByteArrayOutputStream().apply {
            writeLe16(400)
            writeLe16(400)
            writeLe32(0)
            write(0x21)
            write(byteArrayOf(0x01, 0x02))
        }.toByteArray()
        val deflater = Deflater()
        deflater.setInput(body)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        while (!deflater.finished()) compressed.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        val truncated = complete.copyOfRange(0, 9) + compressed.toByteArray()

        val (result, _) = decode(truncated)

        assertEquals(0, result.records)
        assertTrue(result.warnings.any { it.contains("ends earlier") })
    }

    // ------------------------------------------------------------------ bugreport scanning

    @Test
    fun `extracts the base64 block from a bugreport text`() {
        val snooz = snoozV2(unixMs + epochDeltaMs, listOf(outgoingAtt(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x09)))))
        val encoded = Base64.getEncoder().encodeToString(snooz).chunked(76).joinToString("\n")
        val bugreport = buildString {
            appendLine("========================================================")
            appendLine("== dumpstate: 2024-05-01 00:00:00")
            appendLine("------ BLUETOOTH MANAGER (dumpsys bluetooth_manager) ------")
            appendLine(BtsnoozDecoder.BEGIN_MARKER)
            appendLine(encoded)
            appendLine(BtsnoozDecoder.END_MARKER)
            appendLine("------ NEXT SECTION ------")
        }

        val extracted = BtsnoozDecoder.extract(bugreport.byteInputStream(Charsets.ISO_8859_1))

        assertTrue(extracted!!.contentEquals(snooz))
    }

    @Test
    fun `returns null when a text has no btsnooz block`() {
        assertNull(BtsnoozDecoder.extract("nothing to see here\nanother line\n".byteInputStream()))
    }

    @Test
    fun `keeps a block that the bugreport cut off before the end marker`() {
        val snooz = snoozV2(unixMs + epochDeltaMs, listOf(outgoingAtt(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x09)))))
        val encoded = Base64.getEncoder().encodeToString(snooz)
        val text = "${BtsnoozDecoder.BEGIN_MARKER}\n$encoded\n"

        val extracted = BtsnoozDecoder.extract(text.byteInputStream(Charsets.ISO_8859_1))

        assertTrue(extracted!!.contentEquals(snooz))
    }
}
