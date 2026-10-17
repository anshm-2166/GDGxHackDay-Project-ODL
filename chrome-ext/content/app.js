/*
 * Quill content-script controller: tracks the focused text field, checks it as you type,
 * and wires the View's actions (accept, dismiss, rewrite, tone ...) to the model.
 */
(function () {
  'use strict';
  const Q = self.Quill;
  if (window.__quillLoaded) return;
  window.__quillLoaded = true;

  // ------------------------------------------------------------------ state

  let S = Q.DEFAULTS;
  let dictSet = new Set();
  let sig = '';
  let paused = false;
  let dead = false;
  let view = null;
  let active = null;
  let mouseDown = false;
  const states = new WeakMap();

  const wordCount = (s) => (s.match(/\S+/g) || []).length;
  const trunc = (s, n) => (s.length > n ? s.slice(0, n) : s);

  const h32 = (s) => {
    let h = 2166136261;
    for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
    return (h >>> 0).toString(36);
  };

  // Chunk results by (kind, settings, text): editing one paragraph only re-checks that paragraph.
  const cache = new Map();
  const cacheGet = (k) => {
    const v = cache.get(k);
    if (v) {
      cache.delete(k);
      cache.set(k, v);
    }
    return v;
  };
  const cachePut = (k, v) => {
    cache.set(k, v);
    if (cache.size > 400) cache.delete(cache.keys().next().value);
  };
  const inflight = new Map();

  class FieldState {
    constructor(el) {
      this.field = new Q.Field(el);
      this.text = '';
      this.lastText = null;
      this.all = [];
      this.status = 'idle';
      this.error = null;
      this.gen = 0;
      this.keys = new Set();
      this.dismissed = new Set();
      this.timer = 0;
      this.composing = false;
      this.lastSel = null;
      this.mo = null;
      this.rw = { tool: null, instruction: '', scope: null, result: '', running: false, error: null, tps: null, cancel: null };
      this.tone = { running: false, result: '', error: null };
    }

    /** Suggestions the user should see: category on, not dismissed, not in the dictionary. */
    get issues() {
      return this.all.filter((i) => {
        if (S.categories[i.category] === false || this.dismissed.has(Q.issueKey(i))) return false;
        const w = i.original.toLowerCase().replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, '');
        return !dictSet.has(w);
      });
    }
  }

  // --------------------------------------------------------------- settings

  function info() {
    let host = '';
    try {
      host = new URL(S.url).host;
    } catch (e) {}
    return { model: S.model, host };
  }

  async function loadSettings() {
    S = await Q.getSettings();
    dictSet = new Set(S.dictionary.map((w) => String(w).toLowerCase()));
    sig = h32([S.model, S.temperature, Q.toolPrompt(S, Q.FIX), Q.stylePrompt(S)].join('|'));
    paused = !S.enabled || Q.siteDisabled(S, location.hostname);
    if (view) view.setSettings(S, info());
  }

  chrome.storage.onChanged.addListener(async (changes, area) => {
    if (area !== 'local' || dead) return;
    const keys = Object.keys(changes);
    if (keys.every((k) => k === 'accepted')) return;
    await loadSettings();
    if (!active) return;
    if (paused) return deactivate();
    // Anything that changes what the model says means checking again.
    if (keys.some((k) => ['model', 'temperature', 'style', 'stylePrompt', 'tools', 'maxChars', 'url', 'checkAsYouType'].includes(k))) {
      active.lastText = null;
      scheduleCheck(active, true);
    }
    view.update();
  });

  // ------------------------------------------------------------- activation

  function ensureView() {
    if (view) return view;
    view = new Q.View({
      onAccept: (i) => accept(active, i),
      onDismiss: (i) => dismiss(active, i),
      onAddWord: (i) => addWord(active, i),
      onExplain: (i) => explain(active, i),
      onAcceptAll: () => acceptAll(active),
      onSettings: () => chrome.runtime.sendMessage({ type: 'openOptions' }),
      onCheckNow: () => active && scheduleCheck(active, true, true),
      onTool: (name) => active && runTool(active, name),
      onInstruction: (text) => active && runTool(active, null, text),
      onApply: () => active && applyResult(active),
      onCopy: () => active && copy(active.rw.result),
      onRetry: () => active && runTool(active, active.rw.tool, active.rw.instruction),
      onStop: () => active && active.rw.cancel && active.rw.cancel(),
      onTone: () => active && detectTone(active),
      onSelectionTool: (tool) => {
        if (!active) return;
        view.openPanel('rewrite');
        if (tool) runTool(active, tool);
      },
      onPanel: () => {},
      scopeInfo: () => {
        const sc = active ? scopeOf(active) : { sel: false, text: '' };
        return { sel: sc.sel, text: sc.text, words: wordCount(sc.text) };
      },
      onTick: tick,
      onLost: () => deactivate(),
    });
    view.build();
    view.setSettings(S, info());
    return view;
  }

  function activate(el) {
    if (active && active.field.el === el) return;
    deactivate();
    let fs = states.get(el);
    if (!fs) states.set(el, (fs = new FieldState(el)));
    active = fs;
    if (fs.field.kind === 'ce') {
      fs.mo = new MutationObserver(() => textMaybeChanged(fs));
      fs.mo.observe(el, { childList: true, subtree: true, characterData: true });
    }
    ensureView().setField(fs);
    fs.field.invalidate();
    fs.text = fs.field.getText();
    fs.all = Q.locate(fs.text, fs.all);
    if (fs.text !== fs.lastText) scheduleCheck(fs);
    else view.update();
  }

  function deactivate() {
    if (!active) return;
    const fs = active;
    active = null;
    clearTimeout(fs.timer);
    if (fs.mo) fs.mo.disconnect();
    fs.mo = null;
    if (view) view.clearField();
  }

  function teardown() {
    dead = true;
    deactivate();
    if (view) view.destroy();
  }

  /** The element that really has focus, looking through shadow roots. */
  function deepActive() {
    let a = document.activeElement;
    while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement;
    return a;
  }

  const origin = (e) => (e.composedPath ? e.composedPath()[0] : e.target);

  /**
   * Makes the field an event came from the active one. Focus alone is not enough: autofocused boxes,
   * script-driven focus and some editors never fire a usable focusin, but a click, key or input always will.
   */
  function adopt(e, leave) {
    if (dead) return null;
    if (Q.orphaned()) return void teardown();
    if (paused) return null;
    const path = e.composedPath ? e.composedPath() : [e.target];
    if (view && view.owns(path)) return null; // events inside Quill's own panel
    let el = null;
    for (const n of path) {
      if ((el = Q.editableFor(n))) break;
    }
    if (!el) return null;
    if (Q.eligible(el)) {
      activate(el);
      return el;
    }
    if (leave && active && active.field.el !== el) deactivate();
    return null;
  }

  document.addEventListener('focusin', (e) => adopt(e, true), true);
  document.addEventListener('pointerdown', (e) => adopt(e, false), true);
  document.addEventListener('keydown', (e) => e.key.length === 1 && adopt(e, false), true);

  document.addEventListener(
    'focusout',
    (e) => {
      if (dead || !active || origin(e) !== active.field.el) return;
      setTimeout(() => {
        if (!active) return;
        const a = deepActive();
        if (a === active.field.el || (view && view.host === document.activeElement)) return;
        if (view.panelOpen) return; // closed by an outside click instead
        deactivate();
      }, 80);
    },
    true,
  );

  document.addEventListener(
    'mousedown',
    (e) => {
      mouseDown = true;
      if (dead || !active) return;
      const path = e.composedPath();
      if (view.owns(path) || path.indexOf(active.field.el) >= 0) return;
      view.closePanel();
      view.hideCard();
      view.hideSelbar();
    },
    true,
  );
  document.addEventListener(
    'mouseup',
    () => {
      mouseDown = false;
      captureSelection();
    },
    true,
  );
  document.addEventListener('keyup', () => captureSelection(), true);
  document.addEventListener('select', () => captureSelection(), true);
  document.addEventListener('selectionchange', () => captureSelection(), true);

  document.addEventListener(
    'keydown',
    (e) => {
      if (dead || !active || e.key !== 'Escape') return;
      if (view.panelOpen) view.closePanel();
      else if (view.cardIssue) view.hideCard();
      else view.hideSelbar();
    },
    true,
  );

  document.addEventListener(
    'input',
    (e) => {
      if (dead) return;
      const el = adopt(e, false);
      if (el && active && el === active.field.el) textMaybeChanged(active);
    },
    true,
  );
  document.addEventListener('compositionstart', () => active && (active.composing = true), true);
  document.addEventListener(
    'compositionend',
    () => {
      if (!active) return;
      active.composing = false;
      textMaybeChanged(active);
    },
    true,
  );

  // ----------------------------------------------------------------- typing

  /** Frame-by-frame guard for fields that change without firing events (React clearing a box, say). */
  function tick() {
    if (Q.orphaned()) return teardown();
    const fs = active;
    if (fs && fs.field.kind !== 'ce' && fs.field.el.value !== fs.text) textMaybeChanged(fs);
  }

  function textMaybeChanged(fs) {
    fs.field.invalidate();
    const text = fs.field.getText();
    if (text === fs.text) return;
    fs.text = text;
    fs.lastSel = null;
    fs.all = Q.locate(text, fs.all); // keep underlines glued to their words while typing
    if (active === fs) view.update();
    if (fs.composing || text === fs.lastText) return;
    scheduleCheck(fs);
  }

  let selFrame = 0;
  function captureSelection() {
    if (dead || !active || selFrame) return;
    selFrame = requestAnimationFrame(() => {
      selFrame = 0;
      const fs = active;
      if (!fs) return;
      const sel = fs.field.getSelection();
      if (sel) fs.lastSel = sel.end > sel.start ? sel : null;
      const len = fs.lastSel ? fs.lastSel.end - fs.lastSel.start : 0;
      if (len >= 3 && !mouseDown && S.selectionBar && !view.panelOpen) view.showSelbar();
      else view.hideSelbar();
    });
  }

  // ----------------------------------------------------------------- checks

  function cancelStale(fs, want) {
    fs.keys.forEach((k) => {
      if (!want.has(k) && inflight.has(k)) inflight.get(k).cancel();
    });
    fs.keys = want;
  }

  function scheduleCheck(fs, now, force) {
    clearTimeout(fs.timer);
    if (!S.checkAsYouType && !now) {
      if (fs.status !== 'checking') fs.status = fs.all.length ? 'issues' : 'idle';
      if (active === fs) view.update();
      return;
    }
    fs.timer = setTimeout(() => runCheck(fs, force), now ? 0 : S.delayMs);
  }

  function parseFix(text, out) {
    const fixed = Q.clean(out, text);
    if (!fixed) return [];
    const issues = Q.diff(text, fixed).filter((i) => i.original.indexOf('\n') < 0);
    // A model that rewrote the whole thing isn't proofreading; don't paint every word.
    const changed = issues.reduce((t, i) => t + wordCount(i.original), 0);
    if (wordCount(text) >= 6 && changed > wordCount(text) * 0.6) return [];
    // Hindi, Hinglish and other informal text is correct as written: the model is only trusted on plain English typos.
    if (Q.isIndicOrHinglish(text)) {
      return issues.filter((i) => i.type === 'Spelling' && /^[A-Za-z]+$/.test(i.original) && !Q.hinglishWord(i.original));
    }
    return issues;
  }

  function parseStyle(text, out) {
    const a = out.indexOf('{');
    const b = out.lastIndexOf('}');
    if (a < 0 || b < a) return [];
    let arr;
    try {
      arr = JSON.parse(out.slice(a, b + 1)).suggestions;
    } catch (e) {
      return [];
    }
    if (!Array.isArray(arr)) return [];
    const list = arr
      .map((o) => ({
        type: String(o.label || 'Suggestion'),
        original: String(o.original || ''),
        replacement: String(o.replacement || ''),
        start: -1,
        category: Q.CATEGORIES.indexOf(o.category) > 0 ? o.category : 'clarity',
      }))
      .filter((i) => i.original && i.original !== i.replacement);
    return Q.locate(text, list);
  }

  /** One model call for one chunk, shared between overlapping checks and cached when it lands. */
  function compute(kind, text, key) {
    const hit = inflight.get(key);
    if (hit) return hit;
    const ask =
      kind === 'fix'
        ? Q.ask({ kind: 'fix', system: Q.toolPrompt(S, Q.FIX) + Q.GUARD, user: Q.wrap(text), prio: 0 })
        : Q.ask({ kind: 'style', system: Q.stylePrompt(S) + Q.GUARD, user: Q.wrap(text), format: Q.STYLE_SCHEMA, prio: 1 });
    const promise = ask.promise.then((res) => {
      const list = kind === 'fix' ? parseFix(text, res.text) : parseStyle(text, res.text);
      cachePut(key, list);
      return list;
    });
    const h = { promise, cancel: ask.cancel };
    inflight.set(key, h);
    const drop = () => inflight.delete(key);
    promise.then(drop, drop);
    return h;
  }

  function runCheck(fs, force) {
    if (dead) return;
    if (Q.orphaned()) return teardown();
    fs.field.invalidate();
    const text = fs.field.getText();
    fs.text = text;
    if (text === fs.lastText && !force) return;
    fs.lastText = text;
    const gen = ++fs.gen;
    fs.error = null;

    const chunks = Q.chunk(text.slice(0, S.maxChars), 900).filter((c) => /\p{L}/u.test(c.text) && wordCount(c.text) >= 2);
    fs.all = Q.locate(text, fs.all);
    if (!chunks.length) {
      cancelStale(fs, new Set());
      fs.all = [];
      fs.status = 'idle';
      if (active === fs) view.update();
      return;
    }

    const prev = fs.all;
    const parts = chunks.map((c) => ({ c, fix: null, style: S.style && wordCount(c.text) >= 4 && !Q.isIndicOrHinglish(c.text) ? null : [] }));
    const want = new Set();
    let pending = 0;

    const settle = () => {
      if (fs.gen !== gen) return;
      const fresh = [];
      const stale = [];
      parts.forEach((p) => {
        [p.fix, p.style].forEach((list) => list && list.forEach((i) => fresh.push(Object.assign({}, i, { start: i.start + p.c.start }))));
        if (p.fix === null || p.style === null) prev.forEach((i) => i.start >= p.c.start && i.start < p.c.end && stale.push(i));
      });
      fs.all = Q.merge(fresh.concat(stale));
      fs.status = pending > 0 ? 'checking' : fs.error && !fs.all.length ? 'error' : 'ok';
      if (active === fs) view.update();
    };

    parts.forEach((p) => {
      [['fix', 'fix'], ['style', 'style']].forEach(([kind, field]) => {
        if (p[field] !== null) return;
        const key = kind + '|' + sig + '|' + p.c.text;
        want.add(key);
        const hit = cacheGet(key);
        if (hit) return void (p[field] = hit);
        pending++;
        compute(kind, p.c.text, key).promise.then(
          (list) => (p[field] = list),
          (err) => {
            p[field] = [];
            if (err && err.code === 'orphaned') return teardown();
            if (err && err.code !== 'aborted') fs.error = fs.error || err;
          },
        ).then(() => {
          pending--;
          settle();
        });
      });
    });
    cancelStale(fs, want);
    settle();
  }

  // ---------------------------------------------------------------- actions

  function afterEdit(fs, remaining) {
    clearTimeout(fs.timer);
    fs.field.invalidate();
    const text = fs.field.getText();
    fs.text = text;
    fs.lastText = text; // our own edit: nothing new to check
    fs.lastSel = null;
    fs.all = Q.locate(text, remaining);
    fs.status = fs.all.length ? 'issues' : 'ok';
    if (active === fs) view.update();
  }

  function applyIssues(fs, list) {
    fs.field.invalidate();
    let text = fs.field.getText();
    const located = Q.locate(text, list).sort((a, b) => b.start - a.start); // back to front keeps offsets valid
    if (!located.length) return 0;
    let done = 0;
    located.forEach((i) => {
      if (!text.startsWith(i.original, i.start)) return;
      let end = i.start + i.original.length;
      if (!i.replacement) while (text[end] === ' ') end++;
      if (fs.field.replace(i.start, end, i.replacement)) {
        text = text.slice(0, i.start) + i.replacement + text.slice(end);
        done++;
      }
    });
    const gone = new Set(located.map((i) => Q.issueKey(i)));
    afterEdit(fs, fs.all.filter((i) => !gone.has(Q.issueKey(i))));
    return done;
  }

  function bump(n) {
    if (n > 0) chrome.storage.local.set({ accepted: (S.accepted || 0) + n }).catch(() => {});
  }

  function accept(fs, issue) {
    if (!fs) return;
    view.hideCard();
    bump(applyIssues(fs, [issue]));
  }

  function acceptAll(fs) {
    if (!fs) return;
    const list = fs.issues;
    if (!list.length) return view.toast('Nothing to accept');
    view.hideCard();
    const n = applyIssues(fs, list);
    bump(n);
    view.toast(`Accepted ${n} suggestion${n === 1 ? '' : 's'}`);
  }

  function dismiss(fs, issue) {
    if (!fs) return;
    fs.dismissed.add(Q.issueKey(issue));
    view.hideCard();
    view.update();
  }

  function addWord(fs, issue) {
    if (!fs) return;
    const w = issue.original.toLowerCase().replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, '');
    if (!w) return;
    dictSet.add(w);
    chrome.storage.local.set({ dictionary: Array.from(new Set(S.dictionary.concat(w))) }).catch(() => {});
    view.hideCard();
    view.update();
    view.toast('Added to your dictionary');
  }

  function explain(fs, issue) {
    const at = Math.max(0, issue.start - 140);
    const around = fs.text.slice(at, issue.start + issue.original.length + 140);
    const ask = Q.ask({
      kind: 'explain',
      system: Q.EXPLAIN_PROMPT + ' Never use emoji.',
      user: `Text: ${around}\nSnippet: ${issue.original}\nReplacement: ${issue.replacement || '(remove it)'}`,
      prio: 0,
    });
    return { promise: ask.promise.then((r) => Q.clean(r.text)), cancel: ask.cancel };
  }

  // ------------------------------------------------------- rewrite and tone

  /** What a rewrite applies to: the selection if there is one, else the whole field. */
  function scopeOf(fs) {
    const text = fs.field.getText();
    const s = fs.lastSel;
    if (s && s.end > s.start && s.end <= text.length && text.slice(s.start, s.end).trim()) {
      return { start: s.start, end: s.end, sel: true, text: text.slice(s.start, s.end) };
    }
    return { start: 0, end: text.length, sel: false, text };
  }

  let rwFrame = 0;
  const refresh = (fs) => {
    if (rwFrame) return;
    rwFrame = requestAnimationFrame(() => {
      rwFrame = 0;
      if (active === fs) view.update();
    });
  };

  function runTool(fs, name, instruction) {
    const rw = fs.rw;
    if (rw.cancel) rw.cancel();
    const scope = scopeOf(fs);
    Object.assign(rw, { tool: name, instruction: instruction || '', scope, result: '', running: !!scope.text.trim(), error: null, tps: null, cancel: null });
    if (!view.panelOpen) view.openPanel('rewrite');
    else if (view.tab !== 'rewrite') view.setTab('rewrite');
    if (!scope.text.trim()) {
      rw.error = { message: 'Write something first, then pick a style.' };
      return view.update();
    }
    const compose = name === 'Compose' && !instruction; // its text IS a request, so no guard
    const system = instruction ? Q.CUSTOM_PROMPT + Q.GUARD : Q.toolPrompt(S, name) + (compose ? '' : Q.GUARD);
    const user = instruction ? `Instruction: ${instruction}\n\n${Q.wrap(scope.text)}` : compose ? scope.text : Q.wrap(scope.text);
    let acc = '';
    const run = Q.ask({ kind: 'rewrite', system, user, stream: true, prio: 0 }, (d) => {
      acc += d;
      rw.result = Q.cleanStream(acc).replace(/^\s+/, '');
      refresh(fs);
    });
    rw.cancel = run.cancel;
    run.promise
      .then((res) => {
        rw.result = Q.clean(res.text, scope.text);
        rw.tps = res.tps;
      })
      .catch((e) => {
        if (e && e.code === 'orphaned') return teardown();
        if (e && e.code !== 'aborted') rw.error = e;
        else rw.result = Q.clean(acc, scope.text);
      })
      .finally(() => {
        rw.running = false;
        rw.cancel = null;
        if (active === fs) view.update();
      });
    view.update();
  }

  function applyResult(fs) {
    const rw = fs.rw;
    if (!rw.result || !rw.scope) return;
    const cur = fs.field.getText();
    let { start, end } = rw.scope;
    if (cur.slice(start, end) !== rw.scope.text) {
      const at = cur.indexOf(rw.scope.text);
      if (at < 0) return view.toast('The text changed. Run it again.');
      start = at;
      end = at + rw.scope.text.length;
    }
    fs.field.replace(start, end, rw.result);
    Object.assign(rw, { tool: null, instruction: '', result: '', scope: null });
    view.toast('Replaced');
    textMaybeChanged(fs);
    view.update();
  }

  function detectTone(fs) {
    const t = fs.tone;
    const sc = scopeOf(fs);
    if (!sc.text.trim()) {
      t.error = { message: 'Write something first.' };
      return view.update();
    }
    Object.assign(t, { running: true, result: '', error: null });
    const run = Q.ask({ kind: 'rewrite', system: Q.toolPrompt(S, Q.TONE) + Q.GUARD, user: Q.wrap(sc.text), prio: 0 });
    run.promise
      .then((r) => (t.result = Q.clean(r.text)))
      .catch((e) => {
        if (e && e.code !== 'aborted') t.error = e;
      })
      .finally(() => {
        t.running = false;
        if (active === fs) view.update();
      });
    view.update();
  }

  function copy(text) {
    const done = () => view.toast('Copied');
    const fallback = () => {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0';
      document.documentElement.appendChild(ta);
      ta.select();
      try {
        document.execCommand('copy');
        done();
      } catch (e) {}
      ta.remove();
      if (active) active.field.focus();
    };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, fallback);
    else fallback();
  }

  // --------------------------------------------------- shortcuts, context menu

  chrome.runtime.onMessage.addListener((msg) => {
    if (dead || !active || !msg) return;
    if (msg.type === 'command') {
      if (msg.name === 'toggle-panel') view.togglePanel();
      else if (msg.name === 'accept-all') acceptAll(active);
    } else if (msg.type === 'ctx') {
      if (msg.action === 'panel') view.openPanel('fix');
      else if (msg.action === 'accept') acceptAll(active);
      else if (msg.action === 'tool') runTool(active, msg.tool);
    }
  });

  // ------------------------------------------------------------------- boot

  loadSettings().then(() => {
    if (dead || paused) return;
    const el = Q.editableFor(deepActive());
    if (el && Q.eligible(el)) activate(el);
  });

  // Exposed for the in-page test harness only.
  Q._test = { get active() { return active; }, get view() { return view; }, runCheck, scheduleCheck, runTool, accept, acceptAll };
})();
