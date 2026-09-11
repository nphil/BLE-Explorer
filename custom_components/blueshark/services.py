"""Automation-facing services: thin wrappers over the same engine calls the panel uses.

Every service targets one or more devices (`target: {device: {integration: blueshark}}`
in services.yaml) and supports `SupportsResponse.OPTIONAL`, returning
`{device_id: <result>}` for each targeted device - the same convention Home
Assistant's own multi-target response services use (e.g. `weather.get_forecasts`).

This module is Home Assistant glue (imports `homeassistant.core` et al. at module
level) and is never imported by a pure unit test; `__init__.py` imports and calls
`async_register_services` lazily, from `async_setup`, exactly like `websocket_api`.
"""

from __future__ import annotations

import asyncio
import logging
from functools import partial
from typing import Any

import voluptuous as vol
from homeassistant.core import HomeAssistant, ServiceCall, ServiceResponse, SupportsResponse
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import device_registry as dr
from homeassistant.const import ATTR_DEVICE_ID

from .codecs import Codec, get_codec
from .const import (
    CANARY_INTERVAL,
    CONF_CHARACTERISTIC,
    CONF_CODEC_ID,
    CONF_NOTIFY_CHARACTERISTIC,
    DEFAULT_AWAIT_RESPONSE_MS,
    DEFAULT_SWEEP_STEP_DELAY_MS,
    DOMAIN,
    SERVICE_LISTEN,
    SERVICE_PROBE_OPCODE,
    SERVICE_PROBE_SWEEP,
    SERVICE_SEND_RAW,
)
from .coordinator import BlueSharkDevice
from .sweep import interpret_sweep, plan_sweep
from .websocket_api import encode_for_wire, send_result

_LOGGER = logging.getLogger(__name__)

_CODEC_SELECT = vol.In(("raw", "coolled", "prefix_suffix"))

# `extra=vol.ALLOW_EXTRA`: a `target:` selector merges `device_id` (and
# potentially `entity_id`/`area_id`/`label_id`, resolved by `_target_devices`)
# into `call.data` alongside these fields; none of that is ours to validate.

_SEND_RAW_SCHEMA = vol.Schema(
    {
        vol.Required("payload_hex"): cv.string,
        vol.Optional("characteristic"): cv.string,
        vol.Optional("codec_id"): _CODEC_SELECT,
        vol.Optional("framed", default=False): cv.boolean,
        vol.Optional("await_response_ms", default=DEFAULT_AWAIT_RESPONSE_MS): vol.All(
            vol.Coerce(int), vol.Range(min=0)
        ),
    },
    extra=vol.ALLOW_EXTRA,
)

_PROBE_OPCODE_SCHEMA = vol.Schema(
    {
        vol.Required("opcode"): vol.All(vol.Coerce(int), vol.Range(min=0, max=255)),
        vol.Optional("argument_hex", default=""): cv.string,
        vol.Optional("characteristic"): cv.string,
        vol.Optional("codec_id"): _CODEC_SELECT,
        vol.Optional("await_response_ms", default=DEFAULT_AWAIT_RESPONSE_MS): vol.All(
            vol.Coerce(int), vol.Range(min=0)
        ),
    },
    extra=vol.ALLOW_EXTRA,
)

_PROBE_SWEEP_SCHEMA = vol.Schema(
    {
        vol.Required("start"): vol.All(vol.Coerce(int), vol.Range(min=0, max=255)),
        vol.Required("end"): vol.All(vol.Coerce(int), vol.Range(min=0, max=255)),
        vol.Optional("argument_hex", default=""): cv.string,
        vol.Optional("include_destructive", default=False): cv.boolean,
        vol.Optional("characteristic"): cv.string,
        vol.Optional("codec_id"): _CODEC_SELECT,
        vol.Optional("step_delay_ms", default=DEFAULT_SWEEP_STEP_DELAY_MS): vol.All(
            vol.Coerce(int), vol.Range(min=0)
        ),
        vol.Optional("await_response_ms", default=DEFAULT_AWAIT_RESPONSE_MS): vol.All(
            vol.Coerce(int), vol.Range(min=0)
        ),
    },
    extra=vol.ALLOW_EXTRA,
)

_LISTEN_SCHEMA = vol.Schema(
    {
        vol.Optional("characteristic"): cv.string,
        vol.Required("seconds"): vol.All(vol.Coerce(float), vol.Range(min=0.5, max=300)),
    },
    extra=vol.ALLOW_EXTRA,
)


def _target_devices(hass: HomeAssistant, call: ServiceCall) -> list[tuple[str, BlueSharkDevice]]:
    """Resolve the call's device target(s) to their `BlueSharkDevice` runtimes.

    Reads `device_id` directly from `call.data`: `target: {device: {...}}` in
    services.yaml only ever exposes a device picker for this service (no
    entity/area/label selectors), and the schemas allow the extra key through
    via `extra=vol.ALLOW_EXTRA`. Skips (raising) any targeted device that
    belongs to a legacy profile-import entry - these services only ever act on
    guided entries, since only those have a transport/codec/command channel.

    Uses `async_get_device_and_config_entry_for_domain` rather than iterating
    the device entry's own `config_entries` - the latter is deprecated (a
    device belongs to a single config entry as of HA core 2026.7+).
    """

    device_ids = call.data.get(ATTR_DEVICE_ID) or []
    if isinstance(device_ids, str):
        device_ids = [device_ids]
    if not device_ids:
        raise ServiceValidationError("This service needs at least one target device")
    resolved: list[tuple[str, BlueSharkDevice]] = []
    for device_id in device_ids:
        device_entry, config_entry = dr.async_get_device_and_config_entry_for_domain(
            hass, device_id, domain=DOMAIN
        )
        if device_entry is None:
            raise ServiceValidationError(f"Unknown device {device_id}")
        runtime = hass.data.get(DOMAIN, {}).get(config_entry.entry_id) if config_entry is not None else None
        if not isinstance(runtime, BlueSharkDevice):
            raise ServiceValidationError(
                f"Device {device_id} is not a guided BlueShark device (legacy profile devices are read-only)"
            )
        resolved.append((device_id, runtime))
    return resolved


