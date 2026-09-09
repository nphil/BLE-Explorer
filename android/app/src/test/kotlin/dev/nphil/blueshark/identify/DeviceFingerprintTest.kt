package dev.nphil.blueshark.identify

import dev.nphil.blueshark.model.GattCharacteristicRecord
import dev.nphil.blueshark.model.GattDatabase
import dev.nphil.blueshark.model.GattServiceRecord
import dev.nphil.blueshark.model.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFingerprintTest {

    private val fff0 = "0000fff0-0000-1000-8000-00805f9b34fb"
    private val fff1 = "0000fff1-0000-1000-8000-00805f9b34fb"

    /** The test device: name, advertised service and manufacturer payload as scanned. */
    private val iLedClock = FingerprintInput(
        name = "iLedClock",
        serviceUuids = listOf(fff0),
        manufacturerData = mapOf(12692 to "bcdc070000011000200421".hexToBytes()),
    )

    private fun gatt(vararg services: GattServiceRecord) =
        GattDatabase(capturedAtEpochMs = 1_700_000_000_000L, services = services.toList())

    private fun service(uuid: String, vararg characteristics: GattCharacteristicRecord) =
        GattServiceRecord(uuid = uuid, instanceId = 0, type = "PRIMARY", characteristics = characteristics.toList())

    private fun characteristic(uuid: String, vararg properties: String) = GattCharacteristicRecord(
        uuid = uuid,
        instanceId = 0x52,
        properties = properties.toList(),
        permissions = listOf("WRITE"),
    )

    @Test
    fun `the iLedClock advertisement is a likely CoolLED with its geometry decoded`() {
        val match = DeviceFingerprint.identify(iLedClock).first()

        assertEquals("coolled", match.familyId)
        assertEquals(FamilyConfidence.LIKELY, match.confidence)
        assertEquals("coolled", match.codecId)
        assertEquals(listOf("fff1"), match.commandCharacteristicHints)
        assertEquals("https://github.com/UpDryTwist/coolledx-driver", match.publicDriverUrl)
        assertEquals(
            listOf(
                "advertised name \"iLedClock\" matches CoolLED naming (CoolLED*/iLed*)",
                "advertised service 0000fff0-0000-1000-8000-00805f9b34fb (fff0) is the CoolLED command service",
                "manufacturer 0x3194 data bcdc070000011000200421: 6-byte id bcdc07000001, " +
                    "panel 32x16 px, colour mode 4, firmware 0x21",
            ),
            match.evidence,
        )
    }

    @Test
    fun `fff0 with fff1 in the GATT database makes the CoolLED match certain`() {
        val input = iLedClock.copy(
            gatt = gatt(service(fff0, characteristic(fff1, "WRITE_NO_RESPONSE", "WRITE", "NOTIFY"))),
        )

        val match = DeviceFingerprint.identify(input).first()

        assertEquals("coolled", match.familyId)
        assertEquals(FamilyConfidence.CERTAIN, match.confidence)
        assertEquals(
            "GATT service 0000fff0-0000-1000-8000-00805f9b34fb exposes characteristic " +
                "0000fff1-0000-1000-8000-00805f9b34fb [WRITE_NO_RESPONSE, WRITE, NOTIFY]: " +
                "CoolLED command channel confirmed",
            match.evidence.last(),
        )
    }

    @Test
    fun `a family is never claimed from the GATT database alone`() {
        val input = FingerprintInput(
            gatt = gatt(service(fff0, characteristic(fff1, "WRITE_NO_RESPONSE", "NOTIFY"))),
        )

        val matches = DeviceFingerprint.identify(input)

        assertNull(matches.firstOrNull { it.familyId == "coolled" })
        assertEquals(listOf("command-channel:fff0"), matches.map { it.familyId })
    }

    @Test
    fun `a Govee advertisement is matched on its manufacturer id`() {
        val input = FingerprintInput(
            name = "GVH5075_1234",
            manufacturerData = mapOf(0xEC88 to "0088ec0001010164".hexToBytes()),
        )

        val match = DeviceFingerprint.identify(input).first()

        assertEquals("govee", match.familyId)
        assertEquals(FamilyConfidence.LIKELY, match.confidence)
        assertEquals(
            listOf(
                "manufacturer 0xEC88 data 0088ec0001010164 is Govee's",
                "advertised name \"GVH5075_1234\" matches Govee naming (Govee_*/GVH*/ihoment*)",
            ),
            match.evidence,
        )
    }

    @Test
    fun `a MiBeacon frame is read out of the fe95 service data`() {
        val input = FingerprintInput(
            name = "LYWSD03MMC",
            serviceUuids = listOf("fe95"),
            serviceData = mapOf("fe95" to "5020aa01099e0c4c351b3406100109".hexToBytes()),
        )

        val match = DeviceFingerprint.identify(input).first()

        assertEquals("xiaomi-mibeacon", match.familyId)
        assertEquals(FamilyConfidence.LIKELY, match.confidence)
        assertEquals("https://github.com/Bluetooth-Devices/xiaomi-ble", match.publicDriverUrl)
        assertEquals(
            listOf(
                "service data 0000fe95-0000-1000-8000-00805f9b34fb (fe95) " +
                    "5020aa01099e0c4c351b3406100109 is a MiBeacon frame",
                "MiBeacon frame control 0x2050, product id 0x01aa, counter 9, device 34:1B:35:4C:0C:9E",
            ),
            match.evidence,
        )
    }

    @Test
    fun `an encrypted MiBeacon frame says the preset applies`() {
        val input = FingerprintInput(
            serviceData = mapOf("0000fe95-0000-1000-8000-00805f9b34fb" to "58308b09489e0c4c351b34".hexToBytes()),
        )

        val match = DeviceFingerprint.identify(input).first()

        assertEquals("xiaomi-mibeacon", match.familyId)
        assertEquals(
            "frame control bit 0x08 is set: the payload is encrypted, the MiBeacon AES-CCM preset applies",
            match.evidence.last(),
        )
    }

    @Test
    fun `a write plus notify pair in one service is a possible command channel`() {
        val input = FingerprintInput(
            name = "Unknown widget",
            gatt = gatt(
                service("0000180f-0000-1000-8000-00805f9b34fb", characteristic("00002a19-0000-1000-8000-00805f9b34fb", "READ")),
                service(
                    "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristic("0000ffe1-0000-1000-8000-00805f9b34fb", "WRITE"),
                    characteristic("0000ffe2-0000-1000-8000-00805f9b34fb", "NOTIFY"),
                ),
            ),
        )

        val matches = DeviceFingerprint.identify(input)

        assertEquals(1, matches.size)
        val channel = matches.single()
        assertEquals("command-channel:ffe0", channel.familyId)
        assertEquals(FamilyConfidence.POSSIBLE, channel.confidence)
        assertEquals(listOf("ffe1", "ffe2"), channel.commandCharacteristicHints)
        assertNull(channel.codecId)
        assertEquals(
            "GATT service 0000ffe0-0000-1000-8000-00805f9b34fb: write characteristic " +
                "0000ffe1-0000-1000-8000-00805f9b34fb [WRITE] paired with notify characteristic " +
                "0000ffe2-0000-1000-8000-00805f9b34fb [NOTIFY]",
            channel.evidence.single(),
        )
    }

    @Test
    fun `one characteristic that both writes and notifies is reported as its own channel`() {
        val input = FingerprintInput(
            gatt = gatt(service(fff0, characteristic(fff1, "WRITE_NO_RESPONSE", "WRITE", "NOTIFY"))),
        )

        val channel = DeviceFingerprint.identify(input).single()

        assertEquals(listOf("fff1"), channel.commandCharacteristicHints)
        assertTrue(channel.evidence.single().endsWith("a command channel that answers on itself"))
    }

    @Test
    fun `certain matches sort ahead of the generic heuristic`() {
        val input = iLedClock.copy(
            gatt = gatt(service(fff0, characteristic(fff1, "WRITE_NO_RESPONSE", "NOTIFY"))),
        )

        assertEquals(
            listOf("coolled", "command-channel:fff0"),
            DeviceFingerprint.identify(input).map { it.familyId },
        )
    }

    @Test
    fun `an unremarkable advertisement matches nothing`() {
        val input = FingerprintInput(
            name = "Nordic_Blinky",
            serviceUuids = listOf("0000180a-0000-1000-8000-00805f9b34fb"),
        )

        assertEquals(emptyList<FamilyMatch>(), DeviceFingerprint.identify(input))
    }
}
