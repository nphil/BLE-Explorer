"""Config flow for BlueShark.

Three ways to end up with a guided config entry, all converging on
`_create_guided_entry` so they always produce the identical entry shape:

- `async_step_bluetooth`: Home Assistant found a device matching one of the
  manifest's bluetooth matchers and offers to add it.
- `async_step_user` -> `async_step_guided`: the classic "+ Add Integration"
  entry point, for an operator who wants a device list without the panel.
- `async_step_panel`: the guided onboarding panel already ran its wizard
  (scan/identify/enumerate/learn) over the WebSocket API and calls
  `hass.config_entries.flow.async_init(DOMAIN, context={"source": "panel"}, ...)`
  with everything already decided and validated.

The original manually-entered-profile flow (`async_step_manual_profile`,
still reachable from the `user` step's menu) is unchanged.
"""

from __future__ import annotations

import json

import voluptuous as vol
from homeassistant import config_entries
from homeassistant.components import bluetooth
from homeassistant.const import CONF_NAME
from homeassistant.core import callback
from homeassistant.data_entry_flow import FlowResult

from .codecs import list_codecs
from .command_map import CommandMapError, validate_command_map
from .const import (
    CONF_ADDRESS,
    CONF_ALLOW_WRITES,
    CONF_CHARACTERISTIC,
    CONF_CODEC_ID,
    CONF_COMMAND_MAP,
    CONF_FAMILY_ID,
    CONF_IDLE_DISCONNECT_S,
    CONF_OPCODE_LOG,
    CONF_PROFILE,
    DEFAULT_ALLOW_WRITES,
    DEFAULT_CODEC_ID,
    DEFAULT_IDLE_DISCONNECT_S,
    DOMAIN,
    MAX_IDLE_DISCONNECT_S,
    MIN_IDLE_DISCONNECT_S,
)
from .families import FamilyMatch, FingerprintInput, canonical_uuid, identify
from .profile import ProfileValidationError, parse_profile


def _fingerprint(info: bluetooth.BluetoothServiceInfoBleak) -> FingerprintInput:
    return FingerprintInput(
        name=info.name,
        service_uuids=list(info.service_uuids),
        manufacturer_data=dict(info.manufacturer_data),
        service_data=dict(info.service_data),
    )


def _guided_choice_label(info: bluetooth.BluetoothServiceInfoBleak, matches: list[FamilyMatch]) -> str:
    family = f" - {matches[0].name}" if matches else ""
    return f"{info.name or 'Unnamed device'} ({info.address}){family}"


def _codec_choices() -> dict[str, str]:
    return {codec["id"]: codec["label"] for codec in list_codecs()}


class BlueSharkConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Guided onboarding (primary), bluetooth discovery, and the legacy profile flow."""

    VERSION = 1

    def __init__(self) -> None:
        self._discovery_info: bluetooth.BluetoothServiceInfoBleak | None = None
        self._family_matches: list[FamilyMatch] = []

    # ------------------------------------------------------------------ shared entry creation

    def _create_guided_entry(
        self,
        *,
        address: str,
        name: str,
        codec_id: str,
        characteristic: str,
        family_id: str | None = None,
        command_map: dict[str, object] | None = None,
    ) -> FlowResult:
        data: dict[str, object] = {
            CONF_ADDRESS: address,
            CONF_CODEC_ID: codec_id,
            CONF_CHARACTERISTIC: characteristic,
        }
        if family_id:
            data[CONF_FAMILY_ID] = family_id
        return self.async_create_entry(
            title=name,
            data=data,
            options={
                CONF_COMMAND_MAP: command_map or {},
                CONF_IDLE_DISCONNECT_S: DEFAULT_IDLE_DISCONNECT_S,
                CONF_OPCODE_LOG: [],
            },
        )

    def _address_configured(self, address: str) -> bool:
        normalized = address.lower()
        return any((entry.unique_id or "").lower() == normalized for entry in self._async_current_entries())

    # ------------------------------------------------------------------ entry point menu

    async def async_step_user(self, user_input: dict[str, object] | None = None) -> FlowResult:
        """+ Add Integration: choose the guided device picker or the manual profile form."""

        return self.async_show_menu(step_id="user", menu_options=["guided", "manual_profile"])

    # ------------------------------------------------------------------ guided: device picker

    async def async_step_guided(self, user_input: dict[str, object] | None = None) -> FlowResult:
        """List devices Home Assistant currently hears, with a family badge, to pick one."""

        if user_input is not None:
            info = bluetooth.async_last_service_info(self.hass, user_input[CONF_ADDRESS], connectable=True)
            if info is None:
                return self.async_abort(reason="no_devices_found")
            self._discovery_info = info
            self._family_matches = identify(_fingerprint(info))
            return await self.async_step_codec()

        choices = {
            info.address: _guided_choice_label(info, identify(_fingerprint(info)))
            for info in bluetooth.async_discovered_service_info(self.hass, connectable=True)
            if not self._address_configured(info.address)
        }
        if not choices:
            return self.async_abort(reason="no_devices_found")
        return self.async_show_form(
            step_id="guided", data_schema=vol.Schema({vol.Required(CONF_ADDRESS): vol.In(choices)})
        )

    # ------------------------------------------------------------------ bluetooth discovery

    async def async_step_bluetooth(self, discovery_info: bluetooth.BluetoothServiceInfoBleak) -> FlowResult:
        """Home Assistant found a device matching one of the manifest's bluetooth matchers."""

        await self.async_set_unique_id(discovery_info.address.lower())
        self._abort_if_unique_id_configured()
        self._discovery_info = discovery_info
        self._family_matches = identify(_fingerprint(discovery_info))
        self.context["title_placeholders"] = {"name": discovery_info.name or discovery_info.address}
        return await self.async_step_bluetooth_confirm()

    async def async_step_bluetooth_confirm(self, user_input: dict[str, object] | None = None) -> FlowResult:
        assert self._discovery_info is not None
        if user_input is not None:
            return await self.async_step_codec()
        family = self._family_matches[0] if self._family_matches else None
        return self.async_show_form(
            step_id="bluetooth_confirm",
            description_placeholders={
                "name": self._discovery_info.name or self._discovery_info.address,
                "address": self._discovery_info.address,
                "family": family.name if family else "Unrecognised device",
                "evidence": "; ".join(family.evidence) if family else "No matching family evidence yet.",
            },
        )

    # ------------------------------------------------------------------ codec/characteristic confirmation

    async def async_step_codec(self, user_input: dict[str, object] | None = None) -> FlowResult:
        """Confirm (or override) the codec and command characteristic, then create the entry.

        Defaults come from the strongest family match's `codec_id`/hints when one exists.
        The command map starts empty either way - build it in the panel's prober afterward,
        or via the options flow.
        """

        assert self._discovery_info is not None
        info = self._discovery_info
        family = self._family_matches[0] if self._family_matches else None
        errors: dict[str, str] = {}
        default_codec = (family.codec_id if family and family.codec_id else None) or DEFAULT_CODEC_ID
        default_characteristic = family.command_characteristic_hints[0] if family and family.command_characteristic_hints else ""

        if user_input is not None:
            characteristic = canonical_uuid(str(user_input[CONF_CHARACTERISTIC]))
            if characteristic is None:
                errors[CONF_CHARACTERISTIC] = "invalid_characteristic"
            else:
                await self.async_set_unique_id(info.address.lower(), raise_on_progress=False)
                self._abort_if_unique_id_configured()
                return self._create_guided_entry(
                    address=info.address,
                    name=info.name or info.address,
                    codec_id=str(user_input[CONF_CODEC_ID]),
                    characteristic=characteristic,
                    family_id=family.family_id if family else None,
                )

        return self.async_show_form(
            step_id="codec",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_CODEC_ID, default=default_codec): vol.In(_codec_choices()),
                    vol.Required(CONF_CHARACTERISTIC, default=default_characteristic): str,
                }
            ),
            errors=errors,
            description_placeholders={"name": info.name or info.address, "address": info.address},
        )

    # ------------------------------------------------------------------ programmatic creation (panel/WS API)

    async def async_step_panel(self, user_input: dict[str, object]) -> FlowResult:
        """Create a guided entry from the WS API's `create_entry` command.

        `user_input` is already fully decided and validated by the caller
        (`websocket_api.py`: address non-empty, `command_map` normalized by
        `validate_command_map`) - this step only enforces the one invariant a
        flow step is uniquely positioned to check: the address isn't already
        configured by a concurrent flow or entry.
        """

        address = str(user_input[CONF_ADDRESS]).strip()
        if not address:
            return self.async_abort(reason="invalid_address")
        await self.async_set_unique_id(address.lower())
        self._abort_if_unique_id_configured()
        return self._create_guided_entry(
            address=address,
            name=str(user_input.get(CONF_NAME) or address),
            codec_id=str(user_input[CONF_CODEC_ID]),
            characteristic=str(user_input[CONF_CHARACTERISTIC]),
            command_map=user_input.get(CONF_COMMAND_MAP) or {},
        )

    # ------------------------------------------------------------------ legacy: manually entered profile

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

    async def async_step_manual_profile(self, user_input: dict[str, object] | None = None) -> FlowResult:
        """Enter a manually captured, tested profile JSON (advanced/legacy path)."""

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
            step_id="manual_profile",
            data_schema=self._schema(),
            errors=errors,
            description_placeholders={"validation_error": validation_error},
        )

    async def async_step_reconfigure(self, user_input: dict[str, object] | None = None) -> FlowResult:
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

    # ------------------------------------------------------------------ options flow

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: config_entries.ConfigEntry) -> config_entries.OptionsFlow:
        """Always returns a flow instance - HA derives `supports_options` from this method
        merely being overridden, not from what it returns, and passes a `None` return straight
        through with no null-check. A legacy profile entry's flow immediately self-aborts.
        """

        return BlueSharkOptionsFlow()


