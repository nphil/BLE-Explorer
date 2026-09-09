package dev.nphil.blestudio.hci

/**
 * Names the parser already learned, so a dissection can show a peer address next to a connection
 * handle and a UUID next to an attribute handle.
 */
data class DissectionContext(
    val peerAddressByHandle: Map<Int, String> = emptyMap(),
    val uuidByHandle: Map<Int, String> = emptyMap(),
)

/** Inverts [ParseSummary.connectionsByPeer] into the handle to address map a dissection wants. */
fun dissectionContextOf(summary: ParseSummary): DissectionContext {
    if (summary.connectionsByPeer.isEmpty()) return DissectionContext()
    val addresses = HashMap<Int, String>(summary.connectionsByPeer.size * 2)
    for (connection in summary.connectionsByPeer.values) {
        for (handle in connection.handles) addresses[handle] = connection.address
    }
    return DissectionContext(peerAddressByHandle = addresses)
}

/**
 * Turns one H4 packet into a Wireshark-style dissection tree.
 *
 * Layouts come from the Bluetooth Core Specification v5.4: H4 framing from Vol 4 Part A Section 2,
 * HCI packets and parameters from Vol 4 Part E Sections 5.4, 7.7 and 7.8, L2CAP from Vol 3 Part A
 * Sections 3 and 4, SMP from Vol 3 Part H Section 3, ATT from Vol 3 Part F Section 3.4.
 *
 * The dissector never throws and never guesses: a field that runs off the end of the captured bytes
 * becomes a `[truncated]` leaf, and an opcode with no known layout is named and left as raw bytes.
 * SMP PDUs that carry key material are reported by size only, never by value.
 */
object HciDissector {

    /** Hex is only shown for short fields; a long value is summarised by its length. */
    private const val MAX_HEX_BYTES = 64

    fun dissect(rawPacketWithH4Type: ByteArray, context: DissectionContext? = null): DissectionNode =
        dissect(rawPacketWithH4Type, rawPacketWithH4Type.size, context)

    fun dissect(packet: ByteArray, length: Int, context: DissectionContext? = null): DissectionNode {
        if (length < 1) return DissectionNode("H4", "[empty packet]")
        val type = u8(packet, 0)
        val children = when (type) {
            HciNames.H4_ACL -> listOf(acl(packet, length, context))
            HciNames.H4_EVENT -> listOf(event(packet, length, context))
            HciNames.H4_COMMAND -> listOf(command(packet, length, context))
            else -> listOf(DissectionNode("Payload", bytesValue(packet, 1, length - 1)))
        }
        return DissectionNode("H4", "${HciNames.h4TypeName(type)} (0x%02X)".format(type), children)
    }

    // ------------------------------------------------------------------ ACL and L2CAP

    /** Vol 4 Part E, Section 5.4.2: handle(12 bits), PB(2), BC(2), data total length(2). */
    private fun acl(packet: ByteArray, length: Int, context: DissectionContext?): DissectionNode {
        val fields = ArrayList<DissectionNode>(6)
        if (length < 5) {
            fields += DissectionNode("[truncated]", "ACL header needs 4 bytes, ${length - 1} captured")
            return DissectionNode("HCI ACL Data", null, fields)
        }
        val header = u16le(packet, 1)
        val handle = header and 0x0FFF
        val pb = (header shr 12) and 0x03
        val bc = (header shr 14) and 0x03
        val declared = u16le(packet, 3)
        val peer = context?.peerAddressByHandle?.get(handle)
        fields += DissectionNode(
            "Connection handle",
            if (peer == null) "0x%04X".format(handle) else "0x%04X (%s)".format(handle, peer),
        )
        fields += DissectionNode("PB flag", "0x%02X (%s)".format(pb, HciNames.packetBoundaryName(pb)))
        fields += DissectionNode("BC flag", "0x%02X (%s)".format(bc, HciNames.broadcastFlagName(bc)))
        fields += DissectionNode("Data total length", declared.toString())
        val available = length - 5
        if (available < declared) {
            fields += DissectionNode("[truncated]", "$available of $declared payload bytes captured")
        }
        if (pb == 0x01) {
            fields += DissectionNode("Continuation fragment", bytesValue(packet, 5, available))
            return DissectionNode("HCI ACL Data", null, fields)
        }
        if (available >= 4) fields += l2cap(packet, 5, available, handle, context)
        return DissectionNode("HCI ACL Data", null, fields)
    }

