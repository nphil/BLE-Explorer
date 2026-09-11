// Pure step state machine for the BlueShark onboarding wizard: Find -> Identify -> Learn -> Finish.
// No DOM access, no HA imports: safe to unit test under plain Node. The only side effect in this
// file is localStorage (or an injected storage-like object) persistence inside createWizard(); the
// reducer itself (`reduce`) is a plain (state, action) -> state function.

import { parseHex, parseOpcode, slugify, pickBestSource } from './format.js';

export const STEP_ORDER = Object.freeze(['find', 'identify', 'learn', 'finish']);

// Mirrors const.py so a fresh wizard offers the same defaults as the engine would pick.
export const DEFAULT_CODEC_ID = 'raw'; // const.py DEFAULT_CODEC_ID
export const DEFAULT_SWEEP_START = 1; // const.py DEFAULT_SWEEP_START
export const DEFAULT_SWEEP_END = 0x14; // const.py DEFAULT_SWEEP_END (20)
export const DEFAULT_SWEEP_STEP_DELAY_MS = 400; // const.py DEFAULT_SWEEP_STEP_DELAY_MS
export const DEFAULT_AWAIT_RESPONSE_MS = 1500; // const.py DEFAULT_AWAIT_RESPONSE_MS
export const CANARY_INTERVAL = 5; // const.py CANARY_INTERVAL (display copy only; the sweep plan is server-side)
export const DEFAULT_LISTEN_SECONDS = 10;

export const SCHEMA_VERSION = 1;
export const STORAGE_KEY = 'blueshark-wizard-state-v1';

// --- Initial state -----------------------------------------------------------

function initialFind() {
  return { query: '', selectedAddress: null };
}

function initialIdentify() {
  return {
    loadingIdentify: false,
    identifyError: null,
    matches: null,
    decoded: null,
    loadingEnumerate: false,
    enumerateError: null,
    services: null,
    suggested: null,
    selectedService: null,
    selectedCharacteristic: null,
    selectedNotifyCharacteristic: null,
    codecId: null,
    // Whether commands/get found an entry already configured for this address: null while
    // unchecked/checking, then true or false. When true, Finish updates the existing entry's
    // command map (commands/set) instead of creating a new one (create_entry).
    checkingExisting: false,
    isExistingDevice: null,
  };
}

function initialSweep() {
  return {
    runId: null,
    running: false,
    rows: [],
    summary: null,
    error: null,
    config: {
      start: DEFAULT_SWEEP_START,
      end: DEFAULT_SWEEP_END,
      argumentHex: '',
      includeDestructive: false,
      stepDelayMs: DEFAULT_SWEEP_STEP_DELAY_MS,
      awaitResponseMs: DEFAULT_AWAIT_RESPONSE_MS,
    },
  };
}

function initialLearn() {
  return {
    commandMap: {},
    commandOrder: [],
    sweep: initialSweep(),
    tryPending: false,
    tryError: null,
    tryResult: null,
    listen: { active: false, seconds: DEFAULT_LISTEN_SECONDS, frames: [], startedAtMs: null, error: null },
    rowNotes: {},
  };
}

function initialFinish() {
  return { name: '', creating: false, createError: null, entryId: null, created: false };
}

/** A brand-new wizard, as if the panel had just been opened for the first time. */
export function initialState() {
  return {
    version: SCHEMA_VERSION,
    step: STEP_ORDER[0],
    find: initialFind(),
    devices: {},
    identify: initialIdentify(),
    learn: initialLearn(),
    finish: initialFinish(),
  };
}

// --- Guards --------------------------------------------------------------------

/** A device has been picked in Find. */
export function canLeaveFind(state) {
  return Boolean(state.find.selectedAddress);
}

/** Enumerate has completed and a write channel + codec are chosen. */
export function canLeaveIdentify(state) {
  const id = state.identify;
  return Boolean(id.services && id.selectedCharacteristic && id.codecId);
}

/** Learn never blocks moving on: zero commands is a valid (if unusual) outcome. */
export function canLeaveLearn() {
  return true;
}

/** Everything Finish's submit action needs: a chosen channel+codec, and (for a brand-new
 * device only) a non-empty name. An already-configured device updates its command map instead
 * of creating an entry, so it needs no name. */
export function canCreateEntry(state) {
  if (!canLeaveIdentify(state)) return false;
  if (state.identify.isExistingDevice) return true;
  return Boolean(state.finish.name && state.finish.name.trim());
}

