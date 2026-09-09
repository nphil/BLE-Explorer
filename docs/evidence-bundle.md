# Evidence bundle → Home Assistant integration

This is the contract between BLE Studio for Android and whoever (human or AI agent) writes
the Home Assistant integration for a reverse-engineered device. Feed the agent the
**evidence bundle JSON** exported from Sessions → Export and point it at this file.

## 1. What the bundle contains

`EvidenceBundle { schemaVersion, appVersion, session }` — the Kotlin source of truth is
`android/app/src/main/kotlin/dev/nphil/blestudio/model/SessionModels.kt`.

| Section | Use it for |
| --- | --- |
| `session.device` (`DeviceIdentity`) | `manifest.json` `bluetooth` matchers (`local_name`, `service_uuid`, `manufacturer_id`), `unique_id`, `DeviceInfo`. Prefer manufacturer-data or service-UUID matchers over names; names change. |
| `session.advertisements` | Whether the device advertises while connected (availability source), advertising interval, connectable flag, manufacturer-data layout for passive sensors (`PassiveBluetoothProcessorCoordinator`). |
| `session.gatt` (`GattDatabase`) | Exact service/characteristic UUIDs, properties (`WRITE` vs `WRITE_NO_RESPONSE`, `NOTIFY` vs `INDICATE`), CCCD presence. Never invent a UUID not present here. |
| `session.connection` (`ConnectionFacts`) | `negotiatedMtu` (frame size cap), `preferredWriteType`, `writeWithoutResponseVerified`, `idleDisconnectMs` (persistent vs connect-on-demand decision), `minInterCommandMs` (rate limit), `maxObservedResponseMs` (timeout floor), `pairingRequired`, `connectAttempts` (connect latency distribution and failure modes). |
| `session.events` (`BleEvent[]`) | Raw packets with µs timestamps, direction, source (`HCI_SNOOP` = vendor app traffic, `LIVE_GATT` = our own, `MITM_RELAY`), handles, UUIDs, payload hex. Ground truth for everything below. |
| `session.markers` | The user's labelled moments ("power on") to align with events. |
| `session.commands` (`CommandSpec[]`) | The command catalogue. **Only `stage == DEVICE_TESTED && !synthetic` may become an entity action.** `HYPOTHESIS`/`OBSERVED` entries are leads, not features. `response` gives the correlation predicate (characteristic + prefix/mask + observed latencies). `parameters` are byte-range hypotheses with observed values. |
| `session.notifications` (`NotificationSpec[]`) | Inbound state frames with decoding hypotheses → sensors/binary sensors/state feedback. |
| `session.ciphers` (`CipherScheme[]`) | Application-layer encryption the operator described: primitive, key derivation, nonce/AAD/tag byte sources, ciphertext range and which frames each scheme claims. `keyHex` is **empty unless the operator ticked "Include cipher keys"** at export — the shape travels, the secret does not. Reproduce a scheme with your own key; see `docs/decryption.md`. |
| `session.protocol` (`ProtocolModel`) | Framing: header bytes, length/sequence/checksum offsets, endianness, handshake command ids, pairing/encryption notes. Treat as hypotheses until the events confirm them. |
| `session.environment` (`CaptureEnvironment`) | Android version/fingerprint, vendor app package+version, HCI snoop mode. `hciSnoopMode != "full"` means payloads may be truncated: distrust long frames. |

The **HA install profile** (`HaInstallProfile`, schema_version 2) is the *minimal* strict
subset consumed by the shipped `ble_studio` integration (fixed-payload buttons). The
evidence bundle is the *maximal* record used to write a full-featured integration.

## 2. Reliability rules the generated integration must follow

Derived from auditing production reverse-engineered integrations (`docs/research/ha-ble-integrations.md`,
section 4). These are not optional.

