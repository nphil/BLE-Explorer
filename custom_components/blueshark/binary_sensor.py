"""Guided-onboarding presence: is the link up, and is the device heard at all.

Both describe the device as a whole, not one command-map entry, so exactly
one of each is created per guided config entry (never for the legacy
profile-import path, which tracks neither).
"""

from __future__ import annotations

import logging

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant

from .const import DOMAIN
from .entity_specs import RuntimeListenerMixin, advertising_state, is_legacy_runtime

_LOGGER = logging.getLogger(__name__)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities
) -> None:
    """Add the connected and advertising presence sensors for a guided entry."""

    runtime = hass.data[DOMAIN][entry.entry_id]
    if is_legacy_runtime(runtime):
        return
    async_add_entities(
        [ConnectedBinarySensor(runtime, entry), AdvertisingBinarySensor(runtime, entry)]
    )


class _BlueSharkPresenceSensor(RuntimeListenerMixin, BinarySensorEntity):
    """Shared plumbing for the two device-wide presence sensors."""

    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, runtime: object, entry: ConfigEntry, key: str, name: str) -> None:
        self._runtime = runtime
        self._attr_unique_id = f"{entry.entry_id}_binary_sensor_{key}"
        self._attr_name = name
        device_info = getattr(runtime, "device_info", None)
        if device_info is not None:
            self._attr_device_info = device_info


class ConnectedBinarySensor(_BlueSharkPresenceSensor):
    """On while the transport holds a live BLE connection to the device."""

    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY

    def __init__(self, runtime: object, entry: ConfigEntry) -> None:
        super().__init__(runtime, entry, "connected", "Connected")

    @property
    def is_on(self) -> bool:
        return bool(getattr(self._runtime, "connected", False))


class AdvertisingBinarySensor(_BlueSharkPresenceSensor):
    """On while HA has recently heard this address advertise, connected or not."""

    _attr_device_class = BinarySensorDeviceClass.PRESENCE

    def __init__(self, runtime: object, entry: ConfigEntry) -> None:
        super().__init__(runtime, entry, "advertising", "Advertising")

    @property
    def is_on(self) -> bool | None:
        return advertising_state(self._runtime)