/** Every step the operator can currently jump to from the stepper, in order. */
export function reachableSteps(state) {
  const reachable = ['find'];
  if (canLeaveFind(state)) reachable.push('identify');
  if (canLeaveFind(state) && canLeaveIdentify(state)) reachable.push('learn');
  if (canLeaveFind(state) && canLeaveIdentify(state) && canLeaveLearn(state)) reachable.push('finish');
  return reachable;
}

/** The next step forward, or null when there is none reachable yet. */
export function nextStep(state) {
  const idx = STEP_ORDER.indexOf(state.step);
  const candidate = STEP_ORDER[idx + 1];
  return candidate && reachableSteps(state).includes(candidate) ? candidate : null;
}

/** The previous step, or null when already on the first one. */
export function previousStep(state) {
  const idx = STEP_ORDER.indexOf(state.step);
  return idx > 0 ? STEP_ORDER[idx - 1] : null;
}

/** Human summary of what currently holds the device's one BLE operation slot, or null when idle. */
export function describeBusy(state) {
  if (state.identify.loadingEnumerate) return 'Connecting and enumerating services\u2026';
  if (state.identify.loadingIdentify) return 'Identifying the device\u2026';
  if (state.learn.sweep.running) return 'Sweep in progress';
  if (state.learn.listen.active) return 'Listening for notifications';
  if (state.learn.tryPending) return 'Sending a command';
  if (state.finish.creating) return 'Creating entities';
  return null;
}

// --- Command map building -------------------------------------------------------
//
// These mirror command_map.py's validate_command_map schema exactly (button: opcode +
// argument_hex, or payload_hex; number: opcode + min + max; switch: nested on/off payload
// objects) so whatever the operator builds here passes the server's validator unchanged.

function payloadFieldsFromSource(source) {
  if (source && typeof source.payloadHex === 'string') {
    const { hex } = parseHex(source.payloadHex, { allowEmpty: false });
    return { payload_hex: hex };
  }
  const opcode = parseOpcode(source?.opcode);
  const { hex } = parseHex(source?.argumentHex ?? '', { allowEmpty: true });
  return { opcode, argument_hex: hex };
}

function baseCommandFields({ name, note, characteristic }) {
  const trimmedName = String(name ?? '').trim();
  if (!trimmedName) throw new Error("Give this control a name.");
  const entry = { name: trimmedName };
  const trimmedNote = String(note ?? '').trim();
  if (trimmedNote) entry.note = trimmedNote;
  if (characteristic) entry.characteristic = characteristic;
  return entry;
}

/** A control that fires one fixed payload (opcode + argument, or a raw payload_hex). */
export function buildButtonEntry({ name, note, characteristic, opcode, argumentHex, payloadHex }) {
  return {
    ...baseCommandFields({ name, note, characteristic }),
    kind: 'button',
    ...payloadFieldsFromSource(payloadHex !== undefined ? { payloadHex } : { opcode, argumentHex }),
  };
}

/** A control whose live value (min..max) becomes the second payload byte after opcode. */
export function buildNumberEntry({ name, note, characteristic, opcode, min = 0, max = 255 }) {
  const op = parseOpcode(opcode);
  const lo = parseOpcode(min);
  const hi = parseOpcode(max);
  if (lo >= hi) throw new Error('Minimum must be less than maximum.');
  return { ...baseCommandFields({ name, note, characteristic }), kind: 'number', opcode: op, min: lo, max: hi };
}

/** A control with independent on/off payloads. Each side accepts the same shape as a button. */
export function buildSwitchEntry({ name, note, characteristic, on, off }) {
  if (!on || !off) throw new Error('A switch needs both an on payload and an off payload.');
  return {
    ...baseCommandFields({ name, note, characteristic }),
    kind: 'switch',
    on: payloadFieldsFromSource(on),
    off: payloadFieldsFromSource(off),
  };
}

/** Dispatch to the right builder for `kind`. Throws a human-readable Error on invalid input. */
export function buildCommandMapEntry(kind, fields) {
  if (kind === 'button') return buildButtonEntry(fields);
  if (kind === 'number') return buildNumberEntry(fields);
  if (kind === 'switch') return buildSwitchEntry(fields);
  throw new Error(`Unknown control kind: ${kind}`);
}

