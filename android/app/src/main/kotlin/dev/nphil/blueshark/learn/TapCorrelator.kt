package dev.nphil.blueshark.learn

import dev.nphil.blueshark.model.AttOperation
import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.CaptureMarker
import dev.nphil.blueshark.model.EventDirection
import kotlin.math.roundToLong

/**
 * The state of one vendor-app control at the moment it was used.
 *
 * [checked] is not on [dev.nphil.blueshark.model.ControlRef]: the observer records identity and
 * geometry there, and puts the *state* in the marker label. So this field is parsed back out of
 * that label, and the grammar is a contract with the guided take-over
 * (`guide/ControlObserverService` via `ControlLabeler`):
 *
 * - checkable control: `"<label> -> on"` / `"<label> -> off"` (post-click state)
 * - range control: `"<label> <percent>%"`, raw value still in `ControlRef.rangeValue`
 * - editable control: `"<label>: '<text>'"`
 * - anything else: `"<label>"`
 *
 * If that grammar changes, [MarkerLabel] is the only place here that has to follow.
 */
data class ControlState(
    val label: String,
    val viewId: String,
    val screen: String,
    val checked: Boolean?,
    val rangeValue: Float?,
    val text: String,
)

/**
 * The writes one interaction is held responsible for.
 *
 * @param markerId the marker this belongs to, or `""` for the single bucket of writes that no
 *   interaction can explain.
 * @param eventIds ids of the attributed [BleEvent]s, in the order they went out.
 * @param payloads their payloads, in the same order and with duplicates kept: a control that
 *   writes the same frame twice is a fact about the protocol.
 * @param operations the ATT operation of each payload, parallel to [payloads]. Kept raw rather
 *   than reduced to a write type, because "the app used a command, not a request" is evidence in
 *   its own right; [CommandMapBuilder.fromAttributions] is what turns it into a write type.
 * @param characteristicUuid the characteristic every attributed write went to, or null when they
 *   disagree.
 * @param confidence 1.0 for a write that followed the tap immediately, falling to
 *   [TapCorrelator.EDGE_CONFIDENCE] at the far edge of the window and divided by the number of
 *   taps competing for the same writes. 0.0 for the unattributed bucket.
 * @param note [TapCorrelator.AMBIGUOUS_NOTE], [TapCorrelator.UNATTRIBUTED_NOTE] and
 *   [TapCorrelator.PEER_UNKNOWN_NOTE], joined by `"; "` when more than one applies.
 */
data class Attribution(
    val markerId: String,
    val control: ControlState,
    val eventIds: List<String>,
    val payloads: List<String>,
    val operations: List<AttOperation> = emptyList(),
    val characteristicUuid: String?,
    val confidence: Double,
    val note: String,
)

/** Label parsed into the control's name and the state the operator left it in. */
internal class ParsedLabel(val base: String, val state: String?, val checked: Boolean?)

/**
 * Reads the marker-label grammar the accessibility observer writes.
 *
 * Precedence is the observer's: checked, then range, then text. [ParsedLabel.base] is the label
 * without the state suffix, which is what a command should be named after - "Power switch", not
 * "Power switch -> on".
 */
internal object MarkerLabel {
    private val RANGE = Regex("^(.*?)\\s+(\\d{1,3})%$")
    private val TEXT = Regex("^(.*?):\\s*'(.*)'$")

    fun parse(label: String): ParsedLabel {
        val trimmed = label.trim()
        val lower = trimmed.lowercase()
        if (lower.endsWith("-> on")) return ParsedLabel(trimmed.dropLast(5).base(trimmed), "on", true)
        if (lower.endsWith("-> off")) return ParsedLabel(trimmed.dropLast(6).base(trimmed), "off", false)
        RANGE.matchEntire(trimmed)?.let { match ->
            return ParsedLabel(match.groupValues[1].base(trimmed), "${match.groupValues[2]}%", null)
        }
        TEXT.matchEntire(trimmed)?.let { match ->
            return ParsedLabel(match.groupValues[1].base(trimmed), match.groupValues[2], null)
        }
        return ParsedLabel(trimmed, null, null)
    }

    /** A label that is nothing but its state suffix still has to be called something. */
    private fun String.base(whole: String): String = trim().ifEmpty { whole }
}

