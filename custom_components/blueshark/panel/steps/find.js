// Step 1: Find. A live, searchable list of advertising devices (name, address, RSSI + which
// proxy hears it best, family badge). Picking one selects it and the wizard moves to Identify.

import { h, clear, uid, liveRegion, withPreservedFocus } from '../components.js';
import { adoptSharedStyles } from '../styles.js';
import { formatDeviceLabel, formatAddress, formatRssi, formatFamilyBadge, matchesQuery, sortByRssiDesc } from '../format.js';

class BsStepFind extends HTMLElement {
  constructor() {
    super();
    this._api = null;
    this._wizard = null;
    this._unsubWizard = null;
    this._unsubScan = null;
    this._scanStarted = false;
    this._scanError = null;
    this._rafId = null;
    this._searchId = uid('bs-find-search');
    this.attachShadow({ mode: 'open' });
    adoptSharedStyles(this.shadowRoot);
  }

  set api(value) {
    this._api = value;
  }

  get api() {
    return this._api;
  }

  set wizard(value) {
    if (this._unsubWizard) this._unsubWizard();
    this._wizard = value;
    this._unsubWizard = value && this.isConnected ? value.subscribe(() => this._scheduleRender()) : null;
    this._scheduleRender();
  }

  get wizard() {
    return this._wizard;
  }

  connectedCallback() {
    if (this._wizard && !this._unsubWizard) {
      this._unsubWizard = this._wizard.subscribe(() => this._scheduleRender());
    }
    this._startScan();
    this._scheduleRender();
  }

  disconnectedCallback() {
    if (this._unsubWizard) {
      this._unsubWizard();
      this._unsubWizard = null;
    }
    this._stopScan();
    if (this._rafId) {
      cancelAnimationFrame(this._rafId);
      this._rafId = null;
    }
  }

  async _startScan() {
    if (this._scanStarted) return;
    // hass (and therefore this._api.hass) may not be wired up yet at connectedCallback time even
    // though it arrives moments later in the same tick: check synchronously and simply wait for
    // retryConnection() rather than racing a doomed call that would show a scary error for what
    // is really just normal startup ordering.
    if (!this._api?.hass?.connection?.subscribeMessage) return;
    this._scanStarted = true;
    try {
      const unsubscribe = await this._api.subscribeScan((event) => {
        this._wizard?.dispatch({ type: 'UPSERT_DEVICE', device: event });
      });
      this._unsubScan = unsubscribe;
      this._scanError = null;
      if (!this.isConnected) unsubscribe();
    } catch (err) {
      this._scanStarted = false;
      this._scanError = err;
      this._scheduleRender();
    }
  }

  // Called by the panel when hass transitions from unavailable to ready, and by the manual retry
  // button after a genuine failure. Safe to call any time: _startScan() no-ops if already
  // running or still not ready.
  retryConnection() {
    this._startScan();
  }

  _retryScan() {
    this._scanError = null;
    this._startScan();
    this._render();
  }

  _stopScan() {
    if (typeof this._unsubScan === 'function') {
      try {
        this._unsubScan();
      } catch {
        // Connection already gone; nothing to clean up.
      }
    }
    this._unsubScan = null;
    this._scanStarted = false;
  }

  // Advertisements can arrive many times a second across a handful of devices; batching to one
  // render per animation frame keeps this responsive without redrawing on every single packet.
  _scheduleRender() {
    if (this._rafId || !this.isConnected) return;
    this._rafId = requestAnimationFrame(() => {
      this._rafId = null;
      this._render();
    });
  }

  _renderDeviceCell(row) {
    return h('div', {}, [h('div', {}, formatDeviceLabel(row)), h('div', { class: 'bs-empty' }, formatAddress(row.address))]);
  }

