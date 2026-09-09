package dev.nphil.blestudio.hci

import dev.nphil.blestudio.model.AttOperation
import dev.nphil.blestudio.model.BleEvent
import dev.nphil.blestudio.model.EventDirection
import dev.nphil.blestudio.model.EventSource
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Raised for a stream that is not a btsnoop capture this parser can read. */
class BtsnoopFormatException(message: String) : IOException(message)

/**
 * What the parser saw, so the UI can be honest about the parts it could not decode.
 *
 * @param records btsnoop records read from the stream.
 * @param attEvents ATT [BleEvent]s emitted (including ones with an unhandled opcode).
 * @param systemEvents connection-lifecycle [BleEvent]s emitted (connect, disconnect, encryption,
 *   pairing, MTU, parameter and PHY updates).
 * @param unsupported records this parser could not decode at all: an unknown H4 packet type, an
 *   unknown event code, opcode or channel, or fields that did not fit the captured bytes.
 * @param countedOnly records recognised and counted by name that contributed no summarised fact,
 *   such as Number Of Completed Packets or a command with no parameters worth keeping.
 * @param truncated payloads shortened by the capture itself or by the parser's retention cap.
 * @param connections distinct connection lifetimes (a reused handle counts again).
 * @param hciEvents HCI event counts by name; LE meta events are counted by subevent name.
 * @param hciCommands HCI command counts by name, plus one key per command the controller refused.
 * @param smpPdus Security Manager PDU counts by name.
 * @param l2capSignals LE L2CAP signalling command counts by name.
 * @param connectionsByPeer one entry per peer address seen in a connection, bounded to
 *   [MAX_SUMMARISED_PEERS] addresses.
 * @param advertisers one entry per advertiser seen in an advertising report, same bound.
 */
data class ParseSummary(
    val records: Int = 0,
    val attEvents: Int = 0,
    val systemEvents: Int = 0,
    val unsupported: Int = 0,
    val countedOnly: Int = 0,
    val truncated: Int = 0,
    val warnings: List<String> = emptyList(),
    val connections: Int = 0,
    val hciEvents: Map<String, Int> = emptyMap(),
    val hciCommands: Map<String, Int> = emptyMap(),
    val smpPdus: Map<String, Int> = emptyMap(),
    val l2capSignals: Map<String, Int> = emptyMap(),
    val connectionsByPeer: Map<String, ConnectionSummary> = emptyMap(),
    val advertisers: Map<String, AdvertiserSummary> = emptyMap(),
)

/** Peers and advertisers a summary will describe; the rest are counted but not accumulated. */
const val MAX_SUMMARISED_PEERS = 256

/** Bytes of each retained raw packet; enough for a full 517-byte ATT MTU minus its headers. */
const val MAX_RAW_PACKET_BYTES = 512

/**
 * @param rawHex the H4 bytes of the record each event was decoded from, keyed by [BleEvent.id] and
 *   only populated when the parser was built with `rawPackets = true`. A reassembled L2CAP PDU is
 *   keyed to the fragment that completed it.
 */
data class BtsnoopParseResult(
    val events: List<BleEvent>,
    val summary: ParseSummary,
    val rawHex: Map<String, String> = emptyMap(),
)

/**
 * Deterministic [BleEvent.id] for one decoded record.
 *
 * Re-importing a capture file must not append a second copy of every event, so the id is a hash of
 * the record's own identity instead of a random UUID: the same bytes always name the same event and
 * an importer can drop what it already holds. [recordOrdinal] is part of the key because a capture
 * may legitimately carry two byte-identical records with the same timestamp, and those two remain
 * distinct events.
 */
internal fun hciEventId(
    recordOrdinal: Int,
    timestampEpochMicros: Long,
    connectionHandle: Int?,
    operation: AttOperation,
    attributeHandle: Int?,
    payloadHex: String,
): String {
    val key = "$timestampEpochMicros|$connectionHandle|${operation.name}|$attributeHandle|$payloadHex|$recordOrdinal"
    return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
}

/**
 * Streaming btsnoop (datalink 1002 / H4) reader that dissects the layers around ATT.
 *
 * ATT traffic becomes [BleEvent]s; the HCI, L2CAP signalling and SMP layers around it become
 * connection facts, a per-peer summary and a handful of lifecycle events so the timeline can show
 * connect, disconnect, encryption, pairing, MTU, parameter and PHY changes as banners.
 *
 * It keeps per-connection state: L2CAP fragments are reassembled, GATT discovery responses build a
 * handle to service/characteristic UUID map, and an HCI Disconnection Complete drops that state so
 * a reused connection handle can never inherit the previous connection's UUIDs.
 *
 * Every buffer is bounded ([maxInputBytes], [maxEvents], [maxPayloadBytes], [MAX_SUMMARISED_PEERS]
 * peers, one reassembly buffer per live connection of at most 64 KiB) so a hostile or corrupt file
 * cannot exhaust memory.
 *
 * @param rawPackets keep the H4 bytes of each decoded record in [BtsnoopParseResult.rawHex] so the
 *   inspector can re-dissect one packet on demand. Each entry is capped at [MAX_RAW_PACKET_BYTES]
 *   bytes and there are never more entries than [maxEvents].
 */