def _codec_for(device: BlueSharkDevice, codec_id: str | None) -> Codec:
    """The device's own configured codec, unless the call names a different one to try."""

    if codec_id is None or codec_id == device.entry.data.get(CONF_CODEC_ID):
        return device.codec
    return get_codec(codec_id)


def _characteristic_for(device: BlueSharkDevice, override: str | None, *, notify: bool = False) -> str:
    if override:
        return override
    if notify:
        target = device.entry.data.get(CONF_NOTIFY_CHARACTERISTIC) or device.entry.data.get(CONF_CHARACTERISTIC)
    else:
        target = device.entry.data.get(CONF_CHARACTERISTIC)
    if not target:
        raise ServiceValidationError(f"{device.address} has no characteristic configured")
    return target


async def _async_send_raw(hass: HomeAssistant, call: ServiceCall) -> ServiceResponse:
    try:
        payload = bytes.fromhex(call.data["payload_hex"])
    except ValueError as err:
        raise ServiceValidationError(f"payload_hex is not valid hex: {err}") from err
    results: dict[str, Any] = {}
    for device_id, device in _target_devices(hass, call):
        codec = _codec_for(device, call.data.get("codec_id"))
        characteristic = _characteristic_for(device, call.data.get("characteristic"))
        wire = encode_for_wire(codec, payload, call.data["framed"])
        raw = await device.transport.request(characteristic, wire, call.data["await_response_ms"])
        results[device_id] = send_result(raw.sent, raw.response, raw.elapsed_ms, codec)
    return results


async def _async_probe_opcode(hass: HomeAssistant, call: ServiceCall) -> ServiceResponse:
    try:
        argument = bytes.fromhex(call.data["argument_hex"])
    except ValueError as err:
        raise ServiceValidationError(f"argument_hex is not valid hex: {err}") from err
    payload = bytes([call.data["opcode"]]) + argument
    results: dict[str, Any] = {}
    for device_id, device in _target_devices(hass, call):
        codec = _codec_for(device, call.data.get("codec_id"))
        characteristic = _characteristic_for(device, call.data.get("characteristic"))
        raw = await device.transport.request(characteristic, codec.encode(payload), call.data["await_response_ms"])
        results[device_id] = send_result(raw.sent, raw.response, raw.elapsed_ms, codec)
    return results


async def _async_probe_sweep(hass: HomeAssistant, call: ServiceCall) -> ServiceResponse:
    try:
        argument = bytes.fromhex(call.data["argument_hex"])
    except ValueError as err:
        raise ServiceValidationError(f"argument_hex is not valid hex: {err}") from err
    results: dict[str, Any] = {}
    for device_id, device in _target_devices(hass, call):
        codec = _codec_for(device, call.data.get("codec_id"))
        characteristic = _characteristic_for(device, call.data.get("characteristic"))
        try:
            steps = plan_sweep(
                call.data["start"], call.data["end"], argument, call.data["include_destructive"], codec, CANARY_INTERVAL
            )
        except ValueError as err:
            raise ServiceValidationError(str(err)) from err
        step_results = []
        for index, step in enumerate(steps):
            if index:
                await asyncio.sleep(call.data["step_delay_ms"] / 1000)
            raw = await device.transport.request(
                characteristic, codec.encode(step.payload), call.data["await_response_ms"]
            )
            step_results.append((step, raw.response))
        results[device_id] = interpret_sweep(codec, step_results)
    return results


async def _async_listen(hass: HomeAssistant, call: ServiceCall) -> ServiceResponse:
    results: dict[str, Any] = {}
    for device_id, device in _target_devices(hass, call):
        characteristic = _characteristic_for(device, call.data.get("characteristic"), notify=True)
        frames: list[dict[str, Any]] = []

        def _on_frame(at: float, payload: bytes) -> None:
            frames.append({"at_ms": int(at * 1000), "hex": payload.hex()})

        await device.transport.listen(characteristic, call.data["seconds"], _on_frame)
        results[device_id] = {"frames": frames}
    return results


def async_register_services(hass: HomeAssistant) -> None:
    """Register the four BlueShark services. Idempotent-safe to call once."""

    hass.services.async_register(
        DOMAIN,
        SERVICE_SEND_RAW,
        partial(_async_send_raw, hass),
        schema=_SEND_RAW_SCHEMA,
        supports_response=SupportsResponse.OPTIONAL,
    )
    hass.services.async_register(
        DOMAIN,
        SERVICE_PROBE_OPCODE,
        partial(_async_probe_opcode, hass),
        schema=_PROBE_OPCODE_SCHEMA,
        supports_response=SupportsResponse.OPTIONAL,
    )
    hass.services.async_register(
        DOMAIN,
        SERVICE_PROBE_SWEEP,
        partial(_async_probe_sweep, hass),
        schema=_PROBE_SWEEP_SCHEMA,
        supports_response=SupportsResponse.OPTIONAL,
    )
    hass.services.async_register(
        DOMAIN,
        SERVICE_LISTEN,
        partial(_async_listen, hass),
        schema=_LISTEN_SCHEMA,
        supports_response=SupportsResponse.OPTIONAL,
    )
