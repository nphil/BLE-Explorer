package dev.nphil.blestudio.crypto

import dev.nphil.blestudio.model.AttOperation
import dev.nphil.blestudio.model.BleEvent
import dev.nphil.blestudio.model.ByteSource
import dev.nphil.blestudio.model.CipherPrimitive
import dev.nphil.blestudio.model.CipherScheme
import dev.nphil.blestudio.model.EventDirection
import dev.nphil.blestudio.model.EventSource
import dev.nphil.blestudio.model.KeyDerivation
import dev.nphil.blestudio.model.hexToBytes
import dev.nphil.blestudio.model.toHex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyToolsTest {

    // -- entry normalisation -------------------------------------------------------------------

    @Test
    fun `hex entry survives the separators keys get pasted with`() {
        val expected = "AABBCCDD"
        for (typed in listOf("AABBCCDD", "aabbccdd", "AA:BB:CC:DD", "AA BB CC DD", "0xaabbccdd", "aa-bb-cc-dd")) {
            assertEquals(typed, expected, KeyTools.normalize(typed, KeyEncoding.HEX))
        }
    }

    @Test
    fun `an odd number of nibbles is not a key`() {
        assertNull(KeyTools.normalize("AABBC", KeyEncoding.HEX))
        assertNull(KeyTools.normalize("nothex", KeyEncoding.HEX))
    }

    @Test
    fun `base64 entry decodes`() {
        assertEquals("000102030405060708090A0B0C0D0E0F", KeyTools.normalize("AAECAwQFBgcICQoLDA0ODw==", KeyEncoding.BASE64))
        assertNull(KeyTools.normalize("not base64!!", KeyEncoding.BASE64))
    }

    /** Tuya hands out a printable "local key"; its bytes are the UTF-8 of that text. */
    @Test
    fun `text entry becomes its utf-8 bytes`() {
        assertEquals("616263", KeyTools.normalize("abc", KeyEncoding.UTF8))
    }

    @Test
    fun `the guessed encoding prefers hex then base64 then text`() {
        assertEquals(KeyEncoding.HEX, KeyTools.guessEncoding("00112233445566778899AABBCCDDEEFF"))
        assertEquals(KeyEncoding.BASE64, KeyTools.guessEncoding("AAECAwQFBgcICQoLDA0ODw=="))
        assertEquals(KeyEncoding.UTF8, KeyTools.guessEncoding("my secret key"))
    }

    // -- derivations ---------------------------------------------------------------------------

    @Test
    fun `sha256 matches the published digest of abc`() {
        assertEquals(
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            KeyTools.derive("616263".hexToBytes(), KeyDerivation.SHA256).toHex(),
        )
    }

    @Test
    fun `md5 matches the published digest of abc`() {
        assertEquals(
            "900150983CD24FB0D6963F7D28E17F72",
            KeyTools.derive("616263".hexToBytes(), KeyDerivation.MD5).toHex(),
        )
    }

    /** RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?". */
    @Test
    fun `hmac sha256 matches RFC 4231 case 2`() {
        assertEquals(
            "5BDCC146BF60754E6A042426089575C75A003F089D2739839DEC58B964EC3843",
            KeyTools.derive(
                secret = "4A656665".hexToBytes(),
                derivation = KeyDerivation.HMAC_SHA256_WITH_SALT,
                salt = "7768617420646F2079612077616E7420666F72206E6F7468696E673F",
            ).toHex(),
        )
    }

    /** FIPS-197 appendix C.1, the AES-128 example. */
    @Test
    fun `aes ecb of a constant matches FIPS-197`() {
        assertEquals(
            "69C4E0D86A7B0430D8CDB78070B4C55A",
            KeyTools.derive(
                secret = "000102030405060708090A0B0C0D0E0F".hexToBytes(),
                derivation = KeyDerivation.AES_ECB_OF_CONSTANT,
                constant = "00112233445566778899AABBCCDDEEFF",
            ).toHex(),
        )
    }

    @Test
    fun `a derivation missing its salt or constant says which`() {
        val noSalt = runCatching {
            KeyTools.derive("00".hexToBytes(), KeyDerivation.HMAC_SHA256_WITH_SALT)
        }
        assertTrue(noSalt.exceptionOrNull()!!.message!!.contains("salt"))
        val noConstant = runCatching {
            KeyTools.derive("000102030405060708090A0B0C0D0E0F".hexToBytes(), KeyDerivation.AES_ECB_OF_CONSTANT)
        }
        assertTrue(noConstant.exceptionOrNull()!!.message!!.contains("constant"))
    }

    @Test
    fun `a scheme's key runs through its derivation`() {
        val scheme = CipherScheme(
            name = "Tuya-shaped",
            primitive = CipherPrimitive.AES_CBC,
            keyHex = "616263",
            keyDerivation = KeyDerivation.MD5,
        )
        assertEquals("900150983CD24FB0D6963F7D28E17F72", KeyTools.keyOf(scheme).toHex())
    }

    @Test
    fun `an empty key is refused with a readable message`() {
        val scheme = CipherScheme(name = "empty", primitive = CipherPrimitive.AES_ECB)
        assertTrue(runCatching { KeyTools.keyOf(scheme) }.exceptionOrNull()!!.message!!.contains("no key"))
    }

    // -- handshake derivation ------------------------------------------------------------------

    private fun event(id: String, payload: String) = BleEvent(
        id = id,
        timestampEpochMicros = 1_000,
        direction = EventDirection.DEVICE_TO_PHONE,
        source = EventSource.HCI_SNOOP,
        operation = AttOperation.NOTIFICATION,
        payloadHex = payload,
    )

    private val handshake = listOf(
        event("request", "0C" + "0001020304050607"),
        event("response", "01" + "1011121314151617" + "FFFF"),
    )

    @Test
    fun `a raw handshake rule concatenates the two frames' bytes`() {
        val rule = HandshakeRule(
            parts = listOf(
                HandshakePart("phone random", "request", ByteSource.FrameBytes(1, 8)),
                HandshakePart("device random", "response", ByteSource.FrameBytes(1, 8)),
            ),
        )
        assertEquals(
            "00010203040506071011121314151617",
            KeyTools.deriveFromHandshake(handshake, rule).getOrThrow(),
        )
    }

    /** Tuya's `session_key = MD5(local_key[:6] || srand)`, expressed without naming Tuya. */
    @Test
    fun `an md5 handshake rule digests the composed material`() {
        val rule = HandshakeRule(
            parts = listOf(
                HandshakePart("local key", "request", ByteSource.Constant("61")),
                HandshakePart("srand", "response", ByteSource.Constant("6263")),
            ),
            derivation = KeyDerivation.MD5,
        )
        assertEquals("900150983CD24FB0D6963F7D28E17F72", KeyTools.deriveFromHandshake(handshake, rule).getOrThrow())
    }

    /** The `session_key = AES(login_key, nonce)` shape. */
    @Test
    fun `an aes handshake rule encrypts the material under the long-term key`() {
        val rule = HandshakeRule(
            parts = listOf(
                HandshakePart("phone random", "request", ByteSource.FrameBytes(1, 8)),
                HandshakePart("device random", "response", ByteSource.Constant("8899AABBCCDDEEFF")),
            ),
            derivation = KeyDerivation.AES_ECB_OF_CONSTANT,
            keyHex = "000102030405060708090A0B0C0D0E0F",
        )
        // Material is 00010203040506070001020304050607 -> AES-128 under the FIPS key.
        val expected = KeyTools.derive(
            secret = "000102030405060708090A0B0C0D0E0F".hexToBytes(),
            derivation = KeyDerivation.AES_ECB_OF_CONSTANT,
            constant = "00010203040506078899AABBCCDDEEFF",
        ).toHex()
        assertEquals(expected, KeyTools.deriveFromHandshake(handshake, rule).getOrThrow())
    }

    @Test
    fun `a rule naming a frame that is gone fails with its label`() {
        val rule = HandshakeRule(
            parts = listOf(HandshakePart("device random", "missing", ByteSource.FrameBytes(0, 4))),
        )
        val failure = KeyTools.deriveFromHandshake(handshake, rule).exceptionOrNull()!!
        assertTrue(failure.message!!.contains("device random"))
    }

    @Test
    fun `a rule that selects nothing fails rather than producing an empty key`() {
        val rule = HandshakeRule(
            parts = listOf(HandshakePart("nothing", "request", ByteSource.FrameBytes(90, 4))),
        )
        assertTrue(KeyTools.deriveFromHandshake(handshake, rule).isFailure)
        assertTrue(KeyTools.deriveFromHandshake(handshake, HandshakeRule()).isFailure)
    }

    @Test
    fun `a keyed rule without a long-term key says so`() {
        val rule = HandshakeRule(
            parts = listOf(HandshakePart("material", "request", ByteSource.FrameBytes(1, 16))),
            derivation = KeyDerivation.AES_ECB_OF_CONSTANT,
        )
        assertTrue(
            KeyTools.deriveFromHandshake(handshake, rule).exceptionOrNull()!!
                .message!!.contains("long-term key"),
        )
    }

    // -- key provider seam ---------------------------------------------------------------------

    @Test
    fun `the manual provider normalises what the operator pasted`() = runTest {
        val session = dev.nphil.blestudio.model.CaptureSession(name = "s")
        assertEquals(
            "AABBCCDD",
            ManualKeyProvider("aa:bb:cc:dd").keyFor(session).getOrThrow(),
        )
        assertTrue(ManualKeyProvider("nonsense").keyFor(session).isFailure)
        assertTrue(ManualKeyProvider("").keyFor(session).isFailure)
    }
}
