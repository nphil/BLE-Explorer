package dev.nphil.blueshark.ui.project

import dev.nphil.blueshark.learn.CommandMapBuilder
import dev.nphil.blueshark.learn.TapCorrelator
import dev.nphil.blueshark.model.AttOperation
import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.CaptureMarker
import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.ControlRef
import dev.nphil.blueshark.model.DeviceIdentity
import dev.nphil.blueshark.model.EventDirection
import dev.nphil.blueshark.model.EventSource
import dev.nphil.blueshark.model.EvidenceStage
import dev.nphil.blueshark.model.FamilyMatchRecord
import dev.nphil.blueshark.model.GattCharacteristicRecord
import dev.nphil.blueshark.model.GattDatabase
import dev.nphil.blueshark.model.GattServiceRecord
import dev.nphil.blueshark.model.MarkerSource
import dev.nphil.blueshark.model.ProbeRecord
import dev.nphil.blueshark.model.WriteType
import dev.nphil.blueshark.probe.CoolLedCodec
import dev.nphil.blueshark.probe.ProbeVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a device project is willing to call a command, and on what evidence.
 *
 * Every rule here is one the funnel adds on top of `learn/`, which is exactly where a later
 * "simplification" would quietly undo it. The consequence of getting one wrong is not cosmetic:
 * `ProfileDraft` creates a Home Assistant button for a device-tested command and none for anything
 * weaker, and it writes the ATT write type into that button, so a wrong stage ships a payload
 * nobody watched work and a wrong write type ships a button that always fails.
 */
class ProjectCommandMapTest {

    private val address = "AA:BB:CC:DD:EE:FF"
    private val service = "0000fff0-0000-1000-8000-00805f9b34fb"
    private val characteristic = "0000fff1-0000-1000-8000-00805f9b34fb"

    private fun probe(
        opcode: Int,
        verdict: ProbeVerdict,
        effect: String = "",
        writeType: WriteType = WriteType.WITHOUT_RESPONSE,
        canary: Boolean = false,
    ): ProbeRecord {
        val frame = CoolLedCodec.encode(byteArrayOf(opcode.toByte(), 0xFF.toByte()))
        return ProbeRecord(
            runId = "run-1",
            stepIndex = opcode,
            opcode = opcode,
            label = "opcode $opcode",
            argumentHex = "FF",
            codecId = CoolLedCodec.id,
            serviceUuid = service,
            characteristicUuid = characteristic,
            writeType = writeType,
            sentHex = frame.joinToString("") { "%02X".format(it) },
            responseHex = if (verdict == ProbeVerdict.NO_RESPONSE) null else "010204020600030503",
            verdict = verdict.name,
            statusByte = if (verdict == ProbeVerdict.ACCEPTED) 0x00 else 0x05,
            elapsedMs = 12,
            canary = canary,
            observedEffect = effect,
        )
    }

    private fun project(vararg probes: ProbeRecord) = CaptureSession(
        name = "iLedClock",
        device = DeviceIdentity(address = address, name = "iLedClock"),
        family = FamilyMatchRecord(
            familyId = "coolled",
            name = "CoolLED",
            confidence = "CERTAIN",
            codecId = CoolLedCodec.id,
        ),
        probes = probes.toList(),
    )

    @Test
    fun `an accepted frame nobody watched work stays a hypothesis`() {
        val map = requireNotNull(storedProbeMap(project(probe(0x08, ProbeVerdict.ACCEPTED))))

        val command = map.commands.single()
        assertEquals(EvidenceStage.HYPOTHESIS, command.stage)
        assertTrue(
            "the row has to say why it was held back",
            command.evidence.any { it.contains("no observed effect") },
        )
    }

    @Test
    fun `an accepted frame the operator saw work is device-tested`() {
        val map = requireNotNull(
            storedProbeMap(project(probe(0x08, ProbeVerdict.ACCEPTED, effect = "panel changed mode"))),
        )

        assertEquals(EvidenceStage.DEVICE_TESTED, map.commands.single().stage)
    }

    /** A canary proves the link, not a command, and "no such id" is evidence of absence. */
    @Test
    fun `canaries and unknown-id rejections are not offered as commands`() {
        val session = project(
            probe(0x08, ProbeVerdict.ACCEPTED, effect = "mode changed"),
            probe(0x40, ProbeVerdict.ACCEPTED, canary = true),
            probe(0x11, ProbeVerdict.REJECTED_UNKNOWN_ID),
        )

        val map = requireNotNull(storedProbeMap(session))

        assertEquals(1, map.commands.size)
        assertEquals(EvidenceStage.DEVICE_TESTED, map.commands.single().stage)
    }

    /**
     * The command-map table's "decoded" column and its "write" column, on the vector this whole
     * redesign came out of: `08 FF` framed as `010204020608FF03` changed the test device's mode.
     */
    @Test
    fun `merging fills the decoded payload and the recorded write type`() {
        val session = project(probe(0x08, ProbeVerdict.ACCEPTED, effect = "mode changed"))
        val fresh = requireNotNull(storedProbeMap(session))

        val stored = mergeCommandMap(session, fresh)

        val command = stored.single()
        assertEquals("010204020608FF03", command.payloadHex)
        assertEquals("08FF", command.decodedHex)
        assertEquals(WriteType.WITHOUT_RESPONSE, command.writeType)
    }

