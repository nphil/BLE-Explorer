package dev.nphil.blueshark.crypto

import dev.nphil.blueshark.model.ByteRange
import dev.nphil.blueshark.model.ByteSource
import dev.nphil.blueshark.model.CipherByteOrder
import dev.nphil.blueshark.model.CipherPrimitive
import dev.nphil.blueshark.model.CipherScheme
import dev.nphil.blueshark.model.FrameMatch
import dev.nphil.blueshark.model.KeyDerivation

/**
 * A named starting point: everything about a known scheme except the secret.
 *
 * @param verified true when the byte layout was read out of a cited implementation *and* checked
 *   against a public vector; false marks a preset whose layout is cited but unproven, which the
 *   UI shows as EXPERIMENTAL. Nothing here is a guess: [source] names the file the layout came
 *   from, and a preset whose source is self-inconsistent says so in its notes.
 */
data class CipherPreset(
    val id: String,
    val label: String,
    val ecosystem: String,
    val verified: Boolean,
    val source: String,
    val keyHint: String,
    val template: CipherScheme,
)

/**
 * The presets that ship.
 *
 * These are convenience, not capability: every one of them is expressible in the editor, and an
 * ecosystem nobody has documented is a scheme the operator builds by hand out of the same parts.
 * That is the whole point of modelling the nonce as a [ByteSource] rather than writing a decoder
 * per vendor.
 */
object Presets {

    private const val XIAOMI_SOURCE =
        "Bluetooth-Devices/xiaomi-ble src/xiaomi_ble/parser.py::_decrypt_mibeacon_v4_v5; vector from " +
            "home-is-where-you-hang-your-hack.github.io/ble_monitor/MiBeacon_protocol"

    private const val TUYA_SOURCE =
        "PlusPlus-ua/ha_tuya_ble custom_components/tuya_ble/tuya_ble/tuya_ble.py::_build_packets and " +
            "_parse_input; same code in PlusPlus-ua/python-tuya-ble and redphx/poc-tuya-ble-fingerbot"

    private const val TELINK_SOURCE =
        "mjg59/python-tikteck tikteck/__init__.py::encrypt_packet + generate_sk; google/python-dimond " +
            "dimond/__init__.py agrees on the transmit path"

    /**
     * Xiaomi MiBeacon v4/v5, with the device address carried inside the frame.
     *
     * Layout from `xiaomi-ble`: frame control at 0..1, product id at 2..3 (little-endian on the
     * wire), frame counter at 4, the address reversed at 5..10, then the ciphertext up to the
     * three-byte extended counter and the four-byte MIC that end the frame. The nonce is the
     * reversed address, the product id and counter exactly as transmitted, and the extended
     * counter; the AAD is the single byte 0x11 and the MIC is four bytes.
     *
     * Verified against the public vector in `MiBeaconDecryptTest`.
     */
    val xiaomiMiBeaconV4V5 = CipherPreset(
        id = "xiaomi-mibeacon-v4v5",
        label = "Xiaomi MiBeacon v4/v5",
        ecosystem = "Xiaomi / Mijia",
        verified = true,
        source = XIAOMI_SOURCE,
        keyHint = "The 16-byte bindkey for this device, from the Mi Home cloud or a token extractor",
        template = CipherScheme(
            name = "Xiaomi MiBeacon v4/v5",
            primitive = CipherPrimitive.AES_CCM,
            nonce = ByteSource.Composite(
                listOf(
                    ByteSource.FrameBytes(5, 6),
                    ByteSource.FrameBytes(2, 3),
                    ByteSource.FrameBytes(-7, 3),
                ),
            ),
            aad = ByteSource.Constant("11"),
            tagLength = 4,
            tag = ByteSource.FrameBytes(-4, 4),
            ciphertextRange = ByteRange(offset = 11, length = -1, dropFromEnd = 7),
            match = FrameMatch(minLength = 19),
            presetId = "xiaomi-mibeacon-v4v5",
            notes = "AES-CCM, 4-byte MIC, AAD 0x11. Nonce = reversed address (frame 5..10) + " +
                "product id and frame counter (frame 2..4) + extended counter (3 bytes before the " +
                "MIC). Apply to the 0xFE95 service data, not to a GATT payload. Source: $XIAOMI_SOURCE",
        ),
    )