    /** Vol 3 Part A, Section 3.1: length(2), CID(2), then the channel's payload. */
    private fun l2cap(
        packet: ByteArray,
        offset: Int,
        available: Int,
        handle: Int,
        context: DissectionContext?,
    ): DissectionNode {
        val declared = u16le(packet, offset)
        val cid = u16le(packet, offset + 2)
        val fields = ArrayList<DissectionNode>(4)
        fields += DissectionNode("Length", declared.toString())
        fields += DissectionNode("CID", "0x%04X (%s)".format(cid, HciNames.cidName(cid)))
        val body = offset + 4
        val bodyLength = (available - 4).coerceAtMost(declared)
        if (bodyLength < declared) {
            fields += DissectionNode("[truncated]", "$bodyLength of $declared PDU bytes captured")
        }
        if (bodyLength > 0) {
            fields += when (cid) {
                HciNames.CID_ATT -> att(packet, body, bodyLength, handle, context)
                HciNames.CID_SMP -> smp(packet, body, bodyLength)
                HciNames.CID_SIGNALING -> signaling(packet, body, bodyLength)
                else -> DissectionNode("Payload", bytesValue(packet, body, bodyLength))
            }
        }
        return DissectionNode("L2CAP", null, fields)
    }

    // ------------------------------------------------------------------ ATT (Vol 3 Part F, 3.4)

    private fun att(
        packet: ByteArray,
        offset: Int,
        length: Int,
        handle: Int,
        context: DissectionContext?,
    ): DissectionNode {
        val opcode = u8(packet, offset)
        val fields = ArrayList<DissectionNode>(5)
        fields += DissectionNode("Opcode", "0x%02X (%s)".format(opcode, HciNames.attOpcodeName(opcode)))
        val body = offset + 1
        val rest = length - 1

        fun attributeHandle(label: String, at: Int) {
            val value = u16le(packet, at)
            val uuid = context?.uuidByHandle?.get(value)
            fields += DissectionNode(
                label,
                if (uuid == null) "0x%04X".format(value) else "0x%04X (%s)".format(value, uuid),
            )
        }

        when {
            opcode == 0x01 && rest >= 4 -> {
                val failed = u8(packet, body)
                fields += DissectionNode(
                    "Request opcode in error",
                    "0x%02X (%s)".format(failed, HciNames.attOpcodeName(failed)),
                )
                attributeHandle("Attribute handle", body + 1)
                val code = u8(packet, body + 3)
                fields += DissectionNode("Error code", "0x%02X (%s)".format(code, attError(code)))
            }

            (opcode == 0x02 || opcode == 0x03) && rest >= 2 ->
                fields += DissectionNode(
                    if (opcode == 0x02) "Client Rx MTU" else "Server Rx MTU",
                    u16le(packet, body).toString(),
                )

            (opcode == 0x04 || opcode == 0x08 || opcode == 0x10 || opcode == 0x06) && rest >= 4 -> {
                fields += DissectionNode("Starting handle", "0x%04X".format(u16le(packet, body)))
                fields += DissectionNode("Ending handle", "0x%04X".format(u16le(packet, body + 2)))
                if (rest >= 6) {
                    val type = u16le(packet, body + 4)
                    if (rest == 6 || rest == 8) {
                        fields += DissectionNode("Attribute type", "0x%04X (%s)".format(type, typeName(type)))
                    } else if (rest >= 20) {
                        fields += DissectionNode("Attribute type", uuid128(packet, body + 4))
                    }
                }
                if (opcode == 0x06 && rest > 6) {
                    fields += DissectionNode("Attribute value", bytesValue(packet, body + 6, rest - 6))
                }
            }

            opcode == 0x05 && rest >= 1 -> {
                val format = u8(packet, body)
                fields += DissectionNode("Format", if (format == 1) "16-bit UUIDs" else "128-bit UUIDs")
                fields += DissectionNode("Information data", bytesValue(packet, body + 1, rest - 1))
            }

            (opcode == 0x09 || opcode == 0x11) && rest >= 1 -> {
                fields += DissectionNode("Length per entry", u8(packet, body).toString())
                fields += DissectionNode("Attribute data list", bytesValue(packet, body + 1, rest - 1))
            }

            opcode == 0x0A && rest >= 2 -> attributeHandle("Attribute handle", body)

            opcode == 0x0C && rest >= 4 -> {
                attributeHandle("Attribute handle", body)
                fields += DissectionNode("Value offset", u16le(packet, body + 2).toString())
            }

            (opcode == 0x0B || opcode == 0x0D || opcode == 0x0F) ->
                fields += DissectionNode("Attribute value", bytesValue(packet, body, rest))

            opcode == 0x0E && rest >= 2 -> {
                var cursor = body
                val handles = StringBuilder(rest * 4)
                while (cursor + 2 <= offset + length) {
                    if (handles.isNotEmpty()) handles.append(", ")
                    handles.append("0x%04X".format(u16le(packet, cursor)))
                    cursor += 2
                }
                fields += DissectionNode("Set of handles", handles.toString())
            }

            (opcode == 0x12 || opcode == 0x52 || opcode == 0x1B || opcode == 0x1D) && rest >= 2 -> {
                attributeHandle("Attribute handle", body)
                fields += DissectionNode("Attribute value", bytesValue(packet, body + 2, rest - 2))
            }

            opcode == 0xD2 && rest >= 2 -> {
                attributeHandle("Attribute handle", body)
                val valueLength = (rest - 2 - SIGNATURE_BYTES).coerceAtLeast(0)
                fields += DissectionNode("Attribute value", bytesValue(packet, body + 2, valueLength))
                if (rest - 2 >= SIGNATURE_BYTES) {
                    fields += DissectionNode("Authentication signature", "$SIGNATURE_BYTES bytes")
                }
            }

            opcode == 0x16 && rest >= 4 -> {
                attributeHandle("Attribute handle", body)
                fields += DissectionNode("Value offset", u16le(packet, body + 2).toString())
                fields += DissectionNode("Part attribute value", bytesValue(packet, body + 4, rest - 4))
            }

            opcode == 0x17 && rest >= 4 -> {
                attributeHandle("Attribute handle", body)
                fields += DissectionNode("Value offset", u16le(packet, body + 2).toString())
                fields += DissectionNode("Part attribute value", bytesValue(packet, body + 4, rest - 4))
            }

            opcode == 0x18 && rest >= 1 -> {
                val flags = u8(packet, body)
                fields += DissectionNode("Flags", if (flags == 0x01) "write pending prepared values" else "cancel all prepared writes")
            }

            (opcode == 0x20 || opcode == 0x21) && rest >= 1 ->
                fields += DissectionNode("Payload", bytesValue(packet, body, rest))

            rest > 0 -> fields += DissectionNode("Parameters", bytesValue(packet, body, rest))
        }
        return DissectionNode("ATT", HciNames.attOpcodeName(opcode), fields)
    }

