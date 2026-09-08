"""Unit tests for the strict profile contract."""

import json
import unittest
from pathlib import Path

from custom_components.ble_studio.profile import (
    ProfileValidationError,
    parse_profile,
    validate_profile,
)


EXAMPLE = json.loads(
    Path(__file__).parents[1].joinpath("profiles/example.json").read_text()
)


class ProfileValidatorTests(unittest.TestCase):
    def test_example_profile_is_valid_and_uuid_is_normalized(self):
        profile = validate_profile(EXAMPLE)
        self.assertEqual(profile["schema_version"], 2)
        self.assertEqual(
            profile["commands"][0]["service"],
            "12345678-1234-5678-1234-56789abcdef0",
        )

    def test_duplicate_command_ids_are_rejected(self):
        profile = json.loads(json.dumps(EXAMPLE))
        profile["commands"].append(dict(profile["commands"][0]))
        with self.assertRaisesRegex(ProfileValidationError, "duplicate command id"):
            validate_profile(profile)

    def test_unknown_fields_are_rejected(self):
        profile = json.loads(json.dumps(EXAMPLE))
        profile["commands"][0]["arbitrary_support"] = True
        with self.assertRaisesRegex(ProfileValidationError, "fields must be exactly"):
            validate_profile(profile)

    def test_response_must_be_an_explicit_boolean(self):
        profile = json.loads(json.dumps(EXAMPLE))
        profile["commands"][0]["response"] = 1
        with self.assertRaisesRegex(ProfileValidationError, "response must be a boolean"):
            validate_profile(profile)

    def test_only_tested_even_hex_commands_are_allowed(self):
        profile = json.loads(json.dumps(EXAMPLE))
        profile["commands"][0]["stage"] = "discovered"
        with self.assertRaisesRegex(ProfileValidationError, "stage must be 'tested'"):
            validate_profile(profile)
        profile["commands"][0]["stage"] = "tested"
        profile["commands"][0]["value"] = "0x01"
        with self.assertRaisesRegex(ProfileValidationError, "hexadecimal byte pairs"):
            validate_profile(profile)

    def test_payload_limits_and_spaced_hex_are_enforced(self):
        profile = json.loads(json.dumps(EXAMPLE))
        profile["commands"][0]["value"] = "01 01"
        self.assertEqual(validate_profile(profile)["commands"][0]["value"], "0101")
        profile["commands"][0]["value"] = "00" * 513
        with self.assertRaisesRegex(ProfileValidationError, "1..512 bytes"):
            validate_profile(profile)

    def test_duplicate_json_keys_are_rejected(self):
        with self.assertRaisesRegex(ProfileValidationError, "duplicate JSON key"):
            parse_profile('{"schema_version":2,"schema_version":2}')

