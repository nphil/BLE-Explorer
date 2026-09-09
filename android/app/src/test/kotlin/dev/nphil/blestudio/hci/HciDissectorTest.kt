package dev.nphil.blestudio.hci

import dev.nphil.blestudio.model.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-packet dissection: the tree an operator reads in the inspector. */
class HciDissectorTest {

    private fun DissectionNode.child(label: String): DissectionNode =
        children.firstOrNull { it.label == label }
            ?: throw AssertionError("no child \"$label\" under \"${this.label}\", have ${children.map { it.label }}")

    private fun DissectionNode.value(label: String): String? = child(label).value

    @Test
    fun `an ATT write command dissects down to its value`() {
        val packet = Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x01, 0x02)))

        val root = HciDissector.dissect(packet)

        assertEquals("H4", root.label)
        assertEquals("HCI ACL Data (0x02)", root.value)

        val acl = root.children.single()
        assertEquals("HCI ACL Data", acl.label)
        assertEquals("0x0040", acl.value("Connection handle"))
        assertEquals("0x02 (First automatically-flushable)", acl.value("PB flag"))
        assertEquals("0x00 (Point-to-point)", acl.value("BC flag"))
        assertEquals("9", acl.value("Data total length"))

        val l2cap = acl.child("L2CAP")
        assertEquals("5", l2cap.value("Length"))
        assertEquals("0x0004 (ATT)", l2cap.value("CID"))

        val att = l2cap.child("ATT")
        assertEquals("Write Command", att.value)
        assertEquals("0x52 (Write Command)", att.value("Opcode"))
        assertEquals("0x0025", att.value("Attribute handle"))
        assertEquals("0102 (2 bytes)", att.value("Attribute value"))
    }

    @Test
    fun `an LE connection complete dissects every parameter`() {
        val packet = Fixtures.connectionComplete(
            connectionHandle = 0x0040,
            address = "AA:BB:CC:DD:EE:FF",
            addressType = 0x01,
            intervalUnits = 39,
            latency = 0,
            timeoutUnits = 500,
        )

        val root = HciDissector.dissect(packet)

        assertEquals("HCI Event (0x04)", root.value)
        val event = root.children.single()
        assertEquals("0x3E (LE Meta)", event.value("Event code"))
        assertEquals("19", event.value("Parameter total length"))

        val meta = event.child("LE Meta")
        assertEquals("LE Connection Complete", meta.value)
        assertEquals("0x01 (LE Connection Complete)", meta.value("Subevent code"))
        assertEquals("0x00 (Success)", meta.value("Status"))
        assertEquals("0x0040", meta.value("Connection handle"))
        assertEquals("0x00 (Central)", meta.value("Role"))
        assertEquals("0x01 (Random)", meta.value("Peer address type"))
        assertEquals("AA:BB:CC:DD:EE:FF", meta.value("Peer address"))
        assertEquals("48.75 ms", meta.value("Connection interval"))
        assertEquals("0", meta.value("Peripheral latency"))
        assertEquals("5000 ms", meta.value("Supervision timeout"))
    }

    @Test
    fun `a known handle is labelled with its peer address`() {
        val capture = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, "AA:BB:CC:DD:EE:FF"), sentByHost = false)
            .build()
        val summary = BtsnoopParser().parse(capture.inputStream()).summary

        val root = HciDissector.dissect(
            Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x01))),
            dissectionContextOf(summary),
        )

        assertEquals(
            "0x0040 (AA:BB:CC:DD:EE:FF)",
            root.children.single().value("Connection handle"),
        )
    }

    /**
     * The path the inspector actually takes: the parser retains the H4 bytes of a record, and the
     * view model dissects the packet behind the selected event.
     */
    @Test
    fun `a retained raw packet dissects back to the event it produced`() {
        val capture = CaptureBuilder()
            .packet(Fixtures.connectionComplete(0x0040, "AA:BB:CC:DD:EE:FF"), sentByHost = false)
            .packet(Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x0A, 0x0B))))
            .build()
        val parsed = BtsnoopParser(rawPackets = true).parse(capture.inputStream())

        val write = parsed.events.last { it.attributeHandle == 0x0025 }
        val raw = parsed.rawHex.getValue(write.id)
        val root = HciDissector.dissect(raw.hexToBytes(), dissectionContextOf(parsed.summary))

        val att = root.children.single().child("L2CAP").child("ATT")
        assertEquals("Write Command", att.value)
        assertEquals("0x0025", att.value("Attribute handle"))
        assertEquals("0A0B (2 bytes)", att.value("Attribute value"))
        assertEquals(
            "0x0040 (AA:BB:CC:DD:EE:FF)",
            root.children.single().value("Connection handle"),
        )
    }

    @Test
    fun `an attribute handle shows the UUID the parser learned`() {
        val root = HciDissector.dissect(
            Fixtures.att(0x0040, Fixtures.notification(0x0028, byteArrayOf(0x0A))),
            DissectionContext(uuidByHandle = mapOf(0x0028 to "00002a19-0000-1000-8000-00805f9b34fb")),
        )

        val att = root.children.single().child("L2CAP").child("ATT")
        assertEquals("0x0028 (00002a19-0000-1000-8000-00805f9b34fb)", att.value("Attribute handle"))
    }

    @Test
    fun `SMP pairing features are named and key material is never shown`() {
        val request = HciDissector.dissect(
            Fixtures.smp(0x0040, Fixtures.pairingRequest(io = 0x01, authReq = 0x0D)),
        ).children.single().child("L2CAP").child("SMP")

        assertEquals("Pairing Request", request.value)
        assertEquals("0x01 (DisplayYesNo)", request.value("IO capability"))
        assertEquals("0x00 (OOB data not present)", request.value("OOB data flag"))
        assertEquals("0x0D (bonding+MITM+Secure Connections)", request.value("AuthReq"))
        assertEquals("16", request.value("Maximum encryption key size"))
        assertEquals("0x07 (LTK+IRK+CSRK)", request.value("Initiator key distribution"))

        val distribution = HciDissector.dissect(
            Fixtures.smp(0x0040, Fixtures.encryptionInformation(ByteArray(16) { 0x5A })),
        ).children.single().child("L2CAP").child("SMP")

        assertEquals("Encryption Information", distribution.value)
        assertEquals("[redacted key material] (16 bytes)", distribution.value("Value"))
    }

    @Test
    fun `an L2CAP parameter update request shows the window it asked for`() {
        val signalling = HciDissector.dissect(
            Fixtures.signaling(0x0040, Fixtures.connectionParameterUpdateRequest()),
        ).children.single().child("L2CAP").child("L2CAP Signalling")

        assertEquals("Connection Parameter Update Request", signalling.value)
        assertEquals("30 ms", signalling.value("Interval min"))
        assertEquals("50 ms", signalling.value("Interval max"))
        assertEquals("5000 ms", signalling.value("Timeout multiplier"))
    }

    @Test
    fun `a create connection command shows the peer it dialled and hides the long term key`() {
        val command = HciDissector.dissect(Fixtures.leCreateConnection("11:22:33:44:55:66")).children.single()

        assertEquals("LE Create Connection", command.value)
        assertEquals("0x200D (LE Create Connection, OGF 0x08, OCF 0x00D)", command.value("Opcode"))
        assertEquals("0x00 (Use peer address)", command.value("Initiator filter policy"))
        assertEquals("11:22:33:44:55:66", command.value("Peer address"))
        assertEquals("30 ms", command.value("Connection interval min"))

        val encryption = HciDissector.dissect(
            Fixtures.hciCommand(0x2019, ByteArray(28) { 0x77 }),
        ).children.single()
        assertEquals("[redacted key material] (16 bytes)", encryption.value("Long term key"))
    }

    @Test
    fun `advertising data is broken into its AD structures`() {
        val data = Fixtures.adName("Gadget") +
            Fixtures.adServiceUuids16(0xFA00) +
            Fixtures.adManufacturer(0x004C, byteArrayOf(0x02, 0x15))

        val meta = HciDissector.dissect(Fixtures.advertisingReport("AA:BB:CC:DD:EE:FF", rssi = -70, data = data))
            .children.single()
            .child("LE Meta")

        assertEquals("-70 dBm", meta.value("RSSI"))
        val advertisingData = meta.child("Advertising data")
        assertEquals("Gadget", advertisingData.value("Complete Local Name"))
        assertEquals("0xFA00", advertisingData.value("Complete List of 16-bit Service UUIDs"))
        assertEquals("company 0x004C, 0215 (2 bytes)", advertisingData.value("Manufacturer Specific Data"))
    }

    @Test
    fun `a truncated packet says so instead of throwing`() {
        val root = HciDissector.dissect(byteArrayOf(0x04, 0x3E, 0x13, 0x01, 0x00))

        val event = root.children.single()
        assertNotNull(event.child("[truncated]").value)
        assertTrue(event.child("[truncated]").value!!.contains("of 19 parameter bytes"))
    }

    @Test
    fun `an empty packet is reported, not decoded`() {
        assertEquals("[empty packet]", HciDissector.dissect(ByteArray(0)).value)
    }
}
