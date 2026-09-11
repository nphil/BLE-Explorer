"""Guided-onboarding ``switch`` entities: separate on/off payloads.

Only the guided (command-map) config-entry path has switches; the legacy
profile-import path never did and still doesn't.
"""

from __future__ import annotations

import logging

from homeassistant.components.switch import SwitchEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError

from .const import DOMAIN
from .entity_specs import (
    CommandSpec,
    RuntimeAvailableMixin,
    RuntimeListenerMixin,
    build_switch_specs,
    is_legacy_runtime,
    is_rejected_verdict,
    verdict_of,
)

_LOGGER = logging.getLogger(__name__)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities
) -> None:
    """Add one switch entity per guided command-map entry of kind ``switch``."""

    runtime = hass.data[DOMAIN][entry.entry_id]
    if is_legacy_runtime(runtime):
        return
    command_map = getattr(runtime, "command_map", None)
    entities = [
        BlueSharkSwitch(runtime, entry, spec) for spec in build_switch_specs(command_map)
    ]
    if entities:
        async_add_entities(entities)


class BlueSharkSwitch(RuntimeAvailableMixin, RuntimeListenerMixin, SwitchEntity):
    """A single guided-onboarding command-map ``switch`` entry.

    Optimistic: the reported on/off state only flips once the write comes
    back with a verdict that is not a rejection.
    """

    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, runtime: object, entry: ConfigEntry, spec: CommandSpec) -> None:
        self._runtime = runtime
        self._spec = spec
        self._attr_name = spec.name
        self._attr_unique_id = spec.unique_id(entry.entry_id, "switch")
        self._attr_is_on = None
        device_info = getattr(runtime, "device_info", None)
        if device_info is not None:
            self._attr_device_info = device_info
        note = spec.entry.get("note")
        if note:
            self._attr_extra_state_attributes = {"note": note}

    async def _async_send(self, on: bool) -> None:
        try:
            result = await self._runtime.async_send_switch(self._spec.key, on=on)
        except Exception as err:
            raise HomeAssistantError(str(err)) from err
        if not is_rejected_verdict(verdict_of(result, self._runtime)):
            self._attr_is_on = on
            self.async_write_ha_state()

    async def async_turn_on(self, **kwargs) -> None:
        await self._async_send(True)

    async def async_turn_off(self, **kwargs) -> None:
        await self._async_send(False)
