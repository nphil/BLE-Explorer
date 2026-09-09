package dev.nphil.blueshark.probe

private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

/** `0x0F`. Hand-rolled so the report never picks up locale digit shaping from `String.format`. */
internal fun hexByte(value: Int): String {
    val masked = value and 0xFF
    return "0x${HEX_DIGITS[masked ushr 4]}${HEX_DIGITS[masked and 0x0F]}"
}

/** Lowest opcode the discovery sweep touches; `0x00` is as likely a bootloader trigger as a no-op. */
private const val SWEEP_FIRST = 0x01

/** Highest opcode the discovery sweep touches — the published family crowds into the low ids. */
private const val SWEEP_LAST = 0x14

/** Documented handshake in the published driver; sent first in case the device gates on it. */
private const val OPCODE_INITIALIZE = 0x23

/**
 * The verified-live opcode on the motivating device: the operator wrote `08 FF` and the panel
 * changed mode, so `0x08` exists and is answered.
 */
private const val CANARY_OPCODE = 0x08

/**
 * The verified-inert argument: the operator wrote `08 40` and nothing changed, which is exactly
 * what a liveness probe needs — an answer without a side effect.
 */
private const val CANARY_VALUE: Byte = 0x40

/**
 * Published names, taken from the CoolLED driver's own hardware table
 * (UpDryTwist/coolledx-driver, `src/coolledx/hardware.py`, `class CoolLED`, lines 91-177), kept
 * separate from the sweep so a label can say "brightness?" while the sweep stays agnostic.
 *
 * Every name is a guess for *this* device. The operator's `08 FF` changed mode where that table
 * puts mode at `0x06` and brightness at `0x08`, so the id map is shifted by roughly +2 and no
 * published name can be trusted until a status byte confirms it. Ids the driver does not name at
 * all — `0x0B`, `0x0E`, `0x0F`, `0x10`, `0x14` — are labelled "unmapped".
 */
private val PUBLISHED_NAMES = mapOf(
    0x01 to "music",
    0x02 to "text",
    0x03 to "image",
    0x04 to "animation",
    0x05 to "icon / button off", // the driver returns 0x05 for both cmdbyte_icon and cmdbyte_buttonoff
    0x06 to "mode",
    0x07 to "speed",
    0x08 to "brightness",
    0x09 to "switch",
    0x0A to "xfer",
    0x0C to "invert display",
    0x0D to "clear maybe",
    0x11 to "show icon",
    0x12 to "power down",
    0x13 to "button on",
    0x15 to "invert or something",
    0x1F to "request something",
    OPCODE_INITIALIZE to "initialize",
)

/**
 * Benign arguments for the ids whose published meaning suggests a safe value. Everything else gets
 * `0x00`: for an unmapped id the lowest-magnitude argument is the most reversible one available.
 *
 * The content-transfer ids (`0x01` music, `0x02` text, `0x03` image, `0x04` animation) keep that
 * single `0x00` byte deliberately: it is far too short for a content command, so a device that has
 * them should answer DATA_LENGTH_ERROR rather than overwrite what is on the panel. The error is
 * the evidence we want; the content is not ours to replace.
 */
private val BENIGN_ARGUMENTS = mapOf(
    0x06 to 0x00, // first mode rather than an out-of-range one
    0x08 to 0x40, // mid brightness; verified to change nothing on the motivating device
    0x0C to 0x00, // not inverted, i.e. back to normal
    0x11 to 0x00, // no icon
    0x13 to 0x01, // buttons on: restores the operator's recovery path rather than removing it
    OPCODE_INITIALIZE to 0x01,
)

/** Sweeps the prober can build. Every plan here is data only — nothing touches Bluetooth. */
object ProbePlans {

