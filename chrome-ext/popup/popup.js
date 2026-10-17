(async function () {
  'use strict';
  const Q = self.Quill;
  const $ = (id) => document.getElementById(id);
  let S = await Q.getSettings();
  let host = '';

  const save = (patch) => chrome.storage.local.set(patch);

  // ---- toggles

  $('enabled').checked = S.enabled;
  $('style').checked = S.style;
  $('underline').checked = S.underline;
  $('enabled').addEventListener('change', (e) => save({ enabled: e.target.checked }));
  $('style').addEventListener('change', (e) => save({ style: e.target.checked }));
  $('underline').addEventListener('change', (e) => save({ underline: e.target.checked }));

  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    const u = tab && tab.url && new URL(tab.url);
    if (u && /^https?:$/.test(u.protocol)) {
      host = u.hostname;
      $('siteName').textContent = host.replace(/^www\./, '');
      $('siteRow').hidden = false;
      $('siteOn').checked = !Q.siteDisabled(S, host);
    }
  } catch (e) {}

  $('siteOn').addEventListener('change', async (e) => {
    const cur = (await Q.getSettings()).disabledSites.filter((d) => d.toLowerCase() !== host.toLowerCase());
    save({ disabledSites: e.target.checked ? cur : cur.concat(host) });
  });

  // ---- footer

  const n = S.accepted || 0;
  $('stat').textContent = n ? `${n.toLocaleString()} suggestion${n === 1 ? '' : 's'} accepted` : 'Ready when you are';
  $('settings').addEventListener('click', () => {
    chrome.runtime.openOptionsPage();
    window.close();
  });
  $('shortcuts').addEventListener('click', () => chrome.tabs.create({ url: 'chrome://extensions/shortcuts' }));
  $('connFix').addEventListener('click', () => {
    chrome.runtime.openOptionsPage();
    window.close();
  });

  // ---- connection

  const conn = $('conn');
  const r = await chrome.runtime.sendMessage({ type: 'test' });
  if (r && r.ok) {
    const names = r.models.map((m) => m.name);
    const has = names.some((x) => x === r.model || x === r.model + ':latest');
    if (!names.length) {
      conn.dataset.state = 'bad';
      $('connTitle').textContent = 'Connected, but no models';
      $('connSub').textContent = `Run: ollama pull ${r.model}`;
      $('connFix').hidden = false;
    } else if (!has) {
      conn.dataset.state = 'bad';
      $('connTitle').textContent = `Model "${r.model}" not found`;
      $('connSub').textContent = `Pick one of ${names.length} installed models`;
      $('connFix').hidden = false;
    } else {
      conn.dataset.state = 'ok';
      $('connTitle').textContent = 'Connected to Ollama';
      $('connSub').textContent = `${r.model} · ${new URL(r.url).host}`;
    }
  } else {
    conn.dataset.state = 'bad';
    $('connTitle').textContent = "Can't reach Ollama";
    $('connSub').textContent = (r && r.error && r.error.message) || 'No answer from the service worker';
    $('connFix').hidden = false;
  }
})();
