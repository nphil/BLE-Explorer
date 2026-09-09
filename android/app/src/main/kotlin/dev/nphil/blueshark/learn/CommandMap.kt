package dev.nphil.blueshark.learn

import dev.nphil.blueshark.export.HaProfileBuilder
import dev.nphil.blueshark.model.AttOperation
import dev.nphil.blueshark.model.EvidenceStage
import dev.nphil.blueshark.model.WriteType
import dev.nphil.blueshark.probe.ProbeOutcome
import dev.nphil.blueshark.probe.ProbeVerdict
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One thing this device can be told to do, and why we believe it.
 *
 * @param payloadHex the bytes to write, framing included: what a replay must send.
 * @param decodedHex the payload inside that framing, or the plaintext of an encrypted frame - null
 *   until something has actually decoded it. It is never guessed here.
 * @param source `"probe"` when BlueShark wrote it itself, `"learned"` when the vendor app was seen
 *   writing it, `"manual"` when the operator entered it.
 * @param writeType how the frame was written, or is to be written. Learned commands take it from
 *   the ATT operation the vendor app used, probed ones from the sweep's target; the default is the
 *   unacknowledged write, which is what BlueShark itself prefers where a characteristic offers it.
 *   [ProfileDraft] overrides it when the GATT database says the characteristic cannot take it.
 * @param evidence human-readable lines that quote the bytes, the tap or the status byte behind
 *   this entry. Merging two records for the same payload keeps the union: evidence never shrinks.
 */
data class MappedCommand(
    val id: String,
    val name: String,
    val characteristicUuid: String?,
    val payloadHex: String,
    val decodedHex: String?,
    val source: String,
    val stage: EvidenceStage,
    val evidence: List<String>,
    val writeType: WriteType = WriteType.WITHOUT_RESPONSE,
)

/** Everything known about how to drive one device, from every source that contributed. */
data class CommandMap(
    val deviceAddress: String,
    val familyId: String?,
    val codecId: String?,
    val commands: List<MappedCommand>,
)

/**
 * One probed frame, reduced to what a command map needs.
 *
 * This exists so the mapping logic does not depend on the prober's own types: the sweep engine
 * owns `probe/`, this package owns what a sweep *means*. [CommandMapBuilder.fromProbeOutcomes] is
 * the adapter between the two and the only place that knows [ProbeVerdict].
 */
data class ProbeEvidence(
    val opcode: Int,
    val label: String,
    val sentHex: String,
    val responseHex: String?,
    val accepted: Boolean,
    val effect: String,
)

/**
 * How much a stage is worth when two records describe the same bytes.
 *
 * Written as a `when` rather than [Enum.ordinal] so that adding a stage is a compile error here
 * instead of a silent reordering of the whole merge.
 */
internal fun EvidenceStage.strength(): Int = when (this) {
    EvidenceStage.OBSERVED -> 0
    EvidenceStage.HYPOTHESIS -> 1
    EvidenceStage.DEVICE_TESTED -> 2
}

/**
 * Turns evidence into a command map: names, deduplicates and ranks.
 *
 * The only judgement in here is which record wins when two of them describe the same bytes on the
 * same characteristic - and that is decided by evidence stage, never by which source ran last:
 * a frame BlueShark wrote and the device accepted outranks the same frame merely seen going by.
 */
object CommandMapBuilder {

    const val SOURCE_LEARNED = "learned"
    const val SOURCE_PROBE = "probe"
    const val SOURCE_MANUAL = "manual"

    /**
     * Names commands after the control that produced them: `"<control label> [<state>]"`, so a
     * switch becomes "Power switch [on]" and a slider "Brightness [62%]". A tap that produced
     * several writes yields one command per write, numbered, because a [MappedCommand] is one
     * frame and the order of the frames is itself evidence.
     *
     * The unattributed bucket ([Attribution.markerId] empty) is skipped: a payload with no control
     * behind it cannot be named or offered as an entity. It stays in the [Attribution] list, where
     * the Learn card can still show it as "writes we could not explain".
     */
    fun fromAttributions(
        deviceAddress: String,
        attributions: List<Attribution>,
        familyId: String? = null,
        codecId: String? = null,
    ): CommandMap {
        val commands = ArrayList<MappedCommand>(attributions.size)
        for (attribution in attributions) {
            if (attribution.markerId.isEmpty()) continue
            val control = attribution.control
            val parsed = MarkerLabel.parse(control.label)
            val name = commandName(parsed, control)
            val total = attribution.payloads.size
            val target = attribution.characteristicUuid
            for ((position, payload) in attribution.payloads.withIndex()) {
                val operation = attribution.operations.getOrNull(position)
                val lines = ArrayList<String>(4)
                lines += "learned from tap \"${control.label}\" " +
                    "(confidence ${twoDecimals(attribution.confidence)})"
                controlLine(control)?.let { lines += it }
                lines += "write ${position + 1} of $total to " +
                    "${target ?: "an unrecorded characteristic"}: $payload" +
                    (operation?.let { " (${it.name})" }.orEmpty())
                if (attribution.note.isNotEmpty()) lines += "correlator note: ${attribution.note}"
                commands += MappedCommand(
                    id = "",
                    name = if (total > 1) "$name (write ${position + 1}/$total)" else name,
                    characteristicUuid = target,
                    payloadHex = HaProfileBuilder.normalizeHex(payload) ?: payload,
                    decodedHex = null,
                    source = SOURCE_LEARNED,
                    stage = EvidenceStage.OBSERVED,
                    evidence = lines,
                    writeType = writeTypeOf(operation),
                )
            }
        }
        return CommandMap(deviceAddress.canonicalAddress(), familyId, codecId, finalise(commands))
    }

