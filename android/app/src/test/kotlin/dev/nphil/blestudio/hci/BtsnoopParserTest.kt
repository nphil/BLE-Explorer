package dev.nphil.blestudio.hci

import dev.nphil.blestudio.model.AttOperation
import dev.nphil.blestudio.model.EventDirection
import dev.nphil.blestudio.model.EventSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BtsnoopParserTest {

    private fun parse(bytes: ByteArray, parser: BtsnoopParser = BtsnoopParser()) =
        parser.parse(bytes.inputStream())

    // ------------------------------------------------------------------ header validation

    @Test
    fun `rejects a stream that is not btsnoop`() {
        val bytes = CaptureBuilder(magic = "notsnoop".toByteArray(Charsets.ISO_8859_1)).build()
        val error = assertThrows(BtsnoopFormatException::class.java) { parse(bytes) }
        assertTrue(error.message!!.contains("bad magic"))
    }

    @Test
    fun `rejects an unknown btsnoop version`() {
        val bytes = CaptureBuilder(version = 2).build()
        val error = assertThrows(BtsnoopFormatException::class.java) { parse(bytes) }
        assertTrue(error.message!!.contains("version 2"))
    }

    @Test
    fun `rejects a datalink that is not H4`() {
        val bytes = CaptureBuilder(datalink = 1001).build()
        val error = assertThrows(BtsnoopFormatException::class.java) { parse(bytes) }
        assertTrue(error.message!!.contains("1001"))
    }

    @Test
    fun `rejects a file shorter than the header`() {
        assertThrows(BtsnoopFormatException::class.java) { parse(byteArrayOf(0x62, 0x74, 0x73)) }
    }

    // ------------------------------------------------------------------ record framing

    @Test
    fun `keeps complete events and warns when the stream stops mid record`() {
        val complete = CaptureBuilder()
            .packet(Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x01, 0x02))))
            .build()
        // A second record header that promises 40 bytes of packet but delivers 4.
        val truncated = complete + Fixtures.record(ByteArray(40) { 0x02 }, 0).copyOfRange(0, 28)

        val result = parse(truncated)

        assertEquals(1, result.events.size)
        assertEquals(1, result.summary.records)
        assertTrue(result.summary.warnings.any { it.contains("Truncated record payload") })
    }

    @Test
    fun `converts btsnoop timestamps to unix microseconds`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x7F))), advanceMicros = 5_000)
            .build()

        val event = parse(bytes).events.single()

        assertEquals(CaptureBuilder.BASE_MICROS + 5_000, event.timestampEpochMicros)
    }

    @Test
    fun `records the capture's own truncation of a record`() {
        val pdu = Fixtures.writeCommand(0x0025, byteArrayOf(0x01, 0x02, 0x03))
        val packet = Fixtures.att(0x0040, pdu)
        val bytes = CaptureBuilder().truncatedPacket(packet, originalLength = packet.size + 24).build()

        val result = parse(bytes)

        assertEquals(1, result.summary.truncated)
        assertTrue(result.events.single().note.contains("capture kept"))
    }

    // ------------------------------------------------------------------ reassembly

    @Test
    fun `reassembles a write command split across ACL fragments`() {
        val value = ByteArray(24) { (it + 1).toByte() }
        val pdu = Fixtures.writeCommand(0x0025, value)
        val l2cap = Fixtures.l2cap(0x0004, pdu)
        val split = 9
        val bytes = CaptureBuilder()
            .packet(Fixtures.acl(0x0040, Fixtures.PB_START, l2cap.copyOfRange(0, split)))
            .packet(Fixtures.acl(0x0040, Fixtures.PB_CONTINUATION, l2cap.copyOfRange(split, l2cap.size)))
            .build()

        val result = parse(bytes)

        val event = result.events.single()
        assertEquals(AttOperation.WRITE_COMMAND, event.operation)
        assertEquals(0x0025, event.attributeHandle)
        assertEquals(EventDirection.PHONE_TO_DEVICE, event.direction)
        assertEquals(EventSource.HCI_SNOOP, event.source)
        assertEquals(value.joinToString("") { "%02X".format(it) }, event.payloadHex)
        assertEquals(2, result.summary.records)
    }

    @Test
    fun `ignores a continuation fragment with no start`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.acl(0x0040, Fixtures.PB_CONTINUATION, byteArrayOf(0x11, 0x22, 0x33)))
            .build()

        val result = parse(bytes)

        assertTrue(result.events.isEmpty())
        assertTrue(result.summary.warnings.any { it.contains("continuation") })
    }

    @Test
    fun `reports the peripheral direction for notifications`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.att(0x0040, Fixtures.notification(0x0028, byteArrayOf(0x0A, 0x0B))), sentByHost = false)
            .build()

        val event = parse(bytes).events.single()

        assertEquals(AttOperation.NOTIFICATION, event.operation)
        assertEquals(EventDirection.DEVICE_TO_PHONE, event.direction)
        assertEquals("0A0B", event.payloadHex)
    }

    // ------------------------------------------------------------------ UUID resolution

    @Test
    fun `resolves service and characteristic UUIDs discovered earlier in the connection`() {
        val vendorService = 0x00FA
        val vendorCharacteristic = Fixtures.uuid128("0000fa02-0000-1000-8000-00805f9b34fb")
        val bytes = CaptureBuilder()
            .packet(Fixtures.att(0x0040, Fixtures.readByGroupTypeRequest(0x0001, 0xFFFF)))
            .packet(
                Fixtures.att(0x0040, Fixtures.readByGroupTypeResponse(listOf(Triple(0x0020, 0x002F, vendorService)))),
                sentByHost = false,
            )
            .packet(Fixtures.att(0x0040, Fixtures.readByTypeRequest(0x0020, 0x002F)))
            .packet(
                Fixtures.att(0x0040, Fixtures.characteristicDeclaration128(0x0024, 0x0025, vendorCharacteristic)),
                sentByHost = false,
            )
            .packet(Fixtures.att(0x0040, Fixtures.findInformationRequest(0x0026, 0x002F)))
            .packet(
                Fixtures.att(0x0040, Fixtures.findInformationResponse(listOf(0x0026 to 0x2902))),
                sentByHost = false,
            )
            .packet(Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x03, 0x01))))
            .packet(Fixtures.att(0x0040, Fixtures.writeRequest(0x0026, byteArrayOf(0x01, 0x00))))
            .build()

        val result = parse(bytes)

        val vendorWrite = result.events.single { it.operation == AttOperation.WRITE_COMMAND }
        assertEquals("0000fa02-0000-1000-8000-00805f9b34fb", vendorWrite.characteristicUuid)
        assertEquals("000000fa-0000-1000-8000-00805f9b34fb", vendorWrite.serviceUuid)
        assertEquals("0301", vendorWrite.payloadHex)

        val cccdWrite = result.events.single { it.operation == AttOperation.WRITE_REQUEST }
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", cccdWrite.characteristicUuid)
        assertEquals(1, result.summary.connections)
    }

    @Test
    fun `resolves 16-bit characteristic declarations`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.att(0x0040, Fixtures.readByTypeRequest(0x0001, 0xFFFF)))
            .packet(
                Fixtures.att(0x0040, Fixtures.characteristicDeclarations(listOf(Triple(0x0010, 0x0011, 0x2A19)))),
                sentByHost = false,
            )
            .packet(Fixtures.att(0x0040, Fixtures.notification(0x0011, byteArrayOf(0x64))), sentByHost = false)
            .build()

        val notification = parse(bytes).events.last()

        assertEquals("00002a19-0000-1000-8000-00805f9b34fb", notification.characteristicUuid)
        assertNull(notification.serviceUuid)
    }

    @Test
    fun `does not carry UUIDs across a reused connection handle`() {
        val handle = 0x0040
        val bytes = CaptureBuilder()
            .packet(Fixtures.att(handle, Fixtures.readByTypeRequest(0x0001, 0xFFFF)))
            .packet(
                Fixtures.att(handle, Fixtures.characteristicDeclarations(listOf(Triple(0x0024, 0x0025, 0x2A19)))),
                sentByHost = false,
            )
            .packet(Fixtures.att(handle, Fixtures.writeCommand(0x0025, byteArrayOf(0x01))))
            .packet(Fixtures.disconnectionComplete(handle), sentByHost = false)
            .packet(Fixtures.att(handle, Fixtures.writeCommand(0x0025, byteArrayOf(0x02))))
            .build()

        val result = parse(bytes)

        val writes = result.events.filter { it.operation == AttOperation.WRITE_COMMAND }
        assertEquals(2, writes.size)
        assertNotNull(writes[0].characteristicUuid)
        assertNull("a reused handle must not inherit the old database", writes[1].characteristicUuid)
        assertEquals(2, result.summary.connections)
    }

    @Test
    fun `keeps separate databases for concurrent connections`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.att(0x0040, Fixtures.readByTypeRequest(0x0001, 0xFFFF)))
            .packet(
                Fixtures.att(0x0040, Fixtures.characteristicDeclarations(listOf(Triple(0x0024, 0x0025, 0x2A19)))),
                sentByHost = false,
            )
            .packet(Fixtures.att(0x0041, Fixtures.writeCommand(0x0025, byteArrayOf(0x09))))
            .build()

        val result = parse(bytes)

        assertNull(result.events.last().characteristicUuid)
        assertEquals(2, result.summary.connections)
    }

    // ------------------------------------------------------------------ error and response correlation

    @Test
    fun `correlates a read response with the handle of its request`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.att(0x0040, byteArrayOf(0x0A, 0x25, 0x00)))
            .packet(Fixtures.att(0x0040, byteArrayOf(0x0B, 0x41, 0x42)), sentByHost = false)
            .build()

        val response = parse(bytes).events.last()

        assertEquals(AttOperation.READ_RESPONSE, response.operation)
        assertEquals(0x0025, response.attributeHandle)
        assertEquals("4142", response.payloadHex)
    }

    @Test
    fun `decodes an error response with its status`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.att(0x0040, byteArrayOf(0x01, 0x12, 0x25, 0x00, 0x05)), sentByHost = false)
            .build()

        val event = parse(bytes).events.single()

        assertEquals(AttOperation.ERROR, event.operation)
        assertEquals(0x05, event.status)
        assertEquals(0x0025, event.attributeHandle)
        assertTrue(event.note.contains("insufficient authentication"))
    }

    // ------------------------------------------------------------------ hard limits

    @Test
    fun `stops at the event cap and says so`() {
        val builder = CaptureBuilder()
        repeat(10) { index ->
            builder.packet(Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(index.toByte()))))
        }

        val result = parse(builder.build(), BtsnoopParser(maxEvents = 3))

        assertEquals(3, result.events.size)
        assertTrue(result.summary.warnings.any { it.contains("Event cap of 3") })
    }

    @Test
    fun `retains only the configured payload length`() {
        val value = ByteArray(20) { 0x55 }
        val bytes = CaptureBuilder()
            .packet(Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, value)))
            .build()

        val result = parse(bytes, BtsnoopParser(maxPayloadBytes = 8))

        val event = result.events.single()
        assertEquals(16, event.payloadHex.length)
        assertEquals(1, result.summary.truncated)
        assertTrue(event.note.contains("truncated from 20 bytes"))
    }

    @Test
    fun `stops at the input cap`() {
        val builder = CaptureBuilder()
        repeat(50) { builder.packet(Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, ByteArray(32)))) }

        val result = parse(builder.build(), BtsnoopParser(maxInputBytes = 200))

        assertTrue(result.events.size < 50)
        assertTrue(result.summary.warnings.any { it.contains("Input cap") })
    }

    @Test
    fun `counts non ATT traffic as unsupported instead of dropping it silently`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.acl(0x0040, Fixtures.PB_START, Fixtures.l2cap(0x0006, byteArrayOf(0x01, 0x02))))
            .packet(byteArrayOf(0x01, 0x0D, 0x20, 0x02, 0x00, 0x00))
            .build()

        val result = parse(bytes)

        assertTrue(result.events.isEmpty())
        assertEquals(2, result.summary.unsupported)
        assertEquals(2, result.summary.records)
    }
}
