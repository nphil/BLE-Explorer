package dev.nphil.blestudio.hci

import dev.nphil.blestudio.model.AttOperation
import dev.nphil.blestudio.model.EventDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HCI, L2CAP signalling and SMP dissection around the ATT traffic. */
class BtsnoopHciTest {

    private fun parse(bytes: ByteArray, parser: BtsnoopParser = BtsnoopParser()) =
        parser.parse(bytes.inputStream())

    private val peer = "AA:BB:CC:DD:EE:FF"

    // ------------------------------------------------------------------ connection lifecycle

    @Test
    fun `connection complete names the peer of a handle and its timing`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer, addressType = 0x01, intervalUnits = 39), sentByHost = false)
            .build()

        val result = parse(bytes)

        val connection = result.summary.connectionsByPeer.getValue(peer)
        assertEquals(listOf(0x0040), connection.handles)
        assertEquals("Random", connection.addressType)
        assertEquals(48.75, connection.intervalMs!!, 0.001)
        assertEquals(5000, connection.supervisionTimeoutMs)

        val banner = result.events.single()
        assertEquals(EventDirection.SYSTEM, banner.direction)
        assertEquals(AttOperation.OTHER, banner.operation)
        assertEquals(0x0040, banner.connectionHandle)
        assertTrue(banner.note, banner.note.contains("connected to $peer (Random) as Central"))
        assertTrue(banner.note, banner.note.contains("interval 48.75 ms"))
        assertTrue(banner.note, banner.note.contains("timeout 5000 ms"))
    }

    @Test
    fun `enhanced connection complete reads the timing past the resolvable addresses`() {
        val bytes = CaptureBuilder()
            .packet(
                Fixtures.connectionComplete(0x0041, peer, intervalUnits = 24, timeoutUnits = 200, enhanced = true),
                sentByHost = false,
            )
            .build()

        val connection = parse(bytes).summary.connectionsByPeer.getValue(peer)

        assertEquals(30.0, connection.intervalMs!!, 0.001)
        assertEquals(2000, connection.supervisionTimeoutMs)
        assertEquals(listOf(0x0041), connection.handles)
    }

    @Test
    fun `a failed connection complete is an error banner and no handle state`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer, status = 0x3E), sentByHost = false)
            .build()

        val result = parse(bytes)

        val banner = result.events.single()
        assertEquals(AttOperation.ERROR, banner.operation)
        assertNull(banner.connectionHandle)
        assertTrue(banner.note, banner.note.contains("Connection Failed to be Established"))
        assertEquals(0, result.summary.connections)
    }

    @Test
    fun `disconnection complete reports the reason in words`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false)
            .packet(Fixtures.disconnectionComplete(0x0040, reason = 0x08), sentByHost = false)
            .build()

        val result = parse(bytes)

        val disconnect = result.events.last()
        assertEquals(AttOperation.ERROR, disconnect.operation)
        assertEquals(0x08, disconnect.status)
        assertTrue(disconnect.note, disconnect.note.contains("Connection Timeout"))
        assertEquals(
            listOf("Connection Timeout"),
            result.summary.connectionsByPeer.getValue(peer).disconnectReasons,
        )
    }

    @Test
    fun `a peer-terminated idle link is an idle sample and a host-terminated one is not`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false)
            .packet(Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x01))))
            .packet(Fixtures.disconnectionComplete(0x0040, reason = 0x13), sentByHost = false, advanceMicros = 4_000_000)
            // Second link: the host asked for the disconnection, so its gap proves nothing.
            .packet(Fixtures.connectionComplete(0x0041, peer), sentByHost = false)
            .packet(Fixtures.att(0x0041, Fixtures.writeCommand(0x0025, byteArrayOf(0x02))))
            .packet(Fixtures.disconnectCommand(0x0041))
            .packet(Fixtures.disconnectionComplete(0x0041, reason = 0x16), sentByHost = false, advanceMicros = 9_000_000)
            .build()

        val connection = parse(bytes).summary.connectionsByPeer.getValue(peer)

        assertEquals(listOf(4_000L), connection.idleDisconnectSamplesMs)
        assertEquals(listOf(0x0040, 0x0041), connection.handles)
    }

    @Test
    fun `encryption change marks the link encrypted`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false)
            .packet(Fixtures.encryptionChange(0x0040, enabled = 0x01), sentByHost = false)
            .build()

        val result = parse(bytes)

        assertTrue(result.summary.connectionsByPeer.getValue(peer).encrypted)
        assertTrue(result.events.last().note.contains("encryption on"))
    }

    @Test
    fun `connection and PHY updates are recorded and announced`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false)
            .packet(Fixtures.connectionUpdateComplete(0x0040, intervalUnits = 24, timeoutUnits = 200), sentByHost = false)
            .packet(Fixtures.phyUpdateComplete(0x0040, txPhy = 0x02, rxPhy = 0x02), sentByHost = false)
            .build()

        val result = parse(bytes)

        val connection = result.summary.connectionsByPeer.getValue(peer)
        assertEquals(30.0, connection.intervalMs!!, 0.001)
        assertEquals(2000, connection.supervisionTimeoutMs)
        assertEquals("LE 2M", connection.txPhy)
        assertEquals("LE 2M", connection.rxPhy)
        assertTrue(result.events.map { it.note }.any { it.contains("PHY now LE 2M tx / LE 2M rx") })
    }

    @Test
    fun `create connection and its completion make one timed attempt`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.leCreateConnection(peer))
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false, advanceMicros = 250_000)
            .build()

        val attempts = parse(bytes).summary.connectionsByPeer.getValue(peer).attempts

        assertEquals(1, attempts.size)
        assertTrue(attempts[0].success)
        assertEquals(250_000L, attempts[0].durationMicros)
        assertNull(attempts[0].error)
    }

    @Test
    fun `a refused create connection is a failed attempt`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.leCreateConnection(peer))
            .packet(Fixtures.commandStatus(0x200D, status = 0x0C), sentByHost = false)
            .build()

        val result = parse(bytes)

        val attempts = result.summary.connectionsByPeer.getValue(peer).attempts
        assertEquals(1, attempts.size)
        assertFalse(attempts[0].success)
        assertEquals("Command Disallowed", attempts[0].error)
        assertTrue(result.events.single().note.contains("LE Create Connection refused: Command Disallowed"))
    }

    // ------------------------------------------------------------------ ATT additions

    @Test
    fun `exchange MTU records the smaller of the two values`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false)
            .packet(Fixtures.att(0x0040, Fixtures.exchangeMtuRequest(517)))
            .packet(Fixtures.att(0x0040, Fixtures.exchangeMtuResponse(185)), sentByHost = false)
            .build()

        val result = parse(bytes)

        assertEquals(185, result.summary.connectionsByPeer.getValue(peer).mtu)
        assertTrue(result.events.last().note.contains("ATT MTU 185 (client 517, server 185)"))
    }

    @Test
    fun `read blob and prepare write keep their attribute handle`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.att(0x0040, Fixtures.readBlobRequest(0x0025, offset = 22)))
            .packet(Fixtures.att(0x0040, Fixtures.readBlobResponse(byteArrayOf(0x41, 0x42))), sentByHost = false)
            .packet(Fixtures.att(0x0040, Fixtures.prepareWriteRequest(0x0027, 4, byteArrayOf(0x09))))
            .packet(Fixtures.att(0x0040, Fixtures.executeWriteRequest()))
            .build()

        val events = parse(bytes).events

        assertEquals(AttOperation.READ_REQUEST, events[0].operation)
        assertEquals(0x0025, events[0].attributeHandle)
        assertTrue(events[0].note.contains("offset 22"))
        assertEquals(AttOperation.READ_RESPONSE, events[1].operation)
        assertEquals(0x0025, events[1].attributeHandle)
        assertEquals("4142", events[1].payloadHex)
        assertEquals(0x0027, events[2].attributeHandle)
        assertEquals("09", events[2].payloadHex)
        assertTrue(events[3].note.contains("execute write"))
    }

    @Test
    fun `a signed write keeps the value and not the signature`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.att(0x0040, Fixtures.signedWriteCommand(0x0025, byteArrayOf(0x07, 0x08))))
            .build()

        val event = parse(bytes).events.single()

        assertEquals(AttOperation.WRITE_COMMAND, event.operation)
        assertEquals(0x0025, event.attributeHandle)
        assertEquals("0708", event.payloadHex)
        assertTrue(event.note.contains("signed write"))
    }

    @Test
    fun `read multiple counts the handles it asked for`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.att(0x0040, Fixtures.readMultipleRequest(0x0025, 0x0027, 0x0029)))
            .build()

        val event = parse(bytes).events.single()

        assertEquals(AttOperation.READ_REQUEST, event.operation)
        assertTrue(event.note, event.note.contains("read 3 handles"))
    }

    // ------------------------------------------------------------------ SMP

    @Test
    fun `pairing request and response decode the features and infer the association model`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false)
            // Both sides: DisplayYesNo, MITM wanted, Secure Connections, bonding.
            .packet(Fixtures.smp(0x0040, Fixtures.pairingRequest(io = 0x01, authReq = 0x0D)))
            .packet(
                Fixtures.smp(0x0040, Fixtures.pairingRequest(io = 0x01, authReq = 0x0D, response = true)),
                sentByHost = false,
            )
            .build()

        val result = parse(bytes)

        val request = result.events[1]
        assertEquals(EventDirection.PHONE_TO_DEVICE, request.direction)
        assertTrue(request.note, request.note.contains("IO DisplayYesNo"))
        assertTrue(request.note, request.note.contains("bonding+MITM+Secure Connections"))
        assertTrue(request.note, request.note.contains("max key 16"))
        assertTrue(request.note, request.note.contains("keys LTK+IRK+CSRK/LTK+IRK+CSRK"))
        // The feature exchange itself is evidence, so its bytes are kept verbatim.
        assertEquals("0101000D100707", request.payloadHex)

        val connection = result.summary.connectionsByPeer.getValue(peer)
        assertEquals(PairingMethod.NUMERIC_COMPARISON, connection.pairingMethod)
        assertTrue(connection.bonded)
        assertTrue(connection.pairingSeen)
        assertEquals(1, result.summary.smpPdus["Pairing Request"])
        assertEquals(1, result.summary.smpPdus["Pairing Response"])
    }

    @Test
    fun `just works pairing is inferred when neither side wants MITM protection`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false)
            .packet(Fixtures.smp(0x0040, Fixtures.pairingRequest(io = 0x04, authReq = 0x09)))
            .packet(
                Fixtures.smp(0x0040, Fixtures.pairingRequest(io = 0x01, authReq = 0x09, response = true)),
                sentByHost = false,
            )
            .build()

        val connection = parse(bytes).summary.connectionsByPeer.getValue(peer)

        assertEquals(PairingMethod.JUST_WORKS, connection.pairingMethod)
    }

    @Test
    fun `passkey entry is inferred from a keyboard and a display`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false)
            .packet(Fixtures.smp(0x0040, Fixtures.pairingRequest(io = 0x02, authReq = 0x05)))
            .packet(
                Fixtures.smp(0x0040, Fixtures.pairingRequest(io = 0x00, authReq = 0x05, response = true)),
                sentByHost = false,
            )
            .build()

        val connection = parse(bytes).summary.connectionsByPeer.getValue(peer)

        assertEquals(PairingMethod.PASSKEY_ENTRY, connection.pairingMethod)
    }

    @Test
    fun `key material never reaches an event`() {
        val key = ByteArray(16) { 0x5A }
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false)
            .packet(Fixtures.smp(0x0040, Fixtures.encryptionInformation(key)), sentByHost = false)
            .build()

        val result = parse(bytes)

        val distribution = result.events.last()
        assertEquals("", distribution.payloadHex)
        assertTrue(distribution.note, distribution.note.contains("[redacted key material] (16 bytes)"))
        assertTrue(result.events.none { it.payloadHex.contains("5A5A") })
        assertEquals(1, result.summary.smpPdus["Encryption Information"])
    }

    @Test
    fun `pairing failed is an error with the spec's reason`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false)
            .packet(Fixtures.smp(0x0040, Fixtures.pairingFailed(0x03)), sentByHost = false)
            .build()

        val failure = parse(bytes).events.last()

        assertEquals(AttOperation.ERROR, failure.operation)
        assertEquals(0x03, failure.status)
        assertEquals("pairing failed: Authentication Requirements", failure.note)
    }

    @Test
    fun `a security request counts as pairing seen`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false)
            .packet(Fixtures.smp(0x0040, Fixtures.securityRequest()), sentByHost = false)
            .build()

        val result = parse(bytes)

        assertTrue(result.summary.connectionsByPeer.getValue(peer).pairingSeen)
        assertTrue(result.events.last().note.contains("Security Request"))
    }

    // ------------------------------------------------------------------ L2CAP signalling

    @Test
    fun `a connection parameter update request is announced with its window`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false)
            .packet(Fixtures.signaling(0x0040, Fixtures.connectionParameterUpdateRequest()), sentByHost = false)
            .packet(Fixtures.signaling(0x0040, Fixtures.connectionParameterUpdateResponse()))
            .build()

        val result = parse(bytes)

        val request = result.events[1]
        assertTrue(request.note, request.note.contains("interval 30 ms to 50 ms"))
        assertTrue(request.note, request.note.contains("timeout 5000 ms"))
        assertTrue(result.events.last().note.contains("connection parameter update accepted"))
        assertEquals(1, result.summary.l2capSignals["Connection Parameter Update Request"])
    }

    @Test
    fun `an EATT channel is recorded as a credit-based channel`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, peer), sentByHost = false)
            .packet(Fixtures.signaling(0x0040, Fixtures.leCreditBasedConnectionRequest(spsm = 0x001F)))
            .build()

        val result = parse(bytes)

        assertTrue(result.summary.connectionsByPeer.getValue(peer).creditBasedChannels)
        assertEquals(1, result.summary.l2capSignals["LE Credit Based Connection Request via EATT"])
    }

    // ------------------------------------------------------------------ advertising

    @Test
    fun `advertising reports aggregate per advertiser`() {
        val data = Fixtures.adName("Gadget") +
            Fixtures.adServiceUuids16(0xFA00) +
            Fixtures.adManufacturer(0x004C, byteArrayOf(0x02, 0x15))
        val bytes = CaptureBuilder()
            .packet(Fixtures.advertisingReport(peer, rssi = -70, data = data), sentByHost = false)
            .packet(Fixtures.advertisingReport(peer, rssi = -66, data = data), sentByHost = false)
            .packet(Fixtures.extendedAdvertisingReport(peer, rssi = -61, data = data), sentByHost = false)
            .build()

        val result = parse(bytes)

        val advertiser = result.summary.advertisers.getValue(peer)
        assertEquals(3, advertiser.count)
        assertEquals(-61, advertiser.lastRssi)
        assertEquals(setOf("Gadget"), advertiser.names)
        assertEquals(setOf("0000fa00-0000-1000-8000-00805f9b34fb"), advertiser.serviceUuids)
        assertEquals(setOf(0x004C), advertiser.manufacturerIds)
        assertEquals("0215", advertiser.manufacturerData[0x004C])
        // Advertising is aggregated, never turned into timeline rows.
        assertTrue(result.events.isEmpty())
    }

    // ------------------------------------------------------------------ honesty about the rest

    @Test
    fun `recognised but undecoded records are counted, not called unsupported`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.numberOfCompletedPackets(0x0040), sentByHost = false)
            .packet(Fixtures.commandComplete(0x1009), sentByHost = false)
            .packet(Fixtures.hciCommand(0x0C03), sentByHost = false)
            .build()

        val result = parse(bytes)

        assertEquals(3, result.summary.countedOnly)
        assertEquals(0, result.summary.unsupported)
        assertEquals(1, result.summary.hciEvents["Number Of Completed Packets"])
        assertEquals(1, result.summary.hciCommands["Reset"])
    }

    @Test
    fun `a refused command is counted under its own key`() {
        val bytes = CaptureBuilder()
            .packet(Fixtures.commandComplete(0x2008, status = 0x12), sentByHost = false)
            .build()

        val commands = parse(bytes).summary.hciCommands

        assertEquals(1, commands["LE Set Advertising Data refused: Invalid HCI Command Parameters"])
    }

    @Test
    fun `an event whose parameters do not fit is unsupported`() {
        val bytes = CaptureBuilder()
            // Disconnection Complete promising four parameter bytes and carrying two.
            .packet(byteArrayOf(0x04, 0x05, 0x04, 0x00, 0x40), sentByHost = false)
            .build()

        val result = parse(bytes)

        assertEquals(1, result.summary.unsupported)
        assertTrue(result.events.isEmpty())
    }

    // ------------------------------------------------------------------ raw packet retention

    @Test
    fun `raw packets are retained only when asked for and keyed by event id`() {
        val packet = Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x01, 0x02)))
        val bytes = CaptureBuilder().packet(packet).build()

        val without = parse(bytes)
        val with = parse(bytes, BtsnoopParser(rawPackets = true))

        assertTrue(without.rawHex.isEmpty())
        val event = with.events.single()
        assertEquals(packet.joinToString("") { "%02X".format(it) }, with.rawHex.getValue(event.id))
    }

    @Test
    fun `a retained raw packet is capped`() {
        val value = ByteArray(600) { 0x33 }
        val bytes = CaptureBuilder()
            .packet(Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, value)))
            .build()

        val result = parse(bytes, BtsnoopParser(rawPackets = true))

        assertEquals(MAX_RAW_PACKET_BYTES * 2, result.rawHex.values.single().length)
    }

    // ------------------------------------------------------------------ table coverage

    /**
     * A disconnect reason or command status that falls through to its own number tells an operator
     * nothing, and a missing row is invisible until a device hits it. Every code Vol 1 Part F,
     * Section 1.3 assigns has to be named; the three it reserves (0x2B, 0x31, 0x33) do not.
     */
    @Test
    fun `every assigned HCI status code has a name`() {
        val reserved = setOf(0x2B, 0x31, 0x33)
        val unnamed = (0x00..0x48)
            .filterNot { it in reserved }
            .filter { HciNames.statusText(it) == "Status 0x%02X".format(it) }

        assertTrue("unnamed HCI status codes: ${unnamed.map { "0x%02X".format(it) }}", unnamed.isEmpty())
    }

    /** Same for the SMP failure reasons of Vol 3 Part H, Section 3.5.5, Table 3.7. */
    @Test
    fun `every SMP failure reason has a name`() {
        val unnamed = (0x01..0x0F).filter {
            HciNames.smpFailureReason(it) == "Reason 0x%02X".format(it)
        }

        assertTrue("unnamed SMP reasons: ${unnamed.map { "0x%02X".format(it) }}", unnamed.isEmpty())
    }
}
