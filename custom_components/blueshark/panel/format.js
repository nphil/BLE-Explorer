// Pure formatting and validation helpers for the BlueShark onboarding wizard.
// No DOM access, no HA imports: safe to unit test under plain Node.

export const MAX_PAYLOAD_BYTES = 512;

/**
 * Parse operator-entered hex into canonical lowercase, unspaced wire form.
 * Accepts spaced ("01 0A FF"), colon/dash separated, or bare ("010AFF") input,
 * with an optional leading "0x". Throws a human-readable Error on anything
 * that is not complete hex bytes.
 */
export function parseHex(input, { allowEmpty = false, maxBytes = MAX_PAYLOAD_BYTES } = {}) {
  const cleaned = String(input ?? '')
    .trim()
    .replace(/0x/gi, '')
    .replace(/[\s:-]/g, '');
  if (!cleaned) {
    if (allowEmpty) return { hex: '', bytes: new Uint8Array(0), byteLength: 0 };
    throw new Error('Enter a hex payload, for example 01 0A FF.');
  }
  if (!/^[0-9a-f]+$/i.test(cleaned)) {
    throw new Error('Hex payload may only contain the digits 0-9 and letters A-F.');
  }
  if (cleaned.length % 2 !== 0) {
    throw new Error('Hex payload must contain complete bytes (an even number of hex digits).');
  }
  const byteLength = cleaned.length / 2;
  if (byteLength > maxBytes) {
    throw new Error(`Hex payload exceeds the ${maxBytes}-byte limit.`);
  }
  const hex = cleaned.toLowerCase();
  const bytes = new Uint8Array(byteLength);
  for (let i = 0; i < byteLength; i++) {
    bytes[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return { hex, bytes, byteLength };
}

/** Render any wire-form or operator-entered hex as spaced, uppercase groups. Never throws. */
export function displayHex(value, { placeholder = '\u2014' } = {}) {
  if (value === null || value === undefined || value === '') return placeholder;
  let hex;
  try {
    ({ hex } = parseHex(value, { allowEmpty: true, maxBytes: Infinity }));
  } catch {
    return placeholder;
  }
  if (!hex) return placeholder;
  return hex.toUpperCase().match(/../g).join(' ');
}

/** RSSI (dBm, typically negative) -> a label, a 0-100 bar percentage, and a coarse tone. */
export function formatRssi(rssi) {
  const value = Number(rssi);
  if (!Number.isFinite(value)) {
    return { value: null, label: '\u2014', percent: 0, tone: 'unknown' };
  }
  const clamped = Math.min(-30, Math.max(-100, value));
  const percent = Math.round(((clamped + 100) / 70) * 100);
  const tone = value >= -60 ? 'strong' : value >= -80 ? 'ok' : 'weak';
  return { value, label: `${value} dBm`, percent, tone };
}

function humanizeToken(token) {
  return token
    .split(/[_\s]+/)
    .filter(Boolean)
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(' ');
}

const VERDICT_TONES = {
  accepted: { tone: 'success', label: 'Accepted' },
  rejected: { tone: 'error', label: 'Rejected' },
  denied: { tone: 'error', label: 'Denied' },
  error: { tone: 'error', label: 'Error' },
  unknown: { tone: 'warning', label: 'Unknown' },
  no_response: { tone: 'neutral', label: 'No response' },
  pending: { tone: 'neutral', label: 'Pending' },
};

/** Verdict string -> chip {tone, label, raw, inferred}. Label always carries text, never color-only. */
export function formatVerdict(verdict, { inferred = false } = {}) {
  const key = String(verdict ?? 'pending').trim().toLowerCase().replace(/-/g, '_') || 'pending';
  const known = VERDICT_TONES[key];
  const base = known ?? { tone: 'neutral', label: humanizeToken(key) };
  const label = inferred ? `${base.label} (inferred)` : base.label;
  return { tone: base.tone, label, raw: verdict ?? null, inferred };
}

/** Passthrough for the low-level `status` field distinct from the human `verdict`. */
export function formatStatus(status) {
  if (status === null || status === undefined || status === '') return '\u2014';
  return String(status);
}

/** Milliseconds -> "420 ms" under a second, "1.3 s" at or beyond. */
export function formatElapsed(ms) {
  const value = Number(ms);
  if (!Number.isFinite(value) || value < 0) return '\u2014';
  if (value < 1000) return `${Math.round(value)} ms`;
  return `${(value / 1000).toFixed(1)} s`;
}

/** 0..1 confidence -> a rounded percentage string, or null when not a number. */
export function formatConfidence(confidence) {
  const value = Number(confidence);
  if (!Number.isFinite(value)) return null;
  const clamped = Math.min(1, Math.max(0, value));
  return `${Math.round(clamped * 100)}%`;
}

/** Normalize an evidence array to trimmed, non-empty strings. */
export function formatEvidence(evidence) {
  if (!Array.isArray(evidence)) return [];
  return evidence.map((item) => String(item ?? '').trim()).filter(Boolean);
}

/** Family match object -> a display-ready badge, or null when there is no match. */
export function formatFamilyBadge(family) {
  if (!family || typeof family !== 'object') return null;
  const name = String(family.name ?? '').trim();
  if (!name) return null;
  return {
    id: family.id ?? null,
    name,
    confidenceLabel: formatConfidence(family.confidence),
    evidence: formatEvidence(family.evidence),
  };
}

/** manufacturer_data ({decimalId: lowercaseHex}) -> display rows. */
export function formatManufacturerData(manufacturerData) {
  if (!manufacturerData || typeof manufacturerData !== 'object') return [];
  return Object.entries(manufacturerData).map(([id, hexValue]) => {
    const numericId = Number(id);
    const hasNumericId = Number.isFinite(numericId);
    const idHex = hasNumericId ? `0x${Math.trunc(numericId).toString(16).toUpperCase().padStart(4, '0')}` : null;
    return {
      id,
      idLabel: hasNumericId ? `${Math.trunc(numericId)} (${idHex})` : String(id),
      hex: displayHex(hexValue),
    };
  });
}

const BASE_UUID_RE = /^([0-9a-f]{8})-0000-1000-8000-00805f9b34fb$/;

/** Full 128-bit UUID -> "0xFFF0" for SIG base UUIDs, else a truncated "aabbccdd\u2026eeff". */
export function shortUuid(uuid) {
  const value = String(uuid ?? '').trim().toLowerCase();
  if (!value) return '\u2014';
  const baseMatch = value.match(BASE_UUID_RE);
  if (baseMatch) {
    const head = baseMatch[1].replace(/^0000/, '') || baseMatch[1];
    return `0x${head.toUpperCase()}`;
  }
  if (value.length <= 8) return value.toUpperCase();
  return `${value.slice(0, 8)}\u2026${value.slice(-4)}`;
}

const PROPERTY_ORDER = [
  'read',
  'write',
  'write-without-response',
  'notify',
  'indicate',
  'broadcast',
  'authenticated-signed-writes',
  'extended-properties',
];

/** Sort a characteristic's raw properties into a stable, familiar order. */
export function formatProperties(properties) {
  if (!Array.isArray(properties)) return [];
  const set = new Set(properties.map((p) => String(p ?? '').trim().toLowerCase()).filter(Boolean));
  const ordered = PROPERTY_ORDER.filter((p) => set.has(p));
  const rest = [...set].filter((p) => !PROPERTY_ORDER.includes(p));
  return [...ordered, ...rest];
}

const WRITE_PROPERTIES = new Set(['write', 'write-without-response', 'authenticated-signed-writes']);
const NOTIFY_PROPERTIES = new Set(['notify', 'indicate']);

/** True when a single characteristic's properties combine a write flavor and a notify flavor. */
export function hasWriteNotifyPair(properties) {
  if (!Array.isArray(properties)) return false;
  let hasWrite = false;
  let hasNotify = false;
  for (const raw of properties) {
    const p = String(raw ?? '').trim().toLowerCase();
    if (WRITE_PROPERTIES.has(p)) hasWrite = true;
    if (NOTIFY_PROPERTIES.has(p)) hasNotify = true;
  }
  return hasWrite && hasNotify;
}

/** Combine an opcode byte (0-255) with optional argument hex into one wire payload (lowercase, unspaced). */
export function buildPayloadFromOpcode(opcode, argumentHex = '') {
  const op = Number(opcode);
  if (!Number.isInteger(op) || op < 0 || op > 255) {
    throw new Error('Opcode must be a whole number from 0 to 255 (0x00-0xFF).');
  }
  const { hex } = parseHex(argumentHex, { allowEmpty: true, maxBytes: MAX_PAYLOAD_BYTES - 1 });
  return op.toString(16).padStart(2, '0') + hex;
}

/** A single byte -> "0x4A (74)", or an em dash when out of range. */
export function formatByte(value) {
  const num = Number(value);
  if (!Number.isInteger(num) || num < 0 || num > 255) return '\u2014';
  return `0x${num.toString(16).toUpperCase().padStart(2, '0')} (${num})`;
}

const ERROR_HINTS = {
  busy: 'Another operation is using the connection to this device. Wait for it to finish, or stop it first.',
  no_route: 'No proxy currently hears this device. It may be out of range, asleep, or held by another central such as a phone app.',
  not_found: 'Home Assistant does not know this device yet. Go back to Find and let it advertise again.',
  refused: 'The device rejected this request outright.',
  timeout: 'The device did not answer in time. It may be busy, out of range, or held by another central such as a phone app.',
  unsupported: 'The engine does not support this operation for this device or characteristic.',
};

/** Human hint for a known engine error code, or null when the code is not one of the six documented ones. */
export function apiErrorHint(code) {
  return ERROR_HINTS[String(code ?? '').trim().toLowerCase()] ?? null;
}

/** The engine's own message, verbatim, plus a parenthetical hint when the code is a known one. */
export function formatApiErrorMessage(error) {
  if (!error) return 'Unknown error.';
  const message =
    typeof error.message === 'string' && error.message.trim()
      ? error.message.trim()
      : 'The engine reported an error without a message.';
  const hint = apiErrorHint(error.code);
  return hint ? `${message} (${hint})` : message;
}

/** Lowercase, underscore-joined slug for command_map keys. Never empty. */
export function slugify(text) {
  const base = String(text ?? '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  return base || 'command';
}

/** Best-effort link to the integration's config entry page (device-registry id is not returned by the API). */
export function entryDashboardUrl(domain = 'blueshark') {
  return `/config/integrations/integration/${domain}`;
}

/** Device's advertised name, or its address when nameless. */
export function formatDeviceLabel(device) {
  const name = String(device?.name ?? '').trim();
  return name || formatAddress(device?.address);
}

/** A MAC address, or an em dash when missing. */
export function formatAddress(address) {
  const value = String(address ?? '').trim();
  return value || '\u2014';
}

/** Case-insensitive substring match against a device's name or address. Empty query matches everything. */
export function matchesQuery(device, query) {
  const q = String(query ?? '').trim().toLowerCase();
  if (!q) return true;
  const name = String(device?.name ?? '').toLowerCase();
  const address = String(device?.address ?? '').toLowerCase();
  return name.includes(q) || address.includes(q);
}

/** Non-mutating sort of scan rows by RSSI, strongest (closest to 0) first. Missing RSSI sorts last. */
export function sortByRssiDesc(devices) {
  if (!Array.isArray(devices)) return [];
  return [...devices].sort((a, b) => {
    const ra = Number(a?.rssi);
    const rb = Number(b?.rssi);
    const va = Number.isFinite(ra) ? ra : -Infinity;
    const vb = Number.isFinite(rb) ? rb : -Infinity;
    return vb - va;
  });
}
