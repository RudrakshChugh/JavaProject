'use strict';
/* Skill Exchange front end. Plain JavaScript talking to the Java API in skillexchange.web.WebServer. */

const $ = (sel, el = document) => el.querySelector(sel);
const view = $('#view');
const modal = $('#modal');

const state = { me: null, students: [], slots: [], tab: 'browse', type: 'ALL', q: '' };
const TYPE_LABEL = { PAID: 'Paid', SWAP: 'Credit swap', FREE: 'Free' };
const STARTING_CREDITS = 5;

// ---------- Helpers ----------

async function api(path, data) {
  const opts = data ? { method: 'POST', body: new URLSearchParams(data) } : {};
  const res = await fetch('/api/' + path, opts);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(body.message || 'Request failed');
    err.type = body.type || 'Error';
    throw err;
  }
  return body;
}

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function remember(id) {
  try { id ? localStorage.setItem('skx.me', id) : localStorage.removeItem('skx.me'); } catch { /* storage off */ }
}
function recall() {
  try { return localStorage.getItem('skx.me'); } catch { return null; }
}

const me = () => state.students.find((s) => s.id === state.me) || null;
const tag = (type) => `<span class="tag t-${type.toLowerCase()}">${TYPE_LABEL[type] || type}</span>`;
const pill = (text, cls) => `<span class="tag t-${cls}">${esc(text)}</span>`;
const hhmm = (iso) => iso.slice(11, 16);
function when(iso) {
  const d = new Date(iso);
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' }) + ', ' + hhmm(iso);
}
function nowLocal() {
  const d = new Date();
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
}
function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}
const head = (title, sub) => `<div class="page-head"><h1>${title}</h1>${sub ? `<p>${sub}</p>` : ''}</div>`;
const empty = (text, action = '') => `<div class="empty"><p>${text}</p>${action}</div>`;

function toast(message, kind = 'ok', type = '') {
  const t = document.createElement('div');
  t.className = `toast ${kind}`;
  t.innerHTML = (type ? `<span>${pill(type, 'err')}</span>` : '') + `<p>${esc(message)}</p>`;
  $('#toasts').append(t);
  setTimeout(() => t.remove(), kind === 'err' ? 6000 : 3500);
}
const fail = (e) => toast(e.message, 'err', e.type);

function showModal(html) {
  $('#modal-body').innerHTML = html;
  if (!modal.open) modal.showModal();
  const first = $('#modal-body input, #modal-body button');
  if (first) first.focus();
}
const closeModal = () => modal.open && modal.close();

/** Disables a button while its request runs, so a double click cannot send it twice. */
async function busy(btn, work) {
  if (btn) { btn.disabled = true; btn.classList.add('busy'); }
  try { return await work(); } finally { if (btn) { btn.disabled = false; btn.classList.remove('busy'); } }
}

// ---------- Account ----------

async function refreshStudents() {
  state.students = await api('students');
  if (state.me && !me()) { state.me = null; remember(null); }
  renderAccount();
}

function renderAccount() {
  const m = me();
  $('#account').innerHTML = m
    ? `<span class="credits-pill" title="Credit balance">${m.credits} cr</span>
       <span class="who-name">${esc(m.name)}</span>
       <button class="btn sm" data-action="signin">Switch</button>`
    : `<button class="btn primary sm" data-action="signin">Sign in</button>`;
}

function openSignIn() {
  showModal(`
    <h2 id="modal-title">Sign in</h2>
    <p class="muted">Pick your profile. This demo has no passwords.</p>
    <div class="people">
      ${state.students.map((s) => `
        <button class="person ${s.id === state.me ? 'on' : ''}" data-action="pick" data-id="${s.id}">
          <b>${esc(s.name)}</b><span class="mono">${s.id} · ${s.credits} credits</span>
        </button>`).join('')}
    </div>
    <hr class="divider">
    <form id="register" class="form">
      <h3>New here?</h3>
      <div class="two">
        <label>Name<input class="input" name="name" required maxlength="40" autocomplete="name"></label>
        <label><span>Email <span class="muted">(optional)</span></span><input class="input" name="email" type="email" maxlength="60" autocomplete="email"></label>
      </div>
      <button class="btn dark block">Create profile and get ${STARTING_CREDITS} credits</button>
    </form>
    ${state.me ? '<button class="btn block" data-action="signout">Sign out</button>' : ''}`);
}

