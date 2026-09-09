package dev.nphil.blueshark.probe

/** A framing scheme: how a logical payload becomes the bytes written to a characteristic. */
interface FrameCodec {
    /** Stable identifier, safe to persist and to select a codec by. */
    val id: String

    /** Short human name for a picker or a report header. */
    val label: String

    fun encode(payload: ByteArray): ByteArray

    /** The payload carried by [frame], or null when the bytes are not a valid frame. */
    fun decode(frame: ByteArray): ByteArray?
}

/** Every codec the prober can offer, in the order a picker should show them. */
object FrameCodecs {
    val all: List<FrameCodec> = listOf(CoolLedCodec, RawCodec)

    fun byId(id: String): FrameCodec? = all.firstOrNull { it.id == id }
}

private const val START: Byte = 0x01
private const val ESCAPE: Byte = 0x02
private const val END: Byte = 0x03

/** Bytes below this need escaping: they collide with the start, escape and end markers. */
private const val ESCAPE_BELOW = 0x04

/** An escaped byte is transmitted as `0x02, byte + 0x04`. */
private const val ESCAPE_BIAS = 0x04

/** `len:BE16` sits in front of the payload, inside the escaped region. */
private const val LENGTH_BYTES = 2

/** start + at least one body byte + end; anything shorter cannot carry a length prefix. */
private const val MIN_FRAME = 3

private const val MAX_PAYLOAD = 0xFFFF

/**
 * The CoolLED1248 / iLedClock family framing: `0x01 <len:BE16> <escaped payload> 0x03`.
 *
 * The length prefix is itself inside the escaped region — that is not an aesthetic detail, it is
 * why a two-byte payload comes out as eight bytes: `len = 0x0000_0002` contains three bytes below
 * `0x04`, each of which doubles. Verified against hardware: payload `08 FF` encodes to
 * `01 02 04 02 06 08 FF 03`.
 *
 * [decode] is deliberately strict about structure (markers, declared length) because
 * [ProbeInterpreter] uses "did this decode" as its ERROR oracle, and lenient about the escaped
 * value itself: a device that escapes a byte outside `0x00..0x03` is unusual, but reading its
 * status byte is more useful than discarding the whole response.
 */
object CoolLedCodec : FrameCodec {

    override val id: String = "coolled"

    override val label: String = "CoolLED framed (0x01 len esc 0x03)"

    override fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "Payload exceeds the 16-bit length prefix: ${payload.size}" }
        val high = (payload.size ushr 8) and 0xFF
        val low = payload.size and 0xFF
        var escapes = 0
        if (high < ESCAPE_BELOW) escapes++
        if (low < ESCAPE_BELOW) escapes++
        for (byte in payload) if ((byte.toInt() and 0xFF) < ESCAPE_BELOW) escapes++

        val frame = ByteArray(2 + LENGTH_BYTES + payload.size + escapes)
        frame[0] = START
        var at = frame.putEscaped(1, high)
        at = frame.putEscaped(at, low)
        for (byte in payload) at = frame.putEscaped(at, byte.toInt() and 0xFF)
        frame[at] = END
        return frame
    }

    override fun decode(frame: ByteArray): ByteArray? {
        if (frame.size < MIN_FRAME || frame[0] != START || frame[frame.size - 1] != END) return null
        val end = frame.size - 1

        // First pass: validate the escapes and size the payload exactly.
        var escapes = 0
        var index = 1
        while (index < end) {
            if (frame[index] == ESCAPE) {
                escapes++
                index += 2
                if (index > end) return null // escape marker with nothing to escape
            } else {
                index++
            }
        }
        val bodyLength = (end - 1) - escapes
        if (bodyLength < LENGTH_BYTES) return null

        // Second pass: unescape into the length prefix and the payload.
        val payload = ByteArray(bodyLength - LENGTH_BYTES)
        var declared = 0
        var seen = 0
        index = 1
        while (index < end) {
            var value = frame[index].toInt() and 0xFF
            if (value == ESCAPE.toInt()) {
                index++
                value = (frame[index].toInt() and 0xFF) - ESCAPE_BIAS
            }
            index++
            if (seen < LENGTH_BYTES) declared = (declared shl 8) or (value and 0xFF) else payload[seen - LENGTH_BYTES] = value.toByte()
            seen++
        }
        if (declared != payload.size) return null
        return payload
    }

    private fun ByteArray.putEscaped(at: Int, value: Int): Int {
        if (value >= ESCAPE_BELOW) {
            this[at] = value.toByte()
            return at + 1
        }
        this[at] = ESCAPE
        this[at + 1] = (value + ESCAPE_BIAS).toByte()
        return at + 2
    }
}

/**
 * No framing: the payload is written verbatim and every notification is taken as the response.
 *
 * Both directions hand back the argument itself rather than a copy — these arrays are built per
 * step and never retained, and the prober does not mutate them.
 */
object RawCodec : FrameCodec {

    override val id: String = "raw"

    override val label: String = "Raw bytes (no framing)"

    override fun encode(payload: ByteArray): ByteArray = payload

    override fun decode(frame: ByteArray): ByteArray = frame
}
