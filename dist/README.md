<img src="../branding/logo.svg" alt="BLE Studio" width="380">

# BLE Studio

An experimental, local-first Bluetooth LE workbench and Home Assistant custom integration. Capture evidence, inspect GATT traffic, map reviewed commands, and create explicit button controls through Home Assistant’s shared Bluetooth stack.

**Status: developer preview 0.1.0.** Parser, profile and selected browser interactions have been tested. No physical device, ESPHome proxy, Home Assistant clean installation, HACS installation, or container release has been validated in this environment. No universal-device compatibility is claimed.

## Three parts, one project

| Component | Purpose | Radio access |
| --- | --- | --- |
| BLE Studio | Guided capture import, analysis, byte comparison, evidence review and export | Optional Web Bluetooth on the computer/tablet viewing the page |
| BLE Studio Integration | Reviewed fixed-payload buttons inside Home Assistant | HA Bluetooth API → active ESPHome proxy or local adapter |
| Optional Workbench add-on / Docker image | Hosts the same browser UI locally | No host adapter, DBus, privileged device or proxy access of its own |

HACS installs **custom integrations**. The Supervisor add-on repository installs the optional **Workbench app**. Neither is required to keep the other running. The integration does not fetch code or profiles from the hosted workbench.

## What works in this preview

- Home Assistant-inspired sidebar, blue accents, light/dark themes, responsive cards and readable controls.
- Independent browser-local sessions; synthetic demo never mixes into imported data.
- JSON, header-based CSV/TSV and key=value log imports with atomic text validation.
- Android H4 btsnoop parsing for fixed-channel ATT writes, notifications and indications; fragmented ACL reassembly and connection-handle reuse separation when disconnect events are captured.
- Explicit unresolved handles; UUID mappings scoped to a captured connection.
- Action labels, operation/connection/characteristic filters, byte comparisons with decimal/ASCII views.
- Separate observed, hypothesis and user-reported device-tested stages.
- Full evidence JSON, Python constants, workspace backups and strict eligible-only Home Assistant install profiles.
- Permission-based Web Bluetooth discovery, reads and notification recording for the browser’s own GATT session.
- Integration config/reconfigure flow; reviewed button entities disabled by default; explicit write opt-in.
- Service membership and write-property validation, serialized writes, timeout handling and no automatic replay of failed writes.

## What this does not do

This is not a universal integration generator. It does not decode proprietary encryption, extract keys, bypass authentication, infer checksums or prove command meanings. It does not implement sensor/state decoding, dynamic sliders, locks, climate entities, automatic installation, over-the-air packet capture, raw PCAP decoding, or USB adapter firmware drivers.

Marking a command “device-tested” records **your report**, not an automated certification. Do not use unknown writes with safety-critical devices, locks, heaters, medical equipment or machinery.

## Capture routes

| Route | Supported here | Important boundary |
| --- | --- | --- |
| Vendor app on Android | Import its HCI btsnoop log | Logs the phone’s traffic; capture/log retrieval depends on phone and Android version |
| GATT/Wireshark tools | Import decoded JSON/CSV/text | Export discovery UUIDs or resolve ATT handles later |
| Android tablet / desktop browser | Web Bluetooth reads and notifications when API/permissions are available | Uses that browser device’s radio; does not sniff the vendor app |
| HA local adapter / active ESPHome proxy | Integration command writes | Uses Home Assistant’s connection stack, not the browser’s radio |
| USB sniffer / Web Serial / WebUSB | Architecture extension point only | Requires a concrete supported adapter/firmware and OS/browser support |

An ESPHome proxy is not an over-the-air sniffer for a separate phone connection. HA can expose advertisements and perform its own GATT interactions; it cannot generally recover the vendor app’s command exchange from another connection. Ordinary USB Bluetooth dongles are not interchangeable with serial sniffers.

### Android vendor-app workflow

1. Enable Developer options and Bluetooth HCI snoop logging. Restart Bluetooth.
2. Connect the vendor app to your own device. Record an idle baseline.
3. Press one button, note the timestamp, wait, and repeat three times. Avoid changing several things at once.
4. Retrieve the btsnoop file using the phone’s supported logging/bug-report workflow. Do not upload an entire bug report or unrelated traffic.
5. Import the file, select the relevant connection, label actions and resolve characteristic handles using discovery evidence from the same connection.
6. Disable HCI logging after capture.

