package dev.nphil.blestudio.crypto

import dev.nphil.blestudio.model.BleEvent
import dev.nphil.blestudio.model.ByteSource
import dev.nphil.blestudio.model.CaptureSession
import dev.nphil.blestudio.model.CipherScheme
import dev.nphil.blestudio.model.KeyDerivation
import dev.nphil.blestudio.model.toHex
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** How the operator typed the secret they copied out of a vendor app or a cloud console. */
enum class KeyEncoding(val label: String) {
    HEX("Hex"),
    BASE64("Base64"),
    UTF8("Text"),
}

/**
 * Turning whatever the operator has into the bytes a cipher wants, with no ecosystem knowledge.
 *
 * Vendor apps and cloud consoles hand out the same 16 bytes in three shapes - hex, base64, and a
 * printable "local key" that is really UTF-8 - and then ecosystems derive the working key from it
 * in a handful of ways ([KeyDerivation]). Both halves live here so a new ecosystem is a new
 * [CipherScheme], not new code.
 */
object KeyTools {

    /**
     * Normalises a typed secret to uppercase hex.
     *
     * @return the hex, or null when the text is not valid in [encoding]. Whitespace, `:` and `-`
     *   are always ignored, and a `0x` prefix is dropped, because that is how keys get pasted.
     */
    fun normalize(raw: String, encoding: KeyEncoding): String? = when (encoding) {
        KeyEncoding.HEX -> normalizeHexKey(raw)
        KeyEncoding.BASE64 -> runCatching { Base64.getDecoder().decode(raw.trim()) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.toHex()

        KeyEncoding.UTF8 -> raw.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)?.toHex()
    }

    /** The encoding that best explains [raw], so pasting a key does not need a radio button first. */
    fun guessEncoding(raw: String): KeyEncoding {
        val trimmed = raw.trim()
        if (normalizeHexKey(trimmed) != null) return KeyEncoding.HEX
        val base64 = runCatching { Base64.getDecoder().decode(trimmed) }.getOrNull()
        if (base64 != null && base64.size in KEY_SIZES) return KeyEncoding.BASE64
        return KeyEncoding.UTF8
    }

    /**
     * The bytes [scheme] actually keys its cipher with.
     *
     * @throws IllegalArgumentException when the entered key is not hex, or the derivation needs a
     *   salt or constant the scheme does not carry.
     */
    fun keyOf(scheme: CipherScheme): ByteArray {
        val entered = requireNotNull(normalizeHexKey(scheme.keyHex)?.let(::hexBytes)) {
            "The key is not whole hexadecimal bytes"
        }
        require(entered.isNotEmpty()) { "This scheme has no key yet" }
        return derive(entered, scheme.keyDerivation, scheme.keySaltHex, scheme.keyConstantHex)
    }

