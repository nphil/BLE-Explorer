package dev.nphil.blestudio.relay

/**
 * Allocation-conscious hex helpers for the relay hot path.
 *
 * [dev.nphil.blestudio.model.toHex] formats every byte through `String.format`, which allocates a
 * `Formatter` per byte; a MITM relay touches this code for every ATT packet, so the encoder here
 * writes straight into a `CharArray` instead. The output is identical (uppercase, no separators).
 */
private val HEX_DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')

internal fun ByteArray.toHexFast(): String {
    if (isEmpty()) return ""
    val out = CharArray(size * 2)
    var i = 0
    for (b in this) {
        val v = b.toInt() and 0xFF
        out[i++] = HEX_DIGITS[v ushr 4]
        out[i++] = HEX_DIGITS[v and 0x0F]
    }
    return String(out)
}

/** `"A102FF"` -> `"A1 02 FF"`, for readable timeline rows. */
internal fun String.groupedHex(): String {
    if (length < 3) return this
    val groups = length / 2
    val out = CharArray(groups * 3 - 1)
    var read = 0
    var write = 0
    while (read < groups * 2) {
        if (write > 0) out[write++] = ' '
        out[write++] = this[read++]
        out[write++] = this[read++]
    }
    return String(out)
}

/** ASCII gutter for a hex string: printable bytes verbatim, everything else `.`. */
internal fun String.asciiGutter(): String {
    val bytes = length / 2
    if (bytes == 0) return ""
    val out = CharArray(bytes)
    for (i in 0 until bytes) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        val v = if (hi < 0 || lo < 0) -1 else (hi shl 4) or lo
        out[i] = if (v in 0x20..0x7E) v.toChar() else '.'
    }
    return String(out)
}

private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805F9B34FB"

/** Renders assigned 16-bit UUIDs as `0x2A00` and leaves vendor 128-bit UUIDs intact. */
internal fun String.shortUuid(): String {
    val upper = uppercase()
    if (upper.length == 36 && upper.endsWith(BASE_UUID_SUFFIX)) {
        val short = upper.substring(0, 8)
        if (short.startsWith("0000")) return "0x" + short.substring(4)
        return "0x$short"
    }
    return upper
}
