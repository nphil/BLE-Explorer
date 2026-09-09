<p align="center"><img src="branding/logo-1200.png" alt="BlueShark" width="520"></p>

# BlueShark

Reverse-engineer cheap Bluetooth LE gadgets and bring them into Home Assistant, reliably.

BlueShark is a local-first toolkit with three parts:

| Part | What it is | Where it runs |
| --- | --- | --- |
| **BlueShark for Android** | Native Kotlin / Material 3 app for scanning, exploring GATT, sniffing a vendor app's traffic, labelling commands and exporting evidence. Tablet-first, phone-capable. | Android 12+ (`android/`) |
| **BlueShark integration** | Home Assistant custom integration that turns device-tested commands into button entities through HA's shared Bluetooth stack (local adapters and ESPHome proxies). | HACS (`custom_components/blueshark`) |
| **BlueShark add-on** | Optional web workbench served through HA Ingress for importing and reviewing captures on a desktop. | Supervisor add-on (`addon/`, `dist/`) |

Nothing leaves your device unless you share an export.

## Install the Android app (Obtainium)

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add an app with this URL: **`https://github.com/nphil/BlueShark`**
3. Obtainium tracks releases and installs `BlueShark-vX.Y.Z.apk`. The APK is signed with the project key (SHA-256 `06:61:93:81:F0:C1:87:A6:AF:66:B5:5E:9F:ED:00:33:0E:F8:0B:E9:B7:B8:59:2C:AA:AD:E0:1A:94:98:65:1A`).

