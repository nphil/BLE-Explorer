package dev.nphil.blestudio.hci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event identity across imports.
 *
 * The Capture screen skips events whose id the session already holds, so re-collecting a growing
 * btsnoop log must re-create the ids it produced last time - and two genuinely distinct records
 * that happen to carry identical bytes at an identical timestamp must not collapse into one.
 */
class BtsnoopEventIdTest {

    private fun parse(bytes: ByteArray) = BtsnoopParser().parse(bytes.inputStream())

    /** [records] write commands with distinct payloads, appended in a fixed order. */
    private fun capture(records: Int): ByteArray {
        val builder = CaptureBuilder()
        for (index in 1..records) {
            builder.packet(
                Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(index.toByte(), 0x7F))),
            )
        }
        return builder.build()
    }

    @Test
    fun `the same capture parsed twice yields the same event ids`() {
        val first = parse(capture(records = 4)).events
        val second = parse(capture(records = 4)).events

        assertEquals(4, first.size)
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `a capture that grew keeps the ids of the records it already had`() {
        val earlier = parse(capture(records = 2)).events
        val later = parse(capture(records = 3)).events

        assertEquals(3, later.size)
        assertEquals(earlier.map { it.id }, later.take(2).map { it.id })
        assertTrue(later[2].id !in earlier.map { it.id })
    }

    @Test
    fun `two identical records in one file stay two events`() {
        val packet = Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x01, 0x02)))
        val bytes = CaptureBuilder()
            .packet(packet, advanceMicros = 0)
            .packet(packet, advanceMicros = 0)
            .build()

        val events = parse(bytes).events

        assertEquals(2, events.size)
        // Same instant, same bytes, same handle: only the record ordinal separates them.
        assertEquals(events[0].timestampEpochMicros, events[1].timestampEpochMicros)
        assertEquals(events[0].payloadHex, events[1].payloadHex)
        assertNotEquals(events[0].id, events[1].id)
    }

    @Test
    fun `a different payload gets a different id at the same position`() {
        val one = parse(
            CaptureBuilder()
                .packet(Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x01))))
                .build(),
        ).events.single()
        val other = parse(
            CaptureBuilder()
                .packet(Fixtures.att(0x0040, Fixtures.writeCommand(0x0025, byteArrayOf(0x02))))
                .build(),
        ).events.single()

        assertNotEquals(one.id, other.id)
    }
}