The parser does not decode EATT, prepared/signed writes, discovery packets, encrypted application payloads or btsnoop formats other than version 1 / H4 datalink 1002. It reports unsupported/incomplete packets and rejects truncated records. Captures missing disconnect events can still leave connection reuse ambiguous; use short device-specific sessions.

### Text example

```text
[0.000] WRITE_CMD action="Power on" service=fff0 char=fff1 value=01 01
[0.065] NOTIFY action="Power on" service=fff0 char=fff2 value=A1 01
```

This example is fictional. Fields: `direction`, `value`, `action`, `timestamp`, `service`, `characteristic`, `handle`, `connection`, `response`. `write_cmd` means without-response; `write_req` means with-response. A bare `write` leaves mode unknown unless `response` is supplied. Hex payloads must have complete bytes.

Limits: 10 MB input file, 10,000 events/session, 512 bytes/value. UI tables show the first 250 filtered matches and comparison selectors the first 1,000 events; narrow/split large captures for detailed analysis. Exports retain all events. Browser storage capacity varies; use backups.

### Browser exploration

Open Capture → **Connect from this tablet / desktop**. Supply known service UUIDs, choose your device in the browser permission dialog, then read characteristics or record notifications. Save the captured session before leaving. Subscribe/unsubscribe interacts with the GATT notification configuration but does not send arbitrary device command payloads.

Web Bluetooth requires a supported browser and secure context (normally HTTPS). It may be blocked in a Home Assistant iframe/companion webview or by Permissions Policy. A detected API does not guarantee radio access. Desktop Linux, Android USB access and Web Serial availability differ. Prefer Chrome on a supported platform and open the workbench directly where needed. No adapter chooser can make an unsupported OS USB dongle work automatically.

## Install the custom integration

