package dev.nphil.blueshark.crypto

import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.ByteRange
import dev.nphil.blueshark.model.ByteSource
import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.CipherByteOrder
import dev.nphil.blueshark.model.CipherPrimitive
import dev.nphil.blueshark.model.CipherScheme
import dev.nphil.blueshark.model.EventDirection
import dev.nphil.blueshark.model.toHex
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Everything about one frame a [ByteSource] may need that is not in the frame itself. */
data class FrameContext(
    val address: String = "",
    val counter: Int = 0,
    val direction: EventDirection? = null,
    val characteristicUuid: String? = null,
)

/**
 * What a scheme made of one frame.
 *
 * [verified] is null for the unauthenticated primitives: CTR and XOR produce plaintext-shaped
 * bytes from any key, so "it decrypted" is not evidence of anything and must not be shown as if
 * it were. [nonceHex] and [aadHex] are what the engine actually used, which is the only way an
 * operator can debug a scheme that produces garbage.
 */
data class DecryptResult(
    val plaintextHex: String? = null,
    val verified: Boolean? = null,
    val error: String? = null,
    val nonceHex: String = "",
    val aadHex: String = "",
    val ciphertextHex: String = "",
) {
    val decrypted: Boolean get() = plaintextHex != null
}

/**
 * Applies a [CipherScheme] to a frame. No ecosystem appears anywhere in here.
 *
 * A scheme is a primitive plus a description of where the key, nonce, AAD, tag and ciphertext
 * come from, so a new vendor scheme is data - see [Presets] - and never a new branch. The
 * ciphertext is never written back: [DecryptResult] is a view over evidence that stays intact.
 */
object CipherEngine {

    /**
     * Does [scheme] claim [event]? An empty [dev.nphil.blueshark.model.FrameMatch] claims every
     * frame that carries a payload.
     *
     * This is the hot overload: indexing a whole session runs on every keystroke in the editor, so
     * it neither parses the payload nor builds a [FrameContext] for a frame it is about to reject -
     * a prefix compares just as well against the hex string as against the bytes.
     */
    fun matches(scheme: CipherScheme, event: BleEvent): Boolean {
        if (!matchesMetadata(scheme, event.payloadHex.length / 2, event.direction, event.characteristicUuid)) {
            return false
        }
        val wanted = wantedPrefix(scheme) ?: return scheme.match.payloadPrefixHex == null
        if (wanted.isEmpty()) return true
        if (event.payloadHex.length < wanted.length) return false
        return event.payloadHex.regionMatches(0, wanted, 0, wanted.length, ignoreCase = true)
    }

    /** Byte-level match, for callers that already hold the frame and its context. */
    fun matches(scheme: CipherScheme, frame: ByteArray, context: FrameContext): Boolean {
        if (!matchesMetadata(scheme, frame.size, context.direction, context.characteristicUuid)) return false
        val wanted = wantedPrefix(scheme) ?: return scheme.match.payloadPrefixHex == null
        if (wanted.isEmpty()) return true
        if (frame.size * 2 < wanted.length) return false
        for (index in 0 until wanted.length / 2) {
            val expected = wanted.substring(index * 2, index * 2 + 2).toInt(16)
            if ((frame[index].toInt() and 0xFF) != expected) return false
        }
        return true
    }

    /** Null means the scheme names a prefix that is not hexadecimal, so nothing can match it. */
    private fun wantedPrefix(scheme: CipherScheme): String? {
        val prefix = scheme.match.payloadPrefixHex ?: return ""
        return KeyTools.normalizeHexKey(prefix)
    }

    private fun matchesMetadata(
        scheme: CipherScheme,
        frameSize: Int,
        direction: EventDirection?,
        characteristicUuid: String?,
    ): Boolean {
        val match = scheme.match
        if (frameSize == 0 || frameSize < match.minLength) return false
        match.direction?.let { if (direction != it) return false }
        match.characteristicUuid?.let { wanted ->
            val actual = characteristicUuid ?: return false
            if (!sameUuid(wanted, actual)) return false
        }
        return true
    }

