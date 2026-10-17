(async function () {
  'use strict';
  const Q = self.Quill;
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  };
  const debounce = (fn, ms) => {
    let t;
    return (...a) => {
      clearTimeout(t);
      t = setTimeout(() => fn(...a), ms);
    };
  };

  const ICON = {
    ok: '<svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
    bad: '<svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" d="M6 6l12 12M18 6L6 18"/></svg>',
    x: '<svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" d="M7 7l10 10M17 7L7 17"/></svg>',
  };
  function heading(icon, text) {
    const h = el('h3');
    const i = el('span', 'ico');
    i.innerHTML = icon;
    h.append(i, document.createTextNode(text));
    return h;
  }

  let S = await Q.getSettings();

  let toastTimer;
  function flash(text) {
    const t = $('toast');
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (t.hidden = true), 1500);
  }

  async function set(patch, quiet) {
    await chrome.storage.local.set(patch);
    Object.assign(S, patch);
    if (!quiet) flash('Saved');
  }

  // ------------------------------------------------------- generic bindings

  const unitOf = (k) => (document.querySelector(`.val[data-for="${k}"]`) || {}).dataset?.unit || '';
  const showVal = (k, v) => {
    const n = document.querySelector(`.val[data-for="${k}"]`);
    if (n) n.textContent = Number(v).toLocaleString() + unitOf(k);
  };

  document.querySelectorAll('[data-key]').forEach((input) => {
    const k = input.dataset.key;
    const v = S[k];
    if (input.type === 'checkbox') input.checked = !!v;
    else input.value = v == null ? '' : v;
    showVal(k, v);
    if (input.type === 'range') input.addEventListener('input', () => showVal(k, input.value));
    input.addEventListener('change', () => {
      let val;
      if (input.type === 'checkbox') val = input.checked;
      else if (input.type === 'range' || input.type === 'number') val = Number(input.value);
      else val = input.value.trim();
      if (k === 'model' && !val) {
        input.value = Q.DEFAULT_MODEL;
        val = Q.DEFAULT_MODEL;
      }
      if (k === 'keepAlive' && !val) val = Q.DEFAULTS.keepAlive;
      set({ [k]: val });
    });
  });

  document.querySelectorAll('[data-cat]').forEach((input) => {
    input.checked = S.categories[input.dataset.cat] !== false;
    input.addEventListener('change', () => set({ categories: Object.assign({}, S.categories, { [input.dataset.cat]: input.checked }) }));
  });

  // ------------------------------------------------------------- connection

  $('url').value = S.url;
  $('resetUrl').addEventListener('click', () => ($('url').value = Q.DEFAULT_URL));

  const LOCAL = /^(localhost|127\.0\.0\.1|\[::1\])$/;

  function hints(err, url) {
    const host = new URL(url).host;
    const code = err.code;
    if (code === 'network')
      return [
        'Make sure Ollama is running. Start it with <code>ollama serve</code>, or open the Ollama app.',
        `Check the address and port. <code>${host}</code> should open Ollama's status page in a browser tab.`,
        'If Ollama is on another computer, start it with <code>OLLAMA_HOST=0.0.0.0</code> and allow port 11434 through its firewall.',
      ];
    if (code === 'forbidden')
      return [
        'Ollama rejected the browser origin. Quill normally strips it, so set <code>OLLAMA_ORIGINS=chrome-extension://*</code> on the server and restart Ollama (see the box below).',
        'If a reverse proxy sits in front of Ollama, make sure it forwards requests without adding its own CORS rules.',
      ];
    if (code === 'permission') return ['Press <b>Save &amp; test</b> again and choose <b>Allow</b> when Chrome asks to let Quill reach this address.'];
    if (code === 'timeout') return ['The server answered too slowly. A large model may still be loading; try again in a moment, or raise the timeout under Advanced.'];
    if (code === 'notfound') return ['That address responded, but not like Ollama. Check the port (Ollama uses 11434) and any path prefix.'];
    return [];
  }

  function renderResult(r) {
    const box = $('result');
    box.hidden = false;
    box.textContent = '';
    box.dataset.ok = String(r.ok);
    if (!r.ok) {
      box.append(heading(ICON.bad, "Can't connect"), el('p', null, r.error.message));
      const hs = hints(r.error, r.url);
      if (hs.length) {
        const ul = el('ul');
        hs.forEach((h) => {
          const li = el('li');
          li.innerHTML = h; // static strings above only
          ul.append(li);
        });
        box.append(ul);
      }
      return;
    }
    const names = r.models.map((m) => m.name);
    box.append(heading(ICON.ok, `Connected${r.version ? ' to Ollama ' + r.version : ''}`));
    box.append(el('p', null, `${r.url} · ${names.length} model${names.length === 1 ? '' : 's'} installed`));
    const dl = $('models');
    dl.textContent = '';
    names.forEach((n) => {
      const o = el('option');
      o.value = n;
      dl.append(o);
    });
    const has = names.some((n) => n === S.model || n === S.model + ':latest');
    if (!names.length) {
      box.append(el('p', null, `No models yet. Install one with: ollama pull ${S.model}`));
    } else if (!has) {
      const p = el('p');
      p.append(el('b', null, `"${S.model}" isn't installed. `), document.createTextNode('Use one of yours, or run '), el('code', null, `ollama pull ${S.model}`), document.createTextNode(':'));
      box.append(p);
    }
    if (names.length) {
      const row = el('div', 'tags');
      r.models.slice(0, 12).forEach((m) => {
        const b = el('button', 'btn sm', m.name + (m.params ? ` · ${m.params}` : ''));
        b.addEventListener('click', () => {
          $('model').value = m.name;
          set({ model: m.name });
          renderResult(Object.assign({}, r));
        });
        if (m.name === S.model) b.classList.add('primary');
        row.append(b);
      });
      box.append(row);
    }
  }

  async function runTest(note) {
    const btn = $('saveTest');
    btn.disabled = true;
    const was = btn.textContent;
    btn.textContent = 'Testing…';
    try {
      const r = await chrome.runtime.sendMessage({ type: 'test' });
      renderResult(r || { ok: false, url: S.url, error: { code: 'error', message: 'No answer from the background worker.' } });
      if (note && r && r.ok) flash('Connected');
    } finally {
      btn.disabled = false;
      btn.textContent = was;
    }
  }

  $('saveTest').addEventListener('click', async () => {
    const url = Q.normalizeUrl($('url').value);
    $('url').value = url;
    $('permNote').textContent = '';
    // Ask for access first, while the click still counts as a user gesture.
    try {
      const pattern = Q.originPattern(url);
      if (!(await chrome.permissions.contains({ origins: [pattern] }))) {
        const ok = await chrome.permissions.request({ origins: [pattern] });
        if (!ok) $('permNote').textContent = 'Access to this address was not allowed.';
      }
    } catch (e) {}
    await set({ url }, true);
    await runTest(true);
  });
  $('url').addEventListener('keydown', (e) => e.key === 'Enter' && $('saveTest').click());
  $('refresh').addEventListener('click', () => runTest(false));
  $('shortcuts').addEventListener('click', () => chrome.tabs.create({ url: 'chrome://extensions/shortcuts' }));

  // ---------------------------------------------------------------- prompts

  const ONLY_TEXT = ' Reply with only the resulting text: no quotes, no preamble, no explanation.';

  function defaultPrompt(name) {
    const d = Q.DEFAULT_TOOLS.find((x) => x[0] === name);
    return d ? d[1] : '';
  }

  function saveTool(tool, prompt) {
    const tools = (S.tools || []).slice();
    const i = tools.findIndex((x) => x.name === tool.name && !!x.builtin === tool.builtin);
    if (tool.builtin) {
      if (!prompt.trim() || prompt.trim() === defaultPrompt(tool.name)) {
        if (i >= 0) tools.splice(i, 1);
      } else {
        const e = { name: tool.name, prompt, builtin: true };
        if (i >= 0) tools[i] = e;
        else tools.push(e);
      }
    } else if (i >= 0) tools[i] = Object.assign({}, tools[i], { prompt });
    return set({ tools });
  }

  function renderTools() {
    const list = $('toolList');
    list.textContent = '';
    Q.resolveTools(S.tools).forEach((t) => {
      const d = el('details', 'tool');
      const sum = el('summary');
      const tag = el('span', 'tag');
      const refreshTag = () => {
        const edited = t.builtin && ta.value.trim() !== defaultPrompt(t.name);
        tag.textContent = t.builtin ? (edited ? 'Edited' : 'Built-in') : 'Custom';
        tag.classList.toggle('edited', !!edited);
      };
      sum.append(el('b', null, t.name), tag);
      const body = el('div', 'body');
      const ta = el('textarea');
      ta.rows = 4;
      ta.spellcheck = false;
      ta.value = t.prompt;
      const save = debounce(() => saveTool(t, ta.value), 400);
      ta.addEventListener('input', () => {
        refreshTag();
        save();
      });
      const row = el('div', 'row');
      if (t.builtin) {
        const reset = el('button', 'btn sm', 'Reset to default');
        reset.addEventListener('click', () => {
          ta.value = defaultPrompt(t.name);
          refreshTag();
          saveTool(t, ta.value);
        });
        row.append(reset);
      } else {
        const del = el('button', 'btn sm danger', 'Delete');
        del.addEventListener('click', () => {
          if (del.dataset.sure !== '1') {
            del.dataset.sure = '1';
            del.textContent = 'Click again to delete';
            return;
          }
          set({ tools: (S.tools || []).filter((x) => x.builtin || x.name !== t.name) }).then(renderTools);
        });
        row.append(del);
      }
      body.append(ta, row);
      d.append(sum, body);
      list.append(d);
      refreshTag();
    });
  }

  $('addTool').addEventListener('click', async () => {
    const name = $('newName').value.trim();
    let prompt = $('newPrompt').value.trim();
    const note = $('addNote');
    if (!name || !prompt) return (note.textContent = 'Give the style a name and an instruction.');
    if (Q.resolveTools(S.tools).some((t) => t.name.toLowerCase() === name.toLowerCase())) return (note.textContent = 'A style with that name already exists.');
    if (!/reply with only/i.test(prompt)) prompt += ONLY_TEXT;
    await set({ tools: (S.tools || []).concat({ name, prompt, builtin: false }) });
    $('newName').value = '';
    $('newPrompt').value = '';
    note.textContent = '';
    renderTools();
  });

  const style = $('stylePrompt');
  style.value = Q.stylePrompt(S);
  style.addEventListener(
    'input',
    debounce(() => set({ stylePrompt: style.value.trim() === Q.DEFAULT_STYLE_PROMPT ? '' : style.value }), 400),
  );
  $('resetStyle').addEventListener('click', () => {
    style.value = Q.DEFAULT_STYLE_PROMPT;
    set({ stylePrompt: '' });
  });

  // ------------------------------------------------------ sites, dictionary

  function renderTags(box, items, remove, emptyText) {
    box.textContent = '';
    if (!items.length) return box.append(el('span', 'empty', emptyText));
    items.forEach((item) => {
      const t = el('span', 'tag-item');
      const x = el('button');
      x.innerHTML = ICON.x;
      x.title = 'Remove';
      x.setAttribute('aria-label', 'Remove ' + item);
      x.addEventListener('click', () => remove(item));
      t.append(document.createTextNode(item), x);
      box.append(t);
    });
  }

  function renderSites() {
    renderTags($('siteList'), S.disabledSites, (s) => set({ disabledSites: S.disabledSites.filter((x) => x !== s) }).then(renderSites), 'No sites yet. Quill is on everywhere.');
  }
  function renderWords() {
    renderTags($('wordList'), S.dictionary.slice().sort(), (w) => set({ dictionary: S.dictionary.filter((x) => x !== w) }).then(renderWords), 'Your dictionary is empty.');
  }

  $('addSite').addEventListener('click', async () => {
    const v = $('siteInput').value.trim().toLowerCase().replace(/^[a-z]+:\/\//, '').replace(/^www\./, '').replace(/[/?#:].*$/, '');
    if (!v) return;
    if (!S.disabledSites.includes(v)) await set({ disabledSites: S.disabledSites.concat(v) });
    $('siteInput').value = '';
    renderSites();
  });
  $('siteInput').addEventListener('keydown', (e) => e.key === 'Enter' && $('addSite').click());

  $('addWord').addEventListener('click', async () => {
    const v = $('wordInput').value.trim().toLowerCase();
    if (!v) return;
    if (!S.dictionary.includes(v)) await set({ dictionary: S.dictionary.concat(v) });
    $('wordInput').value = '';
    renderWords();
  });
  $('wordInput').addEventListener('keydown', (e) => e.key === 'Enter' && $('addWord').click());

  // ------------------------------------------------------------------ about

  $('ver').textContent = 'v' + chrome.runtime.getManifest().version;
  const showAccepted = () => ($('accepted').textContent = (S.accepted || 0).toLocaleString());

  $('export').addEventListener('click', async () => {
    const data = await chrome.storage.local.get(null);
    const a = el('a');
    a.href = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
    a.download = 'quill-settings.json';
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  });

  $('import').addEventListener('change', async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    try {
      const obj = JSON.parse(await file.text());
      const clean = {};
      Object.keys(Q.DEFAULTS).forEach((k) => k in obj && (clean[k] = obj[k]));
      await chrome.storage.local.set(clean);
      location.reload();
    } catch (err) {
      flash("That file isn't a Quill settings export.");
    }
  });

  $('reset').addEventListener('click', async (e) => {
    const b = e.target;
    if (b.dataset.sure !== '1') {
      b.dataset.sure = '1';
      b.textContent = 'Click again to erase all settings';
      return;
    }
    await chrome.storage.local.clear();
    location.reload();
  });

  chrome.storage.onChanged.addListener(async () => {
    S = await Q.getSettings();
    renderSites();
    renderWords();
    showAccepted();
  });

  // --------------------------------------------------------------- chrome

  const links = Array.from(document.querySelectorAll('nav a'));
  const io = new IntersectionObserver(
    (entries) => {
      entries.forEach((en) => {
        if (en.isIntersecting) links.forEach((a) => a.classList.toggle('on', a.hash === '#' + en.target.id));
      });
    },
    { rootMargin: '-20% 0px -70% 0px' },
  );
  document.querySelectorAll('main section[id]').forEach((s) => io.observe(s));

  if (/welcome=1/.test(location.search)) $('welcome').hidden = false;

  renderTools();
  renderSites();
  renderWords();
  showAccepted();
  runTest(false);
})();
