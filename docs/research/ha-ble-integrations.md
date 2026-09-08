# BLE Studio reverse-engineering research: Home Assistant BLE reference integrations

## Scope and reading method

This is a read-only inspection of the `main` branches of:

- `nphil/fluvalble` (Fluval Aquarium LED), including its credited upstream `MrMooreUK/fluvalble`.
- `nphil/ac-infinity-airtap-hacs` (AC Infinity AIRTAP), including the vendored lineage from `hunterjm/ac-infinity-hacs` and `hunterjm/ac-infinity-ble`.
- `nphil/ha-bedjet` (BedJet V3), including the credited chain `robert-friedland/ha-bedjet` -> `asheliahut/ha-bedjet` -> `natekspencer/ha-bedjet`.

The report uses `repository/path:Lx-Ly` citations. Line ranges identify the source sections read; GitHub `main` can move, so an exporter should store immutable capture/source references as well as these human-readable links. Statements marked **[INFERENCE]** are reliability conclusions from the observed implementation rather than protocol facts claimed by the repositories.

The three integrations are deliberately different:

| Integration | Discovery identity | Connection shape | State source | HA entities | Most important reliability characteristic |
|---|---|---|---|---|---|
| Fluval | Broad name/service/manufacturer manifest, then strict product-ID gate | Connect-on-demand by default; configurable finite window or persistent mode | Advertisements plus GATT reads/notifications; multiple transports | light, select, switch, button, sensor, binary sensor | Strongest route refresh, command serialization, bounds, retries, and MTU handling; still has address-only callback and custom fixed reachability policy |
| AC Infinity | Connectable advertisement with manufacturer ID `2306` | Connect per poll/command; immediate release; back-to-back commands reuse an open client | Advertisements plus max-two-concurrent advertisement-driven polls and notifications | fan, number, sensor, switch | Good proxy-slot economics and HA unavailable tracking; stale route and timeout/response-correlation gaps remain |
| BedJet | Connectable service/name matchers | Persistent one-connection supervisor by default; explicit handoff switch | 20-byte status notifications ~4 Hz, occasional 11-byte tail read | climate, fan, number, sensor, button, switch, binary sensor | Honest command confirmation and reconnect watchdog; no GATT command lock, no HA route lookup before reconnect, and several unbounded GATT operations |

---

# 1. Fluval Aquarium LED (`nphil/fluvalble`)

## 1.1 Identity, manifest, packaging, and supported hardware

`manifest.json` contains a deliberately broad set of local-name matchers (`Fluval*`, `AquaSky*`, Plant/Marine/Reef variants) plus classic `00001000`/`00001002`, FFF0, and two FACEBD service UUID families. Its service/manufacturer matchers include company IDs `12592`, `12848`, `12599`, `65535`, and `511`; **none of the manifest matchers explicitly sets `connectable: true`**. The integration declares `bluetooth_adapters` and `http` dependencies, no Python requirements, `local_push`, config flow, and device integration metadata: `nphil/fluvalble/custom_components/fluvalble/manifest.json:L1-L90`.

The HACS metadata names the integration “Fluval Aquarium LED”, renders the README, and requires Home Assistant `2026.1.0`: `nphil/fluvalble/hacs.json:L1-L5`. The README says it supports Aquasky, Plant, Marine/Reef, Siena/Roma and first-generation fixtures, and can use local adapters or ESPHome Bluetooth proxies: `nphil/fluvalble/README.md:L1-L80`.

The current fork credits original BLE work by `@mrzottel`, maintenance by `@MrMooreUK`, and the current fork/maintenance by `@nphil`; it also credits proxy and APK/profile contributors: `nphil/fluvalble/README.md:L339-L364`. The credited upstream manifest has the same broad matcher strategy, no external requirements, the same Bluetooth dependencies, and version `0.0.14`: `MrMooreUK/fluvalble/custom_components/fluvalble/manifest.json:L1-L90`.

**Export implication:** preserve every observed identity signal rather than reducing identity to a name. Fluval proves that a shared service UUID alone can be insufficient; a product-ID decode may be the actual identity boundary.

## 1.2 Discovery and config flow

The fork applies a second, stricter check after manifest matching. `is_likely_fluval()` intentionally ignores the name and accepts only an advertisement containing a manufacturer-data product ID recognized by the FluvalConnect catalogue. Classic and FFF0 UUIDs are specifically documented as non-unique, while FACEBD UUIDs are considered specific enough to identify modern advertisements: `nphil/fluvalble/custom_components/fluvalble/core/discovery.py:L1-L45`, `nphil/fluvalble/custom_components/fluvalble/core/discovery.py:L80-L145`.

Bluetooth discovery uses `async_discovered_service_info(..., connectable=True)`, normalizes the MAC, creates a stable unique ID, applies the product-ID gate, and shows a confirm step. Manual setup offers discovered devices or a free-form MAC path; manual addresses are normalized and format-validated, then validated against Bluetooth cache metadata when available: `nphil/fluvalble/custom_components/fluvalble/config_flow.py:L260-L420`. Device metadata includes model/product/service/manufacturer information used for titles and later diagnostics: `nphil/fluvalble/custom_components/fluvalble/config_flow.py:L140-L250`.

The flow has an options flow for runtime connection settings but no reauth/reconfigure path in the inspected section: `nphil/fluvalble/custom_components/fluvalble/config_flow.py:L420-L466`. The stable discovery unique ID is the formatted lower-case MAC, with migration logic for older uppercase entries: `nphil/fluvalble/custom_components/fluvalble/config_flow.py:L140-L180`, `nphil/fluvalble/custom_components/fluvalble/__init__.py:L470-L505`.

**Good pattern:** broad scanner wake-up plus strict application-level identity validation prevents unrelated devices on common UUIDs from creating setup prompts.

**Risk:** product-ID gating can reject a genuinely compatible but as-yet-uncatalogued lamp. The manual path mitigates this operationally, but the implementation still needs a fallback model/profile.

## 1.3 Connection lifecycle, Bluetooth routing, and callbacks

The integration forwards six platforms (binary sensor, button, select, sensor, switch, light), stores per-entry runtime data, first checks HA's last Bluetooth service info with `connectable=True`, and otherwise waits for an advertisement. It registers an **ACTIVE** Bluetooth callback using an address-only matcher (`{"address": mac}`): `nphil/fluvalble/custom_components/fluvalble/__init__.py:L470-L632`.

The `Device` object receives the latest `BLEDevice`, advertisement, and source. It records source/RSSI/service/manufacturer data, updates the product model, and refreshes an already-live client's route. The new client is created only on demand, because client construction itself starts a connection attempt: `nphil/fluvalble/custom_components/fluvalble/core/device.py:L540-L680`.

Fluval's client has separate connection, initialization, and command locks; tracks session initialization, protocol profile, observed state, command verification, and broken-client state: `nphil/fluvalble/custom_components/fluvalble/core/client.py:L1-L180`. Before connecting it asks HA for a fresh connectable `BLEDevice`, falls back to cached service info where needed, then calls `establish_connection` with `max_attempts=3`, a timeout, a `ble_device_callback`, and a disconnected callback. Characteristic discovery and notification subscription happen before publishing the usable client: `nphil/fluvalble/custom_components/fluvalble/core/device.py:L2838-L2925`, `nphil/fluvalble/custom_components/fluvalble/core/client.py:L331-L431`.

The README documents an active connection window of `0` for persistent or `30`–`600` seconds for finite idle release, with a backward-compatible default of `120` seconds. Persistent mode lowers command latency but occupies a local/proxy slot; finite mode permits the official app/gateway to connect: `nphil/fluvalble/README.md:L120-L155`.

When a link drops, the client clears session state, reports disconnected, cancels the heartbeat, and restarts persistent-mode work. The heartbeat reconnects, performs initialization, sends wake reads, writes queued data, waits between cycles, and disconnects after the active window: `nphil/fluvalble/custom_components/fluvalble/core/client.py:L447-L621`.

## 1.4 BLE/GATT protocol and command semantics

Fluval supports three families:

1. **Classic/legacy:** command characteristic `00001001`, notify/state characteristic `00001002`, and legacy wake/read characteristics. Commands use `0x68` opcodes and an XOR checksum.
2. **SPP/Plant Pro:** command `FFF2`, notify `FFF1`, wake `FFF6`, with `D0`/`D1`/`D2` framing around CBOR-like maps.
3. **FACEBD:** FACEBD command/read/notify UUID candidates, with raw CBOR maps and multiple candidate read/notify UUIDs.

The UUID candidates, profile selection, write properties, wake reads, notification UUIDs, and protocol headers are enumerated in `nphil/fluvalble/custom_components/fluvalble/core/client.py:L44-L89` and `nphil/fluvalble/custom_components/fluvalble/core/protocol.py:L1-L79`.

FACEBD/SPP builders encode mode, switch, channel, schedule, effect, clock, and preview values as CBOR maps. FACEBD keys include mode `103`, switch `104`, channels `110`–`114`, and schedule/effect keys `114`–`123`; SPP keys are a parallel compact map: `nphil/fluvalble/custom_components/fluvalble/core/protocol.py:L81-L206`, `nphil/fluvalble/custom_components/fluvalble/core/protocol.py:L265-L353`.

