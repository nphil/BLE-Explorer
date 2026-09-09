package dev.nphil.blestudio.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** Full-fidelity, local-first evidence bundle. Raw bytes are canonical uppercase hex. */
@Serializable
data class EvidenceBundle(
    val schemaVersion: Int = 1,
    val appVersion: String,
    val session: CaptureSession,
)

@Serializable
data class CaptureSession(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = createdAtEpochMs,
    val device: DeviceIdentity = DeviceIdentity(),
    val advertisements: List<AdvertisementSample> = emptyList(),
    val gatt: GattDatabase? = null,
    val connection: ConnectionFacts = ConnectionFacts(),
    val events: List<BleEvent> = emptyList(),
    val markers: List<CaptureMarker> = emptyList(),
    val commands: List<CommandSpec> = emptyList(),
    val notifications: List<NotificationSpec> = emptyList(),
    val protocol: ProtocolModel = ProtocolModel(),
    val environment: CaptureEnvironment? = null,
    val ciphers: List<CipherScheme> = emptyList(),
    val notes: String = "",
)

@Serializable
data class DeviceIdentity(
    val address: String = "",
    val addressType: String? = null,
    val name: String? = null,
    val alias: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    val appearance: Int? = null,
    val advertisedServiceUuids: List<String> = emptyList(),
    val manufacturerData: Map<Int, String> = emptyMap(),
    val serviceData: Map<String, String> = emptyMap(),
    val bonded: Boolean = false,
)

@Serializable
data class AdvertisementSample(
    val timestampEpochMs: Long,
    val rssi: Int,
    val txPower: Int? = null,
    val connectable: Boolean? = null,
    val primaryPhy: Int? = null,
    val secondaryPhy: Int? = null,
    val periodicInterval: Int? = null,
    val bytesHex: String,
)

@Serializable
data class GattDatabase(
    val capturedAtEpochMs: Long = System.currentTimeMillis(),
    val services: List<GattServiceRecord>,
)

@Serializable
data class GattServiceRecord(
    val uuid: String,
    val instanceId: Int,
    val type: String,
    val characteristics: List<GattCharacteristicRecord>,
)

@Serializable
data class GattCharacteristicRecord(
    val uuid: String,
    val instanceId: Int,
    val properties: List<String>,
    val permissions: List<String>,
    val descriptors: List<GattDescriptorRecord> = emptyList(),
)

@Serializable
data class GattDescriptorRecord(
    val uuid: String,
    val instanceId: Int,
    val permissions: List<String>,
)

@Serializable
data class ConnectionFacts(
    val negotiatedMtu: Int? = null,
    val txPhy: Int? = null,
    val rxPhy: Int? = null,
    val pairingRequired: Boolean? = null,
    val idleDisconnectMs: Long? = null,
    val minInterCommandMs: Long? = null,
    val reconnectSamplesMs: List<Long> = emptyList(),
    val preferredWriteType: WriteType? = null,
    val writeWithoutResponseVerified: Boolean = false,
    val maxObservedResponseMs: Long? = null,
    val connectAttempts: List<ConnectAttempt> = emptyList(),
    val notes: String = "",
)

@Serializable
data class ConnectAttempt(
    val startedEpochMs: Long,
    val durationMs: Long,
    val success: Boolean,
    val source: EventSource,
    val error: String? = null,
)

/** Where and how the evidence was captured; needed to reproduce and to judge OEM-specific quirks. */
@Serializable
data class CaptureEnvironment(
    val androidSdk: Int,
    val androidRelease: String,
    val buildFingerprint: String,
    val deviceModel: String,
    val vendorAppPackage: String? = null,
    val vendorAppVersion: String? = null,
    val hciSnoopMode: String? = null,
)

/** Framing hypotheses kept separate from verified commands; every field is optional evidence, never inferred fact. */
@Serializable
data class ProtocolModel(
    val framingNotes: String = "",
    val headerHex: String? = null,
    val lengthByteOffset: Int? = null,
    val lengthIncludesHeader: Boolean? = null,
    val littleEndian: Boolean? = null,
    val sequenceByteOffset: Int? = null,
    val checksumHypothesis: String? = null,
    val checksumByteOffset: Int? = null,
    val handshakeCommandIds: List<String> = emptyList(),
    val requiresPairing: Boolean? = null,
    val encryptionNotes: String = "",
)