class BtsnoopParser(
    private val maxInputBytes: Long = 64L * 1024 * 1024,
    private val maxEvents: Int = 200_000,
    private val maxPayloadBytes: Int = 512,
    private val rawPackets: Boolean = false,
) {

    fun parse(file: File): BtsnoopParseResult = file.inputStream().use { parse(it) }

    fun parse(input: InputStream): BtsnoopParseResult {
        val stream = BufferedInputStream(input, READ_BUFFER)
        val header = ByteArray(FILE_HEADER_SIZE)
        if (!readFully(stream, header, FILE_HEADER_SIZE)) {
            throw BtsnoopFormatException("File is shorter than a btsnoop header (16 bytes)")
        }
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) {
                throw BtsnoopFormatException("Not a btsnoop file: bad magic ${hex(header, 0, 8)}")
            }
        }
        val version = be32(header, 8)
        if (version != 1L) throw BtsnoopFormatException("Unsupported btsnoop version $version (expected 1)")
        val datalink = be32(header, 12)
        if (datalink != DATALINK_H4) {
            throw BtsnoopFormatException("Unsupported datalink $datalink (expected $DATALINK_H4 / HCI H4)")
        }

        val state = ParseState(maxPayloadBytes, rawPackets, maxEvents)
        var consumed = FILE_HEADER_SIZE.toLong()
        val recordHeader = ByteArray(RECORD_HEADER_SIZE)
        var packet = ByteArray(2048)

        while (true) {
            if (state.events.size >= maxEvents) {
                state.warn("Event cap of $maxEvents reached; the rest of the capture was not parsed")
                break
            }
            if (consumed >= maxInputBytes) {
                state.warn("Input cap of $maxInputBytes bytes reached; the rest of the capture was not parsed")
                break
            }
            val got = read(stream, recordHeader, RECORD_HEADER_SIZE)
            if (got == 0) break
            if (got < RECORD_HEADER_SIZE) {
                state.warn("Truncated record header after ${state.records} records")
                break
            }
            consumed += RECORD_HEADER_SIZE
            val originalLength = be32(recordHeader, 0)
            val includedLength = be32(recordHeader, 4)
            val flags = be32(recordHeader, 8)
            val timestamp = be64(recordHeader, 16)
            if (includedLength < 0 || includedLength > MAX_RECORD_BYTES) {
                state.warn("Record ${state.records} declares an impossible length ($includedLength bytes); stopped")
                break
            }
            val length = includedLength.toInt()
            if (packet.size < length) packet = ByteArray(length.coerceAtLeast(packet.size * 2))
            if (!readFully(stream, packet, length)) {
                state.warn("Truncated record payload after ${state.records} records")
                break
            }
            consumed += length
            state.records++
            val capturedShort = originalLength > includedLength
            if (capturedShort) state.truncated++
            state.record(
                packet = packet,
                length = length,
                timestampEpochMicros = timestamp - BTSNOOP_EPOCH_DELTA_MICROS,
                sentByHost = (flags and 1L) == 0L,
                originalLength = originalLength,
                includedLength = includedLength,
            )
        }

        return BtsnoopParseResult(
            events = state.events,
            summary = state.summary(),
            rawHex = state.rawByEvent,
        )
    }

    // ---------------------------------------------------------------- parsing state

    private class ServiceRange(val start: Int, val end: Int, val uuid: String)

    private class ConnectionState {
        val services = ArrayList<ServiceRange>(8)
        val handleUuids = HashMap<Int, String>(32)

        /** In-flight L2CAP PDU being reassembled from ACL fragments. */
        var fragment: ByteArray? = null
        var fragmentLength = 0
        var fragmentCid = 0

        /** Outstanding ATT request; ATT allows one at a time per bearer, which is what makes this safe. */
        var pendingOpcode = 0
        var pendingHandle = -1
        var pendingType = -1
        var pendingRangeStart = 0

        /** The peer this handle belongs to, known only once an LE Connection Complete named it. */
        var peer: PeerState? = null
        var startMicros = -1L

        /** Clock of the last ATT PDU, which is what an idle-timeout gap is measured from. */
        var lastAttMicros = -1L

        /** Set by an HCI Disconnect command: the next disconnection was not the peer's idea. */
        var hostRequestedDisconnect = false

        /** Exchange MTU state (Vol 3 Part F, Section 3.4.2). */
        var clientRxMtu = -1
        var mtu = -1

        /** Pairing features from the request, held until the response completes the exchange. */
        var pairingIo = -1
        var pairingOob = -1
        var pairingAuth = -1

        fun serviceFor(handle: Int): String? {
            for (i in services.indices) {
                val range = services[i]
                if (handle >= range.start && handle <= range.end) return range.uuid
            }
            return null
        }
    }

    /** Everything learned about one peer address, across every handle it used. */
    private class PeerState(val address: String) {
        var addressType: String? = null
        val handles = LinkedHashSet<Int>(4)
        var intervalUnits = -1
        var latency = -1
        var timeoutUnits = -1
        var mtu = -1
        var encrypted = false
        var pairingMethod: PairingMethod? = null
        var bonded = false
        var pairingSeen = false
        var txPhy = -1
        var rxPhy = -1
        var creditBasedChannels = false
        var durationMicros = 0L
        val disconnectReasons = ArrayList<String>(4)
        val attempts = ArrayList<ConnectionAttemptFact>(4)
        val idleSamplesMs = ArrayList<Long>(4)
    }

    /** Everything learned about one advertiser, from the AD structures it broadcast. */
    private class AdvertiserState(val address: String) {
        var addressType: String? = null
        var count = 0
        var lastRssi = NO_RSSI
        val names = LinkedHashSet<String>(2)
        val serviceUuids = LinkedHashSet<String>(4)
        val manufacturerData = LinkedHashMap<Int, String>(2)
    }

    /** Mutable count so a histogram bucket does not box an Int on every packet. */
    private class Counter(var value: Int)

    private class ParseState(
        val maxPayloadBytes: Int,
        val rawPackets: Boolean,
        val maxRawEntries: Int,
    ) {
        val events = ArrayList<BleEvent>(1024)
        val warnings = LinkedHashSet<String>()
        val connections = HashMap<Int, ConnectionState>(4)
        val peers = LinkedHashMap<String, PeerState>(4)
        val advertisers = LinkedHashMap<String, AdvertiserState>(8)
        val hciEventCounts = LinkedHashMap<String, Counter>()
        val hciCommandCounts = LinkedHashMap<String, Counter>()
        val smpCounts = LinkedHashMap<String, Counter>()
        val signalCounts = LinkedHashMap<String, Counter>()
        val rawByEvent = HashMap<String, String>()
        var records = 0
        var attEvents = 0
        var systemEvents = 0
        var unsupported = 0
        var countedOnly = 0
        var truncated = 0
        var connectionCount = 0
        var orphanFragments = 0
        var creditBasedDataWarned = false
        var lastRecordMicros = 0L

        /** The record being decoded, hex-encoded at most once and only when it is wanted. */
        private var rawSource: ByteArray? = null
        private var rawLength = 0
        private var rawCache: String? = null

        /**
         * The outstanding connection attempt. The host may only have one LE Create Connection in
         * flight (Vol 4 Part E, Section 7.8.12), so one slot is enough. A null address means the
         * command used the Filter Accept List and the peer is only known once it connects.
         */
        private var pendingConnectMicros = -1L
        private var pendingConnectAddress: String? = null

        fun warn(message: String) {
            if (warnings.size < MAX_WARNINGS) warnings += message
        }

        fun connection(handle: Int): ConnectionState = connections.getOrPut(handle) {
            connectionCount++
            ConnectionState()
        }

        fun drop(handle: Int) {
            connections.remove(handle)
        }

        private fun peerFor(address: String): PeerState? {
            peers[address]?.let { return it }
            if (peers.size >= MAX_SUMMARISED_PEERS) {
                warn("More than $MAX_SUMMARISED_PEERS connected peers; later ones are counted but not summarised")
                return null
            }
            val state = PeerState(address)
            peers[address] = state
            return state
        }

        private fun advertiserFor(address: String): AdvertiserState? {
            advertisers[address]?.let { return it }
            if (advertisers.size >= MAX_SUMMARISED_PEERS) {
                warn("More than $MAX_SUMMARISED_PEERS advertisers; later ones are counted but not summarised")
                return null
            }
            val state = AdvertiserState(address)
            advertisers[address] = state
            return state
        }

        private fun count(into: MutableMap<String, Counter>, name: String) {
            val existing = into[name]
            if (existing != null) {
                existing.value++
                return
            }
            if (into.size < MAX_COUNTER_KEYS) into[name] = Counter(1)
        }

        fun record(
            packet: ByteArray,
            length: Int,
            timestampEpochMicros: Long,
            sentByHost: Boolean,
            originalLength: Long,
            includedLength: Long,
        ) {
            rawSource = packet
            rawLength = length
            rawCache = null
            lastRecordMicros = timestampEpochMicros
            if (length < 1) {
                unsupported++
                return
            }
            when (packet[0].toInt() and 0xFF) {
                HciNames.H4_ACL ->
                    acl(packet, length, timestampEpochMicros, sentByHost, originalLength, includedLength)
                HciNames.H4_EVENT -> event(packet, length, timestampEpochMicros)
                HciNames.H4_COMMAND -> command(packet, length, timestampEpochMicros)
                else -> unsupported++
            }
        }

        // ------------------------------------------------------------ HCI events (Vol 4 Part E, 7.7)

        /** Event packet: event code(1), parameter total length(1), parameters (Section 5.4.4). */
        private fun event(packet: ByteArray, length: Int, timestampEpochMicros: Long) {
            if (length < 3) {
                unsupported++
                return
            }
            val code = u8(packet, 1)
            val declared = u8(packet, 2)
            val available = (length - 3).coerceAtMost(declared)
            val body = 3
            if (code != HciNames.EVT_LE_META) count(hciEventCounts, HciNames.eventName(code))
            when (code) {
                // 7.7.5: status(1) handle(2) reason(1)
                HciNames.EVT_DISCONNECTION_COMPLETE -> when {
                    available < 4 -> unsupported++
                    else -> disconnectionComplete(
                        status = u8(packet, body),
                        handle = u16le(packet, body + 1) and HANDLE_MASK,
                        reason = u8(packet, body + 3),
                        timestampEpochMicros = timestampEpochMicros,
                    )
                }

                // 7.7.8: status(1) handle(2) encryption enabled(1), plus key size in [v2]
                HciNames.EVT_ENCRYPTION_CHANGE, HciNames.EVT_ENCRYPTION_CHANGE_V2 -> {
                    val needed = if (code == HciNames.EVT_ENCRYPTION_CHANGE) 4 else 5
                    if (available < needed) {
                        unsupported++
                    } else {
                        encryptionChange(
                            status = u8(packet, body),
                            handle = u16le(packet, body + 1) and HANDLE_MASK,
                            enabled = u8(packet, body + 3),
                            keySize = if (needed == 5) u8(packet, body + 4) else -1,
                            timestampEpochMicros = timestampEpochMicros,
                        )
                    }
                }

                // 7.7.14: num HCI command packets(1) opcode(2) return parameters
                HciNames.EVT_COMMAND_COMPLETE -> when {
                    available < 3 -> unsupported++
                    else -> {
                        val opcode = u16le(packet, body + 1)
                        val status = if (available >= 4) u8(packet, body + 3) else HciNames.STATUS_SUCCESS
                        if (status != HciNames.STATUS_SUCCESS) {
                            count(
                                hciCommandCounts,
                                "${HciNames.commandName(opcode)} refused: ${HciNames.statusText(status)}",
                            )
                        }
                        countedOnly++
                    }
                }

                // 7.7.15: status(1) num HCI command packets(1) opcode(2)
                HciNames.EVT_COMMAND_STATUS -> when {
                    available < 4 -> unsupported++
                    else -> commandStatus(
                        status = u8(packet, body),
                        opcode = u16le(packet, body + 2),
                        timestampEpochMicros = timestampEpochMicros,
                    )
                }

                // 7.7.19: buffer accounting only; nothing here describes the link.
                HciNames.EVT_NUMBER_OF_COMPLETED_PACKETS -> countedOnly++

                HciNames.EVT_LE_META -> when {
                    available < 1 -> unsupported++
                    else -> leMeta(packet, body, available, timestampEpochMicros)
                }

                else -> countedOnly++
            }
        }

        /** Vol 4 Part E, Section 7.7.5. */
        private fun disconnectionComplete(status: Int, handle: Int, reason: Int, timestampEpochMicros: Long) {
            if (status != HciNames.STATUS_SUCCESS) {
                emitSystem(
                    timestampEpochMicros = timestampEpochMicros,
                    connectionHandle = handle,
                    operation = AttOperation.ERROR,
                    note = "disconnection failed: ${HciNames.statusText(status)}",
                    status = status,
                )
                return
            }
            val connection = connections[handle]
            val peer = connection?.peer
            val reasonText = HciNames.statusText(reason)
            if (peer != null && connection != null) {
                if (peer.disconnectReasons.size < MAX_PEER_SAMPLES) peer.disconnectReasons += reasonText
                if (connection.startMicros > 0) {
                    peer.durationMicros += timestampEpochMicros - connection.startMicros
                }
                val idle = idleGapMs(connection, reason, timestampEpochMicros)
                if (idle >= 0 && peer.idleSamplesMs.size < MAX_PEER_SAMPLES) peer.idleSamplesMs += idle
            }
            val graceful = reason == HciNames.STATUS_REMOTE_USER_TERMINATED ||
                reason == HciNames.STATUS_LOCAL_HOST_TERMINATED
            val note = StringBuilder(72)
            note.append("disconnected: ").append(reasonText).append(" (0x%02X)".format(reason))
            if (connection?.hostRequestedDisconnect == true) note.append("; host asked for it")
            peer?.let { note.append("; peer ").append(it.address) }
            emitSystem(
                timestampEpochMicros = timestampEpochMicros,
                connectionHandle = handle,
                operation = if (graceful) AttOperation.OTHER else AttOperation.ERROR,
                note = note.toString(),
                status = reason,
            )
            drop(handle)
        }

        /**
         * Idle time before a disconnection, in milliseconds, or -1 when this disconnection is no
         * evidence of an idle timer: a link the host tore down says nothing about the peer, and only
         * a supervision timeout or a peer-initiated termination follows an idle gap.
         */
        private fun idleGapMs(connection: ConnectionState, reason: Int, timestampEpochMicros: Long): Long {
            if (connection.hostRequestedDisconnect) return -1
            if (reason != HciNames.STATUS_CONNECTION_TIMEOUT &&
                reason != HciNames.STATUS_REMOTE_USER_TERMINATED
            ) {
                return -1
            }
            if (connection.lastAttMicros <= 0) return -1
            val gap = (timestampEpochMicros - connection.lastAttMicros) / 1000
            return if (gap >= 0) gap else -1
        }

        /** Vol 4 Part E, Section 7.7.8. */
        private fun encryptionChange(
            status: Int,
            handle: Int,
            enabled: Int,
            keySize: Int,
            timestampEpochMicros: Long,
        ) {
            val peer = connections[handle]?.peer
            if (status == HciNames.STATUS_SUCCESS) peer?.encrypted = enabled != 0
            val note = when {
                status != HciNames.STATUS_SUCCESS -> "encryption change failed: ${HciNames.statusText(status)}"
                enabled == 0 -> "encryption off"
                keySize > 0 -> "encryption on, $keySize byte key"
                else -> "encryption on"
            }
            emitSystem(
                timestampEpochMicros = timestampEpochMicros,
                connectionHandle = handle,
                operation = if (status == HciNames.STATUS_SUCCESS) AttOperation.OTHER else AttOperation.ERROR,
                note = note,
                status = status,
            )
        }

        /** Vol 4 Part E, Section 7.7.15: a refused Create Connection ends the attempt right here. */
        private fun commandStatus(status: Int, opcode: Int, timestampEpochMicros: Long) {
            val creating = opcode == HciNames.CMD_LE_CREATE_CONNECTION ||
                opcode == HciNames.CMD_LE_EXTENDED_CREATE_CONNECTION
            if (status == HciNames.STATUS_SUCCESS || !creating) {
                if (status != HciNames.STATUS_SUCCESS) {
                    count(
                        hciCommandCounts,
                        "${HciNames.commandName(opcode)} refused: ${HciNames.statusText(status)}",
                    )
                }
                countedOnly++
                return
            }
            val address = pendingConnectAddress
            if (address != null) recordAttempt(address, timestampEpochMicros, status)
            pendingConnectMicros = -1
            pendingConnectAddress = null
            emitSystem(
                timestampEpochMicros = timestampEpochMicros,
                connectionHandle = null,
                operation = AttOperation.ERROR,
                note = "${HciNames.commandName(opcode)} refused: ${HciNames.statusText(status)}",
                status = status,
            )
        }

        /** Vol 4 Part E, Section 7.7.65: subevent code(1) then the subevent's own parameters. */
        private fun leMeta(packet: ByteArray, offset: Int, available: Int, timestampEpochMicros: Long) {
            val subevent = u8(packet, offset)
            count(hciEventCounts, HciNames.leSubeventName(subevent))
            val body = offset + 1
            val rest = available - 1
            when (subevent) {
                HciNames.LE_CONNECTION_COMPLETE ->
                    if (rest < 18) unsupported++ else connectionComplete(packet, body, timestampEpochMicros, false)

                HciNames.LE_ENHANCED_CONNECTION_COMPLETE ->
                    if (rest < 30) unsupported++ else connectionComplete(packet, body, timestampEpochMicros, true)

                // 7.7.65.3: status(1) handle(2) interval(2) latency(2) timeout(2)
                HciNames.LE_CONNECTION_UPDATE_COMPLETE -> when {
                    rest < 9 -> unsupported++
                    else -> connectionUpdate(
                        status = u8(packet, body),
                        handle = u16le(packet, body + 1) and HANDLE_MASK,
                        intervalUnits = u16le(packet, body + 3),
                        latency = u16le(packet, body + 5),
                        timeoutUnits = u16le(packet, body + 7),
                        timestampEpochMicros = timestampEpochMicros,
                    )
                }

                // 7.7.65.12: status(1) handle(2) TX PHY(1) RX PHY(1)
                HciNames.LE_PHY_UPDATE_COMPLETE -> when {
                    rest < 5 -> unsupported++
                    else -> phyUpdate(
                        status = u8(packet, body),
                        handle = u16le(packet, body + 1) and HANDLE_MASK,
                        txPhy = u8(packet, body + 3),
                        rxPhy = u8(packet, body + 4),
                        timestampEpochMicros = timestampEpochMicros,
                    )
                }

                HciNames.LE_ADVERTISING_REPORT ->
                    if (rest < 10) unsupported++ else advertisingReports(packet, body, rest)

                HciNames.LE_EXTENDED_ADVERTISING_REPORT ->
                    if (rest < 25) unsupported++ else extendedAdvertisingReports(packet, body, rest)

                // 7.7.65.4: status(1) handle(2) LE features(8). Named and counted; the feature bits
                // describe the controller pair, not this link's behaviour.
                HciNames.LE_READ_REMOTE_FEATURES_COMPLETE -> if (rest < 11) unsupported++ else countedOnly++

                // 7.7.65.7: handle(2) max Tx octets(2) max Tx time(2) max Rx octets(2) max Rx time(2)
                HciNames.LE_DATA_LENGTH_CHANGE -> if (rest < 10) unsupported++ else countedOnly++

                else -> countedOnly++
            }
        }

        /**
         * Vol 4 Part E, Sections 7.7.65.1 and 7.7.65.10. The enhanced form inserts the local and
         * peer resolvable private addresses before the interval, which is the only layout difference.
         */
        private fun connectionComplete(
            packet: ByteArray,
            body: Int,
            timestampEpochMicros: Long,
            enhanced: Boolean,
        ) {
            val status = u8(packet, body)
            val handle = u16le(packet, body + 1) and HANDLE_MASK
            val role = u8(packet, body + 3)
            val addressType = u8(packet, body + 4)
            val address = bdAddr(packet, body + 5)
            val timing = if (enhanced) body + 23 else body + 11
            val intervalUnits = u16le(packet, timing)
            val latency = u16le(packet, timing + 2)
            val timeoutUnits = u16le(packet, timing + 4)

            if (status != HciNames.STATUS_SUCCESS) {
                recordAttempt(address, timestampEpochMicros, status)
                emitSystem(
                    timestampEpochMicros = timestampEpochMicros,
                    connectionHandle = null,
                    operation = AttOperation.ERROR,
                    note = "connection to $address failed: ${HciNames.statusText(status)}",
                    status = status,
                )
                return
            }

            // A reused handle must never inherit the previous connection's database.
            drop(handle)
            val connection = connection(handle)
            connection.startMicros = timestampEpochMicros
            val peer = peerFor(address)
            connection.peer = peer
            if (peer != null) {
                peer.addressType = HciNames.addressTypeName(addressType)
                if (peer.handles.size < MAX_PEER_HANDLES) peer.handles += handle
                peer.intervalUnits = intervalUnits
                peer.latency = latency
                peer.timeoutUnits = timeoutUnits
            }
            recordAttempt(address, timestampEpochMicros, status)
            emitSystem(
                timestampEpochMicros = timestampEpochMicros,
                connectionHandle = handle,
                operation = AttOperation.OTHER,
                note = "connected to $address (${HciNames.addressTypeName(addressType)}) as " +
                    "${HciNames.roleName(role)}, interval ${formatMillis(connectionIntervalMs(intervalUnits))}, " +
                    "latency $latency, timeout ${formatMillis(supervisionTimeoutMs(timeoutUnits).toDouble())}",
                status = status,
            )
        }

        private fun recordAttempt(address: String, timestampEpochMicros: Long, status: Int) {
            val peer = peerFor(address) ?: return
            val requested = pendingConnectAddress
            val started = if (pendingConnectMicros > 0 && (requested == null || requested == address)) {
                pendingConnectMicros
            } else {
                -1L
            }
            if (peer.attempts.size < MAX_PEER_SAMPLES) {
                peer.attempts += ConnectionAttemptFact(
                    startedEpochMicros = if (started > 0) started else timestampEpochMicros,
                    durationMicros = if (started > 0) timestampEpochMicros - started else -1L,
                    success = status == HciNames.STATUS_SUCCESS,
                    error = if (status == HciNames.STATUS_SUCCESS) null else HciNames.statusText(status),
                )
            }
            pendingConnectMicros = -1
            pendingConnectAddress = null
        }

        /** Vol 4 Part E, Section 7.7.65.3. */
        private fun connectionUpdate(
            status: Int,
            handle: Int,
            intervalUnits: Int,
            latency: Int,
            timeoutUnits: Int,
            timestampEpochMicros: Long,
        ) {
            val peer = connections[handle]?.peer
            if (status == HciNames.STATUS_SUCCESS && peer != null) {
                peer.intervalUnits = intervalUnits
                peer.latency = latency
                peer.timeoutUnits = timeoutUnits
            }
            val note = if (status == HciNames.STATUS_SUCCESS) {
                "connection updated: interval ${formatMillis(connectionIntervalMs(intervalUnits))}, " +
                    "latency $latency, timeout ${formatMillis(supervisionTimeoutMs(timeoutUnits).toDouble())}"
            } else {
                "connection update failed: ${HciNames.statusText(status)}"
            }
            emitSystem(
                timestampEpochMicros = timestampEpochMicros,
                connectionHandle = handle,
                operation = if (status == HciNames.STATUS_SUCCESS) AttOperation.OTHER else AttOperation.ERROR,
                note = note,
                status = status,
            )
        }

        /** Vol 4 Part E, Section 7.7.65.12. */
        private fun phyUpdate(status: Int, handle: Int, txPhy: Int, rxPhy: Int, timestampEpochMicros: Long) {
            val peer = connections[handle]?.peer
            if (status == HciNames.STATUS_SUCCESS && peer != null) {
                peer.txPhy = txPhy
                peer.rxPhy = rxPhy
            }
            val note = if (status == HciNames.STATUS_SUCCESS) {
                "PHY now ${HciNames.phyName(txPhy)} tx / ${HciNames.phyName(rxPhy)} rx"
            } else {
                "PHY update failed: ${HciNames.statusText(status)}"
            }
            emitSystem(
                timestampEpochMicros = timestampEpochMicros,
                connectionHandle = handle,
                operation = if (status == HciNames.STATUS_SUCCESS) AttOperation.OTHER else AttOperation.ERROR,
                note = note,
                status = status,
            )
        }

        /**
         * Vol 4 Part E, Section 7.7.65.2: num reports(1) then, per report, event type(1),
         * address type(1), address(6), data length(1), data, RSSI(1). Advertising is aggregated per
         * advertiser instead of emitting events, because a busy room produces thousands per minute.
         */
        private fun advertisingReports(packet: ByteArray, body: Int, available: Int) {
            val reports = u8(packet, body).coerceAtLeast(1)
            val end = body + available
            var cursor = body + 1
            var decoded = 0
            while (decoded < reports && cursor + 9 <= end) {
                val addressType = u8(packet, cursor + 1)
                val address = bdAddr(packet, cursor + 2)
                val dataLength = u8(packet, cursor + 8)
                if (cursor + 10 + dataLength > end) break
                advertiser(address, addressType, packet[cursor + 9 + dataLength].toInt(), packet, cursor + 9, dataLength)
                cursor += 10 + dataLength
                decoded++
            }
            if (decoded == 0) unsupported++
        }

        /**
         * Vol 4 Part E, Section 7.7.65.13: num reports(1) then, per report, event type(2),
         * address type(1), address(6), primary PHY(1), secondary PHY(1), advertising SID(1),
         * TX power(1), RSSI(1), periodic interval(2), direct address type(1), direct address(6),
         * data length(1), data.
         */
        private fun extendedAdvertisingReports(packet: ByteArray, body: Int, available: Int) {
            val reports = u8(packet, body).coerceAtLeast(1)
            val end = body + available
            var cursor = body + 1
            var decoded = 0
            while (decoded < reports && cursor + 24 <= end) {
                val addressType = u8(packet, cursor + 2)
                val address = bdAddr(packet, cursor + 3)
                val rssi = packet[cursor + 13].toInt()
                val dataLength = u8(packet, cursor + 23)
                if (cursor + 24 + dataLength > end) break
                advertiser(address, addressType, rssi, packet, cursor + 24, dataLength)
                cursor += 24 + dataLength
                decoded++
            }
            if (decoded == 0) unsupported++
        }

        /** Core Specification Supplement, Part A, Section 1: names, service UUIDs, company data. */
        private fun advertiser(
            address: String,
            addressType: Int,
            rssi: Int,
            packet: ByteArray,
            dataOffset: Int,
            dataLength: Int,
        ) {
            val state = advertiserFor(address) ?: return
            state.count++
            state.addressType = HciNames.addressTypeName(addressType)
            state.lastRssi = rssi
            forEachAdStructure(packet, dataOffset, dataLength) { type, offset, length ->
                when (type) {
                    HciNames.AD_SHORTENED_NAME, HciNames.AD_COMPLETE_NAME ->
                        if (length > 0 && state.names.size < MAX_ADVERTISER_NAMES) {
                            state.names += String(packet, offset, length, Charsets.UTF_8)
                        }

                    HciNames.AD_INCOMPLETE_16, HciNames.AD_COMPLETE_16 -> {
                        var at = offset
                        while (at + 2 <= offset + length && state.serviceUuids.size < MAX_ADVERTISER_UUIDS) {
                            state.serviceUuids += uuid16(u16le(packet, at))
                            at += 2
                        }
                    }

                    HciNames.AD_INCOMPLETE_32, HciNames.AD_COMPLETE_32 -> {
                        var at = offset
                        while (at + 4 <= offset + length && state.serviceUuids.size < MAX_ADVERTISER_UUIDS) {
                            state.serviceUuids += uuid32(u32le(packet, at))
                            at += 4
                        }
                    }

                    HciNames.AD_INCOMPLETE_128, HciNames.AD_COMPLETE_128 -> {
                        var at = offset
                        while (at + 16 <= offset + length && state.serviceUuids.size < MAX_ADVERTISER_UUIDS) {
                            state.serviceUuids += uuid128(packet, at)
                            at += 16
                        }
                    }

                    HciNames.AD_MANUFACTURER_SPECIFIC ->
                        if (length >= 2 && state.manufacturerData.size < MAX_ADVERTISER_COMPANIES) {
                            val company = u16le(packet, offset)
                            val kept = (length - 2).coerceAtMost(MAX_MANUFACTURER_BYTES)
                            state.manufacturerData[company] = hex(packet, offset + 2, kept)
                        }
                }
            }
        }

        // ------------------------------------------------------------ HCI commands (Vol 4 Part E, 7.8)

        /** Command packet: opcode(2), parameter total length(1), parameters (Section 5.4.1). */
        private fun command(packet: ByteArray, length: Int, timestampEpochMicros: Long) {
            if (length < 4) {
                unsupported++
                return
            }
            val opcode = u16le(packet, 1)
            val declared = u8(packet, 3)
            val available = (length - 4).coerceAtMost(declared)
            val body = 4
            count(hciCommandCounts, HciNames.commandName(opcode))
            when (opcode) {
                // 7.8.12: scan interval(2) window(2) filter policy(1) peer type(1) peer(6) own(1)
                // interval min(2) max(2) latency(2) timeout(2) min CE(2) max CE(2)
                HciNames.CMD_LE_CREATE_CONNECTION -> when {
                    available < 25 -> unsupported++
                    else -> {
                        pendingConnectAddress =
                            if (u8(packet, body + 4) == 0x01) null else bdAddr(packet, body + 6)
                        pendingConnectMicros = timestampEpochMicros
                    }
                }

                // 7.8.66: filter policy(1) own(1) peer type(1) peer(6) initiating PHYs(1) then per-PHY sets
                HciNames.CMD_LE_EXTENDED_CREATE_CONNECTION -> when {
                    available < 10 -> unsupported++
                    else -> {
                        pendingConnectAddress =
                            if (u8(packet, body) == 0x01) null else bdAddr(packet, body + 3)
                        pendingConnectMicros = timestampEpochMicros
                    }
                }

                // 7.8.14: the host gave up on this attempt.
                HciNames.CMD_LE_CREATE_CONNECTION_CANCEL -> {
                    pendingConnectMicros = -1
                    pendingConnectAddress = null
                    countedOnly++
                }

                // 7.1.6: handle(2) reason(1). Marks the link so an idle gap is not misattributed.
                HciNames.CMD_DISCONNECT -> when {
                    available < 3 -> unsupported++
                    else -> connections[u16le(packet, body) and HANDLE_MASK]?.hostRequestedDisconnect = true
                }

                // 7.8.10 / 7.8.11 / 7.8.7 / 7.8.24: decoded field by field in the dissection tree;
                // none of them describes the peer, so the summary only counts them.
                HciNames.CMD_LE_SET_SCAN_PARAMETERS -> if (available < 7) unsupported++ else countedOnly++
                HciNames.CMD_LE_SET_SCAN_ENABLE -> if (available < 2) unsupported++ else countedOnly++
                HciNames.CMD_LE_SET_ADVERTISING_DATA -> if (available < 1) unsupported++ else countedOnly++
                HciNames.CMD_LE_ENABLE_ENCRYPTION -> if (available < 28) unsupported++ else countedOnly++

                else -> countedOnly++
            }
        }

        private fun acl(
            packet: ByteArray,
            length: Int,
            timestampEpochMicros: Long,
            sentByHost: Boolean,
            originalLength: Long,
            includedLength: Long,
        ) {
            if (length < 5) {
                unsupported++
                return
            }
            val header = u16le(packet, 1)
            val handle = header and 0x0FFF
            val boundary = (header shr 12) and 0x03
            val declared = u16le(packet, 3)
            val available = (length - 5).coerceAtMost(declared)
            val connection = connection(handle)

            if (boundary == PB_CONTINUATION) {
                val buffer = connection.fragment
                if (buffer == null) {
                    orphanFragments++
                    if (orphanFragments == 1) {
                        warn("ACL continuation without a start fragment (capture began mid-connection)")
                    }
                    unsupported++
                    return
                }
                val room = (buffer.size - connection.fragmentLength).coerceAtMost(available)
                System.arraycopy(packet, 5, buffer, connection.fragmentLength, room)
                connection.fragmentLength += room
                if (connection.fragmentLength < buffer.size) return
                val cid = connection.fragmentCid
                connection.fragment = null
                connection.fragmentLength = 0
                deliver(connection, handle, cid, buffer, buffer.size, timestampEpochMicros, sentByHost, originalLength, includedLength)
                return
            }

            // Start of an L2CAP PDU: length(2) cid(2) then payload.
            connection.fragment = null
            connection.fragmentLength = 0
            if (available < 4) {
                unsupported++
                return
            }
            val l2capLength = u16le(packet, 5)
            val cid = u16le(packet, 7)
            val payloadAvailable = available - 4
            if (payloadAvailable >= l2capLength) {
                val body = ByteArray(l2capLength)
                System.arraycopy(packet, 9, body, 0, l2capLength)
                deliver(connection, handle, cid, body, l2capLength, timestampEpochMicros, sentByHost, originalLength, includedLength)
                return
            }
            if (l2capLength > MAX_L2CAP_BYTES) {
                warn("L2CAP PDU of $l2capLength bytes exceeds the reassembly limit; dropped")
                unsupported++
                return
            }
            val buffer = ByteArray(l2capLength)
            System.arraycopy(packet, 9, buffer, 0, payloadAvailable)
            connection.fragment = buffer
            connection.fragmentLength = payloadAvailable
            connection.fragmentCid = cid
        }

        /** Vol 3 Part A, Section 2.1: the CID picks the channel's dissector. */
        private fun deliver(
            connection: ConnectionState,
            handle: Int,
            cid: Int,
            body: ByteArray,
            length: Int,
            timestampEpochMicros: Long,
            sentByHost: Boolean,
            originalLength: Long,
            includedLength: Long,
        ) {
            if (length < 1) {
                unsupported++
                return
            }
            when (cid) {
                HciNames.CID_ATT ->
                    att(connection, handle, body, length, timestampEpochMicros, sentByHost, originalLength, includedLength)

                HciNames.CID_SMP -> smp(connection, handle, body, length, timestampEpochMicros, sentByHost)

                HciNames.CID_SIGNALING ->
                    signaling(connection, handle, body, length, timestampEpochMicros, sentByHost)

                else -> {
                    // A credit-based channel (EATT or a CoC) frames its payload as K-frames with
                    // SDU segmentation (Vol 3 Part A, Section 3.4.3), which this parser does not
                    // reassemble, so its ATT traffic is counted rather than guessed at.
                    if (!creditBasedDataWarned && connection.peer?.creditBasedChannels == true) {
                        creditBasedDataWarned = true
                        warn("Data on credit-based channel CID 0x%04X is counted, not dissected".format(cid))
                    }
                    unsupported++
                }
            }
        }

        /**
         * Security Manager, Vol 3 Part H, Section 3: code(1) then the PDU's own fields.
         *
         * Every decodable PDU becomes an event so the pairing sequence is visible. A PDU that
         * carries key material keeps none of its bytes: the payload is empty and the note says so.
         */
        private fun smp(
            connection: ConnectionState,
            connectionHandle: Int,
            pdu: ByteArray,
            length: Int,
            timestampEpochMicros: Long,
            sentByHost: Boolean,
        ) {
            val code = u8(pdu, 0)
            val name = HciNames.smpOpcodeName(code)
            count(smpCounts, name)
            val direction =
                if (sentByHost) EventDirection.PHONE_TO_DEVICE else EventDirection.DEVICE_TO_PHONE
            var operation = AttOperation.OTHER
            var status: Int? = null
            val note = StringBuilder(96)
            note.append(name)

            when (code) {
                // 3.5.1 / 3.5.2: IO capability(1) OOB flag(1) AuthReq(1) max key size(1)
                // initiator key distribution(1) responder key distribution(1)
                HciNames.SMP_PAIRING_REQUEST, HciNames.SMP_PAIRING_RESPONSE -> {
                    if (length < 7) {
                        unsupported++
                        return
                    }
                    val io = u8(pdu, 1)
                    val oob = u8(pdu, 2)
                    val auth = u8(pdu, 3)
                    val maxKey = u8(pdu, 4)
                    val initiatorKeys = u8(pdu, 5)
                    val responderKeys = u8(pdu, 6)
                    val peer = connection.peer
                    peer?.pairingSeen = true
                    if (auth and HciNames.AUTH_REQ_BONDING_MASK == 0x01) peer?.bonded = true
                    if (code == HciNames.SMP_PAIRING_REQUEST) {
                        connection.pairingIo = io
                        connection.pairingOob = oob
                        connection.pairingAuth = auth
                    } else if (connection.pairingAuth >= 0) {
                        val method = HciNames.associationModel(
                            initiatorIo = connection.pairingIo,
                            responderIo = io,
                            initiatorOob = connection.pairingOob,
                            responderOob = oob,
                            initiatorAuthReq = connection.pairingAuth,
                            responderAuthReq = auth,
                        )
                        peer?.pairingMethod = method
                        note.append(" -> ").append(method.name)
                    }
                    note.append(": IO ").append(HciNames.ioCapabilityName(io))
                        .append(", ").append(HciNames.oobDataFlagName(oob))
                        .append(", ").append(HciNames.authReqText(auth))
                        .append(", max key $maxKey")
                        .append(", keys ").append(HciNames.keyDistributionText(initiatorKeys))
                        .append('/').append(HciNames.keyDistributionText(responderKeys))
                }

                // 3.5.5: reason(1)
                HciNames.SMP_PAIRING_FAILED -> {
                    if (length < 2) {
                        unsupported++
                        return
                    }
                    val reason = u8(pdu, 1)
                    operation = AttOperation.ERROR
                    status = reason
                    note.setLength(0)
                    note.append("pairing failed: ").append(HciNames.smpFailureReason(reason))
                }

                // 3.6.7: AuthReq(1)
                HciNames.SMP_SECURITY_REQUEST -> {
                    if (length < 2) {
                        unsupported++
                        return
                    }
                    connection.peer?.pairingSeen = true
                    note.append(": ").append(HciNames.authReqText(u8(pdu, 1)))
                }

                // 3.5.8: notification type(1)
                HciNames.SMP_KEYPRESS_NOTIFICATION -> {
                    if (length < 2) {
                        unsupported++
                        return
                    }
                    note.append(": type 0x%02X".format(u8(pdu, 1)))
                }

                else -> {
                    if (HciNames.smpCarriesKeyMaterial(code)) {
                        note.append(": [redacted key material] (").append(length - 1).append(" bytes)")
                    } else {
                        note.append(": ").append(length - 1).append(" bytes, not decoded")
                        countedOnly++
                    }
                }
            }

            // The negotiation PDUs keep their bytes as evidence; the ones carrying key material do
            // not, so nothing derived from an LTK, IRK, CSRK or public key is ever written down.
            val payloadHex = if (HciNames.smpCarriesKeyMaterial(code)) {
                ""
            } else {
                hex(pdu, 0, length.coerceAtMost(maxPayloadBytes))
            }
            emitSystem(
                timestampEpochMicros = timestampEpochMicros,
                connectionHandle = connectionHandle,
                operation = operation,
                note = note.toString(),
                status = status,
                direction = direction,
                payloadHex = payloadHex,
            )
        }

        /**
         * LE L2CAP signalling, Vol 3 Part A, Section 4: code(1) identifier(1) length(2) then the
         * command's own parameters.
         */
        private fun signaling(
            connection: ConnectionState,
            connectionHandle: Int,
            pdu: ByteArray,
            length: Int,
            timestampEpochMicros: Long,
            sentByHost: Boolean,
        ) {
            val code = u8(pdu, 0)
            val name = HciNames.l2capSignalName(code)
            if (length < 4) {
                count(signalCounts, name)
                unsupported++
                return
            }
            val declared = u16le(pdu, 2)
            val available = (length - 4).coerceAtMost(declared)
            val body = 4
            val direction =
                if (sentByHost) EventDirection.PHONE_TO_DEVICE else EventDirection.DEVICE_TO_PHONE
            // Counted once, under a key that names the SPSM when the command carries one.
            var counterKey = name
            when (code) {
                // 4.20: interval min(2) interval max(2) peripheral latency(2) timeout multiplier(2)
                HciNames.L2CAP_CONN_PARAM_UPDATE_REQ -> when {
                    available < 8 -> unsupported++
                    else -> emitSystem(
                        timestampEpochMicros = timestampEpochMicros,
                        connectionHandle = connectionHandle,
                        operation = AttOperation.OTHER,
                        note = "connection parameter update request: interval " +
                            "${formatMillis(connectionIntervalMs(u16le(pdu, body)))} to " +
                            "${formatMillis(connectionIntervalMs(u16le(pdu, body + 2)))}, " +
                            "latency ${u16le(pdu, body + 4)}, " +
                            "timeout ${formatMillis(supervisionTimeoutMs(u16le(pdu, body + 6)).toDouble())}",
                        direction = direction,
                    )
                }

                // 4.21: result(2)
                HciNames.L2CAP_CONN_PARAM_UPDATE_RSP -> when {
                    available < 2 -> unsupported++
                    else -> {
                        val result = u16le(pdu, body)
                        emitSystem(
                            timestampEpochMicros = timestampEpochMicros,
                            connectionHandle = connectionHandle,
                            operation = if (result == 0) AttOperation.OTHER else AttOperation.ERROR,
                            note = "connection parameter update " +
                                HciNames.connectionParameterResultText(result).lowercase(),
                            status = result,
                            direction = direction,
                        )
                    }
                }

                // 4.22: SPSM(2) source CID(2) MTU(2) MPS(2) initial credits(2)
                // 4.25: SPSM(2) MTU(2) MPS(2) initial credits(2) then one or more source CIDs.
                // Only the SPSM is read here, and it sits first in both layouts.
                HciNames.L2CAP_LE_CREDIT_CONN_REQ, HciNames.L2CAP_CREDIT_CONN_REQ -> when {
                    available < 10 -> unsupported++
                    else -> {
                        connection.peer?.creditBasedChannels = true
                        counterKey = "$name via ${HciNames.spsmName(u16le(pdu, body))}"
                        countedOnly++
                    }
                }

                // 4.23: destination CID(2) MTU(2) MPS(2) initial credits(2) result(2)
                HciNames.L2CAP_LE_CREDIT_CONN_RSP -> when {
                    available < 10 -> unsupported++
                    else -> {
                        if (u16le(pdu, body + 8) == 0) connection.peer?.creditBasedChannels = true
                        countedOnly++
                    }
                }

                // 4.26: MTU(2) MPS(2) initial credits(2) result(2) then the destination CIDs.
                HciNames.L2CAP_CREDIT_CONN_RSP -> when {
                    available < 8 -> unsupported++
                    else -> {
                        if (u16le(pdu, body + 6) == 0) connection.peer?.creditBasedChannels = true
                        countedOnly++
                    }
                }

                else -> countedOnly++
            }
            count(signalCounts, counterKey)
        }

        private fun att(
            connection: ConnectionState,
            connectionHandle: Int,
            pdu: ByteArray,
            length: Int,
            timestampEpochMicros: Long,
            sentByHost: Boolean,
            originalLength: Long,
            includedLength: Long,
        ) {
            val opcode = pdu[0].toInt() and 0xFF
            val direction = if (sentByHost) EventDirection.PHONE_TO_DEVICE else EventDirection.DEVICE_TO_PHONE
            var operation = AttOperation.OTHER
            var attributeHandle: Int? = null
            var status: Int? = null
            var valueOffset = -1
            var valueLength = 0
            var note = ""

            when (opcode) {
                ATT_ERROR_RSP -> {
                    operation = AttOperation.ERROR
                    if (length >= 5) {
                        attributeHandle = u16le(pdu, 2)
                        status = pdu[4].toInt() and 0xFF
                        note = "error on opcode 0x%02X: %s".format(pdu[1].toInt() and 0xFF, attError(status))
                    }
                    connection.pendingOpcode = 0
                }

                ATT_FIND_INFORMATION_REQ -> {
                    operation = AttOperation.DISCOVERY
                    if (length >= 5) {
                        connection.pendingOpcode = opcode
                        connection.pendingRangeStart = u16le(pdu, 1)
                        note = "find information 0x%04X-0x%04X".format(u16le(pdu, 1), u16le(pdu, 3))
                    }
                }

                ATT_FIND_INFORMATION_RSP -> {
                    operation = AttOperation.DISCOVERY
                    note = findInformation(connection, pdu, length)
                    connection.pendingOpcode = 0
                }

                ATT_READ_BY_TYPE_REQ -> {
                    operation = AttOperation.DISCOVERY
                    if (length >= 7) {
                        connection.pendingOpcode = opcode
                        connection.pendingType = if (length == 7) u16le(pdu, 5) else -1
                        note = "read by type ${typeName(connection.pendingType)}"
                    }
                }

                ATT_READ_BY_TYPE_RSP -> {
                    operation = AttOperation.DISCOVERY
                    note = readByType(connection, pdu, length)
                    connection.pendingOpcode = 0
                }

                ATT_READ_BY_GROUP_TYPE_REQ -> {
                    operation = AttOperation.DISCOVERY
                    if (length >= 7) {
                        connection.pendingOpcode = opcode
                        connection.pendingType = if (length == 7) u16le(pdu, 5) else -1
                        note = "read by group type ${typeName(connection.pendingType)}"
                    }
                }

                ATT_READ_BY_GROUP_TYPE_RSP -> {
                    operation = AttOperation.DISCOVERY
                    note = readByGroupType(connection, pdu, length)
                    connection.pendingOpcode = 0
                }

                ATT_READ_REQ -> {
                    operation = AttOperation.READ_REQUEST
                    if (length >= 3) {
                        attributeHandle = u16le(pdu, 1)
                        connection.pendingOpcode = opcode
                        connection.pendingHandle = attributeHandle
                    }
                }

                ATT_READ_RSP -> {
                    operation = AttOperation.READ_RESPONSE
                    if (connection.pendingOpcode == ATT_READ_REQ) attributeHandle = connection.pendingHandle
                    valueOffset = 1
                    valueLength = length - 1
                    connection.pendingOpcode = 0
                }

                ATT_WRITE_REQ -> {
                    operation = AttOperation.WRITE_REQUEST
                    if (length >= 3) {
                        attributeHandle = u16le(pdu, 1)
                        connection.pendingOpcode = opcode
                        connection.pendingHandle = attributeHandle
                        valueOffset = 3
                        valueLength = length - 3
                    }
                }

                ATT_WRITE_RSP -> {
                    operation = AttOperation.WRITE_RESPONSE
                    if (connection.pendingOpcode == ATT_WRITE_REQ) attributeHandle = connection.pendingHandle
                    connection.pendingOpcode = 0
                }

                ATT_WRITE_CMD -> {
                    operation = AttOperation.WRITE_COMMAND
                    if (length >= 3) {
                        attributeHandle = u16le(pdu, 1)
                        valueOffset = 3
                        valueLength = length - 3
                    }
                }

                ATT_HANDLE_VALUE_NTF, ATT_HANDLE_VALUE_IND -> {
                    operation = if (opcode == ATT_HANDLE_VALUE_NTF) AttOperation.NOTIFICATION else AttOperation.INDICATION
                    if (length >= 3) {
                        attributeHandle = u16le(pdu, 1)
                        valueOffset = 3
                        valueLength = length - 3
                    }
                }

                ATT_HANDLE_VALUE_CFM -> operation = AttOperation.CONFIRMATION

                // Vol 3 Part F, 3.4.2: client Rx MTU(2) / server Rx MTU(2). The ATT MTU in force
                // afterwards is the smaller of the two.
                ATT_EXCHANGE_MTU_REQ -> {
                    if (length >= 3) {
                        connection.clientRxMtu = u16le(pdu, 1)
                        connection.pendingOpcode = opcode
                        note = "exchange MTU request: client Rx MTU ${connection.clientRxMtu}"
                    }
                }

                ATT_EXCHANGE_MTU_RSP -> {
                    if (length >= 3) {
                        val serverRxMtu = u16le(pdu, 1)
                        val client = connection.clientRxMtu
                        val negotiated = if (client > 0) minOf(client, serverRxMtu) else serverRxMtu
                        connection.mtu = negotiated
                        connection.peer?.mtu = negotiated
                        note = if (client > 0) {
                            "ATT MTU $negotiated (client $client, server $serverRxMtu)"
                        } else {
                            "ATT MTU $negotiated (server $serverRxMtu)"
                        }
                    }
                    connection.pendingOpcode = 0
                }

                // 3.4.3.5 / 3.4.3.6: read blob request is handle(2) offset(2), response is value.
                ATT_READ_BLOB_REQ -> {
                    operation = AttOperation.READ_REQUEST
                    if (length >= 5) {
                        attributeHandle = u16le(pdu, 1)
                        connection.pendingOpcode = opcode
                        connection.pendingHandle = attributeHandle
                        note = "read blob from offset ${u16le(pdu, 3)}"
                    }
                }

                ATT_READ_BLOB_RSP -> {
                    operation = AttOperation.READ_RESPONSE
                    if (connection.pendingOpcode == ATT_READ_BLOB_REQ) attributeHandle = connection.pendingHandle
                    valueOffset = 1
                    valueLength = length - 1
                    note = "read blob response"
                    connection.pendingOpcode = 0
                }

                // 3.4.4.3 / 3.4.4.4: a set of handles, then their values concatenated.
                ATT_READ_MULTIPLE_REQ, ATT_READ_MULTIPLE_VARIABLE_REQ -> {
                    operation = AttOperation.READ_REQUEST
                    if (length >= 5) {
                        connection.pendingOpcode = opcode
                        connection.pendingHandle = u16le(pdu, 1)
                        attributeHandle = connection.pendingHandle
                        note = "read ${(length - 1) / 2} handles"
                    }
                }

                ATT_READ_MULTIPLE_RSP, ATT_READ_MULTIPLE_VARIABLE_RSP -> {
                    operation = AttOperation.READ_RESPONSE
                    valueOffset = 1
                    valueLength = length - 1
                    note = "concatenated values"
                    connection.pendingOpcode = 0
                }

                // 3.4.6: prepare write request is handle(2) offset(2) part value, and the response
                // echoes it; execute write request carries only its flags.
                ATT_PREPARE_WRITE_REQ, ATT_PREPARE_WRITE_RSP -> {
                    operation = if (opcode == ATT_PREPARE_WRITE_REQ) {
                        AttOperation.WRITE_REQUEST
                    } else {
                        AttOperation.WRITE_RESPONSE
                    }
                    if (length >= 5) {
                        attributeHandle = u16le(pdu, 1)
                        if (opcode == ATT_PREPARE_WRITE_REQ) {
                            connection.pendingOpcode = opcode
                            connection.pendingHandle = attributeHandle
                        }
                        valueOffset = 5
                        valueLength = length - 5
                        note = "prepare write at offset ${u16le(pdu, 3)}"
                    }
                }

                ATT_EXECUTE_WRITE_REQ -> {
                    operation = AttOperation.WRITE_REQUEST
                    if (length >= 2) {
                        connection.pendingOpcode = opcode
                        note = if (u8(pdu, 1) == 0x01) {
                            "execute write: write the queued values"
                        } else {
                            "execute write: cancel the queued values"
                        }
                    }
                }

                ATT_EXECUTE_WRITE_RSP -> {
                    operation = AttOperation.WRITE_RESPONSE
                    note = "execute write response"
                    connection.pendingOpcode = 0
                }

                // 3.4.5.4: handle(2) value, then a 12-byte authentication signature.
                ATT_SIGNED_WRITE_CMD -> {
                    operation = AttOperation.WRITE_COMMAND
                    if (length >= 3) {
                        attributeHandle = u16le(pdu, 1)
                        valueOffset = 3
                        valueLength = (length - 3 - ATT_SIGNATURE_BYTES).coerceAtLeast(0)
                        note = "signed write, 12 byte signature"
                    }
                }

                // 3.4.3.3 / 3.4.3.4: handle range and the attribute value being searched for.
                ATT_FIND_BY_TYPE_VALUE_REQ -> {
                    operation = AttOperation.DISCOVERY
                    if (length >= 7) {
                        connection.pendingOpcode = opcode
                        note = "find by type value ${typeName(u16le(pdu, 5))}"
                    }
                }

                ATT_FIND_BY_TYPE_VALUE_RSP -> {
                    operation = AttOperation.DISCOVERY
                    note = "${(length - 1) / 4} handle groups"
                    connection.pendingOpcode = 0
                }

                else -> {
                    operation = AttOperation.OTHER
                    unsupported++
                    note = "ATT opcode 0x%02X".format(opcode)
                }
            }

            connection.lastAttMicros = timestampEpochMicros

            // Value-bearing PDUs record the value bytes; everything else records the whole PDU so
            // the raw evidence survives even when the decode is partial.
            if (valueOffset < 0 || valueLength < 0) {
                valueOffset = 0
                valueLength = length
            }
            var retained = valueLength
            if (retained > maxPayloadBytes) {
                retained = maxPayloadBytes
                truncated++
                note = append(note, "payload truncated from $valueLength bytes")
            }
            if (originalLength > includedLength) {
                note = append(note, "capture kept $includedLength of $originalLength record bytes")
            }

            val characteristicUuid = attributeHandle?.let { connection.handleUuids[it] }
            val serviceUuid = attributeHandle?.let { connection.serviceFor(it) }
            val payloadHex = hex(pdu, valueOffset, retained)

            val id = hciEventId(
                recordOrdinal = records,
                timestampEpochMicros = timestampEpochMicros,
                connectionHandle = connectionHandle,
                operation = operation,
                attributeHandle = attributeHandle,
                payloadHex = payloadHex,
            )
            events += BleEvent(
                id = id,
                timestampEpochMicros = timestampEpochMicros,
                direction = direction,
                source = EventSource.HCI_SNOOP,
                operation = operation,
                connectionHandle = connectionHandle,
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                attributeHandle = attributeHandle,
                payloadHex = payloadHex,
                status = status,
                note = note,
            )
            attEvents++
            retainRaw(id)
        }

        private fun findInformation(connection: ConnectionState, pdu: ByteArray, length: Int): String {
            if (length < 2) return "find information response"
            val format = pdu[1].toInt() and 0xFF
            val entry = when (format) {
                1 -> 4
                2 -> 18
                else -> return "find information response, unknown format $format"
            }
            var offset = 2
            var count = 0
            while (offset + entry <= length) {
                val handle = u16le(pdu, offset)
                val uuid = if (format == 1) uuid16(u16le(pdu, offset + 2)) else uuid128(pdu, offset + 2)
                connection.handleUuids[handle] = uuid
                offset += entry
                count++
            }
            return "$count attribute UUIDs"
        }

        private fun readByType(connection: ConnectionState, pdu: ByteArray, length: Int): String {
            if (length < 2) return "read by type response"
            val entry = pdu[1].toInt() and 0xFF
            if (entry < 2) return "read by type response, bad entry size $entry"
            val declarations = connection.pendingOpcode == ATT_READ_BY_TYPE_REQ &&
                connection.pendingType == GATT_CHARACTERISTIC_DECLARATION
            var offset = 2
            var count = 0
            while (offset + entry <= length) {
                if (declarations && entry >= 7) {
                    // properties(1) value handle(2) uuid(2|16)
                    val valueHandle = u16le(pdu, offset + 3)
                    val uuid = when (entry) {
                        7 -> uuid16(u16le(pdu, offset + 5))
                        21 -> uuid128(pdu, offset + 5)
                        else -> null
                    }
                    if (uuid != null) connection.handleUuids[valueHandle] = uuid
                }
                offset += entry
                count++
            }
            return if (declarations) "$count characteristic declarations" else "$count attribute values"
        }

        private fun readByGroupType(connection: ConnectionState, pdu: ByteArray, length: Int): String {
            if (length < 2) return "read by group type response"
            val entry = pdu[1].toInt() and 0xFF
            if (entry < 6) return "read by group type response, bad entry size $entry"
            val services = connection.pendingOpcode == ATT_READ_BY_GROUP_TYPE_REQ &&
                (connection.pendingType == GATT_PRIMARY_SERVICE || connection.pendingType == GATT_SECONDARY_SERVICE)
            var offset = 2
            var count = 0
            while (offset + entry <= length) {
                if (services) {
                    val start = u16le(pdu, offset)
                    val end = u16le(pdu, offset + 2)
                    val uuid = when (entry) {
                        6 -> uuid16(u16le(pdu, offset + 4))
                        20 -> uuid128(pdu, offset + 4)
                        else -> null
                    }
                    if (uuid != null && end >= start) connection.services += ServiceRange(start, end, uuid)
                }
                offset += entry
                count++
            }
            return if (services) "$count services" else "$count attribute groups"
        }

        // ------------------------------------------------------------ emitting and summarising

        /**
         * Emits one lifecycle event. At most one of these comes out of a single record, which is
         * what keeps [hciEventId] collision-free: the record ordinal is part of its key.
         */
        private fun emitSystem(
            timestampEpochMicros: Long,
            connectionHandle: Int?,
            operation: AttOperation,
            note: String,
            status: Int? = null,
            direction: EventDirection = EventDirection.SYSTEM,
            payloadHex: String = "",
        ) {
            val id = hciEventId(
                recordOrdinal = records,
                timestampEpochMicros = timestampEpochMicros,
                connectionHandle = connectionHandle,
                operation = operation,
                attributeHandle = null,
                payloadHex = payloadHex,
            )
            events += BleEvent(
                id = id,
                timestampEpochMicros = timestampEpochMicros,
                direction = direction,
                source = EventSource.HCI_SNOOP,
                operation = operation,
                connectionHandle = connectionHandle,
                payloadHex = payloadHex,
                status = status,
                note = note,
            )
            systemEvents++
            retainRaw(id)
        }

        private fun retainRaw(id: String) {
            if (!rawPackets || rawByEvent.size >= maxRawEntries) return
            rawByEvent[id] = rawHex()
        }

        /** The current record's bytes, hex-encoded once however many events it produces. */
        private fun rawHex(): String {
            rawCache?.let { return it }
            val source = rawSource ?: return ""
            val value = hex(source, 0, rawLength.coerceAtMost(MAX_RAW_PACKET_BYTES))
            rawCache = value
            return value
        }

        fun summary(): ParseSummary {
            closeOpenConnections()
            return ParseSummary(
                records = records,
                attEvents = attEvents,
                systemEvents = systemEvents,
                unsupported = unsupported,
                countedOnly = countedOnly,
                truncated = truncated,
                warnings = warnings.toList(),
                connections = connectionCount,
                hciEvents = counts(hciEventCounts),
                hciCommands = counts(hciCommandCounts),
                smpPdus = counts(smpCounts),
                l2capSignals = counts(signalCounts),
                connectionsByPeer = peerSummaries(),
                advertisers = advertiserSummaries(),
            )
        }

        /** A link still up when the capture ended still lasted until the last record. */
        private fun closeOpenConnections() {
            for (connection in connections.values) {
                val peer = connection.peer ?: continue
                if (connection.startMicros > 0 && lastRecordMicros > connection.startMicros) {
                    peer.durationMicros += lastRecordMicros - connection.startMicros
                    connection.startMicros = -1
                }
            }
        }

        private fun counts(from: Map<String, Counter>): Map<String, Int> {
            if (from.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, Int>(from.size * 2)
            for ((name, counter) in from) out[name] = counter.value
            return out
        }

        private fun peerSummaries(): Map<String, ConnectionSummary> {
            if (peers.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, ConnectionSummary>(peers.size * 2)
            for ((address, peer) in peers) {
                out[address] = ConnectionSummary(
                    address = address,
                    addressType = peer.addressType,
                    handles = peer.handles.toList(),
                    intervalMs = if (peer.intervalUnits >= 0) connectionIntervalMs(peer.intervalUnits) else null,
                    latency = if (peer.latency >= 0) peer.latency else null,
                    supervisionTimeoutMs =
                        if (peer.timeoutUnits >= 0) supervisionTimeoutMs(peer.timeoutUnits) else null,
                    mtu = if (peer.mtu > 0) peer.mtu else null,
                    encrypted = peer.encrypted,
                    pairingMethod = peer.pairingMethod,
                    pairingSeen = peer.pairingSeen,
                    bonded = peer.bonded,
                    txPhy = if (peer.txPhy >= 0) HciNames.phyName(peer.txPhy) else null,
                    rxPhy = if (peer.rxPhy >= 0) HciNames.phyName(peer.rxPhy) else null,
                    disconnectReasons = peer.disconnectReasons.toList(),
                    durationMs = peer.durationMicros / 1000,
                    creditBasedChannels = peer.creditBasedChannels,
                    attempts = peer.attempts.toList(),
                    idleDisconnectSamplesMs = peer.idleSamplesMs.toList(),
                )
            }
            return out
        }

        private fun advertiserSummaries(): Map<String, AdvertiserSummary> {
            if (advertisers.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, AdvertiserSummary>(advertisers.size * 2)
            for ((address, advertiser) in advertisers) {
                out[address] = AdvertiserSummary(
                    address = address,
                    addressType = advertiser.addressType,
                    count = advertiser.count,
                    lastRssi = if (advertiser.lastRssi == NO_RSSI) null else advertiser.lastRssi,
                    names = advertiser.names.toSet(),
                    serviceUuids = advertiser.serviceUuids.toSet(),
                    manufacturerIds = advertiser.manufacturerData.keys.toSet(),
                    manufacturerData = advertiser.manufacturerData.toMap(),
                )
            }
            return out
        }
    }

    private companion object {
        const val READ_BUFFER = 64 * 1024
        const val FILE_HEADER_SIZE = 16
        const val RECORD_HEADER_SIZE = 24
        const val MAX_RECORD_BYTES = 70_000L
        const val MAX_L2CAP_BYTES = 64 * 1024
        const val MAX_WARNINGS = 32
        const val DATALINK_H4 = 1002L

        val MAGIC = byteArrayOf(0x62, 0x74, 0x73, 0x6E, 0x6F, 0x6F, 0x70, 0x00)

        /** Bits 0-11 of an ACL or event handle field (Vol 4 Part E, Section 5.4.2). */
        const val HANDLE_MASK = 0x0FFF
        const val PB_CONTINUATION = 0x01

        const val MAX_COUNTER_KEYS = 256
        const val MAX_PEER_HANDLES = 64
        const val MAX_PEER_SAMPLES = 32
        const val MAX_ADVERTISER_NAMES = 4
        const val MAX_ADVERTISER_UUIDS = 32
        const val MAX_ADVERTISER_COMPANIES = 8
        const val MAX_MANUFACTURER_BYTES = 32
        const val NO_RSSI = Int.MIN_VALUE

        const val ATT_ERROR_RSP = 0x01
        const val ATT_EXCHANGE_MTU_REQ = 0x02
        const val ATT_EXCHANGE_MTU_RSP = 0x03
        const val ATT_FIND_INFORMATION_REQ = 0x04
        const val ATT_FIND_INFORMATION_RSP = 0x05
        const val ATT_FIND_BY_TYPE_VALUE_REQ = 0x06
        const val ATT_FIND_BY_TYPE_VALUE_RSP = 0x07
        const val ATT_READ_BY_TYPE_REQ = 0x08
        const val ATT_READ_BY_TYPE_RSP = 0x09
        const val ATT_READ_REQ = 0x0A
        const val ATT_READ_RSP = 0x0B
        const val ATT_READ_BLOB_REQ = 0x0C
        const val ATT_READ_BLOB_RSP = 0x0D
        const val ATT_READ_MULTIPLE_REQ = 0x0E
        const val ATT_READ_MULTIPLE_RSP = 0x0F
        const val ATT_READ_BY_GROUP_TYPE_REQ = 0x10
        const val ATT_READ_BY_GROUP_TYPE_RSP = 0x11
        const val ATT_WRITE_REQ = 0x12
        const val ATT_WRITE_RSP = 0x13
        const val ATT_PREPARE_WRITE_REQ = 0x16
        const val ATT_PREPARE_WRITE_RSP = 0x17
        const val ATT_EXECUTE_WRITE_REQ = 0x18
        const val ATT_EXECUTE_WRITE_RSP = 0x19
        const val ATT_HANDLE_VALUE_NTF = 0x1B
        const val ATT_HANDLE_VALUE_IND = 0x1D
        const val ATT_HANDLE_VALUE_CFM = 0x1E
        const val ATT_READ_MULTIPLE_VARIABLE_REQ = 0x20
        const val ATT_READ_MULTIPLE_VARIABLE_RSP = 0x21
        const val ATT_WRITE_CMD = 0x52
        const val ATT_SIGNED_WRITE_CMD = 0xD2

        /** A signed write ends with a 12-byte authentication signature (Vol 3 Part F, 3.3.1). */
        const val ATT_SIGNATURE_BYTES = 12

        const val GATT_PRIMARY_SERVICE = 0x2800
        const val GATT_SECONDARY_SERVICE = 0x2801
        const val GATT_CHARACTERISTIC_DECLARATION = 0x2803

        fun append(note: String, extra: String): String = if (note.isEmpty()) extra else "$note; $extra"

        fun be32(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)

        fun be64(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
            return value
        }

        fun read(stream: InputStream, into: ByteArray, length: Int): Int {
            var total = 0
            while (total < length) {
                val read = stream.read(into, total, length - total)
                if (read < 0) break
                total += read
            }
            return total
        }

        fun readFully(stream: InputStream, into: ByteArray, length: Int): Boolean =
            read(stream, into, length) == length
    }
}

/**
 * Microseconds between the btsnoop epoch (0000-01-01) and 1970-01-01.
 *
 * AOSP's own writer adds exactly this constant to a Unix microsecond clock
 * (`packages/modules/Bluetooth/system/gd/hal/snoop_logger.cc`, `kBtSnoopEpochDelta`), and
 * `btsnooz.py` uses the same value. It equals `0x00E03AB44A676000` (0 AD to 2000-01-01) minus
 * 946_684_800_000_000 (1970-01-01 to 2000-01-01), so subtracting it yields Unix epoch microseconds
 * — the unit every other BLE Studio timestamp uses.
 */
const val BTSNOOP_EPOCH_DELTA_MICROS = 0x00dcddb30f2f8000L