  _renderFamilyCell(row) {
    const badge = formatFamilyBadge(row.family);
    if (!badge) return h('span', { class: 'bs-empty' }, 'Unrecognized');
    const parts = [badge.name];
    if (badge.confidenceLabel) parts.push(badge.confidenceLabel);
    return h('span', { class: 'bs-badge', title: badge.evidence.join('; ') || undefined }, parts.join(' \u00b7 '));
  }

  _renderRssiCell(row) {
    const rssi = formatRssi(row.rssi);
    return h('div', {}, [
      h('div', {}, rssi.label),
      h('span', { class: 'bs-rssi-bar' }, h('span', { class: 'bs-rssi-bar__fill', 'data-tone': rssi.tone, style: { width: `${rssi.percent}%` } })),
      h('div', { class: 'bs-empty' }, row.source ? `best via ${row.source}` : '\u2014'),
      row.connectable === false ? h('div', { class: 'bs-empty' }, 'Advertisement only (not connectable)') : null,
    ]);
  }

  _renderActionsCell(row, selectedAddress) {
    const selected = selectedAddress === row.address;
    return h(
      'button',
      {
        type: 'button',
        class: selected ? 'bs-btn bs-btn--primary' : 'bs-btn',
        'aria-pressed': String(selected),
        onClick: () => this._wizard.dispatch({ type: 'SELECT_DEVICE', address: row.address }),
      },
      selected ? 'Selected' : 'Select',
    );
  }

  _render() {
    if (!this._wizard) return;
    withPreservedFocus(this.shadowRoot, () => this._doRender());
  }

  _doRender() {
    const state = this._wizard.getState();
    clear(this.shadowRoot);

    const devices = sortByRssiDesc(Object.values(state.devices)).filter((row) => matchesQuery(row, state.find.query));

    const search = h('input', {
      type: 'search',
      id: this._searchId,
      value: state.find.query,
      placeholder: 'Name or address',
      onInput: (event) => this._wizard.dispatch({ type: 'SET_QUERY', query: event.target.value }),
    });

    const table = document.createElement('bs-table');
    table.emptyMessage = 'No devices seen yet. Make sure the device is powered on and advertising near a Bluetooth proxy.';
    table.columns = [
      { key: 'device', label: 'Device', render: (row) => this._renderDeviceCell(row) },
      { key: 'family', label: 'Family', render: (row) => this._renderFamilyCell(row) },
      { key: 'rssi', label: 'Signal', render: (row) => this._renderRssiCell(row) },
      { key: 'actions', label: '', render: (row) => this._renderActionsCell(row, state.find.selectedAddress) },
    ];
    table.rows = devices;
    table.rowAttributes = (row) => ({ dataset: { selected: String(row.address === state.find.selectedAddress) } });

    const announcement = liveRegion('polite');
    announcement.textContent = `${devices.length} device${devices.length === 1 ? '' : 's'} found`;

    const body = h('div', {}, [
      h('div', { class: 'bs-field-row' }, [
        h('div', { class: 'bs-field', style: { flex: '1 1 240px' } }, [h('label', { htmlFor: this._searchId }, 'Search'), search]),
        h(
          'button',
          { type: 'button', class: 'bs-btn bs-btn--text', onClick: () => this._wizard.dispatch({ type: 'RESET_DEVICES' }) },
          'Clear list',
        ),
      ]),
      this._scanError
        ? h('div', { class: 'bs-banner bs-banner--error' }, [
            `Could not start scanning: ${this._scanError.message} `,
            h('button', { type: 'button', class: 'bs-btn bs-btn--text', onClick: () => this._retryScan() }, 'Retry'),
          ])
        : null,
      announcement,
      table,
    ]);

    const card = document.createElement('bs-card');
    card.heading = 'Find';
    card.subheading = 'Pick the device to onboard.';
    card.status = state.find.selectedAddress ? 'complete' : 'active';
    card.append(body);

    this.shadowRoot.append(card);
  }
}

if (!customElements.get('bs-step-find')) customElements.define('bs-step-find', BsStepFind);
