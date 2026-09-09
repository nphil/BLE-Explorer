# Application-layer decryption

Most cheap BLE gadgets do not use BLE's own link-layer encryption for anything that matters.
They encrypt the *payload* — inside the ATT write, inside the advertisement's service data — with
a key the vendor app holds. An HCI snoop capture therefore contains the ciphertext and nothing
else, and the capture is useless until the key is supplied.

BLE Studio's answer is a scheme *description*, not a decoder per vendor. Sessions → **Decrypt**
lets you say which bytes are the nonce, which are the AAD, which are the tag and which are the
ciphertext, pick a primitive, paste the key, and the timeline gains a second line per frame. The
presets for Xiaomi, Tuya and Telink are conveniences built out of exactly the same parts you have
by hand.

Two rules are absolute:

* **The ciphertext is evidence and is never overwritten.** `BleEvent.payloadHex` is what the radio
  carried. Decryption is a view computed on demand and cached in memory; nothing is written back.
* **The key is your credential for someone else's device.** It is stored in the session on this
  device, and it is stripped from a shared evidence bundle unless you explicitly tick *Include
  cipher keys*.

## 1. How a scheme is modelled

`CipherScheme` (`android/app/src/main/kotlin/dev/nphil/blestudio/model/SessionModels.kt`) is the
whole vocabulary:

| Field | Meaning |
| --- | --- |
| `primitive` | `AES_ECB`, `AES_CBC`, `AES_CTR`, `AES_GCM`, `AES_CCM`, `CHACHA20_POLY1305`, `XOR` |
| `keyHex` + `keyDerivation` | the secret, and what to do to it: `RAW`, `SHA256`, `MD5`, `HMAC_SHA256_WITH_SALT`, `AES_ECB_OF_CONSTANT` |
| `nonce`, `aad`, `tag` | `ByteSource`s — see below |
| `tagLength` | AEAD tag size in bytes; CCM allows an even 4…16, GCM 4…16 |
| `ciphertextRange` | `offset` (negative counts from the end), `length` (-1 = to the end), `dropFromEnd` (trailing counter/MIC that is not ciphertext) |
| `match` | which frames the scheme claims: characteristic, direction, payload prefix, minimum length |
| `padded` | PKCS#5 for CBC/ECB; otherwise the ciphertext must be block-aligned and padding bytes show as real bytes |
| `byteOrder` | `NATURAL`, or `REVERSED_BLOCKS` for the Telink convention (§3.3) |
| `counterLittleEndian` | the default endianness for every `ByteSource.Counter` in the scheme |

### `ByteSource`: where a run of bytes comes from

A nonce is almost never a constant. Modelling it as a composable source is what makes the engine
ecosystem-agnostic — a new vendor scheme is a new composition, not new code.

| Variant | Bytes |
| --- | --- |
| `Constant(hex)` | literal bytes you typed |
| `FrameBytes(offset, length)` | a slice of the frame being decrypted; negative `offset` counts from the end, `length` -1 runs to the end |
| `Composite(parts)` | concatenation, in order |
| `MacAddress` / `MacAddressReversed` | the session's device address, printed order or least-significant byte first |
| `Slice(source, offset, length)` | a run of another source, e.g. four bytes of a six-byte address |
| `Counter(width, littleEndian?)` | how many frames this scheme has matched so far; `littleEndian = null` inherits the scheme |

The session counter is positional, so it is assigned during a single metadata pass over the
capture (`SchemeApplier.index`) that runs no crypto at all. The ciphers themselves run lazily,
memoised per event id by `DecryptCache`, so scrolling a ten-thousand-frame timeline only pays for
the rows on screen.

### What "verified" means

`DecryptResult.verified` is `true`/`false` only for the AEAD primitives, where the tag either
matches the key or does not. For `AES_CTR`, `AES_ECB`, `AES_CBC` and `XOR` it is `null`, and the
UI says *"Decrypted, unauthenticated — judge it by the plaintext"*. Those modes turn any key into
plausible-looking bytes; treating "it decrypted" as proof would be the single easiest way to
publish a wrong protocol description.

## 2. AES-CCM