    // ------------------------------------------------------------------ SMP (Vol 3 Part H, 3)

    private fun smp(packet: ByteArray, offset: Int, length: Int): DissectionNode {
        val code = u8(packet, offset)
        val name = HciNames.smpOpcodeName(code)
        val fields = ArrayList<DissectionNode>(8)
        fields += DissectionNode("Code", "0x%02X (%s)".format(code, name))
        val body = offset + 1
        val rest = length - 1
        when {
            (code == HciNames.SMP_PAIRING_REQUEST || code == HciNames.SMP_PAIRING_RESPONSE) && rest >= 6 -> {
                val io = u8(packet, body)
                val oob = u8(packet, body + 1)
                val auth = u8(packet, body + 2)
                fields += DissectionNode("IO capability", "0x%02X (%s)".format(io, HciNames.ioCapabilityName(io)))
                fields += DissectionNode("OOB data flag", "0x%02X (%s)".format(oob, HciNames.oobDataFlagName(oob)))
                fields += DissectionNode("AuthReq", "0x%02X (%s)".format(auth, HciNames.authReqText(auth)))
                fields += DissectionNode("Maximum encryption key size", u8(packet, body + 3).toString())
                fields += DissectionNode(
                    "Initiator key distribution",
                    "0x%02X (%s)".format(u8(packet, body + 4), HciNames.keyDistributionText(u8(packet, body + 4))),
                )
                fields += DissectionNode(
                    "Responder key distribution",
                    "0x%02X (%s)".format(u8(packet, body + 5), HciNames.keyDistributionText(u8(packet, body + 5))),
                )
            }

            code == HciNames.SMP_PAIRING_FAILED && rest >= 1 -> {
                val reason = u8(packet, body)
                fields += DissectionNode("Reason", "0x%02X (%s)".format(reason, HciNames.smpFailureReason(reason)))
            }

            code == HciNames.SMP_SECURITY_REQUEST && rest >= 1 -> {
                val auth = u8(packet, body)
                fields += DissectionNode("AuthReq", "0x%02X (%s)".format(auth, HciNames.authReqText(auth)))
            }

            code == HciNames.SMP_KEYPRESS_NOTIFICATION && rest >= 1 ->
                fields += DissectionNode("Notification type", "0x%02X".format(u8(packet, body)))

            HciNames.smpCarriesKeyMaterial(code) && rest > 0 ->
                fields += DissectionNode("Value", "[redacted key material] ($rest bytes)")

            rest > 0 -> fields += DissectionNode("Parameters", bytesValue(packet, body, rest))
        }
        return DissectionNode("SMP", name, fields)
    }

