"""Unit tests for command map validation and encoding."""

import unittest

from custom_components.blueshark.command_map import (
    MAX_COMMANDS,
    MAX_PAYLOAD_BYTES,
    CommandMapError,
    encode_command,
    encode_switch_command,
    validate_command_map,
)

CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"
OTHER_CHARACTERISTIC = "0000ffe2-0000-1000-8000-00805f9b34fb"
BUTTON = {"name": "X", "kind": "button", "payload_hex": "01"}


def validate_one(entry):
    """Validate a single entry under id ``cmd`` and return its normalized form."""

    return validate_command_map({"cmd": entry})["cmd"]


class CommandMapTests(unittest.TestCase):
    def test_button_with_payload_hex_encodes_the_payload(self):
        entry = validate_one(
            {
                "name": "Power",
                "kind": "button",
                "payload_hex": "7e0404f00001ff00ef",
                "characteristic": CHARACTERISTIC,
            }
        )
        self.assertEqual(
            encode_command(entry), (bytes.fromhex("7e0404f00001ff00ef"), CHARACTERISTIC)
        )

    def test_button_with_opcode_and_argument_encodes_opcode_first(self):
        entry = validate_one(
            {"name": "Mode", "kind": "button", "opcode": 0x0A, "argument_hex": "ff 00"}
        )
        self.assertEqual(encode_command(entry), (b"\x0a\xff\x00", None))
        bare = validate_one({"name": "Ping", "kind": "button", "opcode": 0x0A, "argument_hex": ""})
        self.assertEqual(encode_command(bare), (b"\x0a", None))

    def test_button_needs_exactly_one_payload_form(self):
        with self.assertRaisesRegex(CommandMapError, "not both"):
            validate_one(
                {"name": "X", "kind": "button", "payload_hex": "01", "opcode": 1, "argument_hex": ""}
            )
        with self.assertRaisesRegex(CommandMapError, "must specify payload_hex"):
            validate_one({"name": "X", "kind": "button"})
        with self.assertRaisesRegex(CommandMapError, "opcode together with argument_hex"):
            validate_one({"name": "X", "kind": "button", "opcode": 1})

    def test_number_encodes_value_within_bounds(self):
        entry = validate_one(
            {"name": "Brightness", "kind": "number", "opcode": 0x05, "min": 1, "max": 100}
        )
        self.assertEqual((entry["min"], entry["max"]), (1, 100))
        self.assertEqual(encode_command(entry, value=50), (b"\x05\x32", None))
        self.assertEqual(encode_command(entry, value=1)[0], b"\x05\x01")
        self.assertEqual(encode_command(entry, value=100)[0], b"\x05\x64")
        defaults = validate_one({"name": "Speed", "kind": "number", "opcode": 0x06})
        self.assertEqual((defaults["min"], defaults["max"]), (0, 255))
        self.assertEqual(encode_command(defaults, value=255)[0], b"\x06\xff")

    def test_number_rejects_out_of_range_or_missing_value(self):
        entry = validate_one(
            {"name": "Brightness", "kind": "number", "opcode": 0x05, "min": 1, "max": 100}
        )
        for bad in (0, 101):
            with self.subTest(value=bad), self.assertRaisesRegex(CommandMapError, "from 1 to 100"):
                encode_command(entry, value=bad)
        with self.assertRaisesRegex(CommandMapError, "need a value"):
            encode_command(entry)
        with self.assertRaisesRegex(CommandMapError, "must be an integer"):
            encode_command(entry, value=50.0)

    def test_number_rejects_invalid_bounds(self):
        with self.assertRaisesRegex(CommandMapError, "min must be less than max"):
            validate_one({"name": "X", "kind": "number", "opcode": 1, "min": 10, "max": 10})
        with self.assertRaisesRegex(CommandMapError, "max must be an integer from 0 to 255"):
            validate_one({"name": "X", "kind": "number", "opcode": 1, "max": 256})
        with self.assertRaisesRegex(CommandMapError, "opcode is required"):
            validate_one({"name": "X", "kind": "number"})

    def test_number_rejects_payload_fields(self):
        for field in ("payload_hex", "argument_hex"):
            with self.subTest(field=field), self.assertRaisesRegex(CommandMapError, field):
                validate_one({"name": "X", "kind": "number", "opcode": 1, field: "01"})

    def test_switch_encodes_on_and_off_with_characteristic_fallback(self):
        entry = validate_one(
            {
                "name": "Fan",
                "kind": "switch",
                "characteristic": CHARACTERISTIC,
                "on": {"payload_hex": "01 01"},
                "off": {"opcode": 1, "argument_hex": "00", "characteristic": OTHER_CHARACTERISTIC},
            }
        )
        self.assertEqual(encode_switch_command(entry, on=True), (b"\x01\x01", CHARACTERISTIC))
        self.assertEqual(
            encode_switch_command(entry, on=False), (b"\x01\x00", OTHER_CHARACTERISTIC)
        )
        with self.assertRaisesRegex(CommandMapError, "encode_switch_command"):
            encode_command(entry)
        with self.assertRaisesRegex(CommandMapError, "only switch commands"):
            encode_switch_command(validate_one(BUTTON), on=True)

    def test_switch_requires_both_states(self):
        with self.assertRaisesRegex(CommandMapError, r"cmd\.off is required"):
            validate_one({"name": "X", "kind": "switch", "on": {"payload_hex": "01"}})

    def test_unknown_top_level_field_is_rejected(self):
        with self.assertRaisesRegex(CommandMapError, "unknown fields for kind button: icon"):
            validate_one({**BUTTON, "icon": "mdi:led"})
        # Fields documented for other kinds are still unknown for this one.
        with self.assertRaisesRegex(CommandMapError, "unknown fields for kind button: min, on"):
            validate_one({**BUTTON, "min": 0, "on": {}})

    def test_unknown_field_inside_switch_state_is_rejected(self):
        with self.assertRaisesRegex(CommandMapError, r"cmd\.on has unknown fields: name"):
            validate_one(
                {
                    "name": "X",
                    "kind": "switch",
                    "on": {"payload_hex": "01", "name": "On"},
                    "off": {"payload_hex": "00"},
                }
            )

    def test_hex_fields_are_normalized_without_mutating_input(self):
        raw = {
            "led": {"name": "LED", "kind": "button", "payload_hex": "01 02"},
            "arg": {"name": "Arg", "kind": "button", "opcode": 1, "argument_hex": "AB cd"},
        }
        normalized = validate_command_map(raw)
        self.assertEqual(normalized["led"]["payload_hex"], "0102")
        self.assertEqual(normalized["arg"]["argument_hex"], "abcd")
        self.assertEqual(raw["led"]["payload_hex"], "01 02")
        for bad in ("0x01", "abc"):
            with self.subTest(hex=bad), self.assertRaisesRegex(
                CommandMapError, "hexadecimal byte pairs"
            ):
                validate_one({**BUTTON, "payload_hex": bad})

    def test_command_ids_must_match_the_id_pattern(self):
        self.assertIn("ok.id-1_", validate_command_map({"ok.id-1_": BUTTON}))
        for bad in ("has space", "_leading", "", "x" * 65):
            with self.subTest(id=bad), self.assertRaisesRegex(CommandMapError, "command id"):
                validate_command_map({bad: BUTTON})

    def test_map_shape_limits(self):
        with self.assertRaisesRegex(CommandMapError, "command map must be an object"):
            validate_command_map([])
        with self.assertRaisesRegex(CommandMapError, "cmd must be an object"):
            validate_one("not an object")
        full = {f"c{i}": BUTTON for i in range(MAX_COMMANDS)}
        self.assertEqual(len(validate_command_map(full)), MAX_COMMANDS)
        full[f"c{MAX_COMMANDS}"] = BUTTON
        with self.assertRaisesRegex(CommandMapError, f"at most {MAX_COMMANDS} commands"):
            validate_command_map(full)

    def test_payload_size_limit(self):
        validate_one({**BUTTON, "payload_hex": "00" * MAX_PAYLOAD_BYTES})
        with self.assertRaisesRegex(CommandMapError, f"1..{MAX_PAYLOAD_BYTES} bytes"):
            validate_one({**BUTTON, "payload_hex": "00" * (MAX_PAYLOAD_BYTES + 1)})
        with self.assertRaisesRegex(CommandMapError, "payload_hex must not be empty"):
            validate_one({**BUTTON, "payload_hex": ""})
        opcode_form = {"name": "X", "kind": "button", "opcode": 1}
        validate_one({**opcode_form, "argument_hex": "00" * (MAX_PAYLOAD_BYTES - 1)})
        with self.assertRaisesRegex(CommandMapError, f"at most {MAX_PAYLOAD_BYTES} bytes"):
            validate_one({**opcode_form, "argument_hex": "00" * MAX_PAYLOAD_BYTES})

    def test_characteristic_must_be_a_uuid(self):
        entry = validate_one({**BUTTON, "characteristic": CHARACTERISTIC.upper()})
        self.assertEqual(entry["characteristic"], CHARACTERISTIC)
        with self.assertRaisesRegex(CommandMapError, "characteristic must be a valid UUID"):
            validate_one({**BUTTON, "characteristic": "ffe1"})

    def test_kind_name_and_note_are_checked(self):
        with self.assertRaisesRegex(CommandMapError, "kind must be one of button, number, switch"):
            validate_one({"name": "X", "kind": "slider", "opcode": 1})
        with self.assertRaisesRegex(CommandMapError, "kind must be one of"):
            validate_one({"name": "X", "payload_hex": "01"})
        with self.assertRaisesRegex(CommandMapError, "name is required"):
            validate_one({"kind": "button", "payload_hex": "01"})
        with self.assertRaisesRegex(CommandMapError, "name must not be empty"):
            validate_one({**BUTTON, "name": " "})
        with self.assertRaisesRegex(CommandMapError, "name must be at most 128"):
            validate_one({**BUTTON, "name": "x" * 129})
        with self.assertRaisesRegex(CommandMapError, "note must be at most 512"):
            validate_one({**BUTTON, "note": "n" * 513})
        entry = validate_one({**BUTTON, "name": " Power ", "note": ""})
        self.assertEqual((entry["name"], entry["note"]), ("Power", ""))

    def test_opcode_must_be_a_byte_integer(self):
        for bad in (256, "5", True):
            with self.subTest(opcode=bad), self.assertRaisesRegex(
                CommandMapError, "opcode must be an integer from 0 to 255"
            ):
                validate_one({"name": "X", "kind": "button", "opcode": bad, "argument_hex": ""})
