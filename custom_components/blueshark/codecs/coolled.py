"""CoolLED framing, as used by CoolLEDX / iLedClock BLE signs and clocks.

Frame layout: ``0x01``, big-endian 16-bit payload length, payload, ``0x03``.
Every body byte below ``0x04`` is byte-stuffed as ``0x02`` followed by the
byte plus ``0x04``, so the start, escape and end markers never occur inside
the body.

Hardware-verified, over an ESPHome BLE proxy against a real iLedClock (fff1,
opcode 0x08): the device replies to a write by echoing the opcode back
followed by the resulting value, not a status byte - ``08 40`` -> ``08 FE``
(canary, no visible change) and ``08 FF`` -> ``08 FF`` (visibly changed the
clock).  See ``classify`` below.
[INFERENCE]: ``status_names`` and the meaning of every other opcode.  No
notification carrying an actual status byte (as opposed to an echoed
opcode+value) has ever been observed from this hardware; the table is
ported from a public driver for a shifted variant of the same protocol
family, and is only consulted when a reply does not echo the request.
"""

from __future__ import annotations

from . import Codec

_START = 0x01
_ESCAPE = 0x02
_END = 0x03
# Bytes below this are stuffed as (_ESCAPE, byte + _ESCAPE_BASE).
_ESCAPE_BASE = 0x04


class CoolLedCodec(Codec):
    id = "coolled"
    label = "CoolLED (CoolLEDX / iLedClock)"
    destructive_opcodes = frozenset({0x05, 0x07, 0x09, 0x0B, 0x0D, 0x0F, 0x12, 0x14})
    canary = (0x08, bytes([0x40]))
    status_names = {
        0x00: "SUCCESS",
        0x01: "TRANSMISSION_FAILED",
        0x02: "DEVICE_ABNORMALITY",
        0x03: "DATA_ERROR",
        0x04: "DATA_LENGTH_ERROR",
        0x05: "DATA_ID_ERROR",
        0x06: "DATA_CHECKSUM_ERROR",
    }

    def encode(self, payload: bytes) -> bytes:
        frame = bytearray((_START,))
        for byte in len(payload).to_bytes(2, "big") + payload:
            if byte < _ESCAPE_BASE:
                frame.append(_ESCAPE)
                frame.append(byte + _ESCAPE_BASE)
            else:
                frame.append(byte)
        frame.append(_END)
        return bytes(frame)

    def decode(self, frame: bytes) -> bytes | None:
        if len(frame) < 4 or frame[0] != _START or frame[-1] != _END:
            return None
        body = bytearray()
        inner = iter(frame[1:-1])
        for byte in inner:
            if byte == _ESCAPE:
                follower = next(inner, None)
                if follower is None:
                    return None  # dangling escape
                byte = follower - _ESCAPE_BASE
                if not 0 <= byte < _ESCAPE_BASE:
                    return None  # follower outside 0x04..0x07
            body.append(byte)
        if len(body) < 2 or int.from_bytes(body[:2], "big") != len(body) - 2:
            return None
        return bytes(body[2:])

    def classify(self, request: bytes | None, decoded_response: bytes) -> tuple[str, int | None] | None:
        """``accepted`` with the echoed value when the reply echoes the request's opcode.

        Hardware-observed: this device acknowledges a write by echoing back the opcode
        byte followed by the resulting value - not a status code from ``status_names``.
        The second element of the returned tuple is that echoed *value*, reusing the wire
        `status` field for it since the wire shape is unchanged; it is not a status.
        Falls through (returns ``None``) to the status-byte table only when the reply's
        first byte does not match the opcode that was written, or when there is no request
        to compare against.
        """
        if not request or not decoded_response or decoded_response[0] != request[0]:
            return None
        value = decoded_response[1] if len(decoded_response) > 1 else None
        return "accepted", value