Classic state frames begin with `68 05` and must pass an XOR checksum; classic command builders append that checksum. Classic encrypted writes use an additional envelope with random per-chunk XOR material; decoding validates the envelope and CRC/XOR conditions: `nphil/fluvalble/custom_components/fluvalble/core/protocol.py:L428-L472`, `nphil/fluvalble/custom_components/fluvalble/core/protocol.py:L552-L610`, `nphil/fluvalble/custom_components/fluvalble/core/encryption.py:L1-L70`.

Writes select `write-without-response` when the characteristic exposes it, otherwise `write`. Raw FACEBD/SPP writes are chunked, preferring `max_write_without_response_size`, then `mtu_size - 3`, with a small inter-chunk gap; legacy writes are converted to encrypted frames: `nphil/fluvalble/custom_components/fluvalble/core/client.py:L625-L678`.

## 1.5 Coordinator, availability, state updates, and entity model

Fluval entities set `_attr_should_poll = False`, use Bluetooth MAC device connections/identifiers, and expose manufacturer, model, firmware, name, and per-attribute unique IDs. Entity updates are callback driven: `nphil/fluvalble/custom_components/fluvalble/core/entity.py:L1-L40`.

The platform set is broad: `light.py` contains the main light and schedule/effect handling; `select.py` exposes mode; `switch.py` exposes supported configuration such as daylight-saving; `button.py` provides identify/clock/schedule actions; `sensor.py` exposes RSSI/last-seen/source and diagnostics; `binary_sensor.py` exposes connectivity and schedule-problem state. Representative entity implementations are at `nphil/fluvalble/custom_components/fluvalble/light.py:L1-L190`, `nphil/fluvalble/custom_components/fluvalble/select.py:L1-L90`, `nphil/fluvalble/custom_components/fluvalble/switch.py:L1-L70`, `nphil/fluvalble/custom_components/fluvalble/button.py:L1-L70`, `nphil/fluvalble/custom_components/fluvalble/sensor.py:L1-L110`, and `nphil/fluvalble/custom_components/fluvalble/binary_sensor.py:L1-L100`.

Availability is a custom recent-activity policy rather than HA's learned Bluetooth-unavailable tracker. `REACHABLE_SECONDS` is `300`; `touch_seen()` records successful advertisement, connection, or command activity and schedules a point-in-time expiry. `set_connected()` cancels/re-arms expiry and fans out connection/component callbacks: `nphil/fluvalble/custom_components/fluvalble/core/device.py:L53-L62`, `nphil/fluvalble/custom_components/fluvalble/core/device.py:L540-L603`, `nphil/fluvalble/custom_components/fluvalble/core/device.py:L722-L750`.

High-level writes are wrapped in a bounded `command_transaction`; commands route through `_async_ensure_client()`, `ensure_connected()`, and `send_now()`. The transaction resets the client if its deadline expires, and writes can be verified against decoded state: `nphil/fluvalble/custom_components/fluvalble/core/device.py:L469-L540`, `nphil/fluvalble/custom_components/fluvalble/core/device.py:L2563-L2667`.

## 1.6 Reliability, speed, and proxy-slot tactics

Fluval has the most explicit GATT bounding of the three. It sets connect timeout `20 s`, up to three connection attempts, write retries `2`, write delay `0.3 s`, modern command gap `0.75 s`, classic gap `0.2 s`, post-write verification delay `0.8 s`, state notification timeout `0.75 s`, and chunk gap `0.01 s`. It wraps connect, read, write, notify, disconnect, and other GATT-facing awaits in hard deadlines (`30 s` connect ceiling, `15 s` GATT operation ceiling, `5 s` disconnect ceiling): `nphil/fluvalble/custom_components/fluvalble/core/client.py:L16-L41`, `nphil/fluvalble/custom_components/fluvalble/core/client.py:L180-L230`.

Commands are serialized under `_command_lock`; the device layer adds a whole-command transaction lock and a timeout-triggered connection reset. FACEBD writes may be repeated for verification, followed by an explicit state read if a pushed update did not match: `nphil/fluvalble/custom_components/fluvalble/core/client.py:L754-L913`, `nphil/fluvalble/custom_components/fluvalble/core/device.py:L469-L540`.

The connection route is refreshed before each new connection and is supplied as `ble_device_callback` to `establish_connection`, allowing HA/bleak-retry-connector to select a current adapter/proxy path. This is the pattern BLE Studio should capture as an explicit route-refresh fact: `nphil/fluvalble/custom_components/fluvalble/core/device.py:L2838-L2925`.

The tradeoff is that there is no integration-wide semaphore for proxy slots. A persistent Fluval entry deliberately consumes one slot, and multiple guardians/commands can contend unless the active-time option is chosen carefully: `nphil/fluvalble/README.md:L120-L155`, `nphil/fluvalble/README.md:L326-L353`.

## 1.7 Tests, CI, docs, and upstream lineage

Protocol tests cover classic frames/checksums and decoding; connection-policy tests cover persistent/finite active time, clock initialization, connect-on-demand behavior, and command sequencing: `nphil/fluvalble/tests/test_protocol.py:L1-L40`, `nphil/fluvalble/tests/test_connection_policy.py:L1-L40`. The repository also contains client, discovery, lifecycle, entity, and guardian tests.

CI runs lint/ruff, manifest/translation checks, pytest on Python 3.11/3.12, soft mypy, coverage/Codecov, and a stable-branch HACS check: `nphil/fluvalble/.github/workflows/ci.yml:L1-L154`. Branding assets are explicitly tracked for HACS presentation: `nphil/fluvalble/BRANDING.md:L1-L35`.

## 1.8 Fluval flakiness root-cause audit

**Strong defenses already present**

- Fresh HA connectable-route lookup plus `establish_connection` retry and route callback: `core/device.py:L2838-L2925`, `core/client.py:L331-L431`.
- Separate connection/initialization/command locks, bounded GATT operations, write retry, state verification, and timeout-triggered reset: `core/client.py:L1-L41`, `core/client.py:L754-L913`, `core/device.py:L469-L540`.
- MTU-aware chunking for long modern schedules: `core/client.py:L625-L678`.
- Protocol-level validation for classic checksums, encrypted frames, and CBOR decode: `core/protocol.py:L428-L472`, `core/protocol.py:L636-L783`.

**Residual hazards to carry into BLE Studio requirements**

1. **Manifest/callback connectability is implicit.** The manifest does not set `connectable: true`, and setup registers an address-only callback. HA can therefore deliver address-matching observations that are not the best connectable route. The client later repairs this for a new connection via `async_ble_device_from_address(..., connectable=True)`, but an exporter should record whether a discovery/callback matcher was connectable-constrained: `manifest.json:L1-L90`, `__init__.py:L604-L632`, `core/device.py:L2838-L2925`. **[INFERENCE]** This is a stale/nonconnectable-route risk, not proof that every such frame causes a failed connection.
2. **Fixed 300-second reachability is not HA's learned unavailable interval.** It is intentionally custom and `touch_seen()` counts command/connection activity as well as advertisements. A device can therefore look recently reachable after GATT activity even if scanner advertisements have stopped: `core/device.py:L540-L603`, `core/device.py:L722-L750`. **[INFERENCE]** Prefer raw advertisement age plus HA's unavailable tracker in generated code unless device behavior requires a custom policy.
3. **The legacy `send()` queue is not the same as `send_now()`.** The public verified path uses `_command_lock`, but `send()` updates `send_data` and wakes the ping task; the ping loop's wake read and queued write are outside the command-lock scope used for reconnect: `core/client.py:L447-L621`. **[INFERENCE]** A future caller using `send()` can race a command/read unless all GATT operations, including heartbeat writes, enter one queue.
4. **Persistent mode has slot cost.** It minimizes latency but can block the official app and consumes an adapter/proxy connection slot; finite active time trades latency for sharing: `README.md:L120-L155`.
5. **Pacing uses fixed sleeps and wall-clock command windows.** These are async sleeps, not blocking calls, but the exported timing profile should preserve them and identify whether they came from capture evidence: `core/client.py:L16-L41`, `core/client.py:L680-L913`.

---

# 2. AC Infinity AIRTAP (`nphil/ac-infinity-airtap-hacs`)

## 2.1 Identity, manifest, packaging, and supported hardware

The manifest has one narrow matcher: `connectable: true` plus manufacturer ID `2306`. It declares `bluetooth_adapters`, no external requirements because the protocol library is vendored, `local_push`, config flow, and version `1.2.1`: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/manifest.json:L1-L35`.

HACS metadata identifies “AC Infinity Airtap”, renders README, and requires Home Assistant `2025.7.0`: `nphil/ac-infinity-airtap-hacs/hacs.json:L1-L5`. The README says the integration descends from `hunterjm/ac-infinity-ble`, supports AIRTAP type 6 and best-effort related controllers, and intentionally sends only verified commands: `nphil/ac-infinity-airtap-hacs/README.md:L1-L50`.

The current README documents three typical ESPHome proxy slots, connect-on-demand operation, immediate release, and a fleet-wide cap of two concurrent polls. It reports that six-fan deployments can operate around RSSI `-91 dBm`, but failed polls should not mark entities unavailable while advertisements remain healthy: `nphil/ac-infinity-airtap-hacs/README.md:L85-L100`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L1-L31`.