1. **Route freshness.** Before *every* connect: `ble_device = bluetooth.async_ble_device_from_address(hass, address, connectable=True)`; if `None`, raise `ConfigEntryNotReady`/mark unavailable — never connect through a `BLEDevice` cached from discovery. Use `bleak_retry_connector.establish_connection(BleakClientWithServiceCache, ble_device, name, disconnected_callback, max_attempts=…, ble_device_callback=lambda: fresh lookup)`.
2. **One connection supervisor per device.** A single task owns connect → (handshake) → subscribe → serve commands → disconnect. Choose the shape from evidence: `idleDisconnectMs` small or single-client device ⇒ persistent connection with reconnect (jittered exponential backoff, cap ~5 min); otherwise connect-on-demand with a coalescing window so back-to-back commands share one connection. Persistent connections hold an ESPHome proxy slot: document it.
3. **Serialize GATT.** One `asyncio.Lock` around every read/write/start_notify/stop_notify **and** disconnect. No concurrent writes, no watchdog reads outside the lock.
4. **Bound everything.** Every await (`connect`, `write_gatt_char`, `read_gatt_char`, `start_notify`, `disconnect`, response wait) under `asyncio.timeout`. Timeout for a command response = `max(3 × maxObservedResponseMs, 2 s)`. A timed-out write is a **failure**: do not update optimistic state, do not auto-retry a non-idempotent command (it may have taken effect), poison the connection (disconnect, reconnect on next demand).
5. **Correlate responses.** Create the response `Future` *before* writing; resolve it only when a notification matches the `CommandSpec.response` predicate (characteristic + prefix/mask, sequence byte if `protocol.sequenceByteOffset` is set). Unsolicited frames never resolve a pending command.
6. **Write type from evidence.** Use `response=False` only if `writeWithoutResponseVerified` is true and the characteristic has `WRITE_NO_RESPONSE`; otherwise `response=True`. Frame size ≤ `negotiatedMtu − 3`; chunk only if the vendor app was observed chunking.
7. **Pace.** Enforce `minInterCommandMs` between writes (sleep inside the lock).
8. **Availability from advertisements**, not failed polls: `bluetooth.async_track_unavailable(hass, cb, address, connectable=True)` and last-seen age; for notification-stream devices also mark unavailable when the stream goes silent beyond its observed cadence.
9. **Never block the event loop**: no `time.sleep`, no sync file IO; parsing in pure functions.
10. **Entities**: only commands at `DEVICE_TESTED` become services/buttons/switch actions; state comes from `NotificationSpec` decodes with confidence noted in entity descriptions. Disable diagnostic/experimental entities by default (`entity_registry_enabled_default=False`). Device class/units from hypotheses only when the observed values make sense.
11. **Config flow**: Bluetooth discovery via manifest matchers **and** manual address entry (device may be held by the vendor app during setup). `unique_id = address`. Reauth/reconfigure for pairing changes.
12. **Tests**: pure protocol codec tests from the bundle's real payloads (encode each `DEVICE_TESTED` command, decode every `NotificationSpec` sample). No tests that echo mocks.

## 3. Generation checklist for the agent

```
[ ] Read session.device + gatt: choose matchers, list UUIDs, note write types.
[ ] Read connection facts: persistent vs on-demand; MTU; pacing; timeouts.
[ ] For each DEVICE_TESTED command: payload, write type, response predicate, entity type (CommandSpec.proposedHomeAssistantEntity).
[ ] For each NotificationSpec: decode fields with offsets/scale; entity type; unit.
[ ] Cross-check protocol hypotheses against ≥3 events before encoding them in code.
[ ] Implement: codec.py (pure), client.py (supervisor+lock+timeouts+correlation), coordinator.py, config_flow.py, entities, manifest.json, strings.json, tests/.
[ ] Mirror packaging from custom_components/ble_studio (hacs.json, brand assets, README).
```

Anything the bundle does not show is unknown. Say so in the README of the generated integration rather than guessing.