/** Slugify `name`, then disambiguate against `existingKeys` with -2, -3, ... suffixes. */
export function uniqueCommandKey(name, existingKeys) {
  const base = slugify(name);
  const taken = new Set(existingKeys ?? []);
  if (!taken.has(base)) return base;
  let n = 2;
  while (taken.has(`${base}-${n}`)) n += 1;
  return `${base}-${n}`;
}

// --- Device list merging ---------------------------------------------------------

/** Merge one scan/subscribe event into the live device map, tracking per-source RSSI so the
 * merged row's rssi/source reflect whichever proxy currently hears the device best — correct
 * whether the engine forwards one event per proxy or already dedupes to a single best reading. */
function mergeDevice(devices, event) {
  const address = event?.address;
  if (!address) return devices;
  const prior = devices[address];
  const sources = { ...(prior?.sources ?? {}) };
  const rssiValue = Number(event.rssi);
  if (Number.isFinite(rssiValue)) sources[event.source ?? ''] = rssiValue;
  const best = pickBestSource(sources);
  return {
    ...devices,
    [address]: {
      ...event,
      address,
      sources,
      rssi: best ? best.rssi : event.rssi,
      source: best ? best.source : event.source,
    },
  };
}

// --- Reducer -----------------------------------------------------------------

function omitKey(obj, key) {
  const next = { ...obj };
  delete next[key];
  return next;
}

