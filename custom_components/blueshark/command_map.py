"""Validation and encoding of user-authored command maps.

A command map is a JSON object keyed by command id.  Each entry describes one
entity exposed for a device: a ``button`` that writes a fixed payload, a
``number`` whose value is appended to an opcode as a single byte, or a
``switch`` with separate ``on`` and ``off`` payloads.  Validation rejects
anything undocumented instead of dropping it, so a typo never silently
becomes a no-op.  Encoding turns a validated entry into the bytes to write
plus the optional characteristic UUID to write them to.
"""

from __future__ import annotations

import re
from typing import Any
from uuid import UUID


class CommandMapError(ValueError):
    """Raised when a command map entry is malformed or cannot be encoded."""


_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
_HEX_RE = re.compile(r"^(?:[0-9A-Fa-f]{2})(?: ?[0-9A-Fa-f]{2})*$")
MAX_COMMANDS = 256
MAX_PAYLOAD_BYTES = 512
_MAX_NAME_CHARS = 128
_MAX_NOTE_CHARS = 512

_COMMON_KEYS = frozenset({"name", "kind", "characteristic", "note"})
_PAYLOAD_KEYS = frozenset({"payload_hex", "opcode", "argument_hex"})
_ENTRY_KEYS = {
    "button": _COMMON_KEYS | _PAYLOAD_KEYS,
    "number": _COMMON_KEYS | {"opcode", "min", "max"},
    "switch": _COMMON_KEYS | {"on", "off"},
}
_SWITCH_STATE_KEYS = _PAYLOAD_KEYS | {"characteristic"}


