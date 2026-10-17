/*
 * Quill service worker.
 *
 * Every request to Ollama is made from here, never from a web page, so:
 *  - page CSP / mixed-content rules (https site -> http://localhost) never get in the way, and
 *  - CORS is handled in one place. Extension workers with host permission are not subject to
 *    CORS checks, but Ollama still rejects unknown `Origin` headers (403). A declarativeNetRequest
 *    rule removes the Origin header from our own requests to the configured server, so no
 *    OLLAMA_ORIGINS setup is needed on the server.
 */
importScripts('lib/shared.js');
const Q = self.Quill;

// ------------------------------------------------------------------ settings

let settingsCache = null;
const settings = async () => settingsCache || (settingsCache = await Q.getSettings());

// Resolves once the Origin rule matches the current address; every request waits for it.
let ruleReady = Promise.resolve();

chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== 'local') return;
  settingsCache = null;
  if (changes.url) ruleReady = syncOriginRule();
  if (changes.tools) buildMenus();
  if (changes.enabled) updateIcon();
});

async function syncOriginRule() {
  const s = await settings();
  let host = null;
  try {
    host = new URL(s.url).hostname.replace(/^\[|\]$/g, '');
  } catch (e) {}
  const addRules = host
    ? [
        {
          id: 1,
          priority: 1,
          action: { type: 'modifyHeaders', requestHeaders: [{ header: 'Origin', operation: 'remove' }] },
          condition: {
            requestDomains: [host],
            initiatorDomains: [chrome.runtime.id],
            resourceTypes: ['xmlhttprequest', 'other'],
          },
        },
      ]
    : [];
  await chrome.declarativeNetRequest.updateDynamicRules({ removeRuleIds: [1], addRules });
}

// -------------------------------------------------------------------- Ollama

class QErr extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}
const serialize = (e) => ({ code: e.code || 'error', message: e.message || String(e) });

async function needPermission(base) {
  const u = new URL(base);
  const origin = u.protocol + '//' + u.hostname + '/*';
  if (await chrome.permissions.contains({ origins: [origin] })) return;
  throw new QErr('permission', `Quill needs your permission to reach ${u.host}. Open Quill settings and press "Save & test".`);
}

/** Turns a failed HTTP response or fetch into an error the UI can explain. */
async function httpError(res, base, model) {
  let msg = '';
  try {
    const t = await res.text();
    try {
      msg = JSON.parse(t).error || t;
    } catch (e) {
      msg = t;
    }
  } catch (e) {}
  msg = String(msg || '').slice(0, 300);
  if (res.status === 403) {
    return new QErr('forbidden', 'Ollama refused the request (403). Quill removes the Origin header itself, so if this persists set OLLAMA_ORIGINS=chrome-extension://* and restart Ollama.');
  }
  if (res.status === 404 && /model/i.test(msg)) {
    return new QErr('model', `Model "${model}" isn't installed on that server. Run: ollama pull ${model}`);
  }
  if (res.status === 404) return new QErr('notfound', `${base} answered 404, so it doesn't look like an Ollama server.`);
  return new QErr('http', msg || `Ollama answered HTTP ${res.status}`);
}

/** fetch() with an idle timeout and caller-driven abort, mapped to friendly errors. */
async function request(path, { method = 'GET', body, signal, timeout } = {}, onResponse) {
  await ruleReady;
  const s = await settings();
  const base = s.url;
  await needPermission(base);
  const ctl = new AbortController();
  const abort = () => ctl.abort('aborted');
  if (signal) signal.aborted ? abort() : signal.addEventListener('abort', abort, { once: true });
  const ms = timeout || s.timeoutSec * 1000;
  let timer = setTimeout(() => ctl.abort('timeout'), ms);
  const touch = () => {
    clearTimeout(timer);
    timer = setTimeout(() => ctl.abort('timeout'), ms);
  };
  try {
    const res = await fetch(base + path, {
      method,
      body: body ? JSON.stringify(body) : undefined,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      signal: ctl.signal,
      cache: 'no-store',
    });
    if (!res.ok) throw await httpError(res, base, body && body.model);
    return await onResponse(res, touch);
  } catch (e) {
    if (e instanceof QErr) throw e;
    if (ctl.signal.aborted) {
      throw ctl.signal.reason === 'timeout'
        ? new QErr('timeout', `Ollama didn't answer within ${Math.round(ms / 1000)} seconds. A big model may still be loading; try again.`)
        : new QErr('aborted', 'Cancelled');
    }
    throw new QErr('network', `Can't reach Ollama at ${base}. Is it running?`);
  } finally {
    clearTimeout(timer);
  }
}

