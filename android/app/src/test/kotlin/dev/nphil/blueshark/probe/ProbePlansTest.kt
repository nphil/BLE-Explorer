package dev.nphil.blueshark.probe

import dev.nphil.blueshark.model.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sweep runs against someone's hardware. What it must never contain matters more than what it
 * does: a power-off mid-sweep makes every later step read as silence, which the report would
 * otherwise present as evidence that those opcodes do not exist.
 */
class ProbePlansTest {

    @Test
    fun `the default sweep contains no destructive opcode`() {
        val swept = ProbePlans.coolLedOpcodeSweep().map { it.opcode }.toSet()
        assertEquals(emptySet<Int>(), swept intersect ProbePlans.DESTRUCTIVE_OPCODES)
        assertFalse("power down 0x12 must never be swept by default", 0x12 in swept)
    }

    @Test
    fun `the deny list holds every irrecoverable id the driver documents and explains each one`() {
        // Grounded in UpDryTwist/coolledx-driver src/coolledx/hardware.py: button-off 0x05 (locks
        // the physical buttons), clear 0x0D, power down 0x12, switch 0x09 — each with its +2 image,
        // because this device's id map is shifted (0x08 changed mode where the driver puts mode at
        // 0x06), so the dangerous function may sit two ids above its published number.
        assertEquals(setOf(0x05, 0x07, 0x09, 0x0B, 0x0D, 0x0F, 0x12, 0x14), ProbePlans.DESTRUCTIVE_OPCODES)
        for (opcode in ProbePlans.DESTRUCTIVE_OPCODES) {
            assertNotNull("no documented reason for ${hexByte(opcode)}", ProbePlans.destructiveReason(opcode))
        }
        assertNull(ProbePlans.destructiveReason(0x08))
    }

    @Test
    fun `a reversible command is not treated as destructive`() {
        // invert display 0x0C and its shifted image 0x0E undo by writing the opposite value, which
        // is the entire test for deny-list membership.
        assertNull(ProbePlans.destructiveReason(0x0C))
        assertNull(ProbePlans.destructiveReason(0x0E))
        val swept = ProbePlans.coolLedOpcodeSweep().map { it.opcode }
        assertTrue(0x0C in swept)
        assertTrue(0x0E in swept)
    }

    @Test
    fun `the sweep covers the published opcodes and their unmapped neighbours`() {
        val swept = ProbePlans.coolLedOpcodeSweep().map { it.opcode }
        // Published ids that survive the deny list, plus the documented handshake.
        for (published in listOf(0x06, 0x08, 0x0C, 0x11, 0x13, 0x23)) {
            assertTrue("${hexByte(published)} missing from the sweep", published in swept)
        }
        // The unmapped neighbours are the control group: without them a run of successes proves
        // nothing, because a device that acknowledges everything looks identical.
        for (unmapped in listOf(0x0E, 0x10)) {
            assertTrue("${hexByte(unmapped)} missing from the sweep", unmapped in swept)
        }
        assertEquals(
            listOf(0x23, 0x01, 0x02, 0x03, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0E, 0x10, 0x11, 0x13),
            swept,
        )
        assertTrue("nothing outside 0x01..0x14 except the handshake", swept.all { it in 0x01..0x14 || it == 0x23 })
        assertEquals("the handshake goes first in case the device gates on it", 0x23, swept.first())
    }

    @Test
    fun `every step names its guess as a guess and shows the bytes it will send`() {
        val steps = ProbePlans.coolLedOpcodeSweep()
        assertEquals("brightness? 0x08 = 0x40", steps.first { it.opcode == 0x08 }.label)
        assertEquals("unmapped 0x10 = 0x00", steps.first { it.opcode == 0x10 }.label)
        assertEquals("initialize? 0x23 = 0x01", steps.first { it.opcode == 0x23 }.label)
        assertEquals("button on? 0x13 = 0x01", steps.first { it.opcode == 0x13 }.label)
        // A content-transfer id is probed with a deliberately too-short argument: a length error is
        // the evidence we want, overwriting what is on the panel is not.
        assertEquals("text? 0x02 = 0x00", steps.first { it.opcode == 0x02 }.label)
        assertTrue(steps.none { it.canary })
    }