/** (state, action) -> state. Unknown action types are a no-op (forward-compatible). */
export function reduce(state, action) {
  switch (action.type) {
    case 'SET_QUERY':
      return { ...state, find: { ...state.find, query: action.query ?? '' } };

    case 'UPSERT_DEVICE':
      return { ...state, devices: mergeDevice(state.devices, action.device) };

    case 'RESET_DEVICES':
      return { ...state, devices: {} };

    case 'SELECT_DEVICE': {
      const address = action.address ?? null;
      if (address === state.find.selectedAddress) return state;
      return {
        ...state,
        step: address ? 'identify' : state.step,
        find: { ...state.find, selectedAddress: address },
        identify: initialIdentify(),
        learn: initialLearn(),
        finish: initialFinish(),
      };
    }

    case 'GO_TO_STEP': {
      if (!STEP_ORDER.includes(action.step)) return state;
      if (action.step === state.step) return state;
      if (!reachableSteps(state).includes(action.step)) return state;
      return { ...state, step: action.step };
    }

    case 'IDENTIFY_START':
      return { ...state, identify: { ...state.identify, loadingIdentify: true, identifyError: null } };

    case 'IDENTIFY_SUCCESS':
      return {
        ...state,
        identify: {
          ...state.identify,
          loadingIdentify: false,
          identifyError: null,
          matches: action.matches ?? [],
          decoded: action.decoded ?? null,
        },
      };

    case 'IDENTIFY_ERROR':
      return { ...state, identify: { ...state.identify, loadingIdentify: false, identifyError: action.error ?? null } };

    case 'ENUMERATE_START':
      return { ...state, identify: { ...state.identify, loadingEnumerate: true, enumerateError: null } };

    case 'ENUMERATE_SUCCESS': {
      const suggested = action.suggested ?? null;
      const id = state.identify;
      const codecId = id.codecId ?? suggested?.codec_id ?? id.matches?.[0]?.codec_id ?? DEFAULT_CODEC_ID;
      return {
        ...state,
        identify: {
          ...id,
          loadingEnumerate: false,
          enumerateError: null,
          services: action.services ?? [],
          suggested,
          selectedService: id.selectedService ?? suggested?.service ?? null,
          selectedCharacteristic: id.selectedCharacteristic ?? suggested?.characteristic ?? null,
          selectedNotifyCharacteristic: id.selectedNotifyCharacteristic ?? suggested?.characteristic ?? null,
          codecId,
        },
      };
    }

    case 'ENUMERATE_ERROR':
      return { ...state, identify: { ...state.identify, loadingEnumerate: false, enumerateError: action.error ?? null } };

    case 'EXISTING_ENTRY_CHECK_START':
      return { ...state, identify: { ...state.identify, checkingExisting: true } };

    case 'EXISTING_ENTRY_FOUND': {
      const commandMap = action.commandMap ?? {};
      return {
        ...state,
        identify: { ...state.identify, checkingExisting: false, isExistingDevice: true },
        learn: { ...state.learn, commandMap, commandOrder: Object.keys(commandMap) },
      };
    }

    case 'EXISTING_ENTRY_NOT_FOUND':
      return { ...state, identify: { ...state.identify, checkingExisting: false, isExistingDevice: false } };

    case 'SELECT_CHANNEL':
      return {
        ...state,
        identify: { ...state.identify, selectedService: action.service ?? null, selectedCharacteristic: action.characteristic ?? null },
      };

    case 'SELECT_NOTIFY_CHANNEL':
      return { ...state, identify: { ...state.identify, selectedNotifyCharacteristic: action.characteristic ?? null } };

    case 'SELECT_CODEC':
      return { ...state, identify: { ...state.identify, codecId: action.codecId ?? null } };

    case 'ADD_COMMAND': {
      if (!action.key || !action.entry) return state;
      const { commandMap, commandOrder } = state.learn;
      return {
        ...state,
        learn: {
          ...state.learn,
          commandMap: { ...commandMap, [action.key]: action.entry },
          commandOrder: commandOrder.includes(action.key) ? commandOrder : [...commandOrder, action.key],
        },
      };
    }

    case 'REMOVE_COMMAND':
      return {
        ...state,
        learn: {
          ...state.learn,
          commandMap: omitKey(state.learn.commandMap, action.key),
          commandOrder: state.learn.commandOrder.filter((key) => key !== action.key),
        },
      };

    case 'SWEEP_CONFIGURE':
      return { ...state, learn: { ...state.learn, sweep: { ...state.learn.sweep, config: { ...state.learn.sweep.config, ...action.config } } } };

    case 'SWEEP_START':
      return {
        ...state,
        learn: {
          ...state.learn,
          sweep: { ...initialSweep(), config: state.learn.sweep.config, runId: action.runId ?? null, running: true },
        },
      };

    case 'SWEEP_RUN_ID':
      return { ...state, learn: { ...state.learn, sweep: { ...state.learn.sweep, runId: action.runId ?? null } } };

    case 'SWEEP_PROGRESS':
      return { ...state, learn: { ...state.learn, sweep: { ...state.learn.sweep, rows: [...state.learn.sweep.rows, action.row] } } };

    case 'SWEEP_DONE':
      return { ...state, learn: { ...state.learn, sweep: { ...state.learn.sweep, running: false, summary: action.summary ?? null } } };

    case 'SWEEP_ERROR':
      return { ...state, learn: { ...state.learn, sweep: { ...state.learn.sweep, running: false, error: action.error ?? null } } };

    case 'SWEEP_STOPPED':
      return { ...state, learn: { ...state.learn, sweep: { ...state.learn.sweep, running: false } } };

    case 'TRY_START':
      return { ...state, learn: { ...state.learn, tryPending: true, tryError: null } };

    case 'TRY_RESULT':
      return { ...state, learn: { ...state.learn, tryPending: false, tryError: null, tryResult: action.result ?? null } };

    case 'TRY_ERROR':
      return { ...state, learn: { ...state.learn, tryPending: false, tryError: action.error ?? null } };

    case 'SET_ROW_NOTE':
      return { ...state, learn: { ...state.learn, rowNotes: { ...state.learn.rowNotes, [action.rowKey]: action.note ?? '' } } };

    case 'LISTEN_START':
      return {
        ...state,
        learn: {
          ...state.learn,
          listen: { active: true, seconds: action.seconds ?? state.learn.listen.seconds, frames: [], startedAtMs: action.startedAtMs ?? null, error: null },
        },
      };

    case 'LISTEN_ERROR':
      return { ...state, learn: { ...state.learn, listen: { ...state.learn.listen, active: false, error: action.error ?? null } } };

    case 'LISTEN_FRAME':
      return { ...state, learn: { ...state.learn, listen: { ...state.learn.listen, frames: [...state.learn.listen.frames, action.frame] } } };

    case 'LISTEN_STOP':
      return { ...state, learn: { ...state.learn, listen: { ...state.learn.listen, active: false } } };

    case 'SET_DEVICE_NAME':
      return { ...state, finish: { ...state.finish, name: action.name ?? '' } };

    case 'CREATE_ENTRY_START':
      return { ...state, finish: { ...state.finish, creating: true, createError: null } };

    case 'CREATE_ENTRY_SUCCESS':
      return { ...state, finish: { ...state.finish, creating: false, createError: null, entryId: action.entryId ?? null, created: true } };

    case 'CREATE_ENTRY_ERROR':
      return { ...state, finish: { ...state.finish, creating: false, createError: action.error ?? null } };

    case 'RESTART':
      return initialState();

    default:
      return state;
  }
}