The upstream integration manifest requires the standalone `ac-infinity-ble==0.4.3`, while the current fork vendors the library and deliberately sets `requirements: []`: `hunterjm/ac-infinity-hacs/custom_components/ac_infinity/manifest.json:L1-L26`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/manifest.json:L1-L35`.

## 2.2 Discovery and config flow

Config flow guards every candidate on manufacturer record `2306`, parses the vendor data, rejects missing/truncated/malformed records, sets unique ID to the Bluetooth address, and presents only parseable discovered devices. It stores both the address and a serialized `CONF_SERVICE_DATA` device snapshot: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/config_flow.py:L1-L74`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/config_flow.py:L75-L162`.

The setup picker has **no free-form/manual MAC entry**; it only offers currently discovered, parseable devices. During confirmation it constructs an `ACInfinityDevice`, performs a GATT update/probe, catches BLE failures for a user-facing `cannot_connect`, and always stops the controller in `finally` to release proxy slots: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/config_flow.py:L75-L162`.

The config-entry schema is intentionally frozen at version 1 (`CONF_ADDRESS` plus `CONF_SERVICE_DATA`) and setup normalizes historical dict/dataclass forms. There is no reauth/reconfigure path in the inspected flow: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/config_flow.py:L1-L15`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/__init__.py:L1-L70`.

**Export implication:** retain both raw advertisement evidence and a parsed snapshot, but make the snapshot explicitly derived/versioned. AC Infinity demonstrates why a future generated integration needs migration-safe schema handling.

## 2.3 Connection lifecycle, Bluetooth routing, and callbacks

Setup obtains a connectable `BLEDevice` once with `bluetooth.async_ble_device_from_address(hass, address.upper(), True)`. If no route is in HA's cache, it raises `ConfigEntryNotReady`: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/__init__.py:L70-L100`.

The coordinator is an `ActiveBluetoothDataUpdateCoordinator` with `mode=ACTIVE`, `connectable=True`, advertisement-driven `needs_poll`, and poll method. It registers the controller callback and inherits HA Bluetooth availability behavior: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L35-L119`.

Every dispatched Bluetooth frame updates the coordinator's latest `BLEDevice` and calls `controller.update_ble_device()`, even if the frame lacks manufacturer data. Manufacturer data is merged only when present and valid; **the base coordinator is always called** so listeners, availability, and poll scheduling continue: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L186-L264`. This fixes the documented fleet-wide freeze where record-less ADV/SCAN_RSP frames skipped `super()` and stopped both UI updates and 30-second polls; the regression is pinned by `nphil/ac-infinity-airtap-hacs/tests/test_coordinator_events.py:L1-L100`.

A poll is considered only when HA is fully running, state is due, and a connectable route currently exists. The `_needs_poll` route check calls `async_ble_device_from_address(..., connectable=True)`, but it is a boolean gate; the result is not assigned to the controller. The controller instead uses the latest `BLEDevice` assigned by advertisement callbacks: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L132-L184`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/device.py:L42-L90`.

The controller calls `establish_connection(BleakClientWithServiceCache, ...)`, resolves cached characteristics, clears stale service cache when needed, starts notifications, and disconnects after polls. The connection callback passed to `establish_connection` is `lambda: self._ble_device`: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L364-L430`.

## 2.4 BLE/GATT protocol and command semantics

The vendored protocol is a framed binary protocol. Frames start `A5 00`; bytes `2-3` contain big-endian payload length, bytes `4-5` a big-endian sequence, bytes `6-7` a CRC16 over the header, byte `8` is reserved, byte `9` is command class (`1` read, `3` write), payload begins at byte `10`, and a trailing CRC16 covers the body: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/protocol.py:L1-L31`.

Payload groups use opcodes `16` mode, `17` OFF/minimum level, `18` ON/maximum level, and `19` AUTO threshold block; optional `255/port` data appears for multi-port types. The code can observe twelve work modes, but only OFF/ON/AUTO have command builders. Modes 4–12 are read-only observations: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/protocol.py:L33-L64`.

The model-data parser uses response bytes `12`, `15`, `18`, `21`, `23`, `25`, `26`, and `27` for work type, OFF/ON levels, threshold flags/temperatures/humidity. Advertisements carry device ID/version/type/flags/temperature/humidity/fan level (and optional port/VPD), but not work type or stored bounds. Notifications start with `0x1E 0xFF` and carry environmental fields and a work-type nibble: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/protocol.py:L66-L125`.

There is no response sequence correlation in the parser. The controller's notification handler gives a waiting command future the first notification and returns before parsing it as unsolicited state; state notifications therefore cannot be assumed to belong to the command that is waiting: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L438-L464`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L647-L688`.

No MTU negotiation, write-size calculation, or chunking appears in the vendored write path; a complete framed command is sent as one GATT write: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L647-L688`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L364-L430`. **[INFERENCE]** Current AIRTAP commands are small, but an exporter must record observed maximum write size and whether long frames were ever tested before generating the same assumption.

## 2.5 Coordinator, availability, state updates, and entity model

Platforms are fan, number, sensor, and switch only: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/__init__.py:L18-L23`.

The fan exposes ON/OFF/AUTO and speed 1–10, uses address-based unique IDs, and provides a Bluetooth device record with manufacturer/model/software metadata. Numbers represent minimum/maximum speed and AUTO temperature thresholds. Sensors provide temperature/fan speed and conditional humidity/VPD; switches represent high/low temperature trigger configuration: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/fan.py:L15-L115`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/number.py:L15-L120`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/sensor.py:L1-L180`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/switch.py:L1-L110`.

Availability comes from the HA active/passive Bluetooth coordinator's unavailable tracker, not from poll success. The code intentionally keeps entities available when advertisements flow but a GATT poll fails, because proxy contention and weak RSSI make isolated poll failures routine: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L1-L31`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L250-L290`.

Advertised state is merged into the controller. Work mode, bounds, and AUTO thresholds are filled by polls. High-level command setters update local state after `_send_command()` returns, while the vendored command path returns `None` on a notification timeout rather than raising: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/device.py:L110-L176`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/device.py:L177-L372`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L647-L688`.

## 2.6 Reliability, speed, and proxy-slot tactics

The controller has `_operation_lock` for a whole command round-trip and `_connect_lock` for connection setup/teardown. Its module-level documentation explicitly requires lock order operation -> connect, prevents polite idle teardown during an active operation, uses cached services, and warns against stacked retry loops: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L1-L31`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L74-L113`.

