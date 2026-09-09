package dev.nphil.blueshark.crypto

import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.CipherPrimitive
import dev.nphil.blueshark.model.CipherScheme
import dev.nphil.blueshark.model.EvidenceBundle
import dev.nphil.blueshark.model.KeyDerivation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactionTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val secretScheme = CipherScheme(
        id = "s1",
        name = "Xiaomi MiBeacon v4/v5",
        primitive = CipherPrimitive.AES_CCM,
        keyHex = "FDD8CE9C08AE7533A79BDAF0BB755E96",
        keyDerivation = KeyDerivation.HMAC_SHA256_WITH_SALT,
        keySaltHex = "DEADBEEF",
        keyConstantHex = "00112233445566778899AABBCCDDEEFF",
        tagLength = 4,
        notes = "Bindkey from the Mi Home cloud",
    )

    @Test
    fun `redaction removes every secret field and keeps the shape`() {
        val redacted = SecretRedaction.redact(secretScheme)
        assertEquals("", redacted.keyHex)
        assertNull(redacted.keySaltHex)
        assertNull(redacted.keyConstantHex)
        // The shape is evidence and has to survive, or the recipient cannot use their own key.
        assertEquals(secretScheme.primitive, redacted.primitive)
        assertEquals(secretScheme.keyDerivation, redacted.keyDerivation)
        assertEquals(secretScheme.tagLength, redacted.tagLength)
        assertEquals(secretScheme.nonce, redacted.nonce)
        assertEquals(secretScheme.ciphertextRange, redacted.ciphertextRange)
        assertEquals(secretScheme.match, redacted.match)
        assertEquals(secretScheme.notes, redacted.notes)
        assertEquals(secretScheme.id, redacted.id)
        assertEquals(secretScheme.name, redacted.name)
    }

    @Test
    fun `no key material reaches the encoded bundle`() {
        val session = CaptureSession(name = "capture", ciphers = listOf(secretScheme))
        val shared = json.encodeToString(
            EvidenceBundle.serializer(),
            EvidenceBundle(appVersion = "test", session = SecretRedaction.redact(session)),
        )
        assertFalse("keyHex leaked", shared.contains("FDD8CE9C08AE7533A79BDAF0BB755E96"))
        assertFalse("salt leaked", shared.contains("DEADBEEF"))
        assertFalse("constant leaked", shared.contains("00112233445566778899AABBCCDDEEFF"))
        // …while the scheme itself is still there for the recipient to fill in.
        assertTrue(shared.contains("AES_CCM"))
        assertTrue(shared.contains("Xiaomi MiBeacon v4/v5"))
    }

    @Test
    fun `an unredacted bundle still carries the key when the operator asks for it`() {
        val session = CaptureSession(name = "capture", ciphers = listOf(secretScheme))
        val shared = json.encodeToString(
            EvidenceBundle.serializer(),
            EvidenceBundle(appVersion = "test", session = session),
        )
        assertTrue(shared.contains("FDD8CE9C08AE7533A79BDAF0BB755E96"))
    }

    @Test
    fun `a session with nothing to strip is not copied`() {
        val keyless = CaptureSession(
            name = "capture",
            ciphers = listOf(CipherScheme(name = "template", primitive = CipherPrimitive.XOR)),
        )
        assertSame(keyless, SecretRedaction.redact(keyless))
        val bare = CaptureSession(name = "bare")
        assertSame(bare, SecretRedaction.redact(bare))
        assertEquals(0, SecretRedaction.secretCount(keyless))
    }

    @Test
    fun `the secret count is what the export warning shows`() {
        val session = CaptureSession(
            name = "capture",
            ciphers = listOf(
                secretScheme,
                CipherScheme(name = "no key yet", primitive = CipherPrimitive.XOR),
                CipherScheme(name = "keyed", primitive = CipherPrimitive.XOR, keyHex = "A5"),
            ),
        )
        assertEquals(2, SecretRedaction.secretCount(session))
        assertEquals(0, SecretRedaction.secretCount(SecretRedaction.redact(session)))
    }

    /** A round trip through the serialiser must not lose the scheme, only the secret. */
    @Test
    fun `a redacted bundle decodes back into a usable scheme`() {
        val session = CaptureSession(name = "capture", ciphers = listOf(secretScheme))
        val text = json.encodeToString(
            EvidenceBundle.serializer(),
            EvidenceBundle(appVersion = "test", session = SecretRedaction.redact(session)),
        )
        val decoded = json.decodeFromString(EvidenceBundle.serializer(), text).session
        assertEquals(1, decoded.ciphers.size)
        assertEquals(CipherPrimitive.AES_CCM, decoded.ciphers[0].primitive)
        assertEquals("", decoded.ciphers[0].keyHex)
        assertFalse(SecretRedaction.hasSecret(decoded.ciphers[0]))
    }
}