/**
 * Blames writes on taps.
 *
 * A vendor app's write is caused by the interaction that preceded it, and the accessibility
 * observer knows what that interaction was. So the correlation is a time window: writes after a
 * marker, before the next marker, and inside [correlate]'s window belong to that marker. Three
 * things make this honest rather than merely convenient:
 *
 * - taps closer together than [AMBIGUITY_MICROS] cannot be told apart by a log with millisecond
 *   resolution and a stack that batches writes, so their attributions are *merged* and labelled
 *   ambiguous instead of being split on a coin toss;
 * - writes that no window covers are not dropped and not attached to the nearest tap - they are
 *   collected in one bucket with [UNATTRIBUTED_NOTE], because "the app also writes on its own"
 *   is exactly the kind of fact a captured session should surface;
 * - an HCI log holds every link the phone had open, so writes are filtered to the project's own
 *   device when [correlate]'s `targetAddress` is given, and an attribution that had to trust an
 *   event with no resolved peer says so with [PEER_UNKNOWN_NOTE].
 */
object TapCorrelator {

    /** Taps closer than this cannot be separated; their attributions merge. */
    const val AMBIGUITY_MICROS = 300_000L

    /** A write this soon after the tap is fully attributed. */
    const val FULL_CONFIDENCE_MICROS = 500_000L

    /** Confidence of a write sitting on the far edge of the window. */
    const val EDGE_CONFIDENCE = 0.4

    /** Note on a merged attribution; the taps that produced it cannot be separated. */
    const val AMBIGUOUS_NOTE = "ambiguous: two taps within 300 ms"

    /** Note on the single bucket of writes no interaction explains. */
    const val UNATTRIBUTED_NOTE = "unattributed"

    /**
     * Note on an attribution built from at least one event whose peer could not be resolved, while
     * a target address was being enforced: those writes may belong to another link.
     */
    const val PEER_UNKNOWN_NOTE = "peer unknown"

    /**
     * Label of the marker the overlay's Finish button drops
     * (`guide/GuideController.SESSION_FINISHED_MARKER`). It closes the previous tap's window and
     * never takes writes of its own: it is the operator leaving the vendor app, not a control.
     */
    const val SESSION_END_LABEL = "session finished"

    private val EMPTY_CONTROL = ControlState(
        label = "",
        viewId = "",
        screen = "",
        checked = null,
        rangeValue = null,
        text = "",
    )

    /**
     * @param windowMicros how long after a tap a write can still be caused by it. The default of
     *   three seconds is the round trip a vendor app needs to connect, discover and write on a
     *   device it was not already talking to.
     * @param targetAddress when given, only writes whose [BleEvent.peerAddress] is that address
     *   (case-insensitively) or is unresolved take part; a write that provably went to another
     *   peer is not evidence about this project and is left out entirely.
     */
    fun correlate(
        markers: List<CaptureMarker>,
        events: List<BleEvent>,
        windowMicros: Long = 3_000_000,
        targetAddress: String? = null,
    ): List<Attribution> {
        val target = targetAddress?.trim()?.takeIf { it.isNotEmpty() }
        val groups = group(markers.sortedBy { it.timestampEpochMicros })
        val writes = events
            .filter {
                it.direction == EventDirection.PHONE_TO_DEVICE && it.operation.isWrite() && it.onTarget(target)
            }
            .sortedBy { it.timestampEpochMicros }
        val buckets = List(groups.size) { ArrayList<BleEvent>() }
        val unattributed = ArrayList<BleEvent>()

        // Both lists are in time order, so the group a write can belong to only ever moves forward.
        var index = -1
        for (write in writes) {
            val at = write.timestampEpochMicros
            while (index + 1 < groups.size && groups[index + 1].start < at) index++
            val group = groups.getOrNull(index)
            if (group == null || !group.receiving) {
                unattributed += write
                continue
            }
            val nextStart = groups.getOrNull(index + 1)?.start ?: Long.MAX_VALUE
            val deadline = minOf(group.end + windowMicros, nextStart)
            if (at <= deadline) buckets[index] += write else unattributed += write
        }

        val attributions = ArrayList<Attribution>(groups.size + 1)
        for ((position, group) in groups.withIndex()) {
            val bucket = buckets[position]
            if (bucket.isEmpty()) continue
            attributions += Attribution(
                markerId = group.anchor.id,
                control = group.controlState(),
                eventIds = bucket.map { it.id },
                payloads = bucket.map { it.payloadHex },
                operations = bucket.map { it.operation },
                characteristicUuid = sharedCharacteristic(bucket),
                confidence = confidence(group, bucket.first(), windowMicros),
                note = note(if (group.taps > 1) AMBIGUOUS_NOTE else null, bucket, target),
            )
        }
        if (unattributed.isNotEmpty()) {
            attributions += Attribution(
                markerId = "",
                control = EMPTY_CONTROL,
                eventIds = unattributed.map { it.id },
                payloads = unattributed.map { it.payloadHex },
                operations = unattributed.map { it.operation },
                characteristicUuid = sharedCharacteristic(unattributed),
                confidence = 0.0,
                note = note(UNATTRIBUTED_NOTE, unattributed, target),
            )
        }
        return attributions
    }