def _require_object(value: object, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CommandMapError(f"{label} must be an object")
    return value


def _require_string(
    value: object, label: str, *, max_chars: int | None = None, nonempty: bool = True
) -> str:
    if not isinstance(value, str):
        raise CommandMapError(f"{label} must be a string")
    text = value.strip()
    if nonempty and not text:
        raise CommandMapError(f"{label} must not be empty")
    if max_chars is not None and len(text) > max_chars:
        raise CommandMapError(f"{label} must be at most {max_chars} characters")
    return text


def _require_byte(value: object, label: str) -> int:
    # bool is deliberately excluded: True must not pass as 1.
    if type(value) is not int or not 0 <= value <= 255:
        raise CommandMapError(f"{label} must be an integer from 0 to 255")
    return value


def _require_uuid(value: object, label: str) -> str:
    raw = _require_string(value, label)
    try:
        return str(UUID(raw))
    except ValueError as err:
        raise CommandMapError(f"{label} must be a valid UUID") from err


def _require_hex(value: object, label: str, *, allow_empty: bool = False) -> str:
    """Return ``value`` as lowercase hex with no spaces."""

    if not isinstance(value, str):
        raise CommandMapError(f"{label} must be a string of hexadecimal byte pairs")
    text = value.strip()
    if not text:
        if allow_empty:
            return ""
        raise CommandMapError(f"{label} must not be empty")
    if not _HEX_RE.fullmatch(text):
        raise CommandMapError(f"{label} must be hexadecimal byte pairs (spaces optional)")
    return text.replace(" ", "").lower()


def _unknown_keys(entry: dict[str, Any], allowed: frozenset[str]) -> str:
    return ", ".join(sorted(str(key) for key in entry if key not in allowed))


def _validate_payload(entry: dict[str, Any], label: str) -> dict[str, Any]:
    """Validate exactly one payload form and return its normalized fields."""

    has_payload = "payload_hex" in entry
    has_opcode = "opcode" in entry or "argument_hex" in entry
    if has_payload and has_opcode:
        raise CommandMapError(
            f"{label} must use either payload_hex or opcode + argument_hex, not both"
        )
    if has_payload:
        payload_hex = _require_hex(entry["payload_hex"], f"{label}.payload_hex")
        if len(payload_hex) // 2 > MAX_PAYLOAD_BYTES:
            raise CommandMapError(f"{label}.payload_hex must be 1..{MAX_PAYLOAD_BYTES} bytes")
        return {"payload_hex": payload_hex}
    if "opcode" not in entry or "argument_hex" not in entry:
        raise CommandMapError(
            f"{label} must specify payload_hex, or opcode together with argument_hex"
        )
    opcode = _require_byte(entry["opcode"], f"{label}.opcode")
    argument_hex = _require_hex(entry["argument_hex"], f"{label}.argument_hex", allow_empty=True)
    if 1 + len(argument_hex) // 2 > MAX_PAYLOAD_BYTES:
        raise CommandMapError(
            f"{label} opcode + argument_hex must be at most {MAX_PAYLOAD_BYTES} bytes"
        )
    return {"opcode": opcode, "argument_hex": argument_hex}


def _validate_number(entry: dict[str, Any], label: str) -> dict[str, Any]:
    if "opcode" not in entry:
        raise CommandMapError(f"{label}.opcode is required for kind number")
    minimum = _require_byte(entry.get("min", 0), f"{label}.min")
    maximum = _require_byte(entry.get("max", 255), f"{label}.max")
    if minimum >= maximum:
        raise CommandMapError(f"{label}.min must be less than max")
    return {
        "opcode": _require_byte(entry["opcode"], f"{label}.opcode"),
        "min": minimum,
        "max": maximum,
    }


def _validate_switch(entry: dict[str, Any], label: str) -> dict[str, Any]:
    states: dict[str, Any] = {}
    for state in ("on", "off"):
        if state not in entry:
            raise CommandMapError(f"{label}.{state} is required for kind switch")
        state_label = f"{label}.{state}"
        raw = _require_object(entry[state], state_label)
        extra = _unknown_keys(raw, _SWITCH_STATE_KEYS)
        if extra:
            raise CommandMapError(f"{state_label} has unknown fields: {extra}")
        normalized = _validate_payload(raw, state_label)
        if "characteristic" in raw:
            normalized["characteristic"] = _require_uuid(
                raw["characteristic"], f"{state_label}.characteristic"
            )
        states[state] = normalized
    return states


def _validate_entry(value: object, label: str) -> dict[str, Any]:
    entry = _require_object(value, label)
    kind = entry.get("kind")
    if not isinstance(kind, str) or kind not in _ENTRY_KEYS:
        raise CommandMapError(f"{label}.kind must be one of {', '.join(sorted(_ENTRY_KEYS))}")
    extra = _unknown_keys(entry, _ENTRY_KEYS[kind])
    if extra:
        raise CommandMapError(f"{label} has unknown fields for kind {kind}: {extra}")
    if "name" not in entry:
        raise CommandMapError(f"{label}.name is required")
    normalized: dict[str, Any] = {
        "name": _require_string(entry["name"], f"{label}.name", max_chars=_MAX_NAME_CHARS),
        "kind": kind,
    }
    if "characteristic" in entry:
        normalized["characteristic"] = _require_uuid(
            entry["characteristic"], f"{label}.characteristic"
        )
    if "note" in entry:
        normalized["note"] = _require_string(
            entry["note"], f"{label}.note", max_chars=_MAX_NOTE_CHARS, nonempty=False
        )
    if kind == "button":
        normalized.update(_validate_payload(entry, label))
    elif kind == "number":
        normalized.update(_validate_number(entry, label))
    else:
        normalized.update(_validate_switch(entry, label))
    return normalized


def validate_command_map(value: object) -> dict[str, dict[str, Any]]:
    """Validate and normalize a command map, rejecting unknown fields.

    Returns a new dict: hex fields are lowercased without spaces, UUIDs are in
    canonical form, and number entries carry explicit ``min``/``max``.
    """

    commands = _require_object(value, "command map")
    if len(commands) > MAX_COMMANDS:
        raise CommandMapError(f"command map must contain at most {MAX_COMMANDS} commands")
    normalized: dict[str, dict[str, Any]] = {}
    for command_id, entry in commands.items():
        if not isinstance(command_id, str) or not _NAME_RE.fullmatch(command_id):
            raise CommandMapError(
                f"command id {command_id!r} must be 1-64 characters of letters, digits, "
                "'_', '.' or '-', starting with a letter or digit"
            )
        normalized[command_id] = _validate_entry(entry, command_id)
    return normalized


def _encode_payload(entry: dict[str, Any]) -> bytes:
    if "payload_hex" in entry:
        return bytes.fromhex(entry["payload_hex"])
    return bytes([entry["opcode"]]) + bytes.fromhex(entry["argument_hex"])


def encode_command(
    entry: dict[str, Any], *, value: int | None = None
) -> tuple[bytes, str | None]:
    """Encode a validated button or number entry as ``(payload, characteristic)``.

    Number values outside the entry's ``min``..``max`` are rejected, never clamped.
    """

    kind = entry.get("kind")
    if kind == "switch":
        raise CommandMapError("switch commands are encoded with encode_switch_command")
    if kind not in ("button", "number"):
        raise CommandMapError(f"cannot encode a command of kind {kind!r}")
    characteristic = entry.get("characteristic")
    if kind == "button":
        return _encode_payload(entry), characteristic
    if value is None:
        raise CommandMapError("number commands need a value")
    minimum = entry.get("min", 0)
    maximum = entry.get("max", 255)
    if type(value) is not int or not minimum <= value <= maximum:
        raise CommandMapError(
            f"value must be an integer from {minimum} to {maximum}, got {value!r}"
        )
    return bytes([entry["opcode"], value]), characteristic


def encode_switch_command(entry: dict[str, Any], *, on: bool) -> tuple[bytes, str | None]:
    """Encode a validated switch entry's on or off payload as ``(payload, characteristic)``.

    A state's own ``characteristic`` wins over the entry-level one.
    """

    if entry.get("kind") != "switch":
        raise CommandMapError("only switch commands are encoded with encode_switch_command")
    state = entry["on" if on else "off"]
    return _encode_payload(state), state.get("characteristic") or entry.get("characteristic")
