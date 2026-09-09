"""Config flow for manually entered, evidence-backed BLE command profiles."""

from __future__ import annotations

import json

import voluptuous as vol
from homeassistant import config_entries
from homeassistant.const import CONF_NAME
from homeassistant.core import HomeAssistant

from .const import CONF_ADDRESS, CONF_ALLOW_WRITES, CONF_PROFILE, DEFAULT_ALLOW_WRITES, DOMAIN
from .profile import ProfileValidationError, parse_profile


class BlueSharkConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Collect a BLE address and strict profile JSON from the user."""

    VERSION = 1

    @staticmethod
    def _schema() -> vol.Schema:
        return vol.Schema(
            {
                vol.Required(CONF_ADDRESS): str,
                vol.Required(CONF_PROFILE): str,
                vol.Optional(CONF_ALLOW_WRITES, default=DEFAULT_ALLOW_WRITES): bool,
            }
        )

    @staticmethod
    def _entry_data(profile: dict[str, object], address: str, allow_writes: bool):
        return {
            CONF_NAME: str(profile["device"]["name"]),
            CONF_ADDRESS: address,
            CONF_PROFILE: json.dumps(profile, separators=(",", ":")),
            CONF_ALLOW_WRITES: allow_writes,
        }

    async def async_step_user(self, user_input: dict[str, object] | None = None):
        errors: dict[str, str] = {}
        validation_error = ""
        if user_input is not None:
            try:
                profile = parse_profile(str(user_input[CONF_PROFILE]))
                address = str(user_input[CONF_ADDRESS]).strip()
                if not address:
                    raise ProfileValidationError("address is required")
                if profile["device"]["address"].lower() != address.lower():
                    raise ProfileValidationError("address must match device.address in profile")
            except ProfileValidationError as err:
                errors["base"] = "invalid_profile"
                validation_error = str(err)
            else:
                await self.async_set_unique_id(address.lower())
                self._abort_if_unique_id_configured()
                return self.async_create_entry(
                    title=str(profile["device"]["name"]),
                    data=self._entry_data(
                        profile,
                        address,
                        bool(user_input.get(CONF_ALLOW_WRITES, False)),
                    ),
                )

        return self.async_show_form(
            step_id="user",
            data_schema=self._schema(),
            errors=errors,
            description_placeholders={"validation_error": validation_error},
        )

    async def async_step_reconfigure(self, user_input: dict[str, object] | None = None):
        """Allow changing the profile and write opt-in without deleting the entry."""

        entry = self._get_reconfigure_entry()
        errors: dict[str, str] = {}
        validation_error = ""
        if user_input is not None:
            try:
                profile = parse_profile(str(user_input[CONF_PROFILE]))
                address = str(user_input[CONF_ADDRESS]).strip()
                if not address:
                    raise ProfileValidationError("address is required")
                if address.lower() != str(entry.unique_id).lower():
                    raise ProfileValidationError("Use a new integration entry for a different device address")
                if profile["device"]["address"].lower() != address.lower():
                    raise ProfileValidationError("address must match device.address in profile")
            except ProfileValidationError as err:
                errors["base"] = "invalid_profile"
                validation_error = str(err)
            else:
                return self.async_update_reload_and_abort(
                    entry,
                    data=self._entry_data(
                        profile,
                        address,
                        bool(user_input.get(CONF_ALLOW_WRITES, False)),
                    ),
                    title=str(profile["device"]["name"]),
                )
        return self.async_show_form(
            step_id="reconfigure",
            data_schema=self._schema(),
            errors=errors,
            description_placeholders={"validation_error": validation_error},
        )


async def async_get_options_flow(config_entry):
    """There are intentionally no mutable runtime options in v0.1."""

    return None


def profile_for_form(profile: dict[str, object]) -> str:
    """Render a normalized profile for callers that need a form default."""

    return json.dumps(profile, indent=2, sort_keys=True)