Connects use `establish_connection`; command attempts use `retry_bluetooth_connection_error(DEFAULT_ATTEMPTS)` and re-establish the link inside each retry. Databaseus errors add a `0.25 s` backoff, then force disconnect before retry: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L570-L642`.

Polls use a module-wide semaphore of two slots and a `45 s` timeout. A stale service table raises `CharacteristicMissingError`, which is logged and retried on a later advertisement without flipping availability: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L35-L59`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L150-L184`.

The controller disconnects after an update/poll, and the high-level wrapper comments that immediate release avoids holding the proxy slot for `DISCONNECT_DELAY=120`. This is the main latency/slot tradeoff: `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/device.py:L110-L176`, `nphil/ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L570-L600`, `nphil/ac-infinity-airtap-hacs/README.md:L85-L100`.

## 2.7 Tests, CI, docs, and upstream lineage

Protocol command tests check framing, payload lengths, CRCs, sequence values, and command payloads. Parse/controller tests cover response layouts and notification handling. The coordinator regression tests exercise record-bearing, record-less, malformed, and unavailable events, specifically defending the historic six-fan freeze: `nphil/ac-infinity-airtap-hacs/tests/test_protocol_commands.py:L1-L80`, `nphil/ac-infinity-airtap-hacs/tests/test_protocol_parse.py:L1-L100`, `nphil/ac-infinity-airtap-hacs/tests/test_coordinator_events.py:L1-L100`.

Validation CI runs Hassfest, HACS validation (with branding ignored for the personal fork), and pytest on Python 3.13: `nphil/ac-infinity-airtap-hacs/.github/workflows/validate.yml:L1-L40`.

The README credits `hunterjm/ac-infinity-hacs`/`ac-infinity-ble`, `mtsphere`'s AIRTAP adaptation, and later fixes: `nphil/ac-infinity-airtap-hacs/README.md:L117-L125`. The standalone upstream now keeps protocol source under `src/ac_infinity_ble/protocol.py`; an exporter should store the exact source path/commit when importing upstream protocol evidence.

## 2.8 AC Infinity flakiness root-cause audit

**Strong defenses already present**

- `connectable: true` manifest matcher and HA unavailable tracking: `manifest.json:L1-L35`, `coordinator.py:L1-L31`.
- Record-less advertisement frames still notify listeners, refresh the latest BLE route, re-arm availability, and drive poll scheduling: `coordinator.py:L186-L264`, `tests/test_coordinator_events.py:L1-L100`.
- Global two-poll semaphore, `45 s` poll deadline, cached-service invalidation, operation lock, connect lock, and bounded retry layering: `coordinator.py:L35-L59`, `coordinator.py:L150-L184`, `ac_infinity_ble/device.py:L1-L31`, `ac_infinity_ble/device.py:L570-L688`.

**Residual hazards to carry into BLE Studio requirements**

1. **The poll route check does not update the controller route.** `_needs_poll()` proves that a current connectable route exists, but `ACInfinityDevice.update_ble_device()` only receives the `service_info.device` from callbacks. A poll/command can therefore use an older proxy path until another dispatched event updates it: `coordinator.py:L132-L148`, `coordinator.py:L186-L221`, `device.py:L42-L90`. **[INFERENCE]** This is the clearest missing HA Bluetooth refresh/route handoff in the AC implementation.
2. **Notification timeout becomes a false-success path.** `_execute_command_locked()` catches the five-second timeout and returns `None`; the retry decorator sees no exception. High-level `turn_on`, `turn_off`, `set_speed`, AUTO, and threshold setters then commit local state after `await _send_command()` without testing for `None`: `ac_infinity_ble/device.py:L647-L688`, `device.py:L177-L372`. This can leave Home Assistant showing a change that was never confirmed.
3. **First-notification acceptance lacks correlation.** A stale or unrelated `0x1E 0xFF` notification can satisfy the future before being parsed as state, and the frame protocol has no parser-level sequence match: `ac_infinity_ble/device.py:L438-L464`, `ac_infinity_ble/protocol.py:L66-L125`. **[INFERENCE]** A generated client should match command opcode/expected fields, not merely “some notify arrived.”
4. **Disconnect and stop-notify awaits are unbounded.** `_execute_disconnect()` catches BLE exceptions but has no `asyncio.timeout()` around `stop_notify()` or `disconnect()`, so a wedged backend can hold a proxy slot longer than the command/poll timeout: `ac_infinity_ble/device.py:L570-L600`. **[INFERENCE]** Put a hard deadline around every teardown operation as well as connect/write/read.
5. **No MTU/chunk policy is recorded.** Current frames are small, but a generated integration should not infer that one write is always safe without an exported maximum-size/MTU observation: `ac_infinity_ble/device.py:L647-L688`, `ac_infinity_ble/protocol.py:L1-L31`.
6. **Setup waits for an advertisement before creating a usable entry.** `async_setup_entry()` raises `ConfigEntryNotReady` if no connectable `BLEDevice` exists and then waits up to 30 seconds for a parseable frame. A phone app holding the device, a proxy that sees only partial frames, or a temporarily silent advertiser can make setup/retry noisy: `__init__.py:L70-L113`, `coordinator.py:L61-L94`, `coordinator.py:L270-L290`. **[INFERENCE]** A generated integration should distinguish “known address but currently unavailable” from “never discovered.”
7. **Pacing is async but fixed.** The retry backoff uses `asyncio.sleep(0.25)`; this is non-blocking, but a capture bundle should record the delay and retry budget rather than hide it in code: `ac_infinity_ble/device.py:L600-L642`.

---

# 3. BedJet V3 (`nphil/ha-bedjet`)

## 3.1 Identity, manifest, packaging, and supported hardware

The manifest has three connectable matchers: the BedJet service UUID, a `BEDJET` local name plus legacy service UUID, and a `BEDJET*` local-name wildcard. It declares `bluetooth_adapters`, no requirements because the protocol client is vendored, `local_push`, config flow, and version `1.1.0`: `nphil/ha-bedjet/custom_components/bedjet/manifest.json:L1-L32`.

HACS metadata names BedJet, renders README, and requires Home Assistant `2026.1.0`: `nphil/ha-bedjet/hacs.json:L1-L5`. The README explicitly says the device accepts one BLE connection, stops advertising while connected, streams status notifications at roughly 4 Hz, and has no polling interval: `nphil/ha-bedjet/README.md:L1-L33`.

The README also documents proxy-slot cost: one held BedJet consumes one ESPHome proxy slot for as long as the integration holds the connection, and the mobile app cannot share it. The connection switch intentionally hands that slot back: `nphil/ha-bedjet/README.md:L1-L20`, `nphil/ha-bedjet/README.md:L33-L42`.

The current fork credits the original Robert Friedland integration, Ashley Hut, Nathan Spencer, and the current `nphil` rewrite/maintenance: `nphil/ha-bedjet/README.md:L91-L92`. The older original README is YAML/manual-configuration oriented: `robert-friedland/ha-bedjet/README.md:L1-L30`. Ashley's manifest is a minimal modern config-flow integration with no Bluetooth matcher details: `asheliahut/ha-bedjet/custom_components/bedjet/manifest.json:L1-L10`. Nathan's manifest adds the current BedJet matchers and `bluetooth_adapters`, but used `local_polling`; the current fork changed the behavior to `local_push`: `natekspencer/ha-bedjet/custom_components/bedjet/manifest.json:L1-L35`.

## 3.2 Discovery and config flow

The config flow recognizes a BedJet by the BedJet service UUID or local name prefix, creates a temporary `BedJet`, starts it, waits up to `15 s` for a callback/frame, and then always unregisters and stops the probe. Timeout maps to `cannot_connect`; unexpected exceptions map to `unknown`: `nphil/ha-bedjet/custom_components/bedjet/config_flow.py:L1-L62`.

Bluetooth discovery sets unique ID to the address, shows a confirmation step, and stores only `CONF_ADDRESS`. Manual user setup is actually a discovered-device picker; there is no free-form address field. The picker filters by BedJet identity and probes the selected advertisement before creating an entry: `nphil/ha-bedjet/custom_components/bedjet/config_flow.py:L68-L161`.

Setup requires `async_last_service_info(hass, address, connectable=True)` to have a route. If no route has ever been seen, it raises `ConfigEntryNotReady`. Once found, it constructs the persistent client from the cached `BLEDevice` and advertisement: `nphil/ha-bedjet/custom_components/bedjet/__init__.py:L32-L63`.

## 3.3 Connection lifecycle, Bluetooth routing, and callbacks

The integration forwards binary sensor, button, climate, fan, number, sensor, and switch platforms. It registers an address-only **PASSIVE** callback; each advertisement replaces the client's `BLEDevice`/advertisement/source and wakes the reconnect supervisor: `nphil/ha-bedjet/custom_components/bedjet/__init__.py:L18-L110`, `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L319-L369`.

`BedJet.start()` returns promptly, creates one connection-supervisor task and one watchdog task, and does not wait for a first frame. `hold_connection` controls whether the supervisor maintains the link. The `Bluetooth Connection` switch can disable the hold and explicitly hand the single slot back to the phone app: `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L374-L420`, `nphil/ha-bedjet/README.md:L1-L20`.

The supervisor is the single owner of connect/reconnect. It waits for advertisements, hold toggles, disconnects, or backoff expiry; connection failures use full-jitter exponential backoff from `2 s` to `120 s`; the connect attempt is bounded by `60 s`. `_connect_once()` calls `establish_connection(BleakClientWithServiceCache, ...)`, starts status notifications, publishes the client, sends optional clock sync, and launches a best-effort memory-name read: `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L92-L130`, `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L544-L632`.

A disconnect clears client/tail/bio state, cancels pending tasks, fails pending command futures, wakes the supervisor, and fires callbacks. The implementation passes `lambda: self._ble_device` to `establish_connection`, but there is no call in this lifecycle to HA's `async_ble_device_from_address(..., connectable=True)` before reconnect; the route comes from the last advertisement callback: `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L598-L676`, `nphil/ha-bedjet/custom_components/bedjet/__init__.py:L319-L338`.

## 3.4 BLE/GATT protocol and command semantics

The pure codec documents a 20-byte status notification on characteristic `...2000`, a plain-read tail of 11 bytes from the same characteristic, command characteristic `...2004` using write-without-response, and optional `...2006` bio-data reads. It records the exact status offsets, validation ranges, tail flags, and command opcode families: `nphil/ha-bedjet/custom_components/bedjet/pybedjet/codec.py:L1-L150`.

Status frames use format/type bytes, mode, actual/target/ambient temperature steps, fan step, runtime, max runtime, per-mode temperature bounds, turbo time, and shutdown reason. A frame is accepted only after numeric validation; malformed frames are rejected while the last known-good state remains: `nphil/ha-bedjet/custom_components/bedjet/pybedjet/codec.py:L300-L381`.

The 11-byte tail supplies dual-zone, update phase, connection-test, LED, units, beeper, bio-sequence, and notification fields. `merge_tail()` validates minimum length and notification enum values: `nphil/ha-bedjet/custom_components/bedjet/pybedjet/codec.py:L382-L432`.

Commands are direct `[opcode,payload]` bytes without a checksum. The codec validates runtime, temperature, fan percentage, clock, and bio-data ranges; commands include button, runtime, temperature, status, fan, clock, and GET_BIO: `nphil/ha-bedjet/custom_components/bedjet/pybedjet/codec.py:L433-L482`.

No MTU negotiation or chunking is present. Current commands are only a few bytes, which is consistent with the protocol description, but the export schema must preserve this as an observed fact rather than silently assume every future command fits: `nphil/ha-bedjet/custom_components/bedjet/pybedjet/codec.py:L1-L150`, `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L598-L632`.

## 3.5 Coordinator, availability, state updates, and entity model

The coordinator is push-only: it has no polling interval and pushes decoded states using `async_set_updated_data`. pybedjet already rate-limits continuous-only changes to two seconds while publishing meaningful mode/fan/target/notification/tail changes immediately: `nphil/ha-bedjet/custom_components/bedjet/coordinator.py:L1-L57`, `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L677-L710`.

Entities delegate availability to `BedJet.available`, which requires both a connected client and a valid frame within `300 s`. The entity device info contains BedJet manufacturer/model and Bluetooth address; command failures become HA errors: `nphil/ha-bedjet/custom_components/bedjet/entity.py:L1-L60`, `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L260-L289`.

The climate entity exposes off/heat/cool/dry, target temperature `19`–`43` °C, 5–100% fan steps, and Turbo/Extended Heat/memory presets; the fan entity exposes the same speed range; number is runtime remaining; sensors cover ambient/outlet temperatures and notification plus diagnostics; buttons acknowledge/sync/update; switches control LED/beeper; binary sensors cover connection test, dual zone, and units setup: `nphil/ha-bedjet/custom_components/bedjet/climate.py:L15-L130`, `nphil/ha-bedjet/custom_components/bedjet/fan.py:L15-L80`, `nphil/ha-bedjet/custom_components/bedjet/number.py:L15-L70`, `nphil/ha-bedjet/custom_components/bedjet/sensor.py:L15-L135`, `nphil/ha-bedjet/custom_components/bedjet/button.py:L15-L75`, `nphil/ha-bedjet/custom_components/bedjet/switch.py:L15-L80`, `nphil/ha-bedjet/custom_components/bedjet/binary_sensor.py:L15-L95`.

The watchdog has three tiers: after `60 s` without a valid frame it sends a best-effort status probe; at `300 s` it reports unavailable; at `900 s` it disconnects and reconnects. Watchdog ticks every `10 s`: `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L92-L130`, `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L743-L780`.

## 3.6 Reliability, speed, and proxy-slot tactics

Persistent notification streaming avoids periodic connect/poll overhead and gives immediate updates. Commands wait for a state predicate with a five-second timeout and raise a `BedJetCommandError` if no confirming state arrives. There is no optimistic entity echo: `nphil/ha-bedjet/README.md:L22-L33`, `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L374-L420`, `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L450-L550`.

The supervisor uses one reconnect loop with jittered backoff rather than many competing reconnect tasks. The watchdog does not await a five-second confirmation for its probe; it uses a fire-and-forget raw write so escalation is not delayed: `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L544-L590`, `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L743-L780`.

Frame validation and last-known-good retention are strong defenses against a corrupt notification flapping every entity unavailable. Tail reads are coalesced so only one tail task runs at a time and are refreshed after a command or when older than `15 s`: `nphil/ha-bedjet/custom_components/bedjet/pybedjet/codec.py:L300-L432`, `nphil/ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L677-L742`.

## 3.7 Tests, CI, docs, and upstream lineage

Codec tests use captured status/tail frames and command encodings. Connection tests cover hold-connection behavior, reconnect-on-advertisement, command confirmation/timeout, watchdog tiers, tail staleness, and listener fan-out; fake BLE clients and controllable clocks avoid real multi-second sleeps: `nphil/ha-bedjet/tests/test_codec.py:L1-L80`, `nphil/ha-bedjet/tests/test_connection.py:L1-L100`.

CI runs pytest on Python 3.13 plus Hassfest and HACS validation, with branding ignored for the personal fork: `nphil/ha-bedjet/.github/workflows/tests.yml:L1-L22`, `nphil/ha-bedjet/.github/workflows/validate.yaml:L1-L25`.

## 3.8 BedJet flakiness root-cause audit

**Strong defenses already present**

- Single reconnect supervisor, explicit single-slot/hold switch, full-jitter backoff, connect timeout, and disconnect callback state cleanup: `pybedjet/__init__.py:L1-L45`, `pybedjet/__init__.py:L544-L676`.
- Honest command confirmation, pending-future cleanup, frame validation, last-known-good state, watchdog unavailable/reconnect tiers: `pybedjet/__init__.py:L450-L550`, `pybedjet/__init__.py:L677-L780`, `codec.py:L300-L432`.
- Push coordinator and meaningful-change throttling avoid unnecessary polling/rendering: `coordinator.py:L1-L57`, `pybedjet/__init__.py:L677-L710`.

**Residual hazards to carry into BLE Studio requirements**

1. **No HA route refresh before reconnect.** Setup gets one cached connectable route; later advertisements replace `_ble_device`, but `_connect_once()` passes that object directly to `establish_connection` rather than asking HA for the freshest connectable path. The address-only callback also does not explicitly constrain connectability: `custom_components/bedjet/__init__.py:L32-L86`, `pybedjet/__init__.py:L319-L338`, `pybedjet/__init__.py:L598-L632`. **[INFERENCE]** A stale or nonconnectable proxy route can be selected after topology changes.
2. **There is no command-operation lock.** `_run_command()` appends predicate/future tuples to `_pending`, writes immediately, and waits. Multiple service calls can write concurrently; all pending predicates are checked against each incoming state. The tail read, bio read, watchdog probe, and command writes also have no shared GATT queue: `pybedjet/__init__.py:L450-L550`, `pybedjet/__init__.py:L711-L805`. **[INFERENCE]** This is the most direct serialization hazard in the reference set.
3. **Background GATT operations are not all bounded.** Command confirmation has `5 s`, and connect has `60 s`, but tail `read_gatt_char`, bio write/read, stop-notify, and disconnect have no per-operation `asyncio.timeout`. They catch selected BLE/OSError/EOF exceptions but do not independently bound a backend that remains stuck: `pybedjet/__init__.py:L650-L676`, `pybedjet/__init__.py:L711-L742`, `pybedjet/__init__.py:L781-L805`.
4. **Command timeout does not reset the link.** A timeout raises to the caller and removes the pending predicate, but `_run_command()` does not force a disconnect. A client may remain connected with an unknown command outcome until the watchdog or a later disconnect: `pybedjet/__init__.py:L480-L524`, `pybedjet/__init__.py:L743-L780`. **[INFERENCE]** Generated clients should reset a session after a GATT timeout unless the capture proves the device remains safely usable.
5. **The single-slot rule makes persistent connection a deliberate operational tradeoff.** It is excellent for push latency but blocks the vendor app and consumes a proxy slot; the connection switch is not optional UX polish but a core recovery/control mechanism: `README.md:L1-L42`.
6. **Setup/probe needs an advertisement.** Config flow has a 15-second probe and setup raises `ConfigEntryNotReady` if HA has never seen the address. There is no manual-address fallback, so a phone-held device cannot be configured until it advertises again: `config_flow.py:L25-L62`, `config_flow.py:L98-L161`, `__init__.py:L32-L63`.
7. **No MTU policy is needed for current tiny commands, but that fact is not represented as a negotiated capability.** Export command size and observed write properties explicitly: `codec.py:L433-L482`, `pybedjet/__init__.py:L598-L632`.
8. **No blocking calls were found in the inspected BLE lifecycle.** Sleeps are `asyncio.sleep`, and the frame callback is synchronous only for decode/state mutation; generated code should keep callbacks short and move GATT work to tracked tasks: `pybedjet/__init__.py:L677-L805`.

---

# 4. Cross-integration synthesis for BLE Studio

## 4.1 Reliability comparison

| Audit item | Fluval | AC Infinity | BedJet | Exported design decision |
|---|---|---|---|---|
| `connectable: true` manifest matcher | Not explicit | Explicit | Explicit | Store matcher objects and effective connectability; do not infer from a successful scan |
| HA route refresh before connect | Yes, provider/`async_ble_device_from_address(..., connectable=True)` plus callback | Setup and poll gate check HA, but controller may retain stale route | Setup cache + advertisement replacement; no direct HA lookup per reconnect | Record every route source and refresh policy |
| `establish_connection` | Yes, bounded, retries | Yes, cached services and retry decorator | Yes, single supervisor, cached services | Store connector class/cache/retry parameters |
| GATT command serialization | Device transaction + client command lock; heartbeat queued path is a residual risk | Operation lock + connect lock | No shared operation lock | Generate one per-device queue/lock covering command, read, tail, watchdog, teardown |
| Response correlation | Expected decoded state / explicit re-read | First waiting notification; no sequence correlation | State predicates; concurrent pending predicates possible | Export predicate, correlation bytes, sequence/opcode, and confidence |
| GATT deadlines | Broad hard ceilings including teardown | Command/poll bounded; teardown gaps | Connect/command bounded; tail/bio/teardown gaps | Put timeout on every connect/read/write/notify/stop/disconnect |
| Retry/backoff | Establish retries, write retries, fixed pacing | Establish/decorator retries, small DBus backoff | Supervisor full-jitter reconnect | One retry layer, bounded, jittered for reconnect; record each attempt |
| Availability | Custom fixed 300-second recent activity | HA Bluetooth unavailable tracker | Connected + valid frame age, watchdog tiers | Keep raw advertisement age, frame age, connection state, and HA availability separately |
| MTU/chunking | Explicit MTU/max-write chunking | None | None | Capture negotiated MTU, write properties, maximum tested payload, chunk rules |
| ESPHome slot economics | Persistent/finite option, no global cap | Global max-two poll semaphore, immediate release | Single held slot and handoff switch | Export slot count/limits and use global semaphore for connect-on-demand fleets |
| Update source | Push plus reads/notifications | Ads + notifications + ad-driven polls | Notify stream + occasional tail read | Preserve raw captures and decoded state together |

## 4.2 Consolidated flakiness root causes

1. **Stale or wrong route selection.** An address is not a route. HA may see one device through multiple local/proxy scanners, and a route that was valid for discovery may be stale at connect time. Fluval refreshes the route best; AC checks availability but does not necessarily assign the fresh result; BedJet relies on the last advertisement-fed `BLEDevice`: `fluvalble/custom_components/fluvalble/core/device.py:L2838-L2925`, `ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L132-L148`, `ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L598-L632`.
2. **Concurrent GATT operations.** Notifications, command writes, background tail reads, watchdog probes, and disconnects can interleave. AC demonstrates a good operation/connect lock; BedJet demonstrates the failure mode where a list of pending predicates is not serialization: `ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L1-L31`, `ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L450-L550`, `ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L711-L805`.
3. **“Any notification” mistaken for confirmation.** AC explicitly resolves a waiter before unsolicited parsing and has no sequence match; BedJet predicates can be satisfied by overlapping state changes if multiple operations are in flight: `ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L438-L464`, `ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/protocol.py:L66-L125`, `ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L480-L550`.
4. **Timeouts that do not poison/reset a bad link.** AC returns `None` on command timeout and mutates optimistic state; BedJet raises but leaves the connection in place; Fluval resets on bounded transaction timeout. Generated code should define this policy explicitly: `ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L647-L688`, `ac-infinity-airtap-hacs/custom_components/ac_infinity/device.py:L177-L372`, `ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L480-L524`, `fluvalble/custom_components/fluvalble/core/device.py:L469-L540`.
5. **Unbounded teardown/read operations.** A timeout around a command is not enough if `disconnect`, `stop_notify`, tail read, or bio read can remain blocked and consume a proxy slot: `ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L570-L600`, `ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L650-L805`.
6. **Incorrect availability source.** Poll failures should not necessarily mean unavailable when advertisements are healthy (AC), while a connected-but-silent notify stream should become unavailable (BedJet). Fluval's fixed recent-activity window is useful but mixes command activity and advertisements: `ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L1-L31`, `ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L260-L289`, `fluvalble/custom_components/fluvalble/core/device.py:L540-L603`.
7. **Proxy-slot stampedes.** Persistent sessions reserve slots; simultaneous connect-on-demand polls can exhaust a proxy. AC's global semaphore is the clearest reusable pattern; Fluval and BedJet require user/configuration discipline: `ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L35-L59`, `fluvalble/README.md:L120-L155`, `ha-bedjet/README.md:L1-L42`.
8. **Discovery/setup that requires a currently advertising device.** A device held by the vendor app may be known but temporarily unavailable. Fluval has a manual address route; AC and BedJet rely on discovered entries: `fluvalble/custom_components/fluvalble/config_flow.py:L332-L408`, `ac-infinity-airtap-hacs/custom_components/ac_infinity/config_flow.py:L75-L162`, `ha-bedjet/custom_components/bedjet/config_flow.py:L98-L161`.
9. **Unrecorded wire-size and pacing assumptions.** Fluval exports MTU/chunk behavior; AC and BedJet send one frame because current commands are small. A generated implementation must preserve negotiated MTU, properties, maximum tested frame, inter-command spacing, and response latency as evidence rather than hard-coded folklore: `fluvalble/custom_components/fluvalble/core/client.py:L625-L678`, `ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/protocol.py:L1-L31`, `ha-bedjet/custom_components/bedjet/pybedjet/codec.py:L433-L482`.

## 4.3 Reliability-first generated integration template

The following is a proposal for code generated from a BLE Studio export, not a claim that any one reference implements every item.

### A. Separate evidence, protocol hypotheses, and HA behavior

- Store raw advertisements, service/characteristic discovery, every command/response capture, timestamps, route source, and error outcomes unchanged.
- Store decoded fields with confidence and a source capture reference.
- Store hypotheses separately from verified commands; never generate a write solely because a byte pattern looks plausible.
- Generate entity commands only for catalogue entries marked user-verified or otherwise explicitly approved.

The need for this separation is visible in Fluval's strict product-ID gate and multiple protocol profiles, AC's “only verified command builders” rule, and BedJet's pure codec/provenance split: `fluvalble/custom_components/fluvalble/core/discovery.py:L1-L45`, `ac-infinity-airtap-hacs/README.md:L1-L50`, `ha-bedjet/custom_components/bedjet/pybedjet/codec.py:L1-L45`.

### B. Use one per-device connection supervisor

Recommended state machine:

```text
SEEN -> ROUTE_READY -> CONNECTING -> SERVICES_READY -> SUBSCRIBED
  ^         |             |                |                |
  |         +-------------+----------------+----------------+
  |                         timeout/error -> DEGRADED
  +---------------- advertisement / route refresh / jittered retry

