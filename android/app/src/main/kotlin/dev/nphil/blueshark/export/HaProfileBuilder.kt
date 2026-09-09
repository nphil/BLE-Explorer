package dev.nphil.blueshark.export

import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.CommandSpec
import dev.nphil.blueshark.model.EvidenceStage
import dev.nphil.blueshark.model.HaCommand
import dev.nphil.blueshark.model.HaDevice
import dev.nphil.blueshark.model.HaInstallProfile
import dev.nphil.blueshark.model.WriteType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Why a catalogued command cannot be shipped to Home Assistant. */
enum class ExclusionReason(val label: String) {
    NOT_DEVICE_TESTED("Not device-tested"),
    SYNTHETIC("Synthetic, never observed on the device"),
    SIGNED_WRITE("Signed writes are not installable"),
    UNUSABLE_NAME("Name contains no character usable in an id"),
    INVALID_SERVICE_UUID("Service UUID is not a UUID"),
    INVALID_CHARACTERISTIC_UUID("Characteristic UUID is not a UUID"),
    EMPTY_PAYLOAD("Payload is empty"),
    MALFORMED_PAYLOAD("Payload is not whole hexadecimal bytes"),
    PAYLOAD_TOO_LARGE("Payload is larger than ${HaProfileBuilder.MAX_PAYLOAD_BYTES} bytes"),
}

data class ExclusionNote(val reason: ExclusionReason, val detail: String)

/** One catalogued command that did not make it into the profile, with every reason it failed. */
data class ExcludedCommand(
    val commandId: String,
    val name: String,
    val problems: List<ExclusionNote>,
)

/** A session-level problem: no command can be exported until it is fixed. */
sealed interface ProfileBlocker {
    val message: String

    data object MissingDeviceName : ProfileBlocker {
        override val message = "Give the device a name on the Device tab before exporting"
    }

    data class InvalidDeviceAddress(val raw: String) : ProfileBlocker {
        override val message =
            if (raw.isEmpty()) "The session has no device address"
            else "\"$raw\" is not a Bluetooth address (AA:BB:CC:DD:EE:FF)"
    }

    data object NoEligibleCommands : ProfileBlocker {
        override val message = "No command is device-tested yet"
    }

    data class TooManyCommands(val count: Int) : ProfileBlocker {
        override val message =
            "$count device-tested commands, the integration accepts at most ${HaProfileBuilder.MAX_COMMANDS}"
    }

    data class ProfileTooLarge(val bytes: Int) : ProfileBlocker {
        override val message =
            "Profile is $bytes bytes, the integration accepts at most ${HaProfileBuilder.MAX_PROFILE_BYTES}"
    }
}

/**
 * Everything the export tab needs to explain what will and will not be installed.
 * [profile] is non-null as soon as the device fields are usable and one command survived,
 * even when a blocker still forbids the export, so the UI can show the real size.
 */
data class EligibilityReport(
    val deviceName: String?,
    val deviceAddress: String?,
    val included: List<HaCommand>,
    val excluded: List<ExcludedCommand>,
    val blockers: List<ProfileBlocker>,
    val encodedSizeBytes: Int,
    val profile: HaInstallProfile?,
) {
    val exportable: Boolean get() = blockers.isEmpty() && profile != null

    /** Single-line reason the export is not possible, or a single-line summary when it is. */
    val headline: String
        get() = blockers.firstOrNull()?.message
            ?: "${included.size} command(s) ready, $encodedSizeBytes bytes"
}

/** Thrown through [Result.failure] when a session cannot produce an installable profile. */
class ProfileNotExportableException(val report: EligibilityReport) :
    IllegalStateException(report.headline)

/**
 * Builds the Home Assistant install profile.
 *
 * The field set, the limits and the normalisation here mirror
 * `custom_components/blueshark/profile.py` exactly: the integration rejects unknown keys,
 * so anything added on this side has to be added there first.
 */
object HaProfileBuilder {
    const val MAX_COMMANDS = 128
    const val MAX_PAYLOAD_BYTES = 512
    const val MAX_PROFILE_BYTES = 64 * 1024
    const val MAX_ID_LENGTH = 64

    /** The integration only installs commands that were verified against the physical device. */
    const val PROFILE_STAGE = "tested"

