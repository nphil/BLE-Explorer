package dev.nphil.blestudio.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CCM per RFC 3610, built on AES/ECB/NoPadding.
 *
 * `javax.crypto` on Android exposes no `AES/CCM/NoPadding` transform and Bouncy Castle is not a
 * dependency, so CCM is implemented here. All of CCM is a CBC-MAC plus CTR over the same block
 * cipher, so one raw ECB permutation suffices: [Ecb] is the only cipher call site, and both the
 * MIC chain and the keystream come from it.
 *
 * The nonce length fixes the length field: RFC 3610 requires `15 - nonceLength` bytes for the
 * message length, so a 13-byte nonce gives L=2 (messages < 64 KiB) and a 12-byte nonce gives
 * L=3. Both occur in the wild - MiBeacon uses 13, several vendor schemes use 12.
 */
object AesCcm {

    class CcmException(message: String) : IllegalArgumentException(message)

    private val EMPTY = ByteArray(0)

    /** @return ciphertext followed by the [tagLength]-byte MIC. */
    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = EMPTY,
        tagLength: Int = 8,
    ): ByteArray {
        validate(key, nonce, tagLength, plaintext.size)
        val ecb = Ecb(key)
        val mac = cbcMac(ecb, nonce, plaintext, aad, tagLength)
        val out = ByteArray(plaintext.size + tagLength)
        applyKeystream(ecb, nonce, plaintext, out)
        val s0 = keystreamBlock(ecb, nonce, 0)
        for (index in 0 until tagLength) {
            out[plaintext.size + index] = (mac[index].toInt() xor s0[index].toInt()).toByte()
        }
        return out
    }

    /**
     * @param ciphertext the ciphertext with the MIC appended, unless [tag] carries it separately -
     *   some frames put the MIC in a header rather than after the payload.
     * @return the plaintext, or null when the MIC does not match.
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray = EMPTY,
        tagLength: Int = 8,
        tag: ByteArray? = null,
    ): ByteArray? {
        val body: ByteArray
        val mic: ByteArray
        if (tag == null) {
            if (ciphertext.size < tagLength) throw CcmException("Frame is shorter than its $tagLength-byte MIC")
            body = ciphertext.copyOfRange(0, ciphertext.size - tagLength)
            mic = ciphertext.copyOfRange(ciphertext.size - tagLength, ciphertext.size)
        } else {
            if (tag.size != tagLength) throw CcmException("Tag is ${tag.size} bytes, expected $tagLength")
            body = ciphertext
            mic = tag
        }
        validate(key, nonce, tagLength, body.size)
        val ecb = Ecb(key)
        val plaintext = ByteArray(body.size)
        applyKeystream(ecb, nonce, body, plaintext)
        val expected = cbcMac(ecb, nonce, plaintext, aad, tagLength)
        val s0 = keystreamBlock(ecb, nonce, 0)
        var difference = 0
        for (index in 0 until tagLength) {
            difference = difference or (expected[index].toInt() xor s0[index].toInt() xor mic[index].toInt())
        }
        return if (difference == 0) plaintext else null
    }

    private fun validate(key: ByteArray, nonce: ByteArray, tagLength: Int, messageSize: Int) {
        if (key.size != 16 && key.size != 24 && key.size != 32) {
            throw CcmException("AES needs a 16, 24 or 32 byte key, got ${key.size}")
        }
        if (nonce.size < 7 || nonce.size > 13) throw CcmException("CCM nonce must be 7..13 bytes, got ${nonce.size}")
        if (tagLength < 4 || tagLength > 16 || tagLength % 2 != 0) {
            throw CcmException("CCM tag must be an even 4..16 bytes, got $tagLength")
        }
        val lengthField = 15 - nonce.size
        if (lengthField < 4 && messageSize.toLong() >= 1L shl (8 * lengthField)) {
            throw CcmException("A $messageSize byte message does not fit a $lengthField byte length field")
        }
    }

    /** T = the CBC-MAC over B_0 .. B_n of RFC 3610 section 2.2, truncated by the caller. */
    private fun cbcMac(
        ecb: Ecb,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
        tagLength: Int,
    ): ByteArray {
        val lengthField = 15 - nonce.size
        val chain = MacChain(ecb)
        // B_0: flags = Adata | ((M-2)/2) << 3 | (L-1), then the nonce, then l(m).
        chain.state[0] = (
            (if (aad.isNotEmpty()) 0x40 else 0) or
                (((tagLength - 2) / 2) shl 3) or
                (lengthField - 1)
            ).toByte()
        nonce.copyInto(chain.state, 1)
        var remaining = plaintext.size
        for (index in 0 until lengthField) {
            chain.state[15 - index] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
        chain.seal()
        if (aad.isNotEmpty()) {
            // A BLE frame cannot carry 0xFF00 bytes of AAD, so only the short l(a) encoding is
            // reachable; the long one is still written so the code is not quietly wrong.
            if (aad.size < 0xFF00) {
                chain.absorb((aad.size ushr 8).toByte())
                chain.absorb((aad.size and 0xFF).toByte())
            } else {
                chain.absorb(0xFF.toByte())
                chain.absorb(0xFE.toByte())
                for (shift in intArrayOf(24, 16, 8, 0)) chain.absorb((aad.size ushr shift and 0xFF).toByte())
            }
            chain.absorb(aad)
            chain.seal()
        }
        chain.absorb(plaintext)
        chain.seal()
        return chain.state
    }

    /** CTR over A_1.. , which is CCM encryption and CCM decryption alike. */
    private fun applyKeystream(ecb: Ecb, nonce: ByteArray, input: ByteArray, output: ByteArray) {
        var counter = 1
        var position = 0
        while (position < input.size) {
            val keystream = keystreamBlock(ecb, nonce, counter)
            val span = minOf(16, input.size - position)
            for (index in 0 until span) {
                output[position + index] = (input[position + index].toInt() xor keystream[index].toInt()).toByte()
            }
            position += span
            counter++
        }
    }

    /** S_i = E(K, A_i); the returned buffer is [Ecb]'s scratch and is valid until the next call. */
    private fun keystreamBlock(ecb: Ecb, nonce: ByteArray, counter: Int): ByteArray {
        val lengthField = 15 - nonce.size
        val block = ecb.scratch
        block[0] = (lengthField - 1).toByte()
        nonce.copyInto(block, 1)
        var value = counter
        for (index in 0 until lengthField) {
            block[15 - index] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        ecb.encryptInto(block, ecb.keystream)
        return ecb.keystream
    }

    /**
     * One AES key, one `Cipher`, two scratch blocks.
     *
     * A 20-byte BLE frame needs four permutations; allocating a `Cipher` and a fresh output array
     * per permutation would dominate the cost of decrypting a whole timeline.
     */
    private class Ecb(key: ByteArray) {
        private val cipher: Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }

        /** A_i is rebuilt in place per block: every byte of it is overwritten each time. */
        val scratch = ByteArray(16)
        val keystream = ByteArray(16)

        fun encryptInPlace(target: ByteArray) = cipher.doFinal(target, 0, 16, target, 0)

        fun encryptInto(source: ByteArray, destination: ByteArray) =
            cipher.doFinal(source, 0, 16, destination, 0)
    }

    /**
     * The CBC-MAC accumulator: bytes XOR into the running block, which is permuted whenever it
     * fills. [seal] zero-pads and closes a block group, which is what separates B_0, the AAD
     * blocks and the message blocks.
     */
    private class MacChain(private val ecb: Ecb) {
        val state = ByteArray(16)
        private var filled = 0

        fun absorb(byte: Byte) {
            state[filled] = (state[filled].toInt() xor byte.toInt()).toByte()
            if (++filled == 16) {
                ecb.encryptInPlace(state)
                filled = 0
            }
        }

        fun absorb(bytes: ByteArray) {
            for (byte in bytes) absorb(byte)
        }

        fun seal() {
            if (filled != 0 || state.isEmpty()) Unit
            if (filled != 0) {
                ecb.encryptInPlace(state)
                filled = 0
            } else if (sealedNothing) {
                ecb.encryptInPlace(state)
            }
            sealedNothing = false
        }

        /** B_0 is a full block written straight into [state]; the first [seal] must permute it. */
        private var sealedNothing = true
    }
}
