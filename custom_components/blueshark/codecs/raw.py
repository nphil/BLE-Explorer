"""Raw codec: payloads are written and notifications read exactly as given."""

from __future__ import annotations

from . import Codec


class RawCodec(Codec):
    id = "raw"
    label = "Raw (no framing)"

    def encode(self, payload: bytes) -> bytes:
        return payload

    def decode(self, frame: bytes) -> bytes:
        return frame
