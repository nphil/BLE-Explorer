"""Generic header / trailer / checksum framing, without byte-stuffing.

Covers the many simple BLE protocols that wrap a payload in fixed marker
bytes and optionally append a checksum: ``header + payload + checksum +
trailer``.  Protocols that escape bytes inside the body need their own
codec (see ``coolled``).
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from functools import reduce
from operator import xor

from . import Codec


def _crc16_modbus(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF


# kind -> (width in bytes, integer checksum of the payload); packed little-endian.
_CHECKSUMS: dict[str, tuple[int, Callable[[bytes], int]]] = {
    "none": (0, lambda data: 0),
    "sum8": (1, lambda data: sum(data) & 0xFF),
    "xor8": (1, lambda data: reduce(xor, data, 0)),
    "crc16": (2, _crc16_modbus),
}


def checksum_bytes(kind: str, payload: bytes) -> bytes:
    """Checksum of ``payload`` as the bytes that follow it on the wire."""
    width, compute = _CHECKSUMS[kind]
    return compute(payload).to_bytes(width, "little")


@dataclass(frozen=True)
class PrefixSuffixCodec(Codec):
    id = "prefix_suffix"
    label = "Header / trailer / checksum"

    header: bytes = b""
    trailer: bytes = b""
    checksum: str = "none"

    def __post_init__(self) -> None:
        if self.checksum not in _CHECKSUMS:
            raise ValueError(
                f"checksum must be one of {', '.join(_CHECKSUMS)}, not {self.checksum!r}"
            )

    def encode(self, payload: bytes) -> bytes:
        return self.header + payload + checksum_bytes(self.checksum, payload) + self.trailer

    def decode(self, frame: bytes) -> bytes | None:
        if len(frame) < len(self.header) + len(self.trailer):
            return None
        if not (frame.startswith(self.header) and frame.endswith(self.trailer)):
            return None
        # bytes(): notifications arrive as bytearray, decoded payloads must not.
        body = bytes(frame[len(self.header) : len(frame) - len(self.trailer)])
        width = _CHECKSUMS[self.checksum][0]
        if width == 0:
            return body
        if len(body) < width:
            return None
        payload, given = body[:-width], body[-width:]
        if checksum_bytes(self.checksum, payload) != given:
            return None
        return payload