    /**
     * Decrypts the ciphertext range of [frame] under [scheme].
     *
     * Never throws: a scheme the operator is still typing is wrong far more often than it is
     * right, and every failure mode has to reach the UI as text rather than a crash.
     */
    fun decrypt(scheme: CipherScheme, frame: ByteArray, context: FrameContext): DecryptResult {
        var nonceHex = ""
        var aadHex = ""
        var ciphertextHex = ""
        return try {
            val key = KeyTools.keyOf(scheme)
            val nonce = resolve(scheme.nonce, frame, context, scheme.counterLittleEndian)
            nonceHex = nonce.toHex()
            val aad = scheme.aad?.let { resolve(it, frame, context, scheme.counterLittleEndian) }
            aadHex = aad?.toHex().orEmpty()
            val ciphertext = slice(frame, scheme.ciphertextRange)
            ciphertextHex = ciphertext.toHex()
            require(ciphertext.isNotEmpty()) { "The ciphertext range selected no bytes of a ${frame.size} byte frame" }
            val tag = scheme.tag?.let { resolve(it, frame, context, scheme.counterLittleEndian) }
            val reversed = scheme.byteOrder == CipherByteOrder.REVERSED_BLOCKS
            require(!reversed || scheme.primitive in REVERSIBLE) {
                "Reversed block order is only defined for AES-ECB, AES-CBC and AES-CTR"
            }
            val outcome = when (scheme.primitive) {
                CipherPrimitive.AES_ECB -> Outcome(aesEcb(key, ciphertext, scheme.padded, reversed))
                CipherPrimitive.AES_CBC -> Outcome(aesCbc(key, nonce, ciphertext, scheme.padded, reversed))
                CipherPrimitive.AES_CTR -> Outcome(aesCtr(key, nonce, ciphertext, reversed))
                CipherPrimitive.AES_GCM -> aesGcm(key, nonce, ciphertext, aad, scheme.tagLength ?: 16, tag)
                CipherPrimitive.AES_CCM -> aesCcm(key, nonce, ciphertext, aad, scheme.tagLength ?: 8, tag)
                CipherPrimitive.CHACHA20_POLY1305 -> chacha(key, nonce, ciphertext, aad, tag)
                CipherPrimitive.XOR -> Outcome(xor(key, ciphertext))
            }
            DecryptResult(
                plaintextHex = outcome.plaintext?.toHex(),
                verified = outcome.verified,
                error = if (outcome.plaintext == null) "The authentication tag does not match this key" else null,
                nonceHex = nonceHex,
                aadHex = aadHex,
                ciphertextHex = ciphertextHex,
            )
        } catch (failure: Exception) {
            DecryptResult(
                error = failure.message ?: failure::class.simpleName ?: "Decryption failed",
                nonceHex = nonceHex,
                aadHex = aadHex,
                ciphertextHex = ciphertextHex,
            )
        }
    }

    /**
     * Resolves a [ByteSource] against one frame.
     *
     * @param littleEndianDefault what a [ByteSource.Counter] with no opinion of its own uses.
     */
    fun resolve(
        source: ByteSource,
        frame: ByteArray,
        context: FrameContext,
        littleEndianDefault: Boolean = true,
    ): ByteArray = when (source) {
        is ByteSource.Constant -> {
            val hex = requireNotNull(KeyTools.normalizeHexKey(source.hex)) {
                "\"${source.hex}\" is not whole hexadecimal bytes"
            }
            KeyTools.hexBytes(hex)
        }

        is ByteSource.FrameBytes -> slice(frame, ByteRange(source.offset, source.length))
        is ByteSource.Slice -> slice(
            resolve(source.source, frame, context, littleEndianDefault),
            ByteRange(source.offset, source.length),
        )

        is ByteSource.Composite -> {
            var total = 0
            val parts = arrayOfNulls<ByteArray>(source.parts.size)
            for (index in source.parts.indices) {
                val part = resolve(source.parts[index], frame, context, littleEndianDefault)
                parts[index] = part
                total += part.size
            }
            val joined = ByteArray(total)
            var at = 0
            for (part in parts) {
                part!!.copyInto(joined, at)
                at += part.size
            }
            joined
        }

        ByteSource.MacAddress -> addressBytes(context.address)
        ByteSource.MacAddressReversed -> addressBytes(context.address).also { it.reverse() }
        is ByteSource.Counter -> {
            require(source.width in 1..8) { "A counter is 1..8 bytes wide, not ${source.width}" }
            val littleEndian = source.littleEndian ?: littleEndianDefault
            ByteArray(source.width) { index ->
                val shift = if (littleEndian) index else source.width - 1 - index
                (context.counter ushr (8 * shift) and 0xFF).toByte()
            }
        }
    }

