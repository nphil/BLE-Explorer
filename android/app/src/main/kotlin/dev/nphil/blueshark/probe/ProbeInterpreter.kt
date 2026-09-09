package dev.nphil.blueshark.probe

/** Header label column; "conclusion" is the longest one. */
private const val FIELD_WIDTH = 12

/** Opcodes per line in the compact lists, so a 20-step sweep stays two lines wide. */
private const val OPCODES_PER_LINE = 12

private const val STATUS_SUCCESS = 0x00
private const val STATUS_UNKNOWN_ID = 0x05
private const val STATUS_LAST_KNOWN = 0x06

/**
 * Turns raw responses into verdicts, and a finished run into something an operator can paste
 * somewhere useful.
 *
 * Free of Android imports, so both halves are unit-testable and the report can be rendered on a
 * worker dispatcher. Text is padded rather than tabbed: it lands in monospace in the app
 * ([dev.nphil.blueshark.ui.theme.MonoFamily]) and stays readable in a terminal or a code block,
 * the same as the signal and capture reports.
 */
object ProbeInterpreter {

    /**
     * The status the device reported, and what it proves.
     *
     * The published family answers every write with a framed status byte: `0x00` success, `0x05`
     * "no such command id", `0x01..0x06` other failures. That makes `0x05` the oracle this whole
     * feature is built on — it separates an opcode the device does not have from one it does,
     * without anyone having to interpret a panel.
     *
     * A response that will not decode, or that carries a status outside the published table, is
     * [ProbeVerdict.ERROR] rather than a rejection: claiming the device refused something when we
     * cannot read its answer would be an invention. The status byte is still returned when there
     * was one, so the report can show it.
     */
    fun verdictFor(response: ByteArray?, codec: FrameCodec): Pair<ProbeVerdict, Int?> {
        if (response == null) return ProbeVerdict.NO_RESPONSE to null
        val payload = codec.decode(response) ?: return ProbeVerdict.ERROR to null
        if (payload.isEmpty()) return ProbeVerdict.ERROR to null
        val status = payload[0].toInt() and 0xFF
        val verdict = when {
            status == STATUS_SUCCESS -> ProbeVerdict.ACCEPTED
            status == STATUS_UNKNOWN_ID -> ProbeVerdict.REJECTED_UNKNOWN_ID
            status <= STATUS_LAST_KNOWN -> ProbeVerdict.REJECTED_OTHER
            else -> ProbeVerdict.ERROR
        }
        return verdict to status
    }

    /** The published error table; anything else is reported as unrecognised rather than guessed. */
    fun statusName(status: Int?): String = when (status) {
        0x00 -> "success"
        0x01 -> "transmission failed"
        0x02 -> "device abnormality"
        0x03 -> "data error"
        0x04 -> "data length error"
        0x05 -> "no such command id"
        0x06 -> "data checksum error"
        null -> "no status"
        else -> "unrecognised status"
    }

