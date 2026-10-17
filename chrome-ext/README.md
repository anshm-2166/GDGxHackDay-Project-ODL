# Quill for Chrome

Grammarly-style writing help in every text field, powered by **Gemma**, running on your own machine (served through Ollama). Nothing leaves your machine. It's the browser sibling of the Quill Android app and uses the same prompts and the same diff-based checker.

## Install

1. Open `chrome://extensions`, turn on **Developer mode**.
2. **Load unpacked** and pick this `chrome-ext` folder.
3. The settings page opens. Press **Save & test connection** (default `http://localhost:11434`).
4. Reload any open tabs, click into a text box and type.

Needs Chrome 111+ and an Ollama server with at least one model (`ollama pull gemma4:e4b`).

## Features

- **Live checking while you type** in `<textarea>`, text `<input>` and `contenteditable` editors (Gmail, Slack, LinkedIn, Notion-style editors...).
- **Colored underlines** by type: correctness (red), clarity (blue), engagement (green), delivery (purple).
- **Hover card** with Accept, Dismiss, *Why?* (model explains the change) and *Add to dictionary*.
- **Quill badge** in the field showing the suggestion count, a spinner while checking, a tick when clean.
- **Side panel** (click the badge or `Alt+Q`): suggestions with a writing score and *Accept all*; **Rewrite** tab (Formal, Friendly, Confident, Casual, Shorter, Longer, Clearer, To English, Compose, plus a free-form instruction box) with streaming preview and Replace / Copy / Retry; **Insights** tab with word count, reading and speaking time, readability and tone detection.
- **Selection toolbar**: select text for one-click Fix / Formal / Friendly / Shorter / Clearer.
- Right-click menu in text boxes, shortcuts `Alt+Q` (panel) and `Alt+Shift+A` (accept all), both rebindable at `chrome://extensions/shortcuts`.
- Per-site off switch, personal dictionary, editable prompts and custom rewrite styles, import/export of settings.
- Long texts are chunked and cached, so editing one paragraph only re-checks that paragraph; stale requests are cancelled as you keep typing.
- Skips password, search, email, URL and autofill-style fields, and code editors. Opt any field out with `data-quill="off"`.

## Ollama address and CORS

Set the address under **Settings → Connection**. Defaults to `http://localhost:11434`; `host` alone means port 11434.

All requests go through the background service worker, never the page, so `https://` sites can reach `http://localhost` without mixed-content or CSP issues. Ollama rejects browser requests with an unknown `Origin` (403), so a `declarativeNetRequest` rule removes the `Origin` header from Quill's own requests to the configured host only. **No `OLLAMA_ORIGINS` setup is needed.** Non-local addresses trigger Chrome's one-time "allow access to this host" prompt on *Save & test*. If a proxy still gives 403, set `OLLAMA_ORIGINS=chrome-extension://*` (instructions are on the settings page).

## Layout

| Path | Role |
| --- | --- |
| `manifest.json` | MV3 manifest |
| `background.js` | Ollama client, CORS rule, request queue, menus, shortcuts |
| `lib/shared.js` | Defaults, prompts, diff / merge / chunk helpers (port of `Checker.kt`) |
| `content/` | `bridge` (worker port), `fields` (textarea / input / contenteditable adapters), `ui` (shadow-DOM interface), `app` (controller) |
| `options/`, `popup/` | Settings page and toolbar popup |
| `icons/` | The Quill leaf, in color and greyed-out for "paused" |