    /**
     * The same scheme for frames that omit the address, which the parser then takes from the
     * advertisement's source address. The ciphertext therefore starts six bytes earlier.
     */
    val xiaomiMiBeaconV4V5NoMac = CipherPreset(
        id = "xiaomi-mibeacon-v4v5-nomac",
        label = "Xiaomi MiBeacon v4/v5 (address not in frame)",
        ecosystem = "Xiaomi / Mijia",
        verified = true,
        source = XIAOMI_SOURCE,
        keyHint = "The 16-byte bindkey for this device, from the Mi Home cloud or a token extractor",
        template = CipherScheme(
            name = "Xiaomi MiBeacon v4/v5 (address from session)",
            primitive = CipherPrimitive.AES_CCM,
            nonce = ByteSource.Composite(
                listOf(
                    ByteSource.MacAddressReversed,
                    ByteSource.FrameBytes(2, 3),
                    ByteSource.FrameBytes(-7, 3),
                ),
            ),
            aad = ByteSource.Constant("11"),
            tagLength = 4,
            tag = ByteSource.FrameBytes(-4, 4),
            ciphertextRange = ByteRange(offset = 5, length = -1, dropFromEnd = 7),
            match = FrameMatch(minLength = 13),
            presetId = "xiaomi-mibeacon-v4v5-nomac",
            notes = "As the MAC-in-frame preset, but the first six nonce bytes come from the " +
                "session's device address reversed, and the ciphertext starts at offset 5. Use this " +
                "when the frame control's MAC-included bit is clear. Source: $XIAOMI_SOURCE",
        ),
    )

    /**
     * Tuya BLE frames encrypted under the login key.
     *
     * Wire layout from `tuya_ble.py::_parse_input`: a one-byte security selector, a random
     * 16-byte IV, then AES-CBC ciphertext. Selector 0x04 means the login key, which is
     * `MD5(local_key[:6])` - so the operator pastes the first six characters of the device's local
     * key as text and the MD5 derivation turns it into the key. The plaintext is zero-padded to a
     * block boundary rather than PKCS#5 padded, so it decrypts with NoPadding and the trailing
     * zeros are real bytes, not padding to strip.
     *
     * Structure is cited but there is no public vector: the libraries generate a random IV per
     * packet and ship no crypto tests, so this preset is proved only by round trip.
     */
    val tuyaLoginKey = CipherPreset(
        id = "tuya-ble-login",
        label = "Tuya BLE (login key, flag 0x04)",
        ecosystem = "Tuya",
        verified = false,
        source = TUYA_SOURCE,
        keyHint = "The first six characters of the device's local key, as text - the app MD5s them",
        template = CipherScheme(
            name = "Tuya BLE login key",
            primitive = CipherPrimitive.AES_CBC,
            keyDerivation = KeyDerivation.MD5,
            nonce = ByteSource.FrameBytes(1, 16),
            ciphertextRange = ByteRange(offset = 17, length = -1),
            match = FrameMatch(payloadPrefixHex = "04", minLength = 33),
            presetId = "tuya-ble-login",
            notes = "EXPERIMENTAL: layout cited, no public vector. Frame = flag(1) | IV(16) | " +
                "AES-CBC ciphertext. Key = MD5(local_key[:6]); enter those six characters as text. " +
                "Plaintext is seq(4) | ack(4) | code(2) | length(2) | data | CRC16(2), zero-padded " +
                "to 16 bytes - the trailing zeros are padding the device added, not PKCS#5. " +
                "Source: $TUYA_SOURCE",
        ),
    )

