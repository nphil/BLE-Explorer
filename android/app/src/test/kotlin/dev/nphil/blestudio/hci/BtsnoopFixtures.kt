package dev.nphil.blestudio.hci

import java.io.ByteArrayOutputStream

/** Byte-level builders for synthetic btsnoop captures used by the parser tests. */
internal object Fixtures {

    const val EPOCH_DELTA = 0x00dcddb30f2f8000L

    const val PB_START = 0x02
    const val PB_CONTINUATION = 0x01

    fun fileHeader(
        magic: ByteArray = "btsnoop\u0000".toByteArray(Charsets.ISO_8859_1),
        version: Int = 1,
        datalink: Int = 1002,
    ): ByteArray = ByteArrayOutputStream(16).apply {
        write(magic)
        writeBe32(version.toLong())
        writeBe32(datalink.toLong())
    }.toByteArray()

    /** One btsnoop record. [timestampEpochMicros] is Unix time; the btsnoop epoch delta is added here. */
    fun record(
        packet: ByteArray,
        timestampEpochMicros: Long,
        sentByHost: Boolean = true,
        originalLength: Int = packet.size,
    ): ByteArray = ByteArrayOutputStream(24 + packet.size).apply {
        writeBe32(originalLength.toLong())
        writeBe32(packet.size.toLong())
        writeBe32(if (sentByHost) 0L else 1L)
        writeBe32(0L)
        writeBe64(timestampEpochMicros + EPOCH_DELTA)
        write(packet)
    }.toByteArray()

    /** H4 ACL packet carrying [fragment] bytes of an L2CAP PDU. */
    fun acl(handle: Int, boundary: Int, fragment: ByteArray): ByteArray =
        ByteArrayOutputStream(5 + fragment.size).apply {
            write(0x02)
            writeLe16(handle or (boundary shl 12))
            writeLe16(fragment.size)
            write(fragment)
        }.toByteArray()

    /** Complete L2CAP PDU: length, CID, body. */
    fun l2cap(cid: Int, body: ByteArray): ByteArray = ByteArrayOutputStream(4 + body.size).apply {
        writeLe16(body.size)
        writeLe16(cid)
        write(body)
    }.toByteArray()

    /** ACL packet holding a whole ATT PDU in one fragment. */
    fun att(handle: Int, pdu: ByteArray): ByteArray = acl(handle, PB_START, l2cap(0x0004, pdu))

    fun hciEvent(code: Int, parameters: ByteArray): ByteArray = ByteArrayOutputStream(3 + parameters.size).apply {
        write(0x04)
        write(code)
        write(parameters.size)
        write(parameters)
    }.toByteArray()

    fun disconnectionComplete(connectionHandle: Int, reason: Int = 0x13): ByteArray =
        hciEvent(0x05, ByteArrayOutputStream(4).apply {
            write(0x00)
            writeLe16(connectionHandle)
            write(reason)
        }.toByteArray())

    fun hciCommand(opcode: Int, parameters: ByteArray = ByteArray(0)): ByteArray =
        ByteArrayOutputStream(4 + parameters.size).apply {
            write(0x01)
            writeLe16(opcode)
            write(parameters.size)
            write(parameters)
        }.toByteArray()

    fun leMeta(subevent: Int, parameters: ByteArray): ByteArray =
        hciEvent(0x3E, ByteArrayOutputStream(1 + parameters.size).apply {
            write(subevent)
            write(parameters)
        }.toByteArray())

    /** Bluetooth addresses travel least significant byte first. */
    fun addressBytes(address: String): ByteArray {
        val parts = address.split(":")
        require(parts.size == 6) { "expected AA:BB:CC:DD:EE:FF" }
        return ByteArray(6) { index -> parts[5 - index].toInt(16).toByte() }
    }