    /**
     * Opcodes no default plan may contain, because a wrong guess is not recoverable by writing the
     * opposite value. Three failure modes drive this list: a mid-sweep power-off makes every later
     * step read as NO_RESPONSE (silence that looks like evidence of absence but is not), a clear
     * wipes what the operator stored on the device, and locking the physical buttons removes the
     * recovery path they would use when either of the first two happens.
     *
     * Ids come from the driver's own table (UpDryTwist/coolledx-driver, `src/coolledx/hardware.py`)
     * and each is denied together with its `+2` image, because the operator's `08 FF` changed mode
     * where that table puts mode at `0x06`: this device's ids are shifted, so the dangerous
     * *function* may sit two ids above its published number.
     *
     *  - `0x05` `cmdbyte_buttonoff` (hardware.py:115, and `cmdbyte_icon` returns the same id at
     *    :108) — locks the physical buttons, silently removing the operator's way back.
     *  - `0x07` where button-off lands under the `+2` shift (published `cmdbyte_speed`, :123).
     *  - `0x09` `cmdbyte_switch` (:131) — turns the panel off.
     *  - `0x0B` where switch lands under the `+2` shift; the driver names no `0x0B` at all.
     *  - `0x0D` `cmdbyte_clearmaybe` (:147) — clears stored content.
     *  - `0x0F` where clear lands under the `+2` shift; unnamed in the driver.
     *  - `0x12` `cmdbyte_powerdown` (:157).
     *  - `0x14` where power down lands under the `+2` shift; unnamed in the driver.
     *
     * Deliberately *not* denied: `0x0C` `cmdbyte_invertdisplay` (:141) and its shifted image
     * `0x0E`. Inverting a display is reversible by writing the opposite value, which is the whole
     * test for membership here.
     *
     * What this list cannot cover: an id the driver never documented could still be a reset. The
     * sweep bounds that exposure rather than eliminating it — `0x00` argument for every unmapped
     * id, a too-short argument for the content-transfer ids, range capped at [SWEEP_LAST], nothing
     * above it except the documented handshake — and it is why the sweep is run by an operator who
     * is watching the panel.
     */
    val DESTRUCTIVE_OPCODES: Set<Int> = setOf(0x05, 0x07, 0x09, 0x0B, 0x0D, 0x0F, 0x12, 0x14)

    /** Why [opcode] is denied, or null when it is not in [DESTRUCTIVE_OPCODES]. */
    fun destructiveReason(opcode: Int): String? = when (opcode) {
        0x05 -> "driver cmdbyte_buttonoff: locks the physical buttons, removing the recovery path"
        0x07 -> "where button-off 0x05 lands under this device's +2 id shift (published speed)"
        0x09 -> "driver cmdbyte_switch: can power the panel off mid-sweep"
        0x0B -> "where switch 0x09 lands under this device's +2 id shift"
        0x0D -> "driver cmdbyte_clearmaybe: clears stored content"
        0x0F -> "where clear 0x0D lands under this device's +2 id shift"
        0x12 -> "driver cmdbyte_powerdown"
        0x14 -> "where power down 0x12 lands under this device's +2 id shift"
        else -> null
    }

    /**
     * The safe discovery sweep: every opcode in `0x01..0x14` except [DESTRUCTIVE_OPCODES], plus the
     * documented handshake `0x23` first in case the device rejects commands until it is initialised.
     * That is 13 steps — `0x23`, then `0x01..0x04`, `0x06`, `0x08`, `0x0A`, `0x0C`, `0x0E`, `0x10`,
     * `0x11`, `0x13`.
     *
     * The unmapped neighbours are the point, not padding. A device that answers DATA_ID_ERROR
     * (`0x05`) to `0x10` and SUCCESS (`0x00`) to `0x08` has told you which ids exist without the
     * operator having to interpret the panel; without the neighbours, a run of successes could just
     * as easily mean the device acknowledges everything.
     *
     * Wrap this with [withCanaries] before running it against hardware — otherwise a link that
     * dies mid-sweep produces silence that [ProbeInterpreter.summarise] cannot distinguish from a
     * device declining to answer.
     */
    fun coolLedOpcodeSweep(): List<ProbeStep> {
        val steps = ArrayList<ProbeStep>(SWEEP_LAST - SWEEP_FIRST + 2)
        steps += stepFor(OPCODE_INITIALIZE)
        for (opcode in SWEEP_FIRST..SWEEP_LAST) {
            if (opcode in DESTRUCTIVE_OPCODES) continue
            steps += stepFor(opcode)
        }
        return steps
    }

