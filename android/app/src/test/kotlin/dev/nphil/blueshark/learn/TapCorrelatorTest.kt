package dev.nphil.blueshark.learn

import dev.nphil.blueshark.model.AttOperation
import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.CaptureMarker
import dev.nphil.blueshark.model.ControlRef
import dev.nphil.blueshark.model.EventDirection
import dev.nphil.blueshark.model.EventSource
import dev.nphil.blueshark.model.MarkerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapCorrelatorTest {

    private val fff1 = "0000fff1-0000-1000-8000-00805f9b34fb"

    private fun tap(
        id: String,
        atMicros: Long,
        label: String,
        control: ControlRef? = ControlRef(
            packageName = "com.coolled.app",
            screen = "MainActivity",
            viewId = "com.coolled.app:id/power",
            className = "android.widget.Switch",
            text = "Power",
        ),
    ) = CaptureMarker(
        id = id,
        timestampEpochMicros = atMicros,
        label = label,
        source = MarkerSource.ACCESSIBILITY,
        control = control,
    )

    private fun write(
        id: String,
        atMicros: Long,
        payload: String,
        characteristic: String? = fff1,
        operation: AttOperation = AttOperation.WRITE_COMMAND,
        direction: EventDirection = EventDirection.PHONE_TO_DEVICE,
        peer: String? = null,
    ) = BleEvent(
        id = id,
        timestampEpochMicros = atMicros,
        direction = direction,
        source = EventSource.HCI_SNOOP,
        operation = operation,
        characteristicUuid = characteristic,
        payloadHex = payload,
        peerAddress = peer,
    )

    @Test
    fun `a write to another peer inside the window is not attributed`() {
        val attributions = TapCorrelator.correlate(
            markers = listOf(tap("m1", 1_000_000, "Power switch -> on")),
            events = listOf(
                write("mine", 1_100_000, "AA", peer = "aa:bb:cc:dd:ee:ff"),
                write("theirs", 1_150_000, "BB", peer = "11:22:33:44:55:66"),
            ),
            targetAddress = "AA:BB:CC:DD:EE:FF",
        )

        val attribution = attributions.single()
        assertEquals(listOf("mine"), attribution.eventIds)
        assertEquals(listOf("AA"), attribution.payloads)
        // The other peer's write is not evidence about this project at all, not even unattributed.
        assertTrue(attributions.none { "theirs" in it.eventIds })
        assertEquals("", attribution.note)
    }

    @Test
    fun `an event with no resolved peer is used but the attribution says so`() {
        val attributions = TapCorrelator.correlate(
            markers = listOf(tap("m1", 1_000_000, "Power switch -> on")),
            events = listOf(write("unknown", 1_100_000, "AA", peer = null)),
            targetAddress = "AA:BB:CC:DD:EE:FF",
        )

        assertEquals(TapCorrelator.PEER_UNKNOWN_NOTE, attributions.single().note)
    }

    @Test
    fun `an ambiguous attribution built on an unresolved peer carries both notes`() {
        val attributions = TapCorrelator.correlate(
            markers = listOf(
                tap("m1", 1_000_000, "Power switch -> on"),
                tap("m2", 1_200_000, "Brightness 62%", control = null),
            ),
            events = listOf(write("unknown", 1_400_000, "AA", peer = null)),
            targetAddress = "AA:BB:CC:DD:EE:FF",
        )

        assertEquals(
            "${TapCorrelator.AMBIGUOUS_NOTE}; ${TapCorrelator.PEER_UNKNOWN_NOTE}",
            attributions.single().note,
        )
    }

    @Test
    fun `the ATT operation of every attributed write is kept`() {
        val attribution = TapCorrelator.correlate(
            markers = listOf(tap("m1", 1_000_000, "Power switch -> on")),
            events = listOf(
                write("e1", 1_100_000, "AA", operation = AttOperation.WRITE_REQUEST),
                write("e2", 1_200_000, "BB", operation = AttOperation.WRITE_COMMAND),
            ),
        ).single()

        assertEquals(
            listOf(AttOperation.WRITE_REQUEST, AttOperation.WRITE_COMMAND),
            attribution.operations,
        )
    }

    @Test
    fun `one tap with two writes becomes one attribution carrying both payloads`() {
        val attributions = TapCorrelator.correlate(
            markers = listOf(tap("m1", 1_000_000, "Power switch -> on")),
            events = listOf(
                write("e1", 1_100_000, "010204020608FF03"),
                write("e2", 1_250_000, "0102040206FF0003", operation = AttOperation.WRITE_REQUEST),
                // Neither of these is a phone-to-device write, so neither may be attributed.
                write("e3", 1_300_000, "0100", direction = EventDirection.DEVICE_TO_PHONE, operation = AttOperation.NOTIFICATION),
                write("e4", 1_350_000, "", operation = AttOperation.READ_REQUEST),
            ),
        )

        val attribution = attributions.single()
        assertEquals("m1", attribution.markerId)
        assertEquals(listOf("e1", "e2"), attribution.eventIds)
        assertEquals(listOf("010204020608FF03", "0102040206FF0003"), attribution.payloads)
        assertEquals(fff1, attribution.characteristicUuid)
        assertEquals(1.0, attribution.confidence, 0.0001)
        assertEquals("", attribution.note)
        assertEquals(true, attribution.control.checked)
        assertEquals("Power switch -> on", attribution.control.label)
        assertEquals("com.coolled.app:id/power", attribution.control.viewId)
        assertEquals("MainActivity", attribution.control.screen)
    }

    @Test
    fun `two taps 200 ms apart merge into one ambiguous attribution`() {
        val attributions = TapCorrelator.correlate(
            markers = listOf(
                tap("m1", 1_000_000, "Power switch -> on"),
                tap("m2", 1_200_000, "Brightness 62%", control = null),
            ),
            events = listOf(write("e1", 1_400_000, "A5011E")),
        )

        val attribution = attributions.single()
        assertEquals("m1", attribution.markerId)
        assertEquals(TapCorrelator.AMBIGUOUS_NOTE, attribution.note)
        assertEquals("Power switch -> on | Brightness 62%", attribution.control.label)
        assertEquals(true, attribution.control.checked)
        // One write, two candidate taps: at most half the confidence of an unambiguous tap.
        assertEquals(0.5, attribution.confidence, 0.0001)
    }

    @Test
    fun `taps 300 ms apart are still told apart`() {
        val attributions = TapCorrelator.correlate(
            markers = listOf(
                tap("m1", 1_000_000, "Power switch -> on"),
                tap("m2", 1_300_000, "Power switch -> off"),
            ),
            events = listOf(write("e1", 1_100_000, "AA"), write("e2", 1_400_000, "BB")),
        )

        assertEquals(listOf("m1", "m2"), attributions.map { it.markerId })
        assertEquals(listOf("AA"), attributions[0].payloads)
        assertEquals(listOf("BB"), attributions[1].payloads)
        assertEquals(false, attributions[1].control.checked)
        assertEquals("", attributions[0].note)
    }

    @Test
    fun `writes no tap explains land in one unattributed bucket`() {
        val attributions = TapCorrelator.correlate(
            markers = listOf(tap("m1", 5_000_000, "Power switch -> on")),
            events = listOf(
                write("before", 1_000_000, "AA"),
                write("during", 5_100_000, "BB"),
                write("after", 9_000_000, "CC", characteristic = "0000fff2-0000-1000-8000-00805f9b34fb"),
            ),
        )

        assertEquals(2, attributions.size)
        assertEquals(listOf("during"), attributions[0].eventIds)
        val orphans = attributions[1]
        assertEquals("", orphans.markerId)
        assertEquals(TapCorrelator.UNATTRIBUTED_NOTE, orphans.note)
        assertEquals(listOf("before", "after"), orphans.eventIds)
        assertEquals(listOf("AA", "CC"), orphans.payloads)
        assertEquals(0.0, orphans.confidence, 0.0001)
        // The two writes went to different characteristics, so there is no single one to report.
        assertNull(orphans.characteristicUuid)
        assertEquals("", orphans.control.label)
    }

    @Test
    fun `confidence falls from the half-second grace period to the window edge`() {
        fun confidenceAt(offsetMicros: Long): Double = TapCorrelator.correlate(
            markers = listOf(tap("m1", 0, "Power switch -> on")),
            events = listOf(write("e1", offsetMicros, "AA")),
        ).single().confidence

        assertEquals(1.0, confidenceAt(1), 0.0001)
        assertEquals(1.0, confidenceAt(TapCorrelator.FULL_CONFIDENCE_MICROS), 0.0001)
        assertEquals(0.7, confidenceAt(1_750_000), 0.0001)
        assertEquals(TapCorrelator.EDGE_CONFIDENCE, confidenceAt(3_000_000), 0.0001)
    }

    @Test
    fun `a write past the window edge is unattributed rather than weakly attributed`() {
        val attributions = TapCorrelator.correlate(
            markers = listOf(tap("m1", 0, "Power switch -> on")),
            events = listOf(write("e1", 3_000_001, "AA")),
        )

        assertEquals(TapCorrelator.UNATTRIBUTED_NOTE, attributions.single().note)
    }

    @Test
    fun `the session-end marker closes the last window and takes no writes`() {
        val attributions = TapCorrelator.correlate(
            markers = listOf(
                tap("m1", 1_000_000, "Power switch -> on"),
                tap("finish", 2_000_000, TapCorrelator.SESSION_END_LABEL, control = null),
            ),
            events = listOf(write("e1", 1_100_000, "AA"), write("e2", 2_500_000, "BB")),
        )

        assertEquals(2, attributions.size)
        assertEquals("m1", attributions[0].markerId)
        assertEquals(listOf("AA"), attributions[0].payloads)
        assertEquals(TapCorrelator.UNATTRIBUTED_NOTE, attributions[1].note)
        assertEquals(listOf("BB"), attributions[1].payloads)
    }

    @Test
    fun `a slider tap keeps the raw range value and the labelled percentage`() {
        val slider = ControlRef(
            packageName = "com.coolled.app",
            screen = "Brightness",
            viewId = "com.coolled.app:id/seek",
            className = "android.widget.SeekBar",
            rangeValue = 158f,
        )

        val attribution = TapCorrelator.correlate(
            markers = listOf(tap("m1", 1_000_000, "Brightness 62%", control = slider)),
            events = listOf(write("e1", 1_050_000, "A5023E")),
        ).single()

        assertEquals(158f, attribution.control.rangeValue!!, 0.0001f)
        assertNull(attribution.control.checked)
        assertTrue(attribution.control.label.endsWith("62%"))
    }

    @Test
    fun `markers arriving out of order are sorted before they are used`() {
        val attributions = TapCorrelator.correlate(
            markers = listOf(
                tap("later", 5_000_000, "Power switch -> off"),
                tap("earlier", 1_000_000, "Power switch -> on"),
            ),
            events = listOf(write("e1", 5_100_000, "BB"), write("e2", 1_100_000, "AA")),
        )

        assertEquals(listOf("earlier", "later"), attributions.map { it.markerId })
        assertEquals(listOf("AA"), attributions[0].payloads)
        assertEquals(listOf("BB"), attributions[1].payloads)
    }

    @Test
    fun `a Finish inside the ambiguity window still closes the tap, so post-Finish writes are not learned`() {
        val result = TapCorrelator.correlate(
            markers = listOf(
                tap("power", 1_000_000, "Power -> on"),
                tap("finish", 1_200_000, TapCorrelator.SESSION_END_LABEL, control = null),
            ),
            events = listOf(write("late", 1_300_000, "010204020608FF03")),
        )
        val learned = result.filter { it.markerId.isNotEmpty() }
        assertTrue("post-Finish write must not be attributed: $result", learned.all { it.payloads.isEmpty() })
        val loose = result.firstOrNull { it.markerId.isEmpty() }
        assertEquals(listOf("010204020608FF03"), loose?.payloads ?: emptyList<String>())
    }
}
