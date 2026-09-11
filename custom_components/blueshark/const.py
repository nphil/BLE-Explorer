"""Constants for the BlueShark Home Assistant integration."""

DOMAIN = "blueshark"

# --- Legacy "profile import" config entry (unchanged; keeps the Android app's exported
# profiles installing through the existing profile.py contract). ---
CONF_ADDRESS = "address"
CONF_PROFILE = "profile"
CONF_ALLOW_WRITES = "allow_writes"
DEFAULT_ALLOW_WRITES = False
WRITE_TIMEOUT = 10

# --- Guided-onboarding config entry (one physical device per entry, unique_id = address). ---
CONF_CODEC_ID = "codec_id"
CONF_CODEC_PARAMS = "codec_params"
CONF_CHARACTERISTIC = "characteristic"
CONF_NOTIFY_CHARACTERISTIC = "notify_characteristic"
CONF_FAMILY_ID = "family_id"

# --- Options (mutable after setup, both from the classic options flow and the panel). ---
CONF_IDLE_DISCONNECT_S = "idle_disconnect_s"
CONF_COMMAND_MAP = "command_map"
CONF_OPCODE_LOG = "opcode_log"

DEFAULT_IDLE_DISCONNECT_S = 30
MIN_IDLE_DISCONNECT_S = 0
MAX_IDLE_DISCONNECT_S = 600
MAX_OPCODE_LOG_ENTRIES = 500
DEFAULT_CODEC_ID = "raw"

# --- Entity platforms. Every platform is forwarded for every entry; each platform module
# decides for itself (profile vs. guided, and which command_map entries apply) whether it
# has anything to add. ---
PLATFORMS = ["button", "number", "switch", "sensor", "binary_sensor"]

# --- Panel (registered once, at component-setup time, regardless of how many entries exist). ---
PANEL_URL_PATH = "blueshark"
PANEL_TITLE = "BlueShark"
PANEL_ICON = "mdi:bluetooth"
PANEL_JS_MODULE = "blueshark-panel.js"
PANEL_STATIC_URL = "/api/blueshark/panel"

# --- WebSocket API. ---
WS_PREFIX = "blueshark"

WS_ERROR_NOT_FOUND = "not_found"
WS_ERROR_NO_ROUTE = "no_route"
WS_ERROR_BUSY = "busy"
WS_ERROR_REFUSED = "refused"
WS_ERROR_TIMEOUT = "timeout"
WS_ERROR_UNSUPPORTED = "unsupported"

# --- Config flow sources / discovery keys. ---
SOURCE_PANEL = "panel"

# A single "hub" entry whose only purpose is to make Home Assistant set the component up when no
# device has been onboarded yet: with zero entries HA never calls async_setup, so the panel that
# exists to create the first device would never be registered.
HUB_UNIQUE_ID = "blueshark-wizard"
HUB_TITLE = "BlueShark wizard"

# --- Sweep prober defaults (protocol-agnostic; a codec may still decline to supply a canary
# or a destructive set, in which case the sweep runs without that safety net). ---
DEFAULT_SWEEP_START = 1
DEFAULT_SWEEP_END = 0x14
DEFAULT_SWEEP_STEP_DELAY_MS = 400
DEFAULT_AWAIT_RESPONSE_MS = 1500
CANARY_INTERVAL = 5

# --- hass.data[DOMAIN] internal bookkeeping keys (not part of any external contract). ---
DATA_TRANSPORTS = "transports"
DATA_SWEEP_RUNS = "sweep_runs"

# --- WS presentation defaults. ---
OPCODE_LOG_TAIL_DISPLAY = 50

# --- Services (custom_components/blueshark/services.yaml). ---
SERVICE_SEND_RAW = "send_raw"
SERVICE_PROBE_OPCODE = "probe_opcode"
SERVICE_PROBE_SWEEP = "probe_sweep"
SERVICE_LISTEN = "listen"
