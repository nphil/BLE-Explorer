"""Unit tests for the pure guided/legacy entity-spec builders in entity_specs.py.

These deliberately never import button.py/number.py/switch.py/sensor.py/
binary_sensor.py: those modules import real `homeassistant`/`bleak` packages
at module scope (matching the existing, untested-by-design convention in
transport.py), which are not installed in this sandbox. entity_specs.py has
no such dependency, so it is what stays testable without a running HA.

coordinator.py, codecs/, and command_map.py are HA-free too (verified by
import), so RealBlueSharkDeviceAdapterTests below exercises the pure
adapters in entity_specs.py against the real, landed BlueSharkDevice rather
than a hand-guessed fake.
"""

import unittest
from dataclasses import dataclass
from types import SimpleNamespace

from custom_components.blueshark.codecs import get_codec
from custom_components.blueshark.command_map import validate_command_map
from custom_components.blueshark.const import CONF_ADDRESS, CONF_CHARACTERISTIC, CONF_COMMAND_MAP
from custom_components.blueshark.coordinator import BlueSharkDevice
from custom_components.blueshark.entity_specs import (
    CommandSpec,
    advertising_state,
    build_button_specs,
    build_legacy_button_specs,
    build_number_specs,
    build_switch_specs,
    extract_response_fields,
    is_legacy_runtime,
    is_rejected_verdict,
    link_state,
    verdict_of,
)


MIXED_COMMAND_MAP = {
    "power": {"kind": "button", "name": "Power", "payload_hex": "0801"},
    "brightness": {"kind": "number", "name": "Brightness", "opcode": 0x09, "min": 0, "max": 100},
    "relay": {
        "kind": "switch",
        "name": "Relay",
        "on": {"payload_hex": "0a01"},
        "off": {"payload_hex": "0a00"},
    },
}


class BuildEntitySpecsTests(unittest.TestCase):
    def test_one_of_each_kind_produces_exactly_that_entity_set(self):
        buttons = build_button_specs(MIXED_COMMAND_MAP)
        numbers = build_number_specs(MIXED_COMMAND_MAP)
        switches = build_switch_specs(MIXED_COMMAND_MAP)

        self.assertEqual([(s.key, s.name) for s in buttons], [("power", "Power")])
        self.assertEqual([(s.key, s.name) for s in numbers], [("brightness", "Brightness")])
        self.assertEqual([(s.key, s.name) for s in switches], [("relay", "Relay")])
        # Every builder only ever returns entries matching its own kind.
        self.assertEqual(len(buttons) + len(numbers) + len(switches), len(MIXED_COMMAND_MAP))

    def test_unknown_kind_is_skipped_not_crashing(self):
        command_map = {
            "mystery": {"kind": "climate", "name": "Mystery"},
            "power": {"kind": "button", "name": "Power", "payload_hex": "0801"},
        }

        buttons = build_button_specs(command_map)

        self.assertEqual([s.key for s in buttons], ["power"])

    def test_entry_missing_name_is_skipped(self):
        command_map = {"nameless": {"kind": "button", "payload_hex": "0801"}}

        self.assertEqual(build_button_specs(command_map), [])

    def test_non_mapping_entry_is_skipped(self):
        command_map = {"power": "not-a-dict"}

        self.assertEqual(build_button_specs(command_map), [])

    def test_empty_or_none_command_map_produces_nothing(self):
        self.assertEqual(build_button_specs(None), [])
        self.assertEqual(build_number_specs({}), [])
        self.assertEqual(build_switch_specs(None), [])

    def test_unique_id_is_scoped_by_entry_kind_and_key(self):
        spec = CommandSpec(key="power", name="Power", entry={"kind": "button"})

        self.assertEqual(spec.unique_id("entry123", "button"), "entry123_button_power")


class LegacyButtonSpecsTests(unittest.TestCase):
    def test_only_tested_non_synthetic_commands_become_buttons(self):
        profile = {
            "synthetic": False,
            "commands": [
                {"id": "a", "name": "A", "stage": "tested", "synthetic": False},
                {"id": "b", "name": "B", "stage": "discovered", "synthetic": False},
                {"id": "c", "name": "C", "stage": "tested", "synthetic": True},
            ],
        }

        specs = build_legacy_button_specs(profile)

        self.assertEqual([c["id"] for c in specs], ["a"])

    def test_synthetic_profile_produces_no_buttons(self):
        profile = {
            "synthetic": True,
            "commands": [{"id": "a", "name": "A", "stage": "tested", "synthetic": False}],
        }

        self.assertEqual(build_legacy_button_specs(profile), [])


