package dev.nphil.blueshark.probe

/**
 * One write the prober will perform: an opcode plus its argument bytes.
 *
 * The bytes actually written are `FrameCodec.encode(payload())` — the step describes the *logical*
 * command, the codec owns the framing. [label] is what the operator reads while the sweep runs, so
 * it names the guess as a guess ("brightness? 0x08 = 0x40") rather than asserting a mapping this
 * device has not confirmed.
 *
 * [canary] marks a liveness probe rather than a discovery step. A canary is a write that is known
 * to answer and known to change nothing; when one stops answering, every later silence is a fact
 * about the link, not about the opcode. [ProbeInterpreter.summarise] enforces that reading.
 *
 * `equals`/`hashCode` are hand-written because [argument] is a [ByteArray]: the generated ones
 * would compare array identity, which breaks list diffing and makes tests lie.
 */
class ProbeStep(
    val opcode: Int,
    val argument: ByteArray,
    val label: String,
    val canary: Boolean = false,
) {
    init {
        require(opcode in 0..0xFF) { "Opcode must be one byte, was $opcode" }
    }

    /** Opcode byte followed by [argument] — the logical payload handed to [FrameCodec.encode]. */
    fun payload(): ByteArray {
        val out = ByteArray(argument.size + 1)
        out[0] = opcode.toByte()
        argument.copyInto(out, 1)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProbeStep) return false
        return opcode == other.opcode &&
            canary == other.canary &&
            label == other.label &&
            argument.contentEquals(other.argument)
    }

    override fun hashCode(): Int {
        var result = opcode
        result = 31 * result + argument.contentHashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + canary.hashCode()
        return result
    }

    override fun toString(): String = "ProbeStep($label)"
}

/** What one step proved. */
enum class ProbeVerdict {
    /** The device answered with a success status: the opcode exists and took the argument. */
    ACCEPTED,

    /** The device answered "no such command id" — the strongest available evidence of absence. */
    REJECTED_UNKNOWN_ID,

    /** The device answered with some other error: the opcode is likely real, the argument wrong. */
    REJECTED_OTHER,

    /** Nothing arrived inside the window. Only evidence when a canary still answers after it. */
    NO_RESPONSE,

    /** Something arrived that this codec cannot read as a frame, or a status this table lacks. */
    ERROR,
}

/**
 * The record of one performed step: what went out, what came back, and what that means.
 *
 * [observedEffect] is the operator's own note — "panel changed mode" — and is the only field the
 * device cannot supply. It is what turns an accepted status into a known function.
 */
data class ProbeOutcome(
    val step: ProbeStep,
    val sentHex: String,
    val responseHex: String?,
    val verdict: ProbeVerdict,
    val statusByte: Int?,
    val elapsedMs: Long,
    val note: String = "",
    val observedEffect: String = "",
)