    // ------------------------------------------------------------------ L2CAP signalling (Vol 3 Part A, 4)

    private fun signaling(packet: ByteArray, offset: Int, length: Int): DissectionNode {
        val code = u8(packet, offset)
        val name = HciNames.l2capSignalName(code)
        val fields = ArrayList<DissectionNode>(7)
        fields += DissectionNode("Code", "0x%02X (%s)".format(code, name))
        if (length < 4) {
            fields += DissectionNode("[truncated]", "signalling header needs 4 bytes, $length captured")
            return DissectionNode("L2CAP Signalling", name, fields)
        }
        fields += DissectionNode("Identifier", "0x%02X".format(u8(packet, offset + 1)))
        val declared = u16le(packet, offset + 2)
        fields += DissectionNode("Length", declared.toString())
        val body = offset + 4
        val rest = (length - 4).coerceAtMost(declared)
        when {
            code == HciNames.L2CAP_CONN_PARAM_UPDATE_REQ && rest >= 8 -> {
                fields += DissectionNode("Interval min", formatMillis(connectionIntervalMs(u16le(packet, body))))
                fields += DissectionNode("Interval max", formatMillis(connectionIntervalMs(u16le(packet, body + 2))))
                fields += DissectionNode("Peripheral latency", u16le(packet, body + 4).toString())
                fields += DissectionNode("Timeout multiplier", formatMillis(u16le(packet, body + 6) * 10.0))
            }

            code == HciNames.L2CAP_CONN_PARAM_UPDATE_RSP && rest >= 2 -> {
                val result = u16le(packet, body)
                fields += DissectionNode(
                    "Result",
                    "0x%04X (%s)".format(result, HciNames.connectionParameterResultText(result)),
                )
            }

            (code == HciNames.L2CAP_LE_CREDIT_CONN_REQ || code == HciNames.L2CAP_CREDIT_CONN_REQ) && rest >= 10 -> {
                val spsm = u16le(packet, body)
                fields += DissectionNode("SPSM", "0x%04X (%s)".format(spsm, HciNames.spsmName(spsm)))
                fields += DissectionNode("Source CID", "0x%04X".format(u16le(packet, body + 2)))
                fields += DissectionNode("MTU", u16le(packet, body + 4).toString())
                fields += DissectionNode("MPS", u16le(packet, body + 6).toString())
                fields += DissectionNode("Initial credits", u16le(packet, body + 8).toString())
            }

            code == HciNames.L2CAP_LE_CREDIT_CONN_RSP && rest >= 10 -> {
                fields += DissectionNode("Destination CID", "0x%04X".format(u16le(packet, body)))
                fields += DissectionNode("MTU", u16le(packet, body + 2).toString())
                fields += DissectionNode("MPS", u16le(packet, body + 4).toString())
                fields += DissectionNode("Initial credits", u16le(packet, body + 6).toString())
                val result = u16le(packet, body + 8)
                fields += DissectionNode(
                    "Result",
                    "0x%04X (%s)".format(result, HciNames.leCreditConnectionResultText(result)),
                )
            }

            rest > 0 -> fields += DissectionNode("Parameters", bytesValue(packet, body, rest))
        }
        return DissectionNode("L2CAP Signalling", name, fields)
    }

    // ------------------------------------------------------------------ HCI events (Vol 4 Part E, 7.7)

