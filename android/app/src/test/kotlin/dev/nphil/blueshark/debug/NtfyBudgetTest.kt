package dev.nphil.blueshark.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfyBudgetTest {

    private val day = "2026-09-09"

    @Test
    fun `burst of lines becomes one message and the rest waits`() {
        val lines = List(100) { "line $it " + "x".repeat(60) }
        val (body, rest) = NtfyBudget.takeBatch(lines)
        assertTrue(body.toByteArray().size <= NtfyBudget.MAX_BODY_BYTES)
        assertTrue(rest.isNotEmpty())
        assertEquals(lines.size, body.lines().count { it.isNotEmpty() } + rest.size)
        // Order preserved and nothing lost across the two halves.
        assertEquals(lines.first(), body.lineSequence().first())
        assertEquals(lines.last(), rest.last())
    }

    @Test
    fun `an oversized single line is truncated instead of blocking the queue`() {
        val huge = "y".repeat(10_000)
        val (body, rest) = NtfyBudget.takeBatch(listOf(huge, "after"))
        assertTrue(body.toByteArray().size <= NtfyBudget.MAX_BODY_BYTES)
        assertEquals(listOf("after"), rest)
    }

    @Test
    fun `batch size is measured in utf8 bytes`() {
        val line = "\u00e9".repeat(1_000) // 2 bytes each
        val (body, rest) = NtfyBudget.takeBatch(listOf(line, line, line), maxBytes = 4_096)
        assertEquals(2, body.lines().count { it.isNotEmpty() })
        assertEquals(1, rest.size)
    }

    @Test
    fun `second publish inside the minimum interval is deferred`() {
        val c0 = NtfyBudget.Counter(day, 5)
        val (ok1, c1) = NtfyBudget.admit(c0, day, nowMs = 100_000, lastSendMs = 0)
        assertTrue(ok1)
        assertEquals(6, c1.sentToday)
        val (ok2, c2) = NtfyBudget.admit(c1, day, nowMs = 100_000 + 4_000, lastSendMs = 100_000)
        assertFalse(ok2)
        assertEquals(6, c2.sentToday)
        val (ok3, _) = NtfyBudget.admit(c1, day, nowMs = 100_000 + NtfyBudget.MIN_INTERVAL_MS, lastSendMs = 100_000)
        assertTrue(ok3)
    }

    @Test
    fun `daily cap stops sending and resets on the next day`() {
        val full = NtfyBudget.Counter(day, NtfyBudget.DAILY_CAP)
        val (okSame, keep) = NtfyBudget.admit(full, day, nowMs = 1_000_000, lastSendMs = 0)
        assertFalse(okSame)
        assertEquals(NtfyBudget.DAILY_CAP, keep.sentToday)
        val (okNext, fresh) = NtfyBudget.admit(full, "2026-09-10", nowMs = 1_000_000, lastSendMs = 0)
        assertTrue(okNext)
        assertEquals(NtfyBudget.Counter("2026-09-10", 1), fresh)
    }

    @Test
    fun `redaction masks addresses and drops the secure settings section`() {
        val body = """
            ## getprop
            [ro.bluetooth.address]: [A4:C3:BE:0C:0D:FC]
            ## settings global
            bluetooth_on=1
            ## settings secure
            bluetooth_address=A4:C3:BE:0C:0D:FC
            android_id=0123456789abcdef
            ## dumpsys
            bonded 11-22-33-44-55-66 Kitchen Lamp
        """.trimIndent()
        val out = NtfyBudget.redact(body)
        assertFalse(out.contains("A4:C3:BE:0C:0D:FC"))
        assertFalse(out.contains("0123456789abcdef"))
        assertFalse(out.contains("11-22-33-44-55-66"))
        assertTrue(out.contains("xx:xx:xx:xx:xx:FC"))
        assertTrue(out.contains("## settings secure\n(redacted before upload)"))
        assertTrue(out.contains("bluetooth_on=1"))
        assertTrue(out.contains("Kitchen Lamp"))
    }
}