    /**
     * Tuya BLE frames encrypted under the session key, `MD5(local_key[:6] || srand)` where `srand`
     * is bytes 6..11 of the device-info response. Derive that with the handshake tool in the
     * editor - parts `local_key[:6]` and the response's bytes 6..11, digest MD5 - and paste the
     * result, which is why this template's key is used raw.
     */
    val tuyaSessionKey = CipherPreset(
        id = "tuya-ble-session",
        label = "Tuya BLE (session key, flag 0x05)",
        ecosystem = "Tuya",
        verified = false,
        source = TUYA_SOURCE,
        keyHint = "MD5(local_key[:6] + srand); build it with \"Derive from handshake\"",
        template = CipherScheme(
            name = "Tuya BLE session key",
            primitive = CipherPrimitive.AES_CBC,
            nonce = ByteSource.FrameBytes(1, 16),
            ciphertextRange = ByteRange(offset = 17, length = -1),
            match = FrameMatch(payloadPrefixHex = "05", minLength = 33),
            presetId = "tuya-ble-session",
            notes = "EXPERIMENTAL: layout cited, no public vector. Same framing as the login-key " +
                "preset; the key is MD5(local_key[:6] || srand) with srand = bytes 6..11 of the " +
                "device-info response. Use \"Derive from handshake\": part 1 the six local-key " +
                "characters, part 2 the response frame's bytes 6..11, digest MD5. " +
                "Source: $TUYA_SOURCE",
        ),
    )

    /**
     * Telink private-mesh payload keystream.
     *
     * From `tikteck::encrypt_packet`: bytes 0..2 are a plaintext sequence number, bytes 3..4 a
     * two-byte authenticator the same code derives from the payload, and bytes 5..19 are the
     * fifteen encrypted bytes. The keystream is one AES-ECB block of
     * `00 | address[0..3] | 01 | sequence[0..2] | 00 × 7`, where `address` is the BLE address
     * reversed and only its first four bytes are used - which is what this template's nonce says.
     *
     * Two things make this EXPERIMENTAL, and neither is fixable here. The reference
     * implementations feed the block cipher byte-reversed (reversed key, reversed input, reversed
     * output), which [CipherByteOrder.REVERSED_BLOCKS] reproduces but no specification endorses;
     * and `python-dimond`'s own receive path builds a different block from its transmit path, so
     * the inbound layout cannot be confirmed from a primary source at all. The session key must be
     * supplied ready-made: it is `AES(xor(mesh_name, mesh_password), client_random || device_random)`
     * under that same reversed orientation, which the key toolkit deliberately does not imitate.
     */
    val telinkMesh = CipherPreset(
        id = "telink-mesh",
        label = "Telink mesh payload (experimental)",
        ecosystem = "Telink",
        verified = false,
        source = TELINK_SOURCE,
        keyHint = "The 16-byte session key from the pairing exchange, not the mesh password",
        template = CipherScheme(
            name = "Telink mesh payload",
            primitive = CipherPrimitive.AES_CTR,
            byteOrder = CipherByteOrder.REVERSED_BLOCKS,
            nonce = ByteSource.Composite(
                listOf(
                    ByteSource.Constant("00"),
                    ByteSource.Slice(ByteSource.MacAddressReversed, 0, 4),
                    ByteSource.Constant("01"),
                    ByteSource.FrameBytes(0, 3),
                    ByteSource.Constant("00000000000000"),
                ),
            ),
            ciphertextRange = ByteRange(offset = 5, length = 15),
            match = FrameMatch(minLength = 20),
            presetId = "telink-mesh",
            notes = "EXPERIMENTAL: community implementations only, no normative spec, no public " +
                "vector, and python-dimond's receive path contradicts its own transmit path. " +
                "Keystream = one AES block of 00 | reversed-address[0..3] | 01 | sequence[0..2] | " +
                "zeros, XORed over frame bytes 5..19. Bytes 3..4 are a source-defined authenticator " +
                "this engine does not check. Feed it the 16-byte session key, not the mesh " +
                "password. Source: $TELINK_SOURCE",
        ),
    )

