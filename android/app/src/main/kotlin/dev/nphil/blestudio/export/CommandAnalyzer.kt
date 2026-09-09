package dev.nphil.blestudio.export

import dev.nphil.blestudio.model.AttOperation
import dev.nphil.blestudio.model.BleEvent
import dev.nphil.blestudio.model.CaptureMarker
import dev.nphil.blestudio.model.CaptureSession
import dev.nphil.blestudio.model.EventDirection
import dev.nphil.blestudio.model.ResponseExpectation
import dev.nphil.blestudio.model.WriteType
import java.util.BitSet

/** A repeated outbound write that looks like a command worth cataloguing. */
data class SuggestedCommand(
    val characteristicUuid: String?,
    val attributeHandle: Int?,
    val serviceUuid: String?,
    val payloadHex: String,
    val writeType: WriteType,
    val count: Int,
    val firstSeenEpochMicros: Long,
    val lastSeenEpochMicros: Long,
    val sampleEventIds: List<String>,
    val nearestMarkerLabel: String?,
    val observedLatenciesMs: List<Long>,
    val response: ResponseExpectation?,
) {
    /** Label used when the suggestion is promoted into the catalogue. */
    val suggestedName: String
        get() = nearestMarkerLabel?.takeIf { it.isNotBlank() }
            ?: "Write ${payloadHex.take(12)}"
}

/**
 * Per-offset comparison of several payloads on one characteristic.
 *
 * [stableBytes] has a bit set for every offset that carries the same byte in every sample;
 * [varyingRanges] is the complement, collapsed into contiguous runs; [perOffsetValues] is
 * indexed by offset and lists the distinct values seen there, sorted for stable display.
 */
data class ByteDiff(
    val byteLength: Int,
    val stableBytes: BitSet,
    val varyingRanges: List<IntRange>,
    val perOffsetValues: List<List<String>>,
    val samples: List<String>,
    val ignoredSamples: Int,
    val ragged: Boolean,
)

/**
 * Turns raw capture traffic into command hypotheses.
 *
 * Deliberately free of Android imports so it is unit-testable and safe to run on a worker
 * dispatcher. Each analysis is one scan of the events plus a sort of the writes, the inbound
 * notifications and the markers; markers and responses are then located with binary search,
 * which keeps the whole pass O(n log n) and allocation-light.
 */
object CommandAnalyzer {
    /** Default distance either side of a write in which a capture marker still explains it. */
    const val DEFAULT_MARKER_WINDOW_MICROS = 3_000_000L

    /** A notification later than this after a write is not treated as its response. */
    const val RESPONSE_WINDOW_MICROS = 2_000_000L

    private const val MICROS_PER_MILLI = 1_000L
    private const val MIN_RESPONSE_TIMEOUT_MS = 1_000L
    private const val MAX_RESPONSE_TIMEOUT_MS = 30_000L

    fun suggest(
        session: CaptureSession,
        markerWindowMicros: Long = DEFAULT_MARKER_WINDOW_MICROS,
        responseWindowMicros: Long = RESPONSE_WINDOW_MICROS,
    ): List<SuggestedCommand> {
        val writes = ArrayList<BleEvent>()
        val inbound = ArrayList<BleEvent>()
        for (event in session.events) {
            when {
                isOutboundWrite(event) && event.payloadHex.isNotBlank() -> writes.add(event)
                isInboundNotification(event) -> inbound.add(event)
            }
        }
        if (writes.isEmpty()) return emptyList()

        writes.sortBy(BleEvent::timestampEpochMicros)
        inbound.sortBy(BleEvent::timestampEpochMicros)
        val inboundTimes = LongArray(inbound.size) { inbound[it].timestampEpochMicros }

        val markers = session.markers.sortedBy(CaptureMarker::timestampEpochMicros)
        val markerTimes = LongArray(markers.size) { markers[it].timestampEpochMicros }
        val markerLabelsById = HashMap<String, String>(markers.size * 2)
        for (marker in markers) markerLabelsById[marker.id] = marker.label

        val groups = LinkedHashMap<SuggestionKey, Accumulator>()
        for (write in writes) {
            val payload = HaProfileBuilder.normalizeHex(write.payloadHex)?.takeIf { it.isNotEmpty() }
                ?: continue
            val characteristic = groupingUuid(write.characteristicUuid)
            val key = SuggestionKey(
                characteristicUuid = characteristic,
                attributeHandle = if (characteristic == null) write.attributeHandle else null,
                payloadHex = payload,
            )
            val accumulator = groups.getOrPut(key) { Accumulator(key) }
            accumulator.add(write)

            val markerLabel = write.markerId?.let(markerLabelsById::get)
                ?: nearestMarkerIndex(markerTimes, write.timestampEpochMicros, markerWindowMicros)
                    .takeIf { it >= 0 }
                    ?.let { markers[it].label }
            if (markerLabel != null) accumulator.addMarker(markerLabel)

            val responseIndex = firstIndexAtOrAfter(inboundTimes, write.timestampEpochMicros)
            if (responseIndex < inbound.size &&
                inboundTimes[responseIndex] - write.timestampEpochMicros <= responseWindowMicros
            ) {
                accumulator.addResponse(inbound[responseIndex], write.timestampEpochMicros)
            }
        }
        return groups.values.map { it.build(session) }
    }

