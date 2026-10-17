# Quill

**A private, on-device writing and productivity assistant: an open alternative to Grammarly, powered by Gemma.**

Quill fixes your grammar, rewrites your tone, drafts emails, keeps your agenda and tracks your goals. It runs **Gemma 4 E2B on your phone** through Google's LiteRT-LM, with GPU acceleration, so it works **offline** and **your writing never leaves the device**. Prefer a bigger model? Point it at your own Ollama server instead.

This repository has two parts:

| Part | What it is |
| --- | --- |
| [`app/`](app) | **Quill for Android**: Scribe (writing desk), Chat with business commands, Desk (events, notes, goals), a keyboard and an any-app assistant. |
| [`chrome-ext/`](chrome-ext) | **Quill for Chrome, Edge and Brave**: Grammarly-style checking in every text field on Windows, macOS and Linux. |

---

## Why on-device?

| | |
| --- | --- |
| **Offline** | After a one-time model download, Quill needs no connection. Planes, trains and bad Wi-Fi are fine. |
| **Privacy first** | In on-device mode nothing you write is sent anywhere: no account, no analytics, no cloud. The only network request is the one-time model download. Text from password fields is never sent. |
| **No Gemini Nano needed** | Gemini Nano (Android AICore) is limited to a short list of recent devices. Quill ships its own runtime (LiteRT-LM) and downloads its own model, so it does not depend on AICore, Google Play services or a particular phone brand. |
| **GPU accelerated** | LiteRT-LM's GPU backend is used by default, with an automatic CPU fallback if the GPU can't load the model. |
| **Free, no subscription** | No tiers, no word limits, no login. |
| **Yours to tune** | Every system prompt is editable, every command is a prompt you can change, and the model is replaceable. |

On Google's published benchmark for Gemma 4 E2B on a Galaxy S26 Ultra, the GPU backend reaches roughly 52 tokens/s decode and about 0.3 s to first token (CPU: about 47 tokens/s, 1.8 s). Those are Google's figures for that phone, not measurements of this app; Quill shows the real speed of every reply as `tok/s`. Plan on a phone with several GB of RAM; low-end devices are untested.

### Quill vs. Grammarly

| | Quill | Typical cloud grammar tools |
| --- | --- | --- |
| Where text is processed | On your phone, or a server **you** run | Vendor's cloud |
| Works offline | Yes (on-device mode) | No |
| Account / subscription | None | Usually required for full features |
| Source | Open (MIT) | Closed |
| Customisable prompts and tools | Yes | No |
| Beyond grammar | Email drafting, agenda, reminders, alarms, goals, notes, voice and image input | Varies |

Be realistic about quality: a 2-billion-parameter on-device model is capable but smaller than the models behind cloud services. For harder rewrites, switch the engine to Ollama and run a larger Gemma on your own machine.

---

## Android app

### Features

**Scribe**: paste or type, then one tap: *Fix all* (with word-level diff), Tone, Formal, Friendly, Shorter, Clearer, To English (Hindi and Hinglish supported), Compose. Add your own tools with your own prompts.

**Chat** with business-oriented slash commands (type `/` for suggestions):