    private fun event(packet: ByteArray, length: Int, context: DissectionContext?): DissectionNode {
        val fields = ArrayList<DissectionNode>(8)
        if (length < 3) {
            fields += DissectionNode("[truncated]", "event header needs 2 bytes, ${length - 1} captured")
            return DissectionNode("HCI Event", null, fields)
        }
        val code = u8(packet, 1)
        val declared = u8(packet, 2)
        val name = HciNames.eventName(code)
        fields += DissectionNode("Event code", "0x%02X (%s)".format(code, name))
        fields += DissectionNode("Parameter total length", declared.toString())
        val body = 3
        val rest = (length - 3).coerceAtMost(declared)
        if (length - 3 < declared) {
            fields += DissectionNode("[truncated]", "${length - 3} of $declared parameter bytes captured")
        }
        when {
            code == HciNames.EVT_DISCONNECTION_COMPLETE && rest >= 4 -> {
                fields += statusField(u8(packet, body))
                fields += handleField(u16le(packet, body + 1), context)
                val reason = u8(packet, body + 3)
                fields += DissectionNode("Reason", "0x%02X (%s)".format(reason, HciNames.statusText(reason)))
            }

            code == HciNames.EVT_ENCRYPTION_CHANGE && rest >= 4 -> {
                fields += statusField(u8(packet, body))
                fields += handleField(u16le(packet, body + 1), context)
                fields += DissectionNode("Encryption enabled", encryptionText(u8(packet, body + 3)))
            }

            code == HciNames.EVT_ENCRYPTION_CHANGE_V2 && rest >= 5 -> {
                fields += statusField(u8(packet, body))
                fields += handleField(u16le(packet, body + 1), context)
                fields += DissectionNode("Encryption enabled", encryptionText(u8(packet, body + 3)))
                fields += DissectionNode("Encryption key size", u8(packet, body + 4).toString())
            }

            code == HciNames.EVT_COMMAND_COMPLETE && rest >= 3 -> {
                fields += DissectionNode("Num HCI command packets", u8(packet, body).toString())
                fields += opcodeField(u16le(packet, body + 1))
                if (rest >= 4) fields += statusField(u8(packet, body + 3))
                if (rest > 4) fields += DissectionNode("Return parameters", bytesValue(packet, body + 4, rest - 4))
            }

            code == HciNames.EVT_COMMAND_STATUS && rest >= 4 -> {
                fields += statusField(u8(packet, body))
                fields += DissectionNode("Num HCI command packets", u8(packet, body + 1).toString())
                fields += opcodeField(u16le(packet, body + 2))
            }

            code == HciNames.EVT_NUMBER_OF_COMPLETED_PACKETS && rest >= 1 -> {
                val handles = u8(packet, body)
                fields += DissectionNode("Number of handles", handles.toString())
                var cursor = body + 1
                var index = 0
                while (index < handles && cursor + 4 <= body + rest) {
                    fields += DissectionNode(
                        "Handle 0x%04X".format(u16le(packet, cursor)),
                        "${u16le(packet, cursor + 2)} completed packets",
                    )
                    cursor += 4
                    index++
                }
            }

            code == HciNames.EVT_LE_META && rest >= 1 -> fields += leMeta(packet, body, rest, context)

            rest > 0 -> fields += DissectionNode("Parameters", bytesValue(packet, body, rest))
        }
        return DissectionNode("HCI Event", name, fields)
    }

