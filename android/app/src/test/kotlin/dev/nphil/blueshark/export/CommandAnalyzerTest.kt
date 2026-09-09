package dev.nphil.blueshark.export

import dev.nphil.blueshark.model.AttOperation
import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.CaptureMarker
import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.EventDirection
import dev.nphil.blueshark.model.EventSource
import dev.nphil.blueshark.model.GattCharacteristicRecord
import dev.nphil.blueshark.model.GattDatabase
import dev.nphil.blueshark.model.GattServiceRecord
import dev.nphil.blueshark.model.WriteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandAnalyzerTest {
    private val base = 1_700_000_000_000_000L
    private val fff1 = "0000fff1-0000-1000-8000-00805f9b34fb"
    private val fff2 = "0000fff2-0000-1000-8000-00805f9b34fb"

    private fun write(
        id: String,
        atMicros: Long,
        payload: String,
        characteristic: String? = fff1,
        operation: AttOperation = AttOperation.WRITE_COMMAND,
        service: String? = null,
        handle: Int? = null,
        markerId: String? = null,
    ) = BleEvent(
        id = id,
        timestampEpochMicros = atMicros,
        direction = EventDirection.PHONE_TO_DEVICE,
        source = EventSource.HCI_SNOOP,
        operation = operation,
        serviceUuid = service,
        characteristicUuid = characteristic,
        attributeHandle = handle,
        payloadHex = payload,
        markerId = markerId,
    )

    private fun notification(
        id: String,
        atMicros: Long,
        payload: String,
        characteristic: String? = fff2,
    ) = BleEvent(
        id = id,
        timestampEpochMicros = atMicros,
        direction = EventDirection.DEVICE_TO_PHONE,
        source = EventSource.HCI_SNOOP,
        operation = AttOperation.NOTIFICATION,
        characteristicUuid = characteristic,
        payloadHex = payload,
    )

    private fun session(
        events: List<BleEvent>,
        markers: List<CaptureMarker> = emptyList(),
        gatt: GattDatabase? = null,
    ) = CaptureSession(
        id = "session",
        name = "Analysis",
        events = events,
        markers = markers,
        gatt = gatt,
    )

    @Test
    fun `groups identical writes per characteristic and payload keeping first seen order`() {
        val suggestions = CommandAnalyzer.suggest(
            session(
                listOf(
                    write("e1", base, "A5011E"),
                    write("e2", base + 500_000, "A5011E"),
                    write("e3", base + 900_000, "A50200"),
                    write("e4", base + 1_500_000, "A5011E"),
                    write("e5", base + 1_800_000, "A5011E", characteristic = fff2),
                ),
            ),
        )

        assertEquals(listOf(3, 1, 1), suggestions.map { it.count })
        assertEquals(listOf("A5011E", "A50200", "A5011E"), suggestions.map { it.payloadHex })
        assertEquals(listOf("e1", "e2", "e4"), suggestions.first().sampleEventIds)
        assertEquals(base, suggestions.first().firstSeenEpochMicros)
        assertEquals(base + 1_500_000, suggestions.first().lastSeenEpochMicros)
        assertEquals(WriteType.WITHOUT_RESPONSE, suggestions.first().writeType)
    }

    @Test
    fun `short and long forms of the same uuid group together`() {
        val suggestions = CommandAnalyzer.suggest(
            session(listOf(write("e1", base, "AA"), write("e2", base + 1, "AA", characteristic = "FFF1"))),
        )

        assertEquals(1, suggestions.size)
        assertEquals(2, suggestions.single().count)
        assertEquals(fff1, suggestions.single().characteristicUuid)
    }

    @Test
    fun `handle only writes group by handle`() {
        val suggestions = CommandAnalyzer.suggest(
            session(
                listOf(
                    write("e1", base, "AA", characteristic = null, handle = 0x0012),
                    write("e2", base + 10, "AA", characteristic = null, handle = 0x0014),
                ),
            ),
        )

        assertEquals(listOf(0x0012, 0x0014), suggestions.map { it.attributeHandle })
        assertTrue(suggestions.all { it.characteristicUuid == null })
    }

    @Test
    fun `a single acknowledged write makes the whole group acknowledged`() {
        val suggestions = CommandAnalyzer.suggest(
            session(
                listOf(
                    write("e1", base, "AA"),
                    write("e2", base + 10, "AA", operation = AttOperation.WRITE_REQUEST),
                ),
            ),
        )

        assertEquals(WriteType.WITH_RESPONSE, suggestions.single().writeType)
    }

    @Test
    fun `marker association includes the window boundary and excludes anything past it`() {
        val window = CommandAnalyzer.DEFAULT_MARKER_WINDOW_MICROS
        val onBoundary = CommandAnalyzer.suggest(
            session(
                listOf(write("e1", base, "AA")),
                markers = listOf(CaptureMarker("m1", base - window, "Pressed power")),
            ),
        )
        val justOutside = CommandAnalyzer.suggest(
            session(
                listOf(write("e1", base, "AA")),
                markers = listOf(CaptureMarker("m1", base - window - 1, "Pressed power")),
            ),
        )

        assertEquals("Pressed power", onBoundary.single().nearestMarkerLabel)
        assertNull(justOutside.single().nearestMarkerLabel)
    }

    @Test
    fun `nearest marker wins and an explicit marker id beats proximity`() {
        val markers = listOf(
            CaptureMarker("m1", base - 2_000_000, "Far"),
            CaptureMarker("m2", base + 400_000, "Near"),
        )
        val byProximity = CommandAnalyzer.suggest(session(listOf(write("e1", base, "AA")), markers))
        val byId = CommandAnalyzer.suggest(
            session(listOf(write("e1", base, "AA", markerId = "m1")), markers),
        )

        assertEquals("Near", byProximity.single().nearestMarkerLabel)
        assertEquals("Far", byId.single().nearestMarkerLabel)
        assertEquals("Far", byId.single().suggestedName)
    }

    @Test
    fun `pairs the first notification inside two seconds and derives the response prefix`() {
        val window = CommandAnalyzer.RESPONSE_WINDOW_MICROS
        val suggestions = CommandAnalyzer.suggest(
            session(
                listOf(
                    write("w1", base, "AA"),
                    notification("n1", base + 120_000, "01FF0A"),
                    notification("n2", base + 130_000, "01FF0B"),
                    write("w2", base + 10_000_000, "AA"),
                    notification("n3", base + 10_000_000 + window, "01FF0C"),
                    write("w3", base + 30_000_000, "AA"),
                    notification("n4", base + 30_000_000 + window + 1, "01FFFF"),
                ),
            ),
        )

        val suggestion = suggestions.single()
        assertEquals(listOf(120L, 2_000L), suggestion.observedLatenciesMs)
        val response = requireNotNull(suggestion.response)
        assertEquals(fff2, response.characteristicUuid)
        assertEquals("01FF", response.payloadPrefixHex)
        assertEquals(6_000L, response.timeoutMs)
    }

    @Test
    fun `service uuid falls back to the captured gatt database`() {
        val gatt = GattDatabase(
            services = listOf(
                GattServiceRecord(
                    uuid = "FFF0",
                    instanceId = 0,
                    type = "primary",
                    characteristics = listOf(
                        GattCharacteristicRecord(uuid = fff1, instanceId = 0, properties = listOf("WRITE"), permissions = emptyList()),
                    ),
                ),
            ),
        )
        val fromEvent = CommandAnalyzer.suggest(session(listOf(write("e1", base, "AA", service = "FFF0"))))
        val fromGatt = CommandAnalyzer.suggest(session(listOf(write("e1", base, "AA")), gatt = gatt))
        val unknown = CommandAnalyzer.suggest(session(listOf(write("e1", base, "AA"))))

        assertEquals("0000fff0-0000-1000-8000-00805f9b34fb", fromEvent.single().serviceUuid)
        assertEquals("0000fff0-0000-1000-8000-00805f9b34fb", fromGatt.single().serviceUuid)
        assertNull(unknown.single().serviceUuid)
    }

    @Test
    fun `reads and notifications are not command suggestions`() {
        val suggestions = CommandAnalyzer.suggest(
            session(
                listOf(
                    write("e1", base, "AA", operation = AttOperation.READ_REQUEST),
                    notification("e2", base + 10, "BB"),
                    write("e3", base + 20, "   "),
                ),
            ),
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `diff reports stable bytes varying ranges and per offset values`() {
        val diff = CommandAnalyzer.diff(listOf("A5 01 1E 00", "A5021E01", "A5031E02"))

        assertEquals(4, diff.byteLength)
        assertEquals(listOf(0, 2), diff.stableBytes.stream().toArray().toList())
        assertEquals(listOf(1..1, 3..3), diff.varyingRanges)
        assertEquals(listOf("A5"), diff.perOffsetValues[0])
        assertEquals(listOf("01", "02", "03"), diff.perOffsetValues[1])
        assertFalse(diff.ragged)
        assertEquals(0, diff.ignoredSamples)
    }

    @Test
    fun `diff treats a short sample as varying from the byte it stops at`() {
        val diff = CommandAnalyzer.diff(listOf("A5011E", "A501"))

        assertTrue(diff.ragged)
        assertEquals(listOf(2..2), diff.varyingRanges)
        assertEquals(listOf("1E"), diff.perOffsetValues[2])
    }

    @Test
    fun `diff ignores samples that are not whole hexadecimal bytes`() {
        val diff = CommandAnalyzer.diff(listOf("A501", "zz", "A5F", "", "A502"))

        assertEquals(listOf("A501", "A502"), diff.samples)
        assertEquals(3, diff.ignoredSamples)
        assertEquals(listOf(1..1), diff.varyingRanges)
    }

    @Test
    fun `diff of a single sample is entirely stable`() {
        val diff = CommandAnalyzer.diff(listOf("A5011E"))

        assertEquals(listOf(0, 1, 2), diff.stableBytes.stream().toArray().toList())
        assertTrue(diff.varyingRanges.isEmpty())
    }
}