    /**
     * @param salt required by [KeyDerivation.HMAC_SHA256_WITH_SALT]; the HMAC is keyed with
     *   [secret] and run over the salt, which is the shape every vendor scheme observed uses.
     * @param constant the 16-byte block [KeyDerivation.AES_ECB_OF_CONSTANT] encrypts under
     *   [secret] - the classic "session key = AES(login key, constant)" setup.
     */
    fun derive(
        secret: ByteArray,
        derivation: KeyDerivation,
        salt: String? = null,
        constant: String? = null,
    ): ByteArray = when (derivation) {
        KeyDerivation.RAW -> secret
        KeyDerivation.SHA256 -> MessageDigest.getInstance("SHA-256").digest(secret)
        KeyDerivation.MD5 -> MessageDigest.getInstance("MD5").digest(secret)
        KeyDerivation.HMAC_SHA256_WITH_SALT -> {
            val saltBytes = requireNotNull(normalizeHexKey(salt)?.let(::hexBytes)) {
                "HMAC-SHA256 needs a hexadecimal salt"
            }
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(secret, "HmacSHA256"))
                doFinal(saltBytes)
            }
        }

        KeyDerivation.AES_ECB_OF_CONSTANT -> {
            val block = requireNotNull(normalizeHexKey(constant)?.let(::hexBytes)) {
                "AES-ECB derivation needs a hexadecimal constant"
            }
            require(block.size % 16 == 0 && block.isNotEmpty()) {
                "AES-ECB derivation needs whole 16-byte blocks, got ${block.size}"
            }
            Cipher.getInstance("AES/ECB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"))
                doFinal(block)
            }
        }
    }

    /**
     * Derives a session key from a captured handshake.
     *
     * The shape every ecosystem observed shares: the phone sends a random, the device answers
     * with another, and the session key is a fixed function of the two plus a long-term secret.
     * [rule] expresses that without naming any ecosystem - each part is a [ByteSource] resolved
     * against its own captured frame, the parts concatenate in order, and
     * [HandshakeRule.derivation] turns that material into a key:
     *
     * - `RAW` - the material *is* the key (a device that ships the session key in the clear).
     * - `MD5` / `SHA256` - the key is a digest of the material, e.g. Tuya's
     *   `session_key = MD5(local_key[:6] + srand)`.
     * - `AES_ECB_OF_CONSTANT` - the key is [HandshakeRule.keyHex] encrypting the material, the
     *   classic `session_key = AES(login_key, nonce)`.
     * - `HMAC_SHA256_WITH_SALT` - the key is HMAC([HandshakeRule.keyHex], material).
     *
     * @return the derived key as uppercase hex.
     */
    fun deriveFromHandshake(events: List<BleEvent>, rule: HandshakeRule): Result<String> = runCatching {
        require(rule.parts.isNotEmpty()) { "A handshake rule needs at least one part" }
        val resolved = rule.parts.map { part ->
            val event = events.firstOrNull { it.id == part.eventId }
                ?: throw IllegalArgumentException("The \"${part.label}\" frame is no longer in this session")
            val frame = requireNotNull(normalizeHexKey(event.payloadHex)?.let(::hexBytes)) {
                "The \"${part.label}\" frame is not whole hexadecimal bytes"
            }
            CipherEngine.resolve(part.bytes, frame, FrameContext(), littleEndianDefault = true)
        }
        val material = ByteArray(resolved.sumOf { it.size })
        var at = 0
        for (part in resolved) {
            part.copyInto(material, at)
            at += part.size
        }
        require(material.isNotEmpty()) { "The handshake rule selected no bytes" }
        when (rule.derivation) {
            KeyDerivation.RAW -> material
            KeyDerivation.MD5, KeyDerivation.SHA256 -> derive(material, rule.derivation)
            KeyDerivation.AES_ECB_OF_CONSTANT ->
                derive(longTermKey(rule), rule.derivation, constant = material.toHex())

            KeyDerivation.HMAC_SHA256_WITH_SALT ->
                derive(longTermKey(rule), rule.derivation, salt = material.toHex())
        }.toHex()
    }

    private fun longTermKey(rule: HandshakeRule): ByteArray {
        val hex = requireNotNull(normalizeHexKey(rule.keyHex)?.takeIf { it.isNotEmpty() }) {
            "${rule.derivation.name} derivation needs a long-term key"
        }
        return hexBytes(hex)
    }

    /** Uppercase whole-byte hex without separators, or null when [raw] is not hexadecimal bytes. */
    fun normalizeHexKey(raw: String?): String? {
        val text = raw ?: return null
        val stripped = text.trim().removePrefix("0x").removePrefix("0X")
        val compact = StringBuilder(stripped.length)
        for (character in stripped) {
            when (character) {
                ' ', ':', '-', '_', '\n', '\t', '\r' -> Unit
                in '0'..'9', in 'a'..'f', in 'A'..'F' -> compact.append(character.uppercaseChar())
                else -> return null
            }
        }
        return if (compact.length % 2 == 0) compact.toString() else null
    }

    /** [hex] must already have passed [normalizeHexKey]. */
    fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val KEY_SIZES = intArrayOf(16, 24, 32)
}

/** One side of a handshake: which captured frame, which bytes of it, and what to call it. */
@Serializable
data class HandshakePart(
    val label: String,
    val eventId: String,
    val bytes: ByteSource,
)

/**
 * A recipe for a session key built out of captured frames.
 *
 * @param derivation how the concatenated [parts] become a key; see [KeyTools.deriveFromHandshake].
 * @param keyHex the long-term secret, for the derivations that key on one.
 */
@Serializable
data class HandshakeRule(
    val parts: List<HandshakePart> = emptyList(),
    val derivation: KeyDerivation = KeyDerivation.RAW,
    val keyHex: String = "",
)

/**
 * Where a scheme's key comes from.
 *
 * Only [ManualKeyProvider] ships: the operator pastes the key they extracted themselves. The
 * interface exists so a cloud provider - Xiaomi's `micloud` token extractor, Tuya's IoT platform
 * API - can be added without touching the engine, the model or the UI. A provider is handed the
 * session (it holds the device address and the vendor app package) and returns hex key material
 * or a failure the UI can show. See `docs/decryption.md` for the seam.
 */
interface KeyProvider {
    /** Stable id stored on the scheme so the UI can show where a key came from. */
    val id: String

    val label: String

    /** One line the UI shows under [label]: what the operator has to have, and where from. */
    val description: String

    suspend fun keyFor(session: CaptureSession): Result<String>
}

/** The operator pasted the key. Nothing leaves the device. */
class ManualKeyProvider(private val keyHex: String) : KeyProvider {
    override val id = "manual"
    override val label = "Pasted by hand"
    override val description = "The key you extracted from the vendor app, cloud console or firmware dump"

    override suspend fun keyFor(session: CaptureSession): Result<String> =
        KeyTools.normalizeHexKey(keyHex)
            ?.takeIf { it.isNotEmpty() }
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("That key is not whole hexadecimal bytes"))
}
