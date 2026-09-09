package dev.nphil.blestudio.ui.scan

import dev.nphil.blestudio.model.AdvertisementSample
import dev.nphil.blestudio.model.ConnectAttempt
import dev.nphil.blestudio.model.ConnectionFacts
import dev.nphil.blestudio.model.EventSource
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
        val first = mergeLiveFacts(ConnectionFacts(), live, mergedReconnects = 0)

        val second = mergeLiveFacts(first, live, mergedReconnects = 0)

        assertEquals(listOf(1_000L, 2_000L), second.connectAttempts.map { it.startedEpochMs })
    }

    @Test
    fun `only link timings recorded since the last save are appended`() {
        val afterFirstSave = mergeLiveFacts(
            base = ConnectionFacts(),
            live = ConnectionFacts(reconnectSamplesMs = listOf(310L, 290L)),
            mergedReconnects = 0,
        )

        // Two more connects happened; the live list still carries the first two.
        val afterSecondSave = mergeLiveFacts(
            base = afterFirstSave,
            live = ConnectionFacts(reconnectSamplesMs = listOf(310L, 290L, 640L, 305L)),
            mergedReconnects = 2,
        )

        assertEquals(listOf(310L, 290L), afterFirstSave.reconnectSamplesMs)
        assertEquals(listOf(310L, 290L, 640L, 305L), afterSecondSave.reconnectSamplesMs)
    }

    @Test
    fun `a live list shorter than the mark cannot slice out stored samples`() {
        val base = ConnectionFacts(reconnectSamplesMs = listOf(310L))

        // The GATT client was replaced (screen recreated), so its list starts over.
        val merged = mergeLiveFacts(base, ConnectionFacts(reconnectSamplesMs = emptyList()), mergedReconnects = 4)

        assertEquals(listOf(310L), merged.reconnectSamplesMs)
    }
}