async function chatOnce(job, s, opts, signal, onDelta) {
  const rewrite = job.kind === 'rewrite' || job.kind === 'explain';
  const model = rewrite ? s.rewriteModel || s.model : s.model;
  const temperature = job.temperature != null ? job.temperature : rewrite ? s.rewriteTemperature : s.temperature;
  let system = job.system;
  if (opts.inlineSchema && job.format) {
    system += '\n\nReply with only one JSON object matching this schema, nothing else: ' + JSON.stringify(job.format);
  }
  const body = {
    model,
    stream: true,
    messages: [
      { role: 'system', content: system },
      { role: 'user', content: job.user },
    ],
    options: { temperature },
    keep_alive: s.keepAlive || '15m',
  };
  if (opts.think === false) body.think = false;
  if (opts.format) body.format = job.format;

  return request('/api/chat', { method: 'POST', body, signal }, async (res, touch) => {
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    let text = '';
    let last = null;
    const line = (l) => {
      l = l.trim();
      if (!l) return;
      let o;
      try {
        o = JSON.parse(l);
      } catch (e) {
        return;
      }
      if (o.error) throw new QErr('http', o.error);
      const piece = o.message && o.message.content;
      if (piece) {
        text += piece;
        if (onDelta) onDelta(piece);
      }
      if (o.done) last = o;
    };
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      touch();
      buf += dec.decode(value, { stream: true });
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        line(buf.slice(0, i));
        buf = buf.slice(i + 1);
      }
    }
    line(buf);
    const tps = last && last.eval_count && last.eval_duration ? (last.eval_count * 1e9) / last.eval_duration : null;
    return { text, tps };
  });
}

/** Chat with graceful fallback for older servers or models that reject `think` or `format`. */
async function chat(job, signal, onDelta) {
  const s = await settings();
  const opts = { think: false, format: job.format || null, inlineSchema: false };
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      return await chatOnce(job, s, opts, signal, onDelta);
    } catch (e) {
      const m = String(e.message).toLowerCase();
      if (e.code === 'http' && opts.think === false && m.includes('think')) opts.think = null;
      else if (e.code === 'http' && opts.format && m.includes('format')) {
        opts.format = null;
        opts.inlineSchema = true;
      } else throw e;
    }
  }
  throw new QErr('http', 'Ollama rejected the request');
}

const json = (res) => res.json();

async function diagnose() {
  settingsCache = null;
  ruleReady = syncOriginRule();
  await ruleReady;
  const s = await settings();
  try {
    const version = await request('/api/version', { timeout: 6000 }, json).then((v) => v.version).catch((e) => {
      if (e.code === 'notfound') throw e;
      return null;
    });
    const tags = await request('/api/tags', { timeout: 8000 }, json);
    const models = (tags.models || []).map((m) => ({
      name: m.name,
      size: m.size,
      params: m.details && m.details.parameter_size,
    }));
    // First run: if the default model isn't installed and the user never picked one, adopt an installed one.
    const have = (n) => models.some((m) => m.name === n || m.name === n + ':latest');
    if (models.length && !have(s.model) && !('model' in (await chrome.storage.local.get('model')))) {
      const preferred = models.find((m) => m.name.startsWith('gemma')) || models[0];
      await chrome.storage.local.set({ model: preferred.name });
      settingsCache = null;
      return { ok: true, url: s.url, version, models, model: preferred.name, autoPicked: true };
    }
    return { ok: true, url: s.url, version, models, model: s.model };
  } catch (e) {
    return { ok: false, url: s.url, error: serialize(e) };
  }
}

// ----------------------------------------------------------- job queue (port)

const queue = [];
let active = 0;
let seq = 0;