SERVICES_READY/SUBSCRIBED -> STOPPING -> STOPPED
```

- One task owns connect/reconnect; one stop path cancels and joins all background tasks.
- Before each new connect, call HA's connectable route lookup (`async_ble_device_from_address(..., connectable=True)` or equivalent), then pass a `ble_device_callback` that can refresh again at the connector boundary. Fluval demonstrates the desired route behavior: `fluvalble/custom_components/fluvalble/core/device.py:L2838-L2925`.
- Use `establish_connection` with service-cache support only when the export identifies a stable profile; clear cache and retry after missing-characteristic evidence, as AC does: `ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L364-L430`.
- Expose an explicit hold/persistent setting only when the export proves the device can/should remain connected. For a single-slot device, generate a handoff control like BedJet's: `ha-bedjet/README.md:L1-L42`.

### C. Serialize all GATT work

Use one operation queue or lock for:

- connect/session initialization;
- command writes and response reads;
- explicit state reads;
- notification subscription changes;
- tail/secondary reads;
- watchdog probes;
- polite and forced teardown.

A separate connection lock may protect connect/disconnect, but lock order must be documented and consistent. AC's operation-lock/connect-lock model is the best reference: `ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L1-L31`.

Do not let a synchronous notification callback perform GATT I/O. Decode quickly, enqueue follow-up reads, and track/cancel those tasks on disconnect, as BedJet does for tail scheduling but should extend to all GATT operations: `ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L677-L742`.

### D. Make every operation bounded and honest

- Bound connect, service resolution, start/stop notify, read, write, response wait, and disconnect.
- After a write/read timeout, mark the session suspect and reset/disconnect before retrying, unless the export proves a safe continuation.
- Never convert a response timeout into `None` that the entity layer can mistake for success. AC's false-success path is specifically what the generator must avoid: `ac-infinity-airtap-hacs/custom_components/ac_infinity/ac_infinity_ble/device.py:L647-L688`, `ac-infinity-airtap-hacs/custom_components/ac_infinity/device.py:L177-L372`.
- Retry one layer only: reconnect/backoff at the connection boundary; command retry only when the command is idempotent or the capture proves the device did not apply the first attempt. Record attempt number and outcome.
- Use full-jitter exponential backoff for reconnect storms, bounded by a configurable maximum, as BedJet does: `ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L130-L180`, `ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L544-L590`.

### E. Correlate confirmations

For each generated command, carry:

- write characteristic and write mode (`response` vs no-response);
- expected response characteristic(s);
- opcode/sequence/transaction correlation if present;
- a field predicate or exact response pattern;
- acceptable latency and timeout;
- whether a stale state frame can satisfy the predicate;
- whether the command is idempotent and retryable.

A generic “next notification” rule is unsafe. Fluval's expected-state verification and explicit re-read are preferable: `fluvalble/custom_components/fluvalble/core/client.py:L680-L913`, `fluvalble/custom_components/fluvalble/core/device.py:L2563-L2667`.

### F. Model availability as separate signals

Generate separate diagnostic values for:

- `advertisement_seen_at` and age;
- `valid_frame_at` and age;
- current GATT connected/subscribed state;
- HA scanner/proxy availability;
- last connect route/source;
- last command confirmation;
- last error and retry count.

Use HA's Bluetooth unavailable tracker for scanner absence where possible. Do not mark unavailable only because a nonessential poll failed; AC's rationale is sound: `ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L1-L31`, `ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L250-L264`.

For a notify-stream device, require a fresh valid frame in addition to `connected`, as BedJet does: `ha-bedjet/custom_components/bedjet/pybedjet/__init__.py:L260-L289`.

### G. Preserve MTU, write properties, and pacing

- Capture characteristic properties and negotiated MTU.
- Prefer `write-without-response` only when the characteristic advertises it and captures show it works.
- Chunk at the observed maximum write-without-response size or `MTU-3`; include inter-chunk delay and frame-reassembly evidence. Fluval demonstrates this: `fluvalble/custom_components/fluvalble/core/client.py:L625-L678`.
- Encode command gaps, post-write settle delay, notification latency, and retry delay as data from captures, not magic numbers.
- Keep all waits asynchronous; do not call blocking BLE APIs from HA's event loop.

### H. Budget ESPHome proxy slots

- Export per-proxy observed/declared slot count.
- Use one global semaphore for connect-on-demand polls across all config entries.
- Reserve headroom for user commands; do not let periodic polls consume every slot. AC uses two poll slots for typical three-slot proxies: `ac-infinity-airtap-hacs/custom_components/ac_infinity/coordinator.py:L35-L59`.
- For persistent devices, make slot ownership visible and user-controllable; BedJet's connection switch is a direct example: `ha-bedjet/README.md:L1-L42`.

### I. Generate diagnostics and regression fixtures

Every bundle should generate:

- a redacted raw-capture fixture set;
- a pure decoder/encoder test fixture;
- a connection fake test for timeout/disconnect/reconnect;
- a command-confirmation test that fails if an unrelated notification is accepted;
- an availability transition test;
- a proxy-slot concurrency test;
- a route-refresh test proving a new scanner/proxy is selected.

The references show why these are behavior tests rather than source-shape tests: AC's record-less event regression, BedJet's fake-clock lifecycle tests, and Fluval's protocol/connection-policy tests: `ac-infinity-airtap-hacs/tests/test_coordinator_events.py:L1-L100`, `ha-bedjet/tests/test_connection.py:L1-L100`, `fluvalble/tests/test_connection_policy.py:L1-L40`.

---

# 5. BLE Studio export schema proposal

## 5.1 Required export content

The bundle should contain these top-level groups:

1. **Identity/discovery:** address, address type, local/advertised names, connectability, appearance, manufacturer records keyed by company ID, service-data records keyed by UUID, advertised service UUIDs, RSSI, scanner sources, and identity confidence.
2. **GATT inventory:** services, characteristics, descriptors, handles where available, properties, notification/indication capability, write-without-response size, and CCCD evidence.
3. **Connection facts:** persistent versus connect-on-demand behavior, single-connection/idle-disconnect rules, app conflict, pairing/bonding, negotiated MTU/PHY/interval if observed, route/proxy IDs, slot counts, and connect/disconnect attempt outcomes.
4. **Raw captures:** timestamp plus monotonic timestamp, scanner/source, direction, characteristic, payload bytes, RSSI, latency, inter-command gap, and whether a human verified the action.
5. **Protocol model:** framing, length/endianness, sequence, checksum/CRC, encryption/key material, handshake/session initialization, response/notification semantics, chunking, and confidence for every field.
6. **Command catalogue:** human label, opcode/bytes, characteristic, write mode, parameter schema/ranges, expected response, correlation predicate, timeout/gap/retry policy, idempotence, and verification status.
7. **Notification/state catalogue:** characteristic, payload samples, decoded fields with offsets/scales/enums, validity checks, partial/tail behavior, and confidence.
8. **Snapshots/availability:** decoded state over time, advertisement/frame ages, connect/disconnect transitions, errors, and route changes.
9. **HA entity map:** generated platform, key, unit, bounds, enabled/category defaults, command mapping, availability policy, and device-registry identity.
10. **Source references:** repository/path/line or capture IDs for every imported claim, plus upstream ancestry and bundle tool version.

These fields combine complementary lessons: Fluval's multi-profile/encryption/MTU/route evidence (`fluvalble/core/client.py:L44-L89`, `fluvalble/core/client.py:L625-L678`), AC's CRC/sequence/proxy-slot/command model (`ac-infinity-airtap-hacs/ac_infinity_ble/protocol.py:L1-L125`, `ac-infinity-airtap-hacs/coordinator.py:L35-L59`), and BedJet's single-slot stream/tail/watchdog/confirmation model (`ha-bedjet/pybedjet/codec.py:L1-L150`, `ha-bedjet/pybedjet/__init__.py:L92-L130`).

## 5.2 Suggested source-reference convention

Every generated field should point to either a raw capture or a source reference such as:

```text
{
  "source_references": [
    {
      "id": "fluval-client-mtu",
      "kind": "repository",
      "integration": "fluvalble",
      "path": "custom_components/fluvalble/core/client.py",
      "line_start": 625,
      "line_end": 678,
      "claim": "raw modern writes use MTU/max-write chunking"
    }
  ]
}
```

Do not collapse a field with conflicting evidence into one unqualified value. Keep `value`, `confidence`, `hypothesis`, `observed_in`, and `verified` separately.

## 5.3 JSON Schema (draft 2020-12)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ble-studio.local/schema/ble-evidence-bundle-2020-12.json",
  "title": "BLE Studio evidence bundle",
  "description": "Raw BLE evidence and derived protocol facts used to generate a Home Assistant custom integration.",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "schema_version",
    "bundle_id",
    "captured_at",
    "device",
    "gatt",
    "connection",
    "captures",
    "commands",
    "notifications",
    "snapshots",
    "entities",
    "source_references"
  ],
  "properties": {
    "schema_version": {
      "type": "string",
      "const": "1.0"
    },
    "bundle_id": {
      "type": "string",
      "minLength": 1
    },
    "captured_at": {
      "type": "string",
      "format": "date-time"
    },
    "tool_version": {
      "type": "string"
    },
    "device": {
      "$ref": "#/$defs/device"
    },
    "gatt": {
      "$ref": "#/$defs/gatt"
    },
    "connection": {
      "$ref": "#/$defs/connection"
    },
    "captures": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/capture"
      }
    },
    "commands": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/command"
      }
    },
    "notifications": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/notification"
      }
    },
    "snapshots": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/snapshot"
      }
    },
    "entities": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/entity"
      }
    },
    "protocol": {
      "$ref": "#/$defs/protocol"
    },
    "source_references": {
      "type": "array",
      "items": {
        "$ref": "#/$defs/sourceReference"
      }
    },
    "notes": {
      "type": "array",
      "items": {
        "type": "string"
      }
    }
  },
  "$defs": {
    "confidence": {
      "type": "number",
      "minimum": 0,
      "maximum": 1
    },
    "hex": {
      "type": "string",
      "pattern": "^(?:[0-9A-Fa-f]{2})*$"
    },
    "uuid": {
      "type": "string",
      "minLength": 1
    },
    "address": {
      "type": "string",
      "pattern": "^[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}$"
    },
    "evidence": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "confidence": {
          "$ref": "#/$defs/confidence"
        },
        "verified": {
          "type": "boolean"
        },
        "hypothesis": {
          "type": "boolean"
        },
        "observed_in": {
          "type": "array",
          "items": {
            "type": "string"
          }
        },
        "source_reference_ids": {
          "type": "array",
          "items": {
            "type": "string"
          }
        },
        "notes": {
          "type": "string"
        }
      }
    },
    "device": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "address",
        "address_type",
        "connectable",
        "manufacturer_data",
        "service_data",
        "service_uuids"
      ],
      "properties": {
        "address": {
          "$ref": "#/$defs/address"
        },
        "address_type": {
          "type": "string"
        },
        "local_name": {
          "type": ["string", "null"]
        },
        "advertised_name": {
          "type": ["string", "null"]
        },
        "appearance": {
          "type": ["integer", "null"]
        },
        "connectable": {
          "type": "boolean"
        },
        "rssi": {
          "type": ["integer", "null"]
        },
        "manufacturer_data": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["company_id", "data"],
            "properties": {
              "company_id": {"type": "integer", "minimum": 0},
              "data": {"$ref": "#/$defs/hex"},
              "evidence": {"$ref": "#/$defs/evidence"}
            }
          }
        },
        "service_data": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["uuid", "data"],
            "properties": {
              "uuid": {"$ref": "#/$defs/uuid"},
              "data": {"$ref": "#/$defs/hex"},
              "evidence": {"$ref": "#/$defs/evidence"}
            }
          }
        },
        "service_uuids": {
          "type": "array",
          "items": {"$ref": "#/$defs/uuid"}
        },
        "scanner_sources": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["source"],
            "properties": {
              "source": {"type": "string"},
              "name": {"type": ["string", "null"]},
              "scanner_type": {"type": ["string", "null"]},
              "first_seen": {"type": ["string", "null"], "format": "date-time"},
              "last_seen": {"type": ["string", "null"], "format": "date-time"},
              "rssi": {"type": ["integer", "null"]}
            }
          }
        },
        "identity": {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "vendor": {"type": ["string", "null"]},
            "model": {"type": ["string", "null"]},
            "product_id": {"type": ["string", "integer", "null"]},
            "confidence": {"$ref": "#/$defs/confidence"},
            "source_reference_ids": {"type": "array", "items": {"type": "string"}}
          }
        }
      }
    },
    "gatt": {
      "type": "object",
      "additionalProperties": false,
      "required": ["services"],
      "properties": {
        "services": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["uuid", "characteristics"],
            "properties": {
              "uuid": {"$ref": "#/$defs/uuid"},
              "handle": {"type": ["integer", "null"], "minimum": 0},
              "characteristics": {
                "type": "array",
                "items": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["uuid", "properties"],
                  "properties": {
                    "uuid": {"$ref": "#/$defs/uuid"},
                    "handle": {"type": ["integer", "null"], "minimum": 0},
                    "properties": {"type": "array", "items": {"type": "string"}},
                    "max_write_without_response_size": {"type": ["integer", "null"], "minimum": 0},
                    "cccd_handles": {"type": "array", "items": {"type": "integer", "minimum": 0}},
                    "descriptors": {"type": "array", "items": {"type": "object", "additionalProperties": false, "properties": {"uuid": {"$ref": "#/$defs/uuid"}, "handle": {"type": ["integer", "null"], "minimum": 0}, "value": {"$ref": "#/$defs/hex"}}}},
                    "evidence": {"$ref": "#/$defs/evidence"}
                  }
                }
              }
            }
          }
        },
        "negotiated_mtu": {"type": ["integer", "null"], "minimum": 0},
        "phy": {"type": ["string", "null"]},
        "connection_interval_ms": {"type": ["number", "null"], "minimum": 0}
      }
    },
    "connection": {
      "type": "object",
      "additionalProperties": false,
      "required": ["model", "attempts"],
      "properties": {
        "model": {"type": "string", "enum": ["persistent", "connect_on_demand", "advertisement_triggered", "unknown"]},
        "single_connection": {"type": "boolean"},
        "stops_advertising_while_connected": {"type": "boolean"},
        "idle_disconnect_seconds": {"type": ["number", "null"], "minimum": 0},
        "pairing_required": {"type": ["boolean", "null"]},
        "bonding_required": {"type": ["boolean", "null"]},
        "vendor_app_conflict": {"type": ["boolean", "null"]},
        "refresh_route_before_connect": {"type": "boolean"},
        "establish_connection": {"type": "boolean"},
        "service_cache": {"type": ["boolean", "null"]},
        "proxy_slots": {"type": ["integer", "null"], "minimum": 0},
        "global_poll_slots": {"type": ["integer", "null"], "minimum": 0},
        "attempts": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["started_at", "outcome"],
            "properties": {
              "started_at": {"type": "string", "format": "date-time"},
              "ended_at": {"type": ["string", "null"], "format": "date-time"},
              "route_source": {"type": ["string", "null"]},
              "scanner_source": {"type": ["string", "null"]},
              "outcome": {"type": "string", "enum": ["connected", "timeout", "failed", "cancelled", "disconnected"]},
              "error_type": {"type": ["string", "null"]},
              "error": {"type": ["string", "null"]},
              "backoff_seconds": {"type": ["number", "null"], "minimum": 0}
            }
          }
        },
        "timeouts_seconds": {
          "type": "object",
          "additionalProperties": {"type": "number", "minimum": 0}
        },
        "pacing_seconds": {
          "type": "object",
          "additionalProperties": {"type": "number", "minimum": 0}
        }
      }
    },
    "capture": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "timestamp", "direction", "payload"],
      "properties": {
        "id": {"type": "string"},
        "timestamp": {"type": "string", "format": "date-time"},
        "monotonic_seconds": {"type": ["number", "null"]},
        "direction": {"type": "string", "enum": ["advertisement", "notification", "read", "write", "indication", "connect", "disconnect", "error"]},
        "characteristic_uuid": {"type": ["string", "null"]},
        "service_uuid": {"type": ["string", "null"]},
        "scanner_source": {"type": ["string", "null"]},
        "payload": {"$ref": "#/$defs/hex"},
        "rssi": {"type": ["integer", "null"]},
        "latency_ms": {"type": ["number", "null"], "minimum": 0},
        "inter_command_gap_ms": {"type": ["number", "null"], "minimum": 0},
        "verified_by_user": {"type": "boolean"},
        "notes": {"type": ["string", "null"]}
      }
    },
    "field": {
      "type": "object",
      "additionalProperties": false,
      "required": ["name"],
      "properties": {
        "name": {"type": "string"},
        "offset": {"type": ["integer", "null"], "minimum": 0},
        "length": {"type": ["integer", "null"], "minimum": 0},
        "type": {"type": ["string", "null"]},
        "endianness": {"type": ["string", "null"], "enum": ["little", "big", "none", null]},
        "signed": {"type": ["boolean", "null"]},
        "scale": {"type": ["number", "null"]},
        "bias": {"type": ["number", "null"]},
        "unit": {"type": ["string", "null"]},
        "enum": {"type": "object", "additionalProperties": {"type": ["string", "number", "boolean", "null"]}},
        "valid_range": {"type": "array", "items": {"type": "number"}, "minItems": 2, "maxItems": 2},
        "evidence": {"$ref": "#/$defs/evidence"}
      }
    },
    "command": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "label", "write", "confirmation", "verified"],
      "properties": {
        "id": {"type": "string"},
        "label": {"type": "string"},
        "opcode": {"type": ["integer", "null"], "minimum": 0, "maximum": 255},
        "write": {
          "type": "object",
          "additionalProperties": false,
          "required": ["characteristic_uuid", "payload_template", "response_mode"],
          "properties": {
            "characteristic_uuid": {"$ref": "#/$defs/uuid"},
            "payload_template": {"$ref": "#/$defs/hex"},
            "response_mode": {"type": "string", "enum": ["with_response", "without_response"]},
            "parameters": {"type": "array", "items": {"$ref": "#/$defs/field"}}
          }
        },
        "confirmation": {
          "type": "object",
          "additionalProperties": false,
          "required": ["mode"],
          "properties": {
            "mode": {"type": "string", "enum": ["none", "notification", "readback", "advertisement", "state_predicate", "sequence_match"]},
            "characteristic_uuids": {"type": "array", "items": {"$ref": "#/$defs/uuid"}},
            "expected_payload": {"$ref": "#/$defs/hex"},
            "predicate": {"type": ["string", "null"]},
            "correlation_fields": {"type": "array", "items": {"type": "string"}},
            "timeout_seconds": {"type": ["number", "null"], "minimum": 0},
            "post_write_delay_seconds": {"type": ["number", "null"], "minimum": 0}
          }
        },
        "retry": {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "max_attempts": {"type": "integer", "minimum": 0},
            "retryable": {"type": "boolean"},
            "idempotent": {"type": "boolean"},
            "delay_seconds": {"type": "number", "minimum": 0}
          }
        },
        "verified": {"type": "boolean"},
        "evidence": {"$ref": "#/$defs/evidence"},
        "notes": {"type": ["string", "null"]}
      }
    },
    "notification": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "characteristic_uuid", "fields"],
      "properties": {
        "id": {"type": "string"},
        "characteristic_uuid": {"$ref": "#/$defs/uuid"},
        "framing": {"type": ["string", "null"]},
        "minimum_length": {"type": ["integer", "null"], "minimum": 0},
        "fields": {"type": "array", "items": {"$ref": "#/$defs/field"}},
        "validity_checks": {"type": "array", "items": {"type": "string"}},
        "partial_or_tail": {"type": "boolean"},
        "tail_characteristic_uuid": {"type": ["string", "null"]},
        "evidence": {"$ref": "#/$defs/evidence"}
      }
    },
    "snapshot": {
      "type": "object",
      "additionalProperties": false,
      "required": ["timestamp", "source"],
      "properties": {
        "timestamp": {"type": "string", "format": "date-time"},
        "monotonic_seconds": {"type": ["number", "null"]},
        "source": {"type": "string", "enum": ["advertisement", "notification", "read", "write_confirmation", "diagnostic"]},
        "state": {"type": "object", "additionalProperties": true},
        "advertisement_age_seconds": {"type": ["number", "null"], "minimum": 0},
        "valid_frame_age_seconds": {"type": ["number", "null"], "minimum": 0},
        "connected": {"type": ["boolean", "null"]},
        "available": {"type": ["boolean", "null"]},
        "route_source": {"type": ["string", "null"]},
        "error": {"type": ["string", "null"]}
      }
    },
    "entity": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "platform", "key", "availability_policy"],
      "properties": {
        "id": {"type": "string"},
        "platform": {"type": "string", "enum": ["binary_sensor", "button", "climate", "fan", "light", "number", "select", "sensor", "switch"]},
        "key": {"type": "string"},
        "name": {"type": ["string", "null"]},
        "unit": {"type": ["string", "null"]},
        "device_class": {"type": ["string", "null"]},
        "entity_category": {"type": ["string", "null"]},
        "enabled_by_default": {"type": ["boolean", "null"]},
        "read_fields": {"type": "array", "items": {"type": "string"}},
        "command_ids": {"type": "array", "items": {"type": "string"}},
        "availability_policy": {"type": "string", "enum": ["ha_bluetooth", "connected_and_fresh_frame", "recent_activity", "always", "custom"]},
        "min": {"type": ["number", "null"]},
        "max": {"type": ["number", "null"]},
        "step": {"type": ["number", "null" ]}
      }
    },
    "protocol": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "transports": {"type": "array", "items": {"type": "string"}},
        "framing": {"type": ["string", "null"]},
        "header_hex": {"type": ["string", "null"]},
        "length_offset": {"type": ["integer", "null"], "minimum": 0},
        "length_endianness": {"type": ["string", "null"], "enum": ["little", "big", "none", null]},
        "sequence_offset": {"type": ["integer", "null"], "minimum": 0},
        "sequence_endianness": {"type": ["string", "null"], "enum": ["little", "big", "none", null]},
        "checksum": {"type": ["string", "null"]},
        "checksum_range": {"type": ["string", "null"]},
        "encryption": {"type": ["string", "null"]},
        "session_initialization": {"type": "array", "items": {"type": "string"}},
        "mtu_chunking": {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "required": {"type": "boolean"},
            "size_rule": {"type": ["string", "null"]},
            "inter_chunk_delay_seconds": {"type": ["number", "null"], "minimum": 0},
            "reassembly_observed": {"type": ["boolean", "null"]}
          }
        },
        "evidence": {"$ref": "#/$defs/evidence"}
      }
    },
    "sourceReference": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "kind", "claim"],
      "properties": {
        "id": {"type": "string"},
        "kind": {"type": "string", "enum": ["repository", "capture", "upstream", "documentation", "user_observation"]},
        "integration": {"type": ["string", "null"]},
        "repository": {"type": ["string", "null"]},
        "path": {"type": ["string", "null"]},
        "line_start": {"type": ["integer", "null"], "minimum": 1},
        "line_end": {"type": ["integer", "null"], "minimum": 1},
        "commit": {"type": ["string", "null"]},
        "capture_id": {"type": ["string", "null"]},
        "claim": {"type": "string"},
        "url": {"type": ["string", "null"], "format": "uri"}
      }
    }
  }
}
```