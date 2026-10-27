/*
 * Lucent control center.
 * The page a phone or laptop opens at http://<tv>:8686 to steer Lucent on the living room TV.
 * Plain JavaScript with no build step. Every word that comes from the TV is set with textContent.
 */
(function () {
  'use strict';

  /* ------------------------------------------------------------------ constants */

  const INK_LIGHT = '#F5F1EA';
  const INK_DARK = '#161514';
  const COLOURS = [INK_LIGHT, '#FF6B8B', '#EC944C', '#F4D06F', '#7FD49B', '#6EC1FF', '#B79CFF', '#FF5E5E'];
  const COLOUR_NAMES = ['Ink', 'Rose', 'Ember', 'Gold', 'Mint', 'Sky', 'Violet', 'Red'];
  const PAPERS = [
    { name: 'Night glass', dark: true },
    { name: 'Cream', dark: false },
    { name: 'Blush', dark: false },
    { name: 'Midnight blue', dark: true }
  ];
  const NIBS = [{ w: 5, name: 'Fine' }, { w: 10, name: 'Pen' }, { w: 18, name: 'Marker' }, { w: 32, name: 'Brush' }];
  const MAX_STROKES = 200;
  const MAX_POINTS = 20000;
  const MAX_RAW_POINTS = 60000;
  const MAX_TEXT = 280;
  const MAX_NOTE_BYTES = 384 * 1024;
  const CAL_COLOURS = ['#EC944C', '#6EC1FF', '#7FD49B', '#B79CFF', '#FF6B8B', '#F4D06F'];
  const TABS = ['home', 'remote', 'draw', 'photos', 'plans', 'settings'];
  const SVGNS = 'http://www.w3.org/2000/svg';
  const PHOTO_ID = /^[0-9a-f]{32}$/;

  /* ------------------------------------------------------------------ safe readers */

  // These make a missing or odd field harmless, so rendering never throws.
  function obj(x) { return x && typeof x === 'object' && !Array.isArray(x) ? x : {}; }
  function arr(x) { return Array.isArray(x) ? x : []; }
  function num(x) { return typeof x === 'number' && isFinite(x) ? x : null; }
  function str(x) { return typeof x === 'string' ? x : (typeof x === 'number' && isFinite(x) ? String(x) : ''); }
  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  function load(key, fallback) {
    try {
      const v = window.localStorage.getItem('lucent.' + key);
      return v === null ? fallback : JSON.parse(v);
    } catch (e) {
      return fallback;
    }
  }
  function save(key, value) {
    try { window.localStorage.setItem('lucent.' + key, JSON.stringify(value)); } catch (e) { /* private mode */ }
  }

  /* ------------------------------------------------------------------ dom helpers */

  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  // Builds an element. Text always goes in as text nodes, never as markup.
  function h(tag, props) {
    const el = document.createElement(tag);
    if (props) setProps(el, props);
    for (let i = 2; i < arguments.length; i++) add(el, arguments[i]);
    return el;
  }
  function setProps(el, props) {
    for (const k of Object.keys(props)) {
      const v = props[k];
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = String(v);
      else if (k === 'on') { for (const ev of Object.keys(v)) el.addEventListener(ev, v[ev]); }
      else if (k === 'vars') { for (const n of Object.keys(v)) el.style.setProperty(n, String(v[n])); }
      else if (k === 'data') { for (const n of Object.keys(v)) el.dataset[n] = String(v[n]); }
      else if (k === 'value' || k === 'disabled' || k === 'maxLength' || k === 'readOnly' || k === 'multiple' || k === 'tabIndex') el[k] = v;
      else if (k === 'style') throw new Error('inline styles are not allowed');
      else el.setAttribute(k, v === true ? '' : String(v));
    }
  }
  function add(el, kid) {
    if (kid === null || kid === undefined || kid === false || kid === '') return;
    if (Array.isArray(kid)) { for (const k of kid) add(el, k); return; }
    el.appendChild(typeof kid === 'object' ? kid : document.createTextNode(String(kid)));
  }

  // Swaps the children of a container and puts focus back on the same control if it had it.
  function fill(container, kids) {
    const a = document.activeElement;
    const key = a && a !== document.body && container.contains(a) ? a.getAttribute('data-fk') : null;
    const frag = document.createDocumentFragment();
    add(frag, kids);
    container.replaceChildren(frag);
    if (key) {
      const t = container.querySelector('[data-fk="' + cssEscape(key) + '"]');
      if (t) t.focus({ preventScroll: true });
    }
  }
  function cssEscape(s) {
    return window.CSS && CSS.escape ? CSS.escape(s) : String(s).replace(/[^a-zA-Z0-9_-]/g, '\\$&');
  }

  function icon(name, cls) {
    const s = document.createElementNS(SVGNS, 'svg');
    s.setAttribute('class', 'ico' + (cls ? ' ' + cls : ''));
    s.setAttribute('aria-hidden', 'true');
    s.setAttribute('focusable', 'false');
    s.setAttribute('viewBox', '0 0 24 24');
    const u = document.createElementNS(SVGNS, 'use');
    u.setAttribute('href', '#i-' + name);
    s.appendChild(u);
    return s;
  }

  /* ------------------------------------------------------------------ state and time */

  let S = {};
  let loaded = false;
  let receivedAt = 0;
  let skew = 0;
  const memo = {};
  const lastNums = {};

  // The TV's clock, which drives countdowns and elapsed timers.
  function tvNow() { return Date.now() + skew; }

  // True when this slice differs from what was last drawn under the same key.
  function changed(key, value) {
    let k;
    try { k = JSON.stringify(value === undefined ? null : value); } catch (e) { k = String(Math.random()); }
    if (memo[key] === k) return false;
    memo[key] = k;
    return true;
  }
  function forgetAll() { for (const k of Object.keys(memo)) delete memo[k]; }

  function settings() { return obj(S.settings); }
  function hour12() {
    const c = str(settings().clock);
    return c === '12' ? true : c === '24' ? false : undefined;
  }
  function fmtTime(ms) {
    if (num(ms) === null || ms <= 0) return '';
    try { return new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', hour12: hour12() }); } catch (e) { return ''; }
  }
  function fmtDate(ms, opts) {
    if (num(ms) === null) return '';
    try { return new Date(ms).toLocaleDateString([], opts || { weekday: 'short', day: 'numeric', month: 'short' }); } catch (e) { return ''; }
  }
  function fmtAgo(ms) {
    if (num(ms) === null || ms <= 0) return '';
    const s = Math.round((tvNow() - ms) / 1000);
    if (s < 10) return 'just now';
    if (s < 60) return s + ' s ago';
    const m = Math.round(s / 60);
    if (m < 60) return m + ' min ago';
    const hr = Math.round(m / 60);
    if (hr < 24) return hr + ' h ago';
    return fmtDate(ms);
  }
  function fmtClock(ms) {
    const t = Math.floor(Math.max(0, ms) / 1000);
    const hh = Math.floor(t / 3600);
    const mm = Math.floor((t % 3600) / 60);
    const ss = t % 60;
    return (hh ? hh + ':' + pad2(mm) : String(mm)) + ':' + pad2(ss);
  }
  function fmtBytes(b) {
    b = num(b) || 0;
    if (b < 1024) return Math.round(b) + ' B';
    const u = ['KB', 'MB', 'GB', 'TB'];
    let i = -1;
    do { b /= 1024; i++; } while (b >= 1024 && i < u.length - 1);
    return (b >= 100 ? Math.round(b) : b.toFixed(1)) + ' ' + u[i];
  }
  function fmtInt(n) { return Math.round(n).toLocaleString(); }
  function plural(n, one, many) { return n + ' ' + (n === 1 ? one : (many || one + 's')); }
  function dayStart(ms, plus) {
    const d = new Date(ms);
    return new Date(d.getFullYear(), d.getMonth(), d.getDate() + (plus || 0)).getTime();
  }
  function hex(c, fallback) { return /^#[0-9a-f]{6}$/i.test(str(c)) ? c : fallback; }
  function inkOn(bg) {
    const v = parseInt(bg.slice(1), 16);
    const lin = function (x) { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
    const L = 0.2126 * lin(v >> 16) + 0.7152 * lin((v >> 8) & 255) + 0.0722 * lin(v & 255);
    return L > 0.36 ? '#141210' : '#FFFFFF';
  }

  // A big serif number that cross fades when its value changes.
  function bigNum(key, value, cls) {
    const text = value === null || value === undefined || value === '' ? '–' : String(value);
    const el = h('span', { class: 'num ' + (cls || ''), text: text });
    if (key) {
      if (lastNums[key] !== undefined && lastNums[key] !== text) el.classList.add('swap');
      lastNums[key] = text;
    }
    return el;
  }
  function agoSpan(ms) { return h('span', { 'data-ago': String(ms), text: fmtAgo(ms) }); }
  function empty(text, cls) { return h('p', { class: 'empty' + (cls ? ' ' + cls : ''), text: text }); }

  /* ------------------------------------------------------------------ theme and toast */

  function applyTheme(mode) {
    const root = document.documentElement;
    if (mode === 'day' || mode === 'night') root.setAttribute('data-theme', mode);
    const dark = mode === 'night' || (mode !== 'day' && window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches);
    for (const m of $$('meta[name="theme-color"]')) m.setAttribute('content', dark ? '#0E0D0C' : '#F6F3EE');
  }

  let toastTimer = 0;
  function toast(msg, kind) {
    const t = $('#toast');
    if (!t || !msg) return;
    t.textContent = msg;
    t.className = 'toast show' + (kind ? ' ' + kind : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = 'toast'; }, 3400);
  }

  /* ------------------------------------------------------------------ network */

  // The TV takes four connections per address and a steady pace, so every request waits
  // for a lane here first. Lane 0 is for state and actions, lane 1 for thumbnails.
  const lane = { active: 0, max: 3, waiting: [[], []], tokens: 20, at: Date.now(), timer: 0 };

  function acquire(prio, cost) {
    return new Promise(function (resolve) {
      lane.waiting[prio ? 1 : 0].push({ resolve: resolve, cost: Math.min(cost || 1, 20) });
      pump();
    });
  }
  function pump() {
    clearTimeout(lane.timer);
    lane.timer = 0;
    const t = Date.now();
    lane.tokens = Math.min(20, lane.tokens + (t - lane.at) * 0.008);
    lane.at = t;
    while (lane.active < lane.max) {
      const q = lane.waiting[0].length ? lane.waiting[0] : lane.waiting[1];
      const next = q[0];
      if (!next) return;
      if (lane.tokens < next.cost) {
        lane.timer = setTimeout(pump, Math.ceil((next.cost - lane.tokens) / 0.008) + 5);
        return;
      }
      q.shift();
      lane.tokens -= next.cost;
      lane.active++;
      let done = false;
      next.resolve(function release() {
        if (done) return;
        done = true;
        lane.active--;
        pump();
      });
    }
  }

  // One request to the TV, with a timeout, the X-Lucent header and the cookie.
  function api(path, opts) {
    opts = opts || {};
    return acquire(opts.prio || 0, opts.cost || 1).then(async function (release) {
      const ctl = new AbortController();
      const timer = setTimeout(function () { ctl.abort(); }, opts.timeout || 10000);
      const onAbort = function () { ctl.abort(); };
      if (opts.signal) {
        if (opts.signal.aborted) ctl.abort();
        else opts.signal.addEventListener('abort', onAbort);
      }
      try {
        const init = {
          method: 'GET',
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { 'X-Lucent': '1' },
          signal: ctl.signal
        };
        if (opts.body !== undefined) {
          init.method = 'POST';
          init.headers['Content-Type'] = 'application/json';
          init.body = JSON.stringify(opts.body);
        }
        const res = await fetch(path, init);
        if (opts.blob && res.ok) return { status: res.status, ok: true, data: await res.blob() };
        let data = {};
        try {
          const text = await res.text();
          data = text ? obj(JSON.parse(text)) : {};
        } catch (e) {
          data = {};
        }
        return { status: res.status, ok: res.ok, data: data };
      } finally {
        clearTimeout(timer);
        if (opts.signal) opts.signal.removeEventListener('abort', onAbort);
        release();
      }
    });
  }

  function netMessage(e) {
    if (e && e.name === 'AbortError') return 'The TV took too long to answer. Try again.';
    return 'Can’t reach the TV. Is this device on the home Wi-Fi?';
  }
  function errorText(status, data) {
    const e = str(obj(data).error);
    if (e === 'full' || status === 507) return 'The TV’s photo storage is full. Delete a few first.';
    if (e === 'album full') return 'The album is full. Delete a few photos first.';
    if (e === 'tv storage low') return 'The TV is low on storage, so it can’t take more photos.';
    switch (status) {
      case 400: return 'The TV didn’t accept that.';
      case 403: return 'The TV refused that request.';
      case 404: return 'That isn’t there any more.';
      case 409: return 'The TV can’t do that right now.';
      case 413: return 'That’s too big for the TV.';
      case 415: return 'The TV can’t use that kind of file.';
      case 429: return 'The TV asked to slow down. Try again in a moment.';
      default: return 'Something went wrong (' + status + ').';
    }
  }

  // Posts an action. Slows down once on 429 and sends the page back to pairing on 401.
  async function act(action, body, opts) {
    opts = opts || {};
    for (let attempt = 0; attempt < 2; attempt++) {
      let r;
      try {
        r = await api('/api/' + action, { body: body || {}, timeout: opts.timeout || 10000 });
      } catch (e) {
        throw new Error(netMessage(e));
      }
      if (r.status === 401) {
        toPairing(str(r.data.error));
        throw new Error('This device is no longer paired.');
      }
      if (r.status === 429 && attempt === 0) {
        await sleep(2000);
        continue;
      }
      if (r.ok) {
        if (!opts.quiet) pollSoon();
        return r.data;
      }
      const err = new Error(opts.errors && opts.errors[r.status] ? opts.errors[r.status] : errorText(r.status, r.data));
      err.status = r.status;
      err.data = r.data;
      throw err;
    }
    throw new Error(errorText(429));
  }

  // The TV's own words for an error when it sent a sentence, else the general message. A bare
  // code such as "key" or 409 is not shown.
  function serverError(e) {
    const said = e && e.data ? str(obj(e.data).error).trim() : '';
    if (said && /\s/.test(said) && !/^\d+$/.test(said)) return said.charAt(0).toUpperCase() + said.slice(1);
    return (e && e.message) || 'That didn’t go through.';
  }

  function setSetting(key, value) {
    return act('set', { key: key, value: value }).then(function (d) {
      const st = settings();
      if (key === 'mbtaKey') st.mbtaKey = value ? 'set' : '';
      else if (key === 'wifiPass') st.wifiSet = !!value;
      else if (key !== 'calendars' && key !== 'countdowns') st[key] = value;
      S.settings = st;
      return d;
    });
  }

  /* ------------------------------------------------------------------ polling */

  const poller = { timer: 0, fails: 0, hiddenAt: 0, stopped: true, inflight: false, again: false, fastUntil: 0 };
  // Polls every 1.5 s for a while, as while Lucent answers a question.
  function pollFast(ms) {
    poller.fastUntil = ms > 0 ? Date.now() + ms : 0;
    if (ms > 0) pollSoon();
  }

  function pollStart() { poller.stopped = false; poller.fails = 0; pollNow(); }
  function pollStop() { poller.stopped = true; clearTimeout(poller.timer); }
  function pollNow() { if (poller.stopped) return; clearTimeout(poller.timer); poller.timer = setTimeout(pollTick, 0); }
  function pollSoon() {
    if (poller.stopped) return;
    if (poller.inflight) { poller.again = true; return; }
    clearTimeout(poller.timer);
    poller.timer = setTimeout(pollTick, 350);
  }

  async function pollTick() {
    if (poller.stopped) return;
    if (poller.inflight) { poller.again = true; return; }
    // Stop asking while the page has been hidden for five minutes. Coming back starts it again.
    if (document.hidden && poller.hiddenAt && Date.now() - poller.hiddenAt > 300000) return;
    poller.inflight = true;
    let next = 4000;
    try {
      const r = await api('/api/state', { timeout: 9000 });
      if (r.status === 401) {
        poller.inflight = false;
        toPairing(str(r.data.error));
        return;
      }
      if (r.status === 429) {
        next = 2000 + Math.random() * 600;
      } else if (r.ok) {
        poller.fails = 0;
        setConn('live');
        applyState(r.data);
        next = document.hidden ? 30000 : Date.now() < poller.fastUntil ? 1500 : 4000;
      } else {
        throw new Error('status ' + r.status);
      }
    } catch (e) {
      poller.fails++;
      setConn(poller.fails > 2 ? 'down' : 'retry');
      next = Math.min(30000, 4000 * Math.pow(2, poller.fails - 1)) + Math.random() * 500;
    }
    poller.inflight = false;
    if (poller.stopped) return;
    if (poller.again) { poller.again = false; next = 300; }
    clearTimeout(poller.timer);
    poller.timer = setTimeout(pollTick, next);
  }

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) {
      poller.hiddenAt = Date.now();
      return;
    }
    const away = poller.hiddenAt ? Date.now() - poller.hiddenAt : 0;
    poller.hiddenAt = 0;
    if (!poller.stopped) {
      pollNow();
      if (away > 60000) keepFresh();
    }
    tick();
  });

  // Asks the TV to fetch live data now and keep it fresh while someone is looking.
  let freshTimer = 0;
  function keepFresh(start) {
    act('refresh', {}).catch(function () { /* quiet */ });
    if (start) {
      clearInterval(freshTimer);
      freshTimer = setInterval(function () {
        if (!document.hidden && !poller.stopped) act('refresh', {}).catch(function () { /* quiet */ });
      }, 100000);
    }
  }

  function setConn(state) {
    const text = state === 'live' ? 'Live' : state === 'retry' ? 'Reconnecting' : 'Can’t reach the TV';
    for (const el of $$('.conn')) {
      el.className = 'conn ' + state;
      const t = $('.conn-text', el);
      if (t && t.textContent !== text) t.textContent = text;
    }
  }

  function applyState(data) {
    S = obj(data);
    loaded = true;
    receivedAt = Date.now();
    const tv = obj(S.tv);
    if (num(tv.now) !== null) skew = tv.now - Date.now();
    askSync();
    applyTheme(str(tv.mode));
    renderUrgent();
    renderNavFoot();
    const v = views[current];
    if (v && v.built) v.render();
  }

  /* ------------------------------------------------------------------ the 1 s ticker */

  function tick() {
    if (document.hidden) return;
    for (const el of $$('[data-ago]')) {
      const t = fmtAgo(+el.dataset.ago);
      if (el.textContent !== t) el.textContent = t;
    }
    for (const el of $$('[data-since]')) el.textContent = fmtClock(tvNow() - +el.dataset.since);
    mediaTick();
    pairLockTick();
  }
  setInterval(tick, 1000);

  /* ------------------------------------------------------------------ small components */

  // A segmented control (up to four options) or a wrap of chips. Optimistic, reverts on error.
  function choice(o) {
    const opts = o.options;
    const chips = opts.length > 4;
    const group = h('div', { class: (chips ? 'chips' : 'seg') + (o.small ? ' seg-sm' : ''), role: 'radiogroup', 'aria-label': o.label });
    const btns = opts.map(function (p) {
      return h('button', { type: 'button', role: 'radio', class: chips ? 'chip-opt' : 'seg-opt', 'aria-checked': 'false', data: { v: p[0] }, text: p[1] });
    });
    add(group, btns);
    if (!chips) group.style.setProperty('--n', String(opts.length));
    let value = null;
    let busy = false;
    function paint(v) {
      value = v === undefined || v === null ? null : String(v);
      let idx = -1;
      btns.forEach(function (b, i) {
        const on = b.dataset.v === value;
        b.setAttribute('aria-checked', on ? 'true' : 'false');
        b.tabIndex = on ? 0 : -1;
        if (on) idx = i;
      });
      if (idx < 0 && btns[0]) btns[0].tabIndex = 0;
      if (!chips) {
        group.style.setProperty('--i', String(Math.max(0, idx)));
        group.classList.toggle('unset', idx < 0);
      }
    }
    async function pick(v) {
      if (v === value || busy) return;
      const prev = value;
      paint(v);
      busy = true;
      group.classList.add('busy');
      try {
        await o.onChange(v);
      } catch (e) {
        busy = false;
        paint(prev);
        toast(e && e.message ? e.message : 'That didn’t go through.', 'alert');
      }
      busy = false;
      group.classList.remove('busy');
    }
    group.addEventListener('click', function (e) {
      const b = e.target.closest('button');
      if (b && group.contains(b)) pick(b.dataset.v);
    });
    group.addEventListener('keydown', function (e) {
      const i = btns.indexOf(document.activeElement);
      if (i < 0) return;
      let j = -1;
      if (e.key === 'ArrowRight' || e.key === 'ArrowDown') j = (i + 1) % btns.length;
      else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') j = (i - 1 + btns.length) % btns.length;
      if (j < 0) return;
      e.preventDefault();
      btns[j].focus();
      pick(btns[j].dataset.v);
    });
    paint(o.value);
    return { el: group, set: function (v) { if (!busy) paint(v); } };
  }

  // An on and off switch. Optimistic, reverts on error.
  function toggle(o) {
    let on = !!o.value;
    let busy = false;
    const sw = h('button', { class: 'switch', type: 'button', role: 'switch', id: o.id, 'aria-checked': on ? 'true' : 'false', 'aria-label': o.label });
    function paint() { sw.setAttribute('aria-checked', on ? 'true' : 'false'); }
    sw.addEventListener('click', async function () {
      if (busy) return;
      const prev = on;
      on = !on;
      paint();
      if (!o.onChange) return;
      busy = true;
      try {
        await o.onChange(on);
      } catch (e) {
        on = prev;
        paint();
        toast(e && e.message ? e.message : 'That didn’t go through.', 'alert');
      }
      busy = false;
    });
    return { el: sw, set: function (v) { if (!busy) { on = !!v; paint(); } }, get value() { return on; } };
  }

  // First tap arms the button, a second tap within a few seconds does it. A button with a
  // data-fk key stays armed when a fresh state redraws it between the two taps.
  const armedKeys = {};
  function confirmTap(btn, prompt, fn) {
    let armed = false;
    let timer = 0;
    const key = btn.getAttribute('data-fk');
    const original = Array.prototype.slice.call(btn.childNodes);
    function arm(ms) {
      armed = true;
      btn.classList.add('armed');
      btn.replaceChildren(document.createTextNode(prompt));
      timer = setTimeout(reset, ms);
    }
    function reset() {
      armed = false;
      clearTimeout(timer);
      if (key) delete armedKeys[key];
      btn.classList.remove('armed');
      btn.replaceChildren.apply(btn, original);
    }
    btn.addEventListener('click', function () {
      if (!armed) {
        if (key) armedKeys[key] = Date.now() + 3500;
        arm(3500);
        return;
      }
      reset();
      fn();
    });
    if (key && armedKeys[key] > Date.now()) arm(armedKeys[key] - Date.now());
  }

  let cardSeq = 0;
  function card(n, title, cls) {
    const id = 'card-' + (++cardSeq);
    const meta = h('div', { class: 'card-meta' });
    const body = h('div', { class: 'card-body' });
    const el = h('section', { class: 'card ' + (cls || ''), 'aria-labelledby': id },
      h('header', { class: 'card-head' },
        h('h2', { class: 'card-title', id: id },
          n ? h('span', { class: 'card-n', text: pad2(n) }) : null,
          n ? h('span', { class: 'card-sep', 'aria-hidden': 'true', text: '·' }) : null,
          h('span', { text: title })),
        h('span', { class: 'card-rule', 'aria-hidden': 'true' }),
        meta),
      body);
    return { el: el, meta: meta, body: body };
  }
  function setMeta(c, kids, cls) {
    c.meta.className = 'card-meta' + (cls ? ' ' + cls : '');
    fill(c.meta, kids);
  }
  // Shimmer lines while the first state is on its way. Takes a card or a plain element.
  function skeleton(c, lines) {
    const kids = [];
    for (let i = 0; i < (lines || 3); i++) kids.push(h('span', { class: 'skel' }));
    fill(c.body || c, h('div', { class: 'skel-wrap', 'aria-hidden': 'true' }, kids));
  }
  function stagger(root) {
    $$('.card', root).forEach(function (c, i) { c.style.setProperty('--i', String(i)); });
  }

  function pageHead(eyebrow, title) {
    const eb = h('p', { class: 'eyebrow page-eyebrow' }, h('img', { class: 'head-mark', src: '/icon.svg', alt: '', width: '20', height: '20' }), h('span', { class: 'page-eyebrow-text', text: eyebrow }));
    const t = h('h1', { class: 'page-title' }, title);
    const conn = h('div', { class: 'conn retry', role: 'status' }, h('span', { class: 'conn-dot', 'aria-hidden': 'true' }), h('span', { class: 'conn-text', text: 'Connecting' }));
    const el = h('header', { class: 'page-head' }, h('div', { class: 'page-head-text' }, eb, t), conn);
    return { el: el, eyebrow: $('.page-eyebrow-text', eb), title: t };
  }

  /* ------------------------------------------------------------------ sheet */

  let sheet = null;
  function openSheet(o) {
    closeSheet(true);
    const prevFocus = document.activeElement;
    const close = h('button', { class: 'btn btn-icon sheet-close', type: 'button', 'aria-label': 'Close', on: { click: function () { closeSheet(); } } }, icon('x'));
    const panel = h('div', { class: 'sheet', role: 'dialog', 'aria-modal': 'true', 'aria-label': o.label, tabindex: '-1' },
      h('div', { class: 'sheet-grip', 'aria-hidden': 'true' }), close, o.content);
    const wrap = h('div', { class: 'sheet-wrap' }, panel);
    wrap.addEventListener('click', function (e) { if (e.target === wrap) closeSheet(); });
    document.body.appendChild(wrap);
    document.documentElement.classList.add('locked');
    const app = $('#app');
    app.setAttribute('aria-hidden', 'true');
    app.inert = true;
    sheet = { wrap: wrap, onClose: o.onClose, prevFocus: prevFocus };
    panel.focus({ preventScroll: true });
    return sheet;
  }
  function closeSheet(instant) {
    if (!sheet) return;
    const s = sheet;
    sheet = null;
    const app = $('#app');
    app.removeAttribute('aria-hidden');
    app.inert = false;
    document.documentElement.classList.remove('locked');
    if (instant) s.wrap.remove();
    else {
      s.wrap.classList.add('closing');
      setTimeout(function () { s.wrap.remove(); }, 280);
    }
    if (s.onClose) s.onClose();
    if (s.prevFocus && s.prevFocus.focus && document.contains(s.prevFocus)) s.prevFocus.focus({ preventScroll: true });
  }
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && sheet) closeSheet();
  });

  /* ------------------------------------------------------------------ fonts */

  // The TV takes at most four connections per device, so the five font files go through the
  // same lane as every other request instead of all at once from CSS.
  const FONTS = [
    ['Newsreader', '/fonts/newsreader-display.ttf', { weight: '400', style: 'normal' }],
    ['Inter', '/fonts/inter-medium.ttf', { weight: '500', style: 'normal' }],
    ['Inter', '/fonts/inter-semibold.ttf', { weight: '600', style: 'normal' }],
    ['Geist Mono', '/fonts/geist-mono.ttf', { weight: '500', style: 'normal' }],
    ['Newsreader', '/fonts/newsreader-italic.ttf', { weight: '400', style: 'italic' }]
  ];
  function loadFonts() {
    if (loadFonts.done || typeof FontFace !== 'function' || !document.fonts) return;
    loadFonts.done = true;
    FONTS.forEach(function (f) {
      acquire(1, 1).then(async function (release) {
        try {
          const face = new FontFace(f[0], 'url(' + f[1] + ') format("truetype")', Object.assign({ display: 'swap' }, f[2]));
          await face.load();
          document.fonts.add(face);
        } catch (e) {
          loadFonts.done = false;
        } finally {
          release();
        }
      });
    });
  }

  /* ------------------------------------------------------------------ boot */

  async function boot() {
    applyTheme('');
    // A QR code from the TV carries a one-time secret after #k=. Read it, then drop it from the address.
    let key = '';
    if (/(^#|&)k=/.test(location.hash || '')) {
      const m = /(?:^#|&)k=([A-Za-z0-9_-]{1,64})/.exec(location.hash);
      if (m) key = m[1];
      try { history.replaceState(null, '', '/'); } catch (e) { /* older browsers */ }
    }
    const retry = $('#boot-retry');
    retry.addEventListener('click', function () { retry.hidden = true; boot(); });
    $('#boot-text').textContent = 'Finding the TV…';
    let hello = null;
    let signedOut = false;
    const first = api('/api/hello', { timeout: 8000 });
    loadFonts();
    for (let attempt = 0; attempt < 3 && !hello; attempt++) {
      try {
        const r = attempt === 0 ? await first : await api('/api/hello', { timeout: 8000 });
        if (r.ok) hello = r.data;
        else if (r.status === 401 && str(r.data.error) === 'reused') { signedOut = true; hello = { paired: false }; }
        else if (r.status === 429) await sleep(2000);
        else break;
      } catch (e) {
        await sleep(1200);
      }
    }
    if (!hello) {
      $('#boot-text').textContent = 'Can’t reach the TV. Make sure this device is on the home Wi-Fi and the TV is on.';
      retry.hidden = false;
      // Keep the secret around so a retry can still use it.
      if (key) boot.key = key;
      return;
    }
    key = key || boot.key || '';
    boot.key = '';
    if (hello.paired === true) startApp();
    else showPairing(key, typeof hello.pairing === 'boolean' ? hello.pairing : null);
    if (signedOut) signedOutMessage();
  }

  /* ------------------------------------------------------------------ pairing proof */

  // BEGIN pairing proof
  // The QR secret never leaves the phone. The page proves it knows the secret with
  // HMAC-SHA256 over a fresh nonce from the TV and a random value of its own. Plain http has
  // no crypto.subtle, so SHA-256 is written out here. The test suite checks it against RFC 4231.
  const SHA_K = (function () {
    const k = new Uint32Array(64);
    let n = 0;
    for (let c = 2; n < 64; c++) {
      let prime = true;
      for (let d = 2; d * d <= c; d++) if (c % d === 0) { prime = false; break; }
      if (!prime) continue;
      k[n++] = Math.floor((Math.cbrt(c) % 1) * 4294967296);
    }
    return k;
  })();
  const SHA_H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];

  function ror(x, n) { return (x >>> n) | (x << (32 - n)); }

  function sha256(bytes) {
    const len = bytes.length;
    const total = ((len + 72) >> 6) << 6;
    const m = new Uint8Array(total);
    m.set(bytes);
    m[len] = 0x80;
    const view = new DataView(m.buffer);
    view.setUint32(total - 8, Math.floor(len / 0x20000000));
    view.setUint32(total - 4, (len << 3) >>> 0);
    const H = Uint32Array.from(SHA_H);
    const W = new Uint32Array(64);
    for (let off = 0; off < total; off += 64) {
      for (let i = 0; i < 16; i++) W[i] = view.getUint32(off + i * 4);
      for (let i = 16; i < 64; i++) {
        const x = W[i - 15], y = W[i - 2];
        W[i] = (W[i - 16] + (ror(x, 7) ^ ror(x, 18) ^ (x >>> 3)) + W[i - 7] + (ror(y, 17) ^ ror(y, 19) ^ (y >>> 10))) >>> 0;
      }
      let a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], hh = H[7];
      for (let i = 0; i < 64; i++) {
        const t1 = (hh + (ror(e, 6) ^ ror(e, 11) ^ ror(e, 25)) + ((e & f) ^ (~e & g)) + SHA_K[i] + W[i]) >>> 0;
        const t2 = ((ror(a, 2) ^ ror(a, 13) ^ ror(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) >>> 0;
        hh = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
      }
      H[0] = (H[0] + a) >>> 0; H[1] = (H[1] + b) >>> 0; H[2] = (H[2] + c) >>> 0; H[3] = (H[3] + d) >>> 0;
      H[4] = (H[4] + e) >>> 0; H[5] = (H[5] + f) >>> 0; H[6] = (H[6] + g) >>> 0; H[7] = (H[7] + hh) >>> 0;
    }
    const out = new Uint8Array(32);
    const ov = new DataView(out.buffer);
    for (let i = 0; i < 8; i++) ov.setUint32(i * 4, H[i]);
    return out;
  }

  function hmacSha256(key, msg) {
    if (key.length > 64) key = sha256(key);
    const inner = new Uint8Array(64 + msg.length);
    const outer = new Uint8Array(64 + 32);
    for (let i = 0; i < 64; i++) {
      const k = i < key.length ? key[i] : 0;
      inner[i] = k ^ 0x36;
      outer[i] = k ^ 0x5c;
    }
    inner.set(msg, 64);
    outer.set(sha256(inner), 64);
    return sha256(outer);
  }

  function utf8(text) { return new TextEncoder().encode(text); }
  function toHex(bytes) { let s = ''; for (const b of bytes) s += (b < 16 ? '0' : '') + b.toString(16); return s; }

  // n random bytes as base64url with no padding. getRandomValues works on plain http.
  function randomToken(n) {
    const bytes = new Uint8Array(n);
    crypto.getRandomValues(bytes);
    let bin = '';
    for (const b of bytes) bin += String.fromCharCode(b);
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  function pairProof(k, nonce, c, name) {
    return toHex(hmacSha256(utf8(k), utf8('lucent-pair:' + nonce + ':' + c + ':' + name)));
  }
  // END pairing proof

  // Checks the hash against RFC 4231 test case 2 once, so a broken engine falls back to the PIN.
  let proofOk = null;
  function proofWorks() {
    if (proofOk === null) {
      try {
        proofOk = typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function' && typeof TextEncoder === 'function' &&
          toHex(hmacSha256(utf8('Jefe'), utf8('what do ya want for nothing?'))) === '5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843';
      } catch (e) {
        proofOk = false;
      }
    }
    return proofOk;
  }

  // Asks the TV for its current nonce and signs it with the key from the QR code. Null when no pairing window is open.
  async function keyProof(name) {
    if (!proofWorks()) throw new Error('no proof');
    let r = await api('/api/hello', { timeout: 8000 });
    // A signed out key gets one 401 that clears the cookie. Ask again for the nonce.
    if (r.status === 401) r = await api('/api/hello', { timeout: 8000 });
    const nonce = r.ok ? str(r.data.nonce) : '';
    if (!nonce) return null;
    const c = randomToken(16);
    return { proof: pairProof(pairing.key, nonce, c, name), c: c, name: name };
  }

  /* ------------------------------------------------------------------ pairing */

  const pairing = { step: 'code', key: '', pin: '', name: '', gen: 0, busy: false, lockUntil: 0, open: null, msg: '', kind: '', helloTimer: 0, ctl: null, match: '' };

  function guessName() {
    const ua = navigator.userAgent || '';
    if (/iPhone|iPod/.test(ua)) return 'iPhone';
    if (/iPad/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return 'iPad';
    if (/Android/.test(ua)) return /Mobile/.test(ua) ? 'Android phone' : 'Android tablet';
    if (/Macintosh|Mac OS X/.test(ua)) return 'Mac';
    if (/Windows/.test(ua)) return 'Windows PC';
    if (/CrOS/.test(ua)) return 'Chromebook';
    if (/Linux/.test(ua)) return 'Linux PC';
    return 'Browser';
  }
  function deviceNoun() {
    const g = guessName();
    if (g === 'iPhone' || g === 'Android phone') return 'phone';
    if (g === 'iPad') return 'iPad';
    if (g === 'Android tablet') return 'tablet';
    if (g === 'Browser') return 'device';
    return 'computer';
  }

  function showPairing(key, open) {
    stopApp();
    $('#boot').hidden = true;
    $('#app').hidden = true;
    $('#pair').hidden = false;
    document.title = 'Pair · Lucent';
    pairing.key = key || '';
    pairing.step = key ? 'key' : 'code';
    pairing.open = open === undefined ? null : open;
    pairing.pin = '';
    pairing.busy = false;
    if (!key) setPairMsg('', '');
    if (!pairing.name) pairing.name = load('name', '') || guessName();
    renderPairing();
    scheduleHello(key ? 8000 : 4000);
  }
  // A 401 on a paired page. "reused" means the TV saw this key used from two places and signed it out.
  function toPairing(reason) {
    if (!$('#pair').hidden) {
      if (reason === 'reused') signedOutMessage();
      return;
    }
    showPairing('', null);
    if (reason === 'reused') { signedOutMessage(); return; }
    setPairMsg('This device isn’t paired with the TV any more. Pair it again to carry on.', 'warn');
    renderPairing();
  }
  // Signed out by the TV: forget what this page kept on the device and say so.
  function signedOutMessage() {
    forgetLocal();
    setPairMsg('This ' + deviceNoun() + ' was signed out. Pair it again from the TV.', 'alert');
    if (pairing.step === 'wait' || pairing.step === 'done') return;
    renderPairing();
  }
  function forgetLocal() {
    for (const k of ['draft', 'tab', 'paper']) {
      try { window.localStorage.removeItem('lucent.' + k); } catch (e) { /* nothing stored */ }
    }
    const d = views.draw;
    if (d && d.built) {
      d.text.value = '';
      d.pad.reset();
    }
  }

  function scheduleHello(ms) {
    clearTimeout(pairing.helloTimer);
    pairing.helloTimer = setTimeout(helloCheck, ms);
  }
  async function helloCheck() {
    if ($('#pair').hidden) return;
    if (pairing.step === 'wait' || pairing.step === 'done' || document.hidden) { scheduleHello(5000); return; }
    try {
      const r = await api('/api/hello', { timeout: 8000 });
      if (r.status === 401 && str(r.data.error) === 'reused') signedOutMessage();
      if (r.ok) {
        if (r.data.paired === true) { startApp(); return; }
        const open = typeof r.data.pairing === 'boolean' ? r.data.pairing : null;
        if (open !== pairing.open) {
          pairing.open = open;
          const line = $('#pair-status');
          if (line) line.replaceWith(pairStatusLine());
        }
      }
    } catch (e) { /* try again shortly */ }
    scheduleHello(5000);
  }

  function setPairMsg(msg, kind) { pairing.msg = msg; pairing.kind = kind; }
  function showPairMsg() {
    const el = $('#pair-msg');
    if (!el) return;
    el.textContent = pairing.msg;
    el.className = 'pair-msg ' + (pairing.kind || '');
  }

  function renderPairing() {
    const root = $('#pair');
    const noun = deviceNoun();
    let body;
    if (pairing.step === 'wait') body = pairWaitView(noun);
    else if (pairing.step === 'done') body = pairDoneView(noun);
    else body = pairFormView(noun);
    fill(root, h('div', { class: 'pair-card card step-' + pairing.step }, body));
    if (pairing.step === 'code' && !pairing.busy && !document.activeElement.closest('#pair') && !('ontouchstart' in window)) {
      const pin = $('#pin');
      if (pin && !pin.disabled) pin.focus({ preventScroll: true });
    }
  }

  function pairStatusLine() {
    if (pairing.open === null || (pairing.step !== 'key' && Date.now() < pairing.lockUntil)) return h('p', { class: 'pair-status', id: 'pair-status' });
    return h('p', { class: 'pair-status ' + (pairing.open ? 'open' : 'shut'), id: 'pair-status' },
      h('span', { class: 'pair-status-dot', 'aria-hidden': 'true' }),
      pairing.open ? 'Pairing is open on the TV right now' : 'Pairing isn’t open on the TV yet');
  }

  function pairFormView(noun) {
    const isKey = pairing.step === 'key';
    const locked = !isKey && Date.now() < pairing.lockUntil;
    const nameInput = h('input', { class: 'input', id: 'pair-name', 'data-fk': 'pair-name', type: 'text', autocomplete: 'off', autocapitalize: 'words', maxLength: 40, value: pairing.name, enterkeyhint: isKey ? 'go' : 'next', spellcheck: 'false' });
    nameInput.addEventListener('input', function () { pairing.name = nameInput.value; });
    const pin = isKey ? null : pinInput(function () { pairSubmit(); });
    const btn = h('button', { class: 'btn btn-primary btn-lg btn-block' + (pairing.busy ? ' busy' : ''), type: 'submit', 'data-fk': 'pair-go', disabled: locked || pairing.busy },
      pairing.busy ? 'Asking the TV…' : (isKey ? 'Pair this ' + noun : 'Pair'));
    const form = h('form', { class: 'pair-form', novalidate: true },
      h('div', { class: 'field' }, h('label', { class: 'label', for: 'pair-name', text: 'Name this ' + noun }), nameInput),
      pin ? h('div', { class: 'field' }, h('label', { class: 'label', for: 'pin', text: 'The 6-digit code on the TV' }), pin.el) : null,
      h('p', { class: 'pair-msg ' + (pairing.kind || ''), id: 'pair-msg', role: 'alert', text: pairing.msg }),
      btn);
    form.addEventListener('submit', function (e) { e.preventDefault(); pairSubmit(); });
    if (pin) {
      pin.input.value = pairing.pin;
      pin.input.disabled = locked || pairing.busy;
      pin.sync(false);
    }
    return [
      h('img', { class: 'pair-mark', src: '/icon.svg', alt: '', width: '76', height: '76' }),
      h('p', { class: 'eyebrow', text: 'Lucent · Control center' }),
      h('h1', { class: 'pair-title' }, 'Pair this ', h('em', { text: noun })),
      h('p', { class: 'pair-lede', text: isKey
        ? 'You scanned the code on the TV. Give this ' + noun + ' a name so you can tell it apart later.'
        : 'On the TV, open Settings and choose Pair a phone. Then type the six digits it shows.' }),
      form,
      isKey ? h('button', { class: 'btn btn-quiet btn-block pair-alt', type: 'button', on: { click: function () { pairing.key = ''; pairing.step = 'code'; setPairMsg('', ''); renderPairing(); } } }, 'Type the code instead') : null,
      pairStatusLine()
    ];
  }

  function pinInput(onFull) {
    const input = h('input', { class: 'pin-input', id: 'pin', 'data-fk': 'pin', type: 'text', inputmode: 'numeric', pattern: '[0-9]*', autocomplete: 'one-time-code', maxLength: 6, enterkeyhint: 'go', spellcheck: 'false', 'aria-describedby': 'pair-msg' });
    const cells = [];
    for (let i = 0; i < 6; i++) cells.push(h('span', { class: 'pin-cell' + (i === 2 ? ' gap' : ''), 'aria-hidden': 'true' }));
    const wrap = h('div', { class: 'pin' }, h('div', { class: 'pin-cells' }, cells), input);
    function sync(fire) {
      const v = input.value.replace(/\D/g, '').slice(0, 6);
      if (v !== input.value) input.value = v;
      const focused = document.activeElement === input;
      cells.forEach(function (c, i) {
        c.textContent = v[i] || '';
        c.classList.toggle('filled', i < v.length);
        c.classList.toggle('current', focused && i === Math.min(v.length, 5));
      });
      const was = pairing.pin;
      pairing.pin = v;
      if (fire && v.length === 6 && was.length !== 6) onFull();
    }
    input.addEventListener('input', function () {
      if (pairing.msg && pairing.kind !== 'lock') { setPairMsg('', ''); showPairMsg(); }
      sync(true);
    });
    input.addEventListener('focus', function () { sync(false); });
    input.addEventListener('blur', function () { sync(false); });
    return { el: wrap, input: input, sync: sync };
  }

  async function pairSubmit() {
    const isKey = pairing.step === 'key' && !!pairing.key;
    // A lock turns off the six-digit code only. Scanning the QR code still works.
    if (pairing.busy || (!isKey && Date.now() < pairing.lockUntil)) return;
    const name = (pairing.name || '').trim().slice(0, 40) || guessName();
    if (!isKey && !/^\d{6}$/.test(pairing.pin)) {
      setPairMsg('Type all six digits from the TV.', 'warn');
      showPairMsg();
      return;
    }
    pairing.busy = true;
    pairing.name = name;
    setPairMsg('', '');
    renderPairing();
    let body = { pin: pairing.pin, name: name };
    let r;
    try {
      for (let attempt = 0; attempt < 2; attempt++) {
        if (isKey) {
          body = await keyProof(name);
          if (!body) {
            pairing.busy = false;
            pairing.open = false;
            setPairMsg('Pairing isn’t open on the TV. On the TV, open Settings and choose Pair a phone, then try again.', 'warn');
            renderPairing();
            return;
          }
        }
        r = await api('/api/pair', { body: body, timeout: 12000 });
        // A wrong proof costs the window nothing, so try once more with a fresh nonce and c.
        if (!(isKey && r.status === 403 && str(r.data.error) === 'wrong')) break;
      }
    } catch (e) {
      if (e && e.message === 'no proof') {
        pairing.busy = false;
        pairing.key = '';
        pairing.step = 'code';
        setPairMsg('This browser can’t use the QR code. Type the 6-digit code from the TV instead.', 'warn');
        renderPairing();
        return;
      }
      pairing.busy = false;
      setPairMsg(netMessage(e), 'alert');
      renderPairing();
      return;
    }
    pairing.busy = false;
    if (r.status === 202 && str(r.data.wait)) {
      save('name', name);
      pairing.match = /^\d{1,6}$/.test(str(r.data.match)) ? str(r.data.match) : '';
      pairWait(str(r.data.wait));
      return;
    }
    const code = str(r.data.error);
    if (r.status === 429) {
      pairing.pin = '';
      setPairMsg('That was quick. Wait two seconds and try again.', 'warn');
    } else if (r.status === 403 && code === 'locked') {
      pairing.lockUntil = Date.now() + (num(r.data.lockedFor) || 15 * 60000);
      pairing.pin = '';
      setPairMsg(lockText(), 'lock');
    } else if (r.status === 403 && code === 'closed') {
      pairing.open = false;
      pairing.pin = '';
      if (isKey) { pairing.key = ''; pairing.step = 'code'; }
      setPairMsg('Pairing isn’t open on the TV. On the TV, open Settings and choose Pair a phone, then try again.', 'warn');
    } else if (r.status === 403 && code === 'wrong') {
      pairing.pin = '';
      if (isKey) {
        pairing.key = '';
        pairing.step = 'code';
        setPairMsg('That QR code has expired. Type the 6-digit code from the TV instead.', 'warn');
      } else {
        setPairMsg('That code didn’t match. Check the TV and try again.', 'alert');
      }
    } else if (r.status === 400) {
      pairing.pin = '';
      setPairMsg('The TV didn’t accept that. Check the code and try again.', 'alert');
    } else {
      pairing.pin = '';
      setPairMsg(errorText(r.status, r.data), 'alert');
    }
    renderPairing();
  }

  function lockText() {
    const left = pairing.lockUntil - Date.now();
    if (left <= 0) return 'You can try again now.';
    return 'Too many tries, so the code is off for ' + fmtClock(left) + '. Scan the QR code on the TV instead, or try again then.';
  }
  function pairLockTick() {
    if (!pairing.lockUntil || $('#pair').hidden || pairing.kind !== 'lock') return;
    if (Date.now() >= pairing.lockUntil) {
      pairing.lockUntil = 0;
      setPairMsg('You can try again now. Open Pair a phone on the TV for a fresh code.', 'ok');
      renderPairing();
      return;
    }
    pairing.msg = lockText();
    showPairMsg();
  }

  async function pairWait(id) {
    const gen = ++pairing.gen;
    pairing.step = 'wait';
    renderPairing();
    let fails = 0;
    while (gen === pairing.gen) {
      pairing.ctl = new AbortController();
      let r;
      try {
        r = await api('/api/pair/wait', { body: { id: id }, timeout: 32000, signal: pairing.ctl.signal });
        fails = 0;
      } catch (e) {
        if (gen !== pairing.gen) return;
        if (++fails > 4) {
          pairing.step = 'code';
          setPairMsg(netMessage(e), 'alert');
          renderPairing();
          return;
        }
        await sleep(1500);
        continue;
      }
      if (gen !== pairing.gen) return;
      if (r.status === 200 && r.data.ok) {
        pairing.step = 'done';
        renderPairing();
        await sleep(1100);
        if (gen === pairing.gen) startApp();
        return;
      }
      if (r.status === 202) continue;
      if (r.status === 429) { await sleep(2000); continue; }
      pairing.key = '';
      pairing.step = 'code';
      pairing.pin = '';
      setPairMsg('The TV didn’t allow it, or nobody answered in time. Try again.', 'alert');
      renderPairing();
      return;
    }
  }
  function cancelPairWait() {
    pairing.gen++;
    if (pairing.ctl) pairing.ctl.abort();
    pairing.step = pairing.key ? 'key' : 'code';
    setPairMsg('', '');
    renderPairing();
  }

  function pairWaitView(noun) {
    const match = pairing.match;
    const asking = h('p', { class: 'pair-lede' }, 'The TV is asking ', h('span', { class: 'strong', text: '“Allow ' + pairing.name + '?”' }));
    const cancel = h('button', { class: 'btn btn-quiet btn-block', type: 'button', on: { click: cancelPairWait } }, 'Cancel');
    if (match) {
      return [
        h('p', { class: 'eyebrow', text: 'One more step' }),
        h('div', { class: 'match', 'aria-hidden': 'true' }, match.split('').map(function (d) { return h('span', { class: 'match-digit', text: d }); })),
        h('h1', { class: 'pair-title pair-title-sm', role: 'status', text: 'Check the TV shows ' + match + ', then press OK on the remote' }),
        asking,
        cancel
      ];
    }
    return [
      h('div', { class: 'wait-rings', 'aria-hidden': 'true' },
        h('span', { class: 'ring' }), h('span', { class: 'ring' }), h('span', { class: 'ring' }),
        h('span', { class: 'wait-ok', text: 'OK' })),
      h('p', { class: 'eyebrow', text: 'One more step' }),
      h('h1', { class: 'pair-title pair-title-sm', role: 'status', text: 'Press OK on the remote to allow this ' + noun }),
      asking,
      cancel
    ];
  }
  function pairDoneView() {
    return [
      h('div', { class: 'done-mark', 'aria-hidden': 'true' }, checkGlyph()),
      h('p', { class: 'eyebrow', text: 'Paired' }),
      h('h1', { class: 'pair-title pair-title-sm', role: 'status' }, 'Welcome ', h('em', { text: 'home' })),
      h('p', { class: 'pair-lede', text: 'Opening the control center…' })
    ];
  }
  function checkGlyph() {
    const s = document.createElementNS(SVGNS, 'svg');
    s.setAttribute('viewBox', '0 0 64 64');
    s.setAttribute('class', 'check-glyph');
    const c = document.createElementNS(SVGNS, 'circle');
    c.setAttribute('cx', '32'); c.setAttribute('cy', '32'); c.setAttribute('r', '29');
    c.setAttribute('class', 'check-ring');
    const p = document.createElementNS(SVGNS, 'path');
    p.setAttribute('d', 'M20 33.5l8 8 16.5-17');
    p.setAttribute('class', 'check-tick');
    s.appendChild(c);
    s.appendChild(p);
    return s;
  }

  /* ------------------------------------------------------------------ app shell */

  let appBuilt = false;
  let current = 'home';
  const views = {};

  function startApp() {
    clearTimeout(pairing.helloTimer);
    pairing.gen++;
    $('#boot').hidden = true;
    $('#pair').hidden = true;
    fill($('#pair'), null);
    $('#app').hidden = false;
    document.title = 'Lucent';
    if (!appBuilt) buildShell();
    forgetAll();
    show(load('tab', 'home'), true);
    pollStart();
    keepFresh(true);
  }
  function stopApp() {
    pollStop();
    clearInterval(freshTimer);
    closeSheet(true);
    dropThumbs();
    S = {};
    loaded = false;
    forgetAll();
    for (const k of Object.keys(lastNums)) delete lastNums[k];
  }

  function buildShell() {
    appBuilt = true;
    for (const b of $$('.tab')) b.addEventListener('click', function () { show(b.dataset.tab); });
    document.addEventListener('keydown', function (e) {
      if (current === 'draw' && (e.metaKey || e.ctrlKey) && !e.shiftKey && e.key.toLowerCase() === 'z' && views.draw.pad && !/INPUT|TEXTAREA/.test(document.activeElement.tagName)) {
        e.preventDefault();
        views.draw.pad.undo();
      }
    });
  }

  function show(tab) {
    if (TABS.indexOf(tab) < 0) tab = 'home';
    const switching = tab !== current;
    current = tab;
    save('tab', tab);
    for (const t of TABS) $('#v-' + t).hidden = t !== tab;
    for (const b of $$('.tab')) {
      if (b.dataset.tab === tab) b.setAttribute('aria-current', 'page');
      else b.removeAttribute('aria-current');
    }
    const view = views[tab];
    if (!view.built) { view.build(); view.built = true; }
    view.render();
    const el = $('#v-' + tab);
    el.classList.remove('enter');
    void el.offsetWidth;
    el.classList.add('enter');
    if (switching) window.scrollTo(0, 0);
    if (view.shown) view.shown();
    setConn(poller.fails ? (poller.fails > 2 ? 'down' : 'retry') : (loaded ? 'live' : 'retry'));
  }

  function renderNavFoot() {
    const tv = obj(S.tv);
    if (!changed('navfoot', [tv.name, tv.version, str(obj(S.me).name)])) return;
    fill($('#nav-foot'), [
      h('p', { class: 'nav-tv', text: str(tv.name) || 'Lucent' }),
      h('p', { class: 'eyebrow' }, 'Version ' + (str(tv.version) || '–')),
      str(obj(S.me).name) ? h('p', { class: 'nav-me', text: 'This is ' + str(obj(S.me).name) }) : null,
      h('div', { class: 'conn live', role: 'status' }, h('span', { class: 'conn-dot', 'aria-hidden': 'true' }), h('span', { class: 'conn-text', text: 'Live' }))
    ]);
  }

  /* ------------------------------------------------------------------ bike finder and the alarm */

  function ringing() { return obj(S.finder).alarm === true || obj(S.tv).ringing === true; }

  function renderUrgent() {
    const f = obj(S.finder);
    const on = ringing();
    const slot = $('#urgent');
    if (!changed('urgent', [on, f.ebikes, f.station, f.ended, hour12()])) return;
    document.title = on ? 'E-bike docked · Lucent' : 'Lucent';
    if (!on) {
      slot.replaceChildren();
      slot.hidden = true;
      return;
    }
    slot.hidden = false;
    const n = num(f.ebikes) || 1;
    const station = str(f.station) || str(obj(S.bikes).label) || 'Westgate';
    fill(slot, h('section', { class: 'urgent', role: 'alert' },
      h('div', { class: 'urgent-rings', 'aria-hidden': 'true' }, h('span', { class: 'ring' }), h('span', { class: 'ring' }), icon('bike', 'urgent-ico')),
      h('div', { class: 'urgent-text' },
        h('p', { class: 'eyebrow urgent-eyebrow', text: 'Bike finder · Ringing on the TV' }),
        h('h2', { class: 'urgent-title' }, 'An e-bike just docked at ', h('em', { text: station })),
        h('p', { class: 'urgent-sub', text: plural(n, 'e-bike') + ' ready to ride' + (num(f.ended) ? ' · found at ' + fmtTime(f.ended) : '') })),
      h('button', { class: 'btn btn-lg urgent-btn', type: 'button', 'data-fk': 'silence', on: { click: function () { finderDo('silence'); } } }, icon('bell-off'), 'Silence alarm')));
    try {
      if (navigator.vibrate && navigator.userActivation && navigator.userActivation.hasBeenActive) navigator.vibrate([180, 90, 180]);
    } catch (e) { /* no vibration here */ }
  }

  async function finderDo(kind) {
    const f = Object.assign({}, obj(S.finder));
    const forMin = parseInt(str(settings().findFor), 10) || 30;
    if (kind === 'start') {
      const w = watched(obj(S.bikes));
      Object.assign(f, { state: 'searching', started: tvNow(), deadline: tvNow() + forMin * 60000, checks: 0, alarm: false, offline: false,
        station: w.alt ? w.label + ' and ' + w.altLabel : w.label, ebikes2: w.alt ? Math.max(0, num(w.alt.ebikes) || 0) : -1 });
    }
    if (kind === 'stop') Object.assign(f, { state: 'stopped', ended: tvNow() });
    if (kind === 'silence') { f.alarm = false; if (S.tv) S.tv.ringing = false; }
    S.finder = f;
    renderUrgent();
    if (current === 'home' && views.home.built) views.home.render();
    try {
      const d = await act('finder', { do: kind });
      if (kind === 'start' && str(d.note)) toast(str(d.note));
      else if (kind === 'silence') toast('Alarm silenced');
    } catch (e) {
      toast(e.message, 'alert');
      pollNow();
    }
  }

  /* ------------------------------------------------------------------ home */

  views.home = {
    built: false,
    build: function () {
      const v = $('#v-home');
      this.head = pageHead('', '');
      this.alerts = h('div', { class: 'alerts span-12', role: 'region', 'aria-label': 'Weather alerts' });
      this.c = {
        transit: card(1, 'Transit', 'span-7 card-transit'),
        bikes: card(2, 'Bluebikes', 'span-5 card-bikes'),
        weather: card(3, 'Weather', 'span-7 card-weather'),
        daylight: card(4, 'Sun & air', 'span-5 card-daylight'),
        sky: card(5, 'Overhead', 'span-7 card-sky'),
        next: card(6, 'Up next', 'span-5 card-next'),
        media: card(7, 'On the TV', 'span-6 card-media'),
        room: card(8, 'Living room', 'span-6 card-room')
      };
      const c = this.c;
      this.ask = buildAsk();
      const grid = h('div', { class: 'grid' }, this.ask.el, this.alerts, Object.keys(c).map(function (k) { return c[k].el; }));
      stagger(grid);
      fill(v, [this.head.el, grid]);
      this.room = buildRoom(c.room);
      this.mediaMain = h('div', { class: 'media-main' });
      this.glare = h('div', { class: 'glare-wrap', hidden: true });
      this.volRow = homeVolumeRow();
      fill(c.media.body, [this.mediaMain, this.volRow, this.glare, this.room.tvRow]);
    },
    render: function () {
      const c = this.c;
      const tv = obj(S.tv);
      const city = str(obj(S.weather).city) || 'Cambridge';
      const eyebrow = fmtDate(tvNow(), { weekday: 'long', month: 'long', day: 'numeric' }) + ' · ' + city;
      if (this.head.eyebrow.textContent !== eyebrow) this.head.eyebrow.textContent = eyebrow;
      const who = personName();
      if (changed('h.title', [greeting(), who])) fill(this.head.title, [greeting() + (who ? ', ' : ''), who ? h('em', { text: who }) : null]);
      if (!loaded) {
        for (const k of Object.keys(c)) if (k !== 'room' && k !== 'media') skeleton(c[k], k === 'transit' ? 4 : 3);
        skeleton(this.mediaMain, 3);
        return;
      }
      const st = settings();
      this.ask.render();
      if (changed('h.alerts', [S.alerts, hour12()])) renderAlerts(this.alerts, arr(S.alerts));
      if (changed('h.transit', [S.transit])) renderTransit(c.transit, obj(S.transit));
      if (changed('h.bikes', [S.bikes, S.finder, S.forecast, st.findFor, hour12(), dayStart(tvNow())])) renderBikes(c.bikes, obj(S.bikes), obj(S.finder), S.forecast);
      if (changed('h.weather', [S.weather, st.units])) renderWeather(c.weather, obj(S.weather), str(st.units));
      if (changed('h.daylight', [tv.sunrise, tv.sunset, tv.moon, tv.moonLit, tv.nextChange, tv.mode, tv.modeSetting, S.air, hour12()])) renderDaylight(c.daylight, tv, obj(S.air));
      if (changed('h.sky', [S.sky, st.sky])) renderSky(c.sky, obj(S.sky), str(st.sky));
      if (changed('h.next', [obj(S.calendar).events, obj(S.calendar).countdowns, dayStart(tvNow()), hour12()])) renderNext(c.next, obj(S.calendar));
      const m = obj(S.media);
      if (changed('h.media', [m.active, m.app, m.title, m.artist, m.album, m.playing, m.duration, m.canNext, m.canPrev, S.audio])) renderMedia(c.media, m, obj(S.audio), this.mediaMain);
      mediaSync(m);
      if (changed('h.glare', [S.samsung, obj(S.display).advice])) renderGlare(this.glare, S.samsung, obj(S.display));
      // The TV buttons sit in whichever card is shorter, so the two cards stay level on wide screens.
      const host = obj(S.samsung).connected === true ? c.room.body : c.media.body;
      if (this.room.tvRow.parentNode !== host) host.appendChild(this.room.tvRow);
      this.room.render(tv);
    }
  };

  // One tap from Home: the Fire TV's own volume keys, which the soundbar follows, and the Remote tab.
  function homeVolumeRow() {
    const b = function (what, ico, text, label) {
      return pressable(h('button', { class: 'btn vol-btn', type: 'button', 'aria-label': label, 'data-fk': 'hv-' + what }, icon(ico), h('span', { text: text })),
        { fire: function (btn) { volumePress(btn, what); }, repeat: what === 'mute' ? null : function () { return true; } });
    };
    const open = h('button', { class: 'btn vol-btn', type: 'button', 'data-fk': 'hv-remote', on: { click: function () { show('remote'); } } }, icon('remote'), h('span', { text: 'Remote' }));
    return h('div', { class: 'room-row vol-row' }, h('p', { class: 'room-label', text: 'TV volume' }),
      h('div', { class: 'vol-btns' }, b('down', 'vol-down', 'Down', 'Volume Down'), b('mute', 'mute', 'Mute', 'Mute'), b('up', 'vol-up', 'Up', 'Volume Up'), open));
  }

  /* ask Lucent: questions go to Claude on the TV, answers come back in the state */

  const ASK_EXAMPLES = ['Play Better Call Saul', 'When will the e-bikes run out?', 'Every weekday at 7:15 start the bike finder', 'What’s the famous brass town in India?'];
  // The question this page sent and is waiting on. The answer replaces the queued one under a new
  // id, so it is found in the history by its question and a time at or after the asking.
  const asking = { pending: null, error: '', busy: false, fresh: '' };

  function assistantState() {
    const a = S.assistant;
    return a && typeof a === 'object' && !Array.isArray(a) ? a : null;
  }
  function sameQuestion(a, b) { return str(a).replace(/\s+/g, ' ').trim().toLowerCase() === str(b).replace(/\s+/g, ' ').trim().toLowerCase(); }

  // Each new state: has the question been answered, or did the TV stop answering it?
  function askSync() {
    const p = asking.pending;
    if (!p || !p.replied) return;
    const a = obj(assistantState());
    const hit = arr(a.history).map(obj).find(function (x) { return sameQuestion(x.question, p.question) && (num(x.at) || 0) >= p.at && x.busy !== true; });
    const age = Date.now() - p.since;
    if (hit) {
      asking.pending = null;
      asking.fresh = str(hit.id) || String(hit.at);
      pollFast(0);
      return;
    }
    const busy = !!a.asking && typeof a.asking === 'object';
    // A queued question shows up in the history. Without a queued answer, or after 90 s, stop waiting.
    if (!busy && (!p.queued || age > 90000)) {
      asking.pending = null;
      pollFast(0);
      if (p.queued) asking.error = 'No answer came back. Try again.';
    }
  }

  async function askSend(raw) {
    const ask = views.home.ask;
    const text = str(raw).trim().slice(0, 500);
    if (!text) { ask.input.focus(); return; }
    if (asking.busy || asking.pending) return;
    asking.busy = true;
    asking.error = '';
    asking.fresh = '';
    asking.pending = { question: text, at: tvNow() - 2000, since: Date.now(), queued: false, replied: false };
    ask.render(true);
    try {
      const d = await act('ask', { text: text }, { timeout: 15000 });
      const p = asking.pending;
      if (p) {
        if (str(d.question)) p.question = str(d.question);
        if (num(d.at)) p.at = d.at;
        p.queued = d.busy === true;
        p.replied = true;
      }
      ask.input.value = '';
      pollFast(90000);
    } catch (e) {
      asking.pending = null;
      asking.error = serverError(e);
    }
    asking.busy = false;
    ask.render(true);
  }

  function openClaudeSettings() {
    show('settings');
    requestAnimationFrame(function () {
      const target = $('#set-claude');
      if (target) target.scrollIntoView({ block: 'start' });
      const key = $('#claude-key');
      if (key && !key.closest('[hidden]')) key.focus({ preventScroll: true });
    });
  }

  // "about 4¢" from millionths of a dollar.
  function costText(cost) {
    cost = num(cost);
    if (cost === null || cost <= 0) return '';
    const cents = cost / 10000;
    if (cents < 1) return 'under 1¢';
    if (cents < 100) return 'about ' + Math.round(cents) + '¢';
    return 'about $' + (cost / 1000000).toFixed(2);
  }
  function safeUrl(u) {
    try {
      const x = new URL(str(u));
      return x.protocol === 'https:' || x.protocol === 'http:' ? x.href : '';
    } catch (e) {
      return '';
    }
  }
  function domainOf(u) {
    try { return new URL(u).hostname.replace(/^www\./, ''); } catch (e) { return ''; }
  }
  function askedBy(from) {
    const f = str(from);
    if (!f || f === str(obj(S.me).name)) return '';
    return f === 'the TV' ? 'asked on the TV' : 'from ' + f;
  }
  function answerMeta(a, cls) {
    const at = num(a.at);
    const bits = [askedBy(a.from), costText(a.cost)].filter(Boolean);
    return h('p', { class: cls }, at ? agoSpan(at) : null, bits.length ? (at ? ' · ' : '') + bits.join(' · ') : '');
  }

  function answerCard(a, fresh) {
    const err = str(a.error);
    const did = arr(a.did).map(str).filter(Boolean);
    const sources = arr(a.sources).map(obj).map(function (x) { return { title: str(x.title), url: safeUrl(x.url) }; })
      .filter(function (x) { return x.url && domainOf(x.url); }).slice(0, 3);
    return h('article', { class: 'answer' + (fresh ? ' fresh' : '') },
      h('p', { class: 'answer-q', text: str(a.question) }),
      err ? h('p', { class: 'amber-note answer-error', text: err }) : h('p', { class: 'answer-text', text: str(a.text) || 'Done.' }),
      did.length ? h('ul', { class: 'did', 'aria-label': 'What Lucent did' }, did.map(function (x) { return h('li', { class: 'did-chip' }, icon('check'), h('span', { text: x })); })) : null,
      sources.length ? h('p', { class: 'sources' }, h('span', { class: 'sources-label', text: 'Sources' }), sources.map(function (x) {
        return h('a', { class: 'source', href: x.url, target: '_blank', rel: 'noopener noreferrer', title: x.title || x.url }, h('span', { text: domainOf(x.url) }), icon('external'));
      })) : null,
      answerMeta(a, 'answer-meta'));
  }

  function thinkingCard(question, from) {
    const by = askedBy(from);
    return h('article', { class: 'answer thinking' },
      h('p', { class: 'answer-q', text: question }),
      h('p', { class: 'think-line', role: 'status' },
        h('span', { class: 'think-dots', 'aria-hidden': 'true' }, h('span'), h('span'), h('span')),
        h('span', { text: 'Thinking' + (by ? ', ' + by : '') })));
  }

  function buildAsk() {
    const c = card(0, 'Ask Lucent', 'span-12 card-ask');
    c.el.hidden = true;
    const input = h('input', { class: 'input ask-input', id: 'ask', 'data-fk': 'ask', type: 'text', maxLength: 500, placeholder: 'Ask Lucent anything', autocomplete: 'off', enterkeyhint: 'send', 'aria-label': 'Ask Lucent anything' });
    const send = h('button', { class: 'btn btn-primary btn-icon ask-send', type: 'submit', 'aria-label': 'Ask' }, icon('send'));
    const form = h('form', { class: 'ask-form', novalidate: true }, input, send);
    form.addEventListener('submit', function (e) { e.preventDefault(); askSend(input.value); });
    const ask = h('div', { class: 'ask-box' }, form, h('p', { class: 'ask-hint', text: 'Tap the microphone on the keyboard to speak.' }));
    const nokey = h('div', { class: 'ask-nokey', hidden: true },
      h('p', { class: 'ask-nokey-text', text: 'Add a Claude API key in Settings to ask questions' }),
      h('button', { class: 'btn btn-primary', type: 'button', on: { click: openClaudeSettings } }, 'Add a Key in Settings'));
    const err = h('p', { class: 'amber-note ask-error', role: 'alert', hidden: true });
    const now = h('div', { class: 'ask-now', 'aria-live': 'polite' });
    const recent = h('div', { class: 'ask-recent' });
    fill(c.body, [ask, nokey, err, now, recent]);
    const self = {
      el: c.el, card: c, input: input, send: send,
      render: function (force) {
        const a = assistantState();
        c.el.hidden = !a;
        if (!a) return;
        const on = a.on === true;
        const hist = arr(a.history).map(obj).filter(function (x) { return x.busy !== true && (str(x.question) || str(x.text) || str(x.error)); });
        const p = asking.pending;
        if (!changed('h.ask', [on, hist, a.asking, p, asking.error, asking.busy, asking.fresh, str(obj(S.me).name)]) && !force) return;
        setMeta(c, on ? 'Claude' : '');
        ask.hidden = !on;
        nokey.hidden = on;
        input.disabled = !!p;
        send.disabled = !!p;
        send.classList.toggle('busy', !!p);
        err.textContent = asking.error;
        err.hidden = !asking.error;
        // Now: the question being answered, else the newest answer.
        const other = a.asking && typeof a.asking === 'object' ? obj(a.asking) : null;
        let top = null;
        if (p) top = thinkingCard(p.question, '');
        else if (other) top = thinkingCard(str(other.question), str(other.from));
        else if (hist[0]) top = answerCard(hist[0], asking.fresh && (asking.fresh === str(hist[0].id) || asking.fresh === String(hist[0].at)));
        fill(now, top);
        now.hidden = !top;
        // Then the earlier answers, or examples to start with.
        const rest = p || other ? hist : hist.slice(1);
        if (!hist.length) {
          fill(recent, on ? [h('p', { class: 'room-label', text: 'Try' }), h('div', { class: 'chips ask-examples' }, ASK_EXAMPLES.map(function (q) {
            return h('button', { class: 'chip-opt ask-example', type: 'button', text: q, on: { click: function () { input.value = q; askSend(q); } } });
          }))] : null);
          return;
        }
        const clear = h('button', { class: 'btn btn-quiet btn-sm ask-clear', type: 'button', 'data-fk': 'ask-clear' }, 'Clear');
        confirmTap(clear, 'Tap again to clear', async function () {
          const st = assistantState();
          const was = st ? st.history : null;
          if (st) st.history = [];
          asking.fresh = '';
          self.render(true);
          try {
            await act('assistant', { do: 'clear' });
            toast('History cleared');
          } catch (e) {
            if (st) st.history = was;
            self.render(true);
            toast(serverError(e), 'alert');
          }
        });
        fill(recent, [
          rest.length ? h('div', { class: 'ask-recent-head' }, h('p', { class: 'room-label', text: 'Earlier' })) : null,
          rest.length ? h('ol', { class: 'hist' }, rest.slice(0, 11).map(function (x) {
            const e = str(x.error);
            const body = h('p', { class: 'hist-a' + (e ? ' err' : ''), text: e || str(x.text) || 'Done.' });
            const item = h('li', { class: 'hist-item' }, h('p', { class: 'hist-q', text: str(x.question) }), body, answerMeta(x, 'hist-meta'));
            if ((e || str(x.text)).length > 140) {
              const more = h('button', { class: 'quiet-link hist-more', type: 'button', 'aria-expanded': 'false' }, 'Show all');
              more.addEventListener('click', function () {
                const open = item.classList.toggle('open');
                more.setAttribute('aria-expanded', open ? 'true' : 'false');
                more.textContent = open ? 'Show less' : 'Show all';
              });
              add(item, more);
            }
            return item;
          })) : null,
          h('div', { class: 'ask-foot' }, clear)
        ]);
      }
    };
    return self;
  }

  function greeting() {
    const hr = new Date(tvNow()).getHours();
    return hr < 5 ? 'Good night' : hr < 12 ? 'Good morning' : hr < 17 ? 'Good afternoon' : hr < 22 ? 'Good evening' : 'Good night';
  }
  function personName() {
    const m = /^(.+?)[’']s\s/.exec(str(obj(S.me).name));
    return m ? m[1].trim() : '';
  }

  function renderAlerts(el, alerts) {
    fill(el, alerts.map(obj).filter(function (a) { return str(a.event) || str(a.headline); }).slice(0, 3).map(function (a) {
      const sev = str(a.severity).toLowerCase();
      const cls = sev === 'extreme' || sev === 'severe' ? 'sev-high' : sev === 'moderate' ? 'sev-mid' : 'sev-low';
      return h('div', { class: 'alert-banner ' + cls, role: 'note' },
        icon('alert', 'alert-ico'),
        h('div', { class: 'alert-text' },
          h('p', { class: 'alert-title' }, h('span', { text: str(a.event) || 'Weather alert' }), num(a.expires) ? h('span', { class: 'alert-until', text: 'until ' + fmtTime(a.expires) }) : null),
          str(a.headline) ? h('p', { class: 'alert-sub', text: str(a.headline) }) : null));
    }));
    el.hidden = !el.childNodes.length;
  }

  function renderTransit(c, t) {
    const lines = arr(t.lines).map(obj);
    setMeta(c, lines.length ? (t.live ? 'Live' : 'Timetable') : '', t.live ? 'live' : '');
    if (!lines.length) { fill(c.body, empty('No buses or shuttles to show yet.')); return; }
    fill(c.body, h('ul', { class: 'lines' }, lines.map(function (ln) {
      const chipText = str(ln.chip) || '•';
      let label = str(ln.label);
      if (label.indexOf(chipText + ' ') === 0) label = label.slice(chipText.length + 1);
      const colour = hex(ln.color, '#8A8580');
      const mins = arr(ln.minutes).filter(function (m) { return num(m) !== null && m >= 0; }).sort(function (a, b) { return a - b; });
      const key = 'bus.' + (str(ln.id) || chipText);
      let when;
      if (mins.length) {
        const first = Math.round(mins[0]);
        when = h('div', { class: 'line-when' },
          h('div', { class: 'line-first' }, first === 0 ? bigNum(key, 'Now', 'line-min now') : [bigNum(key, first, 'line-min'), h('span', { class: 'unit', text: 'min' })]),
          mins.length > 1 ? h('p', { class: 'line-then', text: 'then ' + mins.slice(1, 3).map(Math.round).join(', ') }) : null);
      } else {
        when = h('p', { class: 'line-idle', text: str(ln.idle) || 'No buses soon' });
      }
      return h('li', { class: 'line' + (mins.length ? '' : ' idle') },
        h('span', { class: 'chip', vars: { '--chip': colour, '--chip-ink': inkOn(colour) }, text: chipText }),
        h('div', { class: 'line-info' }, h('p', { class: 'line-label', text: label || chipText }), str(ln.stop) ? h('p', { class: 'line-stop', text: str(ln.stop) }) : null),
        when);
    })));
  }

  // The watched docks: Westgate first, then the second dock when one is watched (bikes.alt).
  function watched(b) {
    const stations = arr(b.stations).map(obj);
    const main = stations[0] || null;
    const altId = str(b.alt);
    const alt = altId && stations[1] && str(stations[1].id) === altId ? stations[1] : null;
    const label = str(b.label) || (main ? str(main.short) || str(main.name) : '') || 'Westgate';
    const altLabel = alt ? str(b.altLabel) || str(alt.short) || str(alt.name) || 'Erie St' : '';
    return { stations: stations, main: main, alt: alt, label: label, altLabel: altLabel };
  }

  function bikeDots(e, cl, d, cap, cols, cls) {
    const dots = [];
    for (let i = 0; i < Math.min(cap, 60); i++) {
      const k = i < e ? 'e' : i < e + cl ? 'c' : i < e + cl + d ? 'd' : 'x';
      dots.push(h('span', { class: 'dot dot-' + k }));
    }
    return h('div', { class: 'dots' + (cls ? ' ' + cls : ''), role: 'img', vars: { '--cols': String(cols) }, 'aria-label': e + ' e-bikes, ' + cl + ' classic bikes, ' + d + ' open docks' }, dots);
  }

  function renderBikes(c, b, f, fc) {
    const w = watched(b);
    const st = w.main;
    const label = w.label;
    const at = num(b.published) || num(b.fetched);
    // With two docks their names head the columns, so the meta keeps only the time.
    setMeta(c, w.alt ? (at ? agoSpan(at) : '') : [label, at ? ' · ' : '', at ? agoSpan(at) : null]);
    if (!st) {
      fill(c.body, [empty(b.ok === false ? 'Bluebikes isn’t answering right now. Lucent keeps trying.' : 'Looking for the dock…'), finderBlock(f, label, '')]);
      return;
    }
    const others = w.stations.slice(w.alt ? 2 : 1, w.alt ? 4 : 3).map(function (o) {
      return h('li', { class: 'other' },
        h('span', { class: 'other-name', text: str(o.short) || str(o.name) }),
        h('span', { class: 'other-nums' },
          h('span', { class: 'other-e', text: (num(o.ebikes) || 0) + ' e' }),
          h('span', { text: (num(o.classic) || 0) + ' classic' }),
          num(o.miles) !== null ? h('span', { class: 'muted', text: o.miles.toFixed(1) + ' mi' }) : null));
    });
    const othersList = others.length ? h('ul', { class: 'others', 'aria-label': 'Nearby docks' }, others) : null;
    if (w.alt) {
      fill(c.body, [
        h('div', { class: 'docks' }, dockColumn(st, label, 'ebikes'), dockColumn(w.alt, w.altLabel, 'ebikes2')),
        forecastBlock(fc, w),
        finderBlock(f, label, w.altLabel),
        othersList
      ]);
      return;
    }
    const e = Math.max(0, num(st.ebikes) || 0);
    const cl = Math.max(0, num(st.classic) || 0);
    const d = Math.max(0, num(st.docks) || 0);
    const cap = Math.max(num(st.capacity) || 0, e + cl + d);
    const renting = st.renting !== false;
    fill(c.body, [
      h('div', { class: 'bike-hero' + (renting ? '' : ' off') },
        h('div', { class: 'bike-main' },
          bigNum('ebikes', e, 'hero-num' + (e ? ' has' : '')),
          h('p', { class: 'bike-main-label' }, h('span', { class: 'strong', text: e === 1 ? 'e-bike' : 'e-bikes' }), h('span', { text: renting ? (e ? 'ready to ride' : 'none right now') : 'dock not renting' }))),
        h('dl', { class: 'bike-side' },
          h('div', { class: 'bike-stat' }, h('dt', { text: 'Classic' }), h('dd', null, bigNum('classic', cl, 'side-num'))),
          h('div', { class: 'bike-stat' }, h('dt', { text: 'Open docks' }), h('dd', null, bigNum('docks', d, 'side-num'))))),
      bikeDots(e, cl, d, cap, clamp(Math.min(cap, 60), 1, 30)),
      renting ? null : h('p', { class: 'bike-note', text: 'This dock isn’t renting right now, so its bikes are shown dimmed and the finder won’t count them.' }),
      forecastBlock(fc, w),
      finderBlock(f, label, ''),
      othersList
    ]);
  }

  // One of the two watched docks: its name, its e-bikes as the hero number, then classic and open docks.
  function dockColumn(st, name, key) {
    const e = Math.max(0, num(st.ebikes) || 0);
    const cl = Math.max(0, num(st.classic) || 0);
    const d = Math.max(0, num(st.docks) || 0);
    const cap = Math.max(num(st.capacity) || 0, e + cl + d);
    const renting = st.renting !== false;
    return h('div', { class: 'dock' + (renting ? '' : ' off') },
      h('p', { class: 'dock-name', text: name }),
      h('div', { class: 'dock-hero' },
        bigNum(key, e, 'dock-num' + (e ? ' has' : '')),
        h('span', { class: 'dock-unit', text: e === 1 ? 'e-bike' : 'e-bikes' })),
      h('p', { class: 'dock-sub', text: renting ? cl + ' classic · ' + d + ' open' : 'Not renting right now' }),
      bikeDots(e, cl, d, cap, clamp(Math.ceil(Math.min(cap, 60) / 2), 1, 15), 'dots-sm'));
  }

  /* the e-bike forecast under the docks */

  const DAY_TYPES = { class: 'MIT class day', finals: 'MIT finals', offweek: 'No MIT classes', sat: 'Saturday', sun: 'Sunday' };

  function timeParts(ms) {
    try {
      return new Intl.DateTimeFormat([], { hour: 'numeric', minute: '2-digit', hour12: hour12() }).formatToParts(new Date(ms));
    } catch (e) {
      return [];
    }
  }
  function dayPeriod(ms) {
    for (const p of timeParts(ms)) if (p.type === 'dayPeriod') return p.value;
    return '';
  }
  // "7:50" where the full time is "7:50 AM": for the ends of a range that shares one AM or PM.
  function clockShort(ms) {
    const parts = timeParts(ms);
    if (!parts.length) return fmtTime(ms);
    return parts.filter(function (p) { return p.type !== 'dayPeriod'; }).map(function (p) { return p.value; }).join('').replace(/^[\s  ]+|[\s  ]+$/g, '');
  }
  // A time that never breaks across two lines ("8:10 AM").
  function timeNb(ms) { return fmtTime(ms).replace(/ /g, '\u00a0'); }
  // " (7:50 to 8:40)", with AM and PM only when the range crosses noon or midnight.
  function rangeText(lo, hi, med) {
    lo = num(lo) || 0;
    hi = num(hi) || 0;
    if (lo <= 0 || hi <= 0) return '';
    const same = dayPeriod(lo) === dayPeriod(med) && dayPeriod(hi) === dayPeriod(med);
    const f = same ? clockShort : timeNb;
    return ' (' + f(lo) + ' to ' + f(hi) + ')';
  }

  function forecastBlock(raw, w) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
    const fc = obj(raw);
    const pool = obj(fc.pool), first = obj(fc.first), next = obj(fc.next);
    const lines = [];
    let main = '';
    if (fc.empty === true) {
      const med = num(next.med) || 0;
      main = med > 0 ? 'Next e-bike likely around ' + timeNb(med) + rangeText(next.lo, next.hi, med) : 'No e-bike expected in the next 8 hours';
    } else if (fc.empty === false) {
      const med = num(pool.med) || 0;
      main = med > 0
        ? 'Likely all gone by ' + timeNb(med) + (rangeText(pool.lo, pool.hi, med) || ((num(pool.lo) || 0) > 0 ? ', maybe later' : ''))
        : 'E-bikes should last the next 8 hours';
      const firstMed = num(first.med) || 0;
      if (w.alt && w.main && firstMed > 0 && (num(w.main.ebikes) || 0) > 0) lines.push(w.label + ' likely empty by ' + timeNb(firstMed));
    }
    const chance = num(fc.morning);
    const when = num(fc.morningAt) || 0;
    if (chance !== null && chance >= 0 && when > 0) {
      const isToday = dayStart(when) === dayStart(tvNow());
      const day = isToday ? 'Today' : dayStart(when) === dayStart(tvNow(), 1) ? 'Tomorrow' : fmtDate(when, { weekday: 'long' });
      const pct = function (x) { return Math.round(clamp(x, 0, 1) * 100) + '%'; };
      // The evening outlook: the chance at morningAt and an hour and two hours after it.
      const later = [[num(fc.morning1h), when + 3600000], [num(fc.morning2h), when + 7200000]].filter(function (x) { return x[0] !== null && x[0] >= 0; });
      if (later.length) {
        const pts = [[chance, when]].concat(later);
        const same = pts.every(function (x) { return dayPeriod(x[1]) === dayPeriod(when); });
        const at = same ? clockShort : timeNb;
        const list = pts.map(function (x) { return pct(x[0]) + ' at ' + at(x[1]); }).join(', ');
        lines.push((isToday ? 'Chance of an e-bike: ' : day + ', chance of an e-bike: ') + list);
      } else {
        lines.push(day + ' at ' + timeNb(when) + ': ' + pct(chance) + ' chance of an e-bike');
      }
    }
    if (!main && !lines.length) return null;
    const tag = DAY_TYPES[str(fc.dayType)] || '';
    return h('div', { class: 'forecast' + (fc.empty === true ? ' is-empty' : '') },
      h('p', { class: 'fc-eyebrow', text: 'Forecast' + (tag ? ' · ' + tag : '') }),
      main ? h('p', { class: 'fc-main', text: main }) : null,
      lines.map(function (t) { return h('p', { class: 'fc-line', text: t }); }),
      h('p', { class: 'fc-credit', text: 'Forecast from every Bluebikes trip at these docks since 2024, MIT’s calendar and the weather.' }));
  }

  function finderBlock(f, label, altLabel) {
    const state = str(f.state).toLowerCase();
    const forMin = parseInt(str(settings().findFor), 10) || 30;
    const forText = forMin >= 60 ? plural(forMin / 60, 'hour') : forMin + ' minutes';
    const both = altLabel ? label + ' and ' + altLabel : label;
    const station = str(f.station) || both;
    const findBtn = function (text) {
      return h('button', { class: 'btn btn-primary btn-lg btn-block find-btn', type: 'button', 'data-fk': 'find', on: { click: function () { finderDo('start'); } } }, icon('bike'), text);
    };
    if (/search|watch|run/.test(state)) {
      const started = num(f.started) || tvNow();
      const checks = num(f.checks) || 0;
      const sub = f.offline ? 'The dock’s feed isn’t answering. Still trying.'
        : (checks ? plural(checks, 'check') : 'Just started') + (num(f.deadline) ? ' · gives up at ' + fmtTime(f.deadline) : '');
      const e2 = num(f.ebikes2);
      const counts = altLabel && e2 !== null && e2 >= 0
        ? h('p', { class: 'finder-counts', 'aria-label': 'E-bikes at the last check' },
          h('span', { class: 'fcount' }, h('span', { text: label }), h('span', { class: 'fcount-n', text: String(Math.max(0, num(f.ebikes) || 0)) })),
          h('span', { class: 'fcount' }, h('span', { text: altLabel }), h('span', { class: 'fcount-n', text: String(e2) })))
        : null;
      return h('div', { class: 'finder watching' + (f.offline ? ' offline' : '') },
        h('div', { class: 'radar', 'aria-hidden': 'true' }, h('span', { class: 'ring' }), h('span', { class: 'ring' }), h('span', { class: 'ring' }), icon('bike', 'radar-ico')),
        h('div', { class: 'finder-text', role: 'status' },
          h('p', { class: 'finder-title' }, 'Watching ' + station + ' · ', h('span', { class: 'clock', 'data-since': String(started), text: fmtClock(tvNow() - started) })),
          h('p', { class: 'finder-sub', text: sub })),
        counts,
        h('button', { class: 'btn btn-quiet btn-block finder-stop', type: 'button', 'data-fk': 'stop', on: { click: function () { finderDo('stop'); } } }, 'Stop looking'));
    }
    if (/found/.test(state)) {
      return h('div', { class: 'finder found' },
        h('p', { class: 'finder-result' }, icon('check'), 'Found one' + (str(f.station) ? ' at ' + str(f.station) : '') + (num(f.ended) ? ' at ' + fmtTime(f.ended) : '') + '. ' + plural(num(f.ebikes) || 1, 'e-bike') + ' ready.'),
        findBtn('Find another'));
    }
    if (/time|gave|expire/.test(state)) {
      return h('div', { class: 'finder timeout' },
        h('p', { class: 'finder-result' }, 'No e-bike docked in ' + forText + ', so Lucent stopped looking' + (num(f.ended) ? ' at ' + fmtTime(f.ended) : '') + '.'),
        findBtn('Try again'));
    }
    return h('div', { class: 'finder idle' },
      findBtn('Find me an e-bike'),
      h('p', { class: 'finder-hint', text: altLabel
        ? 'Lucent checks ' + label + ' and ' + altLabel + ' every 5 seconds and rings the TV for the first e-bike at either. It gives up after ' + forText + '.'
        : 'Lucent checks ' + label + ' every 5 seconds and rings the TV the moment one docks. It gives up after ' + forText + '.' }));
  }

  function wxKind(code, day) {
    code = num(code);
    const night = day === false;
    if (code === null) return 'wx-cloud';
    if (code === 0) return night ? 'wx-moon' : 'wx-sun';
    if (code <= 2) return night ? 'wx-cloud-moon' : 'wx-cloud-sun';
    if (code === 3) return 'wx-cloud';
    if (code === 45 || code === 48) return 'wx-fog';
    if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return 'wx-rain';
    if ((code >= 71 && code <= 77) || code === 85 || code === 86) return 'wx-snow';
    if (code >= 95) return 'wx-storm';
    return 'wx-cloud';
  }
  function toC(f) { return f === null ? null : (f - 32) * 5 / 9; }
  function deg(v) { return v === null ? '–' : Math.round(v) + '°'; }

  function renderWeather(c, w, units) {
    const tf = num(w.tempF);
    if (tf === null) {
      setMeta(c, '');
      fill(c.body, empty(w.ok === false ? 'Weather is resting. Lucent will try again shortly.' : 'No weather yet.'));
      return;
    }
    const cFirst = units === 'c';
    const tc = num(w.tempC) !== null ? w.tempC : toC(tf);
    const conv = function (f) { f = num(f); return f === null ? null : cFirst ? toC(f) : f; };
    setMeta(c, [str(w.city) || 'Cambridge', str(w.updated) ? ' · ' + str(w.updated) : '']);
    const facts = [
      ['Feels', deg(conv(w.feelsF))],
      ['High', deg(conv(w.hiF))],
      ['Low', deg(conv(w.loF))],
      ['Wind', num(w.windMph) !== null ? Math.round(w.windMph) + ' mph' + (str(w.windDir) ? ' ' + str(w.windDir) : '') : '–'],
      ['Humidity', num(w.humidity) !== null ? Math.round(w.humidity) + '%' : '–']
    ];
    const hours = arr(w.hours).map(obj).slice(0, 12);
    fill(c.body, [
      h('div', { class: 'wx-top' },
        h('div', { class: 'wx-temp' }, bigNum('temp', Math.round(cFirst ? tc : tf), 'wx-num'), h('span', { class: 'wx-deg', text: '°' + (cFirst ? 'C' : 'F') })),
        h('div', { class: 'wx-side' },
          icon(wxKind(w.code, w.day), 'wx-icon'),
          h('p', { class: 'wx-second', text: (cFirst ? Math.round(tf) : Math.round(tc)) + '°' + (cFirst ? 'F' : 'C') }))),
      h('p', { class: 'wx-cond', text: str(w.condition) || ' ' }),
      rainLine(obj(w.rain)),
      h('dl', { class: 'facts' }, facts.map(function (f) { return h('div', { class: 'fact' }, h('dt', { text: f[0] }), h('dd', { text: f[1] })); })),
      hours.length ? h('ol', { class: 'hours', 'aria-label': 'Next hours' }, hours.map(function (hr) {
        const t = cFirst ? (num(hr.c) !== null ? hr.c : toC(num(hr.f))) : num(hr.f);
        const rain = num(hr.rain);
        return h('li', { class: 'hour' },
          h('span', { class: 'hour-label', text: str(hr.label) }),
          icon(wxKind(hr.code, hr.day), 'hour-ico'),
          h('span', { class: 'hour-temp', text: deg(t) }),
          h('span', { class: 'hour-rain' + (rain !== null && rain >= 30 ? ' wet' : ''), text: rain !== null && rain >= 10 ? Math.round(rain) + '%' : '' }));
      })) : null,
      h('p', { class: 'wx-credit', text: 'Open-Meteo' + (str(w.model) ? ', ' + str(w.model).replace(/\s*\([^)]*\)\s*$/, '') : '') })
    ]);
  }

  // "Rain from 3:45 PM" or "Dry for the next 2 hours". Rain is coming when it has a time, or when the summary says so.
  function rainLine(rain) {
    const text = str(rain.summary);
    if (!text) return null;
    const wet = (num(rain.at) || 0) > 0 || !/^dry\b/i.test(text);
    return h('p', { class: 'wx-rain' + (wet ? ' wet' : '') }, icon(wet ? 'wx-rain' : 'wx-cloud', 'wx-rain-ico'), h('span', { text: text }));
  }

  function moonGlyph(phase, lit) {
    const s = document.createElementNS(SVGNS, 'svg');
    s.setAttribute('viewBox', '0 0 40 40');
    s.setAttribute('class', 'moon');
    s.setAttribute('aria-hidden', 'true');
    const base = document.createElementNS(SVGNS, 'circle');
    base.setAttribute('cx', '20'); base.setAttribute('cy', '20'); base.setAttribute('r', '16');
    base.setAttribute('class', 'moon-dark');
    s.appendChild(base);
    const d = moonPath(clamp(lit === null ? 0.5 : lit, 0, 1), /wan|last|third/i.test(phase));
    if (d) {
      const p = document.createElementNS(SVGNS, 'path');
      p.setAttribute('d', d);
      p.setAttribute('class', 'moon-lit');
      s.appendChild(p);
    }
    return s;
  }
  // The lit part: a half circle on the sunny side closed by the terminator ellipse.
  function moonPath(lit, waning) {
    const r = 16, cx = 20, top = 4, bottom = 36;
    if (lit <= 0.01) return '';
    if (lit >= 0.99) return 'M20 4a16 16 0 1 1 0 32a16 16 0 1 1 0-32z';
    const rx = (Math.abs(1 - 2 * lit) * r).toFixed(2);
    if (!waning) return 'M' + cx + ' ' + top + 'A' + r + ' ' + r + ' 0 0 1 ' + cx + ' ' + bottom + 'A' + rx + ' ' + r + ' 0 0 ' + (lit < 0.5 ? 0 : 1) + ' ' + cx + ' ' + top + 'z';
    return 'M' + cx + ' ' + top + 'A' + r + ' ' + r + ' 0 0 0 ' + cx + ' ' + bottom + 'A' + rx + ' ' + r + ' 0 0 ' + (lit < 0.5 ? 1 : 0) + ' ' + cx + ' ' + top + 'z';
  }

  function aqiBand(a) { return a === null ? '' : a <= 50 ? 'good' : a <= 100 ? 'warn' : a <= 150 ? 'orange' : 'alert'; }
  function uvBand(u) { return u === null ? '' : u < 3 ? 'good' : u < 6 ? 'warn' : u < 8 ? 'orange' : 'alert'; }

  function renderDaylight(c, tv, air) {
    const auto = str(tv.modeSetting) === 'auto' || !str(tv.modeSetting);
    const nextLook = auto && num(tv.nextChange) ? (str(tv.mode) === 'night' ? 'Day look at ' : 'Night look at ') + fmtTime(tv.nextChange) : '';
    setMeta(c, nextLook);
    const lit = num(tv.moonLit);
    const aqi = num(air.aqi);
    const uv = num(air.uv);
    fill(c.body, [
      h('div', { class: 'sun-row' },
        h('div', { class: 'sun-item' }, icon('sunrise', 'sun-ico'), h('p', { class: 'sun-label', text: 'Sunrise' }), h('p', { class: 'sun-time', text: fmtTime(tv.sunrise) || '–' })),
        h('div', { class: 'sun-item' }, icon('sunset', 'sun-ico'), h('p', { class: 'sun-label', text: 'Sunset' }), h('p', { class: 'sun-time', text: fmtTime(tv.sunset) || '–' }))),
      str(tv.moon) ? h('div', { class: 'moon-row' }, moonGlyph(str(tv.moon), lit),
        h('div', null, h('p', { class: 'moon-name', text: str(tv.moon) }), lit !== null ? h('p', { class: 'moon-lit-text', text: Math.round(lit * 100) + '% lit' }) : null)) : null,
      h('div', { class: 'air-row' },
        h('div', { class: 'air-item' }, h('p', { class: 'air-label', text: 'Air quality' }),
          h('p', { class: 'air-value' }, h('span', { class: 'air-num', text: aqi === null ? '–' : String(Math.round(aqi)) }), h('span', { class: 'band ' + aqiBand(aqi), text: str(air.aqiWord) }))),
        h('div', { class: 'air-item' }, h('p', { class: 'air-label', text: 'UV index' }),
          h('p', { class: 'air-value' }, h('span', { class: 'air-num', text: uv === null ? '–' : String(Math.round(uv * 10) / 10) }), h('span', { class: 'band ' + uvBand(uv), text: str(air.uvWord) }))))
    ]);
  }

  function renderSky(c, sky, setting) {
    const flights = arr(sky.flights).map(obj).sort(function (a, b) { return (num(a.miles) || 99) - (num(b.miles) || 99); });
    const inRange = num(sky.inRange);
    setMeta(c, [inRange !== null ? inRange + ' in range' : '', str(sky.source) ? (inRange !== null ? ' · ' : '') + str(sky.source) : '']);
    if (setting === 'off' && !flights.length) { fill(c.body, empty('Planes are switched off on the TV. Turn them on in Settings.')); return; }
    if (!flights.length) { fill(c.body, empty('Clear skies over Westgate right now.', 'calm')); return; }
    const f = flights[0];
    const route = str(f.from) && str(f.to) ? [h('span', { text: str(f.from) }), icon('arrow', 'route-arrow'), h('span', { text: str(f.to) })] : null;
    const cities = str(f.fromCity) && str(f.toCity) ? str(f.fromCity) + ' to ' + str(f.toCity) : '';
    const trend = str(f.trend);
    const stats = [
      ['Altitude', num(f.alt) !== null ? fmtInt(f.alt) : '–', 'ft'],
      ['Speed', num(f.knots) !== null ? fmtInt(f.knots) : '–', 'kt'],
      ['Away', num(f.miles) !== null ? f.miles.toFixed(1) : '–', 'mi'],
      ['Aircraft', str(f.type) || '–', '']
    ];
    fill(c.body, [
      h('div', { class: 'flight-hero' },
        h('div', { class: 'flight-head' },
          h('p', { class: 'flight-name', text: str(f.name) || 'Unknown flight' }),
          trend ? h('span', { class: 'trend ' + trend.toLowerCase() }, icon(/climb/i.test(trend) ? 'up' : /desc/i.test(trend) ? 'down' : 'level'), trend) : null),
        route ? h('p', { class: 'route' }, route) : null,
        cities ? h('p', { class: 'route-cities', text: cities }) : null),
      h('dl', { class: 'flight-stats' }, stats.map(function (s) {
        return h('div', { class: 'flight-stat' }, h('dt', { text: s[0] }), h('dd', null, h('span', { class: 'stat-num', text: s[1] }), s[2] ? h('span', { class: 'unit', text: s[2] }) : null));
      })),
      flights.length > 1 ? h('ul', { class: 'flights-more' }, flights.slice(1, 4).map(function (o) {
        return h('li', { class: 'flight-row' },
          icon('plane', 'row-ico'),
          h('span', { class: 'flight-row-name', text: str(o.name) || 'Flight' }),
          h('span', { class: 'flight-row-route', text: str(o.from) && str(o.to) ? str(o.from) + ' → ' + str(o.to) : '' }),
          h('span', { class: 'flight-row-alt', text: num(o.alt) !== null ? fmtInt(o.alt) + ' ft' : '' }));
      })) : null
    ]);
  }

  function daysUntil(date) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(str(date));
    if (!m) return null;
    const d = new Date(+m[1], +m[2] - 1, +m[3]).getTime();
    return Math.round((d - dayStart(tvNow())) / 86400000);
  }
  function countdownDays(c) { return num(c.days) !== null ? c.days : daysUntil(c.date); }

  function renderNext(c, cal) {
    const now = tvNow();
    const evs = arr(cal.events).map(obj).filter(function (e) { return num(e.start) !== null && (num(e.end) || e.start + 3600000) > now; })
      .sort(function (a, b) { return a.start - b.start; });
    const cds = arr(cal.countdowns).map(obj).filter(function (x) { const d = countdownDays(x); return d !== null && d >= 0; })
      .sort(function (a, b) { return countdownDays(a) - countdownDays(b); });
    setMeta(c, '');
    const kids = [];
    const e = evs[0];
    if (e) {
      const t1 = dayStart(now, 1);
      const when = e.allDay ? (e.start < t1 ? 'Today' : fmtDate(e.start)) : e.start <= now ? 'Now' : (e.start < t1 ? 'Today ' : e.start < dayStart(now, 2) ? 'Tomorrow ' : fmtDate(e.start) + ' ') + fmtTime(e.start);
      kids.push(h('div', { class: 'next-event', vars: { '--cal': CAL_COLOURS[(num(e.cal) || 0) % CAL_COLOURS.length] } },
        h('p', { class: 'next-when', text: when }),
        h('p', { class: 'next-title', text: str(e.title) || 'Busy' }),
        str(e.location) || str(e.calName) ? h('p', { class: 'next-where', text: [str(e.location), str(e.calName)].filter(Boolean).join(' · ') }) : null));
    } else {
      kids.push(empty(arr(cal.feeds).length ? 'Nothing else on the calendar today.' : 'Add a shared calendar in Settings to see what’s next.'));
    }
    const cd = cds[0];
    if (cd) {
      const d = countdownDays(cd);
      kids.push(h('div', { class: 'countdown-hero' },
        bigNum('cd.' + str(cd.title), d === 0 ? 'Today' : d, 'cd-big'),
        h('p', { class: 'cd-hero-text' }, d === 0 ? '' : (d === 1 ? 'day until ' : 'days until '), h('em', { text: str(cd.title) }))));
    }
    kids.push(h('button', { class: 'btn btn-quiet btn-sm next-open', type: 'button', on: { click: function () { show('plans'); } } }, 'Open plans', icon('chevron')));
    fill(c.body, kids);
  }

  /* media: what is playing on the TV */

  const media = { fill: null, pos: null, dur: null, base: 0, at: 0, playing: false, duration: 0 };

  function renderMedia(c, m, audio, target) {
    const route = [str(audio.route), str(audio.headline)].filter(Boolean).join(' · ');
    const formats = arr(audio.formats).map(str).filter(Boolean).slice(0, 4);
    const extra = formats.filter(function (f) { return f !== str(audio.headline); });
    const audioLine = route || formats.length ? h('div', { class: 'audio-line' },
      h('p', { class: 'audio-route' }, icon('speaker', 'row-ico'), h('span', { text: route || 'Sound' })),
      extra.length ? h('p', { class: 'formats' }, extra.map(function (f) { return h('span', { class: 'format', text: f }); })) : null) : null;
    if (!m.active) {
      setMeta(c, '');
      media.fill = null;
      fill(target, [h('p', { class: 'media-idle' }, 'Nothing playing ', h('em', { text: 'right now' })), audioLine]);
      return;
    }
    setMeta(c, str(m.app));
    const bar = h('div', { class: 'progress-fill' });
    const pos = h('span', { class: 'mono-num' });
    const dur = h('span', { class: 'mono-num' });
    media.fill = bar;
    media.pos = pos;
    media.dur = dur;
    const ctl = function (doWhat, name, label, enabled, cls) {
      return h('button', { class: 'btn btn-icon media-btn ' + (cls || ''), type: 'button', 'aria-label': label, 'data-fk': 'm-' + doWhat, disabled: !enabled, on: { click: function () { mediaDo(doWhat); } } }, icon(name));
    };
    fill(target, [
      h('p', { class: 'media-title', text: str(m.title) || 'Untitled' }),
      h('p', { class: 'media-sub', text: [str(m.artist), str(m.album)].filter(Boolean).join(' · ') }),
      num(m.duration) ? h('div', { class: 'progress' }, h('div', { class: 'progress-track' }, bar), h('div', { class: 'progress-times' }, pos, dur)) : null,
      h('div', { class: 'media-ctrl' },
        ctl('prev', 'prev', 'Previous', m.canPrev !== false),
        ctl('toggle', m.playing ? 'pause' : 'play', m.playing ? 'Pause' : 'Play', true, 'media-play'),
        ctl('next', 'next', 'Next', m.canNext !== false)),
      audioLine
    ]);
    mediaTick();
  }
  function mediaSync(m) {
    media.base = num(m.position) || 0;
    media.at = receivedAt;
    media.playing = m.playing === true;
    media.duration = num(m.duration) || 0;
    mediaTick();
  }
  function mediaTick() {
    if (!media.fill || !media.duration) return;
    const p = clamp(media.base + (media.playing ? Date.now() - media.at : 0), 0, media.duration);
    media.fill.style.setProperty('--p', (p / media.duration).toFixed(4));
    media.pos.textContent = fmtClock(p);
    media.dur.textContent = fmtClock(media.duration);
  }
  async function mediaDo(what) {
    if (what === 'toggle') {
      const m = obj(S.media);
      media.base = clamp(media.base + (media.playing ? Date.now() - media.at : 0), 0, media.duration || 1e12);
      media.at = Date.now();
      media.playing = !media.playing;
      m.playing = media.playing;
      m.position = media.base;
      receivedAt = media.at;
      views.home.render();
    }
    try { await act('media', { do: what }); } catch (e) { toast(e.message, 'alert'); pollNow(); }
  }

  /* living room: look, hearts and the TV buttons */

  function buildRoom(c) {
    const look = choice({
      label: 'Day and night look',
      options: [['auto', 'Auto'], ['day', 'Day'], ['night', 'Night']],
      onChange: function (v) {
        return act('mode', { mode: v }).then(function () {
          const tv = obj(S.tv);
          tv.modeSetting = v;
          if (v !== 'auto') { tv.mode = v; applyTheme(v); }
          S.tv = tv;
        });
      }
    });
    const lookNote = h('p', { class: 'room-note' });
    let kind = 'hearts';
    const heartIco = h('span', { class: 'heart-ico-wrap' }, icon('heart'));
    const heartText = h('span', { text: 'Send hearts' });
    const heartBtn = h('button', { class: 'btn btn-lg btn-block heart-btn', type: 'button' }, heartIco, heartText, h('span', { class: 'cooldown', 'aria-hidden': 'true' }));
    const kinds = choice({
      label: 'What to send',
      small: true,
      value: 'hearts',
      options: [['hearts', 'Hearts'], ['sparkles', 'Sparkles'], ['wave', 'Wave']],
      onChange: function (v) {
        kind = v;
        heartText.textContent = v === 'hearts' ? 'Send hearts' : v === 'sparkles' ? 'Send sparkles' : 'Send a wave';
        heartIco.replaceChildren(icon(v === 'hearts' ? 'heart' : v === 'sparkles' ? 'sparkles' : 'wave'));
      }
    });
    heartBtn.addEventListener('click', async function () {
      if (heartBtn.classList.contains('cooling')) return;
      burst(heartBtn, kind);
      heartBtn.classList.add('cooling');
      setTimeout(function () { heartBtn.classList.remove('cooling'); }, 3000);
      try { await act('heart', { kind: kind }); } catch (e) { toast(e.message, 'alert'); }
    });
    const front = h('p', { class: 'room-note' });
    const back = h('button', { class: 'btn tv-btn', type: 'button', on: { click: function () { tvDo('lucent', 'Lucent is coming back to the screen'); } } }, icon('lucent'), h('span', { text: 'Bring Lucent back' }));
    const fireHome = h('button', { class: 'btn tv-btn', type: 'button', on: { click: function () { tvDo('firetv', 'Opening Fire TV’s home'); } } }, icon('grid'), h('span', { text: 'Fire TV home' }));
    const sleepBtn = h('button', { class: 'btn tv-btn', type: 'button' }, icon('moon'), h('span', { text: 'Sleep' }));
    confirmTap(sleepBtn, 'Tap again to sleep', function () { tvDo('sleep', 'Good night. The TV is going to sleep'); });
    fill(c.body, [
      h('div', { class: 'room-row' }, h('p', { class: 'room-label', text: 'Look' }), look.el, lookNote),
      h('div', { class: 'room-row' }, h('p', { class: 'room-label', text: 'Say hello' }), heartBtn, kinds.el)
    ]);
    return {
      tvRow: h('div', { class: 'room-row tv-row' }, h('p', { class: 'room-label', text: 'The TV' }), h('div', { class: 'tv-btns' }, back, fireHome, sleepBtn), front),
      render: function (tv) {
        look.set(str(tv.modeSetting) || str(settings().theme) || 'auto');
        const auto = (str(tv.modeSetting) || 'auto') === 'auto';
        const note = !loaded ? '' : auto
          ? (num(tv.nextChange) ? 'Follows the sun. ' + (str(tv.mode) === 'night' ? 'Day' : 'Night') + ' look at ' + fmtTime(tv.nextChange) + '.' : 'Follows sunrise and sunset in Cambridge.')
          : 'Stays ' + str(tv.modeSetting) + ' until you change it.';
        if (lookNote.textContent !== note) lookNote.textContent = note;
        const f = !loaded ? '' : tv.front === false ? 'Another app is on screen.' : 'Lucent is on screen' + (tv.dim ? ', dimmed.' : '.');
        if (front.textContent !== f) front.textContent = f;
        back.classList.toggle('btn-primary', tv.front === false);
      }
    };
  }

  /* glare: the Samsung picture switch on the home screen */

  function renderGlare(el, raw, display) {
    const kids = [];
    if (raw && typeof raw === 'object') {
      const sam = obj(raw);
      if (sam.connected === true) {
        const on = sam.glare === true;
        const bright = str(sam.glareMode) || 'bright';
        const btn = h('button', { class: 'glare-btn' + (on ? ' on' : ''), type: 'button', role: 'switch', 'aria-checked': on ? 'true' : 'false', 'data-fk': 'glare' },
          h('span', { class: 'glare-ico', 'aria-hidden': 'true' }, icon('wx-sun')),
          h('span', { class: 'glare-text' },
            h('span', { class: 'glare-title', text: 'Glare' }),
            h('span', { class: 'glare-sub', text: on ? 'On. The ' + bright + ' picture beats the sun' : 'Sun on the screen? Switch to ' + bright })),
          h('span', { class: 'glare-switch', 'aria-hidden': 'true' }));
        btn.addEventListener('click', function () { glareDo(!on); });
        kids.push(btn);
        kids.push(h('p', { class: 'picture-mode' }, 'Picture ', h('span', { class: 'strong', text: str(sam.mode) || 'unknown' }), str(sam.device) ? ' · ' + str(sam.device) : ''));
        if (str(sam.error)) kids.push(h('p', { class: 'amber-note', text: str(sam.error) }));
      } else {
        kids.push(h('button', { class: 'quiet-link', type: 'button', on: { click: openSamsungSettings } }, h('span', { text: 'Control the Samsung picture from here. Set it up in Settings' }), icon('chevron')));
      }
    }
    if (str(display.advice)) kids.push(h('p', { class: 'amber-note', text: str(display.advice) }));
    fill(el, kids);
    el.hidden = !kids.length;
  }
  function openSamsungSettings() {
    show('settings');
    requestAnimationFrame(function () {
      const target = $('#set-samsung');
      if (target) target.scrollIntoView({ block: 'start' });
    });
  }
  async function glareDo(on) {
    const sam = obj(S.samsung);
    const was = { glare: sam.glare, mode: sam.mode };
    sam.glare = on;
    if (on && str(sam.glareMode)) sam.mode = sam.glareMode;
    S.samsung = sam;
    if (views.home.built) views.home.render();
    try {
      await act('samsung', { do: 'glare', on: on });
      toast(on ? 'Glare picture on' : 'Back to the usual picture');
    } catch (e) {
      sam.glare = was.glare;
      sam.mode = was.mode;
      if (views.home.built) views.home.render();
      toast(serverError(e), 'alert');
    }
  }

  function burst(btn, kind) {
    const name = kind === 'sparkles' ? 'sparkles' : kind === 'wave' ? 'wave' : 'heart';
    for (let i = 0; i < 7; i++) {
      const s = h('span', { class: 'fly', 'aria-hidden': 'true', vars: { '--x': Math.round((Math.random() - 0.5) * 140) + 'px', '--d': Math.round(i * 70) + 'ms', '--r': Math.round((Math.random() - 0.5) * 50) + 'deg', '--s': (0.7 + Math.random() * 0.6).toFixed(2) } }, icon(name));
      btn.appendChild(s);
      setTimeout(function () { s.remove(); }, 1700);
    }
  }

  async function tvDo(what, msg) {
    try {
      await act('tv', { do: what }, { errors: what === 'sleep' ? { 409: 'The TV can’t sleep from here while Lucent’s HOME service is off.' } : null });
      toast(msg);
    } catch (e) {
      toast(e.message, 'alert');
    }
  }

  /* ------------------------------------------------------------------ the TV remote */

  // Keys go to the Samsung's own remote on the home network once Lucent has paired with it, else
  // through SmartThings (slow, 12 a minute), else nowhere until one of them is set up. Volume
  // always goes through the Fire TV, whose volume keys reach the soundbar over HDMI-CEC.
  function remoteState() { return obj(obj(S.samsung).remote); }
  function hasSamsung() { return !!S.samsung && typeof S.samsung === 'object' && !Array.isArray(S.samsung); }
  // The TV's Lucent can pair with the Samsung's remote (state.samsung.remote is there).
  function canSetUpRemote() { const r = obj(S.samsung).remote; return hasSamsung() && !!r && typeof r === 'object' && !Array.isArray(r); }
  function keyTarget() {
    const sam = obj(S.samsung);
    const r = obj(sam.remote);
    const st = str(r.state);
    if (st === 'connected' || (r.paired === true && st !== 'changed' && st !== 'refused' && st !== 'none')) return 'local';
    if (sam.connected === true && obj(sam.can).keys === true) return 'cloud';
    return '';
  }
  function tvLabel() {
    const sam = obj(S.samsung);
    const label = str(remoteState().label).replace(/^\s*\[[^\]]*\]\s*/, '');
    return (sam.connected === true && str(sam.device)) || label || 'Samsung';
  }
  // SmartThings names the same keys without the KEY_ prefix, and calls Enter OK and Return Back.
  const CLOUD_KEYS = {
    KEY_UP: 'UP', KEY_DOWN: 'DOWN', KEY_LEFT: 'LEFT', KEY_RIGHT: 'RIGHT', KEY_ENTER: 'OK', KEY_RETURN: 'BACK',
    KEY_HOME: 'HOME', KEY_MENU: 'MENU', KEY_SOURCE: 'SOURCE', KEY_EXIT: 'EXIT', KEY_TOOLS: 'MORE', KEY_POWER: 'POWER',
    KEY_PLAY: 'PLAY', KEY_PAUSE: 'PAUSE', KEY_VOLUP: 'VOLUP', KEY_VOLDOWN: 'VOLDOWN', KEY_MUTE: 'MUTE'
  };
  const press = { busy: 0, cloud: [], nextPlay: 'KEY_PAUSE' };

  // Restarts a short animation class on a button: pressed feedback, a dropped press, a failure.
  function nudge(btn, cls) {
    if (!btn) return;
    btn.classList.remove(cls);
    void btn.offsetWidth;
    btn.classList.add(cls);
    clearTimeout(btn['_' + cls]);
    btn['_' + cls] = setTimeout(function () { btn.classList.remove(cls); }, 520);
  }
  function buzz() {
    try {
      if (navigator.vibrate && navigator.userActivation && navigator.userActivation.isActive) navigator.vibrate(8);
    } catch (e) { /* no vibration here */ }
  }

  // One press. Nothing is debounced, but a press is dropped while three are still on their way.
  function remotePress(btn, action, body, o) {
    o = o || {};
    if (press.busy >= 3) { nudge(btn, 'is-dropped'); return false; }
    press.busy++;
    nudge(btn, 'is-sent');
    buzz();
    act(action, body, { timeout: o.timeout || 15000, quiet: o.quiet !== false })
      .then(function (d) { if (o.ok) o.ok(d); })
      .catch(function (e) {
        nudge(btn, 'is-failed');
        if (o.fail) o.fail(e);
        else toast(serverError(e), 'alert');
      })
      .then(function () { press.busy = Math.max(0, press.busy - 1); });
    return true;
  }
  function sendKey(btn, key) {
    const t = keyTarget();
    if (t === 'local') return remotePress(btn, 'remote', { do: 'key', key: key });
    if (t === 'cloud' && CLOUD_KEYS[key]) return cloudPress(btn, CLOUD_KEYS[key]);
    nudge(btn, 'is-dropped');
    return false;
  }
  // One key through SmartThings, which takes 12 a minute: past that, presses wait.
  function cloudPress(btn, key, o) {
    const now = Date.now();
    press.cloud = press.cloud.filter(function (at) { return now - at < 60000; });
    if (press.cloud.length >= 12) {
      nudge(btn, 'is-dropped');
      toast('SmartThings takes 12 keys a minute. Wait a moment.', 'warn');
      return false;
    }
    if (!remotePress(btn, 'samsung', { do: 'cloudKey', key: key }, Object.assign({ timeout: 20000 }, o))) return false;
    press.cloud.push(now);
    return true;
  }
  function volumePress(btn, what) { return remotePress(btn, 'volume', { do: what }); }

  function canPower() {
    const sam = obj(S.samsung);
    return (sam.connected === true && obj(sam.can).power === true) || !!keyTarget();
  }
  // A tap turns the TV off or on through SmartThings when it can, else wakes it over the network
  // when it is off, else sends the remote's Power key.
  function powerTap(btn) {
    const sam = obj(S.samsung);
    const r = obj(sam.remote);
    if (sam.connected === true && obj(sam.can).power === true) {
      const on = str(sam.power) !== 'on';
      remotePress(btn, 'samsung', { do: 'power', on: on }, { timeout: 20000, quiet: false, ok: function () {
        obj(S.samsung).power = on ? 'on' : 'off';
        toast(on ? 'Turning the TV on' : 'Turning the TV off');
        if (views.remote.built) views.remote.render();
      } });
      return;
    }
    if (keyTarget() === 'local' && r.canWake === true && (str(sam.power) === 'off' || str(r.state) === 'unreachable')) {
      remotePress(btn, 'remote', { do: 'wake' }, { ok: function () { toast('Waking the TV'); } });
      return;
    }
    sendKey(btn, 'KEY_POWER');
  }
  // A long press holds the remote's Power key for 3 seconds, as on the TV's own remote.
  function powerHold(btn) {
    if (keyTarget() !== 'local') { powerTap(btn); return; }
    remotePress(btn, 'remote', { do: 'hold', key: 'KEY_POWER', ms: 3000 }, { timeout: 20000, ok: function () { toast('Held Power for 3 seconds'); } });
  }
  // Play and pause what the Fire TV plays when it says so, else alternate the TV's own keys.
  function playTap(btn) {
    if (obj(S.media).active === true) {
      remotePress(btn, 'media', { do: 'toggle' }, { quiet: false });
      return;
    }
    const k = press.nextPlay;
    if (sendKey(btn, k)) press.nextPlay = k === 'KEY_PAUSE' ? 'KEY_PLAY' : 'KEY_PAUSE';
  }
  function inputKey(id) {
    const m = /^HDMI\s*([1-4])$/i.exec(str(id));
    if (m) return 'KEY_HDMI' + m[1];
    return /^(dtv|tv|digitaltv)$/i.test(str(id)) ? 'KEY_TV' : '';
  }

  // Wires a remote button: feedback the moment it is touched, one press per tap, and when asked
  // it repeats while held (arrows, volume) or does something else on a long press (Power).
  function pressable(btn, o) {
    let timer = 0, rep = 0, held = false;
    function stop() {
      clearTimeout(timer);
      clearInterval(rep);
      timer = rep = 0;
      btn.classList.remove('is-pressed', 'is-holding');
    }
    btn.addEventListener('pointerdown', function (e) {
      if (btn.disabled || (e.pointerType === 'mouse' && e.button !== 0)) return;
      stop();
      held = false;
      btn.classList.add('is-pressed');
      if (o.long) {
        btn.classList.add('is-holding');
        timer = setTimeout(function () { held = true; btn.classList.remove('is-holding'); o.long(btn); }, 700);
      } else if (o.repeat && o.repeat()) {
        timer = setTimeout(function () {
          held = true;
          o.fire(btn);
          rep = setInterval(function () { if (!btn.disabled) o.fire(btn); else stop(); }, 180);
        }, 420);
      }
    });
    for (const ev of ['pointerup', 'pointercancel', 'pointerleave']) btn.addEventListener(ev, stop);
    btn.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    btn.addEventListener('click', function () {
      if (held) { held = false; return; }
      stop();
      o.fire(btn);
    });
    return btn;
  }

  function openRemoteSettings() {
    show('settings');
    requestAnimationFrame(function () {
      const target = $('#set-remote');
      if (target) target.scrollIntoView({ block: 'start' });
    });
  }

  views.remote = {
    built: false,
    build: function () {
      const self = this;
      const v = $('#v-remote');
      this.head = pageHead('Samsung', ['TV ', h('em', { text: 'remote' })]);
      this.pad = card(1, 'Remote', 'span-7 card-remote');
      this.more = card(2, 'Picture & sound', 'span-5 card-picture');
      this.keyEl = {};
      const local = function () { return keyTarget() === 'local'; };
      const key = function (name, k, cls, label, ico, text) {
        const b = h('button', { class: cls, type: 'button', 'aria-label': label, 'data-fk': 'rc-' + name, disabled: true },
          ico ? icon(ico, 'rc-ico') : null, text ? h('span', { class: 'rc-text', text: text }) : null);
        pressable(b, { fire: function (btn) { sendKey(btn, k); }, repeat: /up|down|left|right/.test(name) ? local : null });
        self.keyEl[name] = b;
        return b;
      };
      const dpad = h('div', { class: 'dpad', role: 'group', 'aria-label': 'Arrows and OK' },
        key('up', 'KEY_UP', 'dp dp-up', 'Up', 'chevron'),
        key('right', 'KEY_RIGHT', 'dp dp-right', 'Right', 'chevron'),
        key('down', 'KEY_DOWN', 'dp dp-down', 'Down', 'chevron'),
        key('left', 'KEY_LEFT', 'dp dp-left', 'Left', 'chevron'),
        key('ok', 'KEY_ENTER', 'dp-ok', 'OK', null, 'OK'));
      const around = h('div', { class: 'dpad-wrap' },
        key('source', 'KEY_SOURCE', 'rc-btn rc-corner tl', 'Source', 'source', 'Source'),
        key('menu', 'KEY_MENU', 'rc-btn rc-corner tr', 'Menu', 'menu', 'Menu'),
        dpad,
        key('back', 'KEY_RETURN', 'rc-btn rc-corner bl', 'Back', 'undo', 'Back'),
        key('home', 'KEY_HOME', 'rc-btn rc-corner br', 'Home', 'home', 'Home'));
      this.power = pressable(h('button', { class: 'btn rc-pill rc-power', type: 'button', 'data-fk': 'rc-power', disabled: true }, icon('power'), h('span', { text: 'Power' })),
        { fire: powerTap, long: powerHold });
      this.play = pressable(h('button', { class: 'btn rc-pill rc-play', type: 'button', 'data-fk': 'rc-play', disabled: true }, icon('playpause'), h('span', { text: 'Play/Pause' })),
        { fire: playTap });
      const always = function () { return true; };
      this.volDown = pressable(h('button', { class: 'rocker-btn', type: 'button', 'aria-label': 'Volume Down', 'data-fk': 'rc-vol-down' }, icon('vol-down')),
        { fire: function (btn) { volumePress(btn, 'down'); }, repeat: always });
      this.volUp = pressable(h('button', { class: 'rocker-btn', type: 'button', 'aria-label': 'Volume Up', 'data-fk': 'rc-vol-up' }, icon('vol-up')),
        { fire: function (btn) { volumePress(btn, 'up'); }, repeat: always });
      this.mute = pressable(h('button', { class: 'btn rc-pill rc-mute', type: 'button', 'aria-label': 'Mute', 'data-fk': 'rc-mute' }, icon('mute'), h('span', { text: 'Mute' })),
        { fire: function (btn) { volumePress(btn, 'mute'); } });
      this.hint = h('div', { class: 'rc-hint' });
      this.inputs = h('div', { class: 'rc-inputs', role: 'group', 'aria-label': 'Inputs' });
      fill(this.pad.body, h('div', { class: 'rc' },
        this.hint,
        h('div', { class: 'rc-top' }, this.power, this.play),
        around,
        h('div', { class: 'rc-vol' },
          h('div', { class: 'rocker', role: 'group', 'aria-label': 'Volume' }, this.volDown, h('span', { class: 'rocker-label', 'aria-hidden': 'true', text: 'Volume' }), this.volUp),
          this.mute),
        h('p', { class: 'rc-note', text: 'Volume goes through the Fire TV, so the soundbar follows.' }),
        this.inputs));
      const grid = h('div', { class: 'grid' }, this.pad.el, this.more.el);
      stagger(grid);
      fill(v, [this.head.el, grid]);
      // Arrow keys, Enter and Backspace work the remote from a computer's keyboard.
      document.addEventListener('keydown', function (e) {
        if (current !== 'remote' || sheet || e.defaultPrevented || e.metaKey || e.ctrlKey || e.altKey) return;
        const a = document.activeElement;
        if (a && (/^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName) || (a.closest && a.closest('[role="radiogroup"]')))) return;
        if ((e.key === 'Enter' || e.key === ' ') && a && a.tagName === 'BUTTON') return;
        const names = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'ok', Backspace: 'back' };
        let btn = names[e.key] ? self.keyEl[names[e.key]] : null;
        if (e.key === '+' || e.key === '=') btn = self.volUp;
        else if (e.key === '-' || e.key === '_') btn = self.volDown;
        else if (e.key === 'm' || e.key === 'M') btn = self.mute;
        if (!btn || btn.disabled) return;
        e.preventDefault();
        nudge(btn, 'is-pressed');
        btn.click();
      });
    },
    render: function () {
      const sam = obj(S.samsung);
      const r = obj(sam.remote);
      const target = loaded ? keyTarget() : '';
      const eb = loaded ? tvLabel() : 'Samsung';
      if (this.head.eyebrow.textContent !== eb) this.head.eyebrow.textContent = eb;
      for (const k of Object.keys(this.keyEl)) this.keyEl[k].disabled = !target;
      this.power.disabled = !loaded || !canPower();
      this.play.disabled = !loaded || !(target || obj(S.media).active === true);
      this.pad.el.classList.toggle('no-keys', !target);
      setMeta(this.pad, !loaded ? '' : target === 'local' ? 'Over Wi-Fi' : target === 'cloud' ? 'SmartThings' : 'Volume only',
        target === 'local' && str(r.state) === 'connected' ? 'live' : '');
      if (!loaded) return;
      if (changed('r.hint', [target, canSetUpRemote(), sam.connected, r.state, r.error, r.canWake])) this.renderHint(target, sam, r);
      if (changed('r.inputs', [target, sam.connected, sam.inputs, sam.input, obj(sam.can).input])) this.renderInputs(target, sam);
      if (changed('r.more', [hasSamsung(), sam.connected, sam.modes, sam.mode, sam.sounds, sam.sound, sam.can, sam.volume, sam.muted])) this.renderMore(sam);
    },
    shown: function () {
      // Fresh power, volume and lists from SmartThings, at most every two minutes (it allows 12 calls a minute).
      const sam = obj(S.samsung);
      if (sam.connected !== true || Date.now() - (this.readAt || 0) < 120000) return;
      this.readAt = Date.now();
      act('samsung', { do: 'read' }, { timeout: 20000 }).catch(function () { /* the next look tries again */ });
    },
    renderHint: function (target, sam, r) {
      const kids = [];
      const setUp = function (text, quiet) {
        return h('button', { class: quiet ? 'quiet-link' : 'btn btn-sm rc-setup', type: 'button', on: { click: openRemoteSettings } }, h('span', { text: text }), icon('chevron'));
      };
      const st = str(r.state);
      const setup = canSetUpRemote();
      if (setup && st === 'changed') {
        kids.push(h('p', { class: 'amber-note', text: str(r.error) || 'The TV’s security certificate changed.' }), setUp('Trust the TV Again in Settings', true));
      } else if (!target) {
        kids.push(setup
          ? [h('p', { class: 'rc-hint-text', text: 'The arrows need the TV’s remote set up. It takes a minute.' }), setUp('Set Up the Remote')]
          : h('p', { class: 'rc-hint-text', text: 'Only volume works here. This version of Lucent can’t reach the Samsung.' }));
      } else if (target === 'cloud') {
        kids.push(h('p', { class: 'rc-hint-text', text: 'Keys go through SmartThings, so each one takes a moment.' }), setup ? setUp('Set Up the Remote for Instant Keys', true) : null);
      } else if (st === 'unreachable') {
        kids.push(h('p', { class: 'amber-note', text: 'The TV isn’t answering. It may be off.' + (r.canWake === true ? ' Tap Power to wake it.' : '') }));
      } else if (st === 'refused' || (str(r.error) && st !== 'connected')) {
        kids.push(h('p', { class: 'amber-note', text: str(r.error) || 'The TV said no to Lucent.' }));
      }
      fill(this.hint, kids);
      this.hint.hidden = !kids.length;
    },
    renderInputs: function (target, sam) {
      const cloud = sam.connected === true && obj(sam.can).input === true;
      let list = arr(sam.inputs).map(obj).filter(function (x) { return str(x.id); });
      if (!list.length && target === 'local') list = [{ id: 'HDMI1', name: 'HDMI 1' }, { id: 'HDMI2', name: 'HDMI 2' }];
      if (!cloud) list = target === 'local' ? list.filter(function (x) { return inputKey(x.id); }) : [];
      if (!list.length) { fill(this.inputs, null); this.inputs.hidden = true; return; }
      this.inputs.hidden = false;
      const now = str(sam.input);
      fill(this.inputs, [h('p', { class: 'room-label', text: 'Inputs' }), h('div', { class: 'chips rc-chips' }, list.map(function (x) {
        const id = str(x.id);
        const b = h('button', { class: 'chip-opt', type: 'button', 'aria-pressed': id === now ? 'true' : 'false', 'data-fk': 'in-' + id, text: str(x.name) || id });
        pressable(b, { fire: function (btn) {
          if (cloud) {
            remotePress(btn, 'samsung', { do: 'input', id: id }, { timeout: 20000, quiet: false, ok: function () {
              obj(S.samsung).input = id;
              toast('Switched to ' + (str(x.name) || id));
              views.remote.render();
            } });
          } else {
            sendKey(btn, inputKey(id));
          }
        } });
        return b;
      }))]);
    },
    renderMore: function (sam) {
      const self = this;
      if (!hasSamsung() || sam.connected !== true) {
        setMeta(this.more, '');
        fill(this.more.body, [
          h('p', { class: 'set-help', text: 'Connect SmartThings to change the picture and sound modes and the TV’s own volume from here.' }),
          hasSamsung() ? h('button', { class: 'quiet-link', type: 'button', on: { click: openSamsungSettings } }, h('span', { text: 'Set it up in Settings' }), icon('chevron')) : null
        ]);
        return;
      }
      setMeta(this.more, str(sam.power) === 'off' ? 'TV off' : '');
      const can = obj(sam.can);
      const pickSel = function (id, label, list, value, onPick) {
        const sel = h('select', { class: 'input select', id: id, 'data-fk': id }, list.map(function (m) { return h('option', { value: m, text: m }); }));
        if (value && list.indexOf(value) < 0) add(sel, h('option', { value: value, text: value }));
        if (!value) add(sel, h('option', { value: '', text: 'Not known yet', disabled: true }));
        sel.value = value || '';
        sel.addEventListener('change', function () {
          const v = sel.value;
          sel.disabled = true;
          onPick(v).then(function () { toast(label + ' set to ' + v); })
            .catch(function (e) { toast(serverError(e), 'alert'); sel.value = value || ''; })
            .then(function () { sel.disabled = false; });
        });
        return h('div', { class: 'set-row' }, h('div', { class: 'set-text' }, h('label', { class: 'set-label', for: id, text: label })), sel);
      };
      const modes = arr(sam.modes).map(str).filter(Boolean);
      const sounds = arr(sam.sounds).map(str).filter(Boolean);
      const kids = [];
      if (modes.length) kids.push(pickSel('rc-picture', 'Picture mode', modes, str(sam.mode), function (v) {
        return act('samsung', { do: 'mode', mode: v }, { timeout: 20000 }).then(function () { obj(S.samsung).mode = v; });
      }));
      if (sounds.length && can.sound !== false) kids.push(pickSel('rc-sound', 'Sound mode', sounds, str(sam.sound), function (v) {
        return act('samsung', { do: 'sound', mode: v }, { timeout: 20000 }).then(function () { obj(S.samsung).sound = v; });
      }));
      if (can.volume === true) kids.push(this.tvVolume(sam, can));
      if (!kids.length) kids.push(h('p', { class: 'set-help', text: 'The TV hasn’t listed its picture and sound modes yet. They show up while the TV is on.' }));
      void self;
      fill(this.more.body, kids);
    },
    // The TV's own speakers, through SmartThings: a secondary control, as the soundbar follows the Fire TV.
    tvVolume: function (sam, can) {
      const level = num(sam.volume);
      const muted = str(sam.muted) === 'muted';
      const value = h('span', { class: 'tvvol-value', text: muted ? 'Muted' : level !== null && level >= 0 ? String(Math.round(level)) : '–' });
      const range = h('input', { class: 'tvvol-range', id: 'rc-tvvol', type: 'range', min: '0', max: '100', step: '1', value: String(level !== null && level >= 0 ? Math.round(level) : 0), 'aria-label': 'TV speaker volume' });
      range.disabled = level === null || level < 0;
      range.addEventListener('input', function () { value.textContent = range.value; });
      range.addEventListener('change', function () {
        const n = parseInt(range.value, 10);
        act('samsung', { do: 'volume', level: n }, { timeout: 20000 }).then(function () { obj(S.samsung).volume = n; })
          .catch(function (e) { toast(serverError(e), 'alert'); range.value = String(level || 0); value.textContent = level !== null && level >= 0 ? String(level) : '–'; });
      });
      const step = function (up) {
        return pressable(h('button', { class: 'btn btn-icon btn-icon-sm', type: 'button', 'aria-label': up ? 'TV Volume Up' : 'TV Volume Down' }, icon(up ? 'vol-up' : 'vol-down')),
          { fire: function (btn) { remotePress(btn, 'samsung', { do: 'volumeStep', up: up }, { timeout: 20000, quiet: false }); } });
      };
      const muteBtn = can.mute === true ? pressable(h('button', { class: 'btn btn-sm' + (muted ? ' btn-primary' : ''), type: 'button', 'aria-pressed': muted ? 'true' : 'false' }, muted ? 'Unmute' : 'Mute TV'),
        { fire: function (btn) {
          remotePress(btn, 'samsung', { do: 'mute', on: !muted }, { timeout: 20000, quiet: false, ok: function () { obj(S.samsung).muted = muted ? 'unmuted' : 'muted'; views.remote.render(); } });
        } }) : null;
      return h('div', { class: 'set-row tvvol' },
        h('div', { class: 'set-text' },
          h('div', { class: 'tvvol-head' }, h('label', { class: 'set-label', for: 'rc-tvvol', text: 'TV speakers' }), value),
          h('p', { class: 'set-help', text: 'The TV’s own volume, through SmartThings. The soundbar follows the Volume buttons, so use those first.' })),
        h('div', { class: 'tvvol-row' }, step(false), range, step(true)),
        muteBtn);
    }
  };

  /* ------------------------------------------------------------------ drawing */

  function inkFor(c, paper) {
    if (c === 0) return PAPERS[paper] && PAPERS[paper].dark ? INK_LIGHT : INK_DARK;
    return COLOURS[c] || INK_LIGHT;
  }

  function paintPaper(ctx, W, H, paper) {
    ctx.save();
    if (paper === 1 || paper === 2) {
      ctx.fillStyle = paper === 1 ? '#F6F3EE' : '#F9E3E3';
      ctx.fillRect(0, 0, W, H);
      const g = ctx.createRadialGradient(W * 0.3, 0, 0, W * 0.3, 0, W * 0.9);
      g.addColorStop(0, 'rgba(255,255,255,0.55)');
      g.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, W, H);
    } else {
      const g = ctx.createLinearGradient(0, 0, W * 0.4, H);
      if (paper === 3) { g.addColorStop(0, '#1C2340'); g.addColorStop(1, '#121628'); }
      else { g.addColorStop(0, '#2A2623'); g.addColorStop(1, '#121110'); }
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, W, H);
      const r = ctx.createRadialGradient(W * 0.18, H * 0.05, 0, W * 0.18, H * 0.05, W * 0.75);
      r.addColorStop(0, paper === 3 ? 'rgba(150,170,255,0.12)' : 'rgba(255,236,215,0.10)');
      r.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = r;
      ctx.fillRect(0, 0, W, H);
    }
    ctx.restore();
  }

  // Draws one stroke with quadratic curves through the midpoints, so corners come out round.
  function paintStroke(ctx, p, W, H, w, colour) {
    const n = p.length >> 1;
    if (!n) return;
    const lw = Math.max(1, (w / 1000) * W);
    const X = function (i) { return (p[2 * i] / 10000) * W; };
    const Y = function (i) { return (p[2 * i + 1] / 10000) * H; };
    ctx.save();
    ctx.strokeStyle = colour;
    ctx.fillStyle = colour;
    ctx.lineWidth = lw;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    if (n === 1) {
      ctx.beginPath();
      ctx.arc(X(0), Y(0), lw / 2, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
      return;
    }
    ctx.beginPath();
    ctx.moveTo(X(0), Y(0));
    if (n === 2) {
      ctx.lineTo(X(1), Y(1));
    } else {
      for (let i = 1; i < n - 1; i++) ctx.quadraticCurveTo(X(i), Y(i), (X(i) + X(i + 1)) / 2, (Y(i) + Y(i + 1)) / 2);
      ctx.lineTo(X(n - 1), Y(n - 1));
    }
    ctx.stroke();
    ctx.restore();
  }

  // Smooths with a short moving average, drops points closer than the threshold and rounds
  // to whole units in the 0 to 10000 box. Distances are measured in widths of the box.
  function cleanPoints(raw, thin) {
    const n = raw.length >> 1;
    if (!n) return [];
    const r = function (v) { return clamp(Math.round(v), 0, 10000); };
    if (n === 1) {
      const x = r(raw[0]), y = r(raw[1]);
      return [x, y, x < 10000 ? x + 1 : x - 1, y];
    }
    const xs = new Array(n), ys = new Array(n);
    for (let i = 0; i < n; i++) {
      if (i === 0 || i === n - 1) { xs[i] = raw[2 * i]; ys[i] = raw[2 * i + 1]; continue; }
      xs[i] = (raw[2 * i - 2] + 2 * raw[2 * i] + raw[2 * i + 2]) / 4;
      ys[i] = (raw[2 * i - 1] + 2 * raw[2 * i + 1] + raw[2 * i + 3]) / 4;
    }
    const out = [r(xs[0]), r(ys[0])];
    let lx = xs[0], ly = ys[0];
    for (let i = 1; i < n; i++) {
      const dx = xs[i] - lx, dy = (ys[i] - ly) * 0.75;
      const far = dx * dx + dy * dy >= thin * thin;
      if (far) {
        out.push(r(xs[i]), r(ys[i]));
        lx = xs[i];
        ly = ys[i];
      } else if (i === n - 1) {
        // Keep the place where the pen lifted. Move the last kept point there instead of crowding it.
        if (out.length > 2) { out[out.length - 2] = r(xs[i]); out[out.length - 1] = r(ys[i]); }
        else if (r(xs[i]) !== out[0] || r(ys[i]) !== out[1]) out.push(r(xs[i]), r(ys[i]));
      }
    }
    if (out.length === 2) out.push(out[0] < 10000 ? out[0] + 1 : out[0] - 1, out[1]);
    return out;
  }

  // Turns the drawing into the TV's format and keeps it under every cap.
  function encodeStrokes(strokes) {
    let thin = 40;
    let out = [];
    for (let pass = 0; pass < 8; pass++) {
      out = [];
      let total = 0;
      for (const s of strokes.slice(0, MAX_STROKES)) {
        const p = cleanPoints(s.p, thin);
        if (!p.length) continue;
        out.push({ c: clamp(s.c | 0, 0, 7), w: clamp(Math.round(s.w), 4, 60), p: p });
        total += p.length / 2;
      }
      if (total <= MAX_POINTS && JSON.stringify(out).length < MAX_NOTE_BYTES - 4096) return out;
      thin *= 1.6;
    }
    return out;
  }

  function makePad(canvas, onChange, onLimit) {
    const ctx = canvas.getContext('2d');
    const layer = document.createElement('canvas');
    const lctx = layer.getContext('2d');
    const pad = { strokes: [], stash: null, active: null, paper: 0, colour: 0, width: 10, raw: 0, W: 0, H: 0, locked: false, rect: null };
    let raf = 0;

    function size() {
      const r = canvas.getBoundingClientRect();
      if (r.width < 2) return;
      const dpr = Math.min(3, window.devicePixelRatio || 1);
      const W = Math.round(r.width * dpr);
      const H = Math.round(r.width * 0.75 * dpr);
      if (W === pad.W && H === pad.H) return;
      pad.W = canvas.width = layer.width = W;
      pad.H = canvas.height = layer.height = H;
      repaint();
    }
    function repaint() {
      if (!pad.W) return;
      paintPaper(lctx, pad.W, pad.H, pad.paper);
      for (const s of pad.strokes) paintStroke(lctx, s.p, pad.W, pad.H, s.w, inkFor(s.c, pad.paper));
      frame();
    }
    function frame() {
      raf = 0;
      if (!pad.W) return;
      ctx.clearRect(0, 0, pad.W, pad.H);
      ctx.drawImage(layer, 0, 0);
      const a = pad.active;
      if (a) paintStroke(ctx, a.p, pad.W, pad.H, a.w, inkFor(a.c, pad.paper));
    }
    function schedule() { if (!raf) raf = requestAnimationFrame(frame); }

    function point(cx, cy) {
      const a = pad.active;
      const r = pad.rect;
      if (!a || !r || !r.width) return;
      const x = clamp(((cx - r.left) / r.width) * 10000, 0, 10000);
      const y = clamp(((cy - r.top) / r.height) * 10000, 0, 10000);
      const n = a.p.length;
      if (n >= 2) {
        const dx = x - a.p[n - 2], dy = (y - a.p[n - 1]) * 0.75;
        if (dx * dx + dy * dy < 144) return;
      }
      if (pad.raw >= MAX_RAW_POINTS) return;
      a.p.push(x, y);
      pad.raw++;
    }
    function down(e) {
      if (pad.active || pad.locked) return;
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      e.preventDefault();
      if (pad.strokes.length >= MAX_STROKES) { onLimit('That’s the most strokes one note can hold. Send it, or undo a few.'); return; }
      if (pad.raw >= MAX_RAW_POINTS) { onLimit('This note is full of ink. Send it, or undo a few strokes.'); return; }
      try { canvas.setPointerCapture(e.pointerId); } catch (err) { /* capture is a nicety */ }
      pad.rect = canvas.getBoundingClientRect();
      pad.active = { id: e.pointerId, c: pad.colour, w: pad.width, p: [] };
      point(e.clientX, e.clientY);
      schedule();
    }
    function move(e) {
      const a = pad.active;
      if (!a || e.pointerId !== a.id) return;
      e.preventDefault();
      const list = typeof e.getCoalescedEvents === 'function' ? e.getCoalescedEvents() : null;
      if (list && list.length) for (const ev of list) point(ev.clientX, ev.clientY);
      else point(e.clientX, e.clientY);
      schedule();
    }
    function up(e) {
      const a = pad.active;
      if (!a || e.pointerId !== a.id) return;
      if (e.type === 'pointerup') point(e.clientX, e.clientY);
      finish();
    }
    function finish() {
      const a = pad.active;
      if (!a) return;
      pad.active = null;
      if (a.p.length) {
        pad.strokes.push(a);
        pad.stash = null;
        paintStroke(lctx, a.p, pad.W, pad.H, a.w, inkFor(a.c, pad.paper));
      }
      schedule();
      onChange();
    }

    canvas.addEventListener('pointerdown', down);
    canvas.addEventListener('pointermove', move);
    canvas.addEventListener('pointerup', up);
    canvas.addEventListener('pointercancel', up);
    canvas.addEventListener('lostpointercapture', function (e) { if (pad.active && e.pointerId === pad.active.id) finish(); });
    // Older iOS ignores touch-action on canvas, so stop the page from scrolling under the pen too.
    canvas.addEventListener('touchstart', function (e) { e.preventDefault(); }, { passive: false });
    canvas.addEventListener('touchmove', function (e) { e.preventDefault(); }, { passive: false });
    if (window.ResizeObserver) new ResizeObserver(size).observe(canvas);
    window.addEventListener('resize', size);

    pad.size = size;
    pad.setPaper = function (i) { pad.paper = i; repaint(); };
    pad.undo = function () {
      if (pad.active) return;
      if (!pad.strokes.length && pad.stash) { pad.strokes = pad.stash; pad.stash = null; }
      else pad.strokes.pop();
      pad.raw = pad.strokes.reduce(function (t, s) { return t + s.p.length / 2; }, 0);
      repaint();
      onChange();
    };
    pad.clear = function () {
      if (!pad.strokes.length) return;
      pad.stash = pad.strokes;
      pad.strokes = [];
      pad.raw = 0;
      repaint();
      onChange();
    };
    pad.reset = function () { pad.strokes = []; pad.stash = null; pad.raw = 0; repaint(); onChange(); };
    return pad;
  }

  function paintNote(canvas, note) {
    const W = canvas.width, H = canvas.height;
    const ctx = canvas.getContext('2d');
    const paper = clamp(num(note.paper) || 0, 0, 3);
    paintPaper(ctx, W, H, paper);
    for (const s of arr(note.strokes).map(obj)) {
      const p = arr(s.p).filter(function (v) { return num(v) !== null; });
      if (p.length < 2) continue;
      paintStroke(ctx, p, W, H, clamp(num(s.w) || 8, 4, 60), inkFor(clamp(num(s.c) || 0, 0, 7), paper));
    }
  }

  views.draw = {
    built: false,
    build: function () {
      const self = this;
      const v = $('#v-draw');
      this.head = pageHead('A note for the TV', ['Draw ', h('em', { text: 'something' })]);
      const canvas = h('canvas', { class: 'pad-canvas', role: 'img', 'aria-label': 'Drawing area. Draw with a finger, pen or mouse.' });
      const sentMark = h('div', { class: 'sent-mark' }, checkGlyph());
      this.sent = h('div', { class: 'sent', role: 'status' }, sentMark, h('p', { class: 'sent-text' }, h('span', { class: 'sent-big', text: 'Sent ✓' }), h('span', { class: 'sent-small', text: 'It is writing itself on the TV' })));
      this.stage = h('div', { class: 'stage' }, h('div', { class: 'paper-wrap' }, canvas), this.sent);
      this.pad = makePad(canvas, function () { self.sync(); }, function (msg) { toast(msg, 'warn'); });

      const paperBtns = PAPERS.map(function (p, i) {
        return h('button', { type: 'button', role: 'radio', class: 'paper-sw paper-' + i, 'aria-label': p.name, title: p.name, data: { i: i } }, h('span', { class: 'paper-sw-name', text: p.name }));
      });
      this.paperGroup = h('div', { class: 'papers', role: 'radiogroup', 'aria-label': 'Paper' }, paperBtns);
      this.paperGroup.addEventListener('click', function (e) {
        const b = e.target.closest('button');
        if (!b) return;
        self.pad.setPaper(+b.dataset.i);
        save('paper', +b.dataset.i);
        self.sync();
      });

      const colourBtns = COLOUR_NAMES.map(function (name, i) {
        return h('button', { type: 'button', role: 'radio', class: 'swatch', 'aria-label': name, title: name, data: { i: i } }, h('span', { class: 'swatch-dot' }));
      });
      this.colourBtns = colourBtns;
      this.colourGroup = h('div', { class: 'swatches', role: 'radiogroup', 'aria-label': 'Ink colour' }, colourBtns);
      this.colourGroup.addEventListener('click', function (e) {
        const b = e.target.closest('button');
        if (!b) return;
        self.pad.colour = +b.dataset.i;
        self.sync();
      });

      const nibBtns = NIBS.map(function (n) {
        return h('button', { type: 'button', role: 'radio', class: 'nib', 'aria-label': n.name, title: n.name, data: { w: n.w } }, h('span', { class: 'nib-dot', vars: { '--nib': Math.max(3, Math.round(n.w * 0.7)) + 'px' } }));
      });
      this.nibGroup = h('div', { class: 'nibs', role: 'radiogroup', 'aria-label': 'Pen size' }, nibBtns);
      this.nibGroup.addEventListener('click', function (e) {
        const b = e.target.closest('button');
        if (!b) return;
        self.pad.width = +b.dataset.w;
        self.sync();
      });
      for (const g of [this.paperGroup, this.colourGroup, this.nibGroup]) radioKeys(g);

      this.undoBtn = h('button', { class: 'btn btn-icon', type: 'button', 'aria-label': 'Undo', title: 'Undo', on: { click: function () { self.pad.undo(); } } }, icon('undo'));
      this.clearBtn = h('button', { class: 'btn btn-icon', type: 'button', 'aria-label': 'Clear the drawing', title: 'Clear', on: { click: function () { self.pad.clear(); toast('Cleared. Undo brings it back.'); } } }, icon('trash'));

      this.text = h('textarea', { class: 'input note-input', id: 'note-text', rows: '2', maxLength: MAX_TEXT, placeholder: 'Add a line, if you like', enterkeyhint: 'done' });
      this.text.value = load('draft', '') || '';
      this.counter = h('span', { class: 'counter', 'aria-live': 'polite' });
      this.text.addEventListener('input', function () { save('draft', self.text.value); self.sync(); });
      this.pin = toggle({ id: 'note-pin', label: 'Keep on home screen', value: false });
      this.sendBtn = h('button', { class: 'btn btn-primary btn-lg btn-block send-btn', type: 'button', on: { click: function () { self.send(); } } }, icon('send'), h('span', { text: 'Send to the TV' }));

      const tools = h('div', { class: 'tools' },
        h('div', { class: 'tool-group' }, h('p', { class: 'tool-label', text: 'Ink' }), this.colourGroup),
        h('div', { class: 'tool-group tool-split' },
          h('div', null, h('p', { class: 'tool-label', text: 'Nib' }), this.nibGroup),
          h('div', { class: 'tool-edit' }, this.undoBtn, this.clearBtn)),
        h('div', { class: 'tool-group' }, h('p', { class: 'tool-label', text: 'Paper' }), this.paperGroup),
        h('div', { class: 'tool-group' },
          h('div', { class: 'tool-label-row' }, h('label', { class: 'tool-label', for: 'note-text', text: 'Words' }), this.counter),
          this.text),
        h('div', { class: 'switch-row' }, h('label', { class: 'switch-label', for: 'note-pin' }, h('span', { class: 'strong', text: 'Keep on home screen' }), h('span', { class: 'switch-help', text: 'Stays on the TV until you unpin it' })), this.pin.el),
        this.sendBtn);

      this.composer = card(1, 'Composer', 'span-12 card-composer');
      setMeta(this.composer, 'Shows up on the TV');
      fill(this.composer.body, h('div', { class: 'composer' }, this.stage, tools));
      this.history = card(2, 'Recent notes', 'span-12 card-notes');
      const grid = h('div', { class: 'grid' }, this.composer.el, this.history.el);
      stagger(grid);
      fill(v, [this.head.el, grid]);

      this.pad.paper = clamp(load('paper', 0) | 0, 0, 3);
      this.sync();
    },
    shown: function () {
      const pad = this.pad;
      requestAnimationFrame(function () { pad.size(); });
    },
    sync: function () {
      const pad = this.pad;
      $$('button', this.paperGroup).forEach(function (b, i) { const on = i === pad.paper; b.setAttribute('aria-checked', String(on)); b.tabIndex = on ? 0 : -1; });
      this.colourBtns.forEach(function (b, i) {
        const on = i === pad.colour;
        b.setAttribute('aria-checked', String(on));
        b.tabIndex = on ? 0 : -1;
        b.style.setProperty('--sw', inkFor(i, pad.paper));
      });
      $$('button', this.nibGroup).forEach(function (b) { const on = +b.dataset.w === pad.width; b.setAttribute('aria-checked', String(on)); b.tabIndex = on ? 0 : -1; });
      this.nibGroup.style.setProperty('--ink', inkFor(pad.colour, pad.paper));
      this.nibGroup.classList.toggle('on-dark', PAPERS[pad.paper].dark);
      this.stage.classList.toggle('light-paper', !PAPERS[pad.paper].dark);
      this.undoBtn.disabled = !pad.strokes.length && !pad.stash;
      this.clearBtn.disabled = !pad.strokes.length;
      const len = this.text.value.length;
      this.counter.textContent = len ? len + ' / ' + MAX_TEXT : '';
      this.counter.classList.toggle('near', len > MAX_TEXT - 30);
      this.sendBtn.disabled = this.sending || (!pad.strokes.length && !this.text.value.trim());
    },
    send: async function () {
      const pad = this.pad;
      const text = this.text.value.trim().slice(0, MAX_TEXT);
      if (!pad.strokes.length && !text) { toast('Draw something or add a line first.'); return; }
      const body = { text: text, strokes: encodeStrokes(pad.strokes), paper: pad.paper, pin: this.pin.value };
      let size = 0;
      try { size = new TextEncoder().encode(JSON.stringify(body)).length; } catch (e) { size = JSON.stringify(body).length * 2; }
      if (size > MAX_NOTE_BYTES) { toast('That note has too much detail to send. Undo a few strokes.', 'warn'); return; }
      this.sending = true;
      this.sync();
      this.sendBtn.classList.add('busy');
      try {
        await act('note', body, { timeout: 15000 });
        this.celebrate();
      } catch (e) {
        toast(e.message, 'alert');
      }
      this.sending = false;
      this.sendBtn.classList.remove('busy');
      this.sync();
    },
    celebrate: function () {
      const self = this;
      this.pad.locked = true;
      this.stage.classList.remove('sent-on');
      void this.stage.offsetWidth;
      this.stage.classList.add('sent-on');
      setTimeout(function () {
        self.pad.reset();
        self.text.value = '';
        save('draft', '');
        self.pin.set(false);
        self.stage.classList.remove('sent-on');
        self.pad.locked = false;
        self.sync();
      }, 2600);
    },
    render: function () {
      if (!loaded) { skeleton(this.history, 2); return; }
      const notes = arr(S.notes).map(obj).filter(function (n) { return str(n.id); });
      if (!changed('d.notes', [notes, hour12()])) return;
      setMeta(this.history, notes.length ? plural(notes.length, 'note') + (notes.some(function (n) { return n.pinned; }) ? ' · ' + notes.filter(function (n) { return n.pinned; }).length + ' pinned' : '') : '');
      if (!notes.length) { fill(this.history.body, empty('Notes you send show up here. Pinned ones stay on the TV’s home screen.')); return; }
      notes.sort(function (a, b) { return (num(b.at) || 0) - (num(a.at) || 0); });
      const items = notes.slice(0, 24).map(noteCard);
      fill(this.history.body, h('div', { class: 'notes-grid' }, items));
    }
  };

  function radioKeys(group) {
    group.addEventListener('keydown', function (e) {
      const btns = $$('button', group);
      const i = btns.indexOf(document.activeElement);
      if (i < 0) return;
      let j = -1;
      if (e.key === 'ArrowRight' || e.key === 'ArrowDown') j = (i + 1) % btns.length;
      else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') j = (i - 1 + btns.length) % btns.length;
      if (j < 0) return;
      e.preventDefault();
      btns[j].focus();
      btns[j].click();
    });
  }

  function noteCard(n) {
    const canvas = h('canvas', { class: 'note-canvas', width: '480', height: '360', role: 'img', 'aria-label': 'Drawing from ' + (str(n.from) || 'someone') });
    paintNote(canvas, n);
    const pinBtn = h('button', { class: 'btn btn-sm note-btn' + (n.pinned ? ' on' : ''), type: 'button', 'data-fk': 'pin-' + n.id, 'aria-pressed': n.pinned ? 'true' : 'false' }, icon('pin'), n.pinned ? 'Pinned' : 'Pin');
    pinBtn.addEventListener('click', async function () {
      const to = !n.pinned;
      try {
        await act('notepin', { id: n.id, pin: to });
        n.pinned = to;
        toast(to ? 'Pinned to the home screen' : 'Unpinned');
      } catch (e) { toast(e.message, 'alert'); }
    });
    const delBtn = h('button', { class: 'btn btn-sm btn-icon-sm note-btn', type: 'button', 'aria-label': 'Delete note', 'data-fk': 'del-' + n.id }, icon('trash'));
    confirmTap(delBtn, 'Delete?', async function () {
      try {
        await act('notedel', { id: n.id });
        S.notes = arr(S.notes).filter(function (x) { return obj(x).id !== n.id; });
        views.draw.render();
      } catch (e) { toast(e.message, 'alert'); }
    });
    return h('article', { class: 'note' + (n.pinned ? ' pinned' : '') },
      h('div', { class: 'note-paper' }, canvas, n.pinned ? h('span', { class: 'badge', text: 'On home screen' }) : null),
      str(n.text) ? h('p', { class: 'note-text', text: str(n.text) }) : null,
      h('div', { class: 'note-foot' },
        h('p', { class: 'note-meta' }, (str(n.from) || 'Someone'), num(n.at) ? [' · ', agoSpan(n.at)] : null),
        h('div', { class: 'note-actions' }, pinBtn, delBtn)));
  }

  /* ------------------------------------------------------------------ photos */

  const thumbs = new Map();

  function thumbURL(id) {
    if (!PHOTO_ID.test(id)) return Promise.reject(new Error('bad id'));
    const have = thumbs.get(id);
    if (have) return have.promise;
    const entry = { url: null, promise: null };
    entry.promise = api('/api/photo/' + id + '.thumb.jpg', { blob: true, prio: 1, timeout: 20000 }).then(function (r) {
      if (r.status === 401) { toPairing(str(obj(r.data).error)); throw new Error('unpaired'); }
      if (!r.ok || !r.data) throw new Error('status ' + r.status);
      const url = URL.createObjectURL(r.data);
      if (thumbs.get(id) !== entry) { URL.revokeObjectURL(url); throw new Error('gone'); }
      entry.url = url;
      return url;
    });
    entry.promise.catch(function () { if (thumbs.get(id) === entry && !entry.url) thumbs.delete(id); });
    thumbs.set(id, entry);
    return entry.promise;
  }
  function pruneThumbs(keep) {
    thumbs.forEach(function (t, id) {
      if (keep.has(id)) return;
      if (t.url) URL.revokeObjectURL(t.url);
      thumbs.delete(id);
    });
  }
  function dropThumbs() { pruneThumbs(new Set()); }

  // Reads DateTimeOriginal from the EXIF block of a JPEG, or 0.
  function exifTaken(buf) {
    try {
      const v = new DataView(buf);
      if (v.byteLength < 12 || v.getUint16(0) !== 0xFFD8) return 0;
      let off = 2;
      while (off + 4 <= v.byteLength) {
        const marker = v.getUint16(off);
        if ((marker & 0xFF00) !== 0xFF00 || marker === 0xFFDA || marker === 0xFFD9) return 0;
        const len = v.getUint16(off + 2);
        if (len < 2) return 0;
        if (marker === 0xFFE1 && off + 10 <= v.byteLength && v.getUint32(off + 4) === 0x45786966 && v.getUint16(off + 8) === 0) {
          return tiffTaken(v, off + 10, Math.min(v.byteLength, off + 2 + len));
        }
        off += 2 + len;
      }
    } catch (e) { /* not worth failing an upload over */ }
    return 0;
  }
  function tiffTaken(v, t, end) {
    if (t + 8 > end) return 0;
    const order = v.getUint16(t);
    const le = order === 0x4949;
    if (!le && order !== 0x4D4D) return 0;
    const u16 = function (o) { return o + 2 <= end ? v.getUint16(o, le) : 0; };
    const u32 = function (o) { return o + 4 <= end ? v.getUint32(o, le) : 0; };
    if (u16(t + 2) !== 42) return 0;
    const find = function (ifd, tag) {
      if (ifd < t || ifd + 2 > end) return null;
      const count = u16(ifd);
      for (let i = 0; i < count && i < 512; i++) {
        const e = ifd + 2 + i * 12;
        if (e + 12 > end) return null;
        if (u16(e) === tag) return { type: u16(e + 2), count: u32(e + 4), value: u32(e + 8), at: e + 8 };
      }
      return null;
    };
    const ascii = function (e) {
      if (!e || e.type !== 2) return '';
      const start = e.count <= 4 ? e.at : t + e.value;
      let s = '';
      for (let i = 0; i < e.count && start + i < end; i++) {
        const ch = v.getUint8(start + i);
        if (!ch) break;
        s += String.fromCharCode(ch);
      }
      return s;
    };
    const ptr = find(t + u32(t + 4), 0x8769);
    if (!ptr) return 0;
    const exif = t + ptr.value;
    const when = ascii(find(exif, 0x9003));
    const m = /^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(when);
    if (!m || m[1] === '0000') return 0;
    const offs = /^([+-])(\d{2}):(\d{2})$/.exec(ascii(find(exif, 0x9011)));
    let ms;
    if (offs) {
      const sign = offs[1] === '-' ? -1 : 1;
      ms = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]) - sign * (+offs[2] * 60 + +offs[3]) * 60000;
    } else {
      ms = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]).getTime();
    }
    return isFinite(ms) && ms > 0 ? ms : 0;
  }

  // Decodes with EXIF rotation applied. createImageBitmap knows the option where it is
  // supported, and an image element applies the rotation by default everywhere else.
  async function decodeImage(file) {
    if (typeof createImageBitmap === 'function') {
      try {
        const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
        return { image: bmp, width: bmp.width, height: bmp.height, done: function () { if (bmp.close) bmp.close(); } };
      } catch (e) { /* fall back to an image element */ }
    }
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.decoding = 'async';
    try {
      await new Promise(function (res, rej) {
        img.onload = res;
        img.onerror = function () { rej(new Error('Couldn’t read this photo.')); };
        img.src = url;
      });
    } catch (e) {
      URL.revokeObjectURL(url);
      throw e;
    }
    return { image: img, width: img.naturalWidth, height: img.naturalHeight, done: function () { URL.revokeObjectURL(url); } };
  }
  function toJpeg(canvas, q) {
    return new Promise(function (res, rej) {
      try {
        canvas.toBlob(function (b) { if (b) res(b); else rej(new Error('Couldn’t encode this photo.')); }, 'image/jpeg', q);
      } catch (e) { rej(e); }
    });
  }

  // Re-encodes a picked photo: rotation applied, every bit of EXIF and GPS gone, long side at most 3840.
  async function preparePhoto(file, preview) {
    const okType = /^image\/(jpeg|png)$/i.test(file.type) || (!file.type && /\.(jpe?g|png)$/i.test(file.name));
    if (!okType) throw new Error('Only JPEG and PNG photos can go on the TV.');
    let taken = 0;
    if (/jpe?g/i.test(file.type) || /\.jpe?g$/i.test(file.name)) {
      try { taken = exifTaken(await file.slice(0, 256 * 1024).arrayBuffer()); } catch (e) { taken = 0; }
    }
    if (!taken) taken = file.lastModified || Date.now();
    let src;
    try { src = await decodeImage(file); } catch (e) { throw new Error('Couldn’t read this photo.'); }
    if (!src.width || !src.height) { src.done(); throw new Error('Couldn’t read this photo.'); }
    let long = 3840;
    let blob = null;
    let w = 0, hgt = 0;
    for (let pass = 0; pass < 3 && !blob; pass++) {
      const scale = Math.min(1, long / Math.max(src.width, src.height));
      w = Math.max(1, Math.round(src.width * scale));
      hgt = Math.max(1, Math.round(src.height * scale));
      const c = document.createElement('canvas');
      c.width = w;
      c.height = hgt;
      const ctx = c.getContext('2d');
      ctx.fillStyle = '#FFFFFF';
      ctx.fillRect(0, 0, w, hgt);
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(src.image, 0, 0, w, hgt);
      if (preview && pass === 0) {
        const pctx = preview.getContext('2d');
        const s = Math.min(w, hgt);
        pctx.drawImage(c, (w - s) / 2, (hgt - s) / 2, s, s, 0, 0, preview.width, preview.height);
      }
      const b = await toJpeg(c, 0.9);
      c.width = c.height = 0;
      if (b.size <= 14 * 1024 * 1024 - 65536) blob = b;
      else long = Math.round(long * 0.75);
    }
    src.done();
    if (!blob) throw new Error('This photo is too big, even after shrinking.');
    return { blob: blob, taken: Math.floor(taken), w: w, h: hgt };
  }

  function uploadPhoto(prep, caption, onProgress) {
    return acquire(0, 11).then(function (release) {
      return new Promise(function (resolve, reject) {
        const xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/photo');
        xhr.setRequestHeader('X-Lucent', '1');
        xhr.setRequestHeader('Content-Type', 'image/jpeg');
        if (caption) xhr.setRequestHeader('X-Caption', encodeURIComponent(caption.slice(0, 60)));
        if (prep.taken > 0 && prep.taken < 1e15) xhr.setRequestHeader('X-Taken', String(prep.taken));
        xhr.timeout = 180000;
        xhr.upload.onprogress = function (e) { if (e.lengthComputable) onProgress(e.loaded / e.total); };
        xhr.onload = function () {
          let data = {};
          try { data = obj(JSON.parse(xhr.responseText || '{}')); } catch (e) { data = {}; }
          resolve({ status: xhr.status, ok: xhr.status >= 200 && xhr.status < 300, data: data });
        };
        xhr.onerror = function () { reject(new Error('Can’t reach the TV.')); };
        xhr.ontimeout = function () { reject(new Error('The upload took too long.')); };
        xhr.send(prep.blob);
      }).finally(release);
    });
  }

  const uploads = { queue: [], running: false };

  views.photos = {
    built: false,
    build: function () {
      const self = this;
      const v = $('#v-photos');
      this.head = pageHead('The wall', ['Photos ', h('em', { text: 'on the TV' })]);

      this.up = card(1, 'Add photos', 'span-7 card-upload');
      const file = h('input', { class: 'sr-only', id: 'file', type: 'file', accept: 'image/jpeg,image/png', multiple: true });
      file.addEventListener('change', function () {
        if (file.files && file.files.length) self.addFiles(Array.prototype.slice.call(file.files));
        file.value = '';
      });
      const drop = h('label', { class: 'drop', for: 'file' },
        h('span', { class: 'drop-ico' }, icon('upload')),
        h('span', { class: 'drop-title', text: 'Choose photos' }),
        h('span', { class: 'drop-sub', text: 'JPEG or PNG. Lucent resizes them here and strips location data before anything leaves this device.' }));
      ['dragenter', 'dragover'].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.add('over'); }); });
      ['dragleave', 'drop'].forEach(function (ev) { drop.addEventListener(ev, function () { drop.classList.remove('over'); }); });
      drop.addEventListener('drop', function (e) {
        e.preventDefault();
        const files = e.dataTransfer && e.dataTransfer.files ? Array.prototype.slice.call(e.dataTransfer.files) : [];
        if (files.length) self.addFiles(files);
      });
      this.caption = h('input', { class: 'input', id: 'photo-caption', type: 'text', maxLength: 60, placeholder: 'Paris, June 2024', autocomplete: 'off', enterkeyhint: 'done' });
      this.queueEl = h('ul', { class: 'queue', 'aria-label': 'Uploads', 'aria-live': 'polite' });
      fill(this.up.body, [file, drop,
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'photo-caption', text: 'Caption for the next photos (optional)' }), this.caption),
        this.queueEl]);

      this.store = card(2, 'Storage & album', 'span-5 card-store');
      this.storeTop = h('div', { class: 'store-top' });
      this.album = choice({ label: 'Album', options: [['nature', 'Nature'], ['ours', 'Ours'], ['both', 'Both']], onChange: function (v) { return setSetting('album', v); } });
      this.rotate = choice({ label: 'Change the photo', options: [['1m', 'Every min'], ['5m', '5 min'], ['10m', '10 min'], ['hour', 'Hourly'], ['day', 'Daily'], ['off', 'Never']], onChange: function (v) { return setSetting('rotate', v); } });
      fill(this.store.body, [this.storeTop,
        h('div', { class: 'set-row' }, h('div', { class: 'set-text' }, h('p', { class: 'set-label', text: 'Which photos' }), h('p', { class: 'set-help', text: 'Nature is Lucent’s own landscapes. Ours is what you add here.' })), this.album.el),
        h('div', { class: 'set-row' }, h('div', { class: 'set-text' }, h('p', { class: 'set-label', text: 'Change the photo' })), this.rotate.el)]);

      this.gallery = card(3, 'On the wall', 'span-12 card-gallery');
      this.grid = h('div', { class: 'gallery' });
      fill(this.gallery.body, this.grid);
      this.io = 'IntersectionObserver' in window ? new IntersectionObserver(function (entries) {
        for (const en of entries) {
          if (!en.isIntersecting) continue;
          self.io.unobserve(en.target);
          self.loadTile(en.target);
        }
      }, { rootMargin: '300px 0px' }) : null;

      const grid = h('div', { class: 'grid' }, this.up.el, this.store.el, this.gallery.el);
      stagger(grid);
      fill(v, [this.head.el, grid]);
    },
    render: function () {
      if (!loaded) { skeleton(this.store, 3); skeleton(this.gallery, 2); return; }
      const p = obj(S.photos);
      const st = settings();
      this.album.set(str(st.album) || str(p.album) || 'both');
      this.rotate.set(str(st.rotate));
      if (changed('p.store', [p.count, p.bytes, p.freeBytes, p.limit])) {
        const count = num(p.count) !== null ? p.count : arr(p.items).length;
        const used = num(p.bytes) || 0;
        const free = num(p.freeBytes);
        const share = free !== null && used + free > 0 ? used / (used + free) : 0;
        const fillEl = h('div', { class: 'meter-fill', vars: { '--p': clamp(share, 0.005, 1).toFixed(4) } });
        fill(this.storeTop, [
          h('div', { class: 'store-count' }, bigNum('photos', count, 'store-num'), h('p', { class: 'store-of', text: num(p.limit) !== null ? 'of ' + p.limit + ' photos' : plural(count, 'photo') })),
          h('div', { class: 'meter', role: 'meter', 'aria-label': 'Photo storage used', 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': String(Math.round(share * 100)) }, fillEl),
          h('p', { class: 'store-text', text: fmtBytes(used) + ' used' + (free !== null ? ' · ' + fmtBytes(free) + ' free on the TV' : '') })
        ]);
      }
      const items = arr(p.items).map(obj).filter(function (x) { return PHOTO_ID.test(str(x.id)); })
        .sort(function (a, b) { return (num(b.added) || 0) - (num(a.added) || 0); });
      pruneThumbs(new Set(items.map(function (x) { return x.id; })));
      if (!changed('p.items', items)) return;
      setMeta(this.gallery, items.length ? plural(items.length, 'photo') : '');
      const self = this;
      if (!items.length) {
        fill(this.grid, empty('No photos of yours yet. Add some above and they join the wallpaper rotation.'));
        return;
      }
      fill(this.grid, items.map(function (it) {
        const img = h('img', { class: 'tile-img', alt: '', decoding: 'async' });
        const t = h('button', { class: 'tile', type: 'button', 'data-fk': 'tile-' + it.id, data: { id: it.id }, 'aria-label': (str(it.caption) || 'Photo') + (num(it.taken) ? ', ' + fmtDate(it.taken, { day: 'numeric', month: 'short', year: 'numeric' }) : '') },
          img, str(it.caption) ? h('span', { class: 'tile-cap', text: str(it.caption) }) : null);
        t.addEventListener('click', function () { openPhoto(it); });
        const cached = thumbs.get(it.id);
        if (cached && cached.url) { img.src = cached.url; img.classList.add('ready'); }
        else if (self.io) self.io.observe(t);
        else self.loadTile(t);
        return t;
      }));
    },
    loadTile: function (tile) {
      const img = $('img', tile);
      thumbURL(tile.dataset.id).then(function (url) {
        img.onload = function () { img.classList.add('ready'); };
        img.src = url;
      }).catch(function () { tile.classList.add('broken'); });
    },
    addFiles: function (files) {
      const caption = this.caption.value.trim().slice(0, 60);
      this.caption.value = '';
      for (const f of files) {
        const item = { file: f, caption: caption, row: uploadRow(f) };
        uploads.queue.push(item);
        this.queueEl.appendChild(item.row.el);
      }
      runUploads();
    }
  };

  function uploadRow(f) {
    const preview = h('canvas', { class: 'q-thumb', width: '96', height: '96', 'aria-hidden': 'true' });
    const status = h('span', { class: 'q-status', text: 'Waiting' });
    const bar = h('div', { class: 'q-fill' });
    const dismiss = h('button', { class: 'btn btn-icon btn-icon-sm q-x', type: 'button', 'aria-label': 'Dismiss', hidden: true }, icon('x'));
    const el = h('li', { class: 'q-row' }, preview,
      h('div', { class: 'q-main' }, h('p', { class: 'q-name', text: f.name || 'Photo' }), status, h('div', { class: 'q-bar' }, bar)),
      dismiss);
    dismiss.addEventListener('click', function () { el.remove(); });
    return {
      el: el,
      preview: preview,
      set: function (text, p, kind) {
        status.textContent = text;
        el.className = 'q-row' + (kind ? ' ' + kind : '');
        bar.style.setProperty('--p', clamp(p, 0, 1).toFixed(3));
        dismiss.hidden = kind !== 'failed';
      }
    };
  }

  async function runUploads() {
    if (uploads.running) return;
    uploads.running = true;
    let added = 0;
    const finished = [];
    while (uploads.queue.length) {
      const item = uploads.queue.shift();
      const row = item.row;
      row.set('Preparing', 0.03, 'busy');
      let prep;
      try {
        prep = await preparePhoto(item.file, row.preview);
      } catch (e) {
        row.set(e.message || 'Couldn’t read this photo.', 0, 'failed');
        continue;
      }
      const sizeText = fmtBytes(item.file.size) + ' → ' + fmtBytes(prep.blob.size);
      let done = false;
      for (let attempt = 0; attempt < 3 && !done; attempt++) {
        row.set('Uploading · ' + sizeText, 0.05, 'busy');
        let r;
        try {
          r = await uploadPhoto(prep, item.caption, function (p) { row.set('Uploading ' + Math.round(p * 100) + '% · ' + sizeText, 0.05 + p * 0.9, 'busy'); });
        } catch (e) {
          row.set(e.message, 0, 'failed');
          break;
        }
        if (r.status === 429) { row.set('The TV asked to slow down. Retrying…', 0.05, 'busy'); await sleep(2000); continue; }
        if (r.status === 401) { row.set('Not paired', 0, 'failed'); toPairing(str(r.data.error)); uploads.running = false; return; }
        if (r.ok) {
          done = true;
          added++;
          row.set('On the TV · ' + fmtBytes(prep.blob.size), 1, 'done');
          finished.push(row);
        } else {
          row.set(errorText(r.status, r.data), 0, 'failed');
          break;
        }
      }
    }
    uploads.running = false;
    // Finished rows stay until the whole batch is done, then fade together.
    setTimeout(function () {
      for (const row of finished) {
        row.el.classList.add('leaving');
        setTimeout(function () { row.el.remove(); }, 500);
      }
    }, 4500);
    if (added) { toast(added === 1 ? 'Photo added to the wall' : added + ' photos added to the wall'); pollSoon(); }
  }

  function openPhoto(it) {
    const id = it.id;
    let full = null;
    let closed = false;
    const img = h('img', { class: 'sheet-img', alt: str(it.caption) || 'Photo' });
    const cached = thumbs.get(id);
    if (cached && cached.url) img.src = cached.url;
    const frame = h('div', { class: 'sheet-frame' }, img);
    if (num(it.w) && num(it.h)) frame.style.setProperty('--ar', String(it.w / it.h));
    api('/api/photo/' + id + '.jpg', { blob: true, timeout: 30000 }).then(function (r) {
      if (closed || !r.ok || !r.data) return;
      full = URL.createObjectURL(r.data);
      img.src = full;
      img.classList.add('full');
    }).catch(function () { /* the thumbnail stays */ });

    const title = h('h2', { class: 'sheet-title', text: str(it.caption) || 'Untitled' });
    const meta = [
      num(it.taken) ? 'Taken ' + fmtDate(it.taken, { day: 'numeric', month: 'long', year: 'numeric' }) : '',
      str(it.from) ? 'added by ' + str(it.from) : '',
      num(it.w) && num(it.h) ? it.w + '\u00A0×\u00A0' + it.h : ''
    ].filter(Boolean).join(' · ');
    const showBtn = h('button', { class: 'btn btn-primary btn-block', type: 'button' }, icon('tv'), 'Show on the TV now');
    showBtn.addEventListener('click', async function () {
      try { await act('photoshow', { id: id }); toast('On the TV now'); } catch (e) { toast(e.message, 'alert'); }
    });
    const nameInput = h('input', { class: 'input', id: 'rename', type: 'text', maxLength: 60, value: str(it.caption), placeholder: 'Caption', enterkeyhint: 'done', autocomplete: 'off' });
    const renameForm = h('form', { class: 'rename', hidden: true },
      h('label', { class: 'label', for: 'rename', text: 'Caption' }),
      h('div', { class: 'inline-form' }, nameInput, h('button', { class: 'btn', type: 'submit' }, 'Save')));
    renameForm.addEventListener('submit', async function (e) {
      e.preventDefault();
      const cap = nameInput.value.trim().slice(0, 60);
      try {
        await act('photocap', { id: id, caption: cap });
        it.caption = cap;
        title.textContent = cap || 'Untitled';
        renameForm.hidden = true;
        toast('Renamed');
      } catch (err) { toast(err.message, 'alert'); }
    });
    const renameBtn = h('button', { class: 'btn btn-block', type: 'button', on: { click: function () { renameForm.hidden = !renameForm.hidden; if (!renameForm.hidden) nameInput.focus(); } } }, icon('pen'), 'Rename');
    const delBtn = h('button', { class: 'btn btn-block btn-danger', type: 'button' }, icon('trash'), 'Delete');
    confirmTap(delBtn, 'Tap again to delete this photo', async function () {
      try {
        await act('photodel', { id: id });
        const p = obj(S.photos);
        p.items = arr(p.items).filter(function (x) { return obj(x).id !== id; });
        if (num(p.count) !== null) p.count = Math.max(0, p.count - 1);
        closeSheet();
        toast('Deleted');
        if (views.photos.built) views.photos.render();
      } catch (e) { toast(e.message, 'alert'); }
    });
    openSheet({
      label: str(it.caption) || 'Photo',
      content: [frame, h('div', { class: 'sheet-body' }, title, meta ? h('p', { class: 'sheet-meta', text: meta }) : null,
        h('div', { class: 'sheet-actions' }, showBtn, h('div', { class: 'sheet-pair' }, renameBtn, delBtn)), renameForm)],
      onClose: function () { closed = true; if (full) URL.revokeObjectURL(full); }
    });
  }

  /* ------------------------------------------------------------------ plans */

  views.plans = {
    built: false,
    build: function () {
      const self = this;
      const v = $('#v-plans');
      this.head = pageHead('Shared', ['Plans ', h('em', { text: 'for us' })]);
      this.agenda = card(1, 'This week', 'span-7 card-agenda');
      this.cds = card(2, 'Countdowns', 'card-cds');
      this.list = card(3, 'The list', 'card-list');

      this.cdList = h('ul', { class: 'cd-list' });
      const cdTitle = h('input', { class: 'input', id: 'cd-title', type: 'text', maxLength: 40, placeholder: 'Wedding', autocomplete: 'off' });
      const cdDate = h('input', { class: 'input input-date', id: 'cd-date', type: 'date' });
      const cdForm = h('form', { class: 'cd-form' },
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'cd-title', text: 'Counting down to' }), cdTitle),
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'cd-date', text: 'Date' }), cdDate),
        h('button', { class: 'btn', type: 'submit' }, icon('plus'), 'Add'));
      cdForm.addEventListener('submit', async function (e) {
        e.preventDefault();
        const t = cdTitle.value.trim().slice(0, 40);
        const d = cdDate.value;
        if (!t || !/^\d{4}-\d{2}-\d{2}$/.test(d)) { toast('Give it a name and a date.', 'warn'); return; }
        const list = arr(obj(S.calendar).countdowns).map(obj).map(function (c) { return { title: str(c.title), date: str(c.date) }; });
        if (list.length >= 6) { toast('Six countdowns is the most the TV keeps.', 'warn'); return; }
        list.push({ title: t, date: d });
        try {
          await setSetting('countdowns', list);
          const cal = obj(S.calendar);
          cal.countdowns = arr(cal.countdowns).concat([{ title: t, date: d, days: daysUntil(d) }]);
          S.calendar = cal;
          cdTitle.value = '';
          cdDate.value = '';
          self.render();
        } catch (err) { toast(err.message, 'alert'); }
      });
      fill(this.cds.body, [this.cdList, cdForm]);

      this.items = h('ul', { class: 'items' });
      const itemInput = h('input', { class: 'input', id: 'list-add', type: 'text', maxLength: 80, placeholder: 'Oat milk', autocomplete: 'off', enterkeyhint: 'done', 'aria-label': 'Add to the list' });
      const itemForm = h('form', { class: 'inline-form list-form' }, itemInput, h('button', { class: 'btn btn-icon', type: 'submit', 'aria-label': 'Add' }, icon('plus')));
      itemForm.addEventListener('submit', async function (e) {
        e.preventDefault();
        const text = itemInput.value.trim().slice(0, 80);
        if (!text) return;
        itemInput.value = '';
        const temp = { id: 'tmp' + Date.now(), text: text, done: false, by: personName() || 'You', at: tvNow(), temp: true };
        S.list = arr(S.list).concat([temp]);
        self.render();
        try { await act('list', { op: 'add', text: text }); } catch (err) { toast(err.message, 'alert'); S.list = arr(S.list).filter(function (x) { return x !== temp; }); self.render(); }
      });
      this.clearBtn = h('button', { class: 'btn btn-quiet btn-sm', type: 'button' }, 'Clear done');
      this.clearBtn.addEventListener('click', async function () {
        S.list = arr(S.list).filter(function (x) { return !obj(x).done; });
        self.render();
        try { await act('list', { op: 'clear' }); } catch (err) { toast(err.message, 'alert'); pollNow(); }
      });
      fill(this.list.body, [itemForm, this.items, h('div', { class: 'list-foot' }, this.clearBtn)]);

      const side = h('div', { class: 'stack span-5' }, this.cds.el, this.list.el);
      const grid = h('div', { class: 'grid' }, this.agenda.el, side);
      stagger(grid);
      fill(v, [this.head.el, grid]);
    },
    render: function () {
      if (!loaded) { skeleton(this.agenda, 5); return; }
      const cal = obj(S.calendar);
      const feeds = arr(cal.feeds).map(obj);
      const eb = feeds.length ? 'Shared · ' + feeds.map(function (f) { return str(f.name); }).filter(Boolean).join(', ') : 'Shared';
      if (this.head.eyebrow.textContent !== eb) this.head.eyebrow.textContent = eb;
      if (changed('pl.agenda', [cal.events, feeds, dayStart(tvNow()), hour12()])) renderAgenda(this.agenda, cal);
      if (changed('pl.cds', [cal.countdowns, dayStart(tvNow())])) this.renderCountdowns(arr(cal.countdowns).map(obj));
      if (changed('pl.list', S.list)) this.renderList(arr(S.list).map(obj));
    },
    renderCountdowns: function (cds) {
      setMeta(this.cds, cds.length ? cds.length + ' of 6' : '');
      if (!cds.length) { fill(this.cdList, h('li', null, empty('Nothing counting down yet. A wedding, a trip, a birthday?'))); return; }
      const sorted = cds.slice().sort(function (a, b) { return (countdownDays(a) === null ? 1e9 : countdownDays(a)) - (countdownDays(b) === null ? 1e9 : countdownDays(b)); });
      fill(this.cdList, sorted.map(function (c) {
        const d = countdownDays(c);
        const rm = h('button', { class: 'btn btn-icon btn-icon-sm cd-x', type: 'button', 'aria-label': 'Remove ' + str(c.title), 'data-fk': 'cd-' + str(c.title) + str(c.date) }, icon('x'));
        confirmTap(rm, 'Remove?', async function () {
          const list = cds.filter(function (x) { return x !== c; }).map(function (x) { return { title: str(x.title), date: str(x.date) }; });
          try {
            await setSetting('countdowns', list);
            const cal = obj(S.calendar);
            cal.countdowns = arr(cal.countdowns).filter(function (x) { return !(obj(x).title === c.title && obj(x).date === c.date); });
            views.plans.render();
          } catch (e) { toast(e.message, 'alert'); }
        });
        return h('li', { class: 'cd' + (d !== null && d < 0 ? ' past' : '') },
          h('div', { class: 'cd-num-wrap' }, bigNum('cdl.' + str(c.title) + str(c.date), d === null ? '–' : d === 0 ? 'Today' : Math.abs(d), 'cd-num'), d !== null && d !== 0 ? h('span', { class: 'unit', text: Math.abs(d) === 1 ? 'day' : 'days' + (d < 0 ? ' ago' : '') }) : null),
          h('div', { class: 'cd-text' }, h('p', { class: 'cd-title', text: str(c.title) }), h('p', { class: 'cd-date', text: dateLabel(str(c.date)) })),
          rm);
      }));
    },
    renderList: function (items) {
      const self = this;
      const open = items.filter(function (x) { return !x.done; });
      const done = items.filter(function (x) { return x.done; });
      setMeta(this.list, items.length ? open.length + ' to get' : '');
      this.clearBtn.hidden = !done.length;
      this.clearBtn.textContent = 'Clear done (' + done.length + ')';
      if (!items.length) { fill(this.items, h('li', null, empty('The list is empty. Add the first thing above.'))); return; }
      fill(this.items, open.concat(done).map(function (it) {
        const box = h('button', { class: 'check', type: 'button', role: 'checkbox', 'aria-checked': it.done ? 'true' : 'false', 'aria-label': str(it.text), 'data-fk': 'chk-' + it.id, disabled: !!it.temp }, icon('check'));
        box.addEventListener('click', async function () {
          it.done = !it.done;
          self.renderList(arr(S.list).map(obj));
          try { await act('list', { op: 'toggle', id: it.id }); } catch (e) { it.done = !it.done; toast(e.message, 'alert'); self.renderList(arr(S.list).map(obj)); }
        });
        const rm = h('button', { class: 'btn btn-icon btn-icon-sm item-x', type: 'button', 'aria-label': 'Remove ' + str(it.text), disabled: !!it.temp }, icon('x'));
        rm.addEventListener('click', async function () {
          S.list = arr(S.list).filter(function (x) { return obj(x).id !== it.id; });
          self.renderList(arr(S.list).map(obj));
          try { await act('list', { op: 'remove', id: it.id }); } catch (e) { toast(e.message, 'alert'); pollNow(); }
        });
        return h('li', { class: 'item' + (it.done ? ' done' : '') },
          box,
          h('div', { class: 'item-text' }, h('p', { class: 'item-name', text: str(it.text) }), str(it.by) ? h('p', { class: 'item-by', text: str(it.by) }) : null),
          rm);
      }));
    }
  };

  function dateLabel(d) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(d);
    if (!m) return d;
    return fmtDate(new Date(+m[1], +m[2] - 1, +m[3]).getTime(), { weekday: 'short', day: 'numeric', month: 'long', year: 'numeric' });
  }

  function renderAgenda(c, cal) {
    const now = tvNow();
    const t0 = dayStart(now), t1 = dayStart(now, 1), t2 = dayStart(now, 2), t7 = dayStart(now, 7);
    const feeds = arr(cal.feeds).map(obj);
    const evs = arr(cal.events).map(obj).filter(function (e) { return num(e.start) !== null; })
      .sort(function (a, b) { return a.start - b.start || (b.allDay ? 1 : 0) - (a.allDay ? 1 : 0); });
    const endOf = function (e) { return num(e.end) !== null && e.end > e.start ? e.end : e.start + (e.allDay ? 86400000 : 3600000); };
    setMeta(c, fmtDate(t0, { day: 'numeric', month: 'short' }) + ' – ' + fmtDate(t7 - 1, { day: 'numeric', month: 'short' }));
    const legend = feeds.length > 1 ? h('ul', { class: 'legend' }, feeds.map(function (f, i) {
      return h('li', { class: 'legend-item', vars: { '--cal': CAL_COLOURS[i % CAL_COLOURS.length] } }, h('span', { class: 'legend-dot', 'aria-hidden': 'true' }), str(f.name) || 'Calendar');
    })) : null;
    if (!feeds.length && !evs.length) {
      fill(c.body, [empty('No shared calendar yet. Add one in Settings, then the week shows up here.'),
        h('button', { class: 'btn btn-sm', type: 'button', on: { click: function () { show('settings'); } } }, 'Open Settings', icon('chevron'))]);
      return;
    }
    const groups = [];
    const today = evs.filter(function (e) { return e.start < t1 && endOf(e) > t0; });
    groups.push({ label: 'Today', sub: fmtDate(t0, { weekday: 'long', day: 'numeric', month: 'long' }), items: today, today: true });
    groups.push({ label: 'Tomorrow', sub: fmtDate(t1, { weekday: 'long', day: 'numeric', month: 'long' }), items: evs.filter(function (e) { return e.start >= t1 && e.start < t2; }) });
    for (let d = 2; d < 7; d++) {
      const a = dayStart(now, d), b = dayStart(now, d + 1);
      const items = evs.filter(function (e) { return e.start >= a && e.start < b; });
      if (items.length) groups.push({ label: fmtDate(a, { weekday: 'long' }), sub: fmtDate(a, { day: 'numeric', month: 'long' }), items: items });
    }
    const later = evs.filter(function (e) { return e.start >= t7; }).length;
    fill(c.body, [legend, h('div', { class: 'agenda' }, groups.map(function (g) {
      return h('section', { class: 'day-group' + (g.today ? ' is-today' : '') },
        h('header', { class: 'day-head' }, h('h3', { class: 'day-name', text: g.label }), h('p', { class: 'day-date', text: g.sub })),
        g.items.length ? h('ul', { class: 'evs' }, g.items.map(function (e) {
          const end = endOf(e);
          const past = end <= now;
          const ongoing = !e.allDay && e.start <= now && end > now;
          return h('li', { class: 'ev' + (past ? ' past' : '') + (ongoing ? ' ongoing' : ''), vars: { '--cal': CAL_COLOURS[(num(e.cal) || 0) % CAL_COLOURS.length] } },
            h('div', { class: 'ev-time' }, e.allDay ? h('span', { text: 'All day' }) : [h('span', { text: fmtTime(e.start) }), h('span', { class: 'ev-end', text: fmtTime(end) })]),
            h('span', { class: 'ev-bar', 'aria-hidden': 'true' }),
            h('div', { class: 'ev-body' },
              h('p', { class: 'ev-title' }, h('span', { text: str(e.title) || 'Busy' }), ongoing ? h('span', { class: 'now-pill', text: 'Now' }) : null),
              str(e.location) || str(e.calName) ? h('p', { class: 'ev-where', text: [str(e.location), str(e.calName)].filter(Boolean).join(' · ') }) : null));
        })) : h('p', { class: 'day-empty', text: g.today ? 'Nothing planned. Enjoy it.' : 'Nothing yet.' }));
    })), later ? h('p', { class: 'agenda-more', text: plural(later, 'more event') + ' after this week' }) : null]);
  }

  /* ------------------------------------------------------------------ settings */

  const SET_OPTIONS = {
    theme: [['auto', 'Auto'], ['day', 'Day'], ['night', 'Night']],
    units: [['f', '°F'], ['c', '°C'], ['off', 'Hide']],
    clock: [['auto', 'Auto'], ['12', '12-hour'], ['24', '24-hour']],
    motion: [['full', 'Full'], ['reduced', 'Reduced']],
    quality: [['motion', 'Slow drift'], ['still', 'Still and sharpest']],
    ambient: [['2', '2 min'], ['5', '5 min'], ['15', '15 min'], ['off', 'Never']],
    dim: [['5', '5 min'], ['10', '10 min'], ['30', '30 min'], ['off', 'Never']],
    findFor: [['15', '15 min'], ['30', '30 min'], ['60', '1 hour'], ['120', '2 hours']],
    alarm: [['ring', 'Ring'], ['chime', 'Chime']],
    alarmFor: [['2', '2 min'], ['5', '5 min'], ['10', '10 min'], ['30', '30 min']],
    wakeForNotes: [['always', 'Always'], ['day', 'Daytime'], ['never', 'Never']],
    sky: [['on', 'Show'], ['off', 'Hide']],
    album: [['nature', 'Nature'], ['ours', 'Ours'], ['both', 'Both']],
    rotate: [['1m', 'Every min'], ['5m', '5 min'], ['10m', '10 min'], ['hour', 'Hourly'], ['day', 'Daily'], ['off', 'Never']]
  };

  views.settings = {
    built: false,
    build: function () {
      const self = this;
      const v = $('#v-settings');
      this.head = pageHead('Lucent', 'Settings');
      this.ctl = {};
      const row = function (key, label, help) {
        const c = choice({ label: label, options: SET_OPTIONS[key], onChange: function (val) { return setSetting(key, val).then(function () { if (key === 'theme') { const tv = obj(S.tv); tv.modeSetting = val; if (val !== 'auto') applyTheme(val); } }); } });
        self.ctl[key] = c;
        return setRow(label, help, c.el);
      };

      const look = card(1, 'Look & feel', 'card-set');
      fill(look.body, [
        row('theme', 'Day and night look', 'Auto follows sunrise and sunset in Cambridge.'),
        row('quality', 'Wallpaper', 'Still shows the photo pixel for pixel, without the slow drift. Photos are stored at up to 4K.'),
        row('units', 'Temperature'),
        row('clock', 'Clock'),
        row('motion', 'Motion', 'Reduced keeps animations to a minimum on the TV.')
      ]);

      const quiet = card(4, 'Quiet hours', 'card-set');
      this.awakeAlways = toggle({ id: 'awake-always', label: 'Always awake', onChange: function (on) {
        if (on) return setSetting('awakeFrom', 'always').then(function () { return setSetting('awakeTo', 'always'); }).then(function () { self.syncAwake(); });
        return setSetting('awakeFrom', self.awakeFrom.value || '07:00').then(function () { return setSetting('awakeTo', self.awakeTo.value || '00:30'); }).then(function () { self.syncAwake(); });
      } });
      this.awakeFrom = h('input', { class: 'input input-time', id: 'awake-from', type: 'time', value: '07:00' });
      this.awakeTo = h('input', { class: 'input input-time', id: 'awake-to', type: 'time', value: '00:30' });
      this.awakeTimes = h('div', { class: 'time-pair' },
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'awake-from', text: 'From' }), this.awakeFrom),
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'awake-to', text: 'Until' }), this.awakeTo));
      [['awakeFrom', this.awakeFrom], ['awakeTo', this.awakeTo]].forEach(function (p) {
        p[1].addEventListener('change', function () {
          if (!/^\d{2}:\d{2}$/.test(p[1].value)) return;
          setSetting(p[0], p[1].value).then(function () { toast('Saved'); }).catch(function (e) { toast(e.message, 'alert'); });
        });
      });
      this.bedOn = toggle({ id: 'bed-on', label: 'Bedtime', onChange: function (on) {
        return setSetting('bedtime', on ? (self.bedTime.value || '00:30') : 'off').then(function () { self.syncAwake(); });
      } });
      this.bedTime = h('input', { class: 'input input-time', id: 'bed-time', type: 'time', value: '00:30', 'aria-label': 'Bedtime' });
      this.bedTime.addEventListener('change', function () {
        if (!/^\d{2}:\d{2}$/.test(self.bedTime.value)) return;
        setSetting('bedtime', self.bedTime.value).then(function () { toast('Saved'); }).catch(function (e) { toast(e.message, 'alert'); });
      });
      fill(quiet.body, [
        row('ambient', 'Ambient mode after', 'The dock fades and the photo, clock and glance bar stay.'),
        row('dim', 'Dim the screen after'),
        h('div', { class: 'set-row' },
          h('div', { class: 'switch-row' }, h('label', { class: 'switch-label', for: 'awake-always' }, h('span', { class: 'strong', text: 'Always awake' }), h('span', { class: 'switch-help', text: 'Or wake and rest on a schedule' })), this.awakeAlways.el),
          this.awakeTimes),
        h('div', { class: 'set-row' },
          h('div', { class: 'switch-row' }, h('label', { class: 'switch-label', for: 'bed-on' }, h('span', { class: 'strong', text: 'Bedtime' }), h('span', { class: 'switch-help', text: 'Puts the TV to sleep at this time' })), this.bedOn.el),
          this.bedTime)
      ]);

      const bikes = card(5, 'Bikes & buses', 'card-set');
      this.station = h('select', { class: 'input select', id: 'station' });
      this.station.addEventListener('change', function () {
        const val = self.station.value;
        setSetting('station', val).then(function () { toast('Bike station saved'); }).catch(function (e) { toast(e.message, 'alert'); self.renderStations(true); });
      });
      this.station2 = h('select', { class: 'input select', id: 'station2' });
      this.station2.addEventListener('change', function () {
        const val = self.station2.value;
        setSetting('station2', val).then(function () { toast(val === 'none' ? 'Bike finder watches one dock' : 'Bike finder watches both docks'); })
          .catch(function (e) { toast(e.message, 'alert'); self.renderStations(true); });
      });
      this.station2Row = h('div', { class: 'set-row', hidden: true },
        h('div', { class: 'set-text' }, h('label', { class: 'set-label', for: 'station2', text: 'Also watch' }), h('p', { class: 'set-help', text: 'Bike finder rings for the first e-bike at either dock. Westgate wins a tie.' })),
        this.station2);
      this.mbtaStatus = h('p', { class: 'set-help' });
      this.mbtaInput = h('input', { class: 'input mono-input', id: 'mbta', type: 'text', maxLength: 32, placeholder: '32 letters and digits', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false' });
      this.mbtaSave = h('button', { class: 'btn', type: 'submit' }, 'Save');
      this.mbtaRemove = h('button', { class: 'btn btn-quiet btn-sm', type: 'button' }, 'Remove key');
      const mbtaForm = h('form', { class: 'inline-form' }, this.mbtaInput, this.mbtaSave);
      mbtaForm.addEventListener('submit', async function (e) {
        e.preventDefault();
        const k = self.mbtaInput.value.trim();
        if (!/^[0-9a-fA-F]{32}$/.test(k)) { toast('An MBTA key is 32 letters a to f and digits.', 'warn'); return; }
        try { await setSetting('mbtaKey', k.toLowerCase()); self.mbtaInput.value = ''; self.syncMbta(); toast('Key saved. Buses go live.'); } catch (err) { toast(err.message, 'alert'); }
      });
      confirmTap(this.mbtaRemove, 'Tap again to remove', async function () {
        try { await setSetting('mbtaKey', ''); self.syncMbta(); toast('Key removed'); } catch (err) { toast(err.message, 'alert'); }
      });
      fill(bikes.body, [
        h('div', { class: 'set-row' }, h('div', { class: 'set-text' }, h('label', { class: 'set-label', for: 'station', text: 'Bike station' }), h('p', { class: 'set-help', text: 'Auto finds Westgate in the live feed, even if it is renamed.' })), this.station),
        this.station2Row,
        row('findFor', 'Bike finder gives up after'),
        row('alarm', 'When an e-bike docks'),
        row('alarmFor', 'Alarm rings for', 'Then it goes quiet by itself.'),
        h('div', { class: 'set-row' },
          h('div', { class: 'set-text' }, h('label', { class: 'set-label', for: 'mbta', text: 'MBTA key' }), this.mbtaStatus),
          mbtaForm, this.mbtaRemove)
      ]);

      const notes = card(7, 'Notes & sky', 'card-set');
      fill(notes.body, [
        row('wakeForNotes', 'Wake the TV for a note', 'Daytime means between the awake hours.'),
        row('sky', 'Planes overhead')
      ]);

      const photos = card(8, 'Photos', 'card-set');
      fill(photos.body, [row('album', 'Which photos'), row('rotate', 'Change the photo')]);

      const cals = card(10, 'Calendars', 'card-set');
      this.feedList = h('ul', { class: 'feeds' });
      const calName = h('input', { class: 'input', id: 'cal-name', type: 'text', maxLength: 30, placeholder: 'Family', autocomplete: 'off' });
      const calUrl = h('input', { class: 'input mono-input', id: 'cal-url', type: 'url', inputmode: 'url', placeholder: 'https://calendar.google.com/…/basic.ics', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false' });
      const calForm = h('form', { class: 'cal-form' },
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'cal-name', text: 'Name' }), calName),
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'cal-url', text: 'Private address (iCal)' }), calUrl),
        h('button', { class: 'btn', type: 'submit' }, icon('plus'), 'Add calendar'));
      calForm.addEventListener('submit', async function (e) {
        e.preventDefault();
        const name = calName.value.trim().slice(0, 30);
        const url = calUrl.value.trim();
        if (!name) { toast('Give the calendar a name.', 'warn'); return; }
        if (!/^(https|webcal):\/\/\S+$/i.test(url) || url.length > 2000) { toast('That doesn’t look like a calendar address. It starts with https:// or webcal://', 'warn'); return; }
        const feeds = arr(obj(S.calendar).feeds).map(obj);
        if (feeds.length >= 6) { toast('Six calendars is the most the TV follows.', 'warn'); return; }
        const list = feeds.map(function (f) { return { name: str(f.name), url: str(f.url) }; }).concat([{ name: name, url: url }]);
        try {
          await setSetting('calendars', list);
          calName.value = '';
          calUrl.value = '';
          toast('Calendar added. It shows up after the next fetch.');
        } catch (err) { toast(err.message, 'alert'); }
      });
      fill(cals.body, [
        this.feedList,
        calForm,
        h('details', { class: 'help' },
          h('summary', null, 'Where do I find the address?'),
          h('div', { class: 'help-body' },
            h('p', null, h('span', { class: 'strong', text: 'Google Calendar: ' }), 'on a computer, Settings › the calendar under Settings for my calendars › Integrate calendar › Secret address in iCal format. Copy it and paste it here.'),
            h('p', null, h('span', { class: 'strong', text: 'iCloud: ' }), 'in the Calendar app, tap the info button beside the calendar, turn on Public Calendar and tap Share Link. Copy the link that starts with webcal://.'),
            h('p', { class: 'muted', text: 'The TV keeps the address to itself and only ever shows the start of it here.' })))
      ]);

      const wifi = card(11, 'Guest Wi-Fi', 'card-set');
      this.ssid = h('input', { class: 'input', id: 'wifi-ssid', type: 'text', maxLength: 32, autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false' });
      this.wpass = h('input', { class: 'input', id: 'wifi-pass', type: 'password', maxLength: 63, autocomplete: 'new-password', autocapitalize: 'off', spellcheck: 'false' });
      const eye = h('button', { class: 'btn btn-icon btn-icon-sm eye', type: 'button', 'aria-label': 'Show password', 'aria-pressed': 'false' }, icon('eye'));
      eye.addEventListener('click', function () {
        const showIt = self.wpass.type === 'password';
        self.wpass.type = showIt ? 'text' : 'password';
        eye.setAttribute('aria-pressed', String(showIt));
        eye.setAttribute('aria-label', showIt ? 'Hide password' : 'Show password');
      });
      const wifiForm = h('form', { class: 'wifi-form' },
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'wifi-ssid', text: 'Network name' }), this.ssid),
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'wifi-pass', text: 'Password' }), h('div', { class: 'pass-wrap' }, this.wpass, eye)),
        h('button', { class: 'btn', type: 'submit' }, 'Save'));
      wifiForm.addEventListener('submit', async function (e) {
        e.preventDefault();
        const ssid = self.ssid.value.trim();
        const pass = self.wpass.value;
        try {
          if (ssid !== str(settings().wifiSsid)) await setSetting('wifiSsid', ssid);
          if (pass) await setSetting('wifiPass', pass);
          self.wpass.value = '';
          self.syncWifi();
          toast('Guest Wi-Fi saved');
        } catch (err) { toast(err.message, 'alert'); }
      });
      const showWifi = h('button', { class: 'btn btn-primary', type: 'button' }, icon('wifi'), 'Show on the TV');
      showWifi.addEventListener('click', function () { act('wifi', { do: 'show' }).then(function () { toast('The Wi-Fi code is on the TV for 2 minutes'); }).catch(function (e) { toast(e.message, 'alert'); }); });
      const hideWifi = h('button', { class: 'btn btn-quiet', type: 'button' }, 'Hide');
      hideWifi.addEventListener('click', function () { act('wifi', { do: 'hide' }).catch(function (e) { toast(e.message, 'alert'); }); });
      fill(wifi.body, [h('p', { class: 'set-help', text: 'Guests scan a code on the TV to join. The password never comes back to this page.' }), wifiForm, h('div', { class: 'btn-row' }, showWifi, hideWifi)]);

      const alexa = card(12, 'Alexa', 'card-set');
      this.alexaOn = toggle({ id: 'alexa-on', label: 'Alexa routines', onChange: function (on) {
        return setSetting('alexa', on ? 'on' : 'off').then(function () { const a = obj(S.alexa); a.on = on; S.alexa = a; self.renderAlexa(true); });
      } });
      this.alexaBody = h('div', { class: 'alexa-body' });
      fill(alexa.body, [
        h('div', { class: 'switch-row' }, h('label', { class: 'switch-label', for: 'alexa-on' }, h('span', { class: 'strong', text: 'Ask Alexa to find an e-bike' }), h('span', { class: 'switch-help', text: 'With a URL switch in the Alexa app' })), this.alexaOn.el),
        this.alexaBody
      ]);

      const devices = card(13, 'Paired devices', 'card-set');
      this.deviceList = h('ul', { class: 'devices' });
      const forget = h('button', { class: 'btn btn-danger btn-block', type: 'button' }, 'Forget this device');
      confirmTap(forget, 'Tap again to unpair this device', async function () {
        try { await act('logout', {}); } catch (e) { /* unpaired either way */ }
        save('tab', 'home');
        showPairing('', null);
      });
      fill(devices.body, [this.deviceList, forget]);

      const health = card(14, 'Health', 'card-set');
      this.healthList = h('ul', { class: 'health' });
      fill(health.body, [h('p', { class: 'set-help', text: 'Every live source the TV reads, and how it is pacing itself. Limited means the source asked Lucent to slow down.' }), this.healthList]);

      const about = card(15, 'About', 'card-set');
      this.aboutBody = h('div', { class: 'about' });
      const refresh = h('button', { class: 'btn btn-block', type: 'button' }, icon('refresh'), 'Fetch fresh data now');
      refresh.addEventListener('click', function () { act('refresh', {}).then(function () { toast('The TV is fetching fresh data'); }).catch(function (e) { toast(e.message, 'alert'); }); });
      fill(about.body, [this.aboutBody, refresh]);

      const samsung = this.buildSamsung();
      const remote = this.buildRemote();
      const push = this.buildPush();
      const claude = this.buildClaude();
      const left = h('div', { class: 'set-col' }, look.el, samsung.el, remote.el, quiet.el, bikes.el, push.el, notes.el, photos.el);
      const right = h('div', { class: 'set-col' }, claude.el, cals.el, wifi.el, alexa.el, devices.el, health.el, about.el);
      const cols = h('div', { class: 'set-cols' }, left, right);
      stagger(cols);
      fill(v, [this.head.el, cols]);
    },
    render: function () {
      const st = settings();
      for (const k of Object.keys(this.ctl)) this.ctl[k].set(k === 'theme' ? (str(obj(S.tv).modeSetting) || str(st.theme)) : str(st[k]));
      if (!loaded) return;
      this.syncAwake();
      this.renderStations();
      this.syncMbta();
      this.syncWifi();
      this.renderFeeds();
      this.renderSamsung();
      this.renderRemote();
      this.renderClaude();
      this.renderPush();
      this.renderAlexa();
      this.renderDevices();
      this.renderHealth();
      this.renderAbout();
    },
    buildSamsung: function () {
      const self = this;
      const c = card(2, 'Samsung picture', 'card-set card-samsung');
      c.el.id = 'set-samsung';
      c.el.hidden = true;
      this.sam = { card: c, authorize: '' };
      this.samError = h('p', { class: 'amber-note', role: 'status', hidden: true });
      const code = function (t) { return h('code', { class: 'code', text: t }); };
      const id = h('input', { class: 'input mono-input', id: 'sam-id', type: 'text', maxLength: 200, autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false' });
      const secret = h('input', { class: 'input mono-input', id: 'sam-secret', type: 'password', maxLength: 200, autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false' });
      const cont = h('button', { class: 'btn btn-primary', type: 'submit' }, 'Continue');
      const signin = h('a', { class: 'btn btn-primary sam-signin', target: '_blank', rel: 'noopener noreferrer', hidden: true }, h('span', { text: 'Open Samsung sign-in' }), icon('external'));
      const again = h('button', { class: 'btn btn-quiet btn-sm', type: 'button', hidden: true }, 'Change the ID or secret');
      const begin = h('form', { class: 'sam-form' },
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'sam-id', text: 'Client ID' }), id),
        h('div', { class: 'field' }, h('label', { class: 'label', for: 'sam-secret', text: 'Client Secret' }), secret),
        h('div', { class: 'btn-row' }, cont, signin, again));
      begin.addEventListener('submit', async function (e) {
        e.preventDefault();
        const clientId = id.value.trim(), clientSecret = secret.value.trim();
        if (!clientId || !clientSecret) { toast('Paste both the Client ID and the Client Secret.', 'warn'); return; }
        cont.disabled = true;
        cont.classList.add('busy');
        try {
          const d = await act('samsung', { do: 'begin', clientId: clientId, clientSecret: clientSecret }, { timeout: 15000 });
          const url = str(d.authorize);
          if (!/^https:\/\/([a-z0-9-]+\.)*smartthings\.com\//i.test(url)) throw new Error('The TV sent back an address that isn’t Samsung’s.');
          self.sam.authorize = url;
          signin.href = url;
          signin.hidden = false;
          cont.hidden = true;
          again.hidden = false;
          secret.value = '';
          self.setSamError('');
          self.syncSamSteps();
        } catch (err) {
          self.setSamError(serverError(err));
        }
        cont.disabled = false;
        cont.classList.remove('busy');
      });
      again.addEventListener('click', function () {
        self.sam.authorize = '';
        signin.hidden = true;
        signin.removeAttribute('href');
        cont.hidden = false;
        again.hidden = true;
        id.focus();
      });
      const pasted = h('textarea', { class: 'input mono-input sam-code', id: 'sam-code', rows: '2', maxLength: 8000, placeholder: 'The code, or the whole address', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false' });
      const connect = h('button', { class: 'btn btn-primary', type: 'submit' }, 'Connect');
      const finish = h('form', { class: 'sam-form' }, h('label', { class: 'sr-only', for: 'sam-code', text: 'Code from the Samsung page' }), pasted, h('div', { class: 'btn-row' }, connect));
      finish.addEventListener('submit', async function (e) {
        e.preventDefault();
        const value = pasted.value.trim();
        if (!value) { toast('Paste the code first.', 'warn'); return; }
        connect.disabled = true;
        connect.classList.add('busy');
        try {
          const d = await act('samsung', { do: 'finish', code: value }, { timeout: 20000 });
          pasted.value = '';
          self.sam.authorize = '';
          signin.hidden = true;
          cont.hidden = false;
          again.hidden = true;
          const sam = obj(S.samsung);
          sam.connected = true;
          sam.setup = 'none';
          sam.error = '';
          if (str(d.device)) sam.device = str(d.device);
          S.samsung = sam;
          self.setSamError('');
          self.renderSamsung(true);
          toast('Connected' + (str(d.device) ? ' to ' + str(d.device) : ''));
        } catch (err) {
          self.setSamError(serverError(err));
        }
        connect.disabled = false;
        connect.classList.remove('busy');
      });
      const step = function (n, kids) {
        return h('li', { class: 'step', data: { n: n } }, h('span', { class: 'step-n', 'aria-hidden': 'true', text: String(n) }), h('div', { class: 'step-body' }, kids));
      };
      this.samStep4 = step(4, [h('p', { text: 'After you allow it, a page of text opens. Copy the code (or the whole address) and paste it here' }), finish]);
      this.samWizard = h('div', { class: 'sam-wizard' },
        h('p', { class: 'set-help', text: 'Lucent can switch the TV’s picture mode for glare, day and night. It talks to Samsung’s SmartThings, which needs a one-time setup from a computer.' }),
        h('ol', { class: 'steps' },
          step(1, [h('p', { text: 'On a Mac:' }), codeField('brew install smartthingscommunity/smartthings/smartthings', 'sam-brew', 'Install command')]),
          step(2, [h('p', null, 'Run ', code('smartthings apps:create'), ', choose OAuth-In App, name it Lucent, pick the scopes ', code('r:devices:*'), ' and ', code('x:devices:*'), ', and add the redirect ', code('https://httpbin.org/get'))]),
          step(3, [h('p', { text: 'Paste the Client ID and Client Secret it prints, then sign in to Samsung.' }), begin]),
          this.samStep4),
        h('p', { class: 'set-help sam-note', text: 'The secret and the sign-in stay on the TV. Phones only ever see Connected.' }));
      this.samConnected = h('div', { class: 'sam-connected', hidden: true });
      fill(c.body, [this.samError, this.samWizard, this.samConnected]);
      return c;
    },
    setSamError: function (text) {
      this.samError.textContent = text || '';
      this.samError.hidden = !text;
    },
    syncSamSteps: function () {
      const begun = !!this.sam.authorize || str(obj(S.samsung).setup) === 'begun';
      this.samStep4.classList.toggle('later', !begun);
      $$('textarea, button', this.samStep4).forEach(function (el) { el.disabled = !begun; });
    },
    renderSamsung: function (force) {
      const raw = S.samsung;
      const has = !!raw && typeof raw === 'object';
      this.sam.card.el.hidden = !has;
      if (!has) return;
      const sam = obj(raw);
      if (!changed('s.samsung', sam) && !force) return;
      // The TV's last Samsung error shows until it clears. A local error from this page stays until the next try.
      const said = str(sam.error);
      if (said) this.setSamError(said);
      else if (sam.connected || (this.sam.said && this.samError.textContent === this.sam.said)) this.setSamError('');
      this.sam.said = said;
      const connected = sam.connected === true;
      this.samWizard.hidden = connected;
      this.samConnected.hidden = !connected;
      setMeta(this.sam.card, connected ? 'Connected' : '', connected ? 'live' : '');
      if (!connected) { this.syncSamSteps(); return; }
      const modes = arr(sam.modes).map(str).filter(Boolean);
      if (changed('s.samModes', [modes, sam.device]) || force || !this.samCtl) this.buildSamConnected(modes, sam);
      this.samCtl.now.set(str(sam.mode));
      this.samCtl.glare.set(str(sam.glareMode));
      this.samCtl.day.set(str(sam.dayMode));
      this.samCtl.night.set(str(sam.nightMode));
      this.samCtl.follow.set(str(sam.follow) !== 'off');
    },
    buildSamConnected: function (modes, sam) {
      const opts = modes.map(function (m) { return [m, m]; });
      const pick = function (label, key) {
        return choice({ label: label, options: opts, onChange: function (v) {
          return setSetting(key, v).then(function () { obj(S.samsung)[key] = v; }).catch(function (e) { throw new Error(serverError(e)); });
        } });
      };
      const now = choice({ label: 'Picture now', options: opts, onChange: function (v) {
        return act('samsung', { do: 'mode', mode: v }).then(function () { obj(S.samsung).mode = v; toast('Picture set to ' + v); }).catch(function (e) { throw new Error(serverError(e)); });
      } });
      const glare = pick('Glare picture', 'glareMode');
      const day = pick('Day picture', 'dayMode');
      const night = pick('Night picture', 'nightMode');
      const follow = toggle({ id: 'sam-follow', label: 'Picture follows day and night', onChange: function (on) {
        return setSetting('pictureFollow', on ? 'on' : 'off').then(function () { obj(S.samsung).follow = on ? 'on' : 'off'; });
      } });
      const disconnect = h('button', { class: 'btn btn-quiet btn-danger btn-block', type: 'button' }, 'Disconnect');
      const self = this;
      confirmTap(disconnect, 'Tap again to disconnect', async function () {
        try {
          await act('samsung', { do: 'disconnect' });
          const s = obj(S.samsung);
          s.connected = false;
          s.device = '';
          self.renderSamsung(true);
          toast('Disconnected from the Samsung');
        } catch (e) { toast(serverError(e), 'alert'); }
      });
      this.samCtl = { now: now, glare: glare, day: day, night: night, follow: follow };
      fill(this.samConnected, [
        h('div', { class: 'sam-device' }, icon('tv', 'sam-ico'), h('div', null, h('p', { class: 'sam-name', text: str(sam.device) || 'Samsung TV' }), h('p', { class: 'set-help', text: 'Lucent switches its picture mode for you.' }))),
        modes.length ? [
          setRow('Picture now', null, now.el),
          setRow('Glare picture', 'What the Glare switch on Home turns on.', glare.el),
          setRow('Day picture', null, day.el),
          setRow('Night picture', null, night.el)
        ] : h('p', { class: 'set-help', text: 'The TV hasn’t listed its picture modes yet.' }),
        h('div', { class: 'set-row' }, h('div', { class: 'switch-row' }, h('label', { class: 'switch-label', for: 'sam-follow' }, h('span', { class: 'strong', text: 'Picture follows day and night' }), h('span', { class: 'switch-help', text: 'Day picture at sunrise, night picture at sunset' })), follow.el)),
        h('div', { class: 'set-row' }, disconnect)
      ]);
    },
    // The Samsung's own remote over the home network: the address, then Allow on the TV once.
    buildRemote: function () {
      const self = this;
      const c = card(3, 'Samsung remote', 'card-set card-rm');
      c.el.id = 'set-remote';
      c.el.hidden = true;
      this.rm = { card: c, busy: false, waiting: false, watchUntil: 0, gen: 0, said: '' };
      this.rmError = h('p', { class: 'amber-note', role: 'status', hidden: true });
      this.rmDenied = h('p', { class: 'set-help rm-denied', hidden: true, text: 'If you chose Deny, allow Lucent again on the TV: Settings, General, External Device Manager, Device Connection Manager, Device List.' });
      this.rmIp = h('input', { class: 'input mono-input', id: 'rm-ip', type: 'text', inputmode: 'decimal', maxLength: 15, placeholder: '192.168.1.20', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false', 'aria-label': 'The TV’s address' });
      this.rmFind = h('button', { class: 'btn btn-primary', type: 'submit' }, 'Find TV');
      const form = h('form', { class: 'inline-form rm-form', novalidate: true }, this.rmIp, this.rmFind);
      form.addEventListener('submit', function (e) { e.preventDefault(); self.rmFindDo(); });
      this.rmFound = h('div', { class: 'rm-found', hidden: true });
      this.rmPair = h('button', { class: 'btn btn-primary rm-pair', type: 'button' }, 'Pair');
      this.rmPair.addEventListener('click', function () { self.rmPairDo(); });
      // Which of Allow, Deny and Close the TV's question starts on is not known, so the page never
      // presses OK blindly: the owner looks at the TV and moves to Allow first.
      const padKey = function (key, label, kids) {
        const b = h('button', { class: 'btn rm-key', type: 'button', 'aria-label': label + ' through SmartThings', data: { key: key } }, kids);
        b.addEventListener('click', function () {
          cloudPress(b, key, { quiet: true, fail: function (e) { self.setRmError(serverError(e)); } });
        });
        return b;
      };
      this.rmAllowWrap = h('div', { class: 'rm-allow', hidden: true },
        h('div', { class: 'rm-pad', role: 'group', 'aria-label': 'TV keys through SmartThings' },
          padKey('LEFT', 'Left', [icon('chevron', 'rm-left'), h('span', { text: 'Left' })]),
          padKey('OK', 'OK', h('span', { text: 'OK' })),
          padKey('RIGHT', 'Right', [h('span', { text: 'Right' }), icon('chevron')])),
        h('p', { class: 'set-help', text: 'Look at the TV. Move to Allow, then press OK. These go through SmartThings and can take a few seconds.' }));
      this.rmWait = h('div', { class: 'rm-wait', hidden: true },
        h('div', { class: 'wait-rings rm-rings', 'aria-hidden': 'true' }, h('span', { class: 'ring' }), h('span', { class: 'ring' }), h('span', { class: 'ring' }), h('span', { class: 'wait-ok' }, icon('tv'))),
        h('p', { class: 'rm-wait-title', role: 'status', text: 'Choose Allow on the TV' }),
        h('p', { class: 'set-help rm-wait-help', text: 'The TV asks whether to allow Lucent. Pick Allow with the TV’s own remote.' }),
        this.rmAllowWrap);
      const step = function (n, kids) {
        return h('li', { class: 'step', data: { n: n } }, h('span', { class: 'step-n', 'aria-hidden': 'true', text: String(n) }), h('div', { class: 'step-body' }, kids));
      };
      this.rmStep2 = step(2, [h('p', { text: 'Pair. The TV asks once whether to allow Lucent.' }), h('div', { class: 'btn-row' }, this.rmPair), this.rmWait]);
      this.rmSteps = h('ol', { class: 'steps' },
        step(1, [h('p', { text: 'On the TV: Settings, General, Network, Network Status, IP Settings. Type the address here.' }), form, this.rmFound]),
        this.rmStep2);
      this.rmIntro = h('p', { class: 'set-help', text: 'Lucent can press the TV’s own remote keys over Wi-Fi. The arrows and OK on the Remote tab then answer at once.' });
      this.rmDevice = h('div', { class: 'rm-device', hidden: true });
      this.rmTrust = h('button', { class: 'btn btn-primary rm-trust', type: 'button', hidden: true }, 'Trust the TV Again');
      this.rmTrust.addEventListener('click', async function () {
        self.rmTrust.disabled = true;
        try {
          await act('remote', { do: 'trust' }, { timeout: 15000 });
          const r = self.rmLocal();
          r.state = 'off';
          r.paired = false;
          r.error = '';
          self.setRmError('');
          self.renderRemote(true);
          toast('Trusted. Now pair again.');
        } catch (err) {
          self.setRmError(serverError(err));
        }
        self.rmTrust.disabled = false;
      });
      this.rmForget = h('button', { class: 'btn btn-quiet btn-danger btn-sm rm-forget', type: 'button', hidden: true }, 'Forget');
      confirmTap(this.rmForget, 'Tap again to forget the TV', async function () {
        try {
          await act('remote', { do: 'forget' }, { timeout: 15000 });
          const sam = obj(S.samsung);
          sam.remote = { state: 'none', address: '', paired: false, label: '', model: '', canWake: false, error: '' };
          S.samsung = sam;
          self.rmIp.value = '';
          self.setRmError('');
          self.renderRemote(true);
          if (views.remote.built) views.remote.render();
          toast('Lucent forgot the TV');
        } catch (err) {
          self.setRmError(serverError(err));
        }
      });
      fill(c.body, [
        this.rmError,
        this.rmDenied,
        this.rmDevice,
        this.rmIntro,
        this.rmSteps,
        h('div', { class: 'btn-row rm-actions' }, this.rmTrust, this.rmForget),
        h('p', { class: 'set-help sam-note', text: 'Lucent talks to the TV only on your home network and keeps its key on the TV.' })
      ]);
      return c;
    },
    rmLocal: function () {
      const sam = obj(S.samsung);
      const r = obj(sam.remote);
      sam.remote = r;
      S.samsung = sam;
      return r;
    },
    setRmError: function (text) {
      this.rmError.textContent = text || '';
      this.rmError.hidden = !text;
    },
    rmFindDo: async function () {
      if (this.rm.busy) return;
      const ip = this.rmIp.value.trim();
      if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) { this.setRmError('Type the address the TV shows, like 192.168.1.20.'); this.rmIp.focus(); return; }
      this.rm.busy = true;
      this.rmFind.disabled = true;
      this.rmFind.classList.add('busy');
      try {
        const d = await act('remote', { do: 'address', ip: ip }, { timeout: 15000 });
        const r = this.rmLocal();
        r.address = ip;
        if (str(d.label)) r.label = str(d.label);
        if (str(d.model)) r.model = str(d.model);
        if (!str(r.state) || str(r.state) === 'none') r.state = 'off';
        r.error = '';
        this.rm.found = { label: str(d.label), model: str(d.model), power: str(d.power) };
        this.setRmError('');
        this.renderRemote(true);
        toast(str(d.label) ? 'Found ' + str(d.label).replace(/^\s*\[[^\]]*\]\s*/, '') : 'Found the TV');
      } catch (err) {
        this.setRmError(serverError(err));
      }
      this.rm.busy = false;
      this.rmFind.disabled = false;
      this.rmFind.classList.remove('busy');
    },
    // Waits while the TV asks to allow Lucent. The TV may cut a long request, so the state that
    // the page polls decides too: connected means paired, anything but waiting means it ended.
    rmPairDo: async function () {
      if (this.rm.waiting) return;
      const gen = ++this.rm.gen;
      this.rm.waiting = true;
      this.rm.watchUntil = 0;
      this.setRmError('');
      const r = this.rmLocal();
      r.state = 'allow';
      r.error = '';
      this.renderRemote(true);
      try {
        await act('remote', { do: 'pair' }, { timeout: 50000 });
        if (gen !== this.rm.gen) return;
        this.rmPaired();
      } catch (err) {
        if (gen !== this.rm.gen) return;
        if (!err.status) {
          this.rm.watchUntil = Date.now() + 40000;
          pollSoon();
          return;
        }
        this.rm.waiting = false;
        const x = this.rmLocal();
        if (str(x.state) === 'allow' || str(x.state) === 'connecting') x.state = err.status === 403 ? 'refused' : 'off';
        this.setRmError(serverError(err));
        this.renderRemote(true);
      }
    },
    rmPaired: function () {
      this.rm.waiting = false;
      this.rm.watchUntil = 0;
      const r = this.rmLocal();
      r.state = 'connected';
      r.paired = true;
      r.error = '';
      this.setRmError('');
      this.renderRemote(true);
      if (views.remote.built) views.remote.render();
      toast('Paired. The remote is ready.');
    },
    renderRemote: function (force) {
      const raw = S.samsung;
      const has = canSetUpRemote();
      this.rm.card.el.hidden = !has;
      if (!has) return;
      const sam = obj(raw);
      const r = obj(sam.remote);
      const state = str(r.state) || 'none';
      // A pair request the TV cut short: the polled state says how it ended.
      if (this.rm.waiting && this.rm.watchUntil) {
        if (state === 'connected') { this.rmPaired(); return; }
        if ((state !== 'allow' && state !== 'connecting') || Date.now() > this.rm.watchUntil) {
          this.rm.waiting = false;
          this.rm.watchUntil = 0;
          if (!str(r.error)) this.setRmError('The TV didn’t answer in time. Try again.');
        }
      }
      if (!changed('s.remote', [r, sam.connected, obj(sam.can).keys, this.rm.waiting, this.rm.found]) && !force) return;
      const said = str(r.error);
      if (said) this.setRmError(said);
      else if (this.rm.said && this.rmError.textContent === this.rm.said) this.setRmError('');
      this.rm.said = said;
      const paired = r.paired === true;
      const changedCert = state === 'changed';
      const waiting = this.rm.waiting || state === 'allow' || state === 'connecting';
      // A refusal: the TV said no, which is what choosing Deny on its question does.
      const shown = this.rmError.hidden ? '' : this.rmError.textContent;
      this.rmDenied.hidden = waiting || !(state === 'refused' || /said no|\bden(y|ied)\b/i.test(shown));
      const ready = (paired || state === 'connected') && !changedCert && !waiting;
      setMeta(this.rm.card, state === 'connected' ? 'Connected' : changedCert ? 'Check the TV' : paired ? 'Paired' : waiting ? 'Waiting' : '', state === 'connected' ? 'live' : '');
      const address = str(r.address);
      if (address && document.activeElement !== this.rmIp && !this.rmIp.value) this.rmIp.value = address;
      // What Lucent knows about the TV: its name, model and address.
      const label = str(r.label).replace(/^\s*\[[^\]]*\]\s*/, '');
      const known = !!(label || str(r.model));
      const deviceKids = known || address ? [
        icon('tv', 'sam-ico'),
        h('div', null,
          h('p', { class: 'sam-name', text: label || 'Samsung TV' }),
          h('p', { class: 'set-help', text: [str(r.model), address].filter(Boolean).join(' · ') + (ready ? '. Keys go straight to it.' : '') }))
      ] : null;
      fill(this.rmDevice, deviceKids);
      this.rmDevice.hidden = !(ready || changedCert) || !deviceKids;
      fill(this.rmFound, !ready && known ? h('p', { class: 'rm-found-text' }, icon('check'), h('span', { text: 'Found ' + (label || 'the TV') + (str(r.model) ? ', ' + str(r.model) : '') + '.' })) : null);
      this.rmFound.hidden = ready || !known;
      this.rmIntro.hidden = ready || changedCert;
      this.rmSteps.hidden = ready || changedCert;
      const canPair = !!address && !waiting;
      this.rmStep2.classList.toggle('later', !address);
      this.rmPair.disabled = !canPair;
      this.rmPair.textContent = state === 'refused' || state === 'unreachable' || said ? 'Pair Again' : 'Pair';
      this.rmWait.hidden = !waiting;
      this.rmAllowWrap.hidden = !(sam.connected === true && obj(sam.can).keys === true);
      this.rmTrust.hidden = !changedCert;
      this.rmForget.hidden = !(address || paired);
      if (ready && !this.rmOpen) {
        this.rmOpen = h('button', { class: 'btn btn-sm rm-open', type: 'button', on: { click: function () { show('remote'); } } }, icon('remote'), 'Open Remote');
      }
      if (this.rmOpen) {
        if (ready && this.rmOpen.parentNode !== this.rmTrust.parentNode) this.rmTrust.parentNode.insertBefore(this.rmOpen, this.rmForget);
        this.rmOpen.hidden = !ready;
      }
    },
    // Claude: the API key (kept on the TV), the month's spending against the cap, and the automations.
    buildClaude: function () {
      const self = this;
      const c = card(9, 'Claude', 'card-set card-claude');
      c.el.id = 'set-claude';
      c.el.hidden = true;
      this.cl = { card: c };
      this.clError = h('p', { class: 'amber-note', role: 'status', hidden: true });
      this.clStatus = h('div', { class: 'cl-status', hidden: true });
      this.clKey = h('input', { class: 'input mono-input', id: 'claude-key', type: 'password', maxLength: 200, placeholder: 'sk-ant-…', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false' });
      this.clSave = h('button', { class: 'btn btn-primary', type: 'submit' }, 'Save');
      this.clForm = h('form', { class: 'set-row cl-form', novalidate: true },
        h('div', { class: 'set-text' }, h('label', { class: 'set-label', for: 'claude-key', text: 'Claude API key' }), h('p', { class: 'set-help', text: 'From console.anthropic.com, under API Keys.' })),
        h('div', { class: 'inline-form' }, this.clKey, this.clSave));
      this.clForm.addEventListener('submit', async function (e) {
        e.preventDefault();
        const key = self.clKey.value.trim();
        if (!key) { self.setClError('Paste the key first.'); self.clKey.focus(); return; }
        self.clSave.disabled = true;
        self.clSave.classList.add('busy');
        try {
          await act('assistant', { do: 'key', key: key }, { timeout: 15000 });
          self.clKey.value = '';
          const a = obj(assistantState());
          a.on = true;
          a.hint = '…' + key.slice(-4);
          S.assistant = a;
          self.setClError('');
          self.renderClaude(true);
          if (views.home.built) views.home.ask.render(true);
          toast('Key saved. Ask away.');
        } catch (err) {
          self.setClError(serverError(err));
        }
        self.clSave.disabled = false;
        self.clSave.classList.remove('busy');
      });
      this.clCap = h('select', { class: 'input select', id: 'claude-cap' });
      this.clCap.addEventListener('change', async function () {
        const dollars = parseInt(self.clCap.value, 10);
        try {
          await act('assistant', { do: 'cap', dollars: dollars });
          obj(assistantState()).cap = dollars;
          self.renderClaude(true);
          toast('Cap set to $' + dollars + ' a month');
        } catch (err) {
          toast(serverError(err), 'alert');
          self.renderClaude(true);
        }
      });
      this.clCapRow = h('div', { class: 'set-row', hidden: true },
        h('div', { class: 'set-text' }, h('label', { class: 'set-label', for: 'claude-cap', text: 'Monthly cap' }), h('p', { class: 'set-help', text: 'Lucent stops asking when the month reaches it.' })),
        this.clCap);
      this.clForget = h('button', { class: 'btn btn-quiet btn-danger btn-sm cl-forget', type: 'button', 'data-fk': 'cl-forget' }, 'Forget Key');
      confirmTap(this.clForget, 'Tap again to forget the key', async function () {
        try {
          await act('assistant', { do: 'forget' });
          const a = obj(assistantState());
          a.on = false;
          a.hint = '';
          S.assistant = a;
          self.renderClaude(true);
          if (views.home.built) views.home.ask.render(true);
          toast('Key forgotten');
        } catch (err) {
          self.setClError(serverError(err));
        }
      });
      this.clForgetRow = h('div', { class: 'set-row', hidden: true }, this.clForget);
      this.clRules = h('ul', { class: 'rules' });
      fill(c.body, [
        this.clError,
        this.clStatus,
        this.clForm,
        this.clCapRow,
        this.clForgetRow,
        h('p', { class: 'set-help cl-help', text: 'Your key stays on the TV and is never shown again. Questions, and what the TV shows (bikes, weather, shuttles, your next plans, what’s playing), go to Anthropic to answer them. Lucent stops asking when the month reaches the cap.' }),
        h('div', { class: 'cl-rules' },
          h('p', { class: 'set-label', text: 'Automations' }),
          this.clRules,
          h('p', { class: 'set-help', text: 'Ask Lucent to add one, like: every weekday at 7:15 start the bike finder.' }))
      ]);
      return c;
    },
    setClError: function (text) {
      this.clError.textContent = text || '';
      this.clError.hidden = !text;
    },
    renderClaude: function (force) {
      const a = assistantState();
      this.cl.card.el.hidden = !a;
      if (!a) return;
      if (!changed('s.claude', [a.on, a.hint, a.model, a.cap, a.spent, a.rules]) && !force) return;
      const on = a.on === true;
      setMeta(this.cl.card, on ? 'Connected' : '', on ? 'live' : '');
      const cap = num(a.cap) || 40;
      const spent = Math.max(0, num(a.spent) || 0) / 1000000;
      const hint = str(a.hint);
      fill(this.clStatus, on ? [
        h('p', { class: 'cl-line' }, h('span', { class: 'strong', text: 'Connected' }), ' · key ' + (hint ? (/^…/.test(hint) ? hint : '…' + hint) : 'saved') + ' · ' + modelName(a.model)),
        h('div', { class: 'cl-spend' },
          h('div', { class: 'cl-spend-top' }, h('span', { class: 'set-label', text: 'This month' }), h('span', { class: 'cl-spend-num', text: '$' + spent.toFixed(2) + ' of $' + cap })),
          h('div', { class: 'cl-bar', role: 'img', 'aria-label': Math.round(Math.min(1, spent / cap) * 100) + '% of the cap used' },
            h('span', { class: 'cl-bar-fill' + (spent >= cap ? ' full' : spent >= cap * 0.8 ? ' high' : ''), vars: { '--p': Math.min(1, spent / cap).toFixed(4) } })))
      ] : null);
      this.clStatus.hidden = !on;
      this.clForm.hidden = on;
      this.clCapRow.hidden = !on;
      this.clForgetRow.hidden = !on;
      const caps = [10, 20, 40, 60, 80, 100];
      if (caps.indexOf(cap) < 0) caps.push(cap);
      caps.sort(function (x, y) { return x - y; });
      fill(this.clCap, caps.map(function (n) { return h('option', { value: String(n), text: '$' + n + ' a month' }); }));
      this.clCap.value = String(cap);
      this.renderRules(arr(a.rules).map(obj).filter(function (r) { return str(r.id); }));
    },
    renderRules: function (rules) {
      if (!rules.length) { fill(this.clRules, h('li', null, empty('No automations yet.'))); return; }
      fill(this.clRules, rules.map(function (r) {
        const id = str(r.id);
        const sw = toggle({ id: 'rule-' + id, label: str(r.name) || 'Automation', value: r.on !== false, onChange: function (v) {
          return act('assistant', { do: 'ruleon', id: id, on: v }).then(function () { r.on = v; item.classList.toggle('off', !v); });
        } });
        const del = h('button', { class: 'btn btn-icon btn-icon-sm rule-del', type: 'button', 'aria-label': 'Delete ' + (str(r.name) || 'automation'), 'data-fk': 'rule-del-' + id }, icon('trash'));
        confirmTap(del, 'Delete?', async function () {
          try {
            await act('assistant', { do: 'ruledel', id: id });
            const a = obj(assistantState());
            a.rules = arr(a.rules).filter(function (x) { return str(obj(x).id) !== id; });
            views.settings.renderClaude(true);
            toast('Automation deleted');
          } catch (e) {
            toast(serverError(e), 'alert');
          }
        });
        const item = h('li', { class: 'rule' + (r.on === false ? ' off' : '') },
          h('label', { class: 'rule-text', for: 'rule-' + id }, h('span', { class: 'rule-name', text: str(r.name) || 'Automation' }), h('span', { class: 'rule-when', text: str(r.when) })),
          sw.el, del);
        return item;
      }));
    },
    buildPush: function () {
      const self = this;
      const c = card(6, 'Phone alerts', 'card-set card-push');
      c.el.hidden = true;
      this.pushCard = c;
      this.pushOn = toggle({ id: 'push-on', label: 'Phone alerts', onChange: function (on) {
        return setSetting('push', on ? 'on' : 'off').then(function () { const p = obj(S.push); p.on = on; S.push = p; self.renderPush(true); });
      } });
      this.pushBody = h('div', { class: 'push-body' });
      fill(c.body, [
        h('div', { class: 'switch-row' }, h('label', { class: 'switch-label', for: 'push-on' }, h('span', { class: 'strong', text: 'Buzz our phones' }), h('span', { class: 'switch-help', text: 'Free, through the ntfy app' })), this.pushOn.el),
        h('p', { class: 'set-help push-why', text: 'When bike finder finds an e-bike, your phones buzz with a link to Bluebikes, where Reserve holds it for 10 minutes.' }),
        this.pushBody
      ]);
      return c;
    },
    renderPush: function (force) {
      const raw = S.push;
      const has = !!raw && typeof raw === 'object';
      this.pushCard.el.hidden = !has;
      if (!has) return;
      const p = obj(raw);
      const on = p.on === true;
      this.pushOn.set(on);
      if (!changed('s.push', [on, p.topic]) && !force) return;
      if (!on) { fill(this.pushBody, null); return; }
      const topic = str(p.topic);
      const test = h('button', { class: 'btn', type: 'button' }, icon('bell'), 'Send a test');
      test.addEventListener('click', async function () {
        try { await act('push', { do: 'test' }); toast('Test sent. Your phones should buzz in a moment'); } catch (e) { toast(serverError(e), 'alert'); }
      });
      const fresh = h('button', { class: 'btn btn-quiet btn-sm', type: 'button' }, icon('refresh'), 'New topic');
      confirmTap(fresh, 'Tap again. Phones must subscribe again', async function () {
        try { await setSetting('push', 'new'); toast('New topic made. Subscribe to it in ntfy.'); pollSoon(); } catch (e) { toast(serverError(e), 'alert'); }
      });
      fill(this.pushBody, [
        h('p', { class: 'push-steps', text: 'Install ntfy from the App Store, tap +, and subscribe to this topic' }),
        topic ? copyField('Topic', topic, 'push-topic') : h('p', { class: 'set-help', text: 'The TV is making a topic…' }),
        h('div', { class: 'btn-row' }, test, fresh)
      ]);
    },
    syncAwake: function () {
      const st = settings();
      const always = str(st.awakeFrom) === 'always' || str(st.awakeTo) === 'always';
      this.awakeAlways.set(always);
      this.awakeTimes.hidden = always;
      if (document.activeElement !== this.awakeFrom && /^\d{2}:\d{2}$/.test(str(st.awakeFrom))) this.awakeFrom.value = st.awakeFrom;
      if (document.activeElement !== this.awakeTo && /^\d{2}:\d{2}$/.test(str(st.awakeTo))) this.awakeTo.value = st.awakeTo;
      const bed = str(st.bedtime);
      const on = /^\d{2}:\d{2}$/.test(bed);
      this.bedOn.set(on);
      this.bedTime.hidden = !on;
      if (on && document.activeElement !== this.bedTime) this.bedTime.value = bed;
    },
    renderStations: function (force) {
      const list = arr(S.stations).map(obj).filter(function (s) { return str(s.id); });
      const val = str(settings().station) || 'auto';
      if (changed('s.stations', list) || force) {
        const opts = list.length ? list : [{ id: 'auto', name: 'Westgate' }];
        fill(this.station, opts.map(function (s) { return h('option', { value: str(s.id), text: (s.id === 'auto' ? 'Auto · ' : '') + (str(s.name) || str(s.id)) }); }));
      }
      if (document.activeElement !== this.station) this.station.value = val;
      // The second dock bike finder watches: "auto" is Erie St, "none" watches Westgate alone.
      const list2 = arr(S.stations2).map(obj).filter(function (s) { return str(s.id); });
      this.station2Row.hidden = !list2.length;
      if (!list2.length) return;
      const val2 = str(settings().station2) || 'auto';
      if (changed('s.stations2', list2) || force) {
        fill(this.station2, list2.map(function (s) {
          const id = str(s.id);
          const name = str(s.name) || (id === 'none' ? 'None' : id);
          return h('option', { value: id, text: id === 'auto' ? 'Auto · ' + name : name });
        }));
      }
      if (document.activeElement !== this.station2) this.station2.value = val2;
    },
    syncMbta: function () {
      const set = str(settings().mbtaKey) === 'set';
      this.mbtaStatus.textContent = set
        ? 'A key is saved, so the 85 streams live. It is never shown again.'
        : 'Without a key Lucent polls the MBTA gently. Get a free key at api-v3.mbta.com and paste it here.';
      this.mbtaRemove.hidden = !set;
      this.mbtaInput.placeholder = set ? 'Paste a new key to replace it' : '32 letters and digits';
    },
    syncWifi: function () {
      const st = settings();
      if (document.activeElement !== this.ssid && !this.ssid.dataset.dirty) this.ssid.value = str(st.wifiSsid);
      this.wpass.placeholder = st.wifiSet ? 'Saved. Type to replace it' : 'Password';
    },
    renderFeeds: function () {
      const feeds = arr(obj(S.calendar).feeds).map(obj);
      if (!changed('s.feeds', feeds)) return;
      const self = this;
      if (!feeds.length) { fill(this.feedList, h('li', null, empty('No calendars yet.'))); return; }
      fill(this.feedList, feeds.map(function (f, i) {
        const rm = h('button', { class: 'btn btn-icon btn-icon-sm', type: 'button', 'aria-label': 'Remove ' + (str(f.name) || 'calendar') }, icon('trash'));
        confirmTap(rm, 'Remove?', async function () {
          const list = feeds.filter(function (x, j) { return j !== i; }).map(function (x) { return { name: str(x.name), url: str(x.url) }; });
          try { await setSetting('calendars', list); toast('Calendar removed'); pollSoon(); } catch (e) { toast(e.message, 'alert'); }
        });
        const ok = f.ok !== false;
        return h('li', { class: 'feed', vars: { '--cal': CAL_COLOURS[i % CAL_COLOURS.length] } },
          h('span', { class: 'legend-dot', 'aria-hidden': 'true' }),
          h('div', { class: 'feed-main' },
            h('p', { class: 'feed-name', text: str(f.name) || 'Calendar' }),
            h('p', { class: 'feed-url', text: str(f.url) }),
            h('p', { class: 'feed-state ' + (ok ? 'ok' : 'bad'), text: ok ? (num(f.count) !== null ? plural(f.count, 'event') + ' coming up' : 'Loading') : 'Not loading. Check the address.' })),
          rm);
      }));
      void self;
    },
    renderAlexa: function (force) {
      const a = obj(S.alexa);
      const on = a.on === true || str(settings().alexa) === 'on';
      this.alexaOn.set(on);
      if (!changed('s.alexa', [on, a.url, a.stopUrl, a.commands]) && !force) return;
      if (!on) {
        fill(this.alexaBody, h('p', { class: 'set-help', text: 'Off. Turn it on for private links you can paste into Alexa.' }));
        return;
      }
      const urls = [['Find an e-bike', str(a.url), 'alexa-url'], ['Stop and silence', str(a.stopUrl), 'alexa-stop']].filter(function (u) { return u[1]; });
      const fresh = h('button', { class: 'btn btn-quiet btn-sm', type: 'button' }, icon('refresh'), 'Make new links');
      confirmTap(fresh, 'Tap again. Old links stop working', async function () {
        try { await setSetting('alexa', 'new'); toast('New links made. Update them in Alexa.'); pollSoon(); } catch (e) { toast(e.message, 'alert'); }
      });
      const commands = arr(a.commands).map(obj).filter(function (c) { return str(c.name) && str(c.on); });
      fill(this.alexaBody, [
        h('p', { class: 'set-help', text: 'At virtualsmarthome.xyz/url_switch, sign in with Amazon, add a switch per command with these addresses, then enable the URL Switch skill in the Alexa app. Alexa, turn on bike finder works at once. For your own phrase, make a routine in the Alexa app.' }),
        commands.length ? h('ul', { class: 'cmds' }, commands.map(commandRow))
          : urls.length ? urls.map(function (u) { return copyField(u[0], u[1], u[2]); })
          : h('p', { class: 'set-help', text: 'The TV is making the links…' }),
        fresh
      ]);
    },
    renderDevices: function () {
      const list = arr(S.devices).map(obj);
      if (!changed('s.devices', [list, Math.floor(tvNow() / 60000)])) return;
      fill(this.deviceList, list.map(function (d) {
        const me = d.me === true;
        const rm = me ? null : h('button', { class: 'btn btn-sm btn-quiet', type: 'button', 'data-fk': 'rev-' + str(d.id) }, 'Revoke');
        if (rm) confirmTap(rm, 'Revoke?', async function () {
          try { await act('revoke', { id: d.id }); S.devices = arr(S.devices).filter(function (x) { return obj(x).id !== d.id; }); toast(str(d.name) + ' can’t control the TV any more'); views.settings.renderDevices(); } catch (e) { toast(e.message, 'alert'); }
        });
        return h('li', { class: 'device' + (me ? ' me' : '') },
          icon(/mac|pc|windows|laptop|computer|book/i.test(str(d.name)) ? 'laptop' : 'phone', 'row-ico'),
          h('div', { class: 'device-main' },
            h('p', { class: 'device-name' }, h('span', { text: str(d.name) || 'Device' }), me ? h('span', { class: 'badge', text: 'This device' }) : null),
            h('p', { class: 'device-meta' }, [str(d.ip), num(d.seen) ? 'seen ' + fmtAgo(d.seen) : ''].filter(Boolean).join(' · '))),
          rm);
      }));
    },
    renderHealth: function () {
      const list = arr(S.health).map(obj);
      if (!changed('s.health', list)) return;
      if (!list.length) { fill(this.healthList, h('li', null, empty('No sources reported yet.'))); return; }
      fill(this.healthList, list.map(function (x) {
        const state = str(x.state) || 'Unknown';
        const s = state.toLowerCase();
        const cls = /^ok|good|live|fine/.test(s) ? 'good' : /limit|slow|back|wait|pause/.test(s) ? 'warn' : /fail|down|err|block/.test(s) ? 'alert' : 'idle';
        const nums = [
          ['sent', x.sent], ['new', x.ok], ['unchanged', x.unchanged], ['limited', x.limited], ['failed', x.failed]
        ].filter(function (p) { return num(p[1]) !== null; });
        return h('li', { class: 'h-row' },
          h('div', { class: 'h-top' }, h('p', { class: 'h-host', text: str(x.host) || 'source' }), h('span', { class: 'h-state ' + cls, text: state })),
          h('p', { class: 'h-nums' }, nums.map(function (p) {
            return h('span', { class: 'h-num' + (p[0] === 'limited' && p[1] > 0 ? ' warn' : p[0] === 'failed' && p[1] > 0 ? ' alert' : '') }, h('span', { class: 'h-n', text: fmtInt(p[1]) }), ' ' + p[0]);
          }), num(x.perMinute) !== null ? h('span', { class: 'h-num' }, h('span', { class: 'h-n', text: String(x.perMinute) }), ' / min') : null));
      }));
    },
    renderAbout: function () {
      const tv = obj(S.tv);
      const display = obj(S.display);
      if (!changed('s.about', [tv.name, tv.version, tv.urls, tv.access, obj(S.me).name, display])) return;
      const picture = pictureLine(display);
      const urls = arr(tv.urls).map(str).filter(Boolean);
      const access = str(tv.access) === 'lan' ? 'Home Wi-Fi and Tailscale' : str(tv.access) === 'tailnet' ? 'Tailscale only' : str(tv.access);
      fill(this.aboutBody, [h('dl', { class: 'kv' },
        h('div', null, h('dt', { text: 'TV' }), h('dd', { text: (str(tv.name) || 'Lucent') + (str(tv.version) ? ' ' + str(tv.version) : '') })),
        str(obj(S.me).name) ? h('div', null, h('dt', { text: 'This device' }), h('dd', { text: str(obj(S.me).name) })) : null,
        urls.length ? h('div', null, h('dt', { text: 'Address' }), h('dd', { class: 'mono-text', text: urls.join('  ') })) : null,
        access ? h('div', null, h('dt', { text: 'Reachable from' }), h('dd', { text: access })) : null,
        picture ? h('div', null, h('dt', { text: 'Picture' }), h('dd', { text: picture })) : null,
        h('div', null, h('dt', { text: 'Privacy' }), h('dd', { text: 'This page only ever talks to the TV.' }))),
        str(display.advice) ? h('p', { class: 'amber-note', text: str(display.advice) }) : null]);
    }
  };

  // "Claude Opus 5.5" from "claude-opus-5-5".
  function modelName(id) {
    const m = /^claude-([a-z]+)-(\d+)(?:-(\d{1,2}))?(?:-|$)/i.exec(str(id));
    if (!m) return str(id) || 'Claude';
    return 'Claude ' + m[1].charAt(0).toUpperCase() + m[1].slice(1).toLowerCase() + ' ' + m[2] + (m[3] ? '.' + m[3] : '');
  }

  function setRow(label, help, control) {
    return h('div', { class: 'set-row' },
      h('div', { class: 'set-text' }, h('p', { class: 'set-label', text: label }), help ? h('p', { class: 'set-help', text: help }) : null),
      control);
  }

  // Copies text. Plain http has no clipboard API, so this falls back to execCommand on a
  // selection, either in the given field or in a hidden buffer. Resolves true when it worked.
  async function copyText(value, input) {
    try {
      if (navigator.clipboard && window.isSecureContext) { await navigator.clipboard.writeText(value); return true; }
    } catch (e) { /* fall through to the selection */ }
    const field = input || h('textarea', { class: 'copy-buffer', readOnly: true, tabindex: '-1', 'aria-hidden': 'true' });
    if (!input) { field.value = value; document.body.appendChild(field); }
    field.focus({ preventScroll: true });
    field.select();
    try { field.setSelectionRange(0, value.length); } catch (e) { /* some inputs refuse */ }
    let ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    if (!input) field.remove();
    return ok;
  }

  // A read-only field with a copy button. When copying fails the text is left selected.
  function copyField(label, value, id) {
    const input = h('input', { class: 'input mono-input copy-input', id: id, type: 'text', readOnly: true, value: value, spellcheck: 'false' });
    const btn = h('button', { class: 'btn', type: 'button' }, icon('copy'), 'Copy');
    input.addEventListener('focus', function () { input.select(); });
    btn.addEventListener('click', async function () {
      const ok = await copyText(value, input);
      toast(ok ? 'Copied' : 'Selected. Use Copy from the menu.');
    });
    return h('div', { class: 'field copy-field' }, h('label', { class: 'label', for: id, text: label }), h('div', { class: 'inline-form' }, input, btn));
  }

  // A command line to type on a computer, shown whole so it wraps, with a copy button.
  // When copying fails the line itself is selected.
  function codeField(value, id, label) {
    const code = h('code', { class: 'code-block', id: id, text: value, 'aria-label': label });
    const btn = h('button', { class: 'btn btn-icon', type: 'button', 'aria-label': 'Copy ' + label.toLowerCase() }, icon('copy'));
    btn.addEventListener('click', async function () {
      const ok = await copyText(value);
      if (!ok) {
        const sel = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(code);
        sel.removeAllRanges();
        sel.addRange(range);
      }
      toast(ok ? 'Copied' : 'Selected. Use Copy from the menu.');
    });
    return h('div', { class: 'code-row' }, code, btn);
  }

  // One Alexa command: what to say, and copy buttons for its on and off addresses.
  function commandRow(c) {
    const reveal = h('div', { class: 'cmd-reveal' });
    const button = function (which, url) {
      const b = h('button', { class: 'btn btn-sm', type: 'button', 'aria-label': 'Copy the ' + which + ' address for ' + str(c.name) }, icon('copy'), which === 'on' ? 'On address' : 'Off address');
      b.addEventListener('click', async function () {
        const ok = await copyText(url);
        if (ok) { toast('Copied the ' + which + ' address'); return; }
        const id = 'cmd-' + str(c.name).replace(/[^a-z0-9]+/gi, '-').toLowerCase() + '-' + which;
        fill(reveal, copyField(str(c.name) + ', ' + which + ' address', url, id));
        const input = $('input', reveal);
        input.focus();
        input.select();
        toast('Selected. Use Copy from the menu.');
      });
      return b;
    };
    return h('li', { class: 'cmd' },
      h('p', { class: 'cmd-name', text: str(c.name) }),
      str(c.say) ? h('p', { class: 'cmd-say', text: '“' + str(c.say) + '”' }) : null,
      h('div', { class: 'btn-row cmd-btns' }, button('on', str(c.on)), str(c.off) ? button('off', str(c.off)) : null),
      reveal);
  }

  // "4K, 60 Hz, HDR10, HLG. Apps draw at 1080p, films play in 4K." from state.display.
  function resLabel(w, hgt) {
    if (!num(w) || !num(hgt)) return '';
    if (hgt >= 2160 || w >= 3840) return '4K';
    if (hgt >= 1440) return '1440p';
    if (hgt >= 1080) return '1080p';
    if (hgt >= 720) return '720p';
    return w + '\u00A0×\u00A0' + hgt;
  }
  function pictureLine(d) {
    const res = resLabel(d.width, d.height);
    if (!res) return '';
    const parts = [res];
    if (num(d.hz)) parts.push(Math.round(d.hz) + ' Hz');
    for (const x of arr(d.hdr).map(str).filter(Boolean)) parts.push(x);
    const ui = resLabel(d.uiWidth, d.uiHeight);
    return parts.join(', ') + '.' + (ui ? ' Apps draw at ' + ui + ', films play in ' + res + '.' : '');
  }

  /* ------------------------------------------------------------------ start */

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
