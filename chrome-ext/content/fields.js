/*
 * Field adapters: one interface over <textarea>, <input> and contenteditable.
 *  - getText()            plain text of the field
 *  - rectsFor(start,end)  viewport rectangles of a character range (call prepare() first)
 *  - getSelection()       {start,end} offsets of the selection, or null
 *  - replace(s,e,text)    edit through execCommand so undo history and framework listeners keep working
 */
(function () {
  'use strict';
  const Q = self.Quill;

  // ------------------------------------------------------------ eligibility

  const CODE_EDITORS = '.monaco-editor,.CodeMirror,.cm-editor,.ace_editor,.xterm,.jsoneditor,.view-lines';
  const OPT_OUT = '[data-quill="off"],[data-gramm="false"],[data-gramm_editor="false"],[data-enable-grammarly="false"]';
  // Only genuinely sensitive inputs are skipped; short values (names, emails) never reach the model anyway
  // because a check needs at least two words.
  const SENSITIVE = /pass(word|code|wd)|otp|one-?time|2fa|cvv|cvc|card-?(number|num)|cc-?(num|number|exp|csc)|captcha|iban|\bssn\b/i;
  const SENSITIVE_AUTOCOMPLETE = /^(cc-|one-time-code|current-password|new-password)/;
  const TEXT_TYPES = new Set(['text', 'search', 'email', 'url']);

  /** The editable element a focus / input event belongs to, or null. */
  Q.editableFor = function (t) {
    if (!(t instanceof Element)) return null;
    if (t instanceof HTMLTextAreaElement || t instanceof HTMLInputElement) return t;
    if (t.isContentEditable) {
      let e = t;
      while (e.parentElement && e.parentElement.isContentEditable) e = e.parentElement;
      return e;
    }
    return null;
  };

  /** Prose fields only: no passwords, search boxes, autofill-style inputs or code editors. */
  Q.eligible = function (el) {
    if (!el || !el.isConnected) return false;
    if (el.closest(OPT_OUT) || el.closest(CODE_EDITORS)) return false;
    if (el instanceof HTMLInputElement) {
      const type = (el.getAttribute('type') || 'text').toLowerCase();
      if (!TEXT_TYPES.has(type)) return false; // password, number, tel, date, file ...
      if (el.readOnly || el.disabled) return false;
      if (SENSITIVE_AUTOCOMPLETE.test((el.getAttribute('autocomplete') || '').toLowerCase())) return false;
      if (/^(numeric|decimal|tel)$/.test(el.inputMode)) return false;
      if (SENSITIVE.test([el.name, el.id, el.getAttribute('aria-label')].join(' '))) return false;
    } else if (el instanceof HTMLTextAreaElement) {
      if (el.readOnly || el.disabled) return false;
    } else if (!el.isContentEditable) return false;
    const r = el.getBoundingClientRect();
    return el instanceof HTMLInputElement ? r.width >= 60 && r.height >= 18 : r.width >= 40 && r.height >= 16;
  };

  // ------------------------------------------------------ contenteditable text

  const BLOCK = new Set(['DIV', 'P', 'LI', 'UL', 'OL', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'BLOCKQUOTE', 'PRE', 'TR', 'TABLE', 'SECTION', 'ARTICLE', 'HEADER', 'FOOTER', 'FORM', 'DL', 'DT', 'DD']);
  const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE']);

  /** Plain text of an editable tree plus where each text node sits in it; blocks and <br> become newlines. */
  function buildModel(root) {
    let text = '';
    const segs = [];
    const nl = () => {
      if (text && text[text.length - 1] !== '\n') text += '\n';
    };
    const walk = (n) => {
      if (n.nodeType === 3) {
        const v = n.nodeValue;
        if (v) {
          segs.push({ node: n, start: text.length, end: text.length + v.length });
          text += v;
        }
        return;
      }
      if (n.nodeType !== 1 || SKIP.has(n.tagName)) return;
      if (n.tagName === 'BR') {
        text += '\n';
        return;
      }
      const block = n !== root && BLOCK.has(n.tagName);
      if (block) nl();
      for (let c = n.firstChild; c; c = c.nextSibling) walk(c);
      if (block) nl();
    };
    walk(root);
    return { text, segs };
  }

  function toOffset(model, node, off) {
    if (node.nodeType === 3) {
      const s = model.segs.find((x) => x.node === node);
      return s ? s.start + off : model.text.length;
    }
    try {
      const b = document.createRange();
      b.setStart(node, off);
      b.collapse(true);
      for (const s of model.segs) if (b.comparePoint(s.node, 0) >= 0) return s.start;
    } catch (e) {}
    return model.text.length;
  }

  // ------------------------------------------------------- mirror (textarea/input)

  let mirrorDiv = null;
  const MIRROR_COPY = [
    'fontStyle', 'fontVariant', 'fontWeight', 'fontStretch', 'fontSize', 'fontSizeAdjust', 'lineHeight', 'fontFamily',
    'fontKerning', 'fontFeatureSettings', 'fontVariantLigatures', 'textAlign', 'textTransform', 'textIndent', 'letterSpacing',
    'wordSpacing', 'tabSize', 'wordBreak', 'direction', 'writingMode', 'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
  ];

  /** A hidden clone, isolated from page CSS in a shadow root, that lays text out exactly like the field. */
  function mirror() {
    if (mirrorDiv && mirrorDiv.isConnected) return mirrorDiv;
    const host = document.createElement('quill-mirror');
    host.style.cssText = 'all:initial;position:fixed;left:0;top:0;width:0;height:0;overflow:visible;visibility:hidden;pointer-events:none;contain:layout;z-index:-1;';
    const root = host.attachShadow({ mode: 'closed' });
    mirrorDiv = document.createElement('div');
    root.appendChild(mirrorDiv);
    document.documentElement.appendChild(host);
    return mirrorDiv;
  }

  // ------------------------------------------------------------------ Field

  class Field {
    constructor(el) {
      this.el = el;
      this.kind = el instanceof HTMLTextAreaElement ? 'textarea' : el instanceof HTMLInputElement ? 'input' : 'ce';
      this._model = null;
      this._dy = 0;
    }

    get multiline() {
      return this.kind !== 'input';
    }

    invalidate() {
      this._model = null;
    }

    model() {
      return this._model || (this._model = buildModel(this.el));
    }

    getText() {
      return this.kind === 'ce' ? this.model().text : this.el.value;
    }

    /** Lays the mirror out for the current text; call once before a batch of rectsFor(). */
    prepare() {
      if (this.kind === 'ce') {
        this.invalidate();
        this.model();
        return;
      }
      const el = this.el;
      const div = mirror();
      const cs = getComputedStyle(el);
      div.removeAttribute('style');
      MIRROR_COPY.forEach((p) => (div.style[p] = cs[p]));
      const padX = (parseFloat(cs.paddingLeft) || 0) + (parseFloat(cs.paddingRight) || 0);
      const padY = (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0);
      const wraps = this.kind === 'textarea' && el.wrap !== 'off' && !/^(pre|nowrap)$/.test(cs.whiteSpace);
      Object.assign(div.style, {
        position: 'absolute',
        left: '0',
        top: '0',
        margin: '0',
        border: '0',
        boxSizing: 'content-box',
        height: 'auto',
        width: Math.max(0, el.clientWidth - padX) + 'px',
        whiteSpace: wraps ? 'pre-wrap' : 'pre',
        overflowWrap: wraps ? 'break-word' : 'normal',
      });
      div.textContent = el.value;
      // A single-line input centres its text vertically inside the content box.
      this._dy = this.kind === 'input' ? Math.max(0, (el.clientHeight - padY - (div.getBoundingClientRect().height - padY)) / 2) : 0;
    }

    /** Viewport rectangles covering characters [start, end). */
    rectsFor(start, end) {
      let rects;
      if (this.kind === 'ce') {
        const r = this.rangeFor(start, end);
        rects = r ? Array.from(r.getClientRects()) : [];
      } else {
        const div = mirror();
        const tn = div.firstChild;
        if (!tn) return [];
        const len = tn.nodeValue.length;
        const r = document.createRange();
        r.setStart(tn, Math.min(start, len));
        r.setEnd(tn, Math.min(end, len));
        const base = div.getBoundingClientRect();
        const box = this.el.getBoundingClientRect();
        const ox = box.left + this.el.clientLeft - base.left - this.el.scrollLeft;
        const oy = box.top + this.el.clientTop - base.top - this.el.scrollTop + this._dy;
        rects = Array.from(r.getClientRects()).map((c) => ({ left: c.left + ox, top: c.top + oy, width: c.width, height: c.height }));
      }
      return rects
        .filter((c) => c.width > 0.5 && c.height > 0.5)
        .map((c) => ({ left: c.left, top: c.top, width: c.width, height: c.height, right: c.left + c.width, bottom: c.top + c.height }));
    }

    rangeFor(start, end) {
      const segs = this.model().segs;
      if (!segs.length) return null;
      let a = null;
      let b = null;
      for (const s of segs) {
        if (!a && start < s.end) a = { node: s.node, off: Math.max(0, start - s.start) };
        if (end <= s.end) {
          b = { node: s.node, off: Math.max(0, end - s.start) };
          break;
        }
      }
      if (!a) return null;
      if (!b) {
        const last = segs[segs.length - 1];
        b = { node: last.node, off: last.node.nodeValue.length };
      }
      const r = document.createRange();
      try {
        r.setStart(a.node, Math.min(a.off, a.node.nodeValue.length));
        r.setEnd(b.node, Math.min(b.off, b.node.nodeValue.length));
      } catch (e) {
        return null;
      }
      return r;
    }

    selectionObj() {
      const root = this.el.getRootNode();
      return root && root.getSelection ? root.getSelection() : document.getSelection();
    }

    /** {start,end} of the user's selection inside this field, or null. */
    getSelection() {
      if (this.kind !== 'ce') {
        try {
          return { start: this.el.selectionStart, end: this.el.selectionEnd };
        } catch (e) {
          return null;
        }
      }
      const sel = this.selectionObj();
      if (!sel || !sel.rangeCount) return null;
      const r = sel.getRangeAt(0);
      if (!this.el.contains(r.startContainer) || !this.el.contains(r.endContainer)) return null;
      this.invalidate();
      const m = this.model();
      return { start: toOffset(m, r.startContainer, r.startOffset), end: toOffset(m, r.endContainer, r.endOffset) };
    }

    focus() {
      try {
        this.el.focus({ preventScroll: true });
      } catch (e) {}
    }

    /** Replaces [start, end) with [text]; returns false if the range could not be found. */
    replace(start, end, text) {
      const el = this.el;
      this.focus();
      if (this.kind !== 'ce') {
        el.setSelectionRange(start, end);
        let ok = false;
        try {
          ok = document.execCommand('insertText', false, text);
        } catch (e) {}
        if (!ok || el.value.substr(start, text.length) !== text) {
          const proto = this.kind === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
          const set = Object.getOwnPropertyDescriptor(proto, 'value').set;
          const v = el.value;
          set.call(el, v.slice(0, start) + text + v.slice(end));
          el.setSelectionRange(start + text.length, start + text.length);
          el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
        }
        return true;
      }
      this.invalidate();
      const r = this.rangeFor(start, end);
      if (!r) return false;
      const sel = this.selectionObj();
      sel.removeAllRanges();
      sel.addRange(r);
      let ok = false;
      try {
        ok = document.execCommand('insertText', false, text);
      } catch (e) {}
      if (!ok) {
        r.deleteContents();
        r.insertNode(document.createTextNode(text));
        el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
      }
      this.invalidate();
      return true;
    }

    /** Field box clipped by every scrolling ancestor, in viewport coordinates; null when nothing is visible. */
    visibleRect() {
      const el = this.el;
      const b = el.getBoundingClientRect();
      let l = b.left;
      let t = b.top;
      let r = b.right;
      let btm = b.bottom;
      if (this.kind !== 'ce') {
        l += el.clientLeft;
        t += el.clientTop;
        r = l + el.clientWidth;
        btm = t + el.clientHeight;
      }
      for (let p = el.parentElement; p && p !== document.documentElement && p !== document.body; p = p.parentElement) {
        const cs = getComputedStyle(p);
        if (cs.overflowX === 'visible' && cs.overflowY === 'visible') continue;
        const pb = p.getBoundingClientRect();
        l = Math.max(l, pb.left);
        t = Math.max(t, pb.top);
        r = Math.min(r, pb.right);
        btm = Math.min(btm, pb.bottom);
      }
      l = Math.max(l, 0);
      t = Math.max(t, 0);
      r = Math.min(r, window.innerWidth);
      btm = Math.min(btm, window.innerHeight);
      return r - l > 1 && btm - t > 1 ? { left: l, top: t, right: r, bottom: btm, width: r - l, height: btm - t } : null;
    }
  }

  Q.Field = Field;
})();