// --- Persistence ---------------------------------------------------------------

function createMemoryStorage() {
  const store = new Map();
  return {
    getItem: (key) => (store.has(key) ? store.get(key) : null),
    setItem: (key, value) => {
      store.set(key, String(value));
    },
    removeItem: (key) => {
      store.delete(key);
    },
  };
}

function resolveStorage(storage) {
  if (storage) return storage;
  if (typeof localStorage !== 'undefined' && localStorage) return localStorage;
  return createMemoryStorage();
}

/** The subset of state worth surviving a reload: choices, not live/in-flight data. Device scans,
 * sweep runs, listen sessions and single-try results all belong to a WS subscription that a page
 * reload has already severed, so they are deliberately left out and start fresh. */
function persistableState(state) {
  return {
    version: SCHEMA_VERSION,
    step: state.step,
    find: { query: state.find.query, selectedAddress: state.find.selectedAddress },
    identify: {
      matches: state.identify.matches,
      decoded: state.identify.decoded,
      services: state.identify.services,
      suggested: state.identify.suggested,
      selectedService: state.identify.selectedService,
      selectedCharacteristic: state.identify.selectedCharacteristic,
      selectedNotifyCharacteristic: state.identify.selectedNotifyCharacteristic,
      codecId: state.identify.codecId,
    },
    learn: {
      commandMap: state.learn.commandMap,
      commandOrder: state.learn.commandOrder,
      rowNotes: state.learn.rowNotes,
      sweep: { config: state.learn.sweep.config },
    },
    finish: { name: state.finish.name },
  };
}

function hydrateState(persisted) {
  const base = initialState();
  if (!persisted || typeof persisted !== 'object') return base;
  return {
    ...base,
    step: STEP_ORDER.includes(persisted.step) ? persisted.step : base.step,
    find: { ...base.find, ...persisted.find },
    identify: { ...base.identify, ...persisted.identify },
    learn: {
      ...base.learn,
      ...persisted.learn,
      sweep: { ...base.learn.sweep, ...(persisted.learn?.sweep ?? {}), running: false, rows: [], runId: null, error: null, summary: null },
      listen: base.learn.listen,
      tryPending: false,
      tryError: null,
      tryResult: null,
    },
    finish: { ...base.finish, ...persisted.finish, creating: false, createError: null, entryId: null, created: false },
    devices: {},
  };
}

/** Read and validate persisted state, falling back to initialState() on any mismatch or error. */
export function restoreState(storage, key = STORAGE_KEY) {
  const store = resolveStorage(storage);
  let raw = null;
  try {
    raw = store.getItem(key);
  } catch {
    return initialState();
  }
  if (!raw) return initialState();
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return initialState();
  }
  if (!parsed || parsed.version !== SCHEMA_VERSION) return initialState();
  return hydrateState(parsed);
}

/** Best-effort persistence: storage quota errors or a disabled store are silently ignored. */
export function persistState(storage, state, key = STORAGE_KEY) {
  const store = resolveStorage(storage);
  try {
    store.setItem(key, JSON.stringify(persistableState(state)));
  } catch {
    // Private browsing or a full quota: persistence is a nicety, not a requirement.
  }
}

export function clearPersistedState(storage, key = STORAGE_KEY) {
  const store = resolveStorage(storage);
  try {
    store.removeItem(key);
  } catch {
    // best-effort
  }
}

// --- Store -----------------------------------------------------------------

/** A tiny observable store around `reduce`, restoring from `storage` (default: localStorage,
 * falling back to an in-memory stub where localStorage does not exist) and persisting every
 * dispatch that actually changes state. */
export function createWizard({ storage, storageKey = STORAGE_KEY } = {}) {
  const store = resolveStorage(storage);
  let state = restoreState(store, storageKey);
  const listeners = new Set();

  function dispatch(action) {
    const next = reduce(state, action);
    if (next === state) return state;
    state = next;
    if (action.type === 'RESTART') {
      clearPersistedState(store, storageKey);
    } else {
      persistState(store, state, storageKey);
    }
    for (const listener of listeners) listener(state, action);
    return state;
  }

  return {
    getState: () => state,
    dispatch,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}