class RuntimeKindTests(unittest.TestCase):
    def test_dict_runtime_is_legacy(self):
        self.assertTrue(is_legacy_runtime({"profile": {}}))

    def test_object_runtime_is_not_legacy(self):
        self.assertFalse(is_legacy_runtime(SimpleNamespace(command_map={})))


class VerdictTests(unittest.TestCase):
    def test_verdict_of_bare_string_result(self):
        self.assertEqual(verdict_of("accepted"), "accepted")

    def test_verdict_of_object_with_verdict_attribute(self):
        result = SimpleNamespace(verdict="rejected_unknown_id")

        self.assertEqual(verdict_of(result), "rejected_unknown_id")

    def test_verdict_of_mapping_result(self):
        self.assertEqual(verdict_of({"verdict": "no_response"}), "no_response")

    def test_verdict_of_falls_back_to_runtime_last_response(self):
        runtime = SimpleNamespace(last_response=SimpleNamespace(verdict="accepted"))

        self.assertEqual(verdict_of(None, runtime), "accepted")

    def test_verdict_of_unknown_shape_is_none(self):
        self.assertIsNone(verdict_of(12345))

    def test_is_rejected_verdict_classification(self):
        # Only an explicit device-side refusal is a rejection.
        self.assertTrue(is_rejected_verdict("rejected_unknown_id"))
        self.assertTrue(is_rejected_verdict("rejected_other"))
        self.assertFalse(is_rejected_verdict("accepted"))

    def test_no_response_and_undecodable_are_not_rejections(self):
        # A write-without-response command legitimately gets "no_response"
        # every time; an optimistic entity must still commit that write.
        self.assertFalse(is_rejected_verdict("no_response"))
        self.assertFalse(is_rejected_verdict("undecodable"))

    def test_unknown_verdict_is_not_treated_as_rejected(self):
        # An entity should not get stuck refusing to reflect a write just
        # because the runtime hasn't reported a verdict yet.
        self.assertFalse(is_rejected_verdict(None))


@dataclass(frozen=True)
class _FakeLastResponse:
    sent: bytes
    response: bytes | None
    verdict: str
    status: int | None
    elapsed_ms: int


class ExtractResponseFieldsTests(unittest.TestCase):
    def test_extracts_hex_and_opcode_from_bytes_fields(self):
        last_response = _FakeLastResponse(
            sent=b"\x08\xff", response=b"\x00", verdict="accepted", status=0, elapsed_ms=42
        )

        fields = extract_response_fields(last_response)

        self.assertEqual(
            fields,
            {
                "verdict": "accepted",
                "sent_hex": "08ff",
                "response_hex": "00",
                "status": 0,
                "elapsed_ms": 42,
                "opcode": 0x08,
            },
        )

    def test_missing_response_yields_none_response_hex(self):
        last_response = _FakeLastResponse(
            sent=b"\x09", response=None, verdict="no_response", status=None, elapsed_ms=1500
        )

        fields = extract_response_fields(last_response)

        self.assertIsNone(fields["response_hex"])
        self.assertEqual(fields["opcode"], 0x09)

    def test_accepts_pre_hexed_mapping_shape(self):
        last_response = {
            "sent_hex": "0801",
            "response_hex": "00",
            "verdict": "accepted",
            "status": 0,
            "elapsed_ms": 10,
            "opcode": 8,
        }

        fields = extract_response_fields(last_response)

        self.assertEqual(fields["sent_hex"], "0801")
        self.assertEqual(fields["opcode"], 8)


class LinkStateTests(unittest.TestCase):
    def test_busy_takes_priority_over_connected(self):
        transport = SimpleNamespace(is_busy=lambda: True, current_operation="send fff1")
        runtime = SimpleNamespace(transport=transport, connected=True)

        self.assertEqual(link_state(runtime), ("busy", "send fff1"))

    def test_connected_when_not_busy(self):
        transport = SimpleNamespace(is_busy=lambda: False, current_operation=None)
        runtime = SimpleNamespace(transport=transport, connected=True)

        self.assertEqual(link_state(runtime), ("connected", None))

    def test_connecting_when_operation_in_flight_but_not_yet_connected(self):
        transport = SimpleNamespace(is_busy=lambda: False, current_operation="enumerate")
        runtime = SimpleNamespace(transport=transport, connected=False)

        self.assertEqual(link_state(runtime), ("connecting", "enumerate"))

    def test_idle_with_no_transport_at_all(self):
        runtime = SimpleNamespace(transport=None, connected=False)

        self.assertEqual(link_state(runtime), ("idle", None))


class AdvertisingStateTests(unittest.TestCase):
    def test_true_and_false_pass_through(self):
        self.assertTrue(advertising_state(SimpleNamespace(advertising=True)))
        self.assertFalse(advertising_state(SimpleNamespace(advertising=False)))

    def test_missing_attribute_is_unknown_none(self):
        self.assertIsNone(advertising_state(SimpleNamespace()))