    val genericAesEcb = generic(
        id = "generic-aes-ecb",
        label = "Generic AES-ECB",
        primitive = CipherPrimitive.AES_ECB,
        notes = "Whole 16-byte blocks, no nonce. The weakest thing a vendor can ship: identical " +
            "plaintext blocks stay identical, so the Compare tab on decrypted payloads is often " +
            "enough to read the protocol.",
    )

    val genericAesCbc = generic(
        id = "generic-aes-cbc",
        label = "Generic AES-CBC",
        primitive = CipherPrimitive.AES_CBC,
        nonce = ByteSource.FrameBytes(0, 16),
        ciphertextRange = ByteRange(offset = 16, length = -1),
        notes = "Assumes the 16-byte IV is the start of the frame, which is where most vendors put " +
            "it. Point the IV somewhere else, or make it a constant, if this device differs.",
    )

    val genericAesCtr = generic(
        id = "generic-aes-ctr",
        label = "Generic AES-CTR",
        primitive = CipherPrimitive.AES_CTR,
        notes = "A nonce shorter than 16 bytes is right-padded with zeros, so the counter starts at " +
            "zero. Unauthenticated: CTR turns any key into plausible-looking bytes, so a result is " +
            "only evidence once the plaintext makes sense.",
    )

    val genericAesGcm = generic(
        id = "generic-aes-gcm",
        label = "Generic AES-GCM",
        primitive = CipherPrimitive.AES_GCM,
        tagLength = 16,
        notes = "12-byte nonce and a 16-byte tag appended to the ciphertext is the usual shape. The " +
            "tag either matches or it does not, so this one tells you whether the key is right.",
    )

    val genericAesCcm = generic(
        id = "generic-aes-ccm",
        label = "Generic AES-CCM",
        primitive = CipherPrimitive.AES_CCM,
        tagLength = 8,
        notes = "RFC 3610. A 13-byte nonce gives the usual 2-byte length field, a 12-byte nonce " +
            "gives 3. Tags are an even 4..16 bytes. The tag proves the key.",
    )

    val genericChaCha = generic(
        id = "generic-chacha20-poly1305",
        label = "Generic ChaCha20-Poly1305",
        primitive = CipherPrimitive.CHACHA20_POLY1305,
        tagLength = 16,
        notes = "32-byte key, 12-byte nonce, 16-byte tag appended. Rare on cheap BLE hardware " +
            "without an AES accelerator, common on ESP32-based devices.",
    )

    val genericXor = generic(
        id = "generic-xor",
        label = "Repeating-key XOR",
        primitive = CipherPrimitive.XOR,
        notes = "The key repeats over the payload. Not encryption, but a lot of devices ship it - " +
            "and a one-byte key is brute-forced by eye in the Compare tab.",
    )

    /** Presets in the order the menu shows them: known ecosystems first, then the raw primitives. */
    val all: List<CipherPreset> = listOf(
        xiaomiMiBeaconV4V5,
        xiaomiMiBeaconV4V5NoMac,
        tuyaLoginKey,
        tuyaSessionKey,
        telinkMesh,
        genericAesEcb,
        genericAesCbc,
        genericAesCtr,
        genericAesGcm,
        genericAesCcm,
        genericChaCha,
        genericXor,
    )

    fun byId(id: String?): CipherPreset? = id?.let { wanted -> all.firstOrNull { it.id == wanted } }

    private fun generic(
        id: String,
        label: String,
        primitive: CipherPrimitive,
        nonce: ByteSource = ByteSource.Constant(""),
        tagLength: Int? = null,
        ciphertextRange: ByteRange = ByteRange(),
        notes: String,
    ) = CipherPreset(
        id = id,
        label = label,
        ecosystem = "Any",
        verified = true,
        source = "This app's engine; no ecosystem assumptions",
        keyHint = "Whatever key you obtained, in hex, base64 or as text",
        template = CipherScheme(
            name = label,
            primitive = primitive,
            nonce = nonce,
            tagLength = tagLength,
            ciphertextRange = ciphertextRange,
            presetId = id,
            notes = notes,
        ),
    )
}