    /** A characteristic that only takes a write request has to be recorded as taking one. */
    @Test
    fun `a frame written with a response keeps that write type`() {
        val session = project(
            probe(0x08, ProbeVerdict.ACCEPTED, effect = "mode changed", writeType = WriteType.WITH_RESPONSE),
        )

        val stored = mergeCommandMap(session, requireNotNull(storedProbeMap(session)))

        assertEquals(WriteType.WITH_RESPONSE, stored.single().writeType)
    }

    /**
     * A frame learned from a tap and later confirmed by the prober is one command with both
     * stories behind it - and it keeps the tap's confidence, because merging must never lose
     * evidence it already had.
     */
    @Test
    fun `a learned frame the prober confirmed keeps the strongest stage and the tap's confidence`() {
        val attributions = TapCorrelator.correlate(markers(), events())
        val attributed = attributions.filter { it.markerId.isNotEmpty() }
        val seed = CaptureSession(name = "seed", device = DeviceIdentity(address = address))
        val learnedMap = mergeCommandMap(
            session = seed,
            fresh = CommandMapBuilder.fromAttributions(deviceAddress = address, attributions = attributed),
            confidences = confidenceByCommand(attributed),
        )
        val learned = learnedMap.single { it.payloadHex == "010204020608FF03" }
        assertEquals(EvidenceStage.OBSERVED, learned.stage)
        assertEquals(1.0, learned.confidence!!, 1e-9)
        assertEquals("Power switch [on]", learned.name)

        val session = project(probe(0x08, ProbeVerdict.ACCEPTED, effect = "mode changed"))
            .copy(commandMap = learnedMap)
        val stored = mergeCommandMap(
            session = session,
            fresh = requireNotNull(storedProbeMap(session)),
        )

        val merged = stored.single { it.payloadHex == "010204020608FF03" }
        assertEquals(EvidenceStage.DEVICE_TESTED, merged.stage)
        assertEquals(1.0, merged.confidence!!, 1e-9)
        assertTrue(
            "the tap that found it must survive the merge",
            merged.evidence.any { it.contains("Power switch") },
        )
    }

    @Test
    fun `writes with no tap in front of them are folded per payload`() {
        val orphans = TapCorrelator.correlate(emptyList(), events()).filter { it.markerId.isEmpty() }

        assertEquals(
            listOf(UnattributedWrite("010204020608FF03", characteristic, 1)),
            unattributedWrites(orphans),
        )
    }

    /**
     * Where the service a command lives in is allowed to come from.
     *
     * Home Assistant looks a characteristic up inside the service it is told, so a command whose
     * service cannot be established is not installable. The fix for that is the attribute
     * database - never folding GATT uuids into `advertisedServiceUuids`, which would fabricate
     * advertisement evidence and let a nameless fff0/fff1 module be certified as a known family.
     */
    @Test
    fun `a draft is exportable only once the attribute database says where the command lives`() {
        val session = project(probe(0x08, ProbeVerdict.ACCEPTED, effect = "mode changed"))
            .copy(device = DeviceIdentity(address = address, name = "iLedClock"))
        val mapped = session.copy(
            commandMap = mergeCommandMap(
                session = session,
                fresh = requireNotNull(storedProbeMap(session)),
            ),
        )

        val unknown = draftOf(mapped)
        assertEquals(false, unknown.exportable)
        assertTrue("the operator has to be told what is missing", unknown.blockedReason!!.contains("enumerate"))

        val enumerated = draftOf(mapped.copy(gatt = database()))
        assertTrue("the database places the characteristic", enumerated.exportable)
        assertTrue(enumerated.text.contains("0000fff0"))
        assertTrue(enumerated.text.contains("010204020608ff03"))
    }

    /** Enumeration must not rewrite what the device broadcast. */
    @Test
    fun `the fingerprint sees the attribute database without the advertisement being rewritten`() {
        val gatt = database()
        val session = CaptureSession(
            name = "nameless module",
            device = DeviceIdentity(address = address),
            gatt = gatt,
        )

        val input = session.fingerprintInput()

        assertEquals(emptyList<String>(), input.serviceUuids)
        assertEquals(gatt, input.gatt)
    }

    private fun database() = GattDatabase(
        services = listOf(
            GattServiceRecord(
                uuid = service,
                instanceId = 0,
                type = "PRIMARY",
                characteristics = listOf(
                    GattCharacteristicRecord(
                        uuid = characteristic,
                        instanceId = 0x52,
                        properties = listOf("WRITE_NO_RESPONSE", "NOTIFY"),
                        permissions = emptyList(),
                    ),
                ),
            ),
        ),
    )

    private fun markers() = listOf(
        CaptureMarker(
            id = "m1",
            timestampEpochMicros = 1_000_000,
            label = "Power switch -> on",
            source = MarkerSource.ACCESSIBILITY,
            control = ControlRef(packageName = "com.vendor.app", screen = "Main", viewId = "sw_power"),
        ),
    )

    private fun events() = listOf(
        BleEvent(
            id = "e1",
            timestampEpochMicros = 1_100_000,
            direction = EventDirection.PHONE_TO_DEVICE,
            source = EventSource.HCI_SNOOP,
            operation = AttOperation.WRITE_COMMAND,
            characteristicUuid = characteristic,
            payloadHex = "010204020608FF03",
        ),
    )
}