@Serializable
enum class EventDirection { PHONE_TO_DEVICE, DEVICE_TO_PHONE, LOCAL_TO_DEVICE, DEVICE_TO_LOCAL, SYSTEM }

@Serializable
enum class EventSource { LIVE_GATT, HCI_SNOOP, MITM_RELAY, IMPORT, SYSTEM }

@Serializable
enum class AttOperation { READ_REQUEST, READ_RESPONSE, WRITE_REQUEST, WRITE_RESPONSE, WRITE_COMMAND, NOTIFICATION, INDICATION, CONFIRMATION, DISCOVERY, ERROR, OTHER }

@Serializable
data class BleEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestampEpochMicros: Long,
    val direction: EventDirection,
    val source: EventSource,
    val operation: AttOperation,
    val connectionHandle: Int? = null,
    val serviceUuid: String? = null,
    val characteristicUuid: String? = null,
    val attributeHandle: Int? = null,
    val payloadHex: String,
    val status: Int? = null,
    val markerId: String? = null,
    val note: String = "",
)

@Serializable
data class CaptureMarker(
    val id: String = UUID.randomUUID().toString(),
    val timestampEpochMicros: Long,
    val label: String,
    val colorArgb: Long? = null,
)

@Serializable
enum class WriteType { WITH_RESPONSE, WITHOUT_RESPONSE, SIGNED }

@Serializable
enum class EvidenceStage { OBSERVED, HYPOTHESIS, DEVICE_TESTED }

@Serializable
data class CommandSpec(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val serviceUuid: String,
    val characteristicUuid: String,
    val payloadHex: String,
    val writeType: WriteType,
    val stage: EvidenceStage = EvidenceStage.OBSERVED,
    val observedCount: Int = 1,
    val successfulReplayCount: Int = 0,
    val minSpacingMs: Long? = null,
    val response: ResponseExpectation? = null,
    val parameters: List<ParameterHypothesis> = emptyList(),
    val proposedHomeAssistantEntity: String? = null,
    val notes: String = "",
    val synthetic: Boolean = false,
)

@Serializable
data class ResponseExpectation(
    val characteristicUuid: String,
    val payloadPrefixHex: String? = null,
    val byteMaskHex: String? = null,
    val timeoutMs: Long = 5_000,
    val observedLatenciesMs: List<Long> = emptyList(),
)

@Serializable
data class ParameterHypothesis(
    val name: String,
    val byteOffset: Int,
    val byteLength: Int = 1,
    val encoding: String,
    val observedValues: Map<String, String> = emptyMap(),
    val notes: String = "",
)

@Serializable
data class NotificationSpec(
    val characteristicUuid: String,
    val indication: Boolean = false,
    val samplesHex: List<String> = emptyList(),
    val decodingHypotheses: List<ParameterHypothesis> = emptyList(),
    val proposedHomeAssistantEntity: String? = null,
    val notes: String = "",
)

@Serializable
data class HaInstallProfile(
    val schema_version: Int = 2,
    val device: HaDevice,
    val synthetic: Boolean = false,
    val commands: List<HaCommand>,
)

@Serializable
data class HaDevice(val name: String, val address: String)

@Serializable
data class HaCommand(
    val id: String,
    val name: String,
    val service: String,
    val characteristic: String,
    val value: String,
    val response: Boolean,
    val stage: String = "tested",
    val notes: String,
    val synthetic: Boolean = false,
)

// ---------------------------------------------------------------------------
// Application-layer decryption
// ---------------------------------------------------------------------------

/** Symmetric primitive a scheme applies to the ciphertext range of a frame. */
@Serializable
enum class CipherPrimitive { AES_ECB, AES_CBC, AES_CTR, AES_GCM, AES_CCM, CHACHA20_POLY1305, XOR }

/** How the operator's entered secret becomes the actual cipher key. */
@Serializable
enum class KeyDerivation { RAW, SHA256, MD5, HMAC_SHA256_WITH_SALT, AES_ECB_OF_CONSTANT }

/**
 * Which way round a scheme feeds bytes through the block cipher.
 *
 * [NATURAL] is what every standard says. [REVERSED_BLOCKS] is the convention the Telink-derived
 * stacks use - the key, every input block and every output block are byte-reversed - and it is
 * here because a whole family of cheap mesh bulbs is otherwise undecryptable, not because any
 * specification endorses it.
 */
@Serializable
enum class CipherByteOrder { NATURAL, REVERSED_BLOCKS }