    /** Vol 4 Part E, Section 7.7.65. */
    private fun leMeta(packet: ByteArray, offset: Int, length: Int, context: DissectionContext?): DissectionNode {
        val subevent = u8(packet, offset)
        val name = HciNames.leSubeventName(subevent)
        val fields = ArrayList<DissectionNode>(12)
        fields += DissectionNode("Subevent code", "0x%02X (%s)".format(subevent, name))
        val body = offset + 1
        val rest = length - 1
        when {
            subevent == HciNames.LE_CONNECTION_COMPLETE && rest >= 18 -> {
                fields += statusField(u8(packet, body))
                fields += handleField(u16le(packet, body + 1), context)
                fields += DissectionNode("Role", "0x%02X (%s)".format(u8(packet, body + 3), HciNames.roleName(u8(packet, body + 3))))
                appendPeerAddress(packet, body + 4, fields)
                fields += DissectionNode("Connection interval", formatMillis(connectionIntervalMs(u16le(packet, body + 11))))
                fields += DissectionNode("Peripheral latency", u16le(packet, body + 13).toString())
                fields += DissectionNode("Supervision timeout", formatMillis(u16le(packet, body + 15) * 10.0))
                fields += DissectionNode("Central clock accuracy", "0x%02X".format(u8(packet, body + 17)))
            }

            subevent == HciNames.LE_ENHANCED_CONNECTION_COMPLETE && rest >= 30 -> {
                fields += statusField(u8(packet, body))
                fields += handleField(u16le(packet, body + 1), context)
                fields += DissectionNode("Role", "0x%02X (%s)".format(u8(packet, body + 3), HciNames.roleName(u8(packet, body + 3))))
                appendPeerAddress(packet, body + 4, fields)
                fields += DissectionNode("Local resolvable private address", bdAddr(packet, body + 11))
                fields += DissectionNode("Peer resolvable private address", bdAddr(packet, body + 17))
                fields += DissectionNode("Connection interval", formatMillis(connectionIntervalMs(u16le(packet, body + 23))))
                fields += DissectionNode("Peripheral latency", u16le(packet, body + 25).toString())
                fields += DissectionNode("Supervision timeout", formatMillis(u16le(packet, body + 27) * 10.0))
                fields += DissectionNode("Central clock accuracy", "0x%02X".format(u8(packet, body + 29)))
            }

            subevent == HciNames.LE_CONNECTION_UPDATE_COMPLETE && rest >= 9 -> {
                fields += statusField(u8(packet, body))
                fields += handleField(u16le(packet, body + 1), context)
                fields += DissectionNode("Connection interval", formatMillis(connectionIntervalMs(u16le(packet, body + 3))))
                fields += DissectionNode("Peripheral latency", u16le(packet, body + 5).toString())
                fields += DissectionNode("Supervision timeout", formatMillis(u16le(packet, body + 7) * 10.0))
            }

            subevent == HciNames.LE_READ_REMOTE_FEATURES_COMPLETE && rest >= 11 -> {
                fields += statusField(u8(packet, body))
                fields += handleField(u16le(packet, body + 1), context)
                fields += DissectionNode("LE features", hex(packet, body + 3, 8))
            }

            subevent == HciNames.LE_DATA_LENGTH_CHANGE && rest >= 10 -> {
                fields += handleField(u16le(packet, body), context)
                fields += DissectionNode("Max Tx octets", u16le(packet, body + 2).toString())
                fields += DissectionNode("Max Tx time", "${u16le(packet, body + 4)} us")
                fields += DissectionNode("Max Rx octets", u16le(packet, body + 6).toString())
                fields += DissectionNode("Max Rx time", "${u16le(packet, body + 8)} us")
            }

            subevent == HciNames.LE_PHY_UPDATE_COMPLETE && rest >= 5 -> {
                fields += statusField(u8(packet, body))
                fields += handleField(u16le(packet, body + 1), context)
                fields += DissectionNode("Tx PHY", HciNames.phyName(u8(packet, body + 3)))
                fields += DissectionNode("Rx PHY", HciNames.phyName(u8(packet, body + 4)))
            }

            subevent == HciNames.LE_ADVERTISING_REPORT && rest >= 10 -> {
                fields += DissectionNode("Num reports", u8(packet, body).toString())
                val type = u8(packet, body + 1)
                fields += DissectionNode(
                    "Event type",
                    "0x%02X (%s)".format(type, HciNames.advertisingEventTypeName(type)),
                )
                val addressType = u8(packet, body + 2)
                fields += DissectionNode(
                    "Address type",
                    "0x%02X (%s)".format(addressType, HciNames.addressTypeName(addressType)),
                )
                fields += DissectionNode("Address", bdAddr(packet, body + 3))
                val dataLength = u8(packet, body + 9)
                fields += DissectionNode("Data length", dataLength.toString())
                if (rest >= 10 + dataLength) {
                    fields += advertisingData(packet, body + 10, dataLength)
                    fields += DissectionNode("RSSI", "${packet[body + 10 + dataLength].toInt()} dBm")
                }
            }

            subevent == HciNames.LE_EXTENDED_ADVERTISING_REPORT && rest >= 25 -> {
                fields += DissectionNode("Num reports", u8(packet, body).toString())
                fields += DissectionNode("Event type", "0x%04X".format(u16le(packet, body + 1)))
                val addressType = u8(packet, body + 3)
                fields += DissectionNode(
                    "Address type",
                    "0x%02X (%s)".format(addressType, HciNames.addressTypeName(addressType)),
                )
                fields += DissectionNode("Address", bdAddr(packet, body + 4))
                fields += DissectionNode("Primary PHY", HciNames.phyName(u8(packet, body + 10)))
                fields += DissectionNode("Secondary PHY", HciNames.phyName(u8(packet, body + 11)))
                fields += DissectionNode("Advertising SID", "0x%02X".format(u8(packet, body + 12)))
                fields += DissectionNode("Tx power", "${packet[body + 13].toInt()} dBm")
                fields += DissectionNode("RSSI", "${packet[body + 14].toInt()} dBm")
                fields += DissectionNode("Periodic interval", "${u16le(packet, body + 15)} units")
                val dataLength = u8(packet, body + 24)
                fields += DissectionNode("Data length", dataLength.toString())
                if (rest >= 25 + dataLength) fields += advertisingData(packet, body + 25, dataLength)
            }

            rest > 0 -> fields += DissectionNode("Parameters", bytesValue(packet, body, rest))
        }
        return DissectionNode("LE Meta", name, fields)
    }

    // ------------------------------------------------------------------ HCI commands (Vol 4 Part E, 7.8)