function signedOut(what) {
  view.innerHTML = head(what) + empty(`Sign in to see ${what.toLowerCase()}.`,
    '<button class="btn primary" data-action="signin">Sign in</button>');
}

// ---------- Router ----------

async function render() {
  document.querySelectorAll('.tab').forEach((t) => {
    if (t.dataset.tab === state.tab) t.setAttribute('aria-current', 'page');
    else t.removeAttribute('aria-current');
  });
  try {
    if (state.tab === 'browse') return await renderBrowse();
    if (state.tab === 'how') return renderHow();
    const titles = { bookings: 'My bookings', teach: 'Teach', credits: 'Credits' };
    if (!me()) return signedOut(titles[state.tab]);
    if (state.tab === 'bookings') return await renderBookings();
    if (state.tab === 'teach') return await renderTeach();
    if (state.tab === 'credits') return await renderCredits();
  } catch (e) {
    view.innerHTML = empty('Could not reach the server. Is the Java app running?');
    fail(e);
  }
}

function go(tab) {
  state.tab = tab;
  render();
  window.scrollTo({ top: 0 });
}

// ---------- Browse ----------

async function renderBrowse() {
  const s = await api('stats');
  const stat = (n, label) => `<div class="stat"><b>${n}</b><span>${label}</span></div>`;
  view.innerHTML = `
    <section class="hero">
      <div>
        <p class="eyebrow">Peer tutoring for students, by students</p>
        <h1>Teach what you know.<br><mark>Learn</mark> what you don't.</h1>
        <p class="lede">List a skill, publish the times you are free, and book sessions with each other.
          Pay a fee, spend credits you earned by teaching, or keep it free.</p>
      </div>
      <div class="stats">
        ${stat(s.students, 'Students')}${stat(s.listings, 'Skills listed')}
        ${stat(s.openSlots, 'Open slots')}${stat(s.completed, 'Sessions done')}
      </div>
    </section>
    <section aria-label="Open slots">
      <div class="toolbar">
        <input class="input search" id="q" type="search" placeholder="Search a skill or tutor" value="${esc(state.q)}" aria-label="Search">
        <div class="chips" role="group" aria-label="Filter by type">
          ${['ALL', 'PAID', 'SWAP', 'FREE'].map((t) => `
            <button class="chip ${state.type === t ? 'on' : ''}" data-action="filter" data-type="${t}" aria-pressed="${state.type === t}">
              ${t === 'ALL' ? 'All' : TYPE_LABEL[t]}</button>`).join('')}
        </div>
      </div>
      <div id="slots" class="grid"><p class="loading">Loading slots...</p></div>
    </section>`;
  $('#q').addEventListener('input', debounce((e) => { state.q = e.target.value; loadSlots(); }, 200));
  await loadSlots();
}

async function loadSlots() {
  state.slots = await api(`slots?q=${encodeURIComponent(state.q)}&type=${state.type}`);
  const grid = $('#slots');
  if (!grid) return;
  grid.innerHTML = state.slots.length
    ? state.slots.map(slotCard).join('')
    : `<div style="grid-column:1/-1">${empty('No open slots match. Try another skill, or add your own in Teach.')}</div>`;
}

function slotCard(s) {
  const mine = state.me === s.tutor.id;
  return `
    <article class="card slot">
      <div class="row between">${tag(s.type)}<span class="price">${esc(s.price)}</span></div>
      <h3>${esc(s.skill)}</h3>
      <p class="desc">${esc(s.description || 'No description yet.')}</p>
      <dl class="meta">
        <div><dt>Tutor</dt><dd>${esc(s.tutor.name)}</dd></div>
        <div><dt>When</dt><dd>${when(s.start)}</dd></div>
        <div><dt>Length</dt><dd>${s.minutes} min</dd></div>
      </dl>
      ${mine
        ? '<button class="btn block" disabled>Your own slot</button>'
        : `<button class="btn primary block" data-action="book" data-slot="${s.id}">Book this slot</button>`}
    </article>`;
}