    /** LE Connection Complete (0x01) or Enhanced LE Connection Complete (0x0A). */
    fun connectionComplete(
        connectionHandle: Int,
        address: String = "AA:BB:CC:DD:EE:FF",
        addressType: Int = 0x01,
        intervalUnits: Int = 39,
        latency: Int = 0,
        timeoutUnits: Int = 500,
        role: Int = 0x00,
        status: Int = 0x00,
        enhanced: Boolean = false,
    ): ByteArray = leMeta(
        if (enhanced) 0x0A else 0x01,
        ByteArrayOutputStream(30).apply {
            write(status)
            writeLe16(connectionHandle)
            write(role)
            write(addressType)
            write(addressBytes(address))
            if (enhanced) {
                write(addressBytes("00:00:00:00:00:00"))
                write(addressBytes("00:00:00:00:00:00"))
            }
            writeLe16(intervalUnits)
            writeLe16(latency)
            writeLe16(timeoutUnits)
            write(0x00)
        }.toByteArray(),
    )

    fun connectionUpdateComplete(
        connectionHandle: Int,
        intervalUnits: Int = 24,
        latency: Int = 0,
        timeoutUnits: Int = 200,
        status: Int = 0x00,
    ): ByteArray = leMeta(0x03, ByteArrayOutputStream(9).apply {
        write(status)
        writeLe16(connectionHandle)
        writeLe16(intervalUnits)
        writeLe16(latency)
        writeLe16(timeoutUnits)
    }.toByteArray())

    fun phyUpdateComplete(connectionHandle: Int, txPhy: Int = 0x02, rxPhy: Int = 0x02, status: Int = 0x00): ByteArray =
        leMeta(0x0C, ByteArrayOutputStream(5).apply {
            write(status)
            writeLe16(connectionHandle)
            write(txPhy)
            write(rxPhy)
        }.toByteArray())

    fun dataLengthChange(connectionHandle: Int, octets: Int = 251): ByteArray =
        leMeta(0x07, ByteArrayOutputStream(10).apply {
            writeLe16(connectionHandle)
            writeLe16(octets)
            writeLe16(2120)
            writeLe16(octets)
            writeLe16(2120)
        }.toByteArray())

    fun encryptionChange(connectionHandle: Int, enabled: Int = 0x01, status: Int = 0x00): ByteArray =
        hciEvent(0x08, ByteArrayOutputStream(4).apply {
            write(status)
            writeLe16(connectionHandle)
            write(enabled)
        }.toByteArray())

    fun commandStatus(opcode: Int, status: Int): ByteArray = hciEvent(0x0F, ByteArrayOutputStream(4).apply {
        write(status)
        write(0x01)
        writeLe16(opcode)
    }.toByteArray())

    fun commandComplete(opcode: Int, status: Int = 0x00): ByteArray =
        hciEvent(0x0E, ByteArrayOutputStream(4).apply {
            write(0x01)
            writeLe16(opcode)
            write(status)
        }.toByteArray())

    fun numberOfCompletedPackets(connectionHandle: Int, packets: Int = 1): ByteArray =
        hciEvent(0x13, ByteArrayOutputStream(5).apply {
            write(0x01)
            writeLe16(connectionHandle)
            writeLe16(packets)
        }.toByteArray())

    /** LE Advertising Report with a single report. */
    fun advertisingReport(
        address: String = "AA:BB:CC:DD:EE:FF",
        addressType: Int = 0x01,
        rssi: Int = -60,
        data: ByteArray = ByteArray(0),
        eventType: Int = 0x00,
    ): ByteArray = leMeta(0x02, ByteArrayOutputStream(11 + data.size).apply {
        write(0x01)
        write(eventType)
        write(addressType)
        write(addressBytes(address))
        write(data.size)
        write(data)
        write(rssi and 0xFF)
    }.toByteArray())

    /** LE Extended Advertising Report with a single report. */
    fun extendedAdvertisingReport(
        address: String = "AA:BB:CC:DD:EE:FF",
        addressType: Int = 0x00,
        rssi: Int = -55,
        data: ByteArray = ByteArray(0),
    ): ByteArray = leMeta(0x0D, ByteArrayOutputStream(26 + data.size).apply {
        write(0x01)
        writeLe16(0x0013)
        write(addressType)
        write(addressBytes(address))
        write(0x01) // primary PHY
        write(0x02) // secondary PHY
        write(0x00) // advertising SID
        write(0x7F) // Tx power: not available
        write(rssi and 0xFF)
        writeLe16(0) // periodic advertising interval
        write(0x00) // direct address type
        write(addressBytes("00:00:00:00:00:00"))
        write(data.size)
        write(data)
    }.toByteArray())