    private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    private val SHORT_16_BIT = Regex("[0-9A-Fa-f]{4}")
    private val SHORT_32_BIT = Regex("[0-9A-Fa-f]{8}")
    private val UNDASHED_128_BIT = Regex("[0-9A-Fa-f]{32}")
    private val CANONICAL_UUID =
        Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
    private val HEX_BYTES = Regex("(?:[0-9A-Fa-f]{2})+")
    private val BLUETOOTH_ADDRESS = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
    private val ID_ALLOWED = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,${MAX_ID_LENGTH - 1}}")
    private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE

    /** Encoding used for every shipped profile; `encodeDefaults` keeps `stage`/`synthetic` present. */
    val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    fun build(session: CaptureSession, zone: ZoneId = ZoneId.systemDefault()): Result<HaInstallProfile> {
        val report = evaluate(session, zone)
        val profile = report.profile
        return if (report.blockers.isEmpty() && profile != null) {
            Result.success(profile)
        } else {
            Result.failure(ProfileNotExportableException(report))
        }
    }

    fun encode(profile: HaInstallProfile): String = json.encodeToString(profile)

    fun evaluate(session: CaptureSession, zone: ZoneId = ZoneId.systemDefault()): EligibilityReport {
        val deviceName = firstNonBlank(session.device.alias, session.device.name)
        val rawAddress = session.device.address.trim().uppercase()
        val address = rawAddress.takeIf { BLUETOOTH_ADDRESS.matches(it) }

        val included = ArrayList<HaCommand>(session.commands.size)
        val excluded = ArrayList<ExcludedCommand>()
        val usedIds = HashSet<String>(session.commands.size * 2)
        val exportDate = ISO_DATE.format(Instant.ofEpochMilli(session.updatedAtEpochMs).atZone(zone))

        for (command in session.commands) {
            val problems = ArrayList<ExclusionNote>(2)
            if (command.stage != EvidenceStage.DEVICE_TESTED) {
                problems += ExclusionNote(
                    ExclusionReason.NOT_DEVICE_TESTED,
                    "stage is ${command.stage.name}",
                )
            }
            if (command.synthetic) {
                problems += ExclusionNote(ExclusionReason.SYNTHETIC, "marked synthetic")
            }
            if (command.writeType == WriteType.SIGNED) {
                problems += ExclusionNote(ExclusionReason.SIGNED_WRITE, "write type is SIGNED")
            }
            val service = canonicalUuid(command.serviceUuid)
            if (service == null) {
                problems += ExclusionNote(
                    ExclusionReason.INVALID_SERVICE_UUID,
                    "service \"${command.serviceUuid}\"",
                )
            }
            val characteristic = canonicalUuid(command.characteristicUuid)
            if (characteristic == null) {
                problems += ExclusionNote(
                    ExclusionReason.INVALID_CHARACTERISTIC_UUID,
                    "characteristic \"${command.characteristicUuid}\"",
                )
            }
            val payload = normalizeHex(command.payloadHex)
            when {
                command.payloadHex.isBlank() ->
                    problems += ExclusionNote(ExclusionReason.EMPTY_PAYLOAD, "no bytes to write")

                payload == null -> problems += ExclusionNote(
                    ExclusionReason.MALFORMED_PAYLOAD,
                    "payload \"${command.payloadHex}\"",
                )

                payload.isEmpty() ->
                    problems += ExclusionNote(ExclusionReason.EMPTY_PAYLOAD, "no bytes to write")

                payload.length / 2 > MAX_PAYLOAD_BYTES -> problems += ExclusionNote(
                    ExclusionReason.PAYLOAD_TOO_LARGE,
                    "${payload.length / 2} bytes",
                )
            }
            val slug = slugify(command.name)
            if (slug == null) {
                problems += ExclusionNote(
                    ExclusionReason.UNUSABLE_NAME,
                    "name \"${command.name}\"",
                )
            }

            if (problems.isNotEmpty()) {
                excluded += ExcludedCommand(command.id, command.name.trim(), problems)
                continue
            }

            included += HaCommand(
                id = uniqueId(slug!!, usedIds),
                name = command.name.trim(),
                service = service!!,
                characteristic = characteristic!!,
                value = payload!!.lowercase(),
                response = command.writeType == WriteType.WITH_RESPONSE,
                stage = PROFILE_STAGE,
                notes = notesFor(command, exportDate),
                synthetic = false,
            )
        }

        val blockers = ArrayList<ProfileBlocker>(2)
        if (deviceName == null) blockers += ProfileBlocker.MissingDeviceName
        if (address == null) blockers += ProfileBlocker.InvalidDeviceAddress(rawAddress)
        if (included.isEmpty()) blockers += ProfileBlocker.NoEligibleCommands
        if (included.size > MAX_COMMANDS) blockers += ProfileBlocker.TooManyCommands(included.size)

        val profile = if (deviceName != null && address != null && included.isNotEmpty()) {
            HaInstallProfile(
                device = HaDevice(name = deviceName, address = address),
                synthetic = false,
                commands = included,
            )
        } else {
            null
        }
        val encodedSize = profile?.let { encode(it).toByteArray(Charsets.UTF_8).size } ?: 0
        if (encodedSize > MAX_PROFILE_BYTES) blockers += ProfileBlocker.ProfileTooLarge(encodedSize)

        return EligibilityReport(
            deviceName = deviceName,
            deviceAddress = address ?: rawAddress.takeIf { it.isNotEmpty() },
            included = included,
            excluded = excluded,
            blockers = blockers,
            encodedSizeBytes = encodedSize,
            profile = profile,
        )
    }

    /**
     * Canonical lowercase 128-bit form, or null when [raw] is not a UUID.
     * Accepts the 16-bit (`FFF1`) and 32-bit shorthands used by sniffer output and the
     * undashed 32-hex form, all expanded against the Bluetooth base UUID.
     */
    fun canonicalUuid(raw: String?): String? {
        val text = raw?.trim()?.removeSurrounding("{", "}")?.removePrefix("urn:uuid:") ?: return null
        val dashed = when {
            text.isEmpty() -> return null
            SHORT_16_BIT.matches(text) -> "0000${text.lowercase()}$BASE_UUID_SUFFIX"
            SHORT_32_BIT.matches(text) -> "${text.lowercase()}$BASE_UUID_SUFFIX"
            UNDASHED_128_BIT.matches(text) -> buildString(36) {
                append(text, 0, 8).append('-')
                append(text, 8, 12).append('-')
                append(text, 12, 16).append('-')
                append(text, 16, 20).append('-')
                append(text, 20, 32)
            }.lowercase()

            CANONICAL_UUID.matches(text) -> text.lowercase()
            else -> return null
        }
        return runCatching { UUID.fromString(dashed).toString() }.getOrNull()
    }

    /** Uppercase whole-byte hex without separators, or null when [raw] is not hexadecimal bytes. */
    fun normalizeHex(raw: String?): String? {
        val text = raw ?: return null
        val compact = StringBuilder(text.length)
        for (character in text) {
            when (character) {
                ' ', ':', '-', '_', '\n', '\t', '\r' -> Unit
                else -> compact.append(character)
            }
        }
        if (compact.isEmpty()) return ""
        val candidate = compact.toString()
        return if (HEX_BYTES.matches(candidate)) candidate.uppercase() else null
    }

    /**
     * Home Assistant object id derived from a human name, or null when nothing usable remains.
     * `.` and `-` survive because the integration allows them; every other run of unusable
     * characters collapses into a single `_`, and the result always starts and ends alphanumeric.
     */
    fun slugify(name: String): String? {
        val slug = StringBuilder(name.length.coerceAtMost(MAX_ID_LENGTH))
        var pendingSeparator = false
        for (character in name.trim().lowercase()) {
            val usable = when {
                character.isLetterOrDigit() && character.code < 128 -> character
                character == '.' || character == '-' -> character
                else -> null
            }
            if (usable == null) {
                pendingSeparator = slug.isNotEmpty()
                continue
            }
            if (slug.length >= MAX_ID_LENGTH) break
            if (pendingSeparator) {
                slug.append('_')
                pendingSeparator = false
                if (slug.length >= MAX_ID_LENGTH) break
            }
            slug.append(usable)
        }
        val candidate = trimToAlphanumeric(slug)
        return candidate.takeIf { it.isNotEmpty() && ID_ALLOWED.matches(it) }
    }

    private fun trimToAlphanumeric(text: CharSequence): String {
        var start = 0
        while (start < text.length && !text[start].isLetterOrDigit()) start++
        var end = text.length
        while (end > start && !text[end - 1].isLetterOrDigit()) end--
        return text.subSequence(start, end).toString()
    }

    private fun uniqueId(slug: String, used: MutableSet<String>): String {
        if (used.add(slug)) return slug
        var suffix = 2
        while (true) {
            val tail = "_$suffix"
            val base = trimToAlphanumeric(slug.take(MAX_ID_LENGTH - tail.length))
            val candidate = base + tail
            if (used.add(candidate)) return candidate
            suffix++
        }
    }

    private fun notesFor(command: CommandSpec, exportDate: String): String {
        val authored = command.notes.trim()
        if (authored.isNotEmpty()) return authored
        return "Device-tested in BlueShark on $exportDate; " +
            "observed ${command.observedCount} times, replayed ${command.successfulReplayCount} times"
    }

    private fun firstNonBlank(vararg candidates: String?): String? =
        candidates.firstNotNullOfOrNull { candidate -> candidate?.trim()?.takeIf { it.isNotEmpty() } }
}
