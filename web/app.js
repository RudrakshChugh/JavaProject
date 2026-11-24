'use strict';
/* Skill Exchange front end. Plain JavaScript talking to the Java API in skillexchange.web.WebServer. */

const $ = (sel, el = document) => el.querySelector(sel);
const $$ = (sel, el = document) => [...el.querySelectorAll(sel)];
const view = $('#view');
const modal = $('#modal');
const root = document.documentElement;

const state = {
  user: null,          // the signed-in student { id, name, credits }, as the server's session says
  me: null,            // ...and their id, for short
  students: [],        // everyone's public { id, name }
  listings: [],        // my listings, as shown on the Teach page
  tab: null,
  q: '',               // browse: search text
  type: 'ALL',         // browse: listing type filter
  slots: [],           // every open slot, soonest first
  activity: [],        // recent bookings, newest first
  pulseIndex: 0,       // which recent booking the home page is showing
  bookings: [],        // my bookings, as shown on the bookings page
  bookingFilter: 'ALL',
  mine: [],            // my bookings, kept fresh in the background
  mineStatus: null,    // booking id -> status at the last check, to notice what changed
  pageJson: '',        // the current page's data as text, to tell whether a background check found anything new
  pendingSlot: null,   // slot the visitor tried to book before signing in
  cmds: [],            // search palette: the commands currently listed
  cmdIndex: 0,
};
const TYPE_LABEL = { PAID: 'Paid', SWAP: 'Credit swap', FREE: 'Free' };
const STATUS_LABEL = { CONFIRMED: 'Confirmed', COMPLETED: 'Completed', CANCELLED: 'Cancelled' };
const STARTING_CREDITS = 5;
const DEMO_PASSWORD = 'demo123';   // the same as DemoData.DEMO_PASSWORD; shown as a hint on the sign-in screen
const MIN_PASSWORD = 6;            // the same as Student.MIN_PASSWORD_LENGTH
const POLL_MS = 6000;   // how often the page checks the server for changes made by other students
// Exceptions thrown by the booking rules. Their class name is shown next to the error message.
const RULE_ERROR = /^(Booking|SlotAlreadyBooked|BookingConflict|InsufficientCredits)Exception$/;

// ---------- Icons ----------