    @Test
    fun `a step sends the opcode followed by its argument`() {
        val step = ProbePlans.coolLedOpcodeSweep().first { it.opcode == 0x08 }
        assertEquals("0840", step.payload().toHex())
        assertEquals("0102040206084003", CoolLedCodec.encode(step.payload()).toHex())
    }

    @Test
    fun `a value sweep marks a destructive opcode in the label the operator reads`() {
        val steps = ProbePlans.valueSweep(0x12, listOf(0x00, 0x01))
        assertEquals(
            listOf("power down? 0x12 = 0x00 [destructive]", "power down? 0x12 = 0x01 [destructive]"),
            steps.map { it.label },
        )
        assertEquals(listOf("1200", "1201"), steps.map { it.payload().toHex() })
    }

    @Test
    fun `a value sweep of a safe opcode carries no warning`() {
        val steps = ProbePlans.valueSweep(0x08, listOf(0x00, 0x40, 0xFF))
        assertEquals(listOf(0x08, 0x08, 0x08), steps.map { it.opcode })
        assertTrue(steps.none { "[destructive]" in it.label })
        assertEquals("brightness? 0x08 = 0xFF", steps.last().label)
    }

    @Test
    fun `a value sweep rejects a value that is not one byte`() {
        val tooBig = runCatching { ProbePlans.valueSweep(0x08, listOf(0x100)) }
        assertTrue(tooBig.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `the canary is the verified-live opcode with the verified-inert value`() {
        val canary = ProbePlans.canaryStep()
        assertEquals(0x08, canary.opcode)
        assertEquals("0840", canary.payload().toHex())
        assertTrue(canary.canary)
    }

    @Test
    fun `canaries bracket the run and appear at the requested interval`() {
        val steps = ProbePlans.withCanaries(ProbePlans.coolLedOpcodeSweep(), every = 5)
        assertTrue("the run starts with a baseline", steps.first().canary)
        assertTrue("the run ends certified", steps.last().canary)
        // 13 sweep steps at every=5: baseline, 5, c, 5, c, 3, c.
        assertEquals(13, steps.count { !it.canary })
        assertEquals(4, steps.count { it.canary })
        assertEquals("canary 0x08 = 0x40 (baseline)", steps.first().label)
        assertEquals("canary 0x08 = 0x40 (after step 6)", steps[6].label)
        assertTrue("canary labels stay distinct", steps.map { it.label }.distinct().size == steps.size)
    }

    @Test
    fun `canary placement counts positions in the run the operator will see`() {
        val steps = ProbePlans.withCanaries(ProbePlans.valueSweep(0x08, listOf(0, 1, 2, 3)), every = 2)
        // baseline, s1, s2, canary(after 3), s3, s4, canary(after 6)
        assertEquals(
            listOf(true, false, false, true, false, false, true),
            steps.map { it.canary },
        )
        assertEquals("canary 0x08 = 0x40 (after step 3)", steps[3].label)
        assertEquals("canary 0x08 = 0x40 (after step 6)", steps.last().label)
    }

    @Test
    fun `wrapping nothing yields nothing rather than a run of pure canaries`() {
        assertEquals(emptyList<ProbeStep>(), ProbePlans.withCanaries(emptyList()))
        val zeroInterval = runCatching { ProbePlans.withCanaries(listOf(ProbePlans.canaryStep()), every = 0) }
        assertTrue(zeroInterval.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `steps compare by their bytes so the runner can diff and key on them`() {
        val one = ProbeStep(0x08, byteArrayOf(0x40), "brightness? 0x08 = 0x40")
        val two = ProbeStep(0x08, byteArrayOf(0x40), "brightness? 0x08 = 0x40")
        assertEquals(one, two)
        assertEquals(one.hashCode(), two.hashCode())
        assertFalse(one == ProbeStep(0x08, byteArrayOf(0x41), "brightness? 0x08 = 0x40"))
        assertFalse(one == ProbeStep(0x08, byteArrayOf(0x40), "brightness? 0x08 = 0x40", canary = true))
    }
}
