package dev.nphil.blueshark.ui.scan

import dev.nphil.blueshark.model.AdvertisementSample
import dev.nphil.blueshark.model.ConnectAttempt
import dev.nphil.blueshark.model.ConnectionFacts
import dev.nphil.blueshark.model.EventSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a second "Save to session" tap may add.
 *
 * Nothing on the scan screen is cleared by a save: the scanner still holds the last advertisement,
 * and the GATT client still holds every connect attempt and link-setup timing of the session. A
 * repeated save must therefore add only what arrived in between.
 */
class ScanEvidenceMergeTest {

    private fun sample(timestampEpochMs: Long, bytesHex: String) = AdvertisementSample(
        timestampEpochMs = timestampEpochMs,
        rssi = -60,
        bytesHex = bytesHex,
    )

    private fun attempt(startedEpochMs: Long, success: Boolean = true) = ConnectAttempt(
        startedEpochMs = startedEpochMs,
        durationMs = 120,
        success = success,
        source = EventSource.LIVE_GATT,
    )

    @Test
    fun `re-offering the stored advertisement adds nothing`() {
        val stored = listOf(sample(1_000, "02011A"))

        assertEquals(stored, mergeAdvertisements(stored, sample(1_000, "02011A")))
    }

    @Test
    fun `a fresh advertisement is appended`() {
        val stored = listOf(sample(1_000, "02011A"))
        val fresh = sample(2_000, "02011A")

        assertEquals(stored + fresh, mergeAdvertisements(stored, fresh))
    }

    @Test
    fun `a saved connect attempt is not stored twice`() {
        val live = ConnectionFacts(connectAttempts = listOf(attempt(1_000), attempt(2_000, success = false)))
        val first = mergeLiveFacts(ConnectionFacts(), live, newSamples = 0)

        val second = mergeLiveFacts(first, live, newSamples = 0)

        assertEquals(listOf(1_000L, 2_000L), second.connectAttempts.map { it.startedEpochMs })
    }

    @Test
    fun `only link timings recorded since the last save are appended`() {
        val afterFirstSave = mergeLiveFacts(
            base = ConnectionFacts(),
            live = ConnectionFacts(reconnectSamplesMs = listOf(310L, 290L)),
            newSamples = 2,
        )

        // Two more connects happened; the live list still carries the first two.
        val afterSecondSave = mergeLiveFacts(
            base = afterFirstSave,
            live = ConnectionFacts(reconnectSamplesMs = listOf(310L, 290L, 640L, 305L)),
            newSamples = 2,
        )

        assertEquals(listOf(310L, 290L), afterFirstSave.reconnectSamplesMs)
        assertEquals(listOf(310L, 290L, 640L, 305L), afterSecondSave.reconnectSamplesMs)
    }

    @Test
    fun `a saturated live list still yields its newest timings`() {
        // GattClient caps reconnectSamplesMs and drops from the front, so once it saturates the
        // list stops growing while connects keep happening. An offset into it would append nothing
        // ever again; the arrival count is what makes the new timings visible.
        val cap = 20
        val stored = ConnectionFacts(reconnectSamplesMs = (1..cap).map { it.toLong() })
        val live = ConnectionFacts(reconnectSamplesMs = (3..cap + 2).map { it.toLong() })

        val merged = mergeLiveFacts(stored, live, newSamples = 2)

        assertEquals(cap + 2, merged.reconnectSamplesMs.size)
        assertEquals(listOf((cap + 1).toLong(), (cap + 2).toLong()), merged.reconnectSamplesMs.takeLast(2))
    }

    @Test
    fun `nothing new means nothing appended`() {
        val stored = ConnectionFacts(reconnectSamplesMs = listOf(310L))

        val merged = mergeLiveFacts(stored, ConnectionFacts(reconnectSamplesMs = listOf(310L)), newSamples = 0)

        assertEquals(listOf(310L), merged.reconnectSamplesMs)
    }

    @Test
    fun `a live list shorter than the count cannot invent timings`() {
        val stored = ConnectionFacts(reconnectSamplesMs = listOf(310L))

        // The client was replaced (screen recreated): its list and its count start over.
        val merged = mergeLiveFacts(stored, ConnectionFacts(reconnectSamplesMs = emptyList()), newSamples = 4)

        assertEquals(listOf(310L), merged.reconnectSamplesMs)
    }
}