Or download the APK from the [latest release](https://github.com/nphil/BlueShark/releases/latest).

Requirements: Android 12 or newer, Bluetooth LE. [Shizuku](https://shizuku.rikka.app/) (ADB mode, no root) is optional and unlocks HCI snoop capture.

## The workflow

```
 Scan ──► Explore GATT ──► Capture vendor app ──► Label & verify ──► Export ──► HA integration
```

1. **Scan.** Find the gadget, see its advertisement (name, manufacturer data, service UUIDs, RSSI, PHY, connectable).
2. **Explore.** Connect from the tablet: full service / characteristic / descriptor tree with properties, negotiated MTU and PHY, read, write (with or without response, strict hex), subscribe. Every operation is serialized, bounded by a timeout, and logged.
3. **Capture.** Sniff what the *vendor's* app sends so you do not have to guess:
   - **Same device (recommended).** With Shizuku granted, BlueShark flips `persist.bluetooth.btsnooplogmode` to `full`, restarts Bluetooth, launches the vendor app for you, lets you drop named markers ("power on", "brightness 50%") while you tap buttons, then collects the HCI snoop log (direct read, or via `bugreportz` and the AOSP btsnooz decoder) and parses it: H4 → ACL → L2CAP → ATT, with fragment reassembly and handle→UUID resolution from the captured discovery. Import of an existing `btsnoop_hci.log` or bugreport is also supported.
   - **Relay (experimental).** For iOS-only vendor apps, BlueShark can connect to the real device and advertise a clone; the phone connects to the tablet and every packet is relayed and logged. Hard limits apply (no MAC spoofing; iOS GATT caching and pairing can prevent it).
4. **Label & verify.** Suggested commands are grouped from the capture and matched to your markers. Compare payloads byte-by-byte to see which bytes vary, note parameter hypotheses, and promote a command to *device-tested* only after you have physically replayed it.
5. **Export.** Share the full **evidence bundle** (identity, GATT table, connection facts, every packet, markers, commands, protocol notes, capture environment) or the strict **HA install profile** consumed by the integration.

The evidence bundle is what you hand to a developer (or an AI assistant) to write a full-featured integration: `docs/research/ha-ble-integrations.md` documents the reliability rules a generated integration must follow, learned from auditing real-world reverse-engineered integrations (Fluval, AC Infinity, BedJet).

## Reliability principles

The same rules govern the app and any integration built from its exports:

- An address is not a route: resolve a fresh device before every connection.
- One outstanding GATT operation at a time; every wait is bounded; a timeout is a failure, never a success.
- Correlate responses to the request that caused them; never treat "any notification" as confirmation.
- Prefer write-without-response only when the device has proven to accept it.
- Track availability from advertisement age, not from failed polls.
- Record what the wire actually did (MTU, spacing, latency, disconnect behaviour) instead of hard-coding folklore.

## Themes

Twenty palettes with light and dark variants: Catppuccin, Nord, Dracula, Gruvbox, Solarized, Tokyo Night, Rosé Pine, Everforest, Kanagawa, One (Atom), Monokai Pro, Ayu, Night Owl, Material Palenight, GitHub, Horizon, Synthwave '84, Zenburn, Cobalt2, Nightfox, plus Material You dynamic color. Sources and contrast notes: `docs/palettes.md`.

## Home Assistant integration

[![Add repository to HACS](https://my.home-assistant.io/badges/hacs_repository.svg)](https://my.home-assistant.io/redirect/hacs_repository/?owner=nphil&repository=BlueShark&category=integration)
[![Add integration](https://my.home-assistant.io/badges/config_flow_start.svg)](https://my.home-assistant.io/redirect/config_flow_start/?domain=blueshark)

1. HACS → custom repositories → add `https://github.com/nphil/BlueShark` as **Integration**, download, restart HA.
2. In the app: Sessions → Export → **Share HA install profile** (only device-tested, non-synthetic commands are eligible; the report tells you why anything was excluded).
3. HA → Settings → Devices & services → Add integration → **BlueShark Integration**. Paste the profile. Writes are disabled until you explicitly enable them in Reconfigure; button entities start disabled.

The integration resolves the device through `bluetooth.async_ble_device_from_address(..., connectable=True)`, so any ESPHome Bluetooth proxy with active connections in range works. Domain: `blueshark`. Requires HA 2026.3+.

## Optional add-on

[![Add add-on repository](https://my.home-assistant.io/badges/supervisor_addon_repository.svg)](https://my.home-assistant.io/redirect/supervisor_addon_repository/?repository_url=https%3A%2F%2Fgithub.com%2Fnphil%2FBlueShark)

Adds a **BlueShark** sidebar panel (Ingress) serving the web workbench for importing btsnoop / JSON / CSV captures and reviewing them on a desktop. It requests no Bluetooth or DBus privileges. Image: `ghcr.io/nphil/blueshark`.

## Development

- **Android:** `cd android && ./gradlew :app:assembleDebug` (JDK 17, Android SDK 37). Unit tests: `./gradlew :app:testDebugUnitTest`. Release builds read the signing key from `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`; the version comes from `BLUESHARK_VERSION` (`v1.2.3`).
- **Integration / workbench:** `python -m unittest discover -s tests` and `npm test`.
- **Branding:** `scripts/render-brand.sh` renders the PNGs from `branding/*.svg` (needs `rsvg-convert`).
- **Release:** push a `v*` tag. `release.yml` publishes the integration ZIP and the add-on image; `android.yml` builds, signs and attaches the APK. Keep `addon/config.yaml` and `custom_components/blueshark/manifest.json` versions aligned with the tag.

Research notes behind the design live in `docs/research/`.

## What BlueShark does not do

It does not decode proprietary encryption, extract keys, bypass authentication or prove what a command means. *Device-tested* records **your** report. Do not replay unknown writes at locks, heaters, medical devices or machinery.

## References

- [Home Assistant Bluetooth APIs](https://developers.home-assistant.io/docs/core/bluetooth/api/) · [ESPHome Bluetooth proxy](https://esphome.io/components/bluetooth_proxy/)
- [Android HCI snoop logging](https://source.android.com/docs/core/connect/bluetooth/verifying_debugging) · [Shizuku API](https://github.com/RikkaApps/Shizuku-API)
- [Bluetooth Core Specification](https://www.bluetooth.com/specifications/specs/core-specification/) (ATT / GATT)

BlueShark is independent and is not endorsed by Home Assistant, HACS, ESPHome, Bluetooth SIG or Google.