    // ---------------------------------------------------------------- advertising data (CSS Part A, 1)

    fun adName(name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream(2 + bytes.size).apply {
            write(bytes.size + 1)
            write(0x09)
            write(bytes)
        }.toByteArray()
    }

    fun adServiceUuids16(vararg uuids: Int): ByteArray = ByteArrayOutputStream(2 + uuids.size * 2).apply {
        write(uuids.size * 2 + 1)
        write(0x03)
        uuids.forEach { writeLe16(it) }
    }.toByteArray()

    fun adManufacturer(company: Int, payload: ByteArray): ByteArray =
        ByteArrayOutputStream(4 + payload.size).apply {
            write(payload.size + 3)
            write(0xFF)
            writeLe16(company)
            write(payload)
        }.toByteArray()

    // ---------------------------------------------------------------- HCI commands

    /** LE Create Connection (0x200D) with the 25 parameter bytes of Vol 4 Part E, 7.8.12. */
    fun leCreateConnection(address: String = "AA:BB:CC:DD:EE:FF", addressType: Int = 0x01): ByteArray =
        hciCommand(0x200D, ByteArrayOutputStream(25).apply {
            writeLe16(96) // scan interval
            writeLe16(48) // scan window
            write(0x00) // initiator filter policy: use the peer address
            write(addressType)
            write(addressBytes(address))
            write(0x00) // own address type
            writeLe16(24)
            writeLe16(40)
            writeLe16(0)
            writeLe16(500)
            writeLe16(0)
            writeLe16(0)
        }.toByteArray())

    fun disconnectCommand(connectionHandle: Int, reason: Int = 0x13): ByteArray =
        hciCommand(0x0406, ByteArrayOutputStream(3).apply {
            writeLe16(connectionHandle)
            write(reason)
        }.toByteArray())

    // ---------------------------------------------------------------- SMP (CID 0x0006)

    fun smp(handle: Int, body: ByteArray): ByteArray = acl(handle, PB_START, l2cap(0x0006, body))

    fun pairingRequest(
        io: Int = 0x01,
        oob: Int = 0x00,
        authReq: Int = 0x0D,
        maxKeySize: Int = 16,
        initiatorKeys: Int = 0x07,
        responderKeys: Int = 0x07,
        response: Boolean = false,
    ): ByteArray = ByteArrayOutputStream(7).apply {
        write(if (response) 0x02 else 0x01)
        write(io)
        write(oob)
        write(authReq)
        write(maxKeySize)
        write(initiatorKeys)
        write(responderKeys)
    }.toByteArray()

    fun pairingFailed(reason: Int = 0x08): ByteArray = byteArrayOf(0x05, reason.toByte())

    fun securityRequest(authReq: Int = 0x0D): ByteArray = byteArrayOf(0x0B, authReq.toByte())

    /** Encryption Information carries the long term key; the parser must never keep it. */
    fun encryptionInformation(key: ByteArray = ByteArray(16) { 0x5A }): ByteArray =
        ByteArrayOutputStream(17).apply {
            write(0x06)
            write(key)
        }.toByteArray()

    // ---------------------------------------------------------------- L2CAP signalling (CID 0x0005)

    fun signaling(handle: Int, body: ByteArray): ByteArray = acl(handle, PB_START, l2cap(0x0005, body))

    fun connectionParameterUpdateRequest(
        intervalMin: Int = 24,
        intervalMax: Int = 40,
        latency: Int = 0,
        timeoutMultiplier: Int = 500,
        identifier: Int = 0x01,
    ): ByteArray = ByteArrayOutputStream(12).apply {
        write(0x12)
        write(identifier)
        writeLe16(8)
        writeLe16(intervalMin)
        writeLe16(intervalMax)
        writeLe16(latency)
        writeLe16(timeoutMultiplier)
    }.toByteArray()