    /**
     * Compares payload samples byte by byte. Samples that are not whole hexadecimal bytes are
     * ignored rather than throwing, so a corrupt event cannot take the compare view down.
     */
    fun diff(samples: List<String>): ByteDiff {
        val normalized = ArrayList<String>(samples.size)
        var ignored = 0
        for (sample in samples) {
            val hex = HaProfileBuilder.normalizeHex(sample)
            if (hex == null || hex.isEmpty()) ignored++ else normalized.add(hex)
        }
        if (normalized.isEmpty()) {
            return ByteDiff(0, BitSet(0), emptyList(), emptyList(), emptyList(), ignored, false)
        }
        var minBytes = Int.MAX_VALUE
        var maxBytes = 0
        for (hex in normalized) {
            val bytes = hex.length / 2
            if (bytes < minBytes) minBytes = bytes
            if (bytes > maxBytes) maxBytes = bytes
        }
        val stable = BitSet(maxBytes)
        val perOffset = ArrayList<List<String>>(maxBytes)
        val varying = ArrayList<IntRange>()
        var runStart = -1
        for (offset in 0 until maxBytes) {
            val values = sortedSetOf<String>()
            var present = 0
            for (hex in normalized) {
                val at = offset * 2
                if (at + 2 > hex.length) continue
                present++
                values.add(hex.substring(at, at + 2))
            }
            perOffset.add(values.toList())
            val isStable = present == normalized.size && values.size == 1
            if (isStable) {
                stable.set(offset)
                if (runStart >= 0) {
                    varying.add(runStart until offset)
                    runStart = -1
                }
            } else if (runStart < 0) {
                runStart = offset
            }
        }
        if (runStart >= 0) varying.add(runStart until maxBytes)
        return ByteDiff(
            byteLength = maxBytes,
            stableBytes = stable,
            varyingRanges = varying,
            perOffsetValues = perOffset,
            samples = normalized,
            ignoredSamples = ignored,
            ragged = minBytes != maxBytes,
        )
    }

    /** Service that owns [characteristicUuid] according to the captured GATT database. */
    fun resolveServiceUuid(session: CaptureSession, characteristicUuid: String?): String? {
        val target = groupingUuid(characteristicUuid) ?: return null
        val services = session.gatt?.services ?: return null
        for (service in services) {
            for (characteristic in service.characteristics) {
                if (groupingUuid(characteristic.uuid) == target) return groupingUuid(service.uuid)
            }
        }
        return null
    }

    fun isOutboundWrite(event: BleEvent): Boolean =
        (event.direction == EventDirection.PHONE_TO_DEVICE || event.direction == EventDirection.LOCAL_TO_DEVICE) &&
            (event.operation == AttOperation.WRITE_REQUEST || event.operation == AttOperation.WRITE_COMMAND)

    fun isInboundNotification(event: BleEvent): Boolean =
        (event.direction == EventDirection.DEVICE_TO_PHONE || event.direction == EventDirection.DEVICE_TO_LOCAL) &&
            (event.operation == AttOperation.NOTIFICATION || event.operation == AttOperation.INDICATION)

    /**
     * Canonical UUID when parseable, otherwise the raw text lowercased so unparseable
     * identifiers still group and display consistently.
     */
    fun groupingUuid(raw: String?): String? =
        HaProfileBuilder.canonicalUuid(raw)
            ?: raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Index of the marker closest to [at] within [window], or -1. */
    internal fun nearestMarkerIndex(times: LongArray, at: Long, window: Long): Int {
        if (times.isEmpty()) return -1
        val found = times.binarySearch(at)
        if (found >= 0) return found
        val insert = -found - 1
        val before = insert - 1
        val distanceBefore = if (before >= 0) at - times[before] else Long.MAX_VALUE
        val distanceAfter = if (insert < times.size) times[insert] - at else Long.MAX_VALUE
        return when {
            distanceBefore <= distanceAfter && distanceBefore <= window -> before
            distanceAfter <= distanceBefore && distanceAfter <= window -> insert
            else -> -1
        }
    }

