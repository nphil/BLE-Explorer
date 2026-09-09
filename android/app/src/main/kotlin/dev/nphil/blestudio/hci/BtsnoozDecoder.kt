package dev.nphil.blestudio.hci

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** What a btsnooz block turned into, so the UI can explain where the events came from. */
data class BtsnoozResult(
    val version: Int,
    val records: Int,
    val bytesWritten: Long,
    val firstEpochMs: Long,
    val lastEpochMs: Long,
    val timestampBase: String,
    val warnings: List<String>,
)

/**
 * Kotlin port of AOSP `system/tools/scripts/btsnooz.py`.
 *
 * Bugreports rarely contain the raw snoop file; what they reliably contain is a base64 block
 * between `--- BEGIN:BTSNOOP_LOG_SUMMARY` and `--- END:BTSNOOP_LOG_SUMMARY` holding a "btsnooz"
 * stream: a 9-byte plaintext header (version byte + last timestamp) followed by zlib-compressed
 * records. [decodeTo] rewrites it as a standard btsnoop file that [BtsnoopParser] can read.
 *
 * One deliberate deviation from the Python script: AOSP writes the millisecond timestamp into the
 * microsecond btsnoop field (and adds a microsecond epoch delta to a millisecond value), which
 * makes absolute times in its output meaningless. This port recovers the real Unix time instead,
 * so HCI events line up with the operator's capture markers.
 */
object BtsnoozDecoder {

    const val BEGIN_MARKER = "--- BEGIN:BTSNOOP_LOG_SUMMARY"
    const val END_MARKER = "--- END:BTSNOOP_LOG_SUMMARY"

    private const val HEADER_SIZE = 9
    private const val MAX_BASE64_CHARS = 8L * 1024 * 1024
    private const val MAX_INFLATED_BYTES = 64L * 1024 * 1024
    private const val INFLATE_CHUNK = 64 * 1024

    /** Snoop-internal packet types, from the script's enumeration. */
    private const val TYPE_IN_EVT = 0x10
    private const val TYPE_IN_ACL = 0x11
    private const val TYPE_IN_SCO = 0x12
    private const val TYPE_IN_ISO = 0x17
    private const val TYPE_OUT_CMD = 0x20
    private const val TYPE_OUT_ACL = 0x21
    private const val TYPE_OUT_SCO = 0x22
    private const val TYPE_OUT_ISO = 0x2D

    /** 2005-01-01: anything older than this is not a real capture clock. */
    private const val MIN_PLAUSIBLE_MS = 1_104_537_600_000L
    private const val EPOCH_DELTA_MS = BTSNOOP_EPOCH_DELTA_MICROS / 1000

    private val BTSNOOP_HEADER = byteArrayOf(
        0x62, 0x74, 0x73, 0x6E, 0x6F, 0x6F, 0x70, 0x00, // "btsnoop\0"
        0x00, 0x00, 0x00, 0x01, // version 1
        0x00, 0x00, 0x03, 0xEA.toByte(), // datalink 1002 (H4)
    )