    /**
     * The operator-facing report, grouped by verdict.
     *
     * [device] and [codec] are optional only so the contract call `summarise(outcomes)` still
     * compiles; pass them. The report travels on its own — whoever reads it will not have the run
     * on screen, so it names the device, the framing, the counts and the liveness evidence before
     * it draws any conclusion.
     *
     * The liveness rule is the part that keeps the report honest. Silence only means "this device
     * does not answer that opcode" while the link is up; once a canary goes quiet, every later
     * silence is a fact about the connection. Those steps are reported separately and explicitly
     * excluded from the conclusion instead of being counted as evidence of absence.
     */
    fun summarise(outcomes: List<ProbeOutcome>, device: String = "", codec: FrameCodec? = null): String {
        val out = StringBuilder(1024)
        out.append("BlueShark command probe\n")
        out.field("device", device.ifBlank { "not recorded" })
        out.field("codec", codec?.let { "${it.id} - ${it.label}" } ?: "not recorded")

        val canaries = outcomes.filter { it.step.canary }
        val silentCanaryAt = outcomes.indexOfFirst { it.step.canary && it.verdict == ProbeVerdict.NO_RESPONSE }

        // Silence stops being evidence at the first silent canary; everything at or after that
        // index is reported as a link failure instead of a verdict about an opcode.
        val accepted = ArrayList<ProbeOutcome>()
        val unknownId = ArrayList<ProbeOutcome>()
        val rejected = ArrayList<ProbeOutcome>()
        val undecodable = ArrayList<ProbeOutcome>()
        val silent = ArrayList<ProbeOutcome>()
        val unreliable = ArrayList<ProbeOutcome>()
        var probed = 0
        var labelWidth = 0
        for ((index, outcome) in outcomes.withIndex()) {
            if (outcome.step.canary) continue
            probed++
            if (outcome.step.label.length > labelWidth) labelWidth = outcome.step.label.length
            when (outcome.verdict) {
                ProbeVerdict.ACCEPTED -> accepted += outcome
                ProbeVerdict.REJECTED_UNKNOWN_ID -> unknownId += outcome
                ProbeVerdict.REJECTED_OTHER -> rejected += outcome
                ProbeVerdict.ERROR -> undecodable += outcome
                ProbeVerdict.NO_RESPONSE -> if (silentCanaryAt in 0..index) unreliable += outcome else silent += outcome
            }
        }
        labelWidth += 2

        out.field(
            "steps",
            "$probed probed · ${accepted.size} accepted · ${unknownId.size} unknown id · " +
                "${rejected.size} rejected · ${silent.size + unreliable.size} silent · ${undecodable.size} undecodable",
        )
        out.field("liveness", livenessLine(canaries, silentCanaryAt, outcomes))

        if (outcomes.isEmpty()) {
            out.append('\n')
            out.field("conclusion", "nothing ran, so nothing is known.")
            return out.toString()
        }

        out.section("accepted", accepted, labelWidth)
        out.compactSection("rejected as unknown id (0x05)", unknownId)
        out.section("rejected with another status", rejected, labelWidth)
        out.section("no response (link was up)", silent, labelWidth)
        out.section("no response after the device went quiet - not evidence", unreliable, labelWidth)
        out.section("undecodable response", undecodable, labelWidth)

        val effects = outcomes.filter { it.observedEffect.isNotBlank() }
        if (effects.isNotEmpty()) {
            out.append("\nobserved effects\n")
            val effectWidth = effects.maxOf { it.step.label.length } + 2
            for (outcome in effects) {
                out.append("  ")
                out.append(outcome.step.label.padEnd(effectWidth))
                out.append(outcome.observedEffect)
                out.append('\n')
            }
        }

        out.append('\n')
        out.field("conclusion", conclusion(accepted, unknownId, rejected, silent, unreliable, undecodable, silentCanaryAt))
        return out.toString()
    }

    private fun livenessLine(
        canaries: List<ProbeOutcome>,
        silentCanaryAt: Int,
        outcomes: List<ProbeOutcome>,
    ): String {
        if (canaries.isEmpty()) {
            return "no canary steps - silence below could be a dead link rather than a refusal"
        }
        val canaryCount = "${canaries.size} " + if (canaries.size == 1) "canary" else "canaries"
        if (silentCanaryAt == 0) {
            // The baseline canary never answered: notifications are probably not subscribed, or
            // the link is already gone. Nothing in the run below is evidence about any opcode.
            return "the baseline canary never answered - the device was not talking to us, " +
                "so nothing below is evidence about any opcode"
        }
        if (silentCanaryAt > 0) {
            // The silent canary sits at position silentCanaryAt + 1, so that many steps ran before it.
            val lastGood = silentCanaryAt
            val stranded = outcomes.drop(silentCanaryAt).count { !it.step.canary && it.verdict == ProbeVerdict.NO_RESPONSE }
            return "device stopped responding after step $lastGood - $stranded later silent " +
                (if (stranded == 1) "step is" else "steps are") + " not evidence of absence"
        }
        val odd = canaries.count { it.verdict != ProbeVerdict.ACCEPTED }
        if (odd > 0) {
            return "$canaryCount, all answered but $odd with an unexpected status - " +
                "the canary itself may be wrong; treat verdicts with care"
        }
        return "$canaryCount, all answered - silence below is evidence"
    }