class BlueSharkOptionsFlow(config_entries.OptionsFlow):
    """Idle disconnect, codec, characteristic, and the command map JSON, plus a log tail.

    Codec/characteristic are conceptually entry identity (`entry.data`), but this is the
    surface an operator uses to change them post-setup, so this flow updates `entry.data`
    directly for those two fields and returns the rest as the flow's own `options` result.
    """

    async def async_step_init(self, user_input: dict[str, object] | None = None) -> FlowResult:
        entry = self.config_entry
        if CONF_CHARACTERISTIC not in entry.data:
            return self.async_abort(reason="no_options")
        errors: dict[str, str] = {}
        command_map_error = ""

        if user_input is not None:
            characteristic = canonical_uuid(str(user_input[CONF_CHARACTERISTIC]))
            if characteristic is None:
                errors[CONF_CHARACTERISTIC] = "invalid_characteristic"
            else:
                try:
                    raw_map = json.loads(user_input[CONF_COMMAND_MAP] or "{}")
                    normalized_map = validate_command_map(raw_map)
                except (json.JSONDecodeError, CommandMapError) as err:
                    errors["base"] = "invalid_command_map"
                    command_map_error = str(err)
                else:
                    self.hass.config_entries.async_update_entry(
                        entry,
                        data={
                            **entry.data,
                            CONF_CODEC_ID: str(user_input[CONF_CODEC_ID]),
                            CONF_CHARACTERISTIC: characteristic,
                        },
                    )
                    return self.async_create_entry(
                        title="",
                        data={
                            CONF_IDLE_DISCONNECT_S: int(user_input[CONF_IDLE_DISCONNECT_S]),
                            CONF_COMMAND_MAP: normalized_map,
                            CONF_OPCODE_LOG: entry.options.get(CONF_OPCODE_LOG, []),
                        },
                    )

        log_tail = entry.options.get(CONF_OPCODE_LOG, [])[-10:]
        return self.async_show_form(
            step_id="init",
            data_schema=vol.Schema(
                {
                    vol.Required(
                        CONF_IDLE_DISCONNECT_S,
                        default=entry.options.get(CONF_IDLE_DISCONNECT_S, DEFAULT_IDLE_DISCONNECT_S),
                    ): vol.All(vol.Coerce(int), vol.Range(min=MIN_IDLE_DISCONNECT_S, max=MAX_IDLE_DISCONNECT_S)),
                    vol.Required(CONF_CODEC_ID, default=entry.data.get(CONF_CODEC_ID, DEFAULT_CODEC_ID)): vol.In(
                        _codec_choices()
                    ),
                    vol.Required(CONF_CHARACTERISTIC, default=entry.data.get(CONF_CHARACTERISTIC, "")): str,
                    vol.Required(
                        CONF_COMMAND_MAP,
                        default=json.dumps(entry.options.get(CONF_COMMAND_MAP, {}), indent=2, sort_keys=True),
                    ): str,
                }
            ),
            errors=errors,
            description_placeholders={
                "command_map_error": command_map_error,
                "opcode_log_tail": json.dumps(log_tail, indent=2) if log_tail else "(empty)",
            },
        )


def profile_for_form(profile: dict[str, object]) -> str:
    """Render a normalized profile for callers that need a form default."""

    return json.dumps(profile, indent=2, sort_keys=True)