    /**
     * Scans a bugreport text stream for the btsnooz block and returns its decoded bytes, or null
     * when the markers are absent. Reads line by line, so a multi-hundred-megabyte bugreport costs
     * only the size of the block itself.
     */
    fun extract(input: InputStream): ByteArray? {
        // Bugreports are mostly UTF-8 but can carry stray bytes; ISO-8859-1 never throws and
        // base64 is ASCII, so the block itself survives byte for byte.
        val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1), 64 * 1024)
        var collecting = false
        val base64 = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            if (!collecting) {
                if (line.contains(BEGIN_MARKER)) collecting = true
                continue
            }
            if (line.contains(END_MARKER)) {
                return if (base64.isEmpty()) null else Base64.getMimeDecoder().decode(base64.toString())
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (base64.length + trimmed.length > MAX_BASE64_CHARS) {
                throw BtsnoopFormatException("btsnooz block exceeds ${MAX_BASE64_CHARS / (1024 * 1024)} MiB")
            }
            base64.append(trimmed)
        }
        // Reached EOF inside the block: AOSP truncates bugreports, so keep what we have.
        return if (collecting && base64.isNotEmpty()) Base64.getMimeDecoder().decode(base64.toString()) else null
    }

    /** True when [input] looks like it contains a btsnooz block, without decoding it. */
    fun looksLikeBugreportText(input: InputStream, scanLimitBytes: Long = 512L * 1024 * 1024): Boolean {
        val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1), 64 * 1024)
        var read = 0L
        while (read < scanLimitBytes) {
            val line = reader.readLine() ?: return false
            read += line.length + 1
            if (line.contains(BEGIN_MARKER)) return true
        }
        return false
    }

    /** Decodes raw btsnooz bytes into a standard btsnoop stream written to [out]. */
    fun decodeTo(snooz: ByteArray, out: OutputStream): BtsnoozResult {
        if (snooz.size <= HEADER_SIZE) {
            throw BtsnoopFormatException("btsnooz block is only ${snooz.size} bytes")
        }
        val version = snooz[0].toInt() and 0xFF
        if (version != 1 && version != 2) {
            throw BtsnoopFormatException("Unsupported btsnooz version $version")
        }
        val lastTimestamp = le64(snooz, 1)
        val body = inflate(snooz, HEADER_SIZE)
        val warnings = ArrayList<String>(2)

        val entrySize = if (version == 1) 7 else 9
        // Pass one: the header holds the *last* timestamp, so walk the deltas backwards to find the
        // first one. Bounds are validated here; pass two can then trust the layout.
        var offset = 0
        var deltaSum = 0L
        var records = 0
        while (offset < body.size) {
            if (offset + entrySize > body.size) {
                warnings += "Trailing ${body.size - offset} bytes after the last complete record"
                break
            }
            val length = le16(body, offset)
            val delta = if (version == 1) le32(body, offset + 2) else le32(body, offset + 4)
            if (length < 1 || offset + entrySize + length - 1 > body.size) {
                warnings += "Record $records declares $length bytes but the block ends earlier"
                break
            }
            deltaSum += delta
            offset += entrySize + length - 1
            records++
        }
        val usableBytes = offset
        val usableRecords = records

        val base = resolveBase(lastTimestamp, warnings)
        var currentMs = base.epochMs - deltaSum

        var firstMs = currentMs
        var written = 0L
        var emitted = 0
        offset = 0
        records = 0
        val recordHeader = ByteArray(24)
        out.write(BTSNOOP_HEADER)
        while (offset < usableBytes && records < usableRecords) {
            val length = le16(body, offset)
            val packetLength: Int
            val delta: Long
            val type: Int
            if (version == 1) {
                packetLength = length
                delta = le32(body, offset + 2)
                type = body[offset + 6].toInt() and 0xFF
            } else {
                packetLength = le16(body, offset + 2)
                delta = le32(body, offset + 4)
                type = body[offset + 8].toInt() and 0xFF
            }
            currentMs += delta
            offset += entrySize
            val hciType = typeToHci(type)
            if (hciType < 0) {
                warnings += "Unknown btsnooz packet type 0x%02X".format(type)
                offset += length - 1
                records++
                continue
            }
            val timestamp = currentMs * 1000L + BTSNOOP_EPOCH_DELTA_MICROS
            putBe32(recordHeader, 0, packetLength.toLong())
            putBe32(recordHeader, 4, length.toLong())
            putBe32(recordHeader, 8, typeToDirection(type).toLong())
            putBe32(recordHeader, 12, 0)
            putBe64(recordHeader, 16, timestamp)
            out.write(recordHeader)
            out.write(hciType)
            out.write(body, offset, length - 1)
            if (emitted == 0) firstMs = currentMs
            written += recordHeader.size + length
            emitted++
            offset += length - 1
            records++
        }
        out.flush()

        return BtsnoozResult(
            version = version,
            records = emitted,
            bytesWritten = written + BTSNOOP_HEADER.size,
            firstEpochMs = firstMs,
            lastEpochMs = currentMs,
            timestampBase = base.description,
            warnings = warnings,
        )
    }

    private class TimeBase(val epochMs: Long, val description: String)

    /**
     * AOSP's writer stores `(unix_micros + epoch_delta) / 1000`, so the Unix time is recovered by
     * subtracting the delta in milliseconds. Builds that store plain Unix milliseconds are accepted
     * too; anything else falls back to "now" so relative spacing stays usable and says so.
     */
    private fun resolveBase(lastTimestamp: Long, warnings: MutableList<String>): TimeBase {
        val now = System.currentTimeMillis()
        val ceiling = now + 365L * 24 * 60 * 60 * 1000
        val adjusted = lastTimestamp - EPOCH_DELTA_MS
        if (adjusted in MIN_PLAUSIBLE_MS..ceiling) return TimeBase(adjusted, "btsnoop epoch delta")
        if (lastTimestamp in MIN_PLAUSIBLE_MS..ceiling) return TimeBase(lastTimestamp, "Unix milliseconds")
        warnings += "btsnooz timestamp base $lastTimestamp is not a recognisable clock; " +
            "times are anchored to the moment of extraction"
        return TimeBase(now, "anchored to extraction time")
    }

    private fun inflate(source: ByteArray, offset: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(source, offset, source.size - offset)
        val out = ByteArrayOutputStream(source.size * 4)
        val chunk = ByteArray(INFLATE_CHUNK)
        try {
            while (!inflater.finished()) {
                val produced = inflater.inflate(chunk)
                if (produced == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(chunk, 0, produced)
                if (out.size() > MAX_INFLATED_BYTES) {
                    throw BtsnoopFormatException("btsnooz payload inflates beyond ${MAX_INFLATED_BYTES / (1024 * 1024)} MiB")
                }
            }
        } catch (e: DataFormatException) {
            throw BtsnoopFormatException("btsnooz payload is not valid zlib data: ${e.message}")
        } finally {
            inflater.end()
        }
        if (out.size() == 0) throw BtsnoopFormatException("btsnooz payload decompressed to nothing")
        return out.toByteArray()
    }

    private fun typeToDirection(type: Int): Int =
        if (type == TYPE_IN_EVT || type == TYPE_IN_ACL || type == TYPE_IN_SCO || type == TYPE_IN_ISO) 1 else 0

    private fun typeToHci(type: Int): Int = when (type) {
        TYPE_OUT_CMD -> 0x01
        TYPE_IN_ACL, TYPE_OUT_ACL -> 0x02
        TYPE_IN_SCO, TYPE_OUT_SCO -> 0x03
        TYPE_IN_EVT -> 0x04
        TYPE_IN_ISO, TYPE_OUT_ISO -> 0x05
        else -> -1
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun le64(b: ByteArray, o: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (b[o + i].toLong() and 0xFF)
        return value
    }

    private fun putBe32(b: ByteArray, o: Int, value: Long) {
        b[o] = (value ushr 24).toByte()
        b[o + 1] = (value ushr 16).toByte()
        b[o + 2] = (value ushr 8).toByte()
        b[o + 3] = value.toByte()
    }

    private fun putBe64(b: ByteArray, o: Int, value: Long) {
        for (i in 0 until 8) b[o + i] = (value ushr (56 - i * 8)).toByte()
    }
}
