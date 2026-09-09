package dev.nphil.blueshark.crypto

import dev.nphil.blueshark.model.AttOperation
import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.ByteRange
import dev.nphil.blueshark.model.ByteSource
import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.CipherByteOrder
import dev.nphil.blueshark.model.CipherPrimitive
import dev.nphil.blueshark.model.CipherScheme
import dev.nphil.blueshark.model.DeviceIdentity
import dev.nphil.blueshark.model.EventDirection
import dev.nphil.blueshark.model.EventSource
import dev.nphil.blueshark.model.FrameMatch
import dev.nphil.blueshark.model.hexToBytes
import dev.nphil.blueshark.model.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CipherEngineTest {

    private val key16 = "000102030405060708090A0B0C0D0E0F"
    private val key32 = "000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F"
    private val address = "AA:BB:CC:DD:EE:FF"

    // -- ByteSource composition ---------------------------------------------------------------

    private val frame = "00112233445566778899AABBCCDDEEFF".hexToBytes()

    @Test
    fun `a frame slice reads from the front`() {
        assertEquals("001122", resolve(ByteSource.FrameBytes(0, 3)))
    }

    @Test
    fun `a negative offset reads from the end`() {
        assertEquals("DDEEFF", resolve(ByteSource.FrameBytes(-3, 3)))
    }

    @Test
    fun `length of minus one runs to the end`() {
        assertEquals("CCDDEEFF", resolve(ByteSource.FrameBytes(12, -1)))
    }

    @Test
    fun `an out of range slice clamps instead of throwing`() {
        assertEquals("EEFF", resolve(ByteSource.FrameBytes(14, 99)))
        assertEquals("", resolve(ByteSource.FrameBytes(64, 4)))
    }

    @Test
    fun `a composite concatenates in order`() {
        val source = ByteSource.Composite(
            listOf(
                ByteSource.Constant("AB"),
                ByteSource.FrameBytes(0, 2),
                ByteSource.Constant("CD"),
            ),
        )
        assertEquals("AB0011CD", resolve(source))
    }

    @Test
    fun `a slice narrows another source`() {
        assertEquals("FFEEDD", resolve(ByteSource.Slice(ByteSource.MacAddressReversed, 0, 3)))
        assertEquals("CCDD", resolve(ByteSource.Slice(ByteSource.MacAddress, 2, 2)))
    }

    @Test
    fun `the address resolves in both directions`() {
        assertEquals("AABBCCDDEEFF", resolve(ByteSource.MacAddress))
        assertEquals("FFEEDDCCBBAA", resolve(ByteSource.MacAddressReversed))
    }

    @Test
    fun `an address without separators resolves the same`() {
        assertEquals(
            "AABBCCDDEEFF",
            CipherEngine.resolve(ByteSource.MacAddress, frame, FrameContext(address = "aabbccddeeff")).toHex(),
        )
    }

    @Test
    fun `counter width and endianness are honoured`() {
        val context = FrameContext(address = address, counter = 0x1234)
        assertEquals(
            "34120000",
            CipherEngine.resolve(ByteSource.Counter(4, littleEndian = true), frame, context).toHex(),
        )
        assertEquals(
            "00001234",
            CipherEngine.resolve(ByteSource.Counter(4, littleEndian = false), frame, context).toHex(),
        )
        assertEquals("34", CipherEngine.resolve(ByteSource.Counter(1, true), frame, context).toHex())
        assertEquals("3412", CipherEngine.resolve(ByteSource.Counter(2, true), frame, context).toHex())
        assertEquals("1234", CipherEngine.resolve(ByteSource.Counter(2, false), frame, context).toHex())
    }

    /** A counter with no opinion of its own follows the scheme, so one switch flips them all. */
    @Test
    fun `a counter with no endianness inherits the scheme`() {
        val context = FrameContext(counter = 0x1234)
        assertEquals(
            "3412",
            CipherEngine.resolve(ByteSource.Counter(2), frame, context, littleEndianDefault = true).toHex(),
        )
        assertEquals(
            "1234",
            CipherEngine.resolve(ByteSource.Counter(2), frame, context, littleEndianDefault = false).toHex(),
        )
    }

    @Test
    fun `a range can drop trailing bytes`() {
        assertEquals(
            "44556677",
            CipherEngine.slice(frame, ByteRange(offset = 4, length = -1, dropFromEnd = 8)).toHex(),
        )
    }

    // -- FrameMatch ---------------------------------------------------------------------------

    @Test
    fun `an empty matcher claims any non-empty frame`() {
        val scheme = scheme(CipherPrimitive.XOR)
        assertTrue(CipherEngine.matches(scheme, frame, FrameContext()))
        assertFalse(CipherEngine.matches(scheme, ByteArray(0), FrameContext()))
        assertFalse(CipherEngine.matches(scheme, event("empty", "", characteristic = null)))
    }

    @Test
    fun `minLength rejects short frames`() {
        val scheme = scheme(CipherPrimitive.XOR, match = FrameMatch(minLength = 17))
        assertFalse(CipherEngine.matches(scheme, frame, FrameContext()))
        assertTrue(CipherEngine.matches(scheme, ByteArray(17), FrameContext()))
    }

    @Test
    fun `direction filters both ways`() {
        val scheme = scheme(
            CipherPrimitive.XOR,
            match = FrameMatch(direction = EventDirection.DEVICE_TO_PHONE),
        )
        assertTrue(
            CipherEngine.matches(scheme, frame, FrameContext(direction = EventDirection.DEVICE_TO_PHONE)),
        )
        assertFalse(
            CipherEngine.matches(scheme, frame, FrameContext(direction = EventDirection.PHONE_TO_DEVICE)),
        )
        assertFalse(CipherEngine.matches(scheme, frame, FrameContext()))
    }

    /** Live GATT stores 128-bit UUIDs and the relay stores the 16-bit form; both must match. */
    @Test
    fun `a sixteen bit characteristic matches its long form`() {
        val scheme = scheme(CipherPrimitive.XOR, match = FrameMatch(characteristicUuid = "FFF1"))
        assertTrue(
            CipherEngine.matches(
                scheme,
                frame,
                FrameContext(characteristicUuid = "0000fff1-0000-1000-8000-00805f9b34fb"),
            ),
        )
        assertFalse(
            CipherEngine.matches(
                scheme,
                frame,
                FrameContext(characteristicUuid = "0000fff2-0000-1000-8000-00805f9b34fb"),
            ),
        )
        assertFalse(CipherEngine.matches(scheme, frame, FrameContext()))
    }

    @Test
    fun `a payload prefix filters on the bytes`() {
        val scheme = scheme(CipherPrimitive.XOR, match = FrameMatch(payloadPrefixHex = "0011"))
        assertTrue(CipherEngine.matches(scheme, frame, FrameContext()))
        assertTrue(CipherEngine.matches(scheme, event("hex", frame.toHex(), characteristic = null)))
        assertFalse(CipherEngine.matches(scheme, "00FF2233".hexToBytes(), FrameContext()))
        assertFalse(CipherEngine.matches(scheme, event("no", "00FF2233", characteristic = null)))
        assertFalse(CipherEngine.matches(scheme, "00".hexToBytes(), FrameContext()))
    }

    /** The hex-string and byte-array matchers exist for speed; they must never disagree. */
    @Test
    fun `both matcher overloads agree`() {
        val scheme = scheme(
            CipherPrimitive.XOR,
            match = FrameMatch(payloadPrefixHex = "00 11 22", minLength = 4),
        )
        val samples = listOf("00112233", "001122", "0011", "00112299FF", "FF112233", "")
        for (sample in samples) {
            assertEquals(
                sample,
                CipherEngine.matches(scheme, event(sample, sample, characteristic = null)),
                CipherEngine.matches(scheme, sample.hexToBytes(), FrameContext()),
            )
        }
    }

    @Test
    fun `a prefix that is not hexadecimal claims nothing`() {
        val scheme = scheme(CipherPrimitive.XOR, match = FrameMatch(payloadPrefixHex = "zz"))
        assertFalse(CipherEngine.matches(scheme, frame, FrameContext()))
        assertFalse(CipherEngine.matches(scheme, event("x", frame.toHex(), characteristic = null)))
    }

    // -- Primitives ---------------------------------------------------------------------------

    @Test
    fun `aes ecb round trips`() {
        val plaintext = "00112233445566778899AABBCCDDEEFF"
        val ciphertext = javaxEncrypt("AES/ECB/NoPadding", key16, null, plaintext)
        val result = decrypt(scheme(CipherPrimitive.AES_ECB), ciphertext)
        assertEquals(plaintext, result.plaintextHex)
        assertNull("ECB proves nothing about the key", result.verified)
    }

    @Test
    fun `aes cbc round trips with the iv taken from the frame`() {
        val iv = "0F0E0D0C0B0A09080706050403020100"
        val plaintext = "00112233445566778899AABBCCDDEEFF"
        val ciphertext = javaxEncrypt("AES/CBC/NoPadding", key16, iv, plaintext)
        val scheme = scheme(
            CipherPrimitive.AES_CBC,
            nonce = ByteSource.FrameBytes(0, 16),
            range = ByteRange(offset = 16, length = -1),
        )
        val result = decrypt(scheme, iv + ciphertext)
        assertEquals(plaintext, result.plaintextHex)
        assertEquals(iv, result.nonceHex)
    }

    @Test
    fun `aes cbc strips pkcs5 padding when asked`() {
        val iv = "0F0E0D0C0B0A09080706050403020100"
        val plaintext = "4142434445"
        val ciphertext = javaxEncrypt("AES/CBC/PKCS5Padding", key16, iv, plaintext)
        val scheme = scheme(
            CipherPrimitive.AES_CBC,
            nonce = ByteSource.Constant(iv),
            padded = true,
        )
        assertEquals(plaintext, decrypt(scheme, ciphertext).plaintextHex)
    }

    @Test
    fun `aes ctr round trips a payload that is not a whole block`() {
        val nonce = "000102030405060708090A0B0C0D0E0F"
        val plaintext = "48656C6C6F2C20424C4521"
        val ciphertext = javaxEncrypt("AES/CTR/NoPadding", key16, nonce, plaintext)
        val scheme = scheme(CipherPrimitive.AES_CTR, nonce = ByteSource.Constant(nonce))
        val result = decrypt(scheme, ciphertext)
        assertEquals(plaintext, result.plaintextHex)
        assertNull("CTR is unauthenticated", result.verified)
    }

    /** A short nonce is the high bytes of the counter block, so the counter starts at zero. */
    @Test
    fun `a short ctr nonce is right padded`() {
        val nonce = "000102030405060708090A0B"
        val plaintext = "48656C6C6F"
        val ciphertext = javaxEncrypt("AES/CTR/NoPadding", key16, nonce + "00000000", plaintext)
        val scheme = scheme(CipherPrimitive.AES_CTR, nonce = ByteSource.Constant(nonce))
        assertEquals(plaintext, decrypt(scheme, ciphertext).plaintextHex)
    }

    @Test
    fun `aes gcm verifies its tag`() {
        val nonce = "000102030405060708090A0B"
        val plaintext = "48656C6C6F2C20424C4521"
        val sealed = javaxGcmSeal(key16, nonce, plaintext, "11")
        val scheme = scheme(
            CipherPrimitive.AES_GCM,
            nonce = ByteSource.Constant(nonce),
            aad = ByteSource.Constant("11"),
            tagLength = 16,
        )
        val result = decrypt(scheme, sealed)
        assertEquals(plaintext, result.plaintextHex)
        assertEquals(true, result.verified)
        assertEquals("11", result.aadHex)
    }

    @Test
    fun `aes gcm reports a wrong key rather than plausible bytes`() {
        val nonce = "000102030405060708090A0B"
        val sealed = javaxGcmSeal(key16, nonce, "48656C6C6F", null)
        val scheme = scheme(
            CipherPrimitive.AES_GCM,
            key = "FF0102030405060708090A0B0C0D0E0F",
            nonce = ByteSource.Constant(nonce),
            tagLength = 16,
        )
        val result = decrypt(scheme, sealed)
        assertNull(result.plaintextHex)
        assertEquals(false, result.verified)
        assertNotNull(result.error)
    }

    @Test
    fun `aes ccm verifies its tag through the engine`() {
        val nonce = "000102030405060708090A0B0C"
        val plaintext = "48656C6C6F2C20424C4521"
        val sealed = AesCcm.encrypt(
            key = key16.hexToBytes(),
            nonce = nonce.hexToBytes(),
            plaintext = plaintext.hexToBytes(),
            aad = "11".hexToBytes(),
            tagLength = 8,
        ).toHex()
        val scheme = scheme(
            CipherPrimitive.AES_CCM,
            nonce = ByteSource.Constant(nonce),
            aad = ByteSource.Constant("11"),
            tagLength = 8,
        )
        val result = decrypt(scheme, sealed)
        assertEquals(plaintext, result.plaintextHex)
        assertEquals(true, result.verified)
    }

    @Test
    fun `chacha20 poly1305 verifies its tag`() {
        val nonce = "000102030405060708090A0B"
        val plaintext = "48656C6C6F2C20424C4521"
        val cipher = Cipher.getInstance("ChaCha20-Poly1305").apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key32.hexToBytes(), "ChaCha20"),
                IvParameterSpec(nonce.hexToBytes()),
            )
            updateAAD("11".hexToBytes())
        }
        val sealed = cipher.doFinal(plaintext.hexToBytes()).toHex()
        val scheme = scheme(
            CipherPrimitive.CHACHA20_POLY1305,
            key = key32,
            nonce = ByteSource.Constant(nonce),
            aad = ByteSource.Constant("11"),
        )
        val result = decrypt(scheme, sealed)
        assertEquals(plaintext, result.plaintextHex)
        assertEquals(true, result.verified)
    }

    @Test
    fun `xor repeats its key over the payload`() {
        val scheme = scheme(CipherPrimitive.XOR, key = "A5")
        assertEquals("A5A4A7A6", decrypt(scheme, "00010203").plaintextHex)
        val wide = scheme(CipherPrimitive.XOR, key = "0102")
        assertEquals("01030301", decrypt(wide, "00010203").plaintextHex)
    }

    /**
     * The Telink family feeds the block cipher byte-reversed. Encrypting with the same reversed
     * convention by hand and decrypting through the engine is the only check available: no
     * primary source publishes a vector.
     */
    @Test
    fun `reversed block order round trips a keystream`() {
        val nonce = "00AABBCCDD01000102000000000000"
        val nonceBlock = (nonce + "00").hexToBytes()
        val reversedKey = key16.hexToBytes().reversedArray()
        val keystream = Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(reversedKey, "AES"))
            doFinal(nonceBlock.reversedArray()).reversedArray()
        }
        val plaintext = "000102030405060708090A0B0C0D0E".hexToBytes()
        val ciphertext = ByteArray(plaintext.size) { (plaintext[it].toInt() xor keystream[it].toInt()).toByte() }
        val scheme = scheme(
            CipherPrimitive.AES_CTR,
            nonce = ByteSource.Constant(nonce + "00"),
            byteOrder = CipherByteOrder.REVERSED_BLOCKS,
        )
        assertEquals(plaintext.toHex(), decrypt(scheme, ciphertext.toHex()).plaintextHex)
    }

    @Test
    fun `reversed block order is refused for the aead primitives`() {
        val scheme = scheme(
            CipherPrimitive.AES_GCM,
            nonce = ByteSource.Constant("000102030405060708090A0B"),
            byteOrder = CipherByteOrder.REVERSED_BLOCKS,
            tagLength = 16,
        )
        val result = decrypt(scheme, "00".repeat(32))
        assertNull(result.plaintextHex)
        assertTrue(result.error!!.contains("Reversed block order"))
    }

    // -- Failure reporting ---------------------------------------------------------------------

    @Test
    fun `a missing key is reported not thrown`() {
        val result = decrypt(scheme(CipherPrimitive.AES_ECB, key = ""), "00".repeat(16))
        assertNull(result.plaintextHex)
        assertTrue(result.error!!.contains("no key"))
    }

    @Test
    fun `a ragged block length is reported`() {
        val result = decrypt(scheme(CipherPrimitive.AES_ECB), "0011223344")
        assertNull(result.plaintextHex)
        assertTrue(result.error!!.contains("16-byte blocks"))
    }

    @Test
    fun `an empty ciphertext range is reported`() {
        val scheme = scheme(CipherPrimitive.XOR, range = ByteRange(offset = 40, length = 4))
        val result = decrypt(scheme, "00112233")
        assertNull(result.plaintextHex)
        assertTrue(result.error!!.contains("selected no bytes"))
    }

    @Test
    fun `a nonce needing an address the session lacks is reported`() {
        val scheme = scheme(CipherPrimitive.AES_CTR, nonce = ByteSource.MacAddressReversed)
        val result = CipherEngine.decrypt(scheme, "00112233".hexToBytes(), FrameContext(address = ""))
        assertNull(result.plaintextHex)
        assertNotNull(result.error)
    }

    // -- SchemeApplier and the cache -----------------------------------------------------------

    private fun event(
        id: String,
        payload: String,
        direction: EventDirection = EventDirection.DEVICE_TO_PHONE,
        characteristic: String? = "0000fff1-0000-1000-8000-00805f9b34fb",
    ) = BleEvent(
        id = id,
        timestampEpochMicros = 1_000L * id.hashCode().toLong().coerceAtLeast(1),
        direction = direction,
        source = EventSource.HCI_SNOOP,
        operation = AttOperation.NOTIFICATION,
        characteristicUuid = characteristic,
        payloadHex = payload,
    )

    @Test
    fun `the session counter advances only on matched frames`() {
        val counted = scheme(
            CipherPrimitive.XOR,
            key = "00",
            nonce = ByteSource.Counter(2, littleEndian = true),
            match = FrameMatch(payloadPrefixHex = "AA"),
        )
        val events = listOf(
            event("a", "AA00"),
            event("b", "BB00"),
            event("c", "AA01"),
            event("d", "AA02"),
        )
        val session = session(listOf(counted), events)
        val index = SchemeApplier.index(session, session.ciphers, events)
        assertEquals(setOf("a", "c", "d"), index.keys)
        assertEquals(0, index["a"]!!.context.counter)
        assertEquals(1, index["c"]!!.context.counter)
        assertEquals(2, index["d"]!!.context.counter)
    }

    @Test
    fun `a disabled scheme claims nothing`() {
        val events = listOf(event("a", "AA00"))
        val session = session(listOf(scheme(CipherPrimitive.XOR, key = "FF").copy(enabled = false)), events)
        assertTrue(SchemeApplier.index(session, session.ciphers, events).isEmpty())
        assertTrue(DecryptCache(session, session.ciphers, events).empty)
    }

    @Test
    fun `the first enabled matching scheme wins`() {
        val first = scheme(CipherPrimitive.XOR, key = "01").copy(id = "first", name = "first")
        val second = scheme(CipherPrimitive.XOR, key = "02").copy(id = "second", name = "second")
        val events = listOf(event("a", "00"))
        val session = session(listOf(first, second), events)
        val applied = SchemeApplier.apply(session, session.ciphers, events)
        assertEquals("first", applied["a"]!!.schemeName)
        assertEquals("01", applied["a"]!!.result.plaintextHex)
    }

    @Test
    fun `the cache falls back to the ciphertext for unclaimed frames`() {
        val onlyAa = scheme(CipherPrimitive.XOR, key = "FF", match = FrameMatch(payloadPrefixHex = "AA"))
        val events = listOf(event("a", "AA55"), event("b", "BB55"))
        val session = session(listOf(onlyAa), events)
        val cache = DecryptCache(session, session.ciphers, events)
        assertEquals("55AA", cache.payloadFor(events[0]))
        assertEquals("BB55", cache.payloadFor(events[1]))
        assertNull(cache.frameFor(events[1]))
        assertEquals(1, cache.matchedCount)
        assertEquals(1, cache.matchCountOf(onlyAa.id))
    }

    /** Scrolling a timeline must not re-run the cipher for a row already computed. */
    @Test
    fun `the cache returns the same result object twice`() {
        val events = listOf(event("a", "0011"))
        val session = session(listOf(scheme(CipherPrimitive.XOR, key = "FF")), events)
        val cache = DecryptCache(session, session.ciphers, events)
        assertSame(cache.frameFor(events[0]), cache.frameFor(events[0]))
    }

    @Test
    fun `the cache lists a scheme's matches for the try it panel`() {
        val scheme = scheme(CipherPrimitive.XOR, key = "FF", match = FrameMatch(payloadPrefixHex = "AA"))
        val events = listOf(event("a", "BB00"), event("b", "AA01"), event("c", "AA02"))
        val session = session(listOf(scheme), events)
        val cache = DecryptCache(session, session.ciphers, events)
        assertEquals("b", cache.firstMatch(scheme.id)?.id)
        assertEquals(listOf("b", "c"), cache.matchesOf(scheme.id).map { it.id })
        assertEquals(scheme.id, cache.schemeFor("b")?.id)
        assertNull(cache.schemeFor("a"))
    }

    /** The editor previews a scheme against any frame, matched or not. */
    @Test
    fun `preview decrypts a frame the scheme does not claim`() {
        val scheme = scheme(CipherPrimitive.XOR, key = "FF", match = FrameMatch(payloadPrefixHex = "AA"))
        val events = listOf(event("a", "BB00"))
        val session = session(listOf(scheme), events)
        val cache = DecryptCache(session, session.ciphers, events)
        assertEquals("44FF", cache.preview(scheme, events[0], session.device.address).plaintextHex)
    }

    // -- helpers ------------------------------------------------------------------------------

    private fun resolve(source: ByteSource) =
        CipherEngine.resolve(source, frame, FrameContext(address = address)).toHex()

    private fun scheme(
        primitive: CipherPrimitive,
        key: String = key16,
        nonce: ByteSource = ByteSource.Constant(""),
        aad: ByteSource? = null,
        tagLength: Int? = null,
        range: ByteRange = ByteRange(),
        match: FrameMatch = FrameMatch(),
        padded: Boolean = false,
        byteOrder: CipherByteOrder = CipherByteOrder.NATURAL,
    ) = CipherScheme(
        name = primitive.name,
        primitive = primitive,
        keyHex = key,
        nonce = nonce,
        aad = aad,
        tagLength = tagLength,
        ciphertextRange = range,
        match = match,
        padded = padded,
        byteOrder = byteOrder,
    )

    private fun session(schemes: List<CipherScheme>, events: List<BleEvent>) = CaptureSession(
        name = "test",
        device = DeviceIdentity(address = address),
        events = events,
        ciphers = schemes,
    )

    private fun decrypt(scheme: CipherScheme, frameHex: String) =
        CipherEngine.decrypt(scheme, frameHex.hexToBytes(), FrameContext(address = address))

    private fun javaxEncrypt(transform: String, keyHex: String, ivHex: String?, plaintextHex: String): String =
        Cipher.getInstance(transform).run {
            if (ivHex == null) {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyHex.hexToBytes(), "AES"))
            } else {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(keyHex.hexToBytes(), "AES"),
                    IvParameterSpec(ivHex.hexToBytes()),
                )
            }
            doFinal(plaintextHex.hexToBytes()).toHex()
        }

    private fun javaxGcmSeal(keyHex: String, nonceHex: String, plaintextHex: String, aadHex: String?): String =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyHex.hexToBytes(), "AES"),
                GCMParameterSpec(128, nonceHex.hexToBytes()),
            )
            aadHex?.let { updateAAD(it.hexToBytes()) }
            doFinal(plaintextHex.hexToBytes()).toHex()
        }
}