function openBook(slotId) {
  const m = me();
  if (!m) return openSignIn();
  const s = state.slots.find((x) => x.id === slotId);
  if (!s) return;
  const note = {
    SWAP: `<b>${s.creditCost} credits</b> are held when you book. ${esc(s.tutor.name)} receives them only after you
           confirm the session took place. If it is cancelled, they come back to you. You have <b>${m.credits}</b>.`,
    PAID: `You pay the fee to ${esc(s.tutor.name)} at the session. Nothing is charged here.`,
    FREE: `This is a free session. ${esc(s.tutor.name)} is volunteering their time.`,
  }[s.type];
  showModal(`
    <div class="row">${tag(s.type)}<span class="price">${esc(s.price)}</span></div>
    <h2 id="modal-title">Book ${esc(s.skill)}</h2>
    <dl class="meta">
      <div><dt>Tutor</dt><dd>${esc(s.tutor.name)}</dd></div>
      <div><dt>When</dt><dd>${when(s.start)}</dd></div>
      <div><dt>Length</dt><dd>${s.minutes} min</dd></div>
    </dl>
    <p class="note">${note}</p>
    <div class="actions">
      <button class="btn" data-action="close">Not now</button>
      <button class="btn primary" data-action="confirm-book" data-slot="${s.id}">Confirm booking</button>
    </div>`);
}

// ---------- My bookings ----------

async function renderBookings() {
  const list = await api(`students/${state.me}/bookings`);
  view.innerHTML = head('My bookings', 'Sessions you are learning in and sessions you are teaching.') + (list.length
    ? `<div class="stack">${list.map(bookingRow).join('')}</div>`
    : empty('No bookings yet.', '<button class="btn primary" data-action="go" data-tab="browse">Browse open slots</button>'));
}

function bookingRow(b) {
  const learning = b.role === 'LEARNING';
  const other = learning ? `with ${esc(b.tutor.name)}` : `for ${esc(b.learner.name)}`;
  const live = b.status === 'CONFIRMED';
  return `
    <article class="card brow">
      <div>
        <div class="row wrap">${pill(b.role, b.role.toLowerCase())} ${pill(b.status, b.status.toLowerCase())} ${tag(b.slot.type)}</div>
        <h3>${esc(b.slot.skill)} <span class="muted">${other}</span></h3>
        <p class="muted mono small">${b.id} · ${when(b.slot.start)} · ${esc(b.slot.price)}</p>
      </div>
      <div class="brow-actions">
        ${live && learning ? `<button class="btn primary sm" data-action="complete" data-id="${b.id}">It happened</button>` : ''}
        ${live ? `<button class="btn sm" data-action="cancel" data-id="${b.id}">Cancel</button>` : ''}
      </div>
    </article>`;
}

// ---------- Teach ----------

async function renderTeach() {
  const listings = await api(`students/${state.me}/listings`);
  view.innerHTML = head('Teach', 'Create a listing for something you can teach, then add the times you are free.') + `
    <div class="split">
      <form class="card form" id="new-listing">
        <h2>New listing</h2>
        <fieldset class="seg">
          <legend>Listing type</legend>
          ${['SWAP', 'PAID', 'FREE'].map((t, i) => `
            <input type="radio" name="type" id="type-${t}" value="${t}" ${i === 0 ? 'checked' : ''}>
            <label for="type-${t}">${TYPE_LABEL[t]}</label>`).join('')}
        </fieldset>
        <label>Skill<input class="input" name="skill" required maxlength="60" placeholder="e.g. Python for beginners"></label>
        <label>Short description<input class="input" name="description" maxlength="120" placeholder="What will you cover?"></label>
        <div class="two">
          <label>Length (minutes)<input class="input" name="minutes" type="number" min="15" max="240" step="5" value="60" required></label>
          <label id="price-field"></label>
        </div>
        <button class="btn primary block">Create listing</button>
      </form>
      <div class="stack">
        ${listings.length ? listings.map(listingCard).join('') : empty('You are not teaching anything yet. Create your first listing.')}
      </div>
    </div>`;
  updatePriceField();
}

function updatePriceField() {
  const form = $('#new-listing');
  const field = $('#price-field');
  if (!form || !field) return;
  const type = form.elements.type.value;
  field.hidden = type === 'FREE';
  field.innerHTML = type === 'PAID'
    ? 'Fee (Rs.)<input class="input" name="fee" type="number" min="1" step="10" value="200" required>'
    : type === 'SWAP'
      ? 'Credits per session<input class="input" name="credits" type="number" min="1" max="10" value="2" required>'
      : '';
}