    fun connectionParameterUpdateResponse(result: Int = 0x0000, identifier: Int = 0x01): ByteArray =
        ByteArrayOutputStream(6).apply {
            write(0x13)
            write(identifier)
            writeLe16(2)
            writeLe16(result)
        }.toByteArray()

    fun leCreditBasedConnectionRequest(
        spsm: Int = 0x001F,
        sourceCid: Int = 0x0040,
        mtu: Int = 517,
        mps: Int = 251,
        credits: Int = 10,
        identifier: Int = 0x02,
    ): ByteArray = ByteArrayOutputStream(14).apply {
        write(0x14)
        write(identifier)
        writeLe16(10)
        writeLe16(spsm)
        writeLe16(sourceCid)
        writeLe16(mtu)
        writeLe16(mps)
        writeLe16(credits)
    }.toByteArray()

    // ---------------------------------------------------------------- more ATT PDUs

    fun exchangeMtuRequest(mtu: Int): ByteArray = ByteArrayOutputStream(3).apply {
        write(0x02)
        writeLe16(mtu)
    }.toByteArray()

    fun exchangeMtuResponse(mtu: Int): ByteArray = ByteArrayOutputStream(3).apply {
        write(0x03)
        writeLe16(mtu)
    }.toByteArray()

    fun readBlobRequest(attributeHandle: Int, offset: Int): ByteArray = ByteArrayOutputStream(5).apply {
        write(0x0C)
        writeLe16(attributeHandle)
        writeLe16(offset)
    }.toByteArray()

    fun readBlobResponse(value: ByteArray): ByteArray = ByteArrayOutputStream(1 + value.size).apply {
        write(0x0D)
        write(value)
    }.toByteArray()

    fun prepareWriteRequest(attributeHandle: Int, offset: Int, value: ByteArray): ByteArray =
        ByteArrayOutputStream(5 + value.size).apply {
            write(0x16)
            writeLe16(attributeHandle)
            writeLe16(offset)
            write(value)
        }.toByteArray()

    fun executeWriteRequest(write: Boolean = true): ByteArray = byteArrayOf(0x18, if (write) 1 else 0)

    fun signedWriteCommand(attributeHandle: Int, value: ByteArray): ByteArray =
        ByteArrayOutputStream(3 + value.size + 12).apply {
            write(0xD2)
            writeLe16(attributeHandle)
            write(value)
            write(ByteArray(12) { 0x11 })
        }.toByteArray()

    fun readMultipleRequest(vararg handles: Int): ByteArray =
        ByteArrayOutputStream(1 + handles.size * 2).apply {
            write(0x0E)
            handles.forEach { writeLe16(it) }
        }.toByteArray()

    // ---------------------------------------------------------------- ATT PDUs

    fun writeCommand(attributeHandle: Int, value: ByteArray): ByteArray =
        ByteArrayOutputStream(3 + value.size).apply {
            write(0x52)
            writeLe16(attributeHandle)
            write(value)
        }.toByteArray()

    fun writeRequest(attributeHandle: Int, value: ByteArray): ByteArray =
        ByteArrayOutputStream(3 + value.size).apply {
            write(0x12)
            writeLe16(attributeHandle)
            write(value)
        }.toByteArray()

    fun notification(attributeHandle: Int, value: ByteArray): ByteArray =
        ByteArrayOutputStream(3 + value.size).apply {
            write(0x1B)
            writeLe16(attributeHandle)
            write(value)
        }.toByteArray()

    fun readByGroupTypeRequest(start: Int, end: Int, type: Int = 0x2800): ByteArray =
        ByteArrayOutputStream(7).apply {
            write(0x10)
            writeLe16(start)
            writeLe16(end)
            writeLe16(type)
        }.toByteArray()

    /** Read By Group Type response listing 16-bit service UUIDs. */
    fun readByGroupTypeResponse(services: List<Triple<Int, Int, Int>>): ByteArray =
        ByteArrayOutputStream(2 + services.size * 6).apply {
            write(0x11)
            write(6)
            services.forEach { (start, end, uuid) ->
                writeLe16(start)
                writeLe16(end)
                writeLe16(uuid)
            }
        }.toByteArray()