    private fun AttOperation.isWrite(): Boolean =
        this == AttOperation.WRITE_REQUEST || this == AttOperation.WRITE_COMMAND

    /** An event with no resolved peer is kept - the log may predate connection tracking - but noted. */
    private fun BleEvent.onTarget(target: String?): Boolean =
        target == null || peerAddress == null || peerAddress.equals(target, ignoreCase = true)

    private fun note(head: String?, events: List<BleEvent>, target: String?): String {
        val peerUnknown = target != null && events.any { it.peerAddress == null }
        return when {
            head != null && peerUnknown -> "$head; $PEER_UNKNOWN_NOTE"
            head != null -> head
            peerUnknown -> PEER_UNKNOWN_NOTE
            else -> ""
        }
    }

    /** Consecutive markers less than [AMBIGUITY_MICROS] apart, as one indivisible interaction. */
    private fun CaptureMarker.isSessionEndMarker(): Boolean =
        control == null && label.trim().equals(SESSION_END_LABEL, ignoreCase = true)

    private fun group(sorted: List<CaptureMarker>): List<MarkerGroup> {
        val groups = ArrayList<MarkerGroup>(sorted.size)
        var current = ArrayList<CaptureMarker>(2)
        for (marker in sorted) {
            val previous = current.lastOrNull()
            // The session-end sentinel is a hard boundary in both directions: it never joins the
            // tap before it (a Finish 200 ms after a tap must still close that tap's window, or
            // whatever the app wrote after Finish becomes a learned command) and nothing joins it.
            val boundary = marker.isSessionEndMarker() || previous?.isSessionEndMarker() == true
            if (previous != null &&
                (boundary || marker.timestampEpochMicros - previous.timestampEpochMicros >= AMBIGUITY_MICROS)
            ) {
                groups += MarkerGroup(current)
                current = ArrayList(2)
            }
            current += marker
        }
        if (current.isNotEmpty()) groups += MarkerGroup(current)
        return groups
    }

    private fun sharedCharacteristic(events: List<BleEvent>): String? {
        val distinct = events.mapTo(LinkedHashSet(2)) { it.characteristicUuid }
        return distinct.singleOrNull()
    }

    private fun confidence(group: MarkerGroup, first: BleEvent, windowMicros: Long): Double {
        val delay = first.timestampEpochMicros - group.anchor.timestampEpochMicros
        val base = if (delay <= FULL_CONFIDENCE_MICROS) {
            1.0
        } else {
            val span = (windowMicros - FULL_CONFIDENCE_MICROS).coerceAtLeast(1L)
            1.0 - (1.0 - EDGE_CONFIDENCE) * (delay - FULL_CONFIDENCE_MICROS).toDouble() / span
        }
        val competing = group.taps.coerceAtLeast(1)
        return round(base.coerceIn(EDGE_CONFIDENCE, 1.0) / competing)
    }

    private fun round(value: Double): Double = (value * 10_000).roundToLong() / 10_000.0

    /**
     * One interaction: usually a single marker, several when they are too close to separate.
     * The session-end sentinel bounds a window like any marker but never receives writes.
     */
    private class MarkerGroup(val markers: List<CaptureMarker>) {
        val start: Long = markers.first().timestampEpochMicros
        val end: Long = markers.last().timestampEpochMicros
        private val controls: List<CaptureMarker> = markers.filterNot { it.isSessionEnd() }
        val taps: Int = controls.size
        val receiving: Boolean = controls.isNotEmpty()
        val anchor: CaptureMarker = controls.firstOrNull() ?: markers.first()

        fun controlState(): ControlState {
            val parsed = controls.map { MarkerLabel.parse(it.label) }
            val reference = controls.firstNotNullOfOrNull { it.control }
            return ControlState(
                label = controls.joinToString(" | ") { it.label.trim() },
                viewId = reference?.viewId.orEmpty(),
                screen = reference?.screen.orEmpty(),
                checked = parsed.mapNotNull { it.checked }.distinct().singleOrNull(),
                rangeValue = controls.firstNotNullOfOrNull { it.control?.rangeValue },
                text = reference?.let { it.text.ifBlank { it.contentDescription } }.orEmpty(),
            )
        }

        private fun CaptureMarker.isSessionEnd(): Boolean = isSessionEndMarker()
    }
}