function listingCard(l) {
  return `
    <article class="card">
      <div class="row between">${tag(l.type)}<span class="price">${esc(l.price)}</span></div>
      <h3 style="margin-top:10px">${esc(l.skill)} <span class="muted mono small">${l.id} · ${l.minutes} min</span></h3>
      ${l.description ? `<p class="desc" style="min-height:0">${esc(l.description)}</p>` : ''}
      <ul class="slots-list">
        ${l.slots.length
          ? l.slots.map((s) => `<li><span>${when(s.start)}</span>${pill(s.booked ? 'Booked' : 'Open', s.booked ? 'booked' : 'open')}</li>`).join('')
          : '<li class="muted">No time slots yet</li>'}
      </ul>
      <form class="add-slot" data-listing="${l.id}">
        <input class="input" type="datetime-local" name="start" required min="${nowLocal()}" aria-label="New slot start time">
        <button class="btn sm dark">Add slot</button>
      </form>
    </article>`;
}

// ---------- Credits ----------

async function renderCredits() {
  const L = await api(`students/${state.me}/ledger`);
  view.innerHTML = head('Credits', 'Earn credits by teaching a swap session. Spend them to learn something new.') + `
    <div class="split">
      <div class="card balance">
        <p class="eyebrow">Balance</p>
        <p class="big">${L.balance}</p>
        <p>credits available</p>
      </div>
      <div class="card">
        <h2>How a swap is paid</h2>
        <ol class="flow">
          <li><b>Book</b><span>Your credits are held, not paid yet.</span></li>
          <li><b>Confirm</b><span>Held credits go to the tutor.</span></li>
          <li><b>Cancel</b><span>Held credits come back to you.</span></li>
        </ol>
      </div>
    </div>
    <h2 class="section-title">History</h2>
    <div class="card ledger">${L.history.map(txRow).join('')}</div>`;
}

