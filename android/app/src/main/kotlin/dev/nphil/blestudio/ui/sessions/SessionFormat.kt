package dev.nphil.blestudio.ui.sessions

import dev.nphil.blestudio.export.CommandAnalyzer
import dev.nphil.blestudio.model.AttOperation
import dev.nphil.blestudio.model.BleEvent
import dev.nphil.blestudio.model.EventDirection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private const val BASE_UUID_TAIL = "-0000-1000-8000-00805F9B34FB"

/**
 * `0000fff1-…-34fb` collapses to `FFF1`; anything else is shown uppercase and in full.
 *
 * Case-insensitive on purpose: the live-GATT and HCI paths store lowercase UUIDs while the relay
 * stores uppercase ones, and both end up in the same session.
 */
internal fun shortUuid(uuid: String?): String {
    val text = uuid?.trim().orEmpty()
    if (text.isEmpty()) return "—"
    val upper = text.uppercase()
    if (upper.length == 36 && upper.endsWith(BASE_UUID_TAIL) && upper.startsWith("0000")) {
        return upper.substring(4, 8)
    }
    return upper
}

/** Stable identity for the attribute an event touched, used for grouping and filter chips. */
internal fun eventChannelKey(event: BleEvent): String =
    CommandAnalyzer.groupingUuid(event.characteristicUuid)
        ?: event.attributeHandle?.let { handle -> "#%04X".format(handle) }
        ?: UNKNOWN_CHANNEL

internal const val UNKNOWN_CHANNEL = "unknown"

internal fun channelLabel(key: String): String = when {
    key == UNKNOWN_CHANNEL -> "No attribute"
    key.startsWith("#") -> "handle $key"
    else -> shortUuid(key)
}

internal fun hexGrouped(hex: String, maxBytes: Int = Int.MAX_VALUE): String {
    if (hex.isEmpty()) return ""
    val bytes = hex.length / 2
    val shown = minOf(bytes, maxBytes)
    val builder = StringBuilder(shown * 3 + 2)
    for (index in 0 until shown) {
        if (index > 0) builder.append(' ')
        builder.append(hex, index * 2, index * 2 + 2)
    }
    if (shown < bytes) builder.append(" +").append(bytes - shown)
    return builder.toString()
}

internal fun asciiOf(hex: String, maxBytes: Int = Int.MAX_VALUE): String {
    val bytes = minOf(hex.length / 2, maxBytes)
    val builder = StringBuilder(bytes)
    for (index in 0 until bytes) {
        val value = hex.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return builder.toString()
        builder.append(if (value in 0x20..0x7E) value.toChar() else '.')
    }
    return builder.toString()
}

internal fun formatClockMicros(micros: Long): String =
    CLOCK.format(Instant.ofEpochMilli(micros / 1_000).atZone(ZoneId.systemDefault()))

internal fun formatDateTime(epochMs: Long): String =
    DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** Signed offset from a reference point, e.g. `+1.284 s`. */
internal fun formatOffsetMicros(deltaMicros: Long): String {
    val sign = if (deltaMicros < 0) "-" else "+"
    val magnitude = abs(deltaMicros)
    return when {
        magnitude < 1_000 -> "$sign$magnitude µs"
        magnitude < 1_000_000 -> "$sign%.3f ms".format(magnitude / 1_000.0)
        else -> "$sign%.3f s".format(magnitude / 1_000_000.0)
    }
}

internal fun directionLabel(direction: EventDirection): String = when (direction) {
    EventDirection.PHONE_TO_DEVICE -> "Phone → device"
    EventDirection.DEVICE_TO_PHONE -> "Device → phone"
    EventDirection.LOCAL_TO_DEVICE -> "Studio → device"
    EventDirection.DEVICE_TO_LOCAL -> "Device → Studio"
    EventDirection.SYSTEM -> "System"
}

internal fun directionArrow(direction: EventDirection): String = when (direction) {
    EventDirection.PHONE_TO_DEVICE, EventDirection.LOCAL_TO_DEVICE -> "→"
    EventDirection.DEVICE_TO_PHONE, EventDirection.DEVICE_TO_LOCAL -> "←"
    EventDirection.SYSTEM -> "·"
}

internal fun operationLabel(operation: AttOperation): String = when (operation) {
    AttOperation.READ_REQUEST -> "Read"
    AttOperation.READ_RESPONSE -> "Read resp"
    AttOperation.WRITE_REQUEST -> "Write req"
    AttOperation.WRITE_RESPONSE -> "Write resp"
    AttOperation.WRITE_COMMAND -> "Write cmd"
    AttOperation.NOTIFICATION -> "Notify"
    AttOperation.INDICATION -> "Indicate"
    AttOperation.CONFIRMATION -> "Confirm"
    AttOperation.DISCOVERY -> "Discovery"
    AttOperation.ERROR -> "Error"
    AttOperation.OTHER -> "Other"
}

/** One inspector row: the same byte read every way an operator needs to read it. */
internal data class ByteRow(
    val offset: Int,
    val hex: String,
    val ascii: String,
    val unsigned: Int,
    val signed: Int,
    val uint16LittleEndian: Int?,
    val uint16BigEndian: Int?,
)

internal fun byteRows(hex: String): List<ByteRow> {
    val bytes = hex.length / 2
    if (bytes == 0) return emptyList()
    val values = IntArray(bytes) { index -> hex.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: 0 }
    return List(bytes) { index ->
        val value = values[index]
        val next = values.getOrNull(index + 1)
        ByteRow(
            offset = index,
            hex = hex.substring(index * 2, index * 2 + 2).uppercase(),
            ascii = if (value in 0x20..0x7E) value.toChar().toString() else "·",
            unsigned = value,
            signed = value.toByte().toInt(),
            uint16LittleEndian = next?.let { (it shl 8) or value },
            uint16BigEndian = next?.let { (value shl 8) or it },
        )
    }
}
