"""Unit tests for opcode sweep planning and interpretation."""

import unittest

from custom_components.blueshark.sweep import (
    SweepStep,
    interpret_sweep,
    plan_sweep,
    verdict,
)


class CanaryCodec:
    """Codec double: replies are 0xAA followed by a body whose first byte is the status."""

    destructive_opcodes = frozenset({5, 7})
    canary = (8, b"\x40")

    def decode(self, frame):
        return frame[1:] if frame[:1] == b"\xaa" else None

    def status(self, decoded):
        return decoded[0] if decoded else None


class PlainCodec(CanaryCodec):
    """Codec double with no canary and nothing marked destructive."""

    destructive_opcodes = frozenset()
    canary = None


class EchoingCodec(CanaryCodec):
    """Codec double whose device echoes the request's opcode plus a resulting value,
    like the real CoolLED/iLedClock hardware, instead of a status byte."""

    def classify(self, request, decoded_response):
        if not request or not decoded_response or decoded_response[0] != request[0]:
            return None
        return "accepted", decoded_response[1] if len(decoded_response) > 1 else None


ACCEPTED = b"\xaa\x00"
UNKNOWN_ID = b"\xaa\x05"
REJECTED = b"\xaa\x03"
CANARY = SweepStep("canary", 8, b"\x08\x40")


def probe(opcode):
    return SweepStep("probe", opcode, bytes([opcode, 0x00]))


class PlanSweepTests(unittest.TestCase):
    def test_canaries_bracket_the_sweep_and_recur_every_fifth_probe(self):
        steps = plan_sweep(1, 10, b"\x00", False, CanaryCodec())
        # Probes are 1,2,3,4,6,8,9,10 (5 and 7 are destructive); the fifth probe is opcode 6.
        self.assertEqual(
            [(step.kind, step.opcode) for step in steps],
            [
                ("canary", 8),
                ("probe", 1),
                ("probe", 2),
                ("probe", 3),
                ("probe", 4),
                ("probe", 6),
                ("canary", 8),
                ("probe", 8),
                ("probe", 9),
                ("probe", 10),
                ("canary", 8),
            ],
        )
        self.assertEqual(len(steps), 11)
        self.assertEqual(steps[0].payload, b"\x08\x40")
        self.assertEqual(steps[1].payload, b"\x01\x00")
        self.assertEqual(steps[9].payload, b"\x0a\x00")

    def test_include_destructive_probes_every_opcode_and_keeps_adjacent_canaries(self):
        steps = plan_sweep(1, 10, b"\x00", True, CanaryCodec())
        self.assertEqual(
            [step.opcode for step in steps if step.kind == "probe"], list(range(1, 11))
        )
        # Ten probes trigger a canary after the tenth; the trailing canary is still added.
        self.assertEqual([step.kind for step in steps[-3:]], ["probe", "canary", "canary"])
        self.assertEqual(len(steps), 14)

    def test_codec_without_canary_yields_only_probes(self):
        steps = plan_sweep(0, 3, b"\x01", False, PlainCodec())
        self.assertEqual(
            steps, [SweepStep("probe", op, bytes([op, 0x01])) for op in range(4)]
        )

    def test_canary_interval_is_honoured(self):
        steps = plan_sweep(1, 4, b"", False, CanaryCodec(), canary_interval=2)
        self.assertEqual(
            [step.kind for step in steps],
            ["canary", "probe", "probe", "canary", "probe", "probe", "canary", "canary"],
        )

    def test_end_before_start_is_rejected(self):
        with self.assertRaises(ValueError):
            plan_sweep(5, 4, b"\x00", False, PlainCodec())


class VerdictTests(unittest.TestCase):
    def setUp(self):
        self.codec = CanaryCodec()

    def test_missing_response(self):
        self.assertEqual(verdict(self.codec, None), ("no_response", None))

    def test_undecodable_frames(self):
        # Wrong header byte: decode() fails.
        self.assertEqual(verdict(self.codec, b"\x00"), ("undecodable", None))
        # Header only: decodes to b"", which carries no status byte.
        self.assertEqual(verdict(self.codec, b"\xaa"), ("undecodable", None))

    def test_status_byte_classification(self):
        self.assertEqual(verdict(self.codec, ACCEPTED), ("accepted", 0))
        self.assertEqual(verdict(self.codec, UNKNOWN_ID), ("rejected_unknown_id", 5))
        self.assertEqual(verdict(self.codec, REJECTED), ("rejected_other", 3))
        self.assertEqual(verdict(self.codec, b"\xaa\xff"), ("rejected_other", 255))

    def test_codec_without_classify_hook_is_unaffected_by_a_request(self):
        # CanaryCodec exposes no classify hook at all; passing a request must not change
        # the generic status-byte classification one bit.
        self.assertEqual(verdict(self.codec, ACCEPTED, request=b"\xaa\x00"), ("accepted", 0))

    def test_classify_hook_overrides_the_status_byte_table_on_an_echo(self):
        codec = EchoingCodec()
        # Request opcode 0x08 is echoed back with 0xfe as the resulting value, not status
        # byte 0xfe from the table.
        self.assertEqual(verdict(codec, b"\xaa\x08\xfe", request=b"\x08\x40"), ("accepted", 0xFE))

    def test_classify_hook_defers_to_the_status_byte_table_when_it_declines(self):
        codec = EchoingCodec()
        # Reply's first byte (0x00) does not echo the request's opcode (0x08), so
        # classify() returns None and the status-byte table (0x00 == accepted) applies.
        self.assertEqual(verdict(codec, b"\xaa\x00", request=b"\x08\x40"), ("accepted", 0))
        self.assertEqual(verdict(codec, b"\xaa\x05", request=b"\x08\x40"), ("rejected_unknown_id", 5))


