"""Strict validation for captured BLE command profiles.

Profiles are evidence records, not a generic protocol description.  Keeping
validation here makes config-flow and setup enforce the same small v0.1
contract.
"""

from __future__ import annotations

import json
import re
from copy import deepcopy
from uuid import UUID


class ProfileValidationError(ValueError):
    """Raised when a profile is not a supported v2 evidence record."""


_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
_HEX_RE = re.compile(r"^(?:[0-9A-Fa-f]{2})(?: ?[0-9A-Fa-f]{2})*$")
MAX_PROFILE_BYTES = 64 * 1024
MAX_COMMANDS = 128
MAX_PAYLOAD_BYTES = 512


def _object_no_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ProfileValidationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def parse_profile(text: str) -> dict[str, object]:
    """Parse and validate the profile JSON supplied in the config flow."""

    if not isinstance(text, str) or not text.strip():
        raise ProfileValidationError("profile JSON must be a non-empty string")
    if len(text.encode("utf-8")) > MAX_PROFILE_BYTES:
        raise ProfileValidationError("profile JSON exceeds 65536-byte limit")
    try:
        value = json.loads(text, object_pairs_hook=_object_no_duplicate_keys)
    except json.JSONDecodeError as err:
        raise ProfileValidationError(f"invalid profile JSON: {err.msg}") from err
    return validate_profile(value)


def _require_object(value: object, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ProfileValidationError(f"{label} must be an object")
    return value


def _require_string(value: object, label: str, *, nonempty: bool = True) -> str:
    if not isinstance(value, str) or (nonempty and not value.strip()):
        raise ProfileValidationError(f"{label} must be a string")
    return value.strip() if nonempty else value


def _require_bool(value: object, label: str) -> bool:
    # bool is deliberately checked separately: integers must not pass as bool.
    if type(value) is not bool:
        raise ProfileValidationError(f"{label} must be a boolean")
    return value


def _require_uuid(value: object, label: str) -> str:
    raw = _require_string(value, label)
    try:
        parsed = UUID(raw)
    except (ValueError, AttributeError) as err:
        raise ProfileValidationError(f"{label} must be a valid UUID") from err
    return str(parsed)


def validate_profile(value: object) -> dict[str, object]:
    """Validate and normalize a v2 profile, rejecting unknown fields."""

    root = _require_object(value, "profile")
    expected_root = {"schema_version", "device", "synthetic", "commands"}
    if set(root) != expected_root:
        missing = expected_root - set(root)
        extra = set(root) - expected_root
        details = []
        if missing:
            details.append(f"missing {', '.join(sorted(missing))}")
        if extra:
            details.append(f"unknown {', '.join(sorted(extra))}")
        raise ProfileValidationError("profile fields: " + "; ".join(details))
    if root["schema_version"] != 2 or type(root["schema_version"]) is not int:
        raise ProfileValidationError("schema_version must be integer 2")

    device = _require_object(root["device"], "device")
    if set(device) != {"name", "address"}:
        raise ProfileValidationError("device fields must be exactly name and address")
    name = _require_string(device["name"], "device.name")
    address = _require_string(device["address"], "device.address")
    if len(address) > 128:
        raise ProfileValidationError("device.address is too long")

    synthetic = _require_bool(root["synthetic"], "synthetic")
    commands = root["commands"]
    if not isinstance(commands, list) or not commands:
        raise ProfileValidationError("commands must be a non-empty array")
    if len(commands) > MAX_COMMANDS:
        raise ProfileValidationError(f"commands must contain at most {MAX_COMMANDS} items")

    normalized_commands: list[dict[str, object]] = []
    seen_ids: set[str] = set()
    command_fields = {
        "id",
        "name",
        "service",
        "characteristic",
        "value",
        "response",
        "stage",
        "notes",
        "synthetic",
    }
    for index, command_value in enumerate(commands):
        command = _require_object(command_value, f"commands[{index}]")
        if set(command) != command_fields:
            raise ProfileValidationError(
                f"commands[{index}] fields must be exactly "
                + ", ".join(sorted(command_fields))
            )
        command_id = _require_string(command["id"], f"commands[{index}].id")
        if not _ID_RE.fullmatch(command_id):
            raise ProfileValidationError(f"commands[{index}].id has invalid characters")
        if command_id in seen_ids:
            raise ProfileValidationError(f"duplicate command id: {command_id}")
        seen_ids.add(command_id)
        value_hex = _require_string(command["value"], f"commands[{index}].value")
        if not _HEX_RE.fullmatch(value_hex):
            raise ProfileValidationError(
                f"commands[{index}].value must be hexadecimal byte pairs (spaces optional)"
            )
        normalized_value = value_hex.replace(" ", "").lower()
        payload_length = len(normalized_value) // 2
        if not 1 <= payload_length <= MAX_PAYLOAD_BYTES:
            raise ProfileValidationError(
                f"commands[{index}].value payload must be 1..{MAX_PAYLOAD_BYTES} bytes"
            )
        stage = _require_string(command["stage"], f"commands[{index}].stage")
        if stage != "tested":
            raise ProfileValidationError(f"commands[{index}].stage must be 'tested'")
        notes = _require_string(command["notes"], f"commands[{index}].notes")
        normalized_commands.append(
            {
                "id": command_id,
                "name": _require_string(command["name"], f"commands[{index}].name"),
                "service": _require_uuid(command["service"], f"commands[{index}].service"),
                "characteristic": _require_uuid(
                    command["characteristic"], f"commands[{index}].characteristic"
                ),
                "value": normalized_value,
                "response": _require_bool(command["response"], f"commands[{index}].response"),
                "stage": stage,
                "notes": notes,
                "synthetic": _require_bool(
                    command["synthetic"], f"commands[{index}].synthetic"
                ),
            }
        )
    return {
        "schema_version": 2,
        "device": {"name": name, "address": address},
        "synthetic": synthetic,
        "commands": deepcopy(normalized_commands),
    }

