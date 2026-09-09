package dev.nphil.blueshark.export

import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.CommandSpec
import dev.nphil.blueshark.model.DeviceIdentity
import dev.nphil.blueshark.model.EvidenceStage
import dev.nphil.blueshark.model.WriteType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class HaProfileBuilderTest {
    private val utc = ZoneId.of("UTC")

    // 2023-11-14T22:13:20Z, so the generated notes date is deterministic.
    private val capturedAt = 1_700_000_000_000L

    private fun command(
        id: String = "c1",
        name: String = "Power on",
        service: String = "0000fff0-0000-1000-8000-00805f9b34fb",
        characteristic: String = "0000fff1-0000-1000-8000-00805f9b34fb",
        payload: String = "A5011E",
        writeType: WriteType = WriteType.WITH_RESPONSE,
        stage: EvidenceStage = EvidenceStage.DEVICE_TESTED,
        notes: String = "Held for two seconds, lamp came on",
        synthetic: Boolean = false,
        observedCount: Int = 1,
        replayCount: Int = 0,
    ) = CommandSpec(
        id = id,
        name = name,
        serviceUuid = service,
        characteristicUuid = characteristic,
        payloadHex = payload,
        writeType = writeType,
        stage = stage,
        observedCount = observedCount,
        successfulReplayCount = replayCount,
        notes = notes,
        synthetic = synthetic,
    )

    private fun session(
        commands: List<CommandSpec>,
        address: String = "AA:BB:CC:DD:EE:FF",
        name: String? = "Desk lamp",
    ) = CaptureSession(
        id = "session",
        name = "Desk lamp capture",
        createdAtEpochMs = capturedAt,
        updatedAtEpochMs = capturedAt,
        device = DeviceIdentity(address = address, name = name),
        commands = commands,
    )

    @Test
    fun `emits exactly the field set the integration accepts`() {
        val profile = HaProfileBuilder.build(session(listOf(command())), utc).getOrThrow()
        val root = Json.parseToJsonElement(HaProfileBuilder.encode(profile)).jsonObject

        assertEquals(setOf("schema_version", "device", "synthetic", "commands"), root.keys)
        assertEquals(setOf("name", "address"), root.getValue("device").jsonObject.keys)
        assertEquals(
            setOf("id", "name", "service", "characteristic", "value", "response", "stage", "notes", "synthetic"),
            root.getValue("commands").jsonArray.single().jsonObject.keys,
        )
    }

    @Test
    fun `payload is emitted as lowercase whole bytes and stage is always tested`() {
        val profile = HaProfileBuilder.build(session(listOf(command(payload = "A5 01 1E"))), utc).getOrThrow()
        val shipped = profile.commands.single()

        assertEquals("a5011e", shipped.value)
        assertEquals("tested", shipped.stage)
        assertFalse(shipped.synthetic)
        assertEquals(2, profile.schema_version)
    }

    @Test
    fun `excludes commands that are not device tested`() {
        val report = HaProfileBuilder.evaluate(
            session(listOf(command(stage = EvidenceStage.HYPOTHESIS))),
            utc,
        )

        assertTrue(report.included.isEmpty())
        assertEquals(
            listOf(ExclusionReason.NOT_DEVICE_TESTED),
            report.excluded.single().problems.map { it.reason },
        )
        assertTrue(report.blockers.contains(ProfileBlocker.NoEligibleCommands))
        assertFalse(report.exportable)
    }

    @Test
    fun `excludes synthetic commands and reports every reason at once`() {
        val report = HaProfileBuilder.evaluate(
            session(listOf(command(stage = EvidenceStage.OBSERVED, synthetic = true))),
            utc,
        )

        assertEquals(
            listOf(ExclusionReason.NOT_DEVICE_TESTED, ExclusionReason.SYNTHETIC),
            report.excluded.single().problems.map { it.reason },
        )
    }

    @Test
    fun `excludes signed writes and maps the response flag from the write type`() {
        val report = HaProfileBuilder.evaluate(
            session(
                listOf(
                    command(id = "signed", name = "Signed", writeType = WriteType.SIGNED),
                    command(id = "ack", name = "Acked", writeType = WriteType.WITH_RESPONSE),
                    command(id = "unack", name = "Unacked", writeType = WriteType.WITHOUT_RESPONSE),
                ),
            ),
            utc,
        )

        assertEquals(
            listOf(ExclusionReason.SIGNED_WRITE),
            report.excluded.single().problems.map { it.reason },
        )
        assertEquals(listOf(true, false), report.included.map { it.response })
    }

    @Test
    fun `excludes commands whose uuids are not uuids`() {
        val report = HaProfileBuilder.evaluate(
            session(listOf(command(service = "not-a-uuid", characteristic = "FFF"))),
            utc,
        )

        assertEquals(
            listOf(ExclusionReason.INVALID_SERVICE_UUID, ExclusionReason.INVALID_CHARACTERISTIC_UUID),
            report.excluded.single().problems.map { it.reason },
        )
    }

    @Test
    fun `excludes empty malformed and oversize payloads`() {
        val report = HaProfileBuilder.evaluate(
            session(
                listOf(
                    command(id = "empty", name = "Empty", payload = ""),
                    command(id = "odd", name = "Odd", payload = "A5F"),
                    command(id = "big", name = "Big", payload = "AB".repeat(513)),
                    command(id = "edge", name = "Edge", payload = "AB".repeat(512)),
                ),
            ),
            utc,
        )

        assertEquals(
            mapOf(
                "Empty" to listOf(ExclusionReason.EMPTY_PAYLOAD),
                "Odd" to listOf(ExclusionReason.MALFORMED_PAYLOAD),
                "Big" to listOf(ExclusionReason.PAYLOAD_TOO_LARGE),
            ),
            report.excluded.associate { it.name to it.problems.map { problem -> problem.reason } },
        )
        assertEquals(listOf("Edge"), report.included.map { it.name })
    }

    @Test
    fun `normalizes 16 bit and undashed uuid shorthand against the base uuid`() {
        val profile = HaProfileBuilder.build(
            session(listOf(command(service = "FFF0", characteristic = "0000fff10000100080000805f9b34fb1"))),
            utc,
        ).getOrThrow()

        assertEquals("0000fff0-0000-1000-8000-00805f9b34fb", profile.commands.single().service)
        assertEquals("0000fff1-0000-1000-8000-0805f9b34fb1", profile.commands.single().characteristic)
    }

    @Test
    fun `derives unique ids from names`() {
        val profile = HaProfileBuilder.build(
            session(
                listOf(
                    command(id = "a", name = "Power On!"),
                    command(id = "b", name = "power  on"),
                    command(id = "c", name = "Power-on"),
                    command(id = "d", name = "  Fan speed 2  "),
                ),
            ),
            utc,
        ).getOrThrow()

        assertEquals(listOf("power_on", "power_on_2", "power-on", "fan_speed_2"), profile.commands.map { it.id })
    }

    @Test
    fun `excludes commands whose name yields no usable id`() {
        val report = HaProfileBuilder.evaluate(session(listOf(command(name = "!!! ???"))), utc)

        assertEquals(
            listOf(ExclusionReason.UNUSABLE_NAME),
            report.excluded.single().problems.map { it.reason },
        )
    }

    @Test
    fun `rejects a profile larger than 64 kib`() {
        val commands = (1..HaProfileBuilder.MAX_COMMANDS).map {
            command(id = "c$it", name = "Command $it", payload = "AB".repeat(HaProfileBuilder.MAX_PAYLOAD_BYTES))
        }
        val report = HaProfileBuilder.evaluate(session(commands), utc)

        assertEquals(HaProfileBuilder.MAX_COMMANDS, report.included.size)
        assertTrue(report.encodedSizeBytes > HaProfileBuilder.MAX_PROFILE_BYTES)
        assertTrue(report.blockers.any { it is ProfileBlocker.ProfileTooLarge })
        assertTrue(HaProfileBuilder.build(session(commands), utc).isFailure)
    }

    @Test
    fun `rejects more device tested commands than the integration accepts`() {
        val commands = (1..HaProfileBuilder.MAX_COMMANDS + 1).map { command(id = "c$it", name = "Command $it") }
        val report = HaProfileBuilder.evaluate(session(commands), utc)

        assertEquals(
            ProfileBlocker.TooManyCommands(HaProfileBuilder.MAX_COMMANDS + 1),
            report.blockers.single(),
        )
    }

    @Test
    fun `requires a bluetooth address and a device name`() {
        val malformed = HaProfileBuilder.evaluate(session(listOf(command()), address = "AA:BB:CC:DD:EE"), utc)
        val missing = HaProfileBuilder.evaluate(session(listOf(command()), address = "", name = "   "), utc)
        val lowercase = HaProfileBuilder.evaluate(session(listOf(command()), address = "aa:bb:cc:dd:ee:ff"), utc)

        assertEquals(ProfileBlocker.InvalidDeviceAddress("AA:BB:CC:DD:EE"), malformed.blockers.single())
        assertNull(malformed.profile)
        assertTrue(missing.blockers.contains(ProfileBlocker.MissingDeviceName))
        assertTrue(missing.blockers.contains(ProfileBlocker.InvalidDeviceAddress("")))
        assertEquals("AA:BB:CC:DD:EE:FF", lowercase.profile?.device?.address)
    }

    @Test
    fun `falls back to generated notes when the operator wrote none`() {
        val profile = HaProfileBuilder.build(
            session(listOf(command(notes = "   ", observedCount = 7, replayCount = 2))),
            utc,
        ).getOrThrow()

        assertEquals(
            "Device-tested in BlueShark on 2023-11-14; observed 7 times, replayed 2 times",
            profile.commands.single().notes,
        )
    }

    @Test
    fun `failure carries the report so the export tab can explain itself`() {
        val failure = HaProfileBuilder.build(
            session(listOf(command(stage = EvidenceStage.OBSERVED))),
            utc,
        ).exceptionOrNull()

        val report = (failure as ProfileNotExportableException).report
        assertEquals(1, report.excluded.size)
        assertEquals("No command is device-tested yet", report.headline)
    }
}