Android's `javax.crypto` has no `AES/CCM/NoPadding` transform and Bouncy Castle is not a
dependency, so CCM is implemented in `crypto/AesCcm.kt` per
[RFC 3610](https://www.rfc-editor.org/rfc/rfc3610.txt) on top of `AES/ECB/NoPadding` — CCM is a
CBC-MAC plus CTR over one block permutation. `AesCcmTest` checks packet vectors 1, 2 and 3 from
RFC 3610 §8 byte-for-byte in both directions, plus tamper detection on the ciphertext and on the
AAD.

The nonce length fixes the length field: `15 − nonceLength` bytes. A 13-byte nonce gives L=2 and a
12-byte nonce (MiBeacon) gives L=3. Tags are an even 4…16 bytes; the tag may be appended to the
ciphertext or supplied separately through `CipherScheme.tag` for frames that carry the MIC
elsewhere.

## 3. The presets, and what is proven about each

### 3.1 Xiaomi MiBeacon v4/v5 — **verified**

Source: [`Bluetooth-Devices/xiaomi-ble`](https://github.com/Bluetooth-Devices/xiaomi-ble)
`src/xiaomi_ble/parser.py`, `_decrypt_mibeacon_v4_v5`.

Apply it to the **0xFE95 service data**, not to a GATT payload. Layout:

```
 0  1 | 2  3 | 4  | 5 .. 10        | 11 ..          | -7 -6 -5 | -4 -3 -2 -1
 frame  prod   cnt  address, LSB    ciphertext        extended   MIC
 control  id                first    (to −7)          counter
```

* AES-CCM, 16-byte bindkey, no key derivation.
* Nonce (12 bytes) = reversed address (frame `5..10`) ‖ product id and frame counter exactly as
  transmitted (frame `2..4`) ‖ extended counter (the three bytes before the MIC).
* AAD = the single byte `0x11`. MIC = 4 bytes, the last four of the frame.

`MiBeaconPresetTest` decrypts the public vector documented for `ble_monitor`
([MiBeacon protocol](https://home-is-where-you-hang-your-hack.github.io/ble_monitor/MiBeacon_protocol)):
service data `58598D0A170FC4E044EF547CC27A5C03A1000000790DF258`, bindkey
`FDD8CE9C08AE7533A79BDAF0BB755E96`, giving nonce `0FC4E044EF548D0A17000000` and plaintext
`0F0003000000` with the tag verifying. A second preset takes the first six nonce bytes from the
session's device address instead, for frames whose frame-control MAC bit is clear.

**Getting the bindkey.** It lives in your Mi Home / Xiaomi Home cloud account, per device. The
community extractors that read it are
[`PiotrMachowski/Xiaomi-cloud-tokens-extractor`](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor)
(logs into the Xiaomi cloud with your account and prints each device's token and BLE bindkey) and
Home Assistant's own Xiaomi BLE config flow, which asks for the bindkey and stores it in the
config entry — so an already-integrated device's key can be read out of
`.storage/core.config_entries`. On a rooted phone the Mi Home app keeps it in its own database.
BLE Studio does not log into anything: paste the 16 bytes.

### 3.2 Tuya BLE — **EXPERIMENTAL** (layout cited, no public vector)

Source: [`PlusPlus-ua/ha_tuya_ble`](https://github.com/PlusPlus-ua/ha_tuya_ble)
`custom_components/tuya_ble/tuya_ble/tuya_ble.py`, `_build_packets` and `_parse_input`; the same
code appears in [`PlusPlus-ua/python-tuya-ble`](https://github.com/PlusPlus-ua/python-tuya-ble)
and [`redphx/poc-tuya-ble-fingerbot`](https://github.com/redphx/poc-tuya-ble-fingerbot).

```
 0            | 1 .. 16 | 17 ..
 security flag  IV        AES-CBC ciphertext
```

* Flag `0x04` → the **login key** = `MD5(local_key[:6])`. Paste those six characters with the
  **Text** encoding and leave the derivation on `MD5(...)`; the app does the rest.
* Flag `0x05` → the **session key** = `MD5(local_key[:6] ‖ srand)`, where `srand` is bytes 6…11 of
  the device-info response. Build it with *Derive from handshake* (§4).
* Flag `0x01` → the 32-byte auth key from the pairing exchange.
* Plaintext is `seq(4) ‖ ack(4) ‖ code(2) ‖ length(2) ‖ data ‖ CRC16(2)`, zero-filled to a 16-byte
  boundary. That is **not** PKCS#5 padding, so leave *PKCS#5 padded* off: the trailing zeros are
  bytes the device added and you should see them.

Marked EXPERIMENTAL because neither library ships a crypto test and every packet uses a fresh
random IV, so there is no fixed vector to check the layout against. The structure is read directly
from the cited source; only the *proof* is missing.

**Getting the local key.** Tuya's own IoT Platform (`iot.tuya.com`) exposes it: create a cloud
project, link the app account that owns the device, and read the device's `local_key` from
*Devices → Device Details*, or via the `/v1.0/devices/{id}` API — this is what
[`tuya-device-sharing-sdk`](https://github.com/tuya/tuya-device-sharing-sdk) and the HA Tuya
integration use. `tuya-cli wizard` automates the same flow. BLE Studio implements none of it.

### 3.3 Telink private mesh — **EXPERIMENTAL** (community sources only)

Sources: [`mjg59/python-tikteck`](https://github.com/mjg59/python-tikteck) `tikteck/__init__.py`
`encrypt_packet` and `generate_sk`; [`google/python-dimond`](https://github.com/google/python-dimond)
`dimond/__init__.py` agrees on the transmit path.

```
 0 1 2      | 3 4                    | 5 .. 19
 sequence     source-defined          15 encrypted bytes
 (plaintext)  authenticator (plain)
```

The keystream is a single AES block of
`00 ‖ reversed-address[0..3] ‖ 01 ‖ sequence[0..2] ‖ 00×7`, XORed over frame bytes 5…19 — which is
`AES_CTR` with that nonce, since only one block is ever consumed.

Three separate reasons this is EXPERIMENTAL and not claimed correct:

1. The reference implementations feed the block cipher **byte-reversed** — reversed key, reversed
   input block, reversed output. `CipherByteOrder.REVERSED_BLOCKS` reproduces it, but no
   specification endorses it, and the app implements it only for ECB/CTR where it is unambiguous.
2. `python-dimond`'s own `decrypt_packet` builds a *different* block from its `encrypt_packet` and
   starts the XOR at byte 7 rather than 5. The receive-side layout cannot be confirmed from a
   primary source at all.
3. Neither repository is a normative Telink SIG/private-mesh specification and neither ships a
   fixed vector, so nothing here can be claimed to cover every Telink variant.

Bytes 3…4 are an authenticator the same reversed-orientation AES derives from the payload. BLE
Studio does not check it, and the UI reports the result as unauthenticated.

**The key.** Supply the 16-byte **session key**, not the mesh password. It is
`AES(xor(mesh_name₁₆, mesh_password₁₆), client_random[:8] ‖ device_random[:8])` under that same
reversed orientation, which the key toolkit deliberately does not imitate — see `generate_sk` in
the cited source. Common factory defaults are mesh name `telink_mesh1` with password `123` (and
`Fulife`/`0000` on some rebadges), but a paired bulb has been renamed.

### 3.4 The generic primitives

`Generic AES-ECB / CBC / CTR / GCM / CCM`, `Generic ChaCha20-Poly1305` and `Repeating-key XOR`
carry no ecosystem assumptions at all: they set the primitive and a plausible starting layout
(CBC assumes the IV is the first 16 bytes, because that is where most vendors put it) and leave
every byte source for you to point at the right place.

## 4. Deriving a session key from the capture

`KeyTools.deriveFromHandshake(events, rule)` covers the shape every ecosystem shares: the phone
sends a random, the device answers with another, and the session key is a fixed function of the
two plus a long-term secret. A `HandshakeRule` is a list of `HandshakePart`s — each a captured
frame plus a `ByteSource` over it — and a derivation applied to the concatenation:

| `derivation` | Session key |
| --- | --- |
| `RAW` | the concatenated material itself |
| `MD5` / `SHA256` | a digest of the material — this is Tuya's `MD5(local_key[:6] ‖ srand)` |
| `AES_ECB_OF_CONSTANT` | `AES(keyHex, material)` — the classic `session_key = AES(login_key, nonce)` |
| `HMAC_SHA256_WITH_SALT` | `HMAC(keyHex, material)` |

The editor's *Derive from handshake* panel builds one of these from two frame pickers and writes
the result into the scheme as a raw key, recording the recipe in the scheme's notes. It is stored
rather than re-derived so the key does not depend on frames you might later delete.

## 5. The `KeyProvider` seam

```kotlin
interface KeyProvider {
    val id: String
    val label: String
    val description: String
    suspend fun keyFor(session: CaptureSession): Result<String>   // uppercase hex
}
```

`ManualKeyProvider` is the only implementation and the only one that ships: you paste the key.
The interface exists so a cloud provider can be added without touching the engine, the model or
the UI — it receives the session (which holds the device address and, in `CaptureEnvironment`, the
vendor app package and version) and returns hex key material or a failure the UI shows.

A future `XiaomiCloudKeyProvider` would do what `Xiaomi-cloud-tokens-extractor` does: sign in to
`account.xiaomi.com`, fetch the device list per region, and read each device's `bindkey`. A
`TuyaCloudKeyProvider` would do what `tuya-cli wizard` does: exchange an IoT-platform access
id/secret for a token and read `local_key` from `/v1.0/devices/{id}`. Both need account
credentials, both are rate-limited, and both would have to answer where those credentials are
stored — which is why neither is in this pass. The seam is the commitment; the login is not.

## 6. Security model

* **Keys stay on the device.** They live in the session bundle in the app's private
  `filesDir/sessions`, and nothing in the app sends them anywhere.
* **Sharing strips them by default.** `ExportService.exportEvidenceBundle(session, includeSecrets)`
  defaults `includeSecrets` to false and runs the session through `SecretRedaction`, which blanks
  `keyHex`, `keySaltHex` and `keyConstantHex` while keeping the scheme's entire shape. The
  recipient learns the frame is AES-CCM with a 4-byte MIC and this nonce layout, and uses their own
  key. Sessions → Export has an *Include cipher keys* checkbox, off by default, and it states how
  many keys the bundle would carry and what that means.
* **The Home Assistant profile is unaffected.** `HaProfileBuilder` builds from `CommandSpec`
  payloads, which are wire bytes. A decrypted view never becomes a command payload, because
  replaying plaintext to an encrypting device does nothing.
* **Decryption cannot corrupt evidence.** There is no code path that writes a plaintext into
  `BleEvent.payloadHex`. The Compare tab's *Use decrypted payloads* switch and the timeline's
  second line are both views over `DecryptCache`, which holds its results in memory only.
