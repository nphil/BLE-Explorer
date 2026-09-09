package dev.nphil.blueshark.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict table is the whole feature: DATA_ID_ERROR (0x05) against SUCCESS (0x00) is what
 * separates an opcode this device has from one it does not, without anyone squinting at a panel.
 * The report is what leaves the phone, so it has to carry its own context and refuse to present
 * silence from a dead link as evidence of absence.
 */
class ProbeInterpreterTest {

    private fun framed(vararg payload: Int): ByteArray =
        CoolLedCodec.encode(ByteArray(payload.size) { payload[it].toByte() })

    private fun outcome(
        opcode: Int,
        verdict: ProbeVerdict,
        status: Int? = null,
        label: String = "unmapped ${hexByte(opcode)} = 0x00",
        canary: Boolean = false,
        effect: String = "",
        responseHex: String? = null,
        elapsedMs: Long = 12,
    ) = ProbeOutcome(
        step = ProbeStep(opcode, byteArrayOf(0x00), label, canary),
        sentHex = "0102040206${"%02X".format(opcode)}0003",
        responseHex = responseHex,
        verdict = verdict,
        statusByte = status,
        elapsedMs = elapsedMs,
        observedEffect = effect,
    )

    @Test
    fun `a success status means the device has the opcode`() {
        assertEquals(ProbeVerdict.ACCEPTED to 0x00, ProbeInterpreter.verdictFor(framed(0x00), CoolLedCodec))
    }

    @Test
    fun `the unknown-id status is the oracle for an opcode the device lacks`() {
        assertEquals(
            ProbeVerdict.REJECTED_UNKNOWN_ID to 0x05,
            ProbeInterpreter.verdictFor(framed(0x05), CoolLedCodec),
        )
    }

    @Test
    fun `every other published error means the opcode is real and the argument was not`() {
        for (status in intArrayOf(0x01, 0x02, 0x03, 0x04, 0x06)) {
            assertEquals(
                "status ${hexByte(status)}",
                ProbeVerdict.REJECTED_OTHER to status,
                ProbeInterpreter.verdictFor(framed(status), CoolLedCodec),
            )
        }
    }

    @Test
    fun `a status outside the published table is reported as unreadable rather than as a refusal`() {
        assertEquals(ProbeVerdict.ERROR to 0x07, ProbeInterpreter.verdictFor(framed(0x07), CoolLedCodec))
        assertEquals(ProbeVerdict.ERROR to 0xFF, ProbeInterpreter.verdictFor(framed(0xFF), CoolLedCodec))
    }

    @Test
    fun `a response that is not a frame carries no status`() {
        // Missing terminator: the codec cannot vouch for any byte in it.
        val (verdict, status) = ProbeInterpreter.verdictFor(byteArrayOf(0x01, 0x02, 0x04, 0x02, 0x05, 0x00), CoolLedCodec)
        assertEquals(ProbeVerdict.ERROR, verdict)
        assertNull(status)
    }

    @Test
    fun `an empty framed payload has no status byte to read`() {
        assertEquals(ProbeVerdict.ERROR to null, ProbeInterpreter.verdictFor(framed(), CoolLedCodec))
    }

    @Test
    fun `silence is its own verdict`() {
        assertEquals(ProbeVerdict.NO_RESPONSE to null, ProbeInterpreter.verdictFor(null, CoolLedCodec))
    }

    @Test
    fun `the raw codec reads the first byte of the notification as the status`() {
        assertEquals(ProbeVerdict.ACCEPTED to 0x00, ProbeInterpreter.verdictFor(byteArrayOf(0x00, 0x11), RawCodec))
        assertEquals(ProbeVerdict.REJECTED_UNKNOWN_ID to 0x05, ProbeInterpreter.verdictFor(byteArrayOf(0x05), RawCodec))
        assertEquals(ProbeVerdict.ERROR to null, ProbeInterpreter.verdictFor(ByteArray(0), RawCodec))
    }

    @Test
    fun `the report names the device the codec the accepted opcodes and the operator's note`() {
        val report = ProbeInterpreter.summarise(
            outcomes = listOf(
                outcome(0x23, ProbeVerdict.ACCEPTED, 0x00, label = "initialize? 0x23 = 0x01"),
                outcome(0x01, ProbeVerdict.REJECTED_UNKNOWN_ID, 0x05),
                outcome(0x02, ProbeVerdict.REJECTED_UNKNOWN_ID, 0x05),
                outcome(
                    0x08,
                    ProbeVerdict.ACCEPTED,
                    0x00,
                    label = "brightness? 0x08 = 0xFF",
                    effect = "panel changed mode",
                ),
                outcome(0x06, ProbeVerdict.REJECTED_OTHER, 0x03, label = "mode? 0x06 = 0x00"),
                outcome(0x0A, ProbeVerdict.NO_RESPONSE),
            ),
            device = "iLedClock D4:12:AB:00:11:22",
            codec = CoolLedCodec,
        )

        assertTrue("names the device", "iLedClock D4:12:AB:00:11:22" in report)
        assertTrue("names the framing", "coolled" in report)
        assertTrue("counts the run", "6 probed · 2 accepted · 2 unknown id" in report)
        assertTrue("lists the accepted step", "brightness? 0x08 = 0xFF" in report)
        assertTrue("names the accepted status", "status 0x00 success" in report)
        assertTrue("lists the unknown ids", "rejected as unknown id (0x05)" in report)
        assertTrue("keeps the other rejection separate", "status 0x03 data error" in report)
        assertTrue("carries the operator's note", "panel changed mode" in report)
        assertTrue("concludes with the accepted set", "accepted 0x08, 0x23;" in report)
        assertTrue("warns that nothing certified the link", "no canary steps" in report)
    }