async function pump() {
  const s = await settings();
  while (active < s.concurrency && queue.length) {
    queue.sort((a, b) => a.prio - b.prio || a.seq - b.seq);
    const job = queue.shift();
    if (job.ctl.signal.aborted) {
      job.fail();
      continue;
    }
    active++;
    job.run().finally(() => {
      active--;
      pump();
    });
  }
}

chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== 'quill') return;
  const jobs = new Map();
  const post = (m) => {
    try {
      port.postMessage(m);
    } catch (e) {}
  };
  port.onMessage.addListener((msg) => {
    if (msg.cancel) {
      const j = jobs.get(msg.id);
      if (j) j.ctl.abort('aborted');
      return;
    }
    const ctl = new AbortController();
    const job = {
      ctl,
      prio: msg.prio == null ? 1 : msg.prio,
      seq: ++seq,
      fail: () => {
        jobs.delete(msg.id);
        post({ id: msg.id, error: { code: 'aborted', message: 'Cancelled' } });
      },
      run: async () => {
        try {
          const r = await chat(msg, ctl.signal, msg.stream ? (d) => post({ id: msg.id, delta: d }) : null);
          post({ id: msg.id, ok: true, text: r.text, tps: r.tps });
        } catch (e) {
          post({ id: msg.id, error: serialize(e) });
        } finally {
          jobs.delete(msg.id);
        }
      },
    };
    jobs.set(msg.id, job);
    queue.push(job);
    pump();
  });
  port.onDisconnect.addListener(() => jobs.forEach((j) => j.ctl.abort('aborted')));
});

// ------------------------------------------------------------ one-shot messages

chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  if (!msg || !msg.type) return;
  if (msg.type === 'test') {
    diagnose().then(reply);
    return true;
  }
  if (msg.type === 'openOptions') {
    chrome.runtime.openOptionsPage();
    return;
  }
});

// -------------------------------------------------------------- menus, keys

let menusBusy = Promise.resolve();
const buildMenus = () => (menusBusy = menusBusy.then(doBuildMenus, doBuildMenus));

async function doBuildMenus() {
  await chrome.contextMenus.removeAll();
  const s = await settings();
  const tools = Q.resolveTools(s.tools).filter((t) => t.name !== Q.TONE && t.name !== Q.FIX);
  const ctx = ['editable'];
  chrome.contextMenus.create({ id: 'quill-panel', title: 'Open Quill panel', contexts: ctx });
  chrome.contextMenus.create({ id: 'quill-accept', title: 'Accept all Quill suggestions', contexts: ctx });
  chrome.contextMenus.create({ id: 'quill-rewrite', title: 'Rewrite with Quill', contexts: ctx });
  tools.forEach((t) =>
    chrome.contextMenus.create({ id: 'tool:' + t.name, parentId: 'quill-rewrite', title: t.name, contexts: ctx }),
  );
}

chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (!tab) return;
  const id = String(info.menuItemId);
  const msg = id === 'quill-panel' ? { type: 'ctx', action: 'panel' } : id === 'quill-accept' ? { type: 'ctx', action: 'accept' } : id.startsWith('tool:') ? { type: 'ctx', action: 'tool', tool: id.slice(5) } : null;
  if (msg) chrome.tabs.sendMessage(tab.id, msg, { frameId: info.frameId }).catch(() => {});
});

chrome.commands.onCommand.addListener((name, tab) => {
  if (!tab) return;
  // Reaches every frame of the tab; only the one with a focused field acts on it.
  chrome.tabs.sendMessage(tab.id, { type: 'command', name }).catch(() => {});
});

async function updateIcon() {
  const s = await settings();
  const suffix = s.enabled ? '' : '-off';
  chrome.action.setIcon({ path: { 16: `icons/icon16${suffix}.png`, 32: `icons/icon32${suffix}.png` } }).catch(() => {});
  chrome.action.setTitle({ title: s.enabled ? 'Quill' : 'Quill (paused)' }).catch(() => {});
}

chrome.runtime.onInstalled.addListener((d) => {
  buildMenus();
  if (d.reason === 'install') chrome.tabs.create({ url: chrome.runtime.getURL('options/options.html?welcome=1') });
});
chrome.runtime.onStartup.addListener(buildMenus);

// Menus persist across worker restarts; the Origin rule and the icon are cheap to refresh on every wake.
ruleReady = syncOriginRule();
updateIcon();
