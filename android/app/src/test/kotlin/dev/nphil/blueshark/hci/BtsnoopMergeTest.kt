package dev.nphil.blueshark.hci

import dev.nphil.blueshark.model.AttOperation
import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.EventDirection
import dev.nphil.blueshark.model.EventSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BtsnoopMergeTest {
    private fun event(id: String, micros: Long) = BleEvent(
        id = id,
        timestampEpochMicros = micros,
        direction = EventDirection.PHONE_TO_DEVICE,
        source = EventSource.HCI_SNOOP,
        operation = AttOperation.WRITE_COMMAND,
        payloadHex = "08FF",
    )

    private fun result(events: List<BleEvent>, records: Int) =
        BtsnoopParseResult(events, ParseSummary(records = records, attEvents = events.size))

    @Test
    fun `a rotation merges ahead of the current file and overlapping records collapse`() {
        val older = result(listOf(event("a", 100), event("b", 200)), records = 2)
        val current = result(listOf(event("b", 200), event("c", 300)), records = 2)

        val merged = current.mergedWith(listOf(older))

        assertEquals(listOf("a", "b", "c"), merged.events.map { it.id })
        assertEquals(listOf(100L, 200L, 300L), merged.events.map { it.timestampEpochMicros })
        assertEquals(4, merged.summary.records)
        assertTrue(merged.summary.warnings.any { it.contains("adapter restarted") })
    }

    @Test
    fun `merging nothing leaves the result untouched`() {
        val only = result(listOf(event("a", 100)), records = 1)
        assertEquals(only, only.mergedWith(emptyList()))
    }
}
