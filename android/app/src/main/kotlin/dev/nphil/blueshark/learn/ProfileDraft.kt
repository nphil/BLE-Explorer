package dev.nphil.blueshark.learn

import dev.nphil.blueshark.export.CommandAnalyzer
import dev.nphil.blueshark.export.HaProfileBuilder
import dev.nphil.blueshark.identify.acknowledgedWrite
import dev.nphil.blueshark.identify.unacknowledgedWrite
import dev.nphil.blueshark.model.DeviceIdentity
import dev.nphil.blueshark.model.EvidenceStage
import dev.nphil.blueshark.model.GattDatabase
import dev.nphil.blueshark.model.HaCommand
import dev.nphil.blueshark.model.HaDevice
import dev.nphil.blueshark.model.HaInstallProfile
import dev.nphil.blueshark.model.WriteType

/**
 * What [ProfileDraft.render] could make of a command map.
 *
 * A profile with an empty `commands` array is rejected by the integration
 * (`profile.py` raises on it), so "nothing installable" is a distinct outcome and not a document:
 * the export card must be able to refuse and say why, per command.
 */
sealed interface ProfileDraftResult {
    /**
     * @param json the profile, ready to paste into the integration's config flow.
     * @param skipped commands that were left out of an otherwise installable profile, one line
     *   each, with the reason.
     */
    data class Ready(val json: String, val skipped: List<String> = emptyList()) : ProfileDraftResult

    /** Nothing could be installed; [reasons] says why, one line per command. */
    data class Unresolvable(val reasons: List<String>) : ProfileDraftResult
}

/**
 * Renders a [CommandMap] as the Home Assistant install profile the `blueshark` integration accepts.
 *
 * The schema is not ours to invent: `custom_components/blueshark/profile.py` rejects unknown keys,
 * requires exactly `schema_version`/`device`/`synthetic`/`commands`, and each command to carry
 * exactly `id`/`name`/`service`/`characteristic`/`value`/`response`/`stage`/`notes`/`synthetic`.
 * So this renders through the same [HaInstallProfile] model and the same limits as
 * [HaProfileBuilder], and differs from it in exactly one way: [HaProfileBuilder] ships a finished
 * profile of device-tested commands only, while a draft has to show the operator everything that
 * was learned, including the parts that are not yet safe to run.
 *
 * How "not yet safe to run" is expressed is forced by the integration:
 * - `profile.py` accepts one value for `stage`, `"tested"` (it raises on anything else), so the
 *   field cannot carry the evidence stage of a draft command;
 * - `button.py` creates an entity for a command only when the profile is not synthetic, the
 *   command is not synthetic and its stage is `"tested"`.
 *
 * Per-command `synthetic` is therefore the only in-schema switch left, and this renderer uses it:
 * a [EvidenceStage.DEVICE_TESTED] command is emitted with `synthetic = false` and gets a button,
 * anything weaker is emitted with `synthetic = true` and gets none, with the real stage spelled
 * out in `notes`. The whole draft is marked synthetic when nothing in it was device-tested, so an
 * unverified draft cannot install a single button by accident. Emitting an observed-only command
 * with `synthetic = false` instead would hand the operator a button that fires a payload nobody
 * ever replayed - the one outcome this stage exists to prevent.
 */
object ProfileDraft {

    /** Kept well inside [HaProfileBuilder.MAX_PROFILE_BYTES] even at 128 commands. */
    const val MAX_NOTE_CHARS = 480

    /** Shown when the session has neither an alias, a name, nor an address. */
    const val UNNAMED_DEVICE = "Unknown BLE device"

    private val STRONGEST_FIRST = compareByDescending<MappedCommand> { it.stage.strength() }
        .thenBy { it.name }