    private fun command(packet: ByteArray, length: Int, context: DissectionContext?): DissectionNode {
        val fields = ArrayList<DissectionNode>(10)
        if (length < 4) {
            fields += DissectionNode("[truncated]", "command header needs 3 bytes, ${length - 1} captured")
            return DissectionNode("HCI Command", null, fields)
        }
        val opcode = u16le(packet, 1)
        val declared = u8(packet, 3)
        val name = HciNames.commandName(opcode)
        fields += opcodeField(opcode)
        fields += DissectionNode("Parameter total length", declared.toString())
        val body = 4
        val rest = (length - 4).coerceAtMost(declared)
        if (length - 4 < declared) {
            fields += DissectionNode("[truncated]", "${length - 4} of $declared parameter bytes captured")
        }
        when {
            opcode == HciNames.CMD_DISCONNECT && rest >= 3 -> {
                fields += handleField(u16le(packet, body), context)
                val reason = u8(packet, body + 2)
                fields += DissectionNode("Reason", "0x%02X (%s)".format(reason, HciNames.statusText(reason)))
            }

            opcode == HciNames.CMD_LE_CREATE_CONNECTION && rest >= 25 -> {
                fields += DissectionNode("Scan interval", formatMillis(u16le(packet, body) * 0.625))
                fields += DissectionNode("Scan window", formatMillis(u16le(packet, body + 2) * 0.625))
                val policy = u8(packet, body + 4)
                fields += DissectionNode(
                    "Initiator filter policy",
                    "0x%02X (%s)".format(policy, HciNames.initiatorFilterPolicyName(policy)),
                )
                val addressType = u8(packet, body + 5)
                fields += DissectionNode(
                    "Peer address type",
                    "0x%02X (%s)".format(addressType, HciNames.addressTypeName(addressType)),
                )
                fields += DissectionNode("Peer address", bdAddr(packet, body + 6))
                fields += DissectionNode("Own address type", "0x%02X".format(u8(packet, body + 12)))
                fields += DissectionNode("Connection interval min", formatMillis(connectionIntervalMs(u16le(packet, body + 13))))
                fields += DissectionNode("Connection interval max", formatMillis(connectionIntervalMs(u16le(packet, body + 15))))
                fields += DissectionNode("Max latency", u16le(packet, body + 17).toString())
                fields += DissectionNode("Supervision timeout", formatMillis(u16le(packet, body + 19) * 10.0))
            }

            opcode == HciNames.CMD_LE_EXTENDED_CREATE_CONNECTION && rest >= 10 -> {
                val policy = u8(packet, body)
                fields += DissectionNode(
                    "Initiator filter policy",
                    "0x%02X (%s)".format(policy, HciNames.initiatorFilterPolicyName(policy)),
                )
                fields += DissectionNode("Own address type", "0x%02X".format(u8(packet, body + 1)))
                val addressType = u8(packet, body + 2)
                fields += DissectionNode(
                    "Peer address type",
                    "0x%02X (%s)".format(addressType, HciNames.addressTypeName(addressType)),
                )
                fields += DissectionNode("Peer address", bdAddr(packet, body + 3))
                fields += DissectionNode("Initiating PHYs", "0x%02X".format(u8(packet, body + 9)))
            }

            opcode == HciNames.CMD_LE_SET_SCAN_PARAMETERS && rest >= 7 -> {
                val type = u8(packet, body)
                fields += DissectionNode("Scan type", "0x%02X (%s)".format(type, HciNames.scanTypeName(type)))
                fields += DissectionNode("Scan interval", formatMillis(u16le(packet, body + 1) * 0.625))
                fields += DissectionNode("Scan window", formatMillis(u16le(packet, body + 3) * 0.625))
                fields += DissectionNode("Own address type", "0x%02X".format(u8(packet, body + 5)))
                fields += DissectionNode("Filter policy", "0x%02X".format(u8(packet, body + 6)))
            }

            opcode == HciNames.CMD_LE_SET_SCAN_ENABLE && rest >= 2 -> {
                fields += DissectionNode("Scan enable", if (u8(packet, body) != 0) "enabled" else "disabled")
                fields += DissectionNode(
                    "Filter duplicates",
                    if (u8(packet, body + 1) != 0) "enabled" else "disabled",
                )
            }

            opcode == HciNames.CMD_LE_SET_ADVERTISING_DATA && rest >= 1 -> {
                val dataLength = u8(packet, body)
                fields += DissectionNode("Data length", dataLength.toString())
                if (rest >= 1 + dataLength) fields += advertisingData(packet, body + 1, dataLength)
            }

            opcode == HciNames.CMD_LE_ENABLE_ENCRYPTION && rest >= 28 -> {
                fields += handleField(u16le(packet, body), context)
                fields += DissectionNode("Random number", "[redacted key material] (8 bytes)")
                fields += DissectionNode("Encrypted diversifier", "[redacted key material] (2 bytes)")
                fields += DissectionNode("Long term key", "[redacted key material] (16 bytes)")
            }

            opcode == HciNames.CMD_LE_CONNECTION_UPDATE && rest >= 14 -> {
                fields += handleField(u16le(packet, body), context)
                fields += DissectionNode("Connection interval min", formatMillis(connectionIntervalMs(u16le(packet, body + 2))))
                fields += DissectionNode("Connection interval max", formatMillis(connectionIntervalMs(u16le(packet, body + 4))))
                fields += DissectionNode("Max latency", u16le(packet, body + 6).toString())
                fields += DissectionNode("Supervision timeout", formatMillis(u16le(packet, body + 8) * 10.0))
            }

            rest > 0 -> fields += DissectionNode("Parameters", bytesValue(packet, body, rest))
        }
        return DissectionNode("HCI Command", name, fields)
    }