    /**
     * One opcode across several argument values — how a suspected opcode is pinned down once the
     * sweep says it exists.
     *
     * **This is the only way to reach [DESTRUCTIVE_OPCODES], and it does not stop you.** Passing
     * power down `0x12`, or an id that turns out to be a factory reset, will power the device off
     * or wipe what is stored on it; every step after that in the same run becomes meaningless
     * because the device is no longer listening. Ask the operator first, keep the run short, and
     * expect to reconnect afterwards. Labels for these ids carry a `[destructive]` marker so the
     * risk is visible in the run list rather than buried in a plan.
     */
    fun valueSweep(opcode: Int, values: List<Int>): List<ProbeStep> {
        require(opcode in 0..0xFF) { "Opcode must be one byte, was $opcode" }
        return values.map { value ->
            require(value in 0..0xFF) { "Argument must be one byte, was $value" }
            val argument = byteArrayOf(value.toByte())
            ProbeStep(opcode, argument, labelFor(opcode, argument))
        }
    }

    /**
     * A liveness probe: `0x08 = 0x40`, verified on the motivating device to be answered and to
     * change nothing visible. Marked [ProbeStep.canary] so [ProbeInterpreter.summarise] can tell a
     * dead link from a declined opcode.
     */
    fun canaryStep(): ProbeStep = canaryStep(preceding = 0)

    /**
     * Interleaves [canaryStep] through [steps]: one before the first step, one after every [every]
     * steps, and one at the end, so both ends of the run are certified.
     *
     * Without the trailing canary the tail of a sweep is unfalsifiable — the device could have
     * stopped answering at any point after the last canary and the report would still read those
     * steps as verdicts.
     */
    fun withCanaries(steps: List<ProbeStep>, every: Int = 5): List<ProbeStep> {
        require(every > 0) { "Canary interval must be positive, was $every" }
        if (steps.isEmpty()) return emptyList()
        val out = ArrayList<ProbeStep>(steps.size + steps.size / every + 2)
        out += canaryStep(preceding = 0)
        var placed = 0
        for (step in steps) {
            out += step
            placed++
            if (placed % every == 0 && placed < steps.size) out += canaryStep(preceding = out.size)
        }
        out += canaryStep(preceding = out.size)
        return out
    }

    private fun stepFor(opcode: Int): ProbeStep {
        val argument = byteArrayOf((BENIGN_ARGUMENTS[opcode] ?: 0x00).toByte())
        return ProbeStep(opcode, argument, labelFor(opcode, argument))
    }

    /** [preceding] is how many steps run before this canary; 0 makes it the baseline. */
    private fun canaryStep(preceding: Int): ProbeStep {
        val where = if (preceding == 0) "baseline" else "after step $preceding"
        return ProbeStep(
            opcode = CANARY_OPCODE,
            argument = byteArrayOf(CANARY_VALUE),
            label = "canary ${hexByte(CANARY_OPCODE)} = ${hexByte(CANARY_VALUE.toInt())} ($where)",
            canary = true,
        )
    }

    /** `brightness? 0x08 = 0x40`, `unmapped 0x0A = 0x00`, `power down? 0x12 = 0x00 [destructive]`. */
    private fun labelFor(opcode: Int, argument: ByteArray): String {
        val out = StringBuilder(48)
        out.append(PUBLISHED_NAMES[opcode]?.let { "$it? " } ?: "unmapped ")
        out.append(hexByte(opcode))
        if (argument.isNotEmpty()) {
            out.append(" = ")
            for ((index, byte) in argument.withIndex()) {
                if (index > 0) out.append(' ')
                out.append(hexByte(byte.toInt()))
            }
        }
        if (opcode in DESTRUCTIVE_OPCODES) out.append(" [destructive]")
        return out.toString()
    }
}