const ICONS = {
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  calendar: '<rect x="3" y="4.5" width="18" height="16" rx="2"/><path d="M3 9.5h18M8 2.5v4M16 2.5v4"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  moon: '<path d="M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5a8.5 8.5 0 1 0 11 11z"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
  lock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  wallet: '<rect x="3" y="6" width="18" height="13" rx="2"/><path d="M3 10h18M16 14.5h2"/>',
  up: '<path d="M3 17l6-6 4 4 8-8M15 7h6v6"/>',
  undo: '<path d="M4 9h10a5 5 0 0 1 0 10H9M4 9l4-4M4 9l4 4"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5.5M12 7.5v.01"/>',
  alert: '<path d="M12 3.5 2.5 20h19L12 3.5zM12 10v4.5M12 17.2v.01"/>',
  play: '<path d="M7 5l12 7-12 7z"/>',
  chevron: '<path d="M6 9l6 6 6-6"/>',
  dice: '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="M9 9v.01M15 9v.01M12 12v.01M9 15v.01M15 15v.01"/>',
  bolt: '<path d="M13 2 4 14h7l-1 8 9-12h-7l1-8z"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 20.5c0-3.6 3.6-6 8-6s8 2.4 8 6"/>',
};
const icon = (name) => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name]}</svg>`;

// ---------- Helpers ----------

async function api(path, data) {
  const opts = data ? { method: 'POST', body: new URLSearchParams(data) } : {};
  let res;
  try {
    res = await fetch('/api/' + path, opts);
  } catch {
    const err = new Error('Cannot reach the server. Check that the Java app is still running.');
    err.type = 'Offline';
    throw err;
  }
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(body.message || 'Request failed');
    err.type = body.type || 'Error';
    // 401 while this page thinks someone is signed in: the session is gone (the server restarted, or it expired).
    if (res.status === 401 && state.me && path !== 'login') {
      err.quiet = true;
      sessionEnded();
    }
    throw err;
  }
  return body;
}

function sessionEnded() {
  state.user = null;
  state.me = null;
  state.mine = [];
  state.mineStatus = null;
  renderAccount();
  toast('You were signed out. Sign in again to continue.', 'info');
  render();
}

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function store(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(key);
    if (value === null) localStorage.removeItem(key); else localStorage.setItem(key, value);
  } catch { /* storage off */ }
  return null;
}

const me = () => state.user;
const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;
const badge = (text, cls, dot = false) => `<span class="badge b-${cls}${dot ? ' dot' : ''}">${esc(text)}</span>`;
const typeBadge = (type) => badge(TYPE_LABEL[type] || type, type.toLowerCase());

function avatar(person, size = '') {
  const initials = person.name.trim().split(/\s+/).slice(0, 2).map((w) => Array.from(w)[0]).join('').toUpperCase();
  const colour = [...person.id].reduce((n, ch) => n + ch.charCodeAt(0), 0) % 5;
  return `<span class="avatar ${size} av-${colour}" aria-hidden="true">${esc(initials)}</span>`;
}

// The server sends local date-times with no zone ("2026-11-05T14:30"). Read them as local time.
function toDate(iso) {
  const [y, mo, d, h, mi, s = 0] = iso.split(/[-T:]/).map(Number);
  return new Date(y, mo - 1, d, h, mi, s);
}
const hhmm = (iso) => iso.slice(11, 16);
const timeRange = (slot) => `${hhmm(slot.start)} – ${hhmm(slot.end)}`;
const dateFmt = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
const shortDate = (iso) => dateFmt.format(toDate(iso));
const stamp = (iso) => `${shortDate(iso)}, ${hhmm(iso)}`;
function daysAway(iso) {
  const midnight = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate());
  return Math.round((midnight(toDate(iso)) - midnight(new Date())) / 86400000);
}
/** "Today · Wed 25 Nov", "Tomorrow · Thu 26 Nov", or just the date. */
function dayLabel(iso) {
  const days = daysAway(iso);
  return (days === 0 ? 'Today · ' : days === 1 ? 'Tomorrow · ' : '') + shortDate(iso);
}
/** "today", "tomorrow" or "on Thu 26 Nov". */
function dayWord(iso) {
  const days = daysAway(iso);
  return days === 0 ? 'today' : days === 1 ? 'tomorrow' : 'on ' + shortDate(iso);
}
/** How long ago a moment was: "just now", "5 min ago", "2 hours ago". */
function ago(iso) {
  const s = Math.max(0, (Date.now() - toDate(iso)) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return plural(Math.floor(s / 3600), 'hour') + ' ago';
  return plural(Math.floor(s / 86400), 'day') + ' ago';
}
/** Time left until a moment, for the ticking countdown: "in 17h 04m 09s". */
function timeLeft(iso) {
  // Rounded up, so it reads "starting now" only once the moment has really arrived. Rounding down said so
  // up to a second early, while the booking still counted as "not started".
  let s = Math.ceil((toDate(iso) - Date.now()) / 1000);
  if (s <= 0) return 'starting now';
  const two = (n) => String(n).padStart(2, '0');
  const d = Math.floor(s / 86400); s -= d * 86400;
  const h = Math.floor(s / 3600); s -= h * 3600;
  const m = Math.floor(s / 60); s -= m * 60;
  return d ? `in ${d}d ${h}h ${two(m)}m` : `in ${h}h ${two(m)}m ${two(s)}s`;
}
function nowLocal() {
  const d = new Date();
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
}

const head = (title, sub) => `<div class="page-head"><h1>${title}</h1>${sub ? `<p>${sub}</p>` : ''}</div>`;
const empty = (iconName, title, text, action = '') => `
  <div class="empty">
    <span class="empty-icon">${icon(iconName)}</span>
    <h3>${title}</h3>${text ? `<p>${text}</p>` : ''}${action}
  </div>`;
const skeleton = (blocks = 3, height = 96) => `
  <div class="stack" aria-busy="true" aria-label="Loading">
    <div class="skeleton" style="height:36px;width:min(280px,60%)"></div>
    ${`<div class="skeleton" style="height:${height}px"></div>`.repeat(blocks)}
  </div>`;

function toast(message, kind = 'ok', type = '') {
  const t = document.createElement('div');
  t.className = `toast ${kind}`;
  t.innerHTML = `${icon({ err: 'alert', info: 'bolt' }[kind] || 'check')}
    <div>${type ? `<span class="badge b-err">${esc(type)}</span>` : ''}<p>${esc(message)}</p></div>
    <button class="icon-btn" aria-label="Dismiss">${icon('x')}</button>`;
  $('button', t).addEventListener('click', () => t.remove());
  $('#toasts').append(t);
  setTimeout(() => t.remove(), kind === 'err' ? 7000 : 4500);
}
const fail = (e) => { if (!e.quiet) toast(e.message, 'err', RULE_ERROR.test(e.type) ? e.type : ''); };

function showModal(html) {
  $('#modal-body').innerHTML = html;
  if (!modal.open) modal.showModal();
  const first = $('#modal-body [autofocus], #modal-body input, #modal-body button');
  if (first) first.focus();
}
const closeModal = () => modal.open && modal.close();

/** Disables a button while its request runs, so a double click cannot send it twice. */
async function busy(btn, work) {
  if (btn) { btn.disabled = true; btn.classList.add('busy'); }
  try { return await work(); } finally { if (btn) { btn.disabled = false; btn.classList.remove('busy'); } }
}

// ---------- Motion ----------

/** No movement for people who asked their system for less of it, or while the page is in a background tab. */
const still = () => document.hidden || matchMedia('(prefers-reduced-motion: reduce)').matches;

/** Counts a number up (or down) to its new value. */
function countUp(el, to, from = Number(el.textContent) || 0) {
  if (still() || from === to) { el.textContent = to; return; }
  const started = performance.now();
  const ms = 700;
  const frame = (now) => {
    const p = Math.min(1, (now - started) / ms);
    el.textContent = Math.round(from + (to - from) * (1 - Math.pow(1 - p, 3)));
    if (p < 1) requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}

/** Makes the matching elements slide in one after another. */
function stagger(scope, selector) {
  $$(selector, scope).forEach((el, i) => {
    el.style.setProperty('--i', Math.min(i, 16));
    el.classList.add('rise');
  });
}

/** Plays a page's arrival: sections slide in, lists follow, numbers count up from zero. */
function enter() {
  stagger(view, ':scope > *');
  stagger(view, '[data-stagger] > *');
  $$('[data-count]', view).forEach((el) => countUp(el, Number(el.dataset.count), 0));
}

/** A short burst of confetti in the site's colours. */
function confetti() {
  if (still()) return;
  const canvas = document.createElement('canvas');
  canvas.className = 'confetti';
  document.body.append(canvas);
  const ctx = canvas.getContext('2d');
  const W = canvas.width = innerWidth;
  const H = canvas.height = innerHeight;
  const css = getComputedStyle(root);
  const colours = ['--brand', '--sun', '--pink', '--mint', '--sky-fg'].map((v) => css.getPropertyValue(v).trim());
  const bits = Array.from({ length: 120 }, (_, i) => ({
    x: W / 2 + (Math.random() - .5) * W * .25, y: H * .38,
    vx: (Math.random() - .5) * 16, vy: -Math.random() * 14 - 4,
    size: Math.random() * 7 + 4, turn: Math.random() * 6.3, spin: (Math.random() - .5) * .4,
    colour: colours[i % colours.length],
  }));
  const started = performance.now();
  const LIFE = 1900;
  const frame = (now) => {
    const t = now - started;
    ctx.clearRect(0, 0, W, H);
    for (const b of bits) {
      b.vy += .42; b.vx *= .99; b.x += b.vx; b.y += b.vy; b.turn += b.spin;
      ctx.save();
      ctx.translate(b.x, b.y);
      ctx.rotate(b.turn);
      ctx.globalAlpha = Math.max(0, 1 - t / LIFE);
      ctx.fillStyle = b.colour;
      ctx.fillRect(-b.size / 2, -b.size / 3, b.size, b.size * .66);
      ctx.restore();
    }
    if (t < LIFE) requestAnimationFrame(frame); else canvas.remove();
  };
  requestAnimationFrame(frame);
}

// Cards with the "spot" class get a soft light under the pointer.
document.addEventListener('pointermove', (e) => {
  const el = e.target.closest && e.target.closest('.spot');
  if (!el) return;
  const box = el.getBoundingClientRect();
  el.style.setProperty('--mx', `${e.clientX - box.left}px`);
  el.style.setProperty('--my', `${e.clientY - box.top}px`);
});

// ---------- Light and dark theme ----------

function paintThemeToggle() {
  const dark = root.dataset.theme === 'dark';
  const btn = $('#theme-toggle');
  btn.innerHTML = icon(dark ? 'sun' : 'moon');
  btn.setAttribute('aria-label', dark ? 'Switch to light theme' : 'Switch to dark theme');
  btn.title = dark ? 'Light theme' : 'Dark theme';
}

function applyTheme(theme) {
  root.dataset.theme = theme;
  store('skx.theme', theme);
  paintThemeToggle();
}

// ---------- Account ----------

/** Asks the server who is signed in (it knows from the session cookie) and loads the list of students. */
async function refreshStudents() {
  const [who, students] = await Promise.all([api('me'), api('students')]);
  state.user = who.student;
  state.me = who.student ? who.student.id : null;
  state.students = students;
  renderAccount();
}

let shownCredits = null;   // the balance the top bar last showed, to notice when it changes

function renderAccount() {
  const m = me();
  $('#account').innerHTML = m
    ? `<button class="account-chip" data-action="signin" aria-label="Signed in as ${esc(m.name)}, ${plural(m.credits, 'credit')}. Switch profile">
         ${avatar(m)}<span class="who">${esc(m.name)}</span>
         <span class="credit-count" title="Credit balance">${plural(m.credits, 'credit')}</span>${icon('chevron')}
       </button>`
    : '<button class="btn primary sm" data-action="signin">Sign in</button>';
  if (m && shownCredits && shownCredits.id === m.id && shownCredits.credits !== m.credits) {
    $('.credit-count').classList.add('bump');   // the balance just changed: give it a little pop
  }
  shownCredits = m ? { id: m.id, credits: m.credits } : null;
}

function openSignIn() {
  const signedIn = Boolean(me());
  showModal(`
    <h2 id="modal-title">${signedIn ? 'Switch profile' : 'Sign in'}</h2>
    <p class="muted">Choose your profile, then enter your password.</p>
    <div class="people">
      ${state.students.map((s) => `
        <button class="person ${s.id === state.me ? 'on' : ''}" data-action="pick" data-id="${esc(s.id)}">
          ${avatar(s, 'lg')}
          <span><b>${esc(s.name)}</b><span class="mono">${esc(s.id)}</span></span>
          ${s.id === state.me ? icon('check') : ''}
        </button>`).join('')}
    </div>
    <p class="note">${icon('info')}<span>Trying the demo? Asha, Ravi, Meera and Kabir all use the password <code>${DEMO_PASSWORD}</code>.</span></p>
    <div class="divider">or create a profile</div>
    <form id="register" class="form">
      <div class="two">
        <label class="field">Name<input class="input" name="name" required maxlength="40" autocomplete="name"></label>
        <label class="field"><span>Email <span class="opt">(optional)</span></span>
          <input class="input" name="email" type="email" maxlength="60" autocomplete="email"></label>
      </div>
      <label class="field">Password
        <input class="input" name="password" type="password" required minlength="${MIN_PASSWORD}" maxlength="72" autocomplete="new-password">
        <span class="hint">At least ${MIN_PASSWORD} characters. Only a scrambled version is stored, never the password itself.</span></label>
      <button class="btn primary block">Create profile</button>
      <p class="hint">New profiles start with ${STARTING_CREDITS} credits.</p>
    </form>
    ${signedIn ? '<button class="btn quiet block" data-action="signout">Sign out</button>' : ''}`);
}

/** Second step of signing in: the password for the chosen profile. */
function openPassword(id) {
  const s = state.students.find((x) => x.id === id);
  if (!s) return;
  showModal(`
    <div class="profile-head">
      ${avatar(s, 'xl')}
      <div><h2 id="modal-title">Sign in as ${esc(s.name)}</h2>
        <p class="muted small">Student <span class="mono">${esc(s.id)}</span></p></div>
    </div>
    <form id="login" class="form">
      <input type="hidden" name="studentId" value="${esc(s.id)}">
      <label class="field">Password
        <input class="input" name="password" type="password" required autocomplete="current-password" autofocus></label>
      <p class="form-error" id="login-error" role="alert" hidden></p>
      <div class="actions">
        <button type="button" class="btn" data-action="signin">Back</button>
        <button class="btn primary">Sign in</button>
      </div>
    </form>`);
}

/** The server has just started a session (after a login or a new profile): load it and carry on. */
async function afterSignIn() {
  const pending = state.pendingSlot;   // read it now: closing the dialog clears it
  state.pendingSlot = null;
  state.mineStatus = null;
  closeModal();
  await refreshStudents();
  await syncMine(false).catch(() => {});
  render();
  if (pending) openBook(pending);
}

// ---------- Staying up to date ----------

/**
 * Loads the signed-in student's bookings. With announce on, anything that changed since the last check
 * was done by someone else (this page re-syncs silently after its own actions), so it is announced.
 * Returns true if anything changed.
 */
async function syncMine(announce) {
  if (!state.me) {
    state.mine = [];
    state.mineStatus = null;
    return false;
  }
  const who = state.me;
  const list = await api('me/bookings');
  if (who !== state.me) return false;   // the profile changed while we waited: this answer is for someone else
  const before = state.mineStatus;
  const changed = JSON.stringify(list) !== JSON.stringify(state.mine);
  state.mine = list;
  state.mineStatus = new Map(list.map((b) => [b.id, b.status]));
  if (!announce || !before) return changed;
  for (const b of list) {
    const was = before.get(b.id);
    const teaching = b.role === 'TEACHING';
    const other = teaching ? b.learner.name : b.tutor.name;
    const cost = b.slot.creditCost;
    if (!was && teaching && b.status === 'CONFIRMED') {
      toast(`${other} booked your ${b.slot.skill} session.`, 'info');
    } else if (was === 'CONFIRMED' && b.status === 'COMPLETED' && teaching) {
      toast(`${other} confirmed ${b.slot.skill}.${cost ? ` You earned ${plural(cost, 'credit')}.` : ''}`);
      confetti();
    } else if (was === 'CONFIRMED' && b.status === 'CANCELLED') {
      toast(`The ${b.slot.skill} booking with ${other} was cancelled.`, 'info');
    }
  }
  return changed;
}

function setOnline(online) {
  $$('.live').forEach((el) => {
    el.classList.toggle('off', !online);
    const label = $('span', el);
    if (label) label.textContent = online ? 'Live' : 'Offline';
  });
}

/** Runs every few seconds: picks up what other students did and updates the page in place. */
async function tick() {
  if (document.hidden || modal.open) return;
  try {
    const creditsBefore = me() ? me().credits : null;
    const wasSignedIn = Boolean(state.me);
    await refreshStudents();
    const mineChanged = await syncMine(true);
    setOnline(true);
    if (wasSignedIn && !state.me) {   // the session is gone, e.g. the server was restarted
      toast('You were signed out. Sign in again to continue.', 'info');
      return render();
    }
    const page = PAGES[state.tab];
    if (!page || (page.auth && !me())) return;
    const creditsChanged = me() && me().credits !== creditsBefore;
    // The home page also shows the balance and the next session, which are not part of its own data.
    const force = state.tab === 'browse' && (mineChanged || creditsChanged);
    if (!page.live) return;
    const seq = renderSeq;
    const data = await page.load();
    if (seq !== renderSeq || modal.open) return;
    const json = JSON.stringify(data);
    if (json === state.pageJson && !force) return;
    state.pageJson = json;
    if (page.patch) {
      page.patch(data);
    } else {
      view.innerHTML = page.view(data);
      if (page.ready) page.ready(false);
    }
  } catch (e) {
    if (e.type === 'Offline') setOnline(false);
  }
}

let seconds = 0;
/** Runs every second: keeps the countdowns ticking and rotates the recent-bookings line. */
function everySecond() {
  let justStarted = false;
  $$('[data-until]').forEach((el) => {
    const text = timeLeft(el.dataset.until);
    if (text === 'starting now' && el.textContent !== text) justStarted = true;
    el.textContent = text;
  });
  // A session has just begun: redraw the bookings so its "confirm" button appears.
  if (justStarted && state.tab === 'bookings' && !modal.open) render();
  if (++seconds % 4 === 0 && state.activity.length > 1 && !document.hidden) {
    state.pulseIndex++;
    paintPulse();
  }
}

// ---------- Router ----------

const PAGES = {
  browse: { title: 'Browse', load: loadBrowse, view: browseView, ready: browseReady, live: true, patch: patchBrowse },
  bookings: { title: 'My bookings', auth: 'Sign in to see the sessions you are learning in and the ones you are teaching.', load: () => api('me/bookings'), view: bookingsView, live: true },
  teach: { title: 'Teach', auth: 'Sign in to list a skill and publish the times you are free.', load: () => api('me/listings'), view: teachView, ready: updatePriceField },
  credits: { title: 'Credits', auth: 'Sign in to see your credit balance and history.', load: () => api('me/ledger'), view: creditsView, live: true },
  how: { title: 'How it works', load: async () => null, view: howView },
};

function currentTab() {
  const tab = location.hash.replace(/^#\/?/, '');
  return PAGES[tab] ? tab : 'browse';
}

let renderSeq = 0;
/** Draws the page named in the URL. If another render starts while this one waits for data, this one gives up. */
async function render() {
  const seq = ++renderSeq;
  const tab = currentTab();
  const page = PAGES[tab];
  const arriving = state.tab !== tab;
  state.tab = tab;
  document.title = `${page.title} · Skill Exchange`;
  $$('.nav a').forEach((a) => {
    if (a.dataset.tab === tab) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  if (page.auth && !me()) {
    view.innerHTML = head(page.title) + empty('lock', 'You are signed out', page.auth,
      '<button class="btn primary" data-action="signin">Sign in</button>');
    if (arriving) enter();
    return;
  }
  if (arriving) view.innerHTML = skeleton();
  try {
    const data = await page.load();
    if (seq !== renderSeq) return;
    state.pageJson = JSON.stringify(data);
    view.innerHTML = page.view(data);
    if (page.ready) page.ready(arriving);
    if (arriving) enter();
  } catch (e) {
    if (seq !== renderSeq) return;
    view.innerHTML = head(page.title) + empty('alert', 'Could not load this page', esc(e.message),
      '<button class="btn" data-action="retry">Try again</button>');
  }
}

function go(tab) {
  if (currentTab() === tab) render(); else location.hash = '#/' + tab;
}

// ---------- Browse ----------

async function loadBrowse() {
  // The recent-bookings line is an extra. If the server cannot provide it, the page still loads without it.
  const [stats, slots, activity] = await Promise.all([api('stats'), api('slots'), api('activity').catch(() => [])]);
  return { stats, slots, activity };
}

const STAT_LABELS = { students: 'Students', listings: 'Skills listed', openSlots: 'Open sessions', completed: 'Sessions completed' };

/** The signed-in student's next confirmed session, with a live countdown. Empty if there is none. */
function nextUpHtml() {
  const now = new Date();
  const next = state.mine
    .filter((b) => b.status === 'CONFIRMED' && toDate(b.slot.start) > now)
    .sort((a, b) => a.slot.start.localeCompare(b.slot.start))[0];
  if (!next) return '';
  const learning = next.role === 'LEARNING';
  return `
    <a class="next-up" href="#/bookings">
      ${icon('clock')}
      <span>
        <span class="small subtle">Your next session · ${learning ? 'learning' : 'teaching'}</span>
        <b>${esc(next.slot.skill)} ${learning ? `with ${esc(next.tutor.name)}` : `for ${esc(next.learner.name)}`}</b>
      </span>
      <span class="countdown" data-until="${esc(next.slot.start)}">${timeLeft(next.slot.start)}</span>
    </a>`;
}

/** The left half of the home page banner: a greeting when signed in, the pitch when not. */
function introHtml() {
  const m = me();
  return m
    ? `<p class="eyebrow">Signed in as ${esc(m.name)}</p>
       <h1>Welcome back, <em>${esc(m.name)}</em>.</h1>
       <p class="lede">You have ${plural(m.credits, 'credit')} to spend on swap sessions.
         Book a session below, or offer a skill of your own to earn more.</p>
       <div class="cta">
         <button class="btn primary" data-action="go" data-tab="teach">${icon('plus')}Offer a skill</button>
         <button class="btn" data-action="go" data-tab="bookings">My bookings</button>
       </div>
       ${nextUpHtml()}`
    : `<p class="eyebrow">Peer tutoring for students, by students</p>
       <h1>Teach what you know. <em>Learn</em> what you don't.</h1>
       <p class="lede">List a skill, publish the times you are free, and book sessions with each other.
         Pay a fee, spend credits you earned by teaching, or keep it free.</p>
       <div class="cta">
         <button class="btn primary" data-action="signin">Sign in to book</button>
         <button class="btn" data-action="go" data-tab="how">How it works</button>
       </div>`;
}

function browseView({ stats, slots, activity }) {
  state.slots = slots;
  state.activity = activity;
  return `
    <section class="hero">
      <div id="intro">${introHtml()}</div>
      <div class="hero-side">
        <div class="stats">
          ${Object.entries(STAT_LABELS).map(([key, label]) => `
            <div class="stat"><b data-count="${stats[key]}" data-key="${key}">${stats[key]}</b><span>${label}</span></div>`).join('')}
        </div>
        <div class="pulse" id="pulse" aria-label="Recent bookings"></div>
      </div>
    </section>
    <section aria-labelledby="explore-title" id="explore">
      <div class="section-head">
        <h2 id="explore-title">Explore skills</h2>
        <button class="btn sm" data-action="surprise">${icon('dice')}Surprise me</button>
      </div>
      <div class="rail" id="rail"></div>
    </section>
    <section aria-labelledby="open-title">
      <div class="section-head">
        <h2 id="open-title">Open sessions</h2>
        <div class="side">
          <span class="live" title="Checks for changes every ${POLL_MS / 1000} seconds"><i></i><span>Live</span></span>
          <span class="count" id="result-count"></span>
        </div>
      </div>
      <div class="toolbar">
        <label class="search">${icon('search')}<span class="sr-only">Search by skill or tutor</span>
          <input class="input" id="q" type="search" placeholder="Search by skill or tutor" value="${esc(state.q)}">
        </label>
        <div class="chips" id="type-chips" role="group" aria-label="Filter by type">
          ${['ALL', 'PAID', 'SWAP', 'FREE'].map((t) => `
            <button class="chip" data-action="filter" data-type="${t}">
              ${t === 'ALL' ? 'All' : TYPE_LABEL[t]} <span class="n"></span></button>`).join('')}
        </div>
      </div>
      <div id="results"></div>
    </section>`;
}

function browseReady(arriving) {
  paintPulse();
  paintRail();
  paintSlots({ animate: arriving });
  $('#q').addEventListener('input', (e) => {
    state.q = e.target.value;
    paintRail();
    paintSlots();
  });
}

/** A background check found something new: update the numbers, the rail and the cards without redrawing the page. */
function patchBrowse({ stats, slots, activity }) {
  if (!$('#intro')) return render();   // the page is showing an error or is still loading: draw it properly
  const known = new Set(state.slots.map((s) => s.id));
  const fresh = slots.filter((s) => !known.has(s.id));
  $$('.stat b[data-key]').forEach((el) => countUp(el, stats[el.dataset.key]));
  state.slots = slots;
  state.activity = activity;
  $('#intro').innerHTML = introHtml();
  paintPulse();
  paintRail();
  paintSlots({ fresh: new Set(fresh.map((s) => s.id)) });
  fresh.filter((s) => s.tutor.id !== state.me).slice(0, 2)
    .forEach((s) => toast(`Now open: ${s.skill} with ${s.tutor.name}, ${dayWord(s.start)} at ${hhmm(s.start)}.`, 'info'));
}

function paintPulse() {
  const el = $('#pulse');
  if (!el) return;
  const list = state.activity;
  if (!list.length) {
    el.innerHTML = '<span class="subtle">No bookings yet. Yours could be the first.</span>';
    return;
  }
  const a = list[state.pulseIndex % list.length];
  el.innerHTML = `
    <span class="pulse-item">
      ${avatar(a.learner, 'sm')}
      <span><b>${esc(a.learner.name)}</b> booked <b>${esc(a.skill)}</b> with ${esc(a.tutor.name)}</span>
      <span class="subtle nowrap">${ago(a.bookedAt)}</span>
    </span>`;
}

/** The open slots grouped by skill, in the order their first session comes up. */
function skillGroups() {
  const groups = new Map();
  for (const s of state.slots) {
    let g = groups.get(s.skill);
    if (!g) groups.set(s.skill, g = { skill: s.skill, count: 0, tutors: new Map(), types: new Set(), next: s.start });
    g.count++;
    g.tutors.set(s.tutor.id, s.tutor);
    g.types.add(s.type);
  }
  return [...groups.values()];
}

function paintRail() {
  const rail = $('#rail');
  if (!rail) return;
  const groups = skillGroups();
  $('#explore').hidden = !groups.length;
  const current = state.q.trim().toLowerCase();
  rail.innerHTML = groups.map((g) => {
    const on = g.skill.toLowerCase() === current;
    return `
      <button class="skill-tile spot ${on ? 'on' : ''}" data-action="skill" data-skill="${esc(g.skill)}" aria-pressed="${on}">
        <span class="row between">
          <span class="avatars">${[...g.tutors.values()].map((t) => avatar(t, 'sm')).join('')}</span>
          <span class="dots">${[...g.types].map(typeBadge).join('')}</span>
        </span>
        <b>${esc(g.skill)}</b>
        <span class="meta">${plural(g.count, 'open session')} · next ${dayWord(g.next)}</span>
      </button>`;
  }).join('');
}

function visibleSlots(type = state.type) {
  const k = state.q.trim().toLowerCase();
  return state.slots.filter((s) => (type === 'ALL' || s.type === type)
    && (!k || s.skill.toLowerCase().includes(k) || s.tutor.name.toLowerCase().includes(k)));
}

/** Fills in the filter counts and the result list. "fresh" holds the ids of slots that have just appeared. */
function paintSlots({ animate = false, fresh = new Set() } = {}) {
  const results = $('#results');
  if (!results) return;
  $$('#type-chips .chip').forEach((c) => {
    const t = c.dataset.type;
    const on = t === state.type;
    c.classList.toggle('on', on);
    c.setAttribute('aria-pressed', on);
    $('.n', c).textContent = visibleSlots(t).length;
  });
  const list = visibleSlots();
  $('#result-count').textContent = plural(list.length, 'session');
  if (!list.length) {
    const filtered = state.q.trim() || state.type !== 'ALL';
    results.innerHTML = filtered
      ? empty('search', 'No sessions match', 'Try another skill or tutor name, or clear the filters.',
          '<button class="btn" data-action="clear-filters">Clear filters</button>')
      : empty('calendar', 'No open sessions right now', 'Be the first to offer one.',
          '<button class="btn primary" data-action="go" data-tab="teach">Offer a skill</button>');
    return;
  }
  // The server sends slots soonest first, so the days come out in order.
  const days = new Map();
  for (const s of list) {
    const day = s.start.slice(0, 10);
    if (!days.has(day)) days.set(day, []);
    days.get(day).push(s);
  }
  results.innerHTML = [...days.values()].map((slots) => `
    <h3 class="day-head">${dayLabel(slots[0].start)}</h3>
    <div class="grid">${slots.map((s) => slotCard(s, fresh.has(s.id))).join('')}</div>`).join('');
  if (animate) stagger(results, '.slot');
}

function slotCard(s, isFresh = false) {
  const m = me();
  const mine = state.me === s.tutor.id;
  const short = m && !mine && s.creditCost > m.credits;
  const action = mine
    ? '<button class="btn block" disabled>Your own session</button>'
    : short
      ? `<p class="short">You need ${plural(s.creditCost, 'credit')} and have ${m.credits}.</p>
         <button class="btn block" disabled>Not enough credits</button>`
      : `<button class="btn primary block" data-action="book" data-slot="${esc(s.id)}">Book session</button>`;
  return `
    <article class="card slot spot t-${s.type.toLowerCase()} ${isFresh ? 'fresh' : ''}">
      <div class="row between">${typeBadge(s.type)}<span class="price">${esc(s.price)}</span></div>
      <h3>${esc(s.skill)}</h3>
      <p class="desc">${esc(s.description || 'No description yet.')}</p>
      <ul class="facts">
        <li><button class="tutor-link" data-action="tutor" data-id="${esc(s.tutor.id)}" title="See what ${esc(s.tutor.name)} teaches">
          ${avatar(s.tutor, 'sm')}<span class="sr-only">Tutor: </span><span class="text">${esc(s.tutor.name)}</span></button></li>
        <li>${icon('clock')}<span class="sr-only">Time: </span><span class="text">${timeRange(s)} <span class="sep">·</span> ${s.minutes} min</span></li>
      </ul>
      ${action}
    </article>`;
}

/** Opens a random session the visitor could actually book. */
function surprise() {
  const m = me();
  const options = state.slots.filter((s) => s.tutor.id !== state.me && (!m || s.creditCost <= m.credits));
  if (!options.length) return toast('There is nothing you can book right now.', 'info');
  openBook(options[Math.floor(Math.random() * options.length)].id);
}

function openBook(slotId) {
  const m = me();
  if (!m) {
    state.pendingSlot = slotId;   // carry on with this booking after signing in
    return openSignIn();
  }
  const s = state.slots.find((x) => x.id === slotId);
  if (!s) return toast('That session is no longer available.', 'info');
  if (s.tutor.id === m.id) return toast('That is your own session, so you cannot book it.', 'err');
  const tutor = esc(s.tutor.name);
  const short = s.creditCost > m.credits;
  const note = short
    ? `You need ${plural(s.creditCost, 'credit')} for this session and have ${m.credits}. Teach a swap session to earn more.`
    : {
        SWAP: `${plural(s.creditCost, 'credit')} will be held when you book. ${tutor} receives them only after you confirm
               the session took place. If the booking is cancelled, they come back to you.`,
        PAID: `You pay the fee to ${tutor} at the session. Nothing is charged here.`,
        FREE: `This is a free session. ${tutor} is volunteering their time.`,
      }[s.type];
  showModal(`
    <h2 id="modal-title">Book ${esc(s.skill)}</h2>
    <dl class="summary">
      <dt>Tutor</dt><dd>${tutor}</dd>
      <dt>Date</dt><dd>${dayLabel(s.start)}</dd>
      <dt>Time</dt><dd>${timeRange(s)} (${s.minutes} min)</dd>
      <dt>Price</dt><dd>${esc(s.price)}</dd>
      ${s.type === 'SWAP' && !short ? `<dt>Your balance after booking</dt><dd>${plural(m.credits - s.creditCost, 'credit')}</dd>` : ''}
    </dl>
    <p class="note ${short ? 'warn' : ''}">${icon(short ? 'alert' : 'info')}<span>${note}</span></p>
    <div class="actions">
      <button class="btn" data-action="close">Not now</button>
      <button class="btn primary" data-action="confirm-book" data-slot="${esc(s.id)}" ${short ? 'disabled' : 'autofocus'}>Confirm booking</button>
    </div>`);
}

/** Anyone's public profile: what they teach, when they are free, and how many sessions they have completed. */
async function openTutor(id) {
  const [p, slots] = await Promise.all([api(`students/${id}/profile`), api('slots')]);
  state.slots = slots;   // so a "Book" button in the profile finds its slot
  const own = p.id === state.me;
  const slotRow = (s) => `
    <li><span>${dayLabel(s.start)} · ${timeRange(s)}</span>
      ${own ? badge('Open', 'open', true) : `<button class="btn sm" data-action="book" data-slot="${esc(s.id)}">Book</button>`}</li>`;
  showModal(`
    <div class="profile-head">
      ${avatar(p, 'xl')}
      <div><h2 id="modal-title">${esc(p.name)}${own ? ' <span class="subtle small">(you)</span>' : ''}</h2>
        <p class="muted small">Student <span class="mono">${esc(p.id)}</span></p></div>
    </div>
    <div class="profile-stats">
      <div><b>${p.listings.length}</b><span>${p.listings.length === 1 ? 'skill' : 'skills'} offered</span></div>
      <div><b>${p.taught}</b><span>${p.taught === 1 ? 'session' : 'sessions'} taught</span></div>
      <div><b>${p.attended}</b><span>${p.attended === 1 ? 'session' : 'sessions'} attended</span></div>
    </div>
    ${p.listings.length ? p.listings.map((l) => `
      <div class="profile-listing">
        <div class="row between">${typeBadge(l.type)}<span class="price">${esc(l.price)}</span></div>
        <h3>${esc(l.skill)}</h3>
        <p class="muted small">${l.minutes} min${l.description ? ` · ${esc(l.description)}` : ''}</p>
        <ul class="slots-list">${l.slots.length ? l.slots.map(slotRow).join('') : '<li class="none">No open times right now</li>'}</ul>
      </div>`).join('') : `<p class="muted">${esc(p.name)} is not teaching anything yet.</p>`}`);
}

// ---------- My bookings ----------

function bookingsView(list) {
  state.bookings = list;
  if (!list.length) {
    return head('My bookings', 'Sessions you are learning in and sessions you are teaching.')
      + empty('calendar', 'No bookings yet', 'When you book a session, or someone books one of yours, it shows up here.',
          '<button class="btn primary" data-action="go" data-tab="browse">Browse open sessions</button>');
  }
  const count = (status) => list.filter((b) => status === 'ALL' || b.status === status).length;
  const chip = (status, label) => `
    <button class="chip ${state.bookingFilter === status ? 'on' : ''}" data-action="booking-filter" data-status="${status}"
      aria-pressed="${state.bookingFilter === status}">${label} <span class="n">${count(status)}</span></button>`;
  return head('My bookings', 'Sessions you are learning in and sessions you are teaching.') + `
    <div class="toolbar">
      <div class="chips" id="status-chips" role="group" aria-label="Filter by status">
        ${chip('ALL', 'All')}${chip('CONFIRMED', 'Confirmed')}${chip('COMPLETED', 'Completed')}${chip('CANCELLED', 'Cancelled')}
      </div>
      <span class="live" title="Checks for changes every ${POLL_MS / 1000} seconds"><i></i><span>Live</span></span>
    </div>
    <div class="stack" id="booking-list" data-stagger style="margin-top:16px">${bookingList()}</div>`;
}

function bookingList() {
  const list = state.bookings.filter((b) => state.bookingFilter === 'ALL' || b.status === state.bookingFilter);
  return list.length
    ? list.map(bookingRow).join('')
    : empty('calendar', `No ${STATUS_LABEL[state.bookingFilter].toLowerCase()} bookings`, '');
}

/** What is happening to the credits of a swap booking, from the viewer's side. Empty for paid and free sessions. */
function escrowNote(b, learning) {
  const cost = b.slot.creditCost;
  if (!cost) return '';
  const credits = plural(cost, 'credit');
  const verb = cost === 1 ? 'is' : 'are';
  const was = cost === 1 ? 'was' : 'were';
  const text = {
    CONFIRMED: learning
      ? `${credits} ${verb} held until you confirm the session took place.`
      : `${credits} ${verb} held for you until ${esc(b.learner.name)} confirms the session took place.`,
    COMPLETED: learning ? `${credits} ${was} paid to ${esc(b.tutor.name)}.` : `You earned ${credits}.`,
    CANCELLED: learning ? `${credits} ${was} refunded to you.` : `${credits} ${was} refunded to ${esc(b.learner.name)}.`,
  }[b.status];
  const iconName = { CONFIRMED: 'lock', COMPLETED: 'check', CANCELLED: 'undo' }[b.status];
  return `<p class="escrow">${icon(iconName)}<span>${text}</span></p>`;
}

function bookingRow(b) {
  const learning = b.role === 'LEARNING';
  const other = learning ? `with ${esc(b.tutor.name)}` : `for ${esc(b.learner.name)}`;
  const live = b.status === 'CONFIRMED';
  const upcoming = live && toDate(b.slot.start) > new Date();
  const d = toDate(b.slot.start);
  return `
    <article class="card booking ${live ? '' : 'is-off'}">
      <div class="date-tile" aria-hidden="true">
        <span class="mon">${d.toLocaleDateString('en-GB', { month: 'short' })}</span>
        <span class="day">${d.getDate()}</span>
        <span class="dow">${d.toLocaleDateString('en-GB', { weekday: 'short' })}</span>
      </div>
      <div>
        <div class="row wrap">${badge(learning ? 'Learning' : 'Teaching', learning ? 'learning' : 'teaching')} ${typeBadge(b.slot.type)}
          ${upcoming ? `<span class="countdown" data-until="${esc(b.slot.start)}">${timeLeft(b.slot.start)}</span>` : ''}</div>
        <h3>${esc(b.slot.skill)} <span>${other}</span></h3>
        <p class="muted small">${shortDate(b.slot.start)} · ${timeRange(b.slot)} · ${esc(b.slot.price)} · <span class="mono">${esc(b.id)}</span></p>
        ${escrowNote(b, learning)}
      </div>
      <div class="booking-side">
        ${badge(STATUS_LABEL[b.status] || b.status, b.status.toLowerCase(), true)}
        ${live ? `<div class="booking-actions">
          ${learning && !upcoming ? `<button class="btn primary sm" data-action="complete" data-id="${esc(b.id)}">Confirm it took place</button>` : ''}
          <button class="btn quiet warn sm" data-action="cancel" data-id="${esc(b.id)}">Cancel</button>
        </div>
        ${learning && upcoming ? '<p class="hint">You can confirm it once it starts.</p>' : ''}` : ''}
      </div>
    </article>`;
}

function openConfirm(kind, bookingId) {
  const b = state.bookings.find((x) => x.id === bookingId);
  if (!b) return;
  const learning = b.role === 'LEARNING';
  const cost = b.slot.creditCost;
  const credits = plural(cost, 'credit');
  const session = `${esc(b.slot.skill)} on ${shortDate(b.slot.start)}, ${timeRange(b.slot)}`;
  const complete = kind === 'complete';
  const consequence = complete
    ? (cost ? `The ${credits} held for this booking will be paid to ${esc(b.tutor.name)}.` : 'The booking will be marked as completed.')
      + ' This cannot be undone.'
    : 'The time slot opens up again for other students.'
      + (cost ? ` The ${credits} held for it will be refunded to ${learning ? 'you' : esc(b.learner.name)}.` : '');
  showModal(`
    <h2 id="modal-title">${complete ? 'Confirm the session took place?' : 'Cancel this booking?'}</h2>
    <p class="muted">${session}, ${learning ? `with ${esc(b.tutor.name)}` : `for ${esc(b.learner.name)}`}.</p>
    <p class="note ${complete ? '' : 'warn'}">${icon(complete ? 'info' : 'alert')}<span>${consequence}</span></p>
    <div class="actions">
      <button class="btn" data-action="close">${complete ? 'Not yet' : 'Keep booking'}</button>
      <button class="btn ${complete ? 'primary' : 'danger'}" data-action="do-${kind}" data-id="${esc(b.id)}">
        ${complete ? 'Yes, it took place' : 'Cancel booking'}</button>
    </div>`);
}

// ---------- Teach ----------

const TYPE_HELP = {
  SWAP: 'Learners pay in credits. The credits are held when they book and reach you once they confirm the session took place.',
  PAID: 'Learners pay you the fee at the session. Nothing is charged in the app.',
  FREE: 'A volunteer session. Learners pay nothing.',
};

function teachView(listings) {
  state.listings = listings;
  return head('Teach', 'Create a listing for something you can teach, then add the times you are free.') + `
    <div class="split">
      <form class="card form sticky" id="new-listing">
        <h2>New listing</h2>
        <div class="field">
          <fieldset class="seg">
            <legend>Listing type</legend>
            ${['SWAP', 'PAID', 'FREE'].map((t, i) => `
              <input type="radio" name="type" id="type-${t}" value="${t}" ${i === 0 ? 'checked' : ''}>
              <label for="type-${t}">${TYPE_LABEL[t]}</label>`).join('')}
          </fieldset>
          <p class="hint" id="type-help"></p>
        </div>
        <label class="field">Skill
          <input class="input" name="skill" required maxlength="60" placeholder="e.g. Python for beginners"></label>
        <label class="field"><span>Short description <span class="opt">(optional)</span></span>
          <input class="input" name="description" maxlength="120" placeholder="What will you cover?"></label>
        <div class="two">
          <label class="field">Length (minutes)
            <input class="input" name="minutes" type="number" min="15" max="240" step="5" value="60" required></label>
          <label class="field" id="price-field"></label>
        </div>
        <button class="btn primary block">Create listing</button>
      </form>
      <div class="stack" data-stagger>
        ${listings.length
          ? `<h2>Your listings <span class="subtle" style="font-weight:400">${listings.length}</span></h2>` + listings.map(listingCard).join('')
          : empty('plus', 'You are not teaching anything yet', 'Create your first listing, then add a time slot so students can book it.')}
      </div>
    </div>`;
}

function updatePriceField() {
  const form = $('#new-listing');
  const field = $('#price-field');
  if (!form || !field) return;
  const type = form.elements.type.value;
  $('#type-help').textContent = TYPE_HELP[type];
  field.hidden = type === 'FREE';
  field.innerHTML = type === 'PAID'
    ? 'Fee (Rs.)<input class="input" name="fee" type="number" min="1" max="100000" step="any" value="200" required>'
    : type === 'SWAP'
      ? 'Credits per session<input class="input" name="credits" type="number" min="1" max="10" value="2" required>'
      : '';
}

function listingCard(l) {
  const now = new Date();
  const slotRow = (s) => {
    // An open slot whose start time has passed can no longer be booked.
    // A slot the tutor cannot teach, because they booked someone else's session at that time, is hidden from learners.
    const [label, cls, why] = s.booked ? ['Booked', 'booked', '']
      : toDate(s.start) <= now ? ['Expired', 'expired', 'This time has passed.']
      : s.busy ? ['Hidden', 'hold', 'You are attending another session at this time, so learners cannot see or book this slot.']
      : ['Open', 'open', ''];
    const remove = s.booked ? '' : `<button class="icon-btn" data-action="remove-slot" data-id="${esc(s.id)}"
      aria-label="Remove the slot on ${shortDate(s.start)} at ${hhmm(s.start)}" title="Remove this slot">${icon('x')}</button>`;
    return `<li><span>${shortDate(s.start)} · ${timeRange(s)}</span>
      <span class="row"><span title="${why}">${badge(label, cls, true)}</span>${remove}</span></li>`;
  };
  return `
    <article class="card listing">
      <div class="row between">${typeBadge(l.type)}<span class="price">${esc(l.price)}</span></div>
      <h3>${esc(l.skill)}</h3>
      <p class="muted small">${l.minutes} min · <span class="mono">${esc(l.id)}</span>${l.description ? ` · ${esc(l.description)}` : ''}</p>
      <ul class="slots-list">
        ${l.slots.length ? l.slots.map(slotRow).join('') : '<li class="none">No time slots yet</li>'}
      </ul>
      <form class="add-slot" data-listing="${esc(l.id)}">
        <input class="input" type="datetime-local" name="start" required min="${nowLocal()}" aria-label="Start time of the new slot for ${esc(l.skill)}">
        <button class="btn sm">${icon('plus')}Add slot</button>
      </form>
      <button class="btn quiet warn sm delete-listing" data-action="delete-listing" data-id="${esc(l.id)}">Delete listing</button>
    </article>`;
}

function openDeleteListing(id) {
  const l = state.listings.find((x) => x.id === id);
  if (!l) return;
  showModal(`
    <h2 id="modal-title">Delete ${esc(l.skill)}?</h2>
    <p class="muted">Listing <span class="mono">${esc(l.id)}</span>, ${esc(l.price)}, with ${plural(l.slots.length, 'time slot')}.</p>
    <p class="note warn">${icon('alert')}<span>The listing and all its time slots are removed. Sessions that already took place
      stay in your bookings and credit history. A listing with a confirmed booking cannot be deleted: cancel the booking first.</span></p>
    <div class="actions">
      <button class="btn" data-action="close">Keep listing</button>
      <button class="btn danger" data-action="do-delete-listing" data-id="${esc(l.id)}">Delete listing</button>
    </div>`);
}

// ---------- Credits ----------

function creditsView(L) {
  return head('Credits', 'Earn credits by teaching a swap session. Spend them to learn something new.') + `
    <div class="money" data-stagger>
      <div class="card balance">
        <p class="label">${icon('wallet')}Available balance</p>
        <p class="big" data-count="${L.balance}">${L.balance}</p>
        <p class="hint">${L.balance === 1 ? 'credit' : 'credits'} ready to spend</p>
      </div>
      <div class="card">
        <p class="label">${icon('lock')}Held in escrow</p>
        <p class="big" data-count="${L.held}">${L.held}</p>
        <p class="hint">for sessions you booked and have not confirmed yet</p>
      </div>
      <div class="card">
        <p class="label">${icon('up')}Earned by teaching</p>
        <p class="big" data-count="${L.earned}">${L.earned}</p>
        <p class="hint">released to you by your learners</p>
      </div>
    </div>
    <div class="section-head"><h2>How a swap is paid</h2></div>
    <div class="card">
      <ol class="flow">
        <li><b>Book</b><span>The learner's credits are held. They leave the balance but are not paid yet.</span></li>
        <li><b>Confirm</b><span>When the learner confirms the session took place, the held credits go to the tutor.</span></li>
        <li><b>Or cancel</b><span>If the booking is cancelled instead, the held credits go back to the learner.</span></li>
      </ol>
    </div>
    <div class="section-head"><h2>History</h2><span class="count">${L.history.length} ${L.history.length === 1 ? 'entry' : 'entries'}</span></div>
    <div class="card ledger" data-stagger>${L.history.map(txRow).join('')}</div>`;
}

function txRow(t) {
  const skill = t.skill ? esc(t.skill) : 'a session';
  const booking = t.bookingId ? `Booking <span class="mono">${esc(t.bookingId)}</span>${t.with ? ` · with ${esc(t.with)}` : ''}` : '';
  const [title, detail, iconName, cls] = {
    INITIAL: ['Starting balance', 'Credits every new profile begins with', 'wallet', 'learning'],
    HOLD: [`Held for ${skill}`, booking, 'lock', 'hold'],
    RELEASE: [`Earned teaching ${skill}`, booking, 'up', 'release'],
    REFUND: [`Refunded for ${skill}`, booking, 'undo', 'refund'],
  }[t.type] || [esc(t.type), booking, 'info', 'learning'];
  return `
    <div class="tx">
      <span class="tx-icon b-${cls}">${icon(iconName)}</span>
      <span><b>${title}</b><span class="muted small">${detail}</span></span>
      <span class="subtle small when nowrap">${stamp(t.time)}</span>
      <span class="amt ${t.amount > 0 ? 'pos' : ''}">${t.amount > 0 ? '+' : t.amount < 0 ? '−' : ''}${Math.abs(t.amount)}</span>
    </div>`;
}

// ---------- How it works ----------

const CONCEPTS = [
  ['Inheritance', 'Three listing types', 'Paid, credit swap and free listings all extend one abstract Listing class.'],
  ['Polymorphism', 'One booking path', 'Booking asks any listing for its credit cost. Only a swap returns more than zero.'],
  ['Collections', 'Fast lookups', 'LinkedHashMap stores everything by id, and a Comparator sorts slots by time.'],
  ['Exceptions', 'Clear failures', 'Broken rules throw checked exceptions such as InsufficientCreditsException.'],
  ['Serialization', 'Nothing is lost', 'The whole service is saved to disk after every change and loaded on start.'],
  ['Synchronization', 'No double booking', 'Checking a slot and booking it happen as one atomic step.'],
];

function howView() {
  return head('How it works',
    'A Core Java backend on the JDK\'s built-in HTTP server, with no frameworks. These are the main ideas behind it, and you can run the concurrency test live.') + `
    <div class="grid concepts" data-stagger>
      ${CONCEPTS.map(([k, t, d]) => `<div class="card concept spot"><p class="eyebrow">${k}</p><h3>${t}</h3><p>${d}</p></div>`).join('')}
    </div>
    <div class="section-head"><h2>Concurrency test</h2></div>
    <section class="card race">
      <div class="row between wrap" style="gap:16px">
        <div>
          <h3 style="font-size:17px">Eight students. One slot. Same instant.</h3>
          <p class="muted" style="margin-top:4px">Runs on a fresh in-memory copy, so your real data is never touched.</p>
        </div>
        <button class="btn primary" data-action="race">${icon('play')}Run the race</button>
      </div>
      <div id="race-out" aria-live="polite"></div>
    </section>`;
}

function renderRace(r) {
  // One lane per thread. The winner's dot arrives first; how far behind the others are is only for show.
  const track = (l, i) => `
    <div class="track ${l.booked ? 'win' : 'lose'}" style="--d:${l.booked ? 800 : 1000 + (i * 137) % 700}ms">
      <span class="who"><b>${esc(l.student)}</b><small>${esc(l.thread)} → ${esc(l.slot)}</small></span>
      <span class="lane-line"><i class="runner"></i></span>
      <span class="outcome">${l.booked ? 'Booked ' + esc(l.detail) : esc(l.detail)}</span>
    </div>`;
  const won = r.safe.filter((l) => l.booked).length;
  const out = $('#race-out');
  out.innerHTML = `
    <div class="race-grid">
      <div class="tile bad"><p class="label">${icon('alert')}Without synchronization</p><p class="big"><b data-count="${r.unsafe.sold}">${r.unsafe.sold}</b><span>/${r.threads}</span></p><p>threads booked the same slot. That is the bug.</p></div>
      <div class="tile good"><p class="label">${icon('check')}With synchronized</p><p class="big"><b data-count="${won}">${won}</b><span>/${r.threads}</span></p><p>${won === 1 ? 'booking' : 'bookings'} went through. The rest were rejected.</p></div>
      <div class="tile"><p class="label">Stress test</p><p class="big"><b data-count="${r.stress.passed}">${r.stress.passed}</b><span>/${r.stress.rounds}</span></p><p class="muted">rounds ended with exactly one winner.</p></div>
    </div>
    <h3>Same slot, ${r.threads} threads</h3>
    <div class="tracks">${r.safe.map(track).join('')}</div>
    <h3>One learner with ${plural(r.credits.start, 'credit')} books ${r.threads} slots costing ${r.credits.cost} each</h3>
    <div class="tracks">${r.credits.lanes.map(track).join('')}</div>
    <p class="muted">Balance after the race: <b>${plural(r.credits.balance, 'credit')}</b>. It never goes below zero.</p>
    <p class="subtle small">The moving dots are an illustration. The result shown in each lane is the real outcome from the server.</p>`;
  $$('[data-count]', out).forEach((el) => countUp(el, Number(el.dataset.count), 0));
}

// ---------- Search palette ----------

/** Everything the palette can jump to: pages, actions, skills with open sessions, and people. */
function allCommands() {
  const list = [];
  const add = (group, label, hint, iconName, run) => list.push({ group, label, hint, iconName, run });
  const dark = root.dataset.theme === 'dark';
  for (const [tab, page] of Object.entries(PAGES)) add('Pages', page.title, '', 'arrow', () => go(tab));
  add('Actions', me() ? 'Switch profile' : 'Sign in', '', 'user', openSignIn);
  add('Actions', 'Surprise me with a session', '', 'dice', surprise);
  add('Actions', dark ? 'Switch to light theme' : 'Switch to dark theme', '', dark ? 'sun' : 'moon', () => applyTheme(dark ? 'light' : 'dark'));
  for (const g of skillGroups()) {
    add('Skills', g.skill, plural(g.count, 'open session'), 'search', () => { state.q = g.skill; state.type = 'ALL'; go('browse'); });
  }
  for (const s of state.students) add('People', s.name, s.id, 'user', () => openTutor(s.id));
  return list;
}

function openPalette() {
  showModal(`
    <h2 id="modal-title" class="sr-only">Search</h2>
    <label class="search cmd-search">${icon('search')}<span class="sr-only">Search pages, skills, people and actions</span>
      <input class="input" id="cmd-q" placeholder="Search pages, skills, people and actions" autocomplete="off" autofocus></label>
    <div class="cmd-list" id="cmd-list"></div>
    <p class="hint cmd-hint"><kbd>↑</kbd><kbd>↓</kbd> to move <kbd>Enter</kbd> to open <kbd>Esc</kbd> to close</p>`);
  paintCommands();
  // The list of skills comes from the open slots, which another page may not have loaded yet.
  api('slots').then((slots) => { state.slots = slots; paintCommands(); }).catch(() => {});
}

function paintCommands() {
  const input = $('#cmd-q');
  const box = $('#cmd-list');
  if (!input || !box) return;
  const k = input.value.trim().toLowerCase();
  state.cmds = allCommands().filter((c) => !k || c.label.toLowerCase().includes(k) || c.group.toLowerCase().includes(k));
  state.cmdIndex = Math.min(state.cmdIndex, Math.max(0, state.cmds.length - 1));
  let group = '';
  box.innerHTML = state.cmds.length ? state.cmds.map((c, i) => {
    const heading = c.group !== group ? `<div class="cmd-group">${c.group}</div>` : '';
    group = c.group;
    return `${heading}<button class="cmd ${i === state.cmdIndex ? 'on' : ''}" data-action="cmd" data-i="${i}">
      ${icon(c.iconName)}<span>${esc(c.label)}</span><small>${esc(c.hint)}</small></button>`;
  }).join('') : '<p class="muted" style="padding:10px">Nothing matches that.</p>';
}

function runCommand(i) {
  const cmd = state.cmds[i];
  if (!cmd) return;
  closeModal();
  Promise.resolve(cmd.run()).catch(fail);
}

// ---------- Actions ----------

/** After a change made here: reload the balances and my bookings without announcing them, then redraw the page. */
async function refresh() {
  await refreshStudents();
  await syncMine(false);
  await render();
}

const actions = {
  signin: () => openSignIn(),
  close: () => closeModal(),
  retry: () => render(),
  palette: () => openPalette(),
  go: (el) => { closeModal(); go(el.dataset.tab); },
  theme: () => applyTheme(root.dataset.theme === 'dark' ? 'light' : 'dark'),
  cmd: (el) => runCommand(Number(el.dataset.i)),
  pick: (el) => openPassword(el.dataset.id),
  async signout() {
    await api('logout', {});
    state.user = null;
    state.me = null;
    state.mine = [];
    state.mineStatus = null;
    closeModal();
    renderAccount();
    render();
  },
  async 'remove-slot'(el) {
    await busy(el, async () => {
      try {
        await api(`slots/${el.dataset.id}/delete`, {});
        toast('Slot removed.');
      } finally {
        await render();
      }
    });
  },
  'delete-listing': (el) => openDeleteListing(el.dataset.id),
  async 'do-delete-listing'(el) {
    await busy(el, async () => {
      try {
        await api(`listings/${el.dataset.id}/delete`, {});
        toast(`Listing ${el.dataset.id} deleted.`);
      } finally {
        closeModal();
        await render();
      }
    });
  },
  filter(el) {
    state.type = el.dataset.type;
    paintSlots({ animate: true });
  },
  skill(el) {
    const skill = el.dataset.skill;
    state.q = state.q.trim().toLowerCase() === skill.toLowerCase() ? '' : skill;   // a second click clears it
    state.type = 'ALL';
    $('#q').value = state.q;
    paintRail();
    paintSlots({ animate: true });
    if (state.q) $('#open-title').scrollIntoView({ behavior: still() ? 'auto' : 'smooth', block: 'start' });
  },
  'clear-filters'() {
    state.type = 'ALL';
    state.q = '';
    $('#q').value = '';
    paintRail();
    paintSlots({ animate: true });
  },
  surprise: () => surprise(),
  tutor: (el) => openTutor(el.dataset.id),
  book: (el) => openBook(el.dataset.slot),
  async 'confirm-book'(el) {
    await busy(el, async () => {
      try {
        const b = await api('bookings', { slotId: el.dataset.slot });
        closeModal();
        toast(`Booked ${b.slot.skill} with ${b.tutor.name}. Booking ${b.id}.`);
        confetti();
      } finally {
        // Success or not, show the latest state: someone else may have just taken this slot.
        closeModal();
        await refresh();
      }
    });
  },
  'booking-filter'(el) {
    state.bookingFilter = el.dataset.status;
    $$('#status-chips .chip').forEach((c) => {
      const on = c.dataset.status === state.bookingFilter;
      c.classList.toggle('on', on);
      c.setAttribute('aria-pressed', on);
    });
    const list = $('#booking-list');
    list.innerHTML = bookingList();
    stagger(list, ':scope > *');
  },
  complete: (el) => openConfirm('complete', el.dataset.id),
  cancel: (el) => openConfirm('cancel', el.dataset.id),
  async 'do-complete'(el) {
    await busy(el, async () => {
      try {
        await api(`bookings/${el.dataset.id}/complete`, {});
        closeModal();
        toast(`Booking ${el.dataset.id} completed.`);
        confetti();
      } finally {
        closeModal();
        await refresh();
      }
    });
  },
  async 'do-cancel'(el) {
    await busy(el, async () => {
      try {
        await api(`bookings/${el.dataset.id}/cancel`, {});
        toast(`Booking ${el.dataset.id} cancelled. The slot is open again.`);
      } finally {
        closeModal();
        await refresh();
      }
    });
  },
  async race(el) {
    $('#race-out').innerHTML = '<div class="skeleton" style="height:120px"></div>';
    await busy(el, async () => {
      try {
        renderRace(await api('demo/race', {}));
      } catch (e) {
        $('#race-out').innerHTML = '';
        throw e;
      }
    });
  },
};

document.addEventListener('click', async (e) => {
  const el = e.target.closest('[data-action]');
  if (!el || el.disabled) return;
  e.preventDefault();
  try { await actions[el.dataset.action]?.(el); } catch (err) { fail(err); }
});

document.addEventListener('submit', async (e) => {
  e.preventDefault();
  const form = e.target;
  const btn = $('button:not([type="button"])', form);   // the submit button, not a "Back" beside it
  const data = Object.fromEntries(new FormData(form));
  try {
    await busy(btn, async () => {
      if (form.id === 'login') {
        try {
          const s = await api('login', data);
          await afterSignIn();
          toast(`Signed in as ${s.name}`);
        } catch (err) {
          if (err.type === 'Offline') throw err;
          // Wrong password, or too many tries: say so next to the field and let them try again.
          const box = $('#login-error');
          if (!box) throw err;
          box.textContent = err.message;
          box.hidden = false;
          form.elements.password.value = '';
          form.elements.password.focus();
        }
      } else if (form.id === 'register') {
        const s = await api('students', data);   // the server signs the new student in
        await afterSignIn();
        toast(`Welcome, ${s.name}! You start with ${plural(s.credits, 'credit')}.`);
        confetti();
      } else if (form.id === 'new-listing') {
        const l = await api('listings', data);
        toast(`Listing ${l.id} created. Now add a time slot so students can book it.`);
        await render();
      } else if (form.classList.contains('add-slot')) {
        await api('slots', { listingId: form.dataset.listing, start: data.start });
        toast('Slot added. Students can book it now.');
        await render();
      }
    });
  } catch (err) { fail(err); }
});

document.addEventListener('change', (e) => {
  if (e.target.name === 'type' && e.target.closest('#new-listing')) updatePriceField();
});

document.addEventListener('input', (e) => {
  if (e.target.id === 'cmd-q') {
    state.cmdIndex = 0;
    paintCommands();
  }
});

document.addEventListener('keydown', (e) => {
  // Ctrl+K or Cmd+K opens the search palette from anywhere; "/" does too, unless you are typing.
  const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName);
  if ((e.key === 'k' && (e.metaKey || e.ctrlKey)) || (e.key === '/' && !typing && !modal.open)) {
    e.preventDefault();
    return modal.open && $('#cmd-q') ? closeModal() : openPalette();
  }
  if (!$('#cmd-q') || !modal.open) return;
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    const n = state.cmds.length;
    if (!n) return;
    state.cmdIndex = (state.cmdIndex + (e.key === 'ArrowDown' ? 1 : n - 1)) % n;
    $$('.cmd').forEach((c, i) => c.classList.toggle('on', i === state.cmdIndex));
    $$('.cmd')[state.cmdIndex].scrollIntoView({ block: 'nearest' });
  } else if (e.key === 'Enter' && e.target.id === 'cmd-q') {
    e.preventDefault();
    runCommand(state.cmdIndex);
  }
});

modal.addEventListener('click', (e) => { if (e.target === modal) closeModal(); });
// The close event arrives a moment after close(). If another dialog has opened by then
// (search palette -> "Surprise me" -> sign in), the booking it is waiting to resume must be kept.
modal.addEventListener('close', () => { if (!modal.open) state.pendingSlot = null; });

window.addEventListener('hashchange', () => {
  render();
  window.scrollTo({ top: 0 });
});

// ---------- Start ----------

(async function init() {
  const mac = /Mac|iPhone|iPad/.test(navigator.platform);
  $('.modal-close').innerHTML = icon('x');
  $('#search-btn').innerHTML = `${icon('search')}<span>Search</span><kbd>${mac ? '⌘K' : 'Ctrl K'}</kbd>`;
  paintThemeToggle();
  try {
    await refreshStudents();
    await syncMine(false);
  } catch (e) {
    fail(e);
  }
  render();
  setInterval(everySecond, 1000);
  setInterval(tick, POLL_MS);
})();