    /**
     * The prober's own results.
     *
     * Canaries and unknown-id rejections are left out: a canary proves the link rather than a
     * command, and "no such command id" is evidence of *absence*, which has no business being
     * offered as something to run. Both stay in the session's
     * [dev.nphil.blueshark.model.ProbeRecord]s, where they still count as evidence.
     */
    fun fromProbeOutcomes(
        deviceAddress: String,
        outcomes: List<ProbeOutcome>,
        characteristicUuid: String? = null,
        familyId: String? = null,
        codecId: String? = null,
        writeType: WriteType = WriteType.WITHOUT_RESPONSE,
    ): CommandMap = fromProbeEvidence(
        deviceAddress = deviceAddress,
        evidence = outcomes
            .filter { !it.step.canary && it.verdict != ProbeVerdict.REJECTED_UNKNOWN_ID }
            .map { outcome ->
                ProbeEvidence(
                    opcode = outcome.step.opcode,
                    label = outcome.step.label,
                    sentHex = outcome.sentHex,
                    responseHex = outcome.responseHex,
                    accepted = outcome.verdict == ProbeVerdict.ACCEPTED,
                    effect = outcome.observedEffect,
                )
            },
        characteristicUuid = characteristicUuid,
        familyId = familyId,
        codecId = codecId,
        writeType = writeType,
    )

    /**
     * The same mapping without any dependency on `probe/`: an accepted frame is
     * [EvidenceStage.DEVICE_TESTED] because the device itself said yes, anything else is a
     * [EvidenceStage.HYPOTHESIS] that names what happened instead.
     */
    fun fromProbeEvidence(
        deviceAddress: String,
        evidence: List<ProbeEvidence>,
        characteristicUuid: String? = null,
        familyId: String? = null,
        codecId: String? = null,
        writeType: WriteType = WriteType.WITHOUT_RESPONSE,
    ): CommandMap {
        val commands = evidence.map { probe ->
            val opcode = "0x%02x".format(probe.opcode)
            val lines = ArrayList<String>(3)
            lines += "prober wrote ${probe.sentHex} (opcode $opcode)"
            lines += when {
                probe.accepted && probe.responseHex != null ->
                    "device accepted it, answering ${probe.responseHex}"

                probe.accepted -> "device accepted it without answering"
                probe.responseHex != null ->
                    "device answered ${probe.responseHex} and did not accept it"

                else -> "device did not answer inside the response window"
            }
            if (probe.effect.isNotBlank()) lines += "operator saw: ${probe.effect.trim()}"
            MappedCommand(
                id = "",
                name = probe.label.trim().ifEmpty { "Opcode $opcode" },
                characteristicUuid = characteristicUuid,
                payloadHex = HaProfileBuilder.normalizeHex(probe.sentHex) ?: probe.sentHex,
                decodedHex = null,
                source = SOURCE_PROBE,
                stage = if (probe.accepted) EvidenceStage.DEVICE_TESTED else EvidenceStage.HYPOTHESIS,
                evidence = lines,
                writeType = writeType,
            )
        }
        return CommandMap(deviceAddress.canonicalAddress(), familyId, codecId, finalise(commands))
    }

    /**
     * Folds maps of the same device into one. Records for the same bytes on the same characteristic
     * collapse into the strongest stage, keeping the union of the evidence, so a learned command
     * that the prober later confirmed becomes device-tested without losing the tap that found it.
     */
    fun merge(maps: List<CommandMap>): CommandMap {
        require(maps.isNotEmpty()) { "merge needs at least one map" }
        val addresses = maps.map { it.deviceAddress.canonicalAddress() }.filter { it.isNotEmpty() }.distinct()
        require(addresses.size <= 1) { "cannot merge command maps of different devices: $addresses" }
        return CommandMap(
            deviceAddress = addresses.firstOrNull().orEmpty(),
            familyId = maps.firstNotNullOfOrNull { it.familyId },
            codecId = maps.firstNotNullOfOrNull { it.codecId },
            commands = finalise(maps.flatMap { it.commands }),
        )
    }