/**
 * Where a run of bytes comes from when a scheme is applied to one frame.
 *
 * A nonce, an IV or an AAD is almost never a constant: real schemes stitch it together from the
 * device address, a slice of the frame itself and a counter. Modelling that as a composable
 * source is what makes the engine ecosystem-agnostic - a new vendor scheme is a new
 * [Composite], not new code.
 */
@Serializable
sealed interface ByteSource {
    /** Literal bytes the operator typed, e.g. MiBeacon's `0x11` AAD. */
    @Serializable
    @SerialName("constant")
    data class Constant(val hex: String) : ByteSource

    /**
     * A slice of the frame being decrypted.
     *
     * A negative [offset] counts back from the end and [length] of -1 means "to the end", because
     * real frames put the counter and the MIC at the end and the operator should not have to
     * recompute offsets per frame length.
     */
    @Serializable
    @SerialName("frame")
    data class FrameBytes(val offset: Int, val length: Int) : ByteSource

    /** Concatenation, in order. */
    @Serializable
    @SerialName("composite")
    data class Composite(val parts: List<ByteSource>) : ByteSource

    /** The device address, least-significant byte first - the order MiBeacon nonces use. */
    @Serializable
    @SerialName("macReversed")
    data object MacAddressReversed : ByteSource

    /** The device address in printed order. */
    @Serializable
    @SerialName("mac")
    data object MacAddress : ByteSource

    /** A run of [source], so a scheme can take four bytes of a six-byte address. */
    @Serializable
    @SerialName("slice")
    data class Slice(val source: ByteSource, val offset: Int = 0, val length: Int = -1) : ByteSource

    /**
     * The session counter: how many frames this scheme has matched so far.
     *
     * [littleEndian] of null inherits [CipherScheme.counterLittleEndian], so one switch flips
     * every counter in a scheme while a single source may still disagree.
     */
    @Serializable
    @SerialName("counter")
    data class Counter(val width: Int = 4, val littleEndian: Boolean? = null) : ByteSource
}

/**
 * Which part of the frame is ciphertext.
 *
 * @param offset first ciphertext byte; negative counts back from the end.
 * @param length -1 means "to the end", minus [dropFromEnd].
 * @param dropFromEnd trailing bytes that are not ciphertext - a counter, a MIC, a checksum.
 */
@Serializable
data class ByteRange(val offset: Int = 0, val length: Int = -1, val dropFromEnd: Int = 0)

/** Which frames a scheme claims. An empty matcher claims every frame with a payload. */
@Serializable
data class FrameMatch(
    val characteristicUuid: String? = null,
    val direction: EventDirection? = null,
    val payloadPrefixHex: String? = null,
    val minLength: Int = 0,
)

/**
 * A complete description of one application-layer cipher: enough to decrypt a frame without any
 * ecosystem-specific code.
 *
 * The key lives here because it is per-session evidence like everything else, but it is stripped
 * from a shared bundle unless the operator opts in - see `ExportService.exportEvidenceBundle`.
 */
@Serializable
data class CipherScheme(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val primitive: CipherPrimitive,
    val keyHex: String = "",
    val keyDerivation: KeyDerivation = KeyDerivation.RAW,
    /** Salt for [KeyDerivation.HMAC_SHA256_WITH_SALT]. */
    val keySaltHex: String? = null,
    /** Plaintext block for [KeyDerivation.AES_ECB_OF_CONSTANT]. */
    val keyConstantHex: String? = null,
    val nonce: ByteSource = ByteSource.Constant(""),
    val aad: ByteSource? = null,
    val tagLength: Int? = null,
    /** Where the AEAD tag lives when it is not appended to the ciphertext. */
    val tag: ByteSource? = null,
    val counterLittleEndian: Boolean = true,
    val ciphertextRange: ByteRange = ByteRange(),
    val match: FrameMatch = FrameMatch(),
    /** PKCS#5/7 padding for CBC; block-aligned NoPadding otherwise. */
    val padded: Boolean = false,
    val byteOrder: CipherByteOrder = CipherByteOrder.NATURAL,
    val presetId: String? = null,
    val notes: String = "",
)

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

fun String.hexToBytes(): ByteArray {
    val compact = filterNot(Char::isWhitespace)
    require(compact.length % 2 == 0 && compact.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Expected complete hexadecimal byte pairs" }
    return ByteArray(compact.length / 2) { compact.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