    private fun conclusion(
        accepted: List<ProbeOutcome>,
        unknownId: List<ProbeOutcome>,
        rejected: List<ProbeOutcome>,
        silent: List<ProbeOutcome>,
        unreliable: List<ProbeOutcome>,
        undecodable: List<ProbeOutcome>,
        silentCanaryAt: Int,
    ): String {
        val out = StringBuilder(160)
        if (accepted.isEmpty()) {
            out.append("no opcode was accepted")
        } else {
            out.append("accepted ")
            out.append(opcodeList(accepted))
        }
        out.append("; ")
        out.append("${unknownId.size} rejected as unknown id")
        if (rejected.isNotEmpty()) out.append(", ${rejected.size} rejected otherwise")
        if (undecodable.isNotEmpty()) out.append(", ${undecodable.size} undecodable")
        if (silent.isNotEmpty()) out.append(", ${silent.size} silent")
        if (unreliable.isNotEmpty()) {
            out.append(", ${unreliable.size} silent ")
            out.append(
                if (silentCanaryAt == 0) {
                    "with the device never answering at all (not evidence)"
                } else {
                    "after the device stopped answering at step $silentCanaryAt (not evidence)"
                },
            )
        }
        out.append('.')
        return out.toString()
    }

    private fun opcodeList(outcomes: List<ProbeOutcome>): String =
        outcomes.map { it.step.opcode }.distinct().sorted().joinToString(", ") { hexByte(it) }

    private fun StringBuilder.field(label: String, value: String) {
        append(label.padEnd(FIELD_WIDTH))
        append(value)
        append('\n')
    }

    private fun StringBuilder.section(title: String, rows: List<ProbeOutcome>, labelWidth: Int) {
        if (rows.isEmpty()) return
        append('\n')
        append(title)
        append('\n')
        for (outcome in rows) {
            append("  ")
            append(outcome.step.label.padEnd(labelWidth))
            append(detail(outcome))
            append('\n')
        }
    }

    private fun StringBuilder.compactSection(title: String, rows: List<ProbeOutcome>) {
        if (rows.isEmpty()) return
        append('\n')
        append(title)
        append('\n')
        val opcodes = rows.map { it.step.opcode }.distinct().sorted()
        for ((index, opcode) in opcodes.withIndex()) {
            append(if (index % OPCODES_PER_LINE == 0) "  " else " ")
            append(hexByte(opcode))
            if (index % OPCODES_PER_LINE == OPCODES_PER_LINE - 1) append('\n')
        }
        if (opcodes.size % OPCODES_PER_LINE != 0) append('\n')
    }

    private fun detail(outcome: ProbeOutcome): String {
        val out = StringBuilder(64)
        when (outcome.verdict) {
            ProbeVerdict.NO_RESPONSE -> out.append("silent")
            ProbeVerdict.ERROR -> {
                out.append(outcome.statusByte?.let { "status ${hexByte(it)} unrecognised" } ?: "could not decode")
                outcome.responseHex?.let { out.append(" (${it})") }
            }
            else -> {
                out.append("status ")
                out.append(hexByte(outcome.statusByte ?: 0))
                out.append(' ')
                out.append(statusName(outcome.statusByte))
            }
        }
        out.append("  ")
        out.append(outcome.elapsedMs)
        out.append(" ms")
        if (outcome.note.isNotBlank()) {
            out.append(" · ")
            out.append(outcome.note)
        }
        return out.toString()
    }
}