    @Test
    fun `a report with nothing accepted says so instead of implying a discovery`() {
        val report = ProbeInterpreter.summarise(
            listOf(outcome(0x01, ProbeVerdict.REJECTED_UNKNOWN_ID, 0x05)),
            device = "iLedClock",
            codec = CoolLedCodec,
        )
        assertTrue("no opcode was accepted; 1 rejected as unknown id." in report)
    }

    @Test
    fun `silence before a healthy canary stays evidence`() {
        val report = ProbeInterpreter.summarise(
            listOf(
                canaryOutcome(ProbeVerdict.ACCEPTED, 0x00, "baseline"),
                outcome(0x0A, ProbeVerdict.NO_RESPONSE),
                canaryOutcome(ProbeVerdict.ACCEPTED, 0x00, "after step 2"),
            ),
            device = "iLedClock",
            codec = CoolLedCodec,
        )
        assertTrue("2 canaries, all answered - silence below is evidence" in report)
        assertTrue("no response (link was up)" in report)
        assertFalse("not evidence" in report)
        assertTrue("1 probed" in report)
    }

    @Test
    fun `once a canary goes quiet later silence is reported as a dead link not a verdict`() {
        val report = ProbeInterpreter.summarise(
            listOf(
                canaryOutcome(ProbeVerdict.ACCEPTED, 0x00, "baseline"),
                outcome(0x01, ProbeVerdict.REJECTED_UNKNOWN_ID, 0x05),
                outcome(0x02, ProbeVerdict.NO_RESPONSE),
                canaryOutcome(ProbeVerdict.NO_RESPONSE, null, "after step 3"),
                outcome(0x03, ProbeVerdict.NO_RESPONSE),
                outcome(0x04, ProbeVerdict.NO_RESPONSE),
            ),
            device = "iLedClock",
            codec = CoolLedCodec,
        )

        assertTrue(
            "device stopped responding after step 3 - 2 later silent steps are not evidence of absence" in report,
        )
        // The silence before the canary died is still a verdict; the two after it are not.
        assertTrue("no response (link was up)" in report)
        assertTrue("no response after the device went quiet - not evidence" in report)
        assertTrue("2 silent after the device stopped answering at step 3 (not evidence)" in report)
        assertTrue("still counts the one honest silence", ", 1 silent," in report)
    }

    @Test
    fun `a canary answering something unexpected undermines the whole run and says so`() {
        val report = ProbeInterpreter.summarise(
            listOf(
                canaryOutcome(ProbeVerdict.REJECTED_UNKNOWN_ID, 0x05, "baseline"),
                outcome(0x01, ProbeVerdict.REJECTED_UNKNOWN_ID, 0x05),
            ),
            device = "iLedClock",
            codec = CoolLedCodec,
        )
        assertTrue("the canary itself may be wrong" in report)
    }

    @Test
    fun `a run whose baseline canary never answered proves nothing at all`() {
        // The likeliest real failure: notifications were never subscribed, so every step is silent.
        val report = ProbeInterpreter.summarise(
            listOf(
                canaryOutcome(ProbeVerdict.NO_RESPONSE, null, "baseline"),
                outcome(0x01, ProbeVerdict.NO_RESPONSE),
                outcome(0x02, ProbeVerdict.NO_RESPONSE),
            ),
            device = "iLedClock",
            codec = CoolLedCodec,
        )
        assertTrue(
            "the baseline canary never answered - the device was not talking to us, " +
                "so nothing below is evidence about any opcode" in report,
        )
        assertTrue("no response after the device went quiet - not evidence" in report)
        assertFalse("no response (link was up)" in report)
        assertTrue("2 silent with the device never answering at all (not evidence)." in report)
    }

    @Test
    fun `an empty run concludes that nothing is known`() {
        val report = ProbeInterpreter.summarise(emptyList(), device = "iLedClock", codec = CoolLedCodec)
        assertTrue("nothing ran, so nothing is known." in report)
        assertTrue("0 probed" in report)
    }

    @Test
    fun `a report built from the contract call still names its own gaps`() {
        val report = ProbeInterpreter.summarise(listOf(outcome(0x01, ProbeVerdict.NO_RESPONSE)))
        assertTrue("device      not recorded" in report)
        assertTrue("codec       not recorded" in report)
    }

    @Test
    fun `an undecodable response shows the bytes that could not be read`() {
        val report = ProbeInterpreter.summarise(
            listOf(outcome(0x0F, ProbeVerdict.ERROR, status = null, responseHex = "0102FF")),
            device = "iLedClock",
            codec = CoolLedCodec,
        )
        assertTrue("undecodable response" in report)
        assertTrue("could not decode (0102FF)" in report)
    }

    private fun canaryOutcome(verdict: ProbeVerdict, status: Int?, where: String) = outcome(
        opcode = 0x08,
        verdict = verdict,
        status = status,
        label = "canary 0x08 = 0x40 ($where)",
        canary = true,
    )
}
