"""Guided-onboarding diagnostics: the last write's verdict, and the link state.

Both sensors describe the device as a whole, not one command-map entry, so
exactly one of each is created per guided config entry (never for the legacy
profile-import path, which has no transport/last_response to report on).
"""

from __future__ import annotations

import logging

from homeassistant.components.sensor import SensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant

from .const import DOMAIN
from .entity_specs import (
    RuntimeAvailableMixin,
    RuntimeListenerMixin,
    extract_response_fields,
    is_legacy_runtime,
    link_state,
)

_LOGGER = logging.getLogger(__name__)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities
) -> None:
    """Add the last-response and link-state diagnostic sensors for a guided entry."""

    runtime = hass.data[DOMAIN][entry.entry_id]
    if is_legacy_runtime(runtime):
        return
    async_add_entities(
        [LastResponseSensor(runtime, entry), LinkStateSensor(runtime, entry)]
    )


class _BlueSharkDeviceSensor(RuntimeAvailableMixin, RuntimeListenerMixin, SensorEntity):
    """Shared plumbing for the two device-wide diagnostic sensors."""

    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, runtime: object, entry: ConfigEntry, key: str, name: str) -> None:
        self._runtime = runtime
        self._attr_unique_id = f"{entry.entry_id}_sensor_{key}"
        self._attr_name = name
        device_info = getattr(runtime, "device_info", None)
        if device_info is not None:
            self._attr_device_info = device_info


class LastResponseSensor(_BlueSharkDeviceSensor):
    """State is the verdict of the most recent write; attributes carry the evidence."""

    def __init__(self, runtime: object, entry: ConfigEntry) -> None:
        super().__init__(runtime, entry, "last_response", "Last response")

    @property
    def native_value(self) -> str | None:
        last_response = getattr(self._runtime, "last_response", None)
        if last_response is None:
            return None
        return extract_response_fields(last_response).get("verdict")

    @property
    def extra_state_attributes(self) -> dict[str, object]:
        last_response = getattr(self._runtime, "last_response", None)
        if last_response is None:
            return {}
        fields = extract_response_fields(last_response)
        return {
            "sent_hex": fields["sent_hex"],
            "response_hex": fields["response_hex"],
            "status": fields["status"],
            "elapsed_ms": fields["elapsed_ms"],
            "opcode": fields["opcode"],
        }


class LinkStateSensor(_BlueSharkDeviceSensor):
    """State is one of idle/connecting/connected/busy; attribute names the current op."""

    _attr_entity_category = EntityCategory.DIAGNOSTIC
    _attr_icon = "mdi:bluetooth-transfer"

    def __init__(self, runtime: object, entry: ConfigEntry) -> None:
        super().__init__(runtime, entry, "link_state", "Link state")

    @property
    def native_value(self) -> str:
        state, _operation = link_state(self._runtime)
        return state

    @property
    def extra_state_attributes(self) -> dict[str, object]:
        _state, operation = link_state(self._runtime)
        return {"current_operation": operation}
