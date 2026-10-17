/*
 * Quill's on-page interface, rendered in a closed-off shadow root so page CSS can't touch it:
 * underlines, the status badge, the suggestion card, the side panel and the selection toolbar.
 * The View only draws; every action is reported back through the callbacks given to its constructor.
 */
(function () {
  'use strict';
  const Q = self.Quill;

  const CAT = {
    correctness: { name: 'Correctness', css: 'var(--red)' },
    clarity: { name: 'Clarity', css: 'var(--blue)' },
    engagement: { name: 'Engagement', css: 'var(--green)' },
    delivery: { name: 'Delivery', css: 'var(--purple)' },
  };

  const SVG = {
    logo: '<svg viewBox="22 24 62 62" aria-hidden="true"><path fill="#fff" d="M74,30C58,31 42,42 38,60C37,64 37,68 38,72C46,70 52,66 57,60C66,50 72,42 74,30z"/><path fill="none" stroke="#2F6B4F" stroke-width="2.8" stroke-linecap="round" d="M72,34C62,46 50,60 36,78"/><path fill="none" stroke="#BFE3C9" stroke-width="4.2" stroke-linecap="round" d="M33,80L40,73"/></svg>',
    leaf: '<svg viewBox="22 24 62 62" aria-hidden="true"><path fill="currentColor" d="M74,30C58,31 42,42 38,60C37,64 37,68 38,72C46,70 52,66 57,60C66,50 72,42 74,30z"/><path fill="none" stroke="var(--bg)" stroke-width="2.8" stroke-linecap="round" d="M72,34C62,46 50,60 36,78"/><path fill="none" stroke="currentColor" stroke-width="4.2" stroke-linecap="round" d="M33,80L40,73"/></svg>',
    check: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round" d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
    close: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" d="M6 6l12 12M18 6L6 18"/></svg>',
    gear: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" d="M12 15.2a3.2 3.2 0 100-6.4 3.2 3.2 0 000 6.4zM19.4 13.5a7.7 7.7 0 000-3l2-1.5-2-3.4-2.3.9a7.7 7.7 0 00-2.6-1.5L14 2.5h-4l-.5 2.5a7.7 7.7 0 00-2.6 1.5l-2.3-.9-2 3.4 2 1.5a7.7 7.7 0 000 3l-2 1.5 2 3.4 2.3-.9a7.7 7.7 0 002.6 1.5l.5 2.5h4l.5-2.5a7.7 7.7 0 002.6-1.5l2.3.9 2-3.4z"/></svg>',
    send: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M3.4 20.4l17.5-7.5a1 1 0 000-1.8L3.4 3.6a1 1 0 00-1.4 1.2L4 10.5l9 1.5-9 1.5-2 5.7a1 1 0 001.4 1.2z"/></svg>',
    arrow: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" d="M5 12h14M13 6l6 6-6 6"/></svg>',
    spark: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 2l1.8 5.7L19.5 9.5l-5.7 1.8L12 17l-1.8-5.7L4.5 9.5l5.7-1.8L12 2zM19 14l.9 2.6 2.6.9-2.6.9L19 21l-.9-2.6-2.6-.9 2.6-.9L19 14z"/></svg>',
  };

  const CSS = `
:host{all:initial}
.q{--bg:#fff;--surface:#F3F6F0;--text:#18271E;--muted:#5B6D61;--line:#D8E2D6;--brand:#2F6B4F;--brand-2:#3F8561;--soft:#DCEBDD;--on-brand:#fff;
  --red:#D9484D;--blue:#2F6FED;--green:#12A150;--purple:#8B5CF6;--amber:#D98A1F;
  --shadow:0 12px 40px rgba(17,48,31,.22),0 2px 6px rgba(0,0,0,.08);
  font:13px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;color:var(--text);
  -webkit-font-smoothing:antialiased;letter-spacing:0;text-align:left}
.q.dark{--bg:#17211B;--surface:#1F2B24;--text:#E6EFE8;--muted:#9FB1A5;--line:#2F3F36;--brand:#6FBF95;--brand-2:#8AD1AC;--soft:#25392F;--on-brand:#0E1F16;
  --red:#F2767B;--blue:#6C9BFF;--green:#3DD582;--purple:#B197FC;--amber:#F0A94A;--shadow:0 12px 40px rgba(0,0,0,.55),0 2px 6px rgba(0,0,0,.4)}
*{box-sizing:border-box}
button{font:inherit;color:inherit;cursor:pointer;border:0;background:none;padding:0}
.hide{display:none!important}
.clip{position:fixed;overflow:hidden;pointer-events:none}
.mk{position:absolute;border-bottom:2px solid var(--c);border-radius:1px;transition:background .12s}
.mk.hl{background:color-mix(in srgb,var(--c) 18%,transparent)}
.cat-correctness{--c:var(--red)}.cat-clarity{--c:var(--blue)}.cat-engagement{--c:var(--green)}.cat-delivery{--c:var(--purple)}

/* badge */
.badge{position:fixed;width:26px;height:26px;border-radius:50%;pointer-events:auto;background:linear-gradient(145deg,var(--brand-2),#245C40);
  box-shadow:0 1px 4px rgba(0,0,0,.28);display:grid;place-items:center;transition:transform .12s,opacity .15s,filter .15s;opacity:.95}
.badge:hover{transform:scale(1.1);opacity:1}
.badge:focus-visible{outline:2px solid var(--blue);outline-offset:2px}
.badge .b-logo{width:16px;height:16px;display:block}.badge .b-logo svg{width:100%;height:100%;display:block}
.badge[data-state=paused]{filter:grayscale(1) opacity(.7)}
.badge .b-ring{position:absolute;inset:-3px;border-radius:50%;border:2px solid transparent;border-top-color:var(--brand-2);opacity:0}
.badge[data-state=checking] .b-ring{opacity:1;animation:spin .8s linear infinite}
.pip{position:absolute;right:-5px;bottom:-5px;min-width:15px;height:15px;padding:0 3px;border-radius:8px;font-size:10px;font-weight:700;line-height:15px;text-align:center;color:#fff;
  box-shadow:0 0 0 2px var(--bg);display:none}
.pip svg{width:9px;height:9px;display:block;margin:3px auto}
.badge[data-state=issues] .pip{display:block;background:var(--red)}
.badge[data-state=ok] .pip{display:block;background:var(--green)}
.badge[data-state=error] .pip{display:block;background:#8A948D}
@keyframes spin{to{transform:rotate(360deg)}}

/* cards + panel share a surface */
.card,.panel,.selbar,.toast{background:var(--bg);color:var(--text);border:1px solid var(--line);box-shadow:var(--shadow);pointer-events:auto}
.card{position:fixed;width:300px;border-radius:12px;padding:12px 14px 12px;border-top:3px solid var(--c,var(--brand));animation:pop .12s ease-out}
.card .c-head{display:flex;align-items:center;gap:7px;margin-bottom:6px;font-weight:600}
.dot{width:8px;height:8px;border-radius:50%;background:var(--c);flex:none}
.card .c-cat{margin-left:auto;font-size:11px;font-weight:500;color:var(--muted)}
.diff{font-size:14px;line-height:1.5;word-break:break-word;margin:2px 0 10px}
.diff del{color:var(--muted);text-decoration:line-through;text-decoration-color:var(--red)}
.diff ins{text-decoration:none;font-weight:600;background:color-mix(in srgb,var(--green) 18%,transparent);border-radius:3px;padding:0 2px}
.diff .arr{color:var(--muted);margin:0 5px;display:inline-block;vertical-align:-2px}.diff .arr svg{width:13px;height:13px;display:block}
.explain{font-size:12px;color:var(--muted);margin:-2px 0 10px;padding:8px 10px;background:var(--surface);border-radius:8px}
.row{display:flex;gap:6px;align-items:center;flex-wrap:wrap}
.btn{height:28px;padding:0 12px;border-radius:14px;font-weight:600;font-size:12.5px;border:1px solid var(--line);background:var(--bg);transition:background .12s,transform .06s}
.btn:hover{background:var(--surface)}.btn:active{transform:scale(.97)}
.btn.primary{background:var(--brand);color:var(--on-brand);border-color:transparent}.btn.primary:hover{background:var(--brand-2)}
.btn.sm{height:26px;padding:0 10px;font-size:12px}
.btn[disabled]{opacity:.5;pointer-events:none}
.ghost{color:var(--muted);font-weight:500;border-color:transparent}
.spacer{flex:1}
.icon{width:28px;height:28px;border-radius:50%;display:grid;place-items:center;color:var(--muted)}
.icon:hover{background:var(--surface);color:var(--text)}.icon svg{width:17px;height:17px}
@keyframes pop{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:none}}

/* panel */
.panel{position:fixed;width:376px;max-height:min(580px,calc(100vh - 24px));border-radius:16px;display:flex;flex-direction:column;overflow:hidden;animation:pop .14s ease-out}
.p-head{display:flex;align-items:center;gap:8px;padding:10px 10px 6px 14px}
.p-brand{display:flex;align-items:center;gap:8px;font-family:Georgia,"Times New Roman",serif;font-size:19px;font-weight:700;color:var(--brand)}
.p-brand i{width:22px;height:22px;display:block;color:var(--brand)}.p-brand i svg{width:100%;height:100%;display:block}
.tabs{display:flex;gap:2px;padding:0 10px;border-bottom:1px solid var(--line)}
.tab{padding:8px 10px 9px;font-weight:600;color:var(--muted);border-bottom:2px solid transparent;margin-bottom:-1px;display:flex;gap:6px;align-items:center}
.tab:hover{color:var(--text)}.tab.on{color:var(--brand);border-bottom-color:var(--brand)}
.tab .n{background:var(--red);color:#fff;border-radius:9px;font-size:10.5px;min-width:18px;height:18px;line-height:18px;text-align:center;padding:0 5px}
.p-body{overflow:auto;padding:12px 14px;flex:1;min-height:120px;scrollbar-width:thin}
.p-foot{padding:8px 14px;border-top:1px solid var(--line);font-size:11.5px;color:var(--muted);display:flex;gap:8px;align-items:center;background:var(--surface)}
.p-foot b{font-weight:600;color:var(--text)}
.p-foot .dotc{width:7px;height:7px;border-radius:50%;background:var(--green)}
.p-foot .dotc.bad{background:var(--red)}
.link{color:var(--brand);font-weight:600;text-decoration:underline;text-underline-offset:2px}

.sum{display:flex;gap:14px;align-items:center;margin-bottom:12px}
.ring{width:54px;height:54px;flex:none;position:relative}
.ring svg{width:100%;height:100%;transform:rotate(-90deg)}
.ring .num{position:absolute;inset:0;display:grid;place-items:center;font-weight:700;font-size:16px}
.ring circle{fill:none;stroke-width:5}.ring .t{stroke:var(--surface)}.ring .v{stroke-linecap:round;transition:stroke-dasharray .4s}
.sum h3{margin:0;font-size:14.5px}.sum p{margin:2px 0 0;color:var(--muted);font-size:12.5px}
.chips{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:12px}
.chip{display:inline-flex;gap:6px;align-items:center;padding:3px 9px;border-radius:12px;background:var(--surface);font-size:12px;font-weight:600;border:1px solid transparent}
.chip.off{opacity:.45}
button.chip:hover,.chip.tool:hover{border-color:var(--brand);color:var(--brand)}
.chip.tool{padding:5px 12px;cursor:pointer;background:var(--soft);color:var(--brand)}
.chip.tool.on{background:var(--brand);color:var(--on-brand)}
.list{display:flex;flex-direction:column;gap:8px}
.sg{border:1px solid var(--line);border-left:3px solid var(--c);border-radius:10px;padding:9px 11px;background:var(--bg);transition:box-shadow .12s,background .12s}
.sg:hover,.sg.hl{background:var(--surface)}
.sg .s-top{display:flex;align-items:center;gap:7px;font-weight:600;margin-bottom:3px}
.sg .s-cat{margin-left:auto;font-size:11px;font-weight:500;color:var(--muted)}
.sg .diff{font-size:13.5px;margin:0 0 8px}
.empty{text-align:center;color:var(--muted);padding:18px 8px}
.empty .big{width:40px;height:40px;margin:0 auto 8px;border-radius:50%;background:color-mix(in srgb,var(--green) 16%,transparent);color:var(--green);display:grid;place-items:center}.empty .big svg{width:22px;height:22px}
.warn{background:color-mix(in srgb,var(--red) 10%,transparent);border:1px solid color-mix(in srgb,var(--red) 35%,transparent);color:var(--text);border-radius:10px;padding:9px 11px;margin-bottom:10px;font-size:12.5px}
.stick{position:sticky;bottom:-12px;background:linear-gradient(transparent,var(--bg) 30%);padding:14px 0 2px;margin-top:6px}

.scope{color:var(--muted);font-size:12px;margin-bottom:8px}.scope b{color:var(--text)}
.ask{display:flex;gap:6px;margin:10px 0 12px}
.ask input{flex:1;height:34px;border-radius:17px;border:1px solid var(--line);background:var(--bg);color:var(--text);padding:0 14px;font:inherit;outline:none}
.ask input:focus{border-color:var(--brand);box-shadow:0 0 0 3px color-mix(in srgb,var(--brand) 20%,transparent)}
.ask button{width:34px;height:34px;border-radius:50%;background:var(--brand);color:var(--on-brand);display:grid;place-items:center}
.ask button svg{width:16px;height:16px}
.result{border:1px solid var(--line);border-radius:12px;padding:11px 12px;background:var(--surface)}
.result .r-label{font-size:11px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;color:var(--brand);margin-bottom:5px;display:flex;gap:6px;align-items:center}
.result .r-text{white-space:pre-wrap;word-break:break-word;font-size:13.5px;line-height:1.55;margin-bottom:10px;user-select:text}
.result .r-text.info{margin-bottom:0}
.shimmer{height:11px;border-radius:6px;margin:7px 0;background:linear-gradient(90deg,var(--line) 25%,var(--surface) 50%,var(--line) 75%);background-size:200% 100%;animation:sh 1.2s linear infinite}
@keyframes sh{to{background-position:-200% 0}}
.caret::after{content:"";display:inline-block;width:2px;height:1em;background:var(--brand);margin-left:2px;vertical-align:-2px;animation:blink 1s steps(1) infinite}
@keyframes blink{50%{opacity:0}}
.tiles{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:12px}
.tile{background:var(--surface);border-radius:10px;padding:9px 10px}
.tile b{display:block;font-size:17px;line-height:1.2}.tile span{font-size:11px;color:var(--muted)}
.tile.wide{grid-column:span 3;display:flex;align-items:center;gap:10px}.tile.wide b{font-size:20px}
.h4{font-size:11px;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:var(--muted);margin:14px 0 6px}
.note{font-size:11.5px;color:var(--muted)}

/* selection toolbar + toast */
.selbar{position:fixed;display:flex;align-items:center;gap:2px;padding:4px 5px 4px 8px;border-radius:20px;animation:pop .12s ease-out}
.selbar i{width:18px;height:18px;margin-right:4px;color:var(--brand);display:block}.selbar i svg{width:100%;height:100%;display:block}
.selbar button{height:26px;padding:0 9px;border-radius:13px;font-weight:600;font-size:12px;color:var(--text)}
.selbar button:hover{background:var(--soft);color:var(--brand)}
.toast{position:fixed;padding:7px 14px;border-radius:18px;font-weight:600;font-size:12.5px;animation:pop .12s ease-out}
`;

  const el = (tag, cls, text) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  };
  const button = (cls, text, onClick, title) => {
    const b = el('button', cls, text);
    b.type = 'button';
    if (title) {
      b.title = title;
      b.setAttribute('aria-label', title);
    }
    b.addEventListener('click', (e) => {
      e.stopPropagation();
      onClick(e);
    });
    return b;
  };
  const iconBtn = (svg, title, onClick) => {
    const b = button('icon', null, onClick, title);
    b.innerHTML = svg;
    return b;
  };
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  const trunc = (s, n) => (s.length > n ? s.slice(0, n - 1) + '…' : s);
  const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;

  function arrow() {
    const a = el('span', 'arr');
    a.innerHTML = SVG.arrow;
    return a;
  }

  function diffNode(issue) {
    const d = el('div', 'diff');
    d.append(el('del', null, trunc(issue.original, 120)));
    if (issue.replacement) {
      d.append(arrow(), el('ins', null, trunc(issue.replacement, 120)));
    } else d.append(arrow(), el('span', null, '(remove)'));
    return d;
  }

  class View {
    constructor(cb) {
      this.cb = cb;
      this.fs = null;
      this.s = Q.DEFAULTS;
      this.marks = [];
      this.tab = 'fix';
      this.panelOpen = false;
      this.hoverKey = null;
      this.cardIssue = null;
      this.cardExplain = null;
      this.sig = '';
      this.dirty = true;
      this.selActive = false;
      this.timers = {};
      this.built = false;
    }

    // -------------------------------------------------------------- build

    build() {
      if (this.built) return;
      this.built = true;
      const host = (this.host = document.createElement('quill-ui'));
      host.style.cssText = 'all:initial;position:fixed;left:0;top:0;width:0;height:0;overflow:visible;z-index:2147483647;pointer-events:none;';
      const root = host.attachShadow({ mode: 'open' });
      const style = document.createElement('style');
      style.textContent = CSS;
      this.q = el('div', 'q');
      this.clip = el('div', 'clip');
      this.badge = el('button', 'badge hide');
      this.badge.type = 'button';
      this.badge.innerHTML = '<span class="b-logo">' + SVG.logo + '</span><span class="b-ring"></span><span class="pip"></span>';
      this.badge.addEventListener('click', (e) => {
        e.stopPropagation();
        this.togglePanel();
      });
      this.pip = this.badge.querySelector('.pip');
      this.card = el('div', 'card hide');
      this.panel = el('div', 'panel hide');
      this.selbar = el('div', 'selbar hide');
      this.toastEl = el('div', 'toast hide');
      this.q.append(this.clip, this.badge, this.selbar, this.card, this.panel, this.toastEl);
      root.append(style, this.q);

      // Clicks inside Quill must not steal focus from the field being edited.
      root.addEventListener('mousedown', (e) => {
        if (!e.target.closest('input,textarea,select,.r-text')) e.preventDefault();
      });
      for (const t of ['keydown', 'keyup', 'keypress']) root.addEventListener(t, (e) => e.stopPropagation());
      this.card.addEventListener('mouseenter', () => clearTimeout(this.timers.hide));
      this.card.addEventListener('mouseleave', () => this.scheduleHide());
      document.documentElement.appendChild(host);

      this._mm = (e) => this.onMove(e);
      this._ck = (e) => this.onMove(e, true);
      this._rz = () => (this.dirty = true);
    }

    destroy() {
      this.clearField();
      if (this.host) this.host.remove();
      this.built = false;
    }

    // ------------------------------------------------------------ settings

    setSettings(s, info) {
      this.s = s;
      this.info = info;
      if (!this.built) return;
      const dark = s.theme === 'dark' || (s.theme === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
      this.q.classList.toggle('dark', dark);
      this.dirty = true;
      if (this.fs) this.update();
    }

    // -------------------------------------------------------------- field

    setField(fs) {
      this.build();
      if (this.fs === fs) return;
      this.clearField();
      this.fs = fs;
      this.sig = '';
      this.dirty = true;
      this.badge.classList.remove('hide');
      document.addEventListener('mousemove', this._mm, { capture: true, passive: true });
      document.addEventListener('click', this._ck, { capture: true, passive: true });
      window.addEventListener('resize', this._rz);
      this.update();
      this.loop();
    }

    clearField() {
      if (!this.built) return;
      cancelAnimationFrame(this.raf);
      document.removeEventListener('mousemove', this._mm, true);
      document.removeEventListener('click', this._ck, true);
      window.removeEventListener('resize', this._rz);
      this.fs = null;
      this.marks = [];
      this.clip.textContent = '';
      this.selActive = false;
      this.hideCard();
      this.closePanel();
      this.badge.classList.add('hide');
      this.selbar.classList.add('hide');
      this.toastEl.classList.add('hide');
    }

    loop() {
      const fs = this.fs;
      if (!fs) return;
      if (!fs.field.el.isConnected) return this.cb.onLost();
      this.cb.onTick();
      const el = fs.field.el;
      const b = el.getBoundingClientRect();
      const sig = [b.left, b.top, b.width, b.height, el.scrollTop, el.scrollLeft, innerWidth, innerHeight].join();
      if (sig !== this.sig || this.dirty) {
        this.sig = sig;
        this.dirty = false;
        this.layout();
      }
      this.raf = requestAnimationFrame(() => this.loop());
    }

    invalidate() {
      this.dirty = true;
    }

    // ------------------------------------------------------------- layout

    /** Re-measures the field and repositions everything that hangs off it. */
    layout() {
      const fs = this.fs;
      if (!fs) return;
      const vis = fs.field.visibleRect();
      this.vis = vis;
      this.layoutMarks(vis);
      this.layoutBadge();
      if (this.panelOpen) this.layoutPanel();
      if (this.selActive) this.layoutSelbar();
      if (this.cardIssue) this.layoutCard();
    }

    layoutMarks(vis) {
      this.clip.textContent = '';
      this.marks = [];
      if (!vis || !this.s.underline) return;
      Object.assign(this.clip.style, { left: vis.left + 'px', top: vis.top + 'px', width: vis.width + 'px', height: vis.height + 'px' });
      const field = this.fs.field;
      field.prepare();
      const frag = document.createDocumentFragment();
      for (const issue of this.fs.issues) {
        const rects = field.rectsFor(issue.start, issue.start + issue.original.length);
        const els = rects.map((r) => {
          const m = el('div', 'mk cat-' + issue.category);
          Object.assign(m.style, { left: r.left - vis.left + 'px', top: r.top - vis.top + 'px', width: r.width + 'px', height: r.height + 'px' });
          frag.append(m);
          return m;
        });
        this.marks.push({ issue, rects, els, key: Q.issueKey(issue) + issue.start });
      }
      this.clip.append(frag);
      this.applyHighlight();
    }

    layoutBadge() {
      const fs = this.fs;
      const box = fs.field.el.getBoundingClientRect();
      const show = this.s.badge && this.vis && box.width > 0;
      this.badge.classList.toggle('hide', !show);
      if (!show) return;
      const size = 26;
      const el = fs.field.el;
      const sbar = fs.field.kind === 'ce' ? Math.max(0, el.offsetWidth - el.clientWidth) : Math.max(0, el.offsetWidth - el.clientWidth - el.clientLeft * 2);
      const x = Math.min(box.right, innerWidth) - size - 6 - sbar;
      const multi = box.height > 52;
      const y = multi ? Math.min(box.bottom, innerHeight) - size - 6 : box.top + (box.height - size) / 2;
      this.badgePos = { x: clamp(x, 0, innerWidth - size), y: clamp(y, 0, innerHeight - size), size };
      Object.assign(this.badge.style, { left: this.badgePos.x + 'px', top: this.badgePos.y + 'px' });
    }

    layoutPanel() {
      const p = this.badgePos || { x: innerWidth - 60, y: innerHeight - 60, size: 26 };
      const w = this.panel.offsetWidth || 376;
      const h = this.panel.offsetHeight || 300;
      const left = clamp(p.x + p.size - w, 8, Math.max(8, innerWidth - w - 8));
      let top = p.y - h - 10;
      if (top < 8) top = Math.min(p.y + p.size + 10, innerHeight - h - 8);
      Object.assign(this.panel.style, { left: left + 'px', top: Math.max(8, top) + 'px' });
    }

    layoutCard() {
      const m = this.marks.find((x) => x.key === this.cardKey);
      if (!m || !m.rects.length) return this.hideCard();
      const r = m.rects[0];
      const w = 300;
      const h = this.card.offsetHeight || 120;
      const left = clamp(r.left, 8, Math.max(8, innerWidth - w - 8));
      let top = r.bottom + 8;
      if (top + h > innerHeight - 8) top = r.top - h - 8;
      Object.assign(this.card.style, { left: left + 'px', top: Math.max(8, top) + 'px' });
    }

    layoutSelbar() {
      const sel = this.fs.field.getSelection();
      if (!sel || sel.end - sel.start < 3) return this.hideSelbar();
      this.fs.field.prepare();
      const rects = this.fs.field.rectsFor(sel.start, sel.end);
      if (!rects.length || !this.vis) return this.hideSelbar();
      const r = rects[0];
      const w = this.selbar.offsetWidth || 300;
      const left = clamp(r.left, 8, Math.max(8, innerWidth - w - 8));
      const top = r.top - 42 < 6 ? r.bottom + 8 : r.top - 42;
      Object.assign(this.selbar.style, { left: left + 'px', top: top + 'px' });
    }

    // ------------------------------------------------------ hover + card

    onMove(e, click) {
      if (!this.fs || !this.marks.length) return;
      const x = e.clientX;
      const y = e.clientY;
      const hit = this.marks.find((m) => m.rects.some((r) => x >= r.left && x <= r.right && y >= r.top - 1 && y <= r.bottom + 3));
      if (hit) {
        clearTimeout(this.timers.hide);
        if (this.cardKey !== hit.key) {
          clearTimeout(this.timers.show);
          this.timers.show = setTimeout(() => this.showCard(hit), click ? 0 : 220);
        }
      } else {
        clearTimeout(this.timers.show);
        if (this.cardIssue) this.scheduleHide();
      }
    }

    scheduleHide() {
      clearTimeout(this.timers.hide);
      this.timers.hide = setTimeout(() => this.hideCard(), 380);
    }

    showCard(m) {
      this.cardIssue = m.issue;
      this.cardKey = m.key;
      this.cardExplain = null;
      this.hoverKey = m.key;
      this.applyHighlight();
      this.renderCard();
      this.card.classList.remove('hide');
      this.layoutCard();
    }

    hideCard() {
      if (!this.built) return;
      clearTimeout(this.timers.show);
      clearTimeout(this.timers.hide);
      if (this.cardExplain && this.cardExplain.cancel) this.cardExplain.cancel();
      this.cardIssue = null;
      this.cardKey = null;
      this.cardExplain = null;
      this.hoverKey = null;
      this.card.classList.add('hide');
      this.applyHighlight();
    }

    renderCard() {
      const i = this.cardIssue;
      const c = this.card;
      c.textContent = '';
      c.className = 'card cat-' + i.category;
      const head = el('div', 'c-head');
      head.append(el('span', 'dot'), el('span', null, i.type), el('span', 'c-cat', CAT[i.category].name));
      c.append(head, diffNode(i));
      if (this.cardExplain) {
        const ex = this.cardExplain;
        c.append(el('div', 'explain', ex.error || ex.text || 'Thinking…'));
      }
      const row = el('div', 'row');
      row.append(button('btn primary', 'Accept', () => this.cb.onAccept(i)), button('btn ghost', 'Dismiss', () => this.cb.onDismiss(i)));
      row.append(el('span', 'spacer'));
      if (!this.cardExplain) row.append(button('btn ghost sm', 'Why?', () => this.explain(i)));
      if (i.type === 'Spelling') row.append(button('btn ghost sm', 'Add to dictionary', () => this.cb.onAddWord(i)));
      c.append(row);
    }

    explain(issue) {
      const st = (this.cardExplain = { text: '', error: '', cancel: null });
      const run = this.cb.onExplain(issue);
      st.cancel = run.cancel;
      run.promise
        .then((t) => (st.text = t))
        .catch((e) => {
          if (e && e.code !== 'aborted') st.error = e.message || 'Could not explain this one.';
        })
        .finally(() => {
          if (this.cardExplain === st && this.cardIssue === issue) {
            this.renderCard();
            this.layoutCard();
          }
        });
      this.renderCard();
      this.layoutCard();
    }

    applyHighlight() {
      this.marks.forEach((m) => m.els.forEach((e) => e.classList.toggle('hl', m.key === this.hoverKey)));
      this.panel.querySelectorAll('.sg').forEach((s) => s.classList.toggle('hl', s.dataset.key === this.hoverKey));
    }

    // -------------------------------------------------------------- badge

    update() {
      if (!this.built) return;
      const fs = this.fs;
      if (!fs) return;
      const n = fs.issues.length;
      const state = fs.status === 'paused' ? 'paused' : fs.status === 'checking' ? 'checking' : fs.status === 'error' ? 'error' : n ? 'issues' : fs.status === 'ok' ? 'ok' : 'idle';
      this.badge.dataset.state = state;
      if (state === 'issues') this.pip.textContent = n > 99 ? '99+' : String(n);
      else if (state === 'ok') this.pip.innerHTML = SVG.check;
      else if (state === 'error') this.pip.textContent = '!';
      else this.pip.textContent = '';
      this.badge.title = { paused: 'Quill is paused here', checking: 'Quill is checking…', error: 'Quill: ' + ((fs.error && fs.error.message) || 'something went wrong'), issues: `Quill: ${plural(n, 'suggestion')}`, ok: 'Quill: looks good', idle: 'Open Quill' }[state];
      this.badge.setAttribute('aria-label', this.badge.title);
      this.dirty = true; // marks follow the latest issues
      if (this.panelOpen) this.renderPanel();
      if (this.cardIssue && !fs.issues.some((i) => Q.issueKey(i) + i.start === this.cardKey)) this.hideCard();
    }

    // -------------------------------------------------------------- panel

    openPanel(tab) {
      if (!this.fs) return;
      if (tab) this.tab = tab;
      this.panelOpen = true;
      this.hideCard();
      this.hideSelbar();
      this.panel.classList.remove('hide');
      this.renderPanel();
      this.layoutPanel();
      this.cb.onPanel(true, this.tab);
    }

    closePanel() {
      if (!this.built || !this.panelOpen) return;
      this.panelOpen = false;
      this.panel.classList.add('hide');
      this.cb.onPanel(false, this.tab);
    }

    togglePanel() {
      this.panelOpen ? this.closePanel() : this.openPanel();
    }

    setTab(t) {
      this.tab = t;
      this.renderPanel();
      this.cb.onPanel(true, t);
    }

    renderPanel() {
      const fs = this.fs;
      if (!fs) return;
      const p = this.panel;
      const keep = p.querySelector('.p-body');
      const scroll = keep ? keep.scrollTop : 0;
      const focused = this.root_input_focused();
      p.textContent = '';

      const head = el('div', 'p-head');
      const brand = el('div', 'p-brand');
      const logo = el('i');
      logo.innerHTML = SVG.leaf;
      brand.append(logo, el('span', null, 'Quill'));
      head.append(brand, el('span', 'spacer'), iconBtn(SVG.gear, 'Quill settings', () => this.cb.onSettings()), iconBtn(SVG.close, 'Close', () => this.closePanel()));

      const n = fs.issues.length;
      const tabs = el('div', 'tabs');
      [['fix', 'Suggestions'], ['rewrite', 'Rewrite'], ['insights', 'Insights']].forEach(([id, label]) => {
        const t = button('tab' + (this.tab === id ? ' on' : ''), label, () => this.setTab(id));
        if (id === 'fix' && n) t.append(el('span', 'n', String(n)));
        tabs.append(t);
      });

      const body = el('div', 'p-body');
      if (this.tab === 'fix') this.paneFix(body);
      else if (this.tab === 'rewrite') this.paneRewrite(body);
      else this.paneInsights(body);

      const foot = el('div', 'p-foot');
      const ok = !(fs.error && fs.status === 'error');
      foot.append(el('span', 'dotc' + (ok ? '' : ' bad')));
      const info = this.info || {};
      const f = el('span');
      f.append(el('b', null, info.model || ''), document.createTextNode(info.host ? ' · ' + info.host : ''));
      foot.append(f, el('span', 'spacer'), el('span', null, 'Private, on your machine'));

      p.append(head, tabs, body, foot);
      body.scrollTop = scroll;
      if (focused) {
        const i = p.querySelector('.ask input');
        if (i) {
          i.value = focused.value;
          i.focus();
          i.setSelectionRange(focused.pos, focused.pos);
        }
      }
      this.applyHighlight();
      if (this.panelOpen) this.layoutPanel();
    }

    root_input_focused() {
      const a = this.host.shadowRoot.activeElement;
      return a && a.tagName === 'INPUT' ? { value: a.value, pos: a.selectionStart } : null;
    }

    paneFix(body) {
      const fs = this.fs;
      const issues = fs.issues;
      const n = issues.length;
      const stats = Q.stats(fs.text || '');
      const score = fs.status === 'paused' ? null : Q.score(issues, stats.words);

      const sum = el('div', 'sum');
      const ring = el('div', 'ring');
      const pct = score == null ? 0 : score;
      const col = score == null ? 'var(--line)' : score >= 80 ? 'var(--green)' : score >= 60 ? 'var(--amber)' : 'var(--red)';
      ring.innerHTML = `<svg viewBox="0 0 54 54"><circle class="t" cx="27" cy="27" r="22"/><circle class="v" cx="27" cy="27" r="22" style="stroke:${col}" stroke-dasharray="${(pct / 100) * 138.2} 138.2"/></svg>`;
      ring.append(el('div', 'num', score == null ? '-' : String(score)));
      const txt = el('div');
      let title = 'Writing score';
      let sub = '';
      if (fs.status === 'paused') (title = 'Quill is paused'), (sub = fs.pausedWhy || 'Turn it back on from the toolbar popup.');
      else if (fs.status === 'checking') sub = n ? `Checking… ${plural(n, 'suggestion')} so far` : 'Checking your text…';
      else if (fs.status === 'error' && !n) (title = "Couldn't check"), (sub = '');
      else if (n) sub = `${plural(n, 'suggestion')} to review`;
      else if (stats.words < 2) sub = 'Start typing and Quill checks as you write.';
      else sub = fs.status === 'ok' ? 'Looks great. No suggestions.' : 'Ready to check.';
      txt.append(el('h3', null, title), el('p', null, sub));
      sum.append(ring, txt);
      body.append(sum);

      if (fs.error && fs.status !== 'paused') {
        const w = el('div', 'warn', fs.error.message);
        const row = el('div', 'row');
        row.style.marginTop = '8px';
        row.append(button('btn sm', 'Open settings', () => this.cb.onSettings()), button('btn sm', 'Retry', () => this.cb.onCheckNow()));
        w.append(row);
        body.append(w);
      }

      if (n) {
        const chips = el('div', 'chips');
        for (const c of Q.CATEGORIES) {
          const k = issues.filter((i) => i.category === c).length;
          if (!k) continue;
          const chip = el('span', 'chip cat-' + c);
          chip.append(el('span', 'dot'), document.createTextNode(`${CAT[c].name} ${k}`));
          chips.append(chip);
        }
        body.append(chips);

        const list = el('div', 'list');
        issues.forEach((i) => {
          const key = Q.issueKey(i) + i.start;
          const card = el('div', 'sg cat-' + i.category);
          card.dataset.key = key;
          card.addEventListener('mouseenter', () => ((this.hoverKey = key), this.applyHighlight()));
          card.addEventListener('mouseleave', () => ((this.hoverKey = null), this.applyHighlight()));
          const top = el('div', 's-top');
          top.append(el('span', 'dot'), el('span', null, i.type), el('span', 's-cat', CAT[i.category].name));
          const row = el('div', 'row');
          row.append(button('btn primary sm', 'Accept', () => this.cb.onAccept(i)), button('btn ghost sm', 'Dismiss', () => this.cb.onDismiss(i)));
          card.append(top, diffNode(i), row);
          list.append(card);
        });
        body.append(list);
        const stick = el('div', 'stick');
        stick.append(button('btn primary', `Accept all (${n})`, () => this.cb.onAcceptAll()));
        body.append(stick);
      } else if (fs.status === 'ok') {
        const e = el('div', 'empty');
        const big = el('div', 'big');
        big.innerHTML = SVG.check;
        e.append(big, el('div', null, 'Nothing to fix. Nice writing!'));
        body.append(e);
      } else if (fs.status !== 'checking' && fs.status !== 'paused' && stats.words >= 2 && (!this.s.checkAsYouType || fs.status === 'idle')) {
        const e = el('div', 'empty');
        e.append(button('btn primary', 'Check now', () => this.cb.onCheckNow()));
        body.append(e);
      }
    }

    paneRewrite(body) {
      const fs = this.fs;
      const rw = fs.rw;
      const sc = this.cb.scopeInfo();
      const scope = el('div', 'scope');
      scope.append(document.createTextNode('Applies to '), el('b', null, sc.sel ? 'your selection' : 'the whole text'), document.createTextNode(` · ${plural(sc.words, 'word')}`));
      body.append(scope);

      const chips = el('div', 'chips');
      Q.resolveTools(this.s.tools)
        .filter((t) => t.name !== Q.TONE)
        .forEach((t) => {
          const c = button('chip tool' + (rw.tool === t.name && !rw.instruction ? ' on' : ''), t.name === Q.FIX ? 'Fix grammar' : t.name, () => this.cb.onTool(t.name));
          chips.append(c);
        });
      body.append(chips);

      const form = el('form', 'ask');
      const input = el('input');
      input.placeholder = 'Or tell Quill what to do…';
      input.value = rw.instruction || '';
      input.maxLength = 300;
      const go = el('button');
      go.type = 'submit';
      go.title = 'Run';
      go.innerHTML = SVG.send;
      form.append(input, go);
      form.addEventListener('submit', (e) => {
        e.preventDefault();
        if (input.value.trim()) this.cb.onInstruction(input.value.trim());
      });
      body.append(form);

      if (rw.error) body.append(el('div', 'warn', rw.error.message));
      if (rw.running || rw.result) {
        const box = el('div', 'result');
        const label = el('div', 'r-label');
        label.append(el('span', null, rw.instruction ? 'Custom' : rw.tool === Q.FIX ? 'Fix grammar' : rw.tool));
        if (rw.tps) label.append(el('span', 'note', `${rw.tps.toFixed(0)} tok/s`));
        box.append(label);
        if (!rw.result) box.append(el('div', 'shimmer'), el('div', 'shimmer'), el('div', 'shimmer'));
        else box.append(el('div', 'r-text' + (rw.running ? ' caret' : ''), rw.result));
        if (!rw.running && rw.result) {
          const row = el('div', 'row');
          row.append(button('btn primary sm', 'Replace', () => this.cb.onApply()), button('btn sm', 'Copy', () => this.cb.onCopy()), button('btn ghost sm', 'Retry', () => this.cb.onRetry()));
          box.append(row);
        } else if (rw.running) {
          const row = el('div', 'row');
          row.append(button('btn sm', 'Stop', () => this.cb.onStop()));
          box.append(row);
        }
        body.append(box);
      } else if (!rw.error) {
        body.append(el('div', 'note', 'Pick a style, or describe the change you want. Nothing is replaced until you press Replace.'));
      }
    }

    paneInsights(body) {
      const fs = this.fs;
      const sc = this.cb.scopeInfo();
      const st = Q.stats(sc.text);
      const fmt = (m) => (m < 1 ? `${Math.max(1, Math.round(m * 60))} sec` : `${Math.round(m * 10) / 10} min`);
      const tiles = el('div', 'tiles');
      const tile = (v, l, cls) => {
        const t = el('div', 'tile' + (cls ? ' ' + cls : ''));
        t.append(el('b', null, String(v)), el('span', null, l));
        return t;
      };
      tiles.append(tile(st.words, 'words'), tile(st.chars, 'characters'), tile(st.sentences, 'sentences'), tile(fmt(st.readMin), 'reading time'), tile(fmt(st.speakMin), 'speaking time'), tile(st.words ? Math.round(st.avgSentence) : 0, 'words / sentence'));
      if (st.flesch != null) {
        const t = el('div', 'tile wide');
        const left = el('div');
        left.append(el('b', null, String(st.flesch)));
        const right = el('div');
        right.append(el('div', null, 'Readability · ' + st.fleschLabel), el('span', null, 'Flesch reading ease, higher is easier'));
        t.append(left, right);
        tiles.append(t);
      }
      body.append(el('div', 'scope', sc.sel ? 'Stats for your selection' : 'Stats for the whole text'), tiles);

      body.append(el('div', 'h4', 'Tone'));
      const tone = fs.tone;
      if (tone.error) body.append(el('div', 'warn', tone.error.message));
      if (tone.running) body.append(el('div', 'shimmer'), el('div', 'shimmer'));
      else if (tone.result) body.append(el('div', 'result r-text info', tone.result));
      const row = el('div', 'row');
      row.style.marginTop = '8px';
      row.append(button('btn sm', tone.result ? 'Check again' : 'Detect tone', () => this.cb.onTone()));
      body.append(row);
    }

    // ---------------------------------------------------- selection toolbar

    showSelbar() {
      if (!this.fs || !this.s.selectionBar || this.panelOpen) return;
      if (!this.selbarBuilt) {
        this.selbarBuilt = true;
        const logo = el('i');
        logo.innerHTML = SVG.leaf;
        this.selbar.append(logo);
        [['Fix', Q.FIX], ['Formal', 'Formal'], ['Friendly', 'Friendly'], ['Shorter', 'Shorter'], ['Clearer', 'Clearer']].forEach(([label, tool]) =>
          this.selbar.append(button(null, label, () => this.cb.onSelectionTool(tool))),
        );
        this.selbar.append(button(null, '…', () => this.cb.onSelectionTool(null), 'More'));
      }
      this.selActive = true;
      this.selbar.classList.remove('hide');
      this.layoutSelbar();
    }

    hideSelbar() {
      this.selActive = false;
      if (this.built) this.selbar.classList.add('hide');
    }

    // -------------------------------------------------------------- toast

    toast(text) {
      if (!this.built || !this.fs) return;
      const t = this.toastEl;
      t.textContent = text;
      t.classList.remove('hide');
      const p = this.badgePos || { x: innerWidth / 2, y: innerHeight - 60, size: 26 };
      t.style.left = clamp(p.x - 120, 8, innerWidth - 220) + 'px';
      t.style.top = Math.max(8, p.y - 44) + 'px';
      clearTimeout(this.timers.toast);
      this.timers.toast = setTimeout(() => t.classList.add('hide'), 1800);
    }

    /** True when [path] (an event's composedPath) goes through Quill's own UI. */
    owns(path) {
      return !!this.host && path.indexOf(this.host) >= 0;
    }
  }

  Q.View = View;
})();
