"""Opcode sweep planning and interpretation.

A sweep writes every opcode in a range to a device and classifies each reply.
Codecs are duck-typed: anything exposing the :class:`SweepCodec` surface can
drive a sweep, so this module never imports a concrete protocol.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


class SweepCodec(Protocol):
    """Surface a codec must expose to plan and interpret a sweep."""

    destructive_opcodes: frozenset[int]
    canary: tuple[int, bytes] | None

    def decode(self, frame: bytes) -> bytes | None:
        """Return the decoded reply body, or ``None`` if the frame is malformed."""

    def status(self, decoded: bytes) -> int | None:
        """Return the status byte of a decoded reply, or ``None`` if it has none."""

    def classify(self, request: bytes | None, decoded_response: bytes) -> tuple[str, int | None] | None:
        """Codec-specific ``(verdict, status)`` override, or ``None`` to defer to the generic
        status-byte table below.  ``request`` is the unframed payload that was written, when
        known.  Optional: codecs (and test doubles) that have no opinion simply omit it."""


@dataclass(frozen=True)
class SweepStep:
    """One write in a sweep: a probe of ``opcode`` or a canary health check."""

    kind: str  # "probe" | "canary"
    opcode: int
    payload: bytes  # bytes([opcode]) + argument


# CoolLED-derived default baked into the generic verdict: status byte 5 means
# "unknown command id".  Other codecs may use 5 for something unrelated; that
# is a known v1 simplification.
_STATUS_UNKNOWN_ID = 5


def plan_sweep(
    start: int,
    end: int,
    argument: bytes,
    include_destructive: bool,
    codec: SweepCodec,
    canary_interval: int = 5,
) -> list[SweepStep]:
    """Plan probes for ``start..end`` inclusive, bracketed by canary checks.

    When the codec has a canary it is sent before the first probe, after every
    ``canary_interval`` probes, and once more after the last probe.  These are
    three independent triggers, so two canaries back to back is expected when
    the probe count is a multiple of the interval.
    """

    if end < start:
        raise ValueError(f"sweep end {end} is before start {start}")
    probe_opcodes = [
        op
        for op in range(start, end + 1)
        if include_destructive or op not in codec.destructive_opcodes
    ]
    if codec.canary is None:
        return [SweepStep("probe", op, bytes([op]) + argument) for op in probe_opcodes]
    canary_op, canary_arg = codec.canary
    canary = SweepStep("canary", canary_op, bytes([canary_op]) + canary_arg)
    steps = [canary]
    for i, op in enumerate(probe_opcodes, start=1):
        steps.append(SweepStep("probe", op, bytes([op]) + argument))
        if i % canary_interval == 0:
            steps.append(canary)
    steps.append(canary)
    return steps


def verdict(
    codec: SweepCodec, response: bytes | None, request: bytes | None = None
) -> tuple[str, int | None]:
    """Classify one reply as ``(verdict, status)``.

    ``request`` is the unframed payload that was written, when known.  It lets a codec's
    ``classify`` hook recognise a reply that echoes the request rather than answering with a
    status byte (see ``CoolLedCodec.classify``); codecs with no such hook, or that decline to
    classify a given reply, fall through to the generic status-byte table below unchanged.
    """

    if response is None:
        return "no_response", None
    decoded = codec.decode(response)
    if decoded is None:
        return "undecodable", None
    classify = getattr(codec, "classify", None)
    if classify is not None:
        override = classify(request, decoded)
        if override is not None:
            return override
    status = codec.status(decoded)
    if status is None:
        return "undecodable", None
    if status == 0:
        return "accepted", status
    if status == _STATUS_UNKNOWN_ID:
        return "rejected_unknown_id", status
    return "rejected_other", status


def interpret_sweep(
    codec: SweepCodec, step_results: list[tuple[SweepStep, bytes | None]]
) -> dict[str, object]:
    """Classify sweep replies in order, stopping at the first silent canary.

    Only probe opcodes are bucketed into ``accepted``/``unknown``/``no_response``;
    canary rows are kept in ``results`` for evidence.  A canary with no reply
    means the device stopped talking, so that step and everything after it is
    dropped and ``aborted`` is set.
    """

    results: list[dict[str, object]] = []
    accepted: list[int] = []
    unknown: list[int] = []
    no_response: list[int] = []
    probe_count = 0
    aborted = False
    message: str | None = None
    for step, response in step_results:
        v, s = verdict(codec, response, step.payload)
        if step.kind == "canary" and v == "no_response":
            aborted = True
            message = f"device stopped responding after step {probe_count}"
            break
        results.append(
            {
                "opcode": step.opcode,
                "argument_hex": step.payload[1:].hex(),
                "kind": step.kind,
                "response_hex": response.hex() if response is not None else None,
                "verdict": v,
                "status": s,
            }
        )
        if step.kind == "probe":
            probe_count += 1
            if v == "accepted":
                accepted.append(step.opcode)
            elif v == "rejected_unknown_id":
                unknown.append(step.opcode)
            elif v == "no_response":
                no_response.append(step.opcode)
    return {
        "results": results,
        "accepted": accepted,
        "unknown": unknown,
        "no_response": no_response,
        "aborted": aborted,
        "message": message,
    }
