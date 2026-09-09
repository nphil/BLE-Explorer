package dev.nphil.blueshark.ui.capture

import dev.nphil.blueshark.hci.ConnectionSummary
import dev.nphil.blueshark.hci.formatMillis
import dev.nphil.blueshark.model.AttOperation
import dev.nphil.blueshark.model.EventDirection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure presentation of captured packets. Kept free of Compose so the rendering decisions can be
 * exercised without a device.
 */

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS").withZone(ZoneId.systemDefault())

private const val BLUETOOTH_BASE_SUFFIX = "-0000-1000-8000-00805F9B34FB"
private const val HEX_BYTES_SHOWN = 48

/** Wall clock with microsecond precision, so packet ordering is readable. */
internal fun formatCaptureTime(epochMicros: Long): String = runCatching {
    TIME_FORMAT.format(
        Instant.ofEpochSecond(
            Math.floorDiv(epochMicros, 1_000_000L),
            Math.floorMod(epochMicros, 1_000_000L) * 1_000L,
        ),
    )
}.getOrDefault("--:--:--")

/**
 * `0000fa02-0000-1000-8000-00805f9b34fb` reads better as `0xFA02` in a dense timeline.
 *
 * Case-insensitive: a session can hold lowercase UUIDs from the snoop parser next to uppercase
 * ones written by the relay.
 */
internal fun shortUuid(uuid: String): String {
    val upper = uuid.uppercase()
    if (upper.length != 36 || !upper.endsWith(BLUETOOTH_BASE_SUFFIX)) return uuid
    val short = upper.substring(0, 8)
    return if (short.startsWith("0000")) "0x${short.substring(4)}" else "0x$short"
}

internal fun formatHandle(handle: Int?): String = handle?.let { "0x%04X".format(it) } ?: "—"

/** Groups hex into byte pairs and stops well before a 512-byte payload floods the row. */
internal fun formatHexPayload(hex: String): String {
    if (hex.isEmpty()) return "—"
    val bytes = hex.length / 2
    val shown = minOf(bytes, HEX_BYTES_SHOWN)
    val builder = StringBuilder(shown * 3 + 16)
    for (index in 0 until shown) {
        if (index > 0) builder.append(' ')
        builder.append(hex, index * 2, index * 2 + 2)
    }
    if (bytes > shown) builder.append(" … +").append(bytes - shown).append(" bytes")
    return builder.toString()
}

/** One line of what a capture proved about a peer, for the collection report. */
internal fun peerDetail(peer: ConnectionSummary): String {
    val out = StringBuilder(96)
    peer.addressType?.let { out.append(it.lowercase()) }
    peer.intervalMs?.let { out.appendPart("interval ${formatMillis(it)}") }
    peer.supervisionTimeoutMs?.let { out.appendPart("timeout $it ms") }
    peer.mtu?.let { out.appendPart("MTU $it") }
    if (peer.encrypted) out.appendPart("encrypted")
    peer.pairingMethod?.let { out.appendPart(it.name.lowercase().replace('_', ' ')) }
    if (peer.bonded) out.appendPart("bonding")
    if (peer.creditBasedChannels) out.appendPart("credit-based channel")
    if (peer.handles.isNotEmpty()) {
        out.appendPart(peer.handles.joinToString(",", prefix = "handles ") { formatHandle(it) })
    }
    if (peer.disconnectReasons.isNotEmpty()) out.appendPart(peer.disconnectReasons.joinToString(", "))
    return out.toString()
}

private fun StringBuilder.appendPart(text: String) {
    if (isNotEmpty()) append(" · ")
    append(text)
}

internal fun AttOperation.label(): String = when (this) {
    AttOperation.READ_REQUEST -> "READ"
    AttOperation.READ_RESPONSE -> "READ RSP"
    AttOperation.WRITE_REQUEST -> "WRITE REQ"
    AttOperation.WRITE_RESPONSE -> "WRITE RSP"
    AttOperation.WRITE_COMMAND -> "WRITE CMD"
    AttOperation.NOTIFICATION -> "NOTIFY"
    AttOperation.INDICATION -> "INDICATE"
    AttOperation.CONFIRMATION -> "CONFIRM"
    AttOperation.DISCOVERY -> "DISCOVERY"
    AttOperation.ERROR -> "ERROR"
    AttOperation.OTHER -> "OTHER"
}

internal fun EventDirection.outbound(): Boolean =
    this == EventDirection.PHONE_TO_DEVICE || this == EventDirection.LOCAL_TO_DEVICE

internal fun EventDirection.describe(): String = when (this) {
    EventDirection.PHONE_TO_DEVICE -> "Phone to device"
    EventDirection.DEVICE_TO_PHONE -> "Device to phone"
    EventDirection.LOCAL_TO_DEVICE -> "BlueShark to device"
    EventDirection.DEVICE_TO_LOCAL -> "Device to BlueShark"
    EventDirection.SYSTEM -> "System"
}