| Group | Commands |
| --- | --- |
| Email | `/draftmail --receiver a@b.com [--subject "..."] [--tone formal] <what to say>` writes the email and **opens your mail app with it prefilled**; `/reply`; `/followup`; `/subject` |
| Writing | `/summarize`, `/explain`, `/proofread`, `/rewrite --tone`, `/translate --to <language>`, `/shorten`, `/expand`, `/tone`, `/define` |
| Meetings and business | `/actionitems`, `/minutes`, `/agenda`, `/linkedin`, `/swot`, `/pitch` |
| Schedule | `/addevent` (alias `/event`), `/alarm` (a real ringing alarm in your Clock app), `/remind`, `/upcoming` (the model briefs you on what's next), `/events` |
| Edit in plain words | `/edit`, `/reschedule`, `/cancel`, `/done`, or just say it: "move my gym alarm to 7am", "I finished the outline" |
| Goals | `/goal <what you want>` (Quill breaks it into steps), `/goals`: tick steps off by tapping or by telling the chat |
| Notes and housekeeping | `/note`, `/notes`, `/clear`, `/help` |

- **Image input**: attach a photo or screenshot and ask about it (`/summarize`, `/explain`, or plain chat). Needs a vision-capable model. The on-device path loads LiteRT-LM's vision backend on demand; image input has not been tested end to end yet.
- **Voice input**: tap the mic and speak; the system speech recognizer fills the box.
- **Streaming replies with live tokens-per-second.**
- **Current date injected into every prompt**, so the model never assumes its training year when you say "tomorrow" or "next Friday".

**Desk**: Upcoming and Past events, Notes, and Goals with progress bars, all stored on the phone. Events and alarms get real notifications (AlarmManager, restored after reboot) and an *Add to Calendar* button.

**Quill Keyboard**: a full keyboard with suggestion cards above the keys and a writing-tools menu.

**Quill Assistant** (accessibility service): underlines mistakes in any app, with any keyboard, and offers fixes from a floating button. Text from password fields is never sent.

**Select text anywhere → "Improve with Quill".**

### Tech stack

| Layer | Choice |
| --- | --- |
| Language | Kotlin 2.4 (Kotlin Gradle plugin 2.4.0) |
| UI | **Android XML views** with AppCompat and RecyclerView. No Jetpack Compose and no Material components; the look is custom shapes, ripples and vector icons |
| Build | Android Gradle Plugin 9.4.1, Gradle version catalog, `minSdk` 26, `compileSdk`/`targetSdk` 37 |
| On-device AI | [LiteRT-LM](https://ai.google.dev/edge/litert-lm/android) `com.google.ai.edge.litertlm:litertlm-android:0.18.0`: GPU backend, vision input, streaming |
| Model | **Gemma 4 E2B** (`gemma-4-E2B-it.litertlm`, about 2.6 GB) from [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) |
| Server AI (optional) | [Ollama](https://ollama.com) `/api/chat`, streamed, with JSON-schema output; default model `gemma4:e4b` |
| Persistence | SharedPreferences (JSON); no database, no cloud |
| Scheduling | AlarmManager, BroadcastReceivers, Clock app intent (`ACTION_SET_ALARM`), Calendar insert intent |
| Voice and mail | `RecognizerIntent`, `mailto:` `ACTION_SENDTO` |
| Tests | JUnit 4 (commands parser, event parser, organizer, diff checker) |

### Fonts

| Use | Font | Licence |
| --- | --- | --- |
| Headings | **DM Serif Display** (regular and italic) | SIL Open Font License 1.1 |
| Interface and body | **DM Sans** (variable, weights 400 to 700) | SIL Open Font License 1.1 |

Both are bundled in `app/src/main/res/font/`, so the app needs no network to render. The palette is a sage and forest green, light and dark.

### How the AI is wired

```
Scribe / Chat / Keyboard / Assistant
              │
         Gemma.kt  ── every request gets the system prompt + the current date and time
        ┌─────┴───────────────┐
   Local.kt (LiteRT-LM)    Ollama HTTP (your server)
   GPU → CPU fallback      streamed /api/chat
   Gemma 4 E2B on device   any model you choose
```

- `Local.kt` loads the engine lazily, tries the GPU backend and falls back to CPU, keeps one engine alive, and creates a fresh conversation per request.
- Models without a JSON-schema mode get the schema in the prompt and Quill extracts the JSON object from the reply.
- `Organizer.kt` shows the model your events and goals under short references (`E1`, `G2.3`) and applies the operations it returns, so "move gym to 7am" edits the right item.
- `EventParser.kt` accepts the many date formats models produce and corrects a stale training year.

### Project layout

```
app/src/main/java/com/vytros/projects/Quill/
  MainActivity.kt      tabs, insets, launchers (image, voice, notifications)
  ScribeTab.kt         writing desk
  ChatTab.kt           chat, slash commands, attachments, goal cards
  DeskTab.kt           events, notes, goals
  SettingsTab.kt       engine, models, prompts
  Gemma.kt             one entry point for all model calls
  Local.kt             LiteRT-LM engine, model download/import
  Commands.kt          slash commands and their editable prompts
  Prompts.kt           editable prompts, clock/calendar helpers
  Organizer.kt         plain-language edits and goal creation
  EventParser.kt       model output → event
  Store.kt             events, notes, goals
  Reminders.kt         alarms, notifications, boot restore
  Checker.kt           word-level diff into suggestion cards
  QuillKeyboard.kt     input method service
  QuillAssistant.kt    accessibility service (any-app underlines)
chrome-ext/            browser extension
```

### Build and run

Requires Android Studio (or JDK 17+ and the Android SDK 37).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
```

Set `sdk.dir` in `local.properties` if Android Studio hasn't done it for you.

**First run, on-device mode**

1. Settings → *Where Quill thinks* → **On this phone**.
2. Tap **Download** (about 2.6 GB, Wi-Fi recommended, resumable), or **Import file** if you already have `gemma-4-E2B-it.litertlm`. You can also copy it to `Android/data/com.vytros.projects.Quill/files/models/` with `adb push`.
3. Leave *Accelerator* on **GPU**. Quill falls back to CPU by itself if needed.

**First run, Ollama mode**

```bash
ollama pull gemma4:e4b
OLLAMA_HOST=0.0.0.0 ollama serve
```

Set the URL in Settings: `http://10.0.2.2:11434` (emulator), `http://<your-PC-IP>:11434` (phone on Wi-Fi), or `adb reverse tcp:11434 tcp:11434` then `http://127.0.0.1:11434` (USB).

### Permissions, and why

| Permission | Used for |
| --- | --- |
| `INTERNET` | Model download, and your Ollama server if you choose it |
| `POST_NOTIFICATIONS` | Event and reminder notifications |
| `SCHEDULE_EXACT_ALARM` | On-time reminders (Quill falls back to approximate alarms if denied) |
| `RECEIVE_BOOT_COMPLETED` | Restore reminders after a reboot |
| `com.android.alarm.permission.SET_ALARM` | `/alarm` hands the alarm to your Clock app |
| Accessibility service (opt-in) | The any-app assistant |

The microphone is not requested by Quill; voice input uses the system speech recognizer.

### Known limitations

- Alarms rung by the Clock app can be created but not removed by Quill (Android doesn't allow it); Quill tells you to delete them there. The Clock app also only accepts alarms within the next 24 hours, so farther alarms become Quill notifications.
- Images are not kept in chat history after a restart.
- The on-device model has no native JSON mode, so structured commands (`/addevent`, `/goal`, `/edit`) can be less reliable than with Ollama.
- Speech recognition may use your phone's speech service, which is outside Quill's control.

---

## Chrome extension (Chrome, Edge, Brave)

Grammarly-style help in every text field on the web: live underlines by type (correctness, clarity, engagement, delivery), hover cards with Accept / Dismiss / *Why?*, a side panel with rewrite styles and insights, a selection toolbar, a personal dictionary and per-site switches. It is the browser sibling of the app and uses the same prompts and the same diff-based checker.

It talks to **Ollama running on your own computer**, so text never goes to a third-party service. Full details are in [`chrome-ext/README.md`](chrome-ext/README.md).

**Works on** any Chromium browser with Manifest V3 support (Chrome 111+): Chrome, Microsoft Edge and Brave, on Windows, macOS and Linux. It uses only standard MV3 APIs and no native components. I have not run it on every browser and OS combination, so report any that misbehave.

### Install (all browsers, all systems)

1. Install [Ollama](https://ollama.com/download) (Windows, macOS and Linux installers available) and pull a model: `ollama pull gemma4:e4b`.
2. Open the extensions page and enable **Developer mode**:

   | Browser | Address |
   | --- | --- |
   | Chrome | `chrome://extensions` |
   | Edge | `edge://extensions` |
   | Brave | `brave://extensions` |

3. Click **Load unpacked** and choose the `chrome-ext` folder.
4. In the settings page that opens, press **Save & test connection** (default `http://localhost:11434`).
5. Reload open tabs, click into a text box and type. `Alt+Q` opens the panel, `Alt+Shift+A` accepts all suggestions.

No `OLLAMA_ORIGINS` setup is needed: requests go through the extension's service worker and a scoped `declarativeNetRequest` rule.

---

## Privacy, precisely

- **On-device mode:** text is processed by the model on your phone. Network use is limited to downloading the model file from Hugging Face.
- **Ollama mode and the Chrome extension:** text goes to the Ollama server **you** configure (your PC, your LAN, your cloud box). Quill has no server of its own and no telemetry.
- Text from password fields is never sent by the keyboard or assistant. The extension skips password, email, URL and search fields and code editors.
- Calendar, notes and goals live in app storage on the phone.

If you use the Ollama option with a server outside your own network, that server's operator can see your text; choose accordingly.

## Credits and licences

- Quill is released under the [MIT licence](LICENSE).
- **Gemma 4** model files from the `litert-community` repository on Hugging Face (licence stated there as Apache-2.0). Check the model card for current terms.
- **LiteRT-LM** by Google AI Edge, used as a library. See its repository for its licence.
- **Ollama** is a separate project, used only as an optional server.
- **DM Sans** and **DM Serif Display** come from Google Fonts (Colophon Foundry), under the SIL Open Font License 1.1.
- Built for the GDG × HackDay project.