    /**
     * The bytes [range] names, with negative offsets counting back from the end.
     *
     * Out-of-range never throws: a range the operator is mid-edit clamps to what exists, and an
     * empty result is reported as "selected no bytes" rather than as a crash.
     */
    fun slice(frame: ByteArray, range: ByteRange): ByteArray {
        val start = (if (range.offset < 0) frame.size + range.offset else range.offset)
            .coerceIn(0, frame.size)
        val end = if (range.length < 0) {
            frame.size - range.dropFromEnd
        } else {
            start + range.length - range.dropFromEnd
        }.coerceIn(start, frame.size)
        return frame.copyOfRange(start, end)
    }

    private class Outcome(val plaintext: ByteArray?, val verified: Boolean? = null)

    private fun aesEcb(key: ByteArray, ciphertext: ByteArray, padded: Boolean, reversed: Boolean): ByteArray {
        require(padded || ciphertext.size % 16 == 0) {
            "AES-ECB needs whole 16-byte blocks; this range is ${ciphertext.size} bytes"
        }
        if (!reversed) return transform("AES/ECB/${padding(padded)}", key, null, ciphertext)
        require(!padded) { "Reversed block order and PKCS#5 padding do not occur together" }
        val out = ByteArray(ciphertext.size)
        val block = ByteArray(16)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key.reversedCopy(), "AES"))
        }
        var at = 0
        while (at < ciphertext.size) {
            ciphertext.copyInto(block, 0, at, at + 16)
            block.reverse()
            cipher.doFinal(block, 0, 16, block, 0)
            block.reverse()
            block.copyInto(out, at)
            at += 16
        }
        return out
    }

    private fun aesCbc(
        key: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        padded: Boolean,
        reversed: Boolean,
    ): ByteArray {
        require(iv.size == 16) { "AES-CBC needs a 16-byte IV, this scheme produced ${iv.size}" }
        require(padded || ciphertext.size % 16 == 0) {
            "AES-CBC needs whole 16-byte blocks; this range is ${ciphertext.size} bytes"
        }
        require(!reversed) { "Reversed block order with CBC chaining is not attested by any source" }
        return transform("AES/CBC/${padding(padded)}", key, IvParameterSpec(iv), ciphertext)
    }

    /**
     * A nonce shorter than a block is right-padded with zeros, which is the convention every
     * vendor CTR scheme observed uses: the counter starts at zero in the low bytes.
     */
    private fun aesCtr(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, reversed: Boolean): ByteArray {
        require(nonce.size in 1..16) { "AES-CTR needs a 1..16 byte nonce, this scheme produced ${nonce.size}" }
        val iv = if (nonce.size == 16) nonce else ByteArray(16).also { nonce.copyInto(it) }
        if (!reversed) return transform("AES/CTR/NoPadding", key, IvParameterSpec(iv), ciphertext)
        // The reversed orientation has no standard CTR to defer to, so the counter is walked here:
        // the block is built naturally, incremented big-endian like every other CTR, and only the
        // permutation is reversed. Telink frames never exceed one block, so that is all that is
        // attested; longer payloads extend it the obvious way.
        val out = ByteArray(ciphertext.size)
        val counter = iv.copyOf()
        val keystream = ByteArray(16)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.reversedCopy(), "AES"))
        }
        var at = 0
        while (at < ciphertext.size) {
            counter.copyInto(keystream)
            keystream.reverse()
            cipher.doFinal(keystream, 0, 16, keystream, 0)
            keystream.reverse()
            val span = minOf(16, ciphertext.size - at)
            for (index in 0 until span) {
                out[at + index] = (ciphertext[at + index].toInt() xor keystream[index].toInt()).toByte()
            }
            at += span
            var position = 15
            while (position >= 0 && ++counter[position] == 0.toByte()) position--
        }
        return out
    }

    private fun aesGcm(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray?,
        tagLength: Int,
        tag: ByteArray?,
    ): Outcome {
        require(nonce.isNotEmpty()) { "AES-GCM needs a nonce; 12 bytes is the standard length" }
        require(tagLength in 4..16) { "AES-GCM tags are 4..16 bytes, not $tagLength" }
        val body = tag?.let { ciphertext + it } ?: ciphertext
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagLength * 8, nonce))
            aad?.let { updateAAD(it) }
        }
        return try {
            Outcome(cipher.doFinal(body), verified = true)
        } catch (_: AEADBadTagException) {
            Outcome(null, verified = false)
        }
    }

    private fun aesCcm(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray?,
        tagLength: Int,
        tag: ByteArray?,
    ): Outcome {
        val plaintext = AesCcm.decrypt(
            key = key,
            nonce = nonce,
            ciphertext = ciphertext,
            aad = aad ?: ByteArray(0),
            tagLength = tagLength,
            tag = tag,
        )
        return Outcome(plaintext, verified = plaintext != null)
    }

    private fun chacha(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray?,
        tag: ByteArray?,
    ): Outcome {
        require(key.size == 32) { "ChaCha20-Poly1305 needs a 32-byte key, got ${key.size}" }
        require(nonce.size == 12) { "ChaCha20-Poly1305 needs a 12-byte nonce, this scheme produced ${nonce.size}" }
        val body = tag?.let { ciphertext + it } ?: ciphertext
        val cipher = Cipher.getInstance("ChaCha20-Poly1305").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            aad?.let { updateAAD(it) }
        }
        return try {
            Outcome(cipher.doFinal(body), verified = true)
        } catch (_: AEADBadTagException) {
            Outcome(null, verified = false)
        }
    }

    private fun xor(key: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "An XOR keystream needs at least one key byte" }
        return ByteArray(ciphertext.size) { index ->
            (ciphertext[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
    }

    private fun padding(padded: Boolean) = if (padded) "PKCS5Padding" else "NoPadding"

    private fun ByteArray.reversedCopy(): ByteArray = copyOf().also { it.reverse() }

    private val REVERSIBLE = setOf(CipherPrimitive.AES_ECB, CipherPrimitive.AES_CBC, CipherPrimitive.AES_CTR)

    private fun transform(
        transformation: String,
        key: ByteArray,
        spec: IvParameterSpec?,
        input: ByteArray,
    ): ByteArray = Cipher.getInstance(transformation).run {
        if (spec == null) {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        } else {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        }
        doFinal(input)
    }

    /** `AA:BB:…` and `aabb…` both parse; anything else is a scheme the operator must fix. */
    private fun addressBytes(address: String): ByteArray {
        val hex = requireNotNull(KeyTools.normalizeHexKey(address)) {
            "\"$address\" is not a device address this scheme can use"
        }
        require(hex.length == 12) { "A device address is 6 bytes, \"$address\" is ${hex.length / 2}" }
        return KeyTools.hexBytes(hex)
    }

    /** 16-bit shorthand matches its 128-bit form, because sessions hold both. */
    private fun sameUuid(wanted: String, actual: String): Boolean {
        if (wanted.equals(actual, ignoreCase = true)) return true
        val a = canonicalUuid(wanted)
        val b = canonicalUuid(actual)
        return a != null && a == b
    }

    private fun canonicalUuid(uuid: String): String? {
        val text = uuid.trim().lowercase()
        return when (text.length) {
            4 -> "0000$text-0000-1000-8000-00805f9b34fb"
            8 -> "$text-0000-1000-8000-00805f9b34fb"
            36 -> text
            else -> null
        }
    }
}

/** One event's decrypted view: which scheme produced it, and what it produced. */
data class DecryptedFrame(
    val schemeId: String,
    val schemeName: String,
    val result: DecryptResult,
)

/**
 * Applies a session's schemes across its events.
 *
 * Two costs are separated on purpose. Matching every event against every enabled scheme, and
 * assigning each matched event its session counter, is a single O(events × schemes) pass with no
 * crypto in it - [index]. Actually decrypting is deferred to [DecryptCache.frameFor] so a
 * thousand-event timeline only pays for the rows the operator can see.
 *
 * The counter has to be indexed eagerly because it is positional: a frame's counter is how many
 * earlier frames the scheme matched, which a lazily-decrypted row cannot work out for itself.
 */
object SchemeApplier {

    /** Which scheme claims each event, and with what counter. Keyed by event id. */
    fun index(
        session: CaptureSession,
        schemes: List<CipherScheme>,
        events: List<BleEvent>,
    ): Map<String, Assignment> {
        val active = schemes.filter { it.enabled }
        if (active.isEmpty() || events.isEmpty()) return emptyMap()
        val counters = IntArray(active.size)
        val assignments = HashMap<String, Assignment>()
        val address = session.device.address
        for (event in events) {
            if (event.payloadHex.isEmpty()) continue
            for (index in active.indices) {
                val scheme = active[index]
                if (!CipherEngine.matches(scheme, event)) continue
                val context = FrameContext(
                    address = address,
                    counter = counters[index],
                    direction = event.direction,
                    characteristicUuid = event.characteristicUuid,
                )
                assignments[event.id] = Assignment(scheme, context)
                counters[index]++
                // First enabled match wins: two schemes claiming one frame is a scheme the
                // operator has to disambiguate, not something to guess at.
                break
            }
        }
        return assignments
    }

    /** Every event's decrypted view, computed now. Used by exports and tests, not by the UI. */
    fun apply(
        session: CaptureSession,
        schemes: List<CipherScheme>,
        events: List<BleEvent>,
    ): Map<String, DecryptedFrame> {
        val assignments = index(session, schemes, events)
        if (assignments.isEmpty()) return emptyMap()
        val results = HashMap<String, DecryptedFrame>(assignments.size)
        for (event in events) {
            val assignment = assignments[event.id] ?: continue
            results[event.id] = assignment.decrypt(event)
        }
        return results
    }

    class Assignment(val scheme: CipherScheme, val context: FrameContext) {
        fun decrypt(event: BleEvent): DecryptedFrame {
            val hex = KeyTools.normalizeHexKey(event.payloadHex)
            val result = if (hex == null) {
                DecryptResult(error = "This frame is not whole hexadecimal bytes")
            } else {
                CipherEngine.decrypt(scheme, KeyTools.hexBytes(hex), context)
            }
            return DecryptedFrame(scheme.id, scheme.name, result)
        }
    }
}

/**
 * A session's decrypted view, memoised per event.
 *
 * Built once per (schemes, events) pair - `remember(session.ciphers, session.events)` in Compose -
 * so scrolling the timeline decrypts each row once and never again. [ConcurrentHashMap] because
 * Compose may lay out rows off the main thread and two rows must not race to the same entry.
 */
class DecryptCache(
    session: CaptureSession,
    schemes: List<CipherScheme>,
    events: List<BleEvent>,
) {
    private val assignments = SchemeApplier.index(session, schemes, events)
    private val frames = events
    private val computed = ConcurrentHashMap<String, DecryptedFrame>()

    /** How many frames each scheme claims. Counted while indexing, so it costs no crypto. */
    private val perScheme: Map<String, Int> = if (assignments.isEmpty()) {
        emptyMap()
    } else {
        HashMap<String, Int>(schemes.size).also { counts ->
            for (assignment in assignments.values) {
                counts[assignment.scheme.id] = (counts[assignment.scheme.id] ?: 0) + 1
            }
        }
    }

    /** True when no enabled scheme claims any frame, so the UI can stay out of the way. */
    val empty: Boolean get() = assignments.isEmpty()

    val matchedCount: Int get() = assignments.size

    fun matchCountOf(schemeId: String): Int = perScheme[schemeId] ?: 0

    /** The scheme that claims [eventId], whether or not it decrypts. */
    fun schemeFor(eventId: String): CipherScheme? = assignments[eventId]?.scheme

    fun frameFor(event: BleEvent): DecryptedFrame? {
        val assignment = assignments[event.id] ?: return null
        return computed.getOrPut(event.id) { assignment.decrypt(event) }
    }

    /** The plaintext hex for [event], or its ciphertext when nothing decrypts it. */
    fun payloadFor(event: BleEvent): String =
        frameFor(event)?.result?.plaintextHex ?: event.payloadHex

    /** The first frame [schemeId] claims, for the editor's "Try it" panel. */
    fun firstMatch(schemeId: String): BleEvent? =
        frames.firstOrNull { assignments[it.id]?.scheme?.id == schemeId }

    /**
     * Up to [limit] frames [schemeId] claims.
     *
     * Bounded on purpose: every caller either shows a picker or samples the scheme's health, and
     * neither has a reason to force the cipher over ten thousand frames.
     */
    fun matchesOf(schemeId: String, limit: Int = 40): List<BleEvent> {
        val out = ArrayList<BleEvent>(minOf(limit, 16))
        for (event in frames) {
            if (assignments[event.id]?.scheme?.id != schemeId) continue
            out.add(event)
            if (out.size == limit) break
        }
        return out
    }

    /** Decrypts [event] under [scheme] regardless of matching, for the editor's preview. */
    fun preview(scheme: CipherScheme, event: BleEvent, address: String): DecryptResult {
        val hex = KeyTools.normalizeHexKey(event.payloadHex)
            ?: return DecryptResult(error = "This frame is not whole hexadecimal bytes")
        val counter = assignments[event.id]?.takeIf { it.scheme.id == scheme.id }?.context?.counter ?: 0
        return CipherEngine.decrypt(
            scheme,
            KeyTools.hexBytes(hex),
            FrameContext(
                address = address,
                counter = counter,
                direction = event.direction,
                characteristicUuid = event.characteristicUuid,
            ),
        )
    }
}