    /** Lowest index whose timestamp is >= [at], or `times.size`. */
    internal fun firstIndexAtOrAfter(times: LongArray, at: Long): Int {
        val found = times.binarySearch(at)
        if (found < 0) return -found - 1
        var index = found
        while (index > 0 && times[index - 1] == at) index--
        return index
    }

    /** Longest common prefix of [values], truncated to whole bytes; null when there is none. */
    internal fun longestCommonBytePrefix(values: List<String>): String? {
        if (values.isEmpty()) return null
        val first = values[0]
        var length = first.length
        for (index in 1 until values.size) {
            val other = values[index]
            val limit = minOf(length, other.length)
            var shared = 0
            while (shared < limit && first[shared] == other[shared]) shared++
            length = shared
            if (length == 0) break
        }
        length -= length % 2
        return if (length == 0) null else first.substring(0, length)
    }

    private data class SuggestionKey(
        val characteristicUuid: String?,
        val attributeHandle: Int?,
        val payloadHex: String,
    )

    private class Accumulator(private val key: SuggestionKey) {
        private val eventIds = ArrayList<String>(4)
        private val latencies = ArrayList<Long>(4)
        private val responsesByCharacteristic = LinkedHashMap<String, MutableList<String>>(2)
        private val markerVotes = LinkedHashMap<String, Int>(2)
        private val serviceVotes = LinkedHashMap<String, Int>(2)
        private var count = 0
        private var first = Long.MAX_VALUE
        private var last = Long.MIN_VALUE
        private var acknowledged = false
        private var indication = false

        fun add(event: BleEvent) {
            count++
            eventIds.add(event.id)
            if (event.timestampEpochMicros < first) first = event.timestampEpochMicros
            if (event.timestampEpochMicros > last) last = event.timestampEpochMicros
            if (event.operation == AttOperation.WRITE_REQUEST) acknowledged = true
            groupingUuid(event.serviceUuid)?.let { serviceVotes.merge(it, 1, Int::plus) }
        }

        fun addMarker(label: String) {
            markerVotes.merge(label, 1, Int::plus)
        }

        fun addResponse(event: BleEvent, writeAtMicros: Long) {
            val delta = event.timestampEpochMicros - writeAtMicros
            latencies.add((delta + MICROS_PER_MILLI / 2) / MICROS_PER_MILLI)
            if (event.operation == AttOperation.INDICATION) indication = true
            val characteristic = groupingUuid(event.characteristicUuid) ?: return
            val payload = HaProfileBuilder.normalizeHex(event.payloadHex) ?: return
            responsesByCharacteristic.getOrPut(characteristic) { ArrayList(4) }.add(payload)
        }

        fun build(session: CaptureSession): SuggestedCommand {
            val service = serviceVotes.maxByOrNull { it.value }?.key
                ?: resolveServiceUuid(session, key.characteristicUuid)
            val response = responsesByCharacteristic.maxByOrNull { it.value.size }?.let { entry ->
                ResponseExpectation(
                    characteristicUuid = entry.key,
                    payloadPrefixHex = longestCommonBytePrefix(entry.value),
                    timeoutMs = responseTimeoutMs(),
                    observedLatenciesMs = latencies.toList(),
                )
            }
            return SuggestedCommand(
                characteristicUuid = key.characteristicUuid,
                attributeHandle = key.attributeHandle,
                serviceUuid = service,
                payloadHex = key.payloadHex,
                writeType = if (acknowledged) WriteType.WITH_RESPONSE else WriteType.WITHOUT_RESPONSE,
                count = count,
                firstSeenEpochMicros = first,
                lastSeenEpochMicros = last,
                sampleEventIds = eventIds.toList(),
                nearestMarkerLabel = markerVotes.maxByOrNull { it.value }?.key,
                observedLatenciesMs = latencies.toList(),
                response = response,
            )
        }

        private fun responseTimeoutMs(): Long {
            val slowest = latencies.maxOrNull() ?: return MIN_RESPONSE_TIMEOUT_MS
            return (slowest * 3).coerceIn(MIN_RESPONSE_TIMEOUT_MS, MAX_RESPONSE_TIMEOUT_MS)
        }
    }
}