class InterpretSweepTests(unittest.TestCase):
    def setUp(self):
        self.codec = CanaryCodec()

    def test_probes_are_bucketed_and_canaries_recorded_without_aborting(self):
        outcome = interpret_sweep(
            self.codec,
            [
                (CANARY, ACCEPTED),
                (probe(1), ACCEPTED),
                (probe(2), UNKNOWN_ID),
                (probe(3), None),
                (probe(4), REJECTED),
                # An answered canary never aborts, even when the answer is a rejection.
                (CANARY, UNKNOWN_ID),
                (probe(6), b"\x00"),
                (CANARY, ACCEPTED),
            ],
        )
        self.assertIs(outcome["aborted"], False)
        self.assertIsNone(outcome["message"])
        self.assertEqual(outcome["accepted"], [1])
        self.assertEqual(outcome["unknown"], [2])
        self.assertEqual(outcome["no_response"], [3])
        self.assertEqual(
            [row["kind"] for row in outcome["results"]],
            ["canary", "probe", "probe", "probe", "probe", "canary", "probe", "canary"],
        )
        self.assertEqual(
            outcome["results"][0],
            {
                "opcode": 8,
                "argument_hex": "40",
                "kind": "canary",
                "response_hex": "aa00",
                "verdict": "accepted",
                "status": 0,
            },
        )
        self.assertEqual(
            outcome["results"][3],
            {
                "opcode": 3,
                "argument_hex": "00",
                "kind": "probe",
                "response_hex": None,
                "verdict": "no_response",
                "status": None,
            },
        )
        self.assertEqual(outcome["results"][4]["verdict"], "rejected_other")
        self.assertEqual(outcome["results"][5]["verdict"], "rejected_unknown_id")
        self.assertEqual(outcome["results"][6]["verdict"], "undecodable")

    def test_probe_step_whose_response_echoes_its_opcode_counts_as_accepted(self):
        # Real hardware behaviour: the reply's first byte (0x09) echoes the request's
        # opcode rather than answering with a status byte, so this must land in
        # `accepted`, not be misread as `rejected_other` via CanaryCodec's status table.
        codec = EchoingCodec()
        outcome = interpret_sweep(codec, [(probe(9), b"\xaa\x09\x05")])
        self.assertEqual(outcome["accepted"], [9])
        self.assertEqual(outcome["results"][0]["verdict"], "accepted")
        self.assertEqual(outcome["results"][0]["status"], 5)

    def test_silent_canary_aborts_and_drops_later_steps(self):
        outcome = interpret_sweep(
            self.codec,
            [
                (probe(1), ACCEPTED),
                (probe(2), UNKNOWN_ID),
                (CANARY, None),
                (probe(3), ACCEPTED),
                (probe(4), ACCEPTED),
            ],
        )
        self.assertIs(outcome["aborted"], True)
        self.assertEqual(outcome["message"], "device stopped responding after step 2")
        self.assertEqual([row["opcode"] for row in outcome["results"]], [1, 2])
        self.assertEqual(outcome["accepted"], [1])
        self.assertEqual(outcome["unknown"], [2])

    def test_silent_leading_canary_reports_step_zero(self):
        outcome = interpret_sweep(self.codec, [(CANARY, None), (probe(1), ACCEPTED)])
        self.assertIs(outcome["aborted"], True)
        self.assertEqual(outcome["message"], "device stopped responding after step 0")
        self.assertEqual(outcome["results"], [])
        self.assertEqual(outcome["accepted"], [])

    def test_empty_input_returns_empty_shape(self):
        self.assertEqual(
            interpret_sweep(self.codec, []),
            {
                "results": [],
                "accepted": [],
                "unknown": [],
                "no_response": [],
                "aborted": False,
                "message": None,
            },
        )
