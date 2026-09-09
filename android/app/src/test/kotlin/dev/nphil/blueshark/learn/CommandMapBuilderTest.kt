package dev.nphil.blueshark.learn

import dev.nphil.blueshark.model.AttOperation
import dev.nphil.blueshark.model.EvidenceStage
import dev.nphil.blueshark.model.WriteType
import dev.nphil.blueshark.probe.ProbeOutcome
import dev.nphil.blueshark.probe.ProbeStep
import dev.nphil.blueshark.probe.ProbeVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandMapBuilderTest {

    private val address = "AA:BB:CC:DD:EE:FF"
    private val fff1 = "0000fff1-0000-1000-8000-00805f9b34fb"

    private fun attribution(
        markerId: String = "m1",
        label: String = "Power switch -> on",
        viewId: String = "com.coolled.app:id/power",
        screen: String = "MainActivity",
        checked: Boolean? = true,
        rangeValue: Float? = null,
        text: String = "Power",
        payloads: List<String> = listOf("A5011E"),
        operations: List<AttOperation> = emptyList(),
        characteristic: String? = fff1,
        confidence: Double = 0.9,
        note: String = "",
    ) = Attribution(
        markerId = markerId,
        control = ControlState(
            label = label,
            viewId = viewId,
            screen = screen,
            checked = checked,
            rangeValue = rangeValue,
            text = text,
        ),
        eventIds = payloads.indices.map { "e$it" },
        payloads = payloads,
        operations = operations,
        characteristicUuid = characteristic,
        confidence = confidence,
        note = note,
    )

    private fun outcome(
        opcode: Int,
        label: String,
        sent: String,
        response: String?,
        verdict: ProbeVerdict,
        status: Int? = null,
        canary: Boolean = false,
        effect: String = "",
    ) = ProbeOutcome(
        step = ProbeStep(opcode = opcode, argument = byteArrayOf(0xFF.toByte()), label = label, canary = canary),
        sentHex = sent,
        responseHex = response,
        verdict = verdict,
        statusByte = status,
        elapsedMs = 40,
        observedEffect = effect,
    )

    @Test
    fun `a learned command is named after the control and its state`() {
        val map = CommandMapBuilder.fromAttributions(address, listOf(attribution()), familyId = "coolled", codecId = "coolled")

        assertEquals(address, map.deviceAddress)
        assertEquals("coolled", map.familyId)
        val command = map.commands.single()
        assertEquals("Power switch [on]", command.name)
        assertEquals("power_switch_on", command.id)
        assertEquals(EvidenceStage.OBSERVED, command.stage)
        assertEquals(CommandMapBuilder.SOURCE_LEARNED, command.source)
        assertEquals("A5011E", command.payloadHex)
        assertEquals(fff1, command.characteristicUuid)
        assertNull(command.decodedHex)
        assertEquals(
            listOf(
                "learned from tap \"Power switch -> on\" (confidence 0.90)",
                "control com.coolled.app:id/power on screen MainActivity",
                "write 1 of 1 to 0000fff1-0000-1000-8000-00805f9b34fb: A5011E",
            ),
            command.evidence,
        )
    }

    @Test
    fun `a slider is named with the percentage from its label`() {
        val map = CommandMapBuilder.fromAttributions(
            address,
            listOf(attribution(label = "Brightness 62%", checked = null, rangeValue = 158f, payloads = listOf("A5023E"))),
        )

        assertEquals("Brightness [62%]", map.commands.single().name)
    }

    @Test
    fun `a slider without a labelled percentage falls back to the raw value`() {
        val fraction = CommandMapBuilder.fromAttributions(
            address,
            listOf(attribution(label = "Brightness", checked = null, rangeValue = 0.62f)),
        )
        val raw = CommandMapBuilder.fromAttributions(
            address,
            listOf(attribution(label = "Fan speed", checked = null, rangeValue = 158f)),
        )

        assertEquals("Brightness [62%]", fraction.commands.single().name)
        assertEquals("Fan speed [158]", raw.commands.single().name)
    }

    @Test
    fun `a tap with several writes yields one numbered command per write`() {
        val map = CommandMapBuilder.fromAttributions(
            address,
            listOf(attribution(payloads = listOf("AA01", "BB02"), note = TapCorrelator.AMBIGUOUS_NOTE)),
        )

        assertEquals(
            listOf("Power switch [on] (write 1/2)", "Power switch [on] (write 2/2)"),
            map.commands.map { it.name },
        )
        assertEquals(listOf("power_switch_on_write_1_2", "power_switch_on_write_2_2"), map.commands.map { it.id })
        assertEquals(listOf("AA01", "BB02"), map.commands.map { it.payloadHex })
        assertTrue(map.commands.all { "correlator note: ${TapCorrelator.AMBIGUOUS_NOTE}" in it.evidence })
    }

    @Test
    fun `the command key ignores hex spacing and uuid shorthand`() {
        assertEquals(
            CommandMapBuilder.commandKey("A5011E", fff1),
            CommandMapBuilder.commandKey("a5 01 1e", "FFF1"),
        )
        assertTrue(
            CommandMapBuilder.commandKey("A5011E", fff1) !=
                CommandMapBuilder.commandKey("A5011E", "0000fff2-0000-1000-8000-00805f9b34fb"),
        )
        assertTrue(
            CommandMapBuilder.commandKey("A5011E", null) !=
                CommandMapBuilder.commandKey("A5011E", fff1),
        )
    }

    @Test
    fun `the write type comes from the ATT operation the app used`() {
        val map = CommandMapBuilder.fromAttributions(
            address,
            listOf(
                attribution(
                    payloads = listOf("AA01", "BB02"),
                    operations = listOf(AttOperation.WRITE_REQUEST, AttOperation.WRITE_COMMAND),
                ),
            ),
        )

        assertEquals(
            listOf(WriteType.WITH_RESPONSE, WriteType.WITHOUT_RESPONSE),
            map.commands.map { it.writeType },
        )
        assertTrue(map.commands.first().evidence.any { it.endsWith("AA01 (WRITE_REQUEST)") })
    }

    @Test
    fun `a frame seen written both ways keeps the acknowledged write`() {
        val map = CommandMapBuilder.fromAttributions(
            address,
            listOf(
                attribution(markerId = "m1", payloads = listOf("AA01"), operations = listOf(AttOperation.WRITE_COMMAND)),
                attribution(markerId = "m2", payloads = listOf("AA01"), operations = listOf(AttOperation.WRITE_REQUEST)),
            ),
        )

        assertEquals(WriteType.WITH_RESPONSE, map.commands.single().writeType)
    }

    @Test
    fun `a probe sweep's write type reaches its commands`() {
        val map = CommandMapBuilder.fromProbeEvidence(
            deviceAddress = address,
            evidence = listOf(ProbeEvidence(0x08, "mode? 0x08", "A5011E", "0100", accepted = true, effect = "")),
            characteristicUuid = fff1,
            writeType = WriteType.WITH_RESPONSE,
        )

        assertEquals(WriteType.WITH_RESPONSE, map.commands.single().writeType)
    }

    @Test
    fun `the same payload on the same characteristic is one command with both taps as evidence`() {
        val map = CommandMapBuilder.fromAttributions(
            address,
            listOf(
                attribution(markerId = "m1", confidence = 0.9),
                attribution(markerId = "m2", confidence = 0.5, screen = "SettingsActivity"),
            ),
        )

        val command = map.commands.single()
        assertEquals(5, command.evidence.size)
        assertTrue("control com.coolled.app:id/power on screen SettingsActivity" in command.evidence)
    }

    @Test
    fun `differing payloads keep distinct ids even under the same name`() {
        val map = CommandMapBuilder.fromAttributions(
            address,
            listOf(attribution(payloads = listOf("AA01")), attribution(markerId = "m2", payloads = listOf("BB02"))),
        )

        assertEquals(listOf("power_switch_on", "power_switch_on_2"), map.commands.map { it.id })
    }

    @Test
    fun `writes that no tap explains are not offered as commands`() {
        val map = CommandMapBuilder.fromAttributions(
            address,
            listOf(
                attribution(),
                Attribution(
                    markerId = "",
                    control = ControlState("", "", "", null, null, ""),
                    eventIds = listOf("orphan"),
                    payloads = listOf("FFEE"),
                    characteristicUuid = fff1,
                    confidence = 0.0,
                    note = TapCorrelator.UNATTRIBUTED_NOTE,
                ),
            ),
        )

        assertEquals(listOf("A5011E"), map.commands.map { it.payloadHex })
    }

    @Test
    fun `an accepted probe frame is device-tested and a silent one stays a hypothesis`() {
        val map = CommandMapBuilder.fromProbeOutcomes(
            deviceAddress = address,
            outcomes = listOf(
                outcome(
                    opcode = 0x08,
                    label = "mode? 0x08 = 0xff",
                    sent = "010204020608ff03",
                    response = "0102040100",
                    verdict = ProbeVerdict.ACCEPTED,
                    status = 0x00,
                    effect = "panel changed mode",
                ),
                outcome(
                    opcode = 0x09,
                    label = "unknown 0x09",
                    sent = "010204020609ff03",
                    response = null,
                    verdict = ProbeVerdict.NO_RESPONSE,
                ),
                outcome(
                    opcode = 0x0A,
                    label = "absent 0x0a",
                    sent = "01020402060aff03",
                    response = "0102040105",
                    verdict = ProbeVerdict.REJECTED_UNKNOWN_ID,
                    status = 0x05,
                ),
                outcome(
                    opcode = 0x01,
                    label = "canary",
                    sent = "010204020601ff03",
                    response = "0102040100",
                    verdict = ProbeVerdict.ACCEPTED,
                    status = 0x00,
                    canary = true,
                ),
            ),
            characteristicUuid = fff1,
            codecId = "coolled",
        )

        assertEquals(listOf("mode? 0x08 = 0xff", "unknown 0x09"), map.commands.map { it.name })
        assertEquals(
            listOf(EvidenceStage.DEVICE_TESTED, EvidenceStage.HYPOTHESIS),
            map.commands.map { it.stage },
        )
        assertEquals("010204020608FF03", map.commands.first().payloadHex)
        assertEquals(CommandMapBuilder.SOURCE_PROBE, map.commands.first().source)
        assertEquals(
            listOf(
                "prober wrote 010204020608ff03 (opcode 0x08)",
                "device accepted it, answering 0102040100",
                "operator saw: panel changed mode",
            ),
            map.commands.first().evidence,
        )
        assertEquals(
            "device did not answer inside the response window",
            map.commands.last().evidence.last(),
        )
    }

    @Test
    fun `merging keeps the device-tested record and every line of evidence`() {
        val learned = CommandMapBuilder.fromAttributions(
            address,
            listOf(attribution(payloads = listOf("010204020608FF03"))),
            familyId = "coolled",
        )
        val probed = CommandMapBuilder.fromProbeEvidence(
            deviceAddress = address,
            evidence = listOf(
                ProbeEvidence(
                    opcode = 0x08,
                    label = "mode? 0x08 = 0xff",
                    sentHex = "010204020608ff03",
                    responseHex = "0102040100",
                    accepted = true,
                    effect = "panel changed mode",
                ),
            ),
            characteristicUuid = fff1,
            codecId = "coolled",
        )

        val merged = CommandMapBuilder.merge(listOf(learned, probed))

        val command = merged.commands.single()
        assertEquals(EvidenceStage.DEVICE_TESTED, command.stage)
        assertEquals("mode? 0x08 = 0xff", command.name)
        assertEquals("coolled", merged.familyId)
        assertEquals("coolled", merged.codecId)
        assertTrue(command.evidence.any { it.startsWith("prober wrote") })
        assertTrue(command.evidence.any { it.startsWith("learned from tap") })
    }

    @Test
    fun `a weaker record never overwrites a device-tested one`() {
        val probed = CommandMapBuilder.fromProbeEvidence(
            deviceAddress = address,
            evidence = listOf(
                ProbeEvidence(0x08, "mode? 0x08 = 0xff", "A5011E", "0100", accepted = true, effect = ""),
            ),
            characteristicUuid = fff1,
        )
        val learned = CommandMapBuilder.fromAttributions(address, listOf(attribution()))

        assertEquals(
            EvidenceStage.DEVICE_TESTED,
            CommandMapBuilder.merge(listOf(probed, learned)).commands.single().stage,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maps of different devices cannot be merged`() {
        CommandMapBuilder.merge(
            listOf(
                CommandMapBuilder.fromAttributions(address, listOf(attribution())),
                CommandMapBuilder.fromAttributions("11:22:33:44:55:66", listOf(attribution())),
            ),
        )
    }
}
