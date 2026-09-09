package dev.nphil.blestudio.model

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

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

fun String.hexToBytes(): ByteArray {
    val compact = filterNot(Char::isWhitespace)
    require(compact.length % 2 == 0 && compact.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Expected complete hexadecimal byte pairs" }
    return ByteArray(compact.length / 2) { compact.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
