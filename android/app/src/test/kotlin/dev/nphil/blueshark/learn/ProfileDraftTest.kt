package dev.nphil.blueshark.learn

import dev.nphil.blueshark.model.DeviceIdentity
import dev.nphil.blueshark.model.EvidenceStage
import dev.nphil.blueshark.model.GattCharacteristicRecord
import dev.nphil.blueshark.model.GattDatabase
import dev.nphil.blueshark.model.GattServiceRecord
import dev.nphil.blueshark.model.WriteType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProfileDraftTest {

    private val fff0 = "0000fff0-0000-1000-8000-00805f9b34fb"
    private val fff1 = "0000fff1-0000-1000-8000-00805f9b34fb"

    private val device = DeviceIdentity(
        address = "AA:BB:CC:DD:EE:FF",
        name = "iLedClock",
        advertisedServiceUuids = listOf("0000180a-0000-1000-8000-00805f9b34fb", fff0),
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

    /** The test device: one command service whose single characteristic writes and notifies. */
    private val coolledGatt = gatt(service(fff0, characteristic(fff1, "WRITE_NO_RESPONSE", "WRITE", "NOTIFY")))

    private fun command(
        name: String,
        payload: String,
        stage: EvidenceStage,
        characteristic: String? = fff1,
        writeType: WriteType = WriteType.WITHOUT_RESPONSE,
        evidence: List<String> = listOf("prober wrote $payload (opcode 0x08)", "device accepted it, answering 0100"),
    ) = MappedCommand(
        id = name.lowercase().replace(' ', '_'),
        name = name,
        characteristicUuid = characteristic,
        payloadHex = payload,
        decodedHex = null,
        source = CommandMapBuilder.SOURCE_PROBE,
        stage = stage,
        evidence = evidence,
        writeType = writeType,
    )

    private fun map(vararg commands: MappedCommand) =
        CommandMap(deviceAddress = device.address, familyId = "coolled", codecId = "coolled", commands = commands.toList())

    private fun ready(result: ProfileDraftResult): ProfileDraftResult.Ready {
        assertTrue("expected Ready, was $result", result is ProfileDraftResult.Ready)
        return result as ProfileDraftResult.Ready
    }

    private fun unresolvable(result: ProfileDraftResult): ProfileDraftResult.Unresolvable {
        assertTrue("expected Unresolvable, was $result", result is ProfileDraftResult.Unresolvable)
        return result as ProfileDraftResult.Unresolvable
    }

    // ---------------------------------------------------------------------
    // custom_components/blueshark/profile.py::validate_profile, in Kotlin.
    // ---------------------------------------------------------------------

    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
    private val hexPattern = Regex("(?:[0-9A-Fa-f]{2})(?: ?[0-9A-Fa-f]{2})*")
    private val commandFields = setOf(
        "id", "name", "service", "characteristic", "value", "response", "stage", "notes", "synthetic",
    )

    /** Applies every rule the integration applies, and returns the parsed profile. */
    private fun validate(text: String): JsonObject {
        assertTrue("profile is larger than 64 KiB", text.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("schema_version", "device", "synthetic", "commands"), root.keys)
        assertEquals(2, root.getValue("schema_version").jsonPrimitive.int)
        assertNotNull("synthetic must be a boolean", root.getValue("synthetic").jsonPrimitive.booleanOrNull)

        val deviceObject = root.getValue("device").jsonObject
        assertEquals(setOf("name", "address"), deviceObject.keys)
        val name = deviceObject.getValue("name").jsonPrimitive.content
        val address = deviceObject.getValue("address").jsonPrimitive.content
        assertTrue("device.name must not be blank", name.isNotBlank())
        assertTrue("device.address must not be blank", address.isNotBlank())
        assertTrue("device.address is too long", address.length <= 128)

        val commands = root.getValue("commands").jsonArray
        assertTrue("commands must be a non-empty array", commands.isNotEmpty())
        assertTrue("commands must hold at most 128 items", commands.size <= 128)
        val ids = HashSet<String>(commands.size * 2)
        for (element in commands) {
            val command = element.jsonObject
            assertEquals(commandFields, command.keys)
            val id = command.getValue("id").jsonPrimitive.content
            assertTrue("id \"$id\" has invalid characters", idPattern.matches(id))
            assertTrue("duplicate command id: $id", ids.add(id))
            assertTrue(command.getValue("name").jsonPrimitive.content.isNotBlank())
            val value = command.getValue("value").jsonPrimitive.content
            assertTrue("value \"$value\" is not hexadecimal byte pairs", hexPattern.matches(value))
            val payloadBytes = value.replace(" ", "").length / 2
            assertTrue("payload must be 1..512 bytes, was $payloadBytes", payloadBytes in 1..512)
            assertEquals("tested", command.getValue("stage").jsonPrimitive.content)
            assertTrue(command.getValue("notes").jsonPrimitive.content.isNotBlank())
            assertNotNull(command.getValue("response").jsonPrimitive.booleanOrNull)
            assertNotNull(command.getValue("synthetic").jsonPrimitive.booleanOrNull)
            // The integration parses both through uuid.UUID, which only takes the full form.
            UUID.fromString(command.getValue("service").jsonPrimitive.content)
            UUID.fromString(command.getValue("characteristic").jsonPrimitive.content)
        }
        return root
    }

    private fun JsonObject.commands(): List<JsonObject> = getValue("commands").jsonArray.map { it.jsonObject }

    @Test
    fun `a draft renders the profile the integration accepts`() {
        val result = ready(
            ProfileDraft.render(
                map(
                    command("Power switch [on]", "010204020608FF03", EvidenceStage.DEVICE_TESTED),
                    command(
                        name = "Brightness [62%]",
                        payload = "A5023E",
                        stage = EvidenceStage.OBSERVED,
                        evidence = listOf("learned from tap \"Brightness 62%\" (confidence 0.90)"),
                    ),
                ),
                device,
                coolledGatt,
            ),
        )

        assertEquals(emptyList<String>(), result.skipped)
        val root = validate(result.json)
        assertEquals(false, root.getValue("synthetic").jsonPrimitive.booleanOrNull)
        assertEquals("iLedClock", root.getValue("device").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            root.getValue("device").jsonObject.getValue("address").jsonPrimitive.content,
        )

        val commands = root.commands()
        assertEquals(listOf("power_switch_on", "brightness_62"), commands.map { it.getValue("id").jsonPrimitive.content })

        val tested = commands.first()
        assertEquals("Power switch [on]", tested.getValue("name").jsonPrimitive.content)
        assertEquals(fff0, tested.getValue("service").jsonPrimitive.content)
        assertEquals(fff1, tested.getValue("characteristic").jsonPrimitive.content)
        assertEquals("010204020608ff03", tested.getValue("value").jsonPrimitive.content)
        assertEquals(false, tested.getValue("response").jsonPrimitive.booleanOrNull)
        assertEquals(false, tested.getValue("synthetic").jsonPrimitive.booleanOrNull)
        assertTrue(tested.getValue("notes").jsonPrimitive.content.startsWith("Device-tested in BlueShark."))

        // Observed only: present so the operator can see it, but no entity is created for it.
        val observed = commands.last()
        assertEquals(true, observed.getValue("synthetic").jsonPrimitive.booleanOrNull)
        assertTrue(
            observed.getValue("notes").jsonPrimitive.content
                .startsWith("Observed in the vendor app's traffic, never replayed"),
        )
    }

    @Test
    fun `the service comes from the GATT database, not from the shape of the uuid`() {
        // BedJet: the command characteristic shares only four characters with its own service, and
        // the service that would win on a prefix match is a different one entirely.
        val bedJetService = "00001000-bed0-0080-aa55-4265644a6574"
        val bedJetCommand = "00002004-bed0-0080-aa55-4265644a6574"
        val result = ready(
            ProfileDraft.render(
                map(command("Turn off", "0102", EvidenceStage.DEVICE_TESTED, characteristic = bedJetCommand)),
                DeviceIdentity(
                    address = "AA:BB:CC:DD:EE:FF",
                    name = "BEDJET_V3",
                    advertisedServiceUuids = listOf("0000fff0-0000-1000-8000-00805f9b34fb", bedJetService),
                ),
                gatt(
                    service(fff0, characteristic(fff1, "WRITE")),
                    service(bedJetService, characteristic(bedJetCommand, "WRITE")),
                ),
            ),
        )

        val command = validate(result.json).commands().single()
        assertEquals(bedJetService, command.getValue("service").jsonPrimitive.content)
        assertEquals(bedJetCommand, command.getValue("characteristic").jsonPrimitive.content)
    }

    @Test
    fun `a characteristic missing from the GATT database is refused with a reason`() {
        val result = unresolvable(
            ProfileDraft.render(
                map(command("Mystery write", "BB02", EvidenceStage.DEVICE_TESTED, characteristic = "0000ffe1-0000-1000-8000-00805f9b34fb")),
                device,
                coolledGatt,
            ),
        )

        assertEquals(
            listOf(
                "Mystery write: no service in the GATT database exposes characteristic " +
                    "0000ffe1-0000-1000-8000-00805f9b34fb",
            ),
            result.reasons,
        )
    }

    @Test
    fun `an unresolvable command is reported beside an installable one`() {
        val result = ready(
            ProfileDraft.render(
                map(
                    command("Power switch [on]", "AA01", EvidenceStage.DEVICE_TESTED),
                    command("Mystery write", "BB02", EvidenceStage.DEVICE_TESTED, characteristic = "0000ffe1-0000-1000-8000-00805f9b34fb"),
                    command("No characteristic", "CC03", EvidenceStage.DEVICE_TESTED, characteristic = null),
                ),
                device,
                coolledGatt,
            ),
        )

        assertEquals(listOf("Power switch [on]"), validate(result.json).commands().map { it.getValue("name").jsonPrimitive.content })
        assertEquals(
            listOf(
                "Mystery write: no service in the GATT database exposes characteristic " +
                    "0000ffe1-0000-1000-8000-00805f9b34fb",
                "No characteristic: no characteristic was recorded for it",
            ),
            result.skipped,
        )
    }

    @Test
    fun `without a GATT database one advertised service is used and several are refused`() {
        val single = ready(
            ProfileDraft.render(
                map(command("Power switch [on]", "AA01", EvidenceStage.DEVICE_TESTED)),
                device.copy(advertisedServiceUuids = listOf(fff0)),
            ),
        )
        val ambiguous = unresolvable(
            ProfileDraft.render(map(command("Power switch [on]", "AA01", EvidenceStage.DEVICE_TESTED)), device),
        )

        assertEquals(fff0, validate(single.json).commands().single().getValue("service").jsonPrimitive.content)
        assertEquals(
            listOf(
                "Power switch [on]: no GATT database, and the advertisement names 2 services - " +
                    "which one holds $fff1 is unknown",
            ),
            ambiguous.reasons,
        )
    }

    @Test
    fun `response follows the write type unless the characteristic cannot take it`() {
        fun responseFor(writeType: WriteType, vararg properties: String): JsonObject {
            val result = ready(
                ProfileDraft.render(
                    map(command("Power switch [on]", "AA01", EvidenceStage.DEVICE_TESTED, writeType = writeType)),
                    device,
                    gatt(service(fff0, characteristic(fff1, *properties))),
                ),
            )
            return validate(result.json).commands().single()
        }

        assertEquals(
            true,
            responseFor(WriteType.WITH_RESPONSE, "WRITE", "NOTIFY").getValue("response").jsonPrimitive.booleanOrNull,
        )
        assertEquals(
            false,
            responseFor(WriteType.WITHOUT_RESPONSE, "WRITE_NO_RESPONSE").getValue("response").jsonPrimitive.booleanOrNull,
        )

        // Recorded as an unacknowledged write, but the characteristic only offers the acknowledged
        // one - button.py would refuse write-without-response, so the draft asks for the other.
        val forced = responseFor(WriteType.WITHOUT_RESPONSE, "WRITE", "NOTIFY")
        assertEquals(true, forced.getValue("response").jsonPrimitive.booleanOrNull)
        assertTrue(
            forced.getValue("notes").jsonPrimitive.content.contains(
                "the characteristic does not offer write-without-response, " +
                    "so an acknowledged write is requested",
            ),
        )

        // And the other way round: a characteristic that only takes unacknowledged writes.
        val relaxed = responseFor(WriteType.WITH_RESPONSE, "WRITE_NO_RESPONSE", "NOTIFY")
        assertEquals(false, relaxed.getValue("response").jsonPrimitive.booleanOrNull)
        assertTrue(
            relaxed.getValue("notes").jsonPrimitive.content.contains("only offers write-without-response"),
        )
    }

    @Test
    fun `a characteristic that takes no write at all is refused`() {
        val result = unresolvable(
            ProfileDraft.render(
                map(command("Status", "AA01", EvidenceStage.DEVICE_TESTED)),
                device,
                gatt(service(fff0, characteristic(fff1, "READ", "NOTIFY"))),
            ),
        )

        assertEquals(
            listOf("Status: characteristic $fff1 accepts no write (properties [READ, NOTIFY])"),
            result.reasons,
        )
    }

    @Test
    fun `a signed write cannot be installed`() {
        val result = unresolvable(
            ProfileDraft.render(
                map(command("Signed", "AA01", EvidenceStage.DEVICE_TESTED, writeType = WriteType.SIGNED)),
                device,
                coolledGatt,
            ),
        )

        assertEquals(listOf("Signed: signed writes cannot be installed"), result.reasons)
    }

    @Test
    fun `a draft with nothing device-tested installs nothing`() {
        val result = ready(
            ProfileDraft.render(map(command("Brightness [62%]", "A5023E", EvidenceStage.OBSERVED)), device, coolledGatt),
        )

        val root = validate(result.json)
        assertEquals(true, root.getValue("synthetic").jsonPrimitive.booleanOrNull)
        assertEquals(true, root.commands().single().getValue("synthetic").jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `payloads are kept up to the integration's limit and no further`() {
        val result = ready(
            ProfileDraft.render(
                map(
                    command("At the limit", "AB".repeat(512), EvidenceStage.DEVICE_TESTED),
                    command("Over the limit", "AB".repeat(513), EvidenceStage.DEVICE_TESTED),
                    command("Not hexadecimal", "nonsense", EvidenceStage.DEVICE_TESTED),
                    command("Empty", "", EvidenceStage.DEVICE_TESTED),
                ),
                device,
                coolledGatt,
            ),
        )

        assertEquals(listOf("at_the_limit"), validate(result.json).commands().map { it.getValue("id").jsonPrimitive.content })
        assertEquals(3, result.skipped.size)
        assertTrue(result.skipped.all { it.contains("is not 1..512 hexadecimal bytes") })
    }

    @Test
    fun `no more commands than the integration installs`() {
        val commands = (1..130).map { command("Command $it", "%04X".format(it), EvidenceStage.DEVICE_TESTED) }

        val result = ready(ProfileDraft.render(map(*commands.toTypedArray()), device, coolledGatt))

        assertEquals(128, validate(result.json).commands().size)
        assertEquals(2, result.skipped.size)
        assertTrue(result.skipped.all { it.endsWith("over the 128-command limit") })
    }

    @Test
    fun `an alias wins over the advertised name, and an unnamed device still renders`() {
        val aliased = ready(
            ProfileDraft.render(
                map(command("Power switch [on]", "AA01", EvidenceStage.DEVICE_TESTED)),
                device.copy(alias = "Desk clock"),
                coolledGatt,
            ),
        )
        val nameless = ready(
            ProfileDraft.render(
                map(command("Power switch [on]", "AA01", EvidenceStage.DEVICE_TESTED)),
                device.copy(name = null),
                coolledGatt,
            ),
        )

        assertEquals(
            "Desk clock",
            validate(aliased.json).getValue("device").jsonObject.getValue("name").jsonPrimitive.content,
        )
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            validate(nameless.json).getValue("device").jsonObject.getValue("name").jsonPrimitive.content,
        )
    }

    @Test
    fun `an empty map is unresolvable rather than an empty profile`() {
        val result = unresolvable(ProfileDraft.render(map(), device, coolledGatt))

        assertEquals(listOf("This project has no commands yet."), result.reasons)
    }

    @Test
    fun `the whole learn path renders a draft from taps and probes`() {
        val learned = CommandMapBuilder.fromAttributions(
            deviceAddress = device.address,
            attributions = listOf(
                Attribution(
                    markerId = "m1",
                    control = ControlState(
                        label = "Power switch -> on",
                        viewId = "com.coolled.app:id/power",
                        screen = "MainActivity",
                        checked = true,
                        rangeValue = null,
                        text = "Power",
                    ),
                    eventIds = listOf("e1"),
                    payloads = listOf("010204020608FF03"),
                    operations = listOf(dev.nphil.blueshark.model.AttOperation.WRITE_COMMAND),
                    characteristicUuid = fff1,
                    confidence = 0.9,
                    note = "",
                ),
            ),
            familyId = "coolled",
            codecId = "coolled",
        )
        val probed = CommandMapBuilder.fromProbeEvidence(
            deviceAddress = device.address,
            evidence = listOf(
                ProbeEvidence(0x08, "mode? 0x08 = 0xff", "010204020608ff03", "0102040100", accepted = true, effect = "mode changed"),
            ),
            characteristicUuid = fff1,
        )

        val result = ready(
            ProfileDraft.render(CommandMapBuilder.merge(listOf(learned, probed)), device, coolledGatt),
        )

        val root = validate(result.json)
        val command = root.commands().single()
        assertEquals("mode? 0x08 = 0xff", command.getValue("name").jsonPrimitive.content)
        assertEquals("010204020608ff03", command.getValue("value").jsonPrimitive.content)
        assertEquals(false, command.getValue("synthetic").jsonPrimitive.booleanOrNull)
        assertFalse(root.getValue("synthetic").jsonPrimitive.booleanOrNull!!)
        val notes = command.getValue("notes").jsonPrimitive.content
        assertTrue(notes, notes.contains("operator saw: mode changed"))
        assertTrue(notes, notes.contains("learned from tap"))
    }
}
