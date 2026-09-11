// Entry point for the BlueShark custom panel. Registered by the integration as a `_panel_custom`
// with `module_url` pointing at this file (it uses import/export, so it must load as an ES
// module, not a classic script). Home Assistant creates one `<blueshark-panel>` and assigns
// `hass`, `narrow`, `route` and `panel` as plain properties on every relevant update.

import { adoptSharedStyles } from './styles.js';
import { createWizard, STEP_ORDER, reachableSteps } from './wizard.js';
import { BlueSharkApi } from './api.js';
import { h, clear } from './components.js';
import './steps/find.js';
import './steps/identify.js';
import './steps/learn.js';
import './steps/finish.js';

const STEP_LABELS = { find: 'Find', identify: 'Identify', learn: 'Learn', finish: 'Finish' };
const STEP_TAGS = { find: 'bs-step-find', identify: 'bs-step-identify', learn: 'bs-step-learn', finish: 'bs-step-finish' };

class BlueSharkPanel extends HTMLElement {
  constructor() {
    super();
    this._hass = null;
    this._narrow = false;
    this._route = null;
    this._panel = null;
    this._wizard = createWizard();
    this._api = new BlueSharkApi(null);
    this._mountedStep = null;
    this._unsubWizard = null;

    this.attachShadow({ mode: 'open' });
    adoptSharedStyles(this.shadowRoot);

    this._stepper = document.createElement('bs-stepper');
    this._stepper.addEventListener('step-select', (event) => {
      this._wizard.dispatch({ type: 'GO_TO_STEP', step: event.detail.id });
    });

    this._stepHost = h('div', { class: 'bs-content' });
    const header = h('header', { style: { padding: '16px 16px 0' } }, [
      h('h1', { style: { margin: '0', fontSize: '1.4em' } }, 'BlueShark'),
      h('p', { class: 'bs-empty', style: { margin: '4px 0 0' } }, 'Add any BLE device through a guided, evidence-first wizard.'),
    ]);
    const shell = h('div', { class: 'bs-shell' }, [this._stepper, this._stepHost]);
    this.shadowRoot.append(header, shell);
  }

  set hass(value) {
    const wasReady = Boolean(this._hass && typeof this._hass.callWS === 'function');
    this._hass = value;
    this._api.hass = value;
    const isReady = Boolean(value && typeof value.callWS === 'function');
    if (!wasReady && isReady && typeof this._mountedStep?.retryConnection === 'function') {
      this._mountedStep.retryConnection();
    }
  }

  get hass() {
    return this._hass;
  }

  set narrow(value) {
    this._narrow = Boolean(value);
    if (this._narrow) this.setAttribute('narrow', '');
    else this.removeAttribute('narrow');
  }

  get narrow() {
    return this._narrow;
  }

  set route(value) {
    this._route = value;
  }

  get route() {
    return this._route;
  }

  set panel(value) {
    this._panel = value;
  }

  get panel() {
    return this._panel;
  }

  connectedCallback() {
    if (!this._unsubWizard) this._unsubWizard = this._wizard.subscribe(() => this._renderStepper());
    this._renderStepper();
  }

  disconnectedCallback() {
    if (this._unsubWizard) {
      this._unsubWizard();
      this._unsubWizard = null;
    }
  }

  _renderStepper() {
    const state = this._wizard.getState();
    const reachable = reachableSteps(state);
    const currentIndex = STEP_ORDER.indexOf(state.step);
    this._stepper.steps = STEP_ORDER.map((id, index) => ({
      id,
      label: STEP_LABELS[id],
      reachable: reachable.includes(id),
      status: id === state.step ? 'active' : reachable.includes(id) && index < currentIndex ? 'complete' : 'pending',
    }));
    this._stepper.active = state.step;
    this._syncActiveStep(state);
  }

  // Only replaces the mounted step element when the active step itself changes; any other
  // wizard-state change is handled by that element's own subscription, so it never loses its own
  // in-progress UI (typed-but-not-yet-submitted form fields, open dialogs, etc.).
  _syncActiveStep(state) {
    const tag = STEP_TAGS[state.step];
    if (this._mountedStep && this._mountedStep.tagName.toLowerCase() === tag) return;
    clear(this._stepHost);
    const el = document.createElement(tag);
    el.api = this._api;
    el.wizard = this._wizard;
    // Assigned before append: append() connects el synchronously, which runs its
    // connectedCallback, which can render and dispatch (e.g. the existing-entry check),
    // which notifies this panel's own subscriber and re-enters _syncActiveStep before this
    // call would otherwise have returned. That re-entrant call must see the real _mountedStep
    // (and take the early-return above) instead of racing to mount a second, duplicate element.
    this._mountedStep = el;
    this._stepHost.append(el);
  }
}

if (!customElements.get('blueshark-panel')) customElements.define('blueshark-panel', BlueSharkPanel);
