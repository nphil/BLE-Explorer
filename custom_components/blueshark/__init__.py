"""BlueShark integration entry points."""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from .const import CONF_ADDRESS, CONF_ALLOW_WRITES, CONF_PROFILE, DOMAIN
from .profile import ProfileValidationError, parse_profile

if TYPE_CHECKING:
    from homeassistant.config_entries import ConfigEntry
    from homeassistant.core import HomeAssistant

_LOGGER = logging.getLogger(__name__)
PLATFORMS = ["button"]


async def async_setup(hass: HomeAssistant, config: dict) -> bool:
    """Set up from config entries only; no Bluetooth work is done here."""

    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Validate the stored evidence before creating disabled button entities."""

    try:
        profile = parse_profile(entry.data[CONF_PROFILE])
    except (KeyError, ProfileValidationError) as err:
        _LOGGER.error("Refusing invalid BLE command profile: %s", err)
        return False
    hass.data.setdefault(DOMAIN, {})[entry.entry_id] = {
        "address": entry.data[CONF_ADDRESS],
        "allow_writes": bool(entry.data.get(CONF_ALLOW_WRITES, False)),
        "profile": profile,
        "write_lock": None,
    }
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""

    unloaded = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unloaded:
        hass.data.get(DOMAIN, {}).pop(entry.entry_id, None)
    return unloaded