function txRow(t) {
  const label = t.bookingId ? `Booking ${esc(t.bookingId)}` : 'Starting balance';
  return `
    <div class="tx">
      <span>${pill(t.type, t.type.toLowerCase())}</span>
      <span>${label}</span>
      <span class="muted mono small when">${esc(t.time.replace('T', ' ').slice(0, 16))}</span>
      <b class="amt ${t.amount < 0 ? 'neg' : ''}">${t.amount > 0 ? '+' : ''}${t.amount}</b>
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

function renderHow() {
  view.innerHTML = head('How it works',
    'A Core Java backend on the JDK\'s built-in HTTP server, with no frameworks. This page shows the main ideas, and lets you run the concurrency test live.') + `
    <div class="grid concepts">
      ${CONCEPTS.map(([k, t, d]) => `<div class="card concept"><p class="eyebrow">${k}</p><h3>${t}</h3><p>${d}</p></div>`).join('')}
    </div>
    <section class="card race">
      <div class="row between wrap" style="gap:16px">
        <div>
          <h2>Eight students. One slot. Same instant.</h2>
          <p class="muted" style="margin-top:6px">Runs on a fresh in-memory copy, so your real data is never touched.</p>
        </div>
        <button class="btn primary" data-action="race">Run the race</button>
      </div>
      <div id="race-out"></div>
    </section>`;
}

function renderRace(r) {
  const lane = (l) => `
    <div class="lane ${l.booked ? 'win' : 'lose'}">
      <span class="mono small">${esc(l.thread)} → ${esc(l.slot)}</span>
      <b>${esc(l.student)}</b>
      <span class="mono small">${l.booked ? 'BOOKED ' + esc(l.detail) : esc(l.detail)}</span>
    </div>`;
  const won = r.safe.filter((l) => l.booked).length;
  $('#race-out').innerHTML = `
    <div class="race-grid">
      <div class="card bad"><p class="eyebrow">Without synchronization</p><p class="big">${r.unsafe.sold}<span>/${r.threads}</span></p><p>threads booked the same slot. That is the bug.</p></div>
      <div class="card good"><p class="eyebrow">With synchronized</p><p class="big">${won}<span>/${r.threads}</span></p><p>booking went through. The rest were rejected.</p></div>
      <div class="card"><p class="eyebrow">Stress test</p><p class="big">${r.stress.passed}<span>/${r.stress.rounds}</span></p><p>rounds ended with exactly one winner.</p></div>
    </div>
    <h3 style="margin-top:8px">Same slot, ${r.threads} threads</h3>
    <div class="lanes">${r.safe.map(lane).join('')}</div>
    <h3>One learner with ${r.credits.start} credits books ${r.threads} slots costing ${r.credits.cost} each</h3>
    <div class="lanes">${r.credits.lanes.map(lane).join('')}</div>
    <p class="muted">Balance after the race: <b>${r.credits.balance}</b> credit. It never goes below zero.</p>`;
}

// ---------- Actions ----------

const actions = {
  signin: () => openSignIn(),
  close: () => closeModal(),
  go: (el) => go(el.dataset.tab),
  async pick(el) {
    state.me = el.dataset.id;
    remember(state.me);
    closeModal();
    renderAccount();
    toast(`Signed in as ${me().name}`);
    render();
  },
  signout() {
    state.me = null;
    remember(null);
    closeModal();
    renderAccount();
    render();
  },
  filter(el) {
    state.type = el.dataset.type;
    document.querySelectorAll('.chip').forEach((c) => {
      const on = c.dataset.type === state.type;
      c.classList.toggle('on', on);
      c.setAttribute('aria-pressed', on);
    });
    loadSlots().catch(fail);
  },
  book: (el) => openBook(el.dataset.slot),
  async 'confirm-book'(el) {
    await busy(el, async () => {
      try {
        const b = await api('bookings', { studentId: state.me, slotId: el.dataset.slot });
        toast(`Booked ${b.slot.skill} with ${b.tutor.name}. Booking ${b.id}.`);
      } finally {
        // Success or not, show the latest state: someone else may have just taken this slot.
        closeModal();
        await refreshStudents();
        render();
      }
    });
  },
  async complete(el) {
    await busy(el, async () => {
      await api(`bookings/${el.dataset.id}/complete`, { studentId: state.me });
      toast(`Session ${el.dataset.id} confirmed. Held credits went to the tutor.`);
      await refreshStudents();
      render();
    });
  },
  async cancel(el) {
    await busy(el, async () => {
      await api(`bookings/${el.dataset.id}/cancel`, { studentId: state.me });
      toast(`Booking ${el.dataset.id} cancelled. The slot is open again.`);
      await refreshStudents();
      render();
    });
  },
  async race(el) {
    $('#race-out').innerHTML = '<p class="loading">Racing 8 threads, 200 rounds...</p>';
    await busy(el, async () => renderRace(await api('demo/race', {})));
  },
};

document.addEventListener('click', async (e) => {
  const tab = e.target.closest('.tab');
  if (tab) return go(tab.dataset.tab);
  const el = e.target.closest('[data-action]');
  if (!el) return;
  e.preventDefault();
  try { await actions[el.dataset.action]?.(el); } catch (err) { fail(err); }
});

document.addEventListener('submit', async (e) => {
  e.preventDefault();
  const form = e.target;
  const btn = form.querySelector('button');
  const data = Object.fromEntries(new FormData(form));
  try {
    await busy(btn, async () => {
      if (form.id === 'register') {
        const s = await api('students', data);
        state.me = s.id;
        remember(s.id);
        closeModal();
        await refreshStudents();
        toast(`Welcome, ${s.name}! You start with ${s.credits} credits.`);
        render();
      } else if (form.id === 'new-listing') {
        const l = await api('listings', { ...data, studentId: state.me });
        toast(`Listing ${l.id} created. Now add a time slot.`);
        render();
      } else if (form.classList.contains('add-slot')) {
        await api('slots', { studentId: state.me, listingId: form.dataset.listing, start: data.start });
        toast('Slot added. Students can book it now.');
        render();
      }
    });
  } catch (err) { fail(err); }
});

document.addEventListener('change', (e) => {
  if (e.target.name === 'type' && e.target.closest('#new-listing')) updatePriceField();
});

modal.addEventListener('click', (e) => { if (e.target === modal) closeModal(); });

// ---------- Start ----------

(async function init() {
  state.me = recall();
  try {
    await refreshStudents();
  } catch (e) {
    fail(e);
  }
  render();
})();