[![Add repository to HACS](https://my.home-assistant.io/badges/hacs_repository.svg)](https://my.home-assistant.io/redirect/hacs_repository/?owner=nphil&repository=BLE-Explorer&category=integration)
[![Add integration](https://my.home-assistant.io/badges/config_flow_start.svg)](https://my.home-assistant.io/redirect/config_flow_start/?domain=ble_studio)

### HACS (after repository publication)

1. Install/configure HACS first. Open HACS → custom repositories.
2. Add `https://github.com/nphil/BLE-Studio` as **Integration**.
3. Download BLE Studio Integration and restart Home Assistant.
4. In the workbench, save the device’s Bluetooth address. Only after physically testing a safe command, record evidence and set its write mode.
5. Select Integration → **Download HA install profile**. This is the eligible-only runtime file, not the richer evidence/backup JSON.
6. In HA, Settings → Devices & services → Add integration → BLE Studio Integration. Enter the target address and paste the install profile. Leave writes disabled for initial review.
7. Check the generated device and buttons. Explicitly reconfigure to allow writes and enable only the button entities you intend to use.

Home Assistant 2026.3+ is the declared minimum for local brand assets. The technical domain remains `ble_studio` for profile/entry stability. HACS installs the custom component folder from the selected source release; the release ZIP is for manual installation, avoiding ambiguous HACS ZIP nesting.

### Manual/private installation

Copy `custom_components/ble_studio/` into `/config/custom_components/ble_studio/` without overwriting unrelated integrations, or extract the release ZIP into `/config/` (it contains the `custom_components/ble_studio` prefix). Restart HA and use Add integration. Back up the existing folder before upgrading.

To revise a profile or change write opt-in, use the integration entry’s **Reconfigure** action. A different Bluetooth address requires a new entry, preventing identity collisions. Rotating addresses and devices requiring pairing/session authentication need additional device-specific work.

### ESPHome proxies and local adapters

Ensure the target device is in range of a Home Assistant Bluetooth source with active connections enabled. The integration resolves it with `bluetooth.async_ble_device_from_address(hass, address, connectable=True)` and uses the shared Bleak connection path. It does not create its own scanner or connect using a raw address outside HA.

If a write fails: close the vendor app if the device allows one client, check proxy connection slots, active-proxy configuration, HA Bluetooth health, device power and address. A connection error is not proof that a payload is wrong. Connection establishment can retry; command writes are not automatically retried because a timed-out write may already have taken effect. No guarantee is made for pairing, large payloads or vendor session protocols.

## Optional Workbench add-on

[![Add add-on repository](https://my.home-assistant.io/badges/supervisor_addon_repository.svg)](https://my.home-assistant.io/redirect/supervisor_addon_repository/?repository_url=https%3A%2F%2Fgithub.com%2Fnphil%2FBLE-Studio)

After a matching image release exists, add the repository to Home Assistant’s app/add-on store, install BLE Studio and open its ingress panel. The ingress panel is the authenticated native-sidebar route, so an iframe panel is not needed. This requires a Supervisor-managed installation supporting add-ons; HA Container does not provide the add-on store.

The add-on requests no Bluetooth/DBus/device privileges. It serves the UI; the separately installed integration uses HA Bluetooth. Browser-local session data belongs to the ingress origin/profile and is not synchronized to the hosted Site or another browser. The published GHCR package must be public for the Supervisor to pull it without registry credentials.

## Docker

```sh
docker build -t ble-studio:dev .
docker run --rm -p 127.0.0.1:8080:8080 ble-studio:dev
```

Open `http://localhost:8080`. The image serves `dist/` with unprivileged nginx. It contains no Home Assistant runtime or BLE driver. Do not expose it to a network without authentication/TLS: unlike the private hosted Site, the standalone container has **no built-in login**. HTTPS/secure-context requirements apply to browser hardware APIs.

After a successful tagged workflow, use `ghcr.io/nphil/ble-studio:0.1.0` (amd64/arm64). OCI labels carry the project title and repository. GHCR does not have a portable per-image icon field; README branding and OCI metadata are supplied instead.

## Development and release

No frontend dependency install is required: Node 22+ runs `npm run dev`, `npm test` and `npm run check`. The source is plain ES modules and CSS; assets live in `dist/` intentionally. Python validator tests: `python -m unittest discover -s tests -p 'test_*.py'`. Python runtime modules require Home Assistant and are not a standalone CLI.

Workflows test branch/PR changes. A `v*` tag runs tests, packages a manual integration ZIP and publishes a GitHub release plus multi-architecture GHCR image. Release/package permissions use the repository-scoped `GITHUB_TOKEN`. No personal access token is embedded. Keep the manifest, add-on version and release tag aligned. A published image is not a tested add-on release.

Before tagging: validate with the target Home Assistant version; test clean install/reconfigure/unload; test a real safe device through an active proxy and local adapter; build/run the container; validate HACS install; verify add-on ingress, image pull and branding. The workbench’s Web Bluetooth path also needs physical tablet/desktop testing.

Local `brand/icon.png` and `brand/logo.png` assets are included for HA. HACS’s own repository-list icon may depend on its version and branding support. SVG sources are in `dist/icon.svg` and `branding/logo.svg`; `scripts/render-brand.cjs` generates packaging PNGs using Sharp.

## Privacy and security

Capture files are parsed in the browser; no capture upload endpoint, analytics, remote font or telemetry is included. Browser Bluetooth uses the browser’s permission chooser. Notes, addresses and captures can identify devices or reveal private behavior: do not commit them. Use generated examples in tests/issues. Back up locally before deleting sessions or clearing browser data. Backups are unencrypted JSON; protect them accordingly.

The runtime stores the submitted profile in HA config-entry data. It has no automatic discovery matcher, no arbitrary service execution endpoint, no cloud key extraction and no unattended install/update mechanism. Fixed payloads are not inherently safe: verify exact device/firmware semantics yourself.

## Next development milestones

1. Hardware-tested reference profile with reproducible evidence and HA installation tests.
2. Read/notification capture through the installed HA integration, with explicitly scoped UI access and connection lifecycle management.
3. Versioned device-specific decoders, authentication strategies, parameterized commands and entity state models.
4. A named USB/sniffer transport after selecting and testing actual hardware; Android support must be demonstrated rather than inferred.

## References

- [Home Assistant Bluetooth APIs](https://developers.home-assistant.io/docs/core/bluetooth/api/)
- [ESPHome Bluetooth proxy](https://esphome.io/components/bluetooth_proxy/)
- [Android HCI logging](https://source.android.com/docs/core/connect/bluetooth/verifying_debugging)
- [Web Bluetooth](https://developer.chrome.com/docs/capabilities/bluetooth)
- [Web Serial](https://developer.chrome.com/docs/capabilities/serial)
- [Home Assistant local integration branding](https://developers.home-assistant.io/blog/2026/02/24/brands-proxy-api/)

BLE Studio is independent and is not endorsed by Home Assistant, HACS, ESPHome or Bluetooth SIG.