    private fun String.canonicalAddress(): String = trim().uppercase()

    /** `"Power switch [on]"`; the state comes from the label, or from the raw slider value. */
    private fun commandName(parsed: ParsedLabel, control: ControlState): String {
        val state = parsed.state ?: control.rangeValue?.let { formatRange(it) }
        return if (state.isNullOrBlank()) parsed.base else "${parsed.base} [$state]"
    }

    /**
     * A value the observer read from a range control. Accessibility reports the raw scale, and
     * only the label knows the percentage, so a fraction is shown as one and anything else as the
     * number the app itself uses.
     */
    private fun formatRange(value: Float): String = when {
        value < 0f -> value.trimmed()
        value <= 1f -> "${(value * 100).roundToInt()}%"
        else -> value.trimmed()
    }

    private fun Float.trimmed(): String =
        if (this == toLong().toFloat()) toLong().toString() else twoDecimals(toDouble())

    /** Evidence text must read the same wherever the app runs, so never the default locale. */
    private fun twoDecimals(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun controlLine(control: ControlState): String? {
        val identity = control.viewId.ifBlank { control.text }
        return when {
            identity.isBlank() && control.screen.isBlank() -> null
            control.screen.isBlank() -> "control $identity"
            identity.isBlank() -> "control on screen ${control.screen}"
            else -> "control $identity on screen ${control.screen}"
        }
    }

    private fun finalise(commands: List<MappedCommand>): List<MappedCommand> = withIds(dedupe(commands))

    /**
     * The identity of a command: the bytes, normalised, and the characteristic they are written
     * to, canonicalised. Two records with the same key are the same command whoever found them.
     *
     * Public because anything that keys its own table by command - a confidence map, a selection,
     * a stored session's merge - has to agree with the way [merge] deduplicates, and re-deriving
     * the normalisation is how two such tables silently drift apart.
     */
    fun commandKey(payloadHex: String, characteristicUuid: String?): String {
        val payload = HaProfileBuilder.normalizeHex(payloadHex).orEmpty()
        val characteristic = HaProfileBuilder.canonicalUuid(characteristicUuid)
            ?: characteristicUuid?.trim()?.lowercase().orEmpty()
        return "$payload@$characteristic"
    }

    /** Same bytes on the same characteristic are one command, whoever found them. */
    private fun dedupe(commands: List<MappedCommand>): List<MappedCommand> {
        val byKey = LinkedHashMap<String, MappedCommand>(commands.size)
        for (command in commands) {
            val key = commandKey(command.payloadHex, command.characteristicUuid)
            val existing = byKey[key]
            byKey[key] = if (existing == null) command else strongest(existing, command)
        }
        return byKey.values.toList()
    }

    private fun strongest(first: MappedCommand, second: MappedCommand): MappedCommand {
        val winner = if (second.stage.strength() > first.stage.strength()) second else first
        val loser = if (winner === first) second else first
        return winner.copy(
            evidence = (winner.evidence + loser.evidence).distinct(),
            writeType = saferWrite(winner.writeType, loser.writeType),
        )
    }

    /**
     * The write type to keep when the same frame was seen written two ways. An acknowledged write
     * wins: every characteristic that takes an unacknowledged write from a phone app is allowed to
     * answer one, and the acknowledgement is what turns a silent failure into an error the
     * operator can see. A signed write only survives when nothing else was ever observed.
     */
    private fun saferWrite(first: WriteType, second: WriteType): WriteType = when {
        first == WriteType.WITH_RESPONSE || second == WriteType.WITH_RESPONSE -> WriteType.WITH_RESPONSE
        first == WriteType.WITHOUT_RESPONSE || second == WriteType.WITHOUT_RESPONSE -> WriteType.WITHOUT_RESPONSE
        else -> WriteType.SIGNED
    }

    /** WRITE_REQUEST is an acknowledged write; WRITE_COMMAND is not; nothing recorded means neither. */
    private fun writeTypeOf(operation: AttOperation?): WriteType = when (operation) {
        AttOperation.WRITE_REQUEST -> WriteType.WITH_RESPONSE
        else -> WriteType.WITHOUT_RESPONSE
    }

    /** Ids are derived from the names, so two runs over the same evidence produce the same map. */
    private fun withIds(commands: List<MappedCommand>): List<MappedCommand> {
        val used = HashSet<String>(commands.size * 2)
        return commands.map { command ->
            val slug = HaProfileBuilder.slugify(command.name) ?: "command"
            var candidate = slug
            var suffix = 2
            while (!used.add(candidate)) {
                candidate = "${slug}_$suffix"
                suffix++
            }
            command.copy(id = candidate)
        }
    }
}
