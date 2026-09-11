// Small vanilla custom-element toolkit for the BlueShark wizard: a hyperscript-ish DOM builder,
// a handful of plain-input field factories, and the six reusable components the steps are built
// from (card, stepper, chip, table, hex field, confirm dialog). Every element styles itself only
// from the shared, theme-variable-only stylesheet in styles.js. Requires a DOM; never imported by
// the Node test suite (format.js/wizard.js/api.js are the pure, tested layer).

import { adoptSharedStyles } from './styles.js';
import { parseHex } from './format.js';

// --- DOM builder ---------------------------------------------------------------

let uidCounter = 0;
/** A short, deterministic, collision-free id for wiring <label for> to a generated control. */
export function uid(prefix = 'bs') {
  uidCounter += 1;
  return `${prefix}-${uidCounter}`;
}

function applyProps(el, props) {
  for (const [key, value] of Object.entries(props ?? {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') {
      el.className = value;
      continue;
    }
    if (key === 'style' && typeof value === 'object') {
      Object.assign(el.style, value);
      continue;
    }
    if (key === 'dataset' && typeof value === 'object') {
      Object.assign(el.dataset, value);
      continue;
    }
    if (key.startsWith('on') && typeof value === 'function') {
      el.addEventListener(key.slice(2).toLowerCase(), value);
      continue;
    }
    if (key in el) {
      try {
        el[key] = value;
        continue;
      } catch {
        // Not writable on this element; fall through to a plain attribute.
      }
    }
    el.setAttribute(key, value === true ? '' : String(value));
  }
}

function appendChildren(el, children) {
  const list = Array.isArray(children) ? children : [children];
  for (const child of list) {
    if (child === null || child === undefined || child === false) continue;
    if (Array.isArray(child)) {
      appendChildren(el, child);
      continue;
    }
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

/** Minimal hyperscript: h('div', {class:'x', onClick:fn}, ['text', childNode]). Skips
 * null/undefined/false props and children at any nesting depth so ternaries read naturally. */
export function h(tag, props = {}, children = []) {
  const el = document.createElement(tag);
  applyProps(el, props);
  appendChildren(el, children);
  return el;
}

export function text(value) {
  return document.createTextNode(String(value ?? ''));
}

export function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
}

/** Run `rebuild()` (which typically clears and repopulates a subtree) without losing keyboard
 * focus or cursor position on the field the operator is actively typing into. Every full
 * re-render in this wizard replaces DOM nodes wholesale rather than diffing, which would
 * otherwise steal focus after the first keystroke into any field whose change re-renders its
 * step. Relies on the focused field carrying a stable `id` across renders (a fixed id from the
 * constructor, or a deterministic one derived from a stable row key). */
export function withPreservedFocus(shadowRoot, rebuild) {
  const active = shadowRoot.activeElement;
  const activeId = active?.id || null;
  const selectionStart = active && typeof active.selectionStart === 'number' ? active.selectionStart : null;
  const selectionEnd = active && typeof active.selectionEnd === 'number' ? active.selectionEnd : null;
  rebuild();
  if (!activeId) return;
  const restored = shadowRoot.getElementById(activeId);
  if (!restored) return;
  restored.focus({ preventScroll: true });
  if (selectionStart !== null && typeof restored.setSelectionRange === 'function') {
    try {
      restored.setSelectionRange(selectionStart, selectionEnd);
    } catch {
      // Some input types (e.g. number) reject setSelectionRange; focus alone still landed.
    }
  }
}

/** A visually-hidden live region for announcing streaming updates to screen readers. */
export function liveRegion(politeness = 'polite') {
  return h('div', { 'aria-live': politeness, class: 'bs-visually-hidden' }, '');
}

// --- Plain field factories -------------------------------------------------------
// Not custom elements: a labeled <input>/<select>/checkbox is simple enough as markup, and every
// step rebuilds its whole subtree on each wizard change anyway (see the steps/*.js render loop).

export function textField({ label, value = '', onInput, onChange, id, placeholder, type = 'text', mono = false, disabled = false } = {}) {
  const inputId = id || uid('txt');
  const input = h('input', {
    type,
    id: inputId,
    value,
    placeholder,
    disabled,
    class: mono ? 'bs-mono' : null,
    onInput: onInput ? (event) => onInput(event.target.value) : null,
    onChange: onChange ? (event) => onChange(event.target.value) : null,
  });
  const element = h('div', { class: 'bs-field' }, [label ? h('label', { htmlFor: inputId }, label) : null, input]);
  return { element, input };
}

export function selectField({ label, options, value, onChange, id, disabled = false } = {}) {
  const selectId = id || uid('sel');
  const select = h(
    'select',
    { id: selectId, disabled, onChange: onChange ? (event) => onChange(event.target.value) : null },
    (options ?? []).map((opt) => h('option', { value: opt.value }, opt.label)),
  );
  select.value = value ?? '';
  const element = h('div', { class: 'bs-field' }, [label ? h('label', { htmlFor: selectId }, label) : null, select]);
  return { element, select };
}

export function checkboxField({ label, checked = false, onChange, id, disabled = false } = {}) {
  const checkboxId = id || uid('chk');
  const input = h('input', {
    type: 'checkbox',
    id: checkboxId,
    checked,
    disabled,
    onChange: onChange ? (event) => onChange(event.target.checked) : null,
  });
  const element = h('div', { class: 'bs-checkbox-row' }, [input, h('label', { htmlFor: checkboxId }, label)]);
  return { element, input };
}

function define(name, ctor) {
  if (!customElements.get(name)) customElements.define(name, ctor);
}

// --- <bs-card> -----------------------------------------------------------------
// One stage of the wizard. Slots its light-DOM children as the body, collapsing to a heading +
// checkmark once `status` is "complete" (the operator can still click to re-open and review).

class BsCard extends HTMLElement {
  constructor() {
    super();
    this._heading = '';
    this._subheading = '';
    this._status = 'pending';
    this._open = true;
    this._headingId = uid('bs-card-heading');
    this.attachShadow({ mode: 'open' });
    adoptSharedStyles(this.shadowRoot);
    this._render();
  }

  set heading(value) {
    this._heading = String(value ?? '');
    this._render();
  }

  get heading() {
    return this._heading;
  }

  set subheading(value) {
    this._subheading = String(value ?? '');
    this._render();
  }

  set status(value) {
    this._status = value ?? 'pending';
    this._render();
  }

  get status() {
    return this._status;
  }

  set open(value) {
    this._open = Boolean(value);
    this._render();
  }

  get open() {
    return this._open;
  }

  _render() {
    clear(this.shadowRoot);
    const badge =
      this._status === 'pending'
        ? null
        : h('span', { class: 'bs-stepper__badge', 'aria-hidden': 'true' }, this._status === 'complete' ? '\u2713' : '\u2022');
    const header = h(
      'button',
      {
        type: 'button',
        class: 'bs-card__header',
        'aria-expanded': String(this._open),
        onClick: () => {
          this.open = !this._open;
          this.dispatchEvent(new CustomEvent('toggle', { detail: { open: this._open } }));
        },
      },
      [
        badge,
        h('span', {}, [
          h('p', { class: 'bs-card__heading', id: this._headingId }, this._heading),
          this._subheading ? h('p', { class: 'bs-card__subheading' }, this._subheading) : null,
        ]),
        h('span', { class: 'bs-card__chevron', 'aria-hidden': 'true' }, '\u25BE'),
      ],
    );
    const body = h('div', { class: 'bs-card__body', hidden: !this._open, role: 'region', 'aria-labelledby': this._headingId }, [
      h('slot'),
    ]);
    const section = h('section', { class: 'bs-card' }, [header, body]);
    section.dataset.status = this._status;
    if (this._open) section.setAttribute('open', '');
    this.shadowRoot.append(section);
  }
}

// --- <bs-chip> -----------------------------------------------------------------
// Verdict/status chip. Always renders `label` as text; tone is a color hint, never the only cue.

class BsChip extends HTMLElement {
  constructor() {
    super();
    this._tone = 'neutral';
    this._label = '';
    this.attachShadow({ mode: 'open' });
    adoptSharedStyles(this.shadowRoot);
    this._render();
  }

  set tone(value) {
    this._tone = value || 'neutral';
    this._render();
  }

  get tone() {
    return this._tone;
  }

  set label(value) {
    this._label = String(value ?? '');
    this._render();
  }

  get label() {
    return this._label;
  }

  _render() {
    clear(this.shadowRoot);
    this.shadowRoot.append(h('span', { class: `bs-chip bs-chip--${this._tone}`, role: 'status' }, this._label));
  }
}

/** Convenience: build a configured <bs-chip> from a {tone, label} pair (e.g. formatVerdict()'s output). */
export function chip({ tone, label }) {
  const el = document.createElement('bs-chip');
  el.tone = tone;
  el.label = label;
  return el;
}

// --- <bs-stepper> ----------------------------------------------------------------
// The Find/Identify/Learn/Finish nav. Lays out as a column on wide viewports, a row on narrow
// ones (driven by the :host([narrow]) rule in styles.js, toggled by the host panel).

class BsStepper extends HTMLElement {
  constructor() {
    super();
    this._steps = [];
    this._active = null;
    this.attachShadow({ mode: 'open' });
    adoptSharedStyles(this.shadowRoot);
    this._render();
  }

  set steps(value) {
    this._steps = Array.isArray(value) ? value : [];
    this._render();
  }

  get steps() {
    return this._steps;
  }

  set active(value) {
    this._active = value ?? null;
    this._render();
  }

  get active() {
    return this._active;
  }

  _render() {
    clear(this.shadowRoot);
    const nav = h(
      'nav',
      { class: 'bs-stepper', 'aria-label': 'Onboarding steps' },
      this._steps.map((step, index) => {
        const isActive = step.id === this._active;
        const button = h(
          'button',
          {
            type: 'button',
            class: 'bs-stepper__step',
            disabled: !step.reachable,
            'aria-current': isActive ? 'step' : null,
            onClick: () => {
              if (step.reachable) this.dispatchEvent(new CustomEvent('step-select', { detail: { id: step.id } }));
            },
          },
          [
            h('span', { class: 'bs-stepper__badge', 'aria-hidden': 'true' }, step.status === 'complete' ? '\u2713' : String(index + 1)),
            h('span', {}, step.label),
          ],
        );
        button.dataset.status = step.status ?? 'pending';
        return button;
      }),
    );
    this.shadowRoot.append(nav);
  }
}

// --- <bs-hex-field> --------------------------------------------------------------
// A monospace, live-validated hex input. Fires "value-change" (detail: {value, valid}) rather
// than reusing "input"/"change" so it can't double-fire alongside the native <input> event, which
// already crosses the shadow boundary composed+retargeted.

class BsHexField extends HTMLElement {
  constructor() {
    super();
    this._label = '';
    this._value = '';
    this._placeholder = '';
    this._allowEmpty = true;
    this._error = null;
    this._input = null;
    this._errorEl = null;
    this._inputId = uid('bs-hex');
    this.attachShadow({ mode: 'open', delegatesFocus: true });
    adoptSharedStyles(this.shadowRoot);
    this._render();
  }

  set label(value) {
    this._label = String(value ?? '');
    this._render();
  }

  set placeholder(value) {
    this._placeholder = String(value ?? '');
    this._render();
  }

  set allowEmpty(value) {
    this._allowEmpty = Boolean(value);
    this._validate();
  }

  get allowEmpty() {
    return this._allowEmpty;
  }

  set value(value) {
    this._value = String(value ?? '');
    if (this._input) this._input.value = this._value;
    this._validate();
  }

  get value() {
    return this._value;
  }

  get valid() {
    return this._error === null;
  }

  get error() {
    return this._error;
  }

  /** Parsed bytes for the current value, or null while invalid. */
  get bytes() {
    try {
      return parseHex(this._value, { allowEmpty: this._allowEmpty }).bytes;
    } catch {
      return null;
    }
  }

  _validate() {
    try {
      parseHex(this._value, { allowEmpty: this._allowEmpty });
      this._error = null;
    } catch (err) {
      this._error = err.message;
    }
    if (this._errorEl) {
      this._errorEl.textContent = this._error ?? '';
      this._errorEl.hidden = !this._error;
    }
    if (this._input) this._input.setAttribute('aria-invalid', String(!this.valid));
  }

  _render() {
    clear(this.shadowRoot);
    this._input = h('input', {
      type: 'text',
      id: this._inputId,
      class: 'bs-mono',
      value: this._value,
      placeholder: this._placeholder,
      spellcheck: false,
      autocomplete: 'off',
      'aria-describedby': `${this._inputId}-error`,
      onInput: (event) => {
        this._value = event.target.value;
        this._validate();
        this.dispatchEvent(new CustomEvent('value-change', { detail: { value: this._value, valid: this.valid }, bubbles: true, composed: true }));
      },
    });
    this._errorEl = h('p', { class: 'bs-field__error', id: `${this._inputId}-error`, hidden: true }, '');
    this.shadowRoot.append(
      h('div', { class: 'bs-field bs-hexfield' }, [
        this._label ? h('label', { htmlFor: this._inputId }, this._label) : null,
        this._input,
        this._errorEl,
      ]),
    );
    this._validate();
  }
}

// --- <bs-table> ------------------------------------------------------------------
// Generic column/row renderer shared by the GATT characteristic list, the sweep results table
// and the Finish command-map review. `column.render(row, index)` may return a string or a Node
// (e.g. a <bs-chip> for a verdict cell); `rowAttributes(row, index)` can mark e.g. canary rows.

class BsTable extends HTMLElement {
  constructor() {
    super();
    this._columns = [];
    this._rows = [];
    this._emptyMessage = 'Nothing here yet.';
    this._rowAttributes = null;
    this.attachShadow({ mode: 'open' });
    adoptSharedStyles(this.shadowRoot);
    this._render();
  }

  set columns(value) {
    this._columns = Array.isArray(value) ? value : [];
    this._render();
  }

  set rows(value) {
    this._rows = Array.isArray(value) ? value : [];
    this._render();
  }

  get rows() {
    return this._rows;
  }

  set emptyMessage(value) {
    this._emptyMessage = String(value ?? '');
    this._render();
  }

  set rowAttributes(fn) {
    this._rowAttributes = typeof fn === 'function' ? fn : null;
    this._render();
  }

  _render() {
    clear(this.shadowRoot);
    if (!this._rows.length) {
      this.shadowRoot.append(h('p', { class: 'bs-table__empty' }, this._emptyMessage));
      return;
    }
    const thead = h('thead', {}, [h('tr', {}, this._columns.map((col) => h('th', { scope: 'col' }, col.label)))]);
    const tbody = h(
      'tbody',
      {},
      this._rows.map((row, index) => {
        const tr = h(
          'tr',
          {},
          this._columns.map((col) => h('td', {}, col.render ? col.render(row, index) : text(row[col.key] ?? ''))),
        );
        const extra = this._rowAttributes ? this._rowAttributes(row, index) : null;
        if (extra) applyProps(tr, extra);
        return tr;
      }),
    );
    this.shadowRoot.append(h('div', { class: 'bs-table-wrap' }, h('table', { class: 'bs-table' }, [thead, tbody])));
  }
}

// --- <bs-confirm-dialog> -----------------------------------------------------------
// A native <dialog>-backed confirm. `.open({title, message, confirmLabel, cancelLabel, danger})`
// returns a Promise<boolean>: true only when the operator clicks the confirm button.

class BsConfirmDialog extends HTMLElement {
  constructor() {
    super();
    this._resolve = null;
    this.attachShadow({ mode: 'open' });
    adoptSharedStyles(this.shadowRoot);
    this._render();
  }

  _render() {
    clear(this.shadowRoot);
    this._titleEl = h('h2', { class: 'bs-dialog__title' }, '');
    this._messageEl = h('p', { class: 'bs-dialog__message' }, '');
    this._cancelBtn = h('button', { type: 'button', class: 'bs-btn', onClick: () => this._close(false) }, 'Cancel');
    this._confirmBtn = h('button', { type: 'button', class: 'bs-btn bs-btn--danger', onClick: () => this._close(true) }, 'Confirm');
    this._dialog = h(
      'dialog',
      { class: 'bs-dialog', 'aria-labelledby': 'bs-dialog-title', 'aria-describedby': 'bs-dialog-message' },
      [this._titleEl, this._messageEl, h('div', { class: 'bs-dialog__actions' }, [this._cancelBtn, this._confirmBtn])],
    );
    this._titleEl.id = 'bs-dialog-title';
    this._messageEl.id = 'bs-dialog-message';
    this._dialog.addEventListener('cancel', (event) => {
      event.preventDefault();
      this._close(false);
    });
    this.shadowRoot.append(this._dialog);
  }

  _close(result) {
    if (this._dialog.open) this._dialog.close();
    const resolve = this._resolve;
    this._resolve = null;
    if (resolve) resolve(result);
  }

  open({ title = 'Are you sure?', message = '', confirmLabel = 'Confirm', cancelLabel = 'Cancel', danger = true } = {}) {
    this._titleEl.textContent = title;
    this._messageEl.textContent = message;
    this._confirmBtn.textContent = confirmLabel;
    this._cancelBtn.textContent = cancelLabel;
    this._confirmBtn.className = danger ? 'bs-btn bs-btn--danger' : 'bs-btn bs-btn--primary';
    this._dialog.showModal();
    this._confirmBtn.focus();
    return new Promise((resolve) => {
      this._resolve = resolve;
    });
  }
}

define('bs-card', BsCard);
define('bs-chip', BsChip);
define('bs-stepper', BsStepper);
define('bs-hex-field', BsHexField);
define('bs-table', BsTable);
define('bs-confirm-dialog', BsConfirmDialog);
