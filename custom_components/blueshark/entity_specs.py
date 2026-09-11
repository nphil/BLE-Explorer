"""Pure, HA-free builders for guided command-map entity specs.

A guided config entry persists one ``command_map`` (validated by
:mod:`.command_map` before it is ever written). Each platform module
(``button``/``number``/``switch``) turns the entries relevant to it into
one entity per entry. That filtering — and the decision to skip an entry
this platform does not recognise rather than let ``async_setup_entry``
raise — is pure data-shuffling with no Home Assistant dependency, so it
lives here where :mod:`tests.test_entity_build` can exercise it directly.

Defensive by design: a persisted command map can outlive a schema change
(nothing re-validates it just because HA restarted), so an entry with a
missing/unrecognised ``kind`` or a missing ``name`` is skipped rather than
aborting the whole platform's setup.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterator, Mapping


@dataclass(frozen=True)
class CommandSpec:
    """One command-map entry, already filtered to a single entity kind."""

    key: str
    name: str
    entry: Mapping[str, Any]

    def unique_id(self, entry_id: str, kind: str) -> str:
        return f"{entry_id}_{kind}_{self.key}"


def _iter_kind(command_map: Mapping[str, Any] | None, kind: str) -> Iterator[CommandSpec]:
    if not command_map:
        return
    for key, entry in command_map.items():
        if not isinstance(key, str) or not isinstance(entry, Mapping):
            continue
        if entry.get("kind") != kind:
            continue
        name = entry.get("name")
        if not isinstance(name, str) or not name:
            continue
        yield CommandSpec(key=key, name=name, entry=entry)


def build_button_specs(command_map: Mapping[str, Any] | None) -> list[CommandSpec]:
    """Guided buttons: one per command-map entry with ``kind == "button"``."""

    return list(_iter_kind(command_map, "button"))


def build_number_specs(command_map: Mapping[str, Any] | None) -> list[CommandSpec]:
    """Guided numbers: one per command-map entry with ``kind == "number"``."""

    return list(_iter_kind(command_map, "number"))


def build_switch_specs(command_map: Mapping[str, Any] | None) -> list[CommandSpec]:
    """Guided switches: one per command-map entry with ``kind == "switch"``."""

    return list(_iter_kind(command_map, "switch"))


def build_legacy_button_specs(profile: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    """The exact filter the legacy profile-import button platform has always used.

    Only non-synthetic commands marked ``stage: tested`` become buttons, and a
    synthetic profile (no physical device backing it, used for contract fixtures)
    never produces any. Returns the raw command dicts from the profile, matching
    what ``button.BleCommandButton`` has always been constructed from.
    """

    if profile.get("synthetic"):
        return []
    return [
        command
        for command in profile.get("commands", [])
        if not command.get("synthetic") and command.get("stage") == "tested"
    ]


# --- Runtime adapter helpers, shared by button/number/switch/sensor/binary_sensor. ---
#
# The guided runtime object (``BlueSharkDevice``, built by the coordinator module)
# is under active development on a sibling slice. Its contract per the panel design
# doc is: ``available``, ``connected``, ``device_info``, ``last_response``,
# ``async_add_listener(callback) -> unsubscribe``, ``async_send_command(key)``,
# ``async_send_number(key, value)``, ``async_send_switch(key, on)``, plus read-only
# ``transport``/``codec``/``entry``/``command_map`` attributes. The exact shape of a
# write's return value and of ``last_response`` is not yet nailed down, so every
# accessor below is defensive: it accepts an attribute, a mapping key, or a bare
# string/None, and never raises on an unexpected shape. [INFERENCE]

# Only an explicit device-side refusal blocks the optimistic update. "no_response"
# and "undecodable" are NOT rejections: a write-without-response command (the
# common case) never gets a reply at all, and a malformed/unexpected reply still
# means the device accepted bytes on the wire - neither should leave an entity
# permanently stuck refusing to reflect what was just sent.
_REJECTED_VERDICTS = frozenset({"rejected_unknown_id", "rejected_other"})


def is_legacy_runtime(runtime: object) -> bool:
    """The profile-import platform stores a plain dict; guided stores a device object."""

    return isinstance(runtime, dict)


def _field(source: object, name: str) -> Any:
    if source is None:
        return None
    if isinstance(source, Mapping):
        return source.get(name)
    return getattr(source, name, None)


def verdict_of(result: object, runtime: object | None = None) -> str | None:
    """Best-effort verdict string for a just-completed guided write.

    Tries the write's own return value first (a bare string, a mapping with a
    ``verdict`` key, or an object with a ``verdict`` attribute), then falls back
    to the runtime's ``last_response`` if the write itself returned nothing useful.
    """

    if isinstance(result, str):
        return result
    verdict = _field(result, "verdict")
    if verdict is not None:
        return str(verdict)
    if runtime is not None:
        last_response = _field(runtime, "last_response")
        verdict = _field(last_response, "verdict")
        if verdict is not None:
            return str(verdict)
    return None


def is_rejected_verdict(verdict: str | None) -> bool:
    """True for any verdict that means the device did not accept the write.

    ``None`` (verdict unknown/unavailable) is treated as *not* rejected so an
    optimistic entity still reflects a write when the runtime cannot yet report
    a verdict — the safer failure mode is a stale-looking success, not a stuck
    "unknown" state after every single command.
    """

    return verdict in _REJECTED_VERDICTS


def extract_response_fields(last_response: object) -> dict[str, Any]:
    """Normalize ``last_response`` into the fields the ``last_response`` sensor needs."""

    sent = _field(last_response, "sent") or _field(last_response, "sent_hex")
    if isinstance(sent, (bytes, bytearray)):
        sent_hex = sent.hex()
    else:
        sent_hex = sent if isinstance(sent, str) else None
    response = _field(last_response, "response")
    if response is None:
        response = _field(last_response, "response_hex")
    if isinstance(response, (bytes, bytearray)):
        response_hex = response.hex()
    else:
        response_hex = response if isinstance(response, str) else None
    opcode = _field(last_response, "opcode")
    if opcode is None and isinstance(sent, (bytes, bytearray)) and sent:
        opcode = sent[0]
    return {
        "verdict": _field(last_response, "verdict"),
        "sent_hex": sent_hex,
        "response_hex": response_hex,
        "status": _field(last_response, "status"),
        "elapsed_ms": _field(last_response, "elapsed_ms"),
        "opcode": opcode,
    }


def link_state(runtime: object) -> tuple[str, str | None]:
    """Classify the current link as one of idle/connecting/connected/busy.

    Returns ``(state, current_operation)``. Derived from :class:`.transport.BleTransport`'s
    own ``is_busy()``/``current_operation`` surface, since the contract does not define a
    single combined "link state" field. [INFERENCE]
    """

    transport = _field(runtime, "transport")
    is_busy = getattr(transport, "is_busy", None)
    busy = bool(is_busy()) if callable(is_busy) else False
    operation = getattr(transport, "current_operation", None) if transport is not None else None
    if callable(operation):
        operation = operation()
    if busy:
        return "busy", operation
    if bool(_field(runtime, "connected")):
        return "connected", operation
    if operation:
        return "connecting", operation
    return "idle", None


def advertising_state(runtime: object) -> bool | None:
    """Whether the coordinator currently sees this device's advertisements.

    ``None`` (unknown, e.g. before the first advertisement report) is
    distinct from ``False`` (was seen, has since gone quiet). [INFERENCE]
    """

    value = _field(runtime, "advertising")
    return None if value is None else bool(value)


# --- Shared entity-behaviour mixins. ---
#
# Every guided command/diagnostic entity across button/number/switch/sensor
# needs the same three things: unavailable when the runtime reports no route,
# a listener registered on setup, and a plain state refresh on update. Rather
# than repeat that `getattr`-guarded boilerplate in five platform files, it
# lives here once. These are plain mixins with no Home Assistant base class of
# their own (this module still has zero HA dependency) - each platform mixes
# one in alongside the real HA entity base class, e.g.
# ``class BlueSharkNumber(RuntimeAvailableMixin, RuntimeListenerMixin, NumberEntity)``.
# Presence sensors (connected/advertising) deliberately do NOT use
# ``RuntimeAvailableMixin``: their entire purpose is to report "no route" as an
# ``off`` state, not to vanish when there is one.


class RuntimeListenerMixin:
    """Subscribes to the runtime's update callback and refreshes HA state on it.

    Expects ``self._runtime`` to already be set (every guided entity stores it
    in ``__init__``) and ``self.async_on_remove``/``self.async_write_ha_state``
    from the real HA ``Entity`` base class this is mixed alongside.
    """

    async def async_added_to_hass(self) -> None:
        add_listener = getattr(self._runtime, "async_add_listener", None)
        if callable(add_listener):
            self.async_on_remove(add_listener(self._handle_runtime_update))

    def _handle_runtime_update(self) -> None:
        self.async_write_ha_state()


class RuntimeAvailableMixin:
    """``available`` mirrors the runtime's own availability (has a route, one way or another)."""

    @property
    def available(self) -> bool:
        return bool(getattr(self._runtime, "available", True))