    // ------------------------------------------------------------------ shared leaves

    private fun statusField(status: Int): DissectionNode =
        DissectionNode("Status", "0x%02X (%s)".format(status, HciNames.statusText(status)))

    private fun handleField(handle: Int, context: DissectionContext?): DissectionNode {
        val peer = context?.peerAddressByHandle?.get(handle)
        return DissectionNode(
            "Connection handle",
            if (peer == null) "0x%04X".format(handle) else "0x%04X (%s)".format(handle, peer),
        )
    }

    private fun opcodeField(opcode: Int): DissectionNode = DissectionNode(
        "Opcode",
        "0x%04X (%s, OGF 0x%02X, OCF 0x%03X)".format(
            opcode,
            HciNames.commandName(opcode),
            HciNames.opcodeGroup(opcode),
            HciNames.opcodeCommand(opcode),
        ),
    )

    private fun encryptionText(value: Int): String = when (value) {
        0x00 -> "off"
        0x01 -> "on, E0 or AES-CCM"
        0x02 -> "on, AES-CCM for BR/EDR"
        else -> "0x%02X".format(value)
    }

    /** Peer_Address_Type then Peer_Address, in the order Vol 4 Part E gives them. */
    private fun appendPeerAddress(packet: ByteArray, offset: Int, into: MutableList<DissectionNode>) {
        val type = u8(packet, offset)
        into += DissectionNode("Peer address type", "0x%02X (%s)".format(type, HciNames.addressTypeName(type)))
        into += DissectionNode("Peer address", bdAddr(packet, offset + 1))
    }

    /** Core Specification Supplement, Part A, Section 1: a list of AD structures. */
    private fun advertisingData(packet: ByteArray, offset: Int, length: Int): DissectionNode {
        val children = ArrayList<DissectionNode>(6)
        forEachAdStructure(packet, offset, length) { type, valueOffset, valueLength ->
            children += DissectionNode(
                HciNames.adTypeName(type),
                adValue(packet, type, valueOffset, valueLength),
            )
        }
        return DissectionNode("Advertising data", "$length bytes", children)
    }

    private fun adValue(packet: ByteArray, type: Int, offset: Int, length: Int): String = when {
        length <= 0 -> ""
        type == HciNames.AD_SHORTENED_NAME || type == HciNames.AD_COMPLETE_NAME ->
            String(packet, offset, length, Charsets.UTF_8)

        type == HciNames.AD_COMPLETE_16 || type == HciNames.AD_INCOMPLETE_16 -> {
            val out = StringBuilder(length * 5)
            var cursor = offset
            while (cursor + 2 <= offset + length) {
                if (out.isNotEmpty()) out.append(", ")
                out.append("0x%04X".format(u16le(packet, cursor)))
                cursor += 2
            }
            out.toString()
        }

        type == HciNames.AD_COMPLETE_128 || type == HciNames.AD_INCOMPLETE_128 ->
            if (length >= 16) uuid128(packet, offset) else hex(packet, offset, length)

        type == HciNames.AD_MANUFACTURER_SPECIFIC && length >= 2 ->
            "company 0x%04X, %s".format(u16le(packet, offset), bytesValue(packet, offset + 2, length - 2))

        type == HciNames.AD_FLAGS -> "0x%02X".format(u8(packet, offset))
        type == HciNames.AD_TX_POWER -> "${packet[offset].toInt()} dBm"
        type == HciNames.AD_APPEARANCE && length >= 2 -> "0x%04X".format(u16le(packet, offset))
        else -> bytesValue(packet, offset, length)
    }

    private fun bytesValue(bytes: ByteArray, offset: Int, length: Int): String = when {
        length <= 0 -> "(empty)"
        length <= MAX_HEX_BYTES -> "${hex(bytes, offset, length)} ($length bytes)"
        else -> "${hex(bytes, offset, MAX_HEX_BYTES)}… ($length bytes)"
    }

    /** ATT signed writes append a 12-byte authentication signature (Vol 3 Part F, Section 3.3.1). */
    private const val SIGNATURE_BYTES = 12
}