    fun readByTypeRequest(start: Int, end: Int, type: Int = 0x2803): ByteArray =
        ByteArrayOutputStream(7).apply {
            write(0x08)
            writeLe16(start)
            writeLe16(end)
            writeLe16(type)
        }.toByteArray()

    /** Read By Type response listing characteristic declarations with 16-bit UUIDs. */
    fun characteristicDeclarations(entries: List<Triple<Int, Int, Int>>): ByteArray =
        ByteArrayOutputStream(2 + entries.size * 7).apply {
            write(0x09)
            write(7)
            entries.forEach { (declarationHandle, valueHandle, uuid) ->
                writeLe16(declarationHandle)
                write(0x1C) // notify | write-without-response | write
                writeLe16(valueHandle)
                writeLe16(uuid)
            }
        }.toByteArray()

    /** Read By Type response whose declaration carries a 128-bit characteristic UUID. */
    fun characteristicDeclaration128(declarationHandle: Int, valueHandle: Int, uuid: ByteArray): ByteArray =
        ByteArrayOutputStream(2 + 21).apply {
            write(0x09)
            write(21)
            writeLe16(declarationHandle)
            write(0x0C)
            writeLe16(valueHandle)
            write(uuid)
        }.toByteArray()

    fun findInformationRequest(start: Int, end: Int): ByteArray = ByteArrayOutputStream(5).apply {
        write(0x04)
        writeLe16(start)
        writeLe16(end)
    }.toByteArray()

    fun findInformationResponse(entries: List<Pair<Int, Int>>): ByteArray =
        ByteArrayOutputStream(2 + entries.size * 4).apply {
            write(0x05)
            write(1)
            entries.forEach { (handle, uuid) ->
                writeLe16(handle)
                writeLe16(uuid)
            }
        }.toByteArray()

    /** 128-bit UUID bytes in ATT order (least significant byte first). */
    fun uuid128(text: String): ByteArray {
        val hex = text.replace("-", "")
        require(hex.length == 32) { "expected a 128-bit UUID" }
        val bytes = ByteArray(16)
        for (i in 0 until 16) {
            bytes[15 - i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    fun ByteArrayOutputStream.writeBe32(value: Long) {
        write(((value ushr 24) and 0xFF).toInt())
        write(((value ushr 16) and 0xFF).toInt())
        write(((value ushr 8) and 0xFF).toInt())
        write((value and 0xFF).toInt())
    }

    fun ByteArrayOutputStream.writeBe64(value: Long) {
        for (shift in 56 downTo 0 step 8) write(((value ushr shift) and 0xFF).toInt())
    }
}

/** Assembles a whole capture: header plus records, in order. */
internal class CaptureBuilder(
    magic: ByteArray = "btsnoop\u0000".toByteArray(Charsets.ISO_8859_1),
    version: Int = 1,
    datalink: Int = 1002,
) {
    private val out = ByteArrayOutputStream(1024)
    private var clock = BASE_MICROS

    init {
        out.write(Fixtures.fileHeader(magic, version, datalink))
    }

    /** Appends a packet, advancing the synthetic clock by [advanceMicros]. */
    fun packet(packet: ByteArray, sentByHost: Boolean = true, advanceMicros: Long = 1_000): CaptureBuilder {
        clock += advanceMicros
        out.write(Fixtures.record(packet, clock, sentByHost))
        return this
    }

    /** Appends a packet whose record claims more original bytes than were captured. */
    fun truncatedPacket(packet: ByteArray, originalLength: Int, sentByHost: Boolean = true): CaptureBuilder {
        clock += 1_000
        out.write(Fixtures.record(packet, clock, sentByHost, originalLength))
        return this
    }

    fun raw(bytes: ByteArray): CaptureBuilder {
        out.write(bytes)
        return this
    }

    fun build(): ByteArray = out.toByteArray()

    companion object {
        /** 2024-05-01T00:00:00Z in Unix microseconds. */
        const val BASE_MICROS = 1_714_521_600_000_000L
    }
}
