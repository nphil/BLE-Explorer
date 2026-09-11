"""Guided-onboarding ``number`` entities: one byte value appended to an opcode.

Only the guided (command-map) config-entry path has numbers; the legacy
profile-import path never did and still doesn't.
"""

from __future__ import annotations

import logging

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError

from .const import DOMAIN
from .entity_specs import (
    CommandSpec,
    RuntimeAvailableMixin,
    RuntimeListenerMixin,
    build_number_specs,
    is_legacy_runtime,
    is_rejected_verdict,
    verdict_of,
)

_LOGGER = logging.getLogger(__name__)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities
) -> None:
    """Add one number entity per guided command-map entry of kind ``number``."""

    runtime = hass.data[DOMAIN][entry.entry_id]
    if is_legacy_runtime(runtime):
        return
    command_map = getattr(runtime, "command_map", None)
    entities = [
        BlueSharkNumber(runtime, entry, spec) for spec in build_number_specs(command_map)
    ]
    if entities:
        async_add_entities(entities)


class BlueSharkNumber(RuntimeAvailableMixin, RuntimeListenerMixin, NumberEntity):
    """A single guided-onboarding command-map ``number`` entry.

    Optimistic: the displayed value only moves once the write comes back with
    a verdict that is not a rejection, so a device that silently ignores an
    out-of-range or unsupported opcode doesn't show a value it never accepted.
    """

    _attr_has_entity_name = True
    _attr_should_poll = False
    _attr_mode = NumberMode.SLIDER
    _attr_native_step = 1

    def __init__(self, runtime: object, entry: ConfigEntry, spec: CommandSpec) -> None:
        self._runtime = runtime
        self._spec = spec
        self._attr_name = spec.name
        self._attr_unique_id = spec.unique_id(entry.entry_id, "number")
        self._attr_native_min_value = spec.entry.get("min", 0)
        self._attr_native_max_value = spec.entry.get("max", 255)
        self._attr_native_value = None
        device_info = getattr(runtime, "device_info", None)
        if device_info is not None:
            self._attr_device_info = device_info
        note = spec.entry.get("note")
        if note:
            self._attr_extra_state_attributes = {"note": note}

    async def async_set_native_value(self, value: float) -> None:
        int_value = int(value)
        try:
            result = await self._runtime.async_send_number(self._spec.key, int_value)
        except Exception as err:
            raise HomeAssistantError(str(err)) from err
        if not is_rejected_verdict(verdict_of(result, self._runtime)):
            self._attr_native_value = int_value
            self.async_write_ha_state()