    /**
     * The draft, or the reasons there is nothing to install.
     *
     * A command's service comes from [gatt]: `button.py` resolves the service first and then
     * demands the characteristic be *inside* it, so the only safe answer is the service that
     * actually exposes that characteristic - the same walk as
     * [CommandAnalyzer.resolveServiceUuid]. Without a database the draft falls back to the
     * advertisement, and only when it advertises exactly one service; anything less certain is
     * refused rather than guessed, because a wrong service is a button that always errors.
     *
     * `response` follows [MappedCommand.writeType], overridden by the characteristic's properties
     * when they are known: `button.py` requires `write-without-response` for `response = false`
     * and `write` for `response = true`, so a command whose recorded write type the characteristic
     * does not offer is emitted the other way round with a note, and one that takes no write at
     * all is left out.
     */
    fun render(map: CommandMap, device: DeviceIdentity, gatt: GattDatabase? = null): ProfileDraftResult {
        val advertised = device.advertisedServiceUuids.mapNotNull { HaProfileBuilder.canonicalUuid(it) }
        val address = device.address.trim().uppercase()
        val name = sequenceOf(device.alias, device.name)
            .mapNotNull { candidate -> candidate?.trim()?.takeIf { it.isNotEmpty() } }
            .firstOrNull()
            ?: address.ifEmpty { UNNAMED_DEVICE }

        val used = HashSet<String>(map.commands.size * 2)
        val emitted = ArrayList<HaCommand>(map.commands.size)
        val skipped = ArrayList<String>()

        for (command in map.commands.sortedWith(STRONGEST_FIRST)) {
            val label = command.name.trim().ifEmpty { command.id }
            if (emitted.size >= HaProfileBuilder.MAX_COMMANDS) {
                skipped += "$label: over the ${HaProfileBuilder.MAX_COMMANDS}-command limit"
                continue
            }
            val characteristic = HaProfileBuilder.canonicalUuid(command.characteristicUuid)
            if (characteristic == null) {
                skipped += "$label: no characteristic was recorded for it"
                continue
            }
            val payload = HaProfileBuilder.normalizeHex(command.payloadHex)?.takeIf {
                it.isNotEmpty() && it.length / 2 <= HaProfileBuilder.MAX_PAYLOAD_BYTES
            }
            if (payload == null) {
                skipped += "$label: payload \"${command.payloadHex}\" is not 1.." +
                    "${HaProfileBuilder.MAX_PAYLOAD_BYTES} hexadecimal bytes"
                continue
            }
            val slug = HaProfileBuilder.slugify(command.name)
            if (slug == null) {
                skipped += "$label: the name yields no usable Home Assistant id"
                continue
            }
            if (command.writeType == WriteType.SIGNED) {
                skipped += "$label: signed writes cannot be installed"
                continue
            }
            val resolved = resolve(characteristic, gatt, advertised)
            if (resolved == null) {
                skipped += "$label: " + if (gatt != null) {
                    "no service in the GATT database exposes characteristic $characteristic"
                } else {
                    "no GATT database, and the advertisement names ${advertised.size} services - " +
                        "which one holds $characteristic is unknown"
                }
                continue
            }
            val write = writeMode(command.writeType, resolved.properties)
            if (write == null) {
                skipped += "$label: characteristic $characteristic accepts no write " +
                    "(properties ${resolved.properties.orEmpty()})"
                continue
            }
            emitted += HaCommand(
                id = uniqueId(slug, used),
                name = command.name.trim(),
                service = resolved.service,
                characteristic = characteristic,
                value = payload.lowercase(),
                response = write.response,
                stage = HaProfileBuilder.PROFILE_STAGE,
                notes = notesFor(command, write.note),
                synthetic = command.stage != EvidenceStage.DEVICE_TESTED,
            )
        }

        if (emitted.isEmpty()) {
            val reasons = skipped.ifEmpty { listOf("This project has no commands yet.") }
            return ProfileDraftResult.Unresolvable(reasons)
        }

        var kept: List<HaCommand> = emitted
        var text = HaProfileBuilder.encode(profileOf(name, address, kept))
        while (kept.size > 1 && text.toByteArray(Charsets.UTF_8).size > HaProfileBuilder.MAX_PROFILE_BYTES) {
            // Weakest evidence last, so an oversized draft loses its least useful commands first.
            skipped += "${kept.last().name}: dropped to keep the profile under " +
                "${HaProfileBuilder.MAX_PROFILE_BYTES / 1024} KiB"
            kept = kept.dropLast(1)
            text = HaProfileBuilder.encode(profileOf(name, address, kept))
        }
        return ProfileDraftResult.Ready(text, skipped)
    }

    private fun profileOf(name: String, address: String, commands: List<HaCommand>) = HaInstallProfile(
        device = HaDevice(name = name, address = address),
        synthetic = commands.none { !it.synthetic },
        commands = commands,
    )

    /** The service a characteristic really lives in, plus its properties when a database says so. */
    private class Resolved(val service: String, val properties: List<String>?)

    private fun resolve(characteristic: String, gatt: GattDatabase?, advertised: List<String>): Resolved? {
        if (gatt != null) {
            val (service, record) = CommandAnalyzer.resolveCharacteristic(gatt, characteristic) ?: return null
            val canonical = HaProfileBuilder.canonicalUuid(service) ?: return null
            return Resolved(canonical, record.properties)
        }
        val single = advertised.singleOrNull() ?: return null
        return Resolved(single, null)
    }

    /** Which write the integration should perform, and why it is not the one that was recorded. */
    private class WriteMode(val response: Boolean, val note: String?)

    private fun writeMode(writeType: WriteType, properties: List<String>?): WriteMode? {
        val acknowledged = writeType == WriteType.WITH_RESPONSE
        if (properties == null) return WriteMode(acknowledged, null)
        val takesAcknowledged = properties.acknowledgedWrite()
        val takesUnacknowledged = properties.unacknowledgedWrite()
        return when {
            acknowledged && takesAcknowledged -> WriteMode(true, null)
            !acknowledged && takesUnacknowledged -> WriteMode(false, null)
            takesAcknowledged -> WriteMode(
                true,
                "the characteristic does not offer write-without-response, so an acknowledged write is requested",
            )

            takesUnacknowledged -> WriteMode(
                false,
                "the characteristic only offers write-without-response, so an unacknowledged write is requested",
            )

            else -> null
        }
    }

    /**
     * Why this command is in the draft, in the operator's words where possible. Never empty: the
     * integration requires a note on every command.
     */
    private fun notesFor(command: MappedCommand, writeNote: String?): String {
        val head = when (command.stage) {
            EvidenceStage.DEVICE_TESTED -> "Device-tested in BlueShark"
            EvidenceStage.HYPOTHESIS ->
                "Hypothesis only, never confirmed by the device - no entity is created for it"

            EvidenceStage.OBSERVED ->
                "Observed in the vendor app's traffic, never replayed - no entity is created for it"
        }
        val body = command.evidence.joinToString("; ").ifBlank { "source: ${command.source}" }
        val write = writeNote?.let { "$it. " }.orEmpty()
        return "$head. $write$body".take(MAX_NOTE_CHARS).trim()
    }

    private fun uniqueId(slug: String, used: MutableSet<String>): String {
        if (used.add(slug)) return slug
        var suffix = 2
        while (true) {
            val tail = "_$suffix"
            val candidate = slug.take(HaProfileBuilder.MAX_ID_LENGTH - tail.length) + tail
            if (used.add(candidate)) return candidate
            suffix++
        }
    }
}