class _FakeTransport:
    """Minimal duck-typed stand-in for BleTransport: request()/is_busy()/current_operation.

    coordinator.py only ever calls these three members on `transport` (verified by
    reading its `_async_send`/`connected`/`link_state` usage), never `isinstance`s it,
    so this is a faithful substitute without importing the real, HA-dependent module.
    """

    def __init__(self, response: bytes | None) -> None:
        self.connected = False
        self.on_connection_changed = None
        self._response = response
        self.requests: list[tuple[str, bytes, int]] = []

    async def request(self, characteristic: str, payload: bytes, await_response_ms: int):
        self.requests.append((characteristic, payload, await_response_ms))
        return SimpleNamespace(sent=payload, response=self._response, elapsed_ms=7)

    def is_busy(self) -> bool:
        return False

    @property
    def current_operation(self) -> str | None:
        return None


def _make_device(response: bytes | None, command_map: dict) -> BlueSharkDevice:
    entry = SimpleNamespace(
        data={
            CONF_ADDRESS: "01:00:00:67:0D:8A",
            CONF_CHARACTERISTIC: "0000fff1-0000-1000-8000-00805f9b34fb",
        },
        options={CONF_COMMAND_MAP: command_map},
        title="Test Device",
    )
    transport = _FakeTransport(response)
    codec = get_codec("raw", {})
    return BlueSharkDevice(hass=SimpleNamespace(), entry=entry, transport=transport, codec=codec)


class RealBlueSharkDeviceAdapterTests(unittest.IsolatedAsyncioTestCase):
    """Exercises entity_specs' runtime adapters against the real, landed BlueSharkDevice.

    Confirms verdict_of/is_rejected_verdict/extract_response_fields/link_state agree
    with the actual `LastResponse` dataclass shape and the real `sweep.verdict()`
    vocabulary, not just the shape assumed while coordinator.py was still being
    written by a sibling. coordinator.py has no Home Assistant import at module
    scope, so this is importable in the same sandbox as everything else here.
    """

    command_map = validate_command_map(
        {
            "power": {"kind": "button", "name": "Power", "payload_hex": "08ff"},
            "brightness": {
                "kind": "number",
                "name": "Brightness",
                "opcode": 0x09,
                "min": 0,
                "max": 100,
            },
            "relay": {
                "kind": "switch",
                "name": "Relay",
                "on": {"payload_hex": "0a01"},
                "off": {"payload_hex": "0a00"},
            },
        }
    )

    async def test_accepted_write_commits_and_matches_last_response_sensor_fields(self):
        device = _make_device(response=b"\x00", command_map=self.command_map)

        result = await device.async_send_command("power")

        verdict = verdict_of(result)
        self.assertEqual(verdict, "accepted")
        self.assertFalse(is_rejected_verdict(verdict))
        fields = extract_response_fields(device.last_response)
        self.assertEqual(fields["sent_hex"], "08ff")
        self.assertEqual(fields["status"], 0)
        self.assertEqual(fields["opcode"], 0x08)

    async def test_rejected_unknown_id_blocks_optimistic_commit(self):
        device = _make_device(response=b"\x05", command_map=self.command_map)

        result = await device.async_send_number("brightness", 42)

        verdict = verdict_of(result, device)
        self.assertEqual(verdict, "rejected_unknown_id")
        self.assertTrue(is_rejected_verdict(verdict))

    async def test_no_response_does_not_block_optimistic_commit(self):
        # A write-without-response send that never gets a notification back.
        device = _make_device(response=None, command_map=self.command_map)

        result = await device.async_send_switch("relay", on=True)

        verdict = verdict_of(result, device)
        self.assertEqual(verdict, "no_response")
        self.assertFalse(is_rejected_verdict(verdict))

    async def test_undecodable_reply_does_not_block_optimistic_commit(self):
        device = _make_device(response=b"", command_map=self.command_map)

        result = await device.async_send_command("power")

        verdict = verdict_of(result, device)
        self.assertEqual(verdict, "undecodable")
        self.assertFalse(is_rejected_verdict(verdict))

    async def test_link_state_reflects_the_real_transport_connected_flag(self):
        device = _make_device(response=b"\x00", command_map=self.command_map)

        self.assertEqual(link_state(device), ("idle", None))
        device.transport.connected = True
        self.assertEqual(link_state(device), ("connected", None))

    async def test_device_info_and_command_map_are_readable_through_getattr(self):
        device = _make_device(response=b"\x00", command_map=self.command_map)

        self.assertEqual(device.device_info["name"], "Test Device")
        self.assertEqual(set(device.command_map), {"power", "brightness", "relay"})


if __name__ == "__main__":
    unittest.main()
