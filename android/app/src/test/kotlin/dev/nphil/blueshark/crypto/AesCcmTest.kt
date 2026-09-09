package dev.nphil.blueshark.crypto

import dev.nphil.blueshark.model.hexToBytes
import dev.nphil.blueshark.model.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * RFC 3610 section 8 packet vectors.
 *
 * Every vector uses the same key, an 8-octet cleartext header as the AAD and an 8-octet MIC. The
 * expected bytes are quoted from the RFC, so this is the only test in the suite that proves the
 * CCM construction itself rather than this app's use of it.
 */
class AesCcmTest {
    private val key = "C0C1C2C3C4C5C6C7C8C9CACBCCCDCECF".hexToBytes()
    private val header = "0001020304050607".hexToBytes()

    @Test
    fun `packet vector 1 matches RFC 3610`() = assertVector(
        nonce = "00000003020100A0A1A2A3A4A5",
        plaintext = "08090A0B0C0D0E0F101112131415161718191A1B1C1D1E",
        ciphertext = "588C979A61C663D2F066D0C2C0F989806D5F6B61DAC384",
        mic = "17E8D12CFDF926E0",
    )

    @Test
    fun `packet vector 2 matches RFC 3610`() = assertVector(
        nonce = "00000004030201A0A1A2A3A4A5",
        plaintext = "08090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F",
        ciphertext = "72C91A36E135F8CF291CA894085C87E3CC15C439C9E43A3B",
        mic = "A091D56E10400916",
    )

    @Test
    fun `packet vector 3 matches RFC 3610`() = assertVector(
        nonce = "00000005040302A0A1A2A3A4A5",
        plaintext = "08090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20",
        ciphertext = "51B1E5F44A197D1DA46B0F8E2D282AE871E838BB64DA859657",
        mic = "4ADAA76FBD9FB0C5",
    )

    @Test
    fun `a flipped ciphertext bit fails the MIC`() {
        val nonce = "00000003020100A0A1A2A3A4A5".hexToBytes()
        val sealed = ("588C979A61C663D2F066D0C2C0F989806D5F6B61DAC384" + "17E8D12CFDF926E0").hexToBytes()
        sealed[3] = (sealed[3].toInt() xor 0x01).toByte()
        assertNull(AesCcm.decrypt(key, nonce, sealed, header, tagLength = 8))
    }

    @Test
    fun `a flipped AAD bit fails the MIC`() {
        val nonce = "00000003020100A0A1A2A3A4A5".hexToBytes()
        val sealed = ("588C979A61C663D2F066D0C2C0F989806D5F6B61DAC384" + "17E8D12CFDF926E0").hexToBytes()
        val tampered = header.copyOf().also { it[0] = 0x7F }
        assertNull(AesCcm.decrypt(key, nonce, sealed, tampered, tagLength = 8))
    }

    /** A 12-byte nonce is the L=3 length field, which MiBeacon and several vendor schemes use. */
    @Test
    fun `twelve byte nonce round trips`() {
        val nonce = "000102030405060708090A0B".hexToBytes()
        val plaintext = "DEADBEEFCAFE".hexToBytes()
        val sealed = AesCcm.encrypt(key, nonce, plaintext, header, tagLength = 4)
        assertEquals(plaintext.size + 4, sealed.size)
        assertEquals(plaintext.toHex(), AesCcm.decrypt(key, nonce, sealed, header, 4)?.toHex())
    }

    @Test
    fun `every legal tag length round trips`() {
        val nonce = "00000003020100A0A1A2A3A4A5".hexToBytes()
        val plaintext = "0102030405".hexToBytes()
        for (tagLength in 4..16 step 2) {
            val sealed = AesCcm.encrypt(key, nonce, plaintext, header, tagLength)
            assertEquals(
                "tag length $tagLength",
                plaintext.toHex(),
                AesCcm.decrypt(key, nonce, sealed, header, tagLength)?.toHex(),
            )
        }
    }

    @Test
    fun `an empty message is still authenticated`() {
        val nonce = "00000003020100A0A1A2A3A4A5".hexToBytes()
        val sealed = AesCcm.encrypt(key, nonce, ByteArray(0), header, tagLength = 8)
        assertEquals(8, sealed.size)
        assertEquals("", AesCcm.decrypt(key, nonce, sealed, header, 8)?.toHex())
        sealed[0] = (sealed[0].toInt() xor 0x40).toByte()
        assertNull(AesCcm.decrypt(key, nonce, sealed, header, 8))
    }

    @Test
    fun `absent AAD is not the same as empty AAD in the flags byte`() {
        val nonce = "00000003020100A0A1A2A3A4A5".hexToBytes()
        val plaintext = "0102030405".hexToBytes()
        val withHeader = AesCcm.encrypt(key, nonce, plaintext, header, 8)
        val withoutHeader = AesCcm.encrypt(key, nonce, plaintext, ByteArray(0), 8)
        assertNotEquals(withHeader.toHex(), withoutHeader.toHex())
        assertNull(AesCcm.decrypt(key, nonce, withHeader, ByteArray(0), 8))
    }

    /** The tag may live elsewhere in the frame; passing it separately must give the same answer. */
    @Test
    fun `a detached tag verifies`() {
        val nonce = "00000003020100A0A1A2A3A4A5".hexToBytes()
        val body = "588C979A61C663D2F066D0C2C0F989806D5F6B61DAC384".hexToBytes()
        val tag = "17E8D12CFDF926E0".hexToBytes()
        assertEquals(
            "08090A0B0C0D0E0F101112131415161718191A1B1C1D1E",
            AesCcm.decrypt(key, nonce, body, header, 8, tag)?.toHex(),
        )
    }

    @Test
    fun `a nonce outside seven to thirteen bytes is rejected`() {
        val plaintext = "0102".hexToBytes()
        assertThrows(AesCcm.CcmException::class.java) {
            AesCcm.encrypt(key, ByteArray(6), plaintext, tagLength = 8)
        }
        assertThrows(AesCcm.CcmException::class.java) {
            AesCcm.encrypt(key, ByteArray(14), plaintext, tagLength = 8)
        }
    }

    @Test
    fun `an odd tag length is rejected`() {
        assertThrows(AesCcm.CcmException::class.java) {
            AesCcm.encrypt(key, ByteArray(13), "0102".hexToBytes(), tagLength = 5)
        }
    }

    @Test
    fun `a frame shorter than its MIC is rejected`() {
        assertThrows(AesCcm.CcmException::class.java) {
            AesCcm.decrypt(key, ByteArray(13), "0102".hexToBytes(), tagLength = 8)
        }
    }

    /** A message longer than the length field can express must not silently wrap. */
    @Test
    fun `a message too long for the length field is rejected`() {
        assertThrows(AesCcm.CcmException::class.java) {
            AesCcm.encrypt(key, ByteArray(13), ByteArray(65_536), tagLength = 8)
        }
    }

    private fun assertVector(nonce: String, plaintext: String, ciphertext: String, mic: String) {
        val nonceBytes = nonce.hexToBytes()
        val expected = ciphertext + mic
        val sealed = AesCcm.encrypt(key, nonceBytes, plaintext.hexToBytes(), header, tagLength = 8)
        assertEquals(expected, sealed.toHex())
        assertEquals(plaintext, AesCcm.decrypt(key, nonceBytes, expected.hexToBytes(), header, 8)?.toHex())
    }
}
