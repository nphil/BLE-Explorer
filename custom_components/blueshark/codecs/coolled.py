"""CoolLED framing, as used by CoolLEDX / iLedClock BLE signs and clocks.

Frame layout: ``0x01``, big-endian 16-bit payload length, payload, ``0x03``.
Every body byte below ``0x04`` is byte-stuffed as ``0x02`` followed by the
byte plus ``0x04``, so the start, escape and end markers never occur inside
the body.

Hardware-verified: this framing and escaping, and the opcode-0x08 write.
[INFERENCE]: ``status_names`` and the meaning of every other opcode.  No
notification has ever been observed from this hardware; the status table is
ported from a public driver for a shifted variant of the same protocol
family.
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
