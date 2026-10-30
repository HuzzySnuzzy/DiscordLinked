'use strict';

const state = { me: null, members: null, logsBefore: null, logsLoading: false, player: null, playerLogsBefore: null };

const EVENT_LABELS = {
  LINK: ['Linked', 'good'],
  UNLINK: ['Unlinked', 'bad'],
  SUSPEND: ['Suspended', 'bad'],
  SUSPEND_EDIT: ['Suspension edited', 'warn'],
  UNSUSPEND: ['Unsuspended', 'good'],
  KICK: ['Kicked', 'warn'],
  NOTE: ['Note', 'info'],
  JOIN: ['Join', 'info'],
  LEAVE: ['Leave', ''],
  SERVER_SWITCH: ['Server switch', ''],
  CHAT: ['Chat', ''],
  COMMAND: ['Command', ''],
  DENIED_UNLINKED: ['Denied: not linked', 'warn'],
  DENIED_SUSPENDED: ['Denied: suspended', 'warn'],
  WEB_LOGIN: ['Website login', 'info'],
  LOG_EXPORT: ['Log export', 'info'],
  INVENTORY_VIEW: ['Inventory viewed', ''],
  INVENTORY_SNAPSHOT: ['Snapshot saved', 'info'],
  INVENTORY_RESTORE: ['Inventory restored', 'warn'],
  SETTINGS_CHANGE: ['Settings changed', 'info'],
  DUTY: ['Duty', 'info'],
  DEATH: ['Death', 'bad'],
  SPY_START: ['Spy started', 'info'],
  SPY_END: ['Spy ended', ''],
  INTERACTION: ['Interaction', ''],
  ADVANCEMENT: ['Advancement', 'good'],
  CREDITS_ADJUST: ['Credits changed', 'warn'],
  SHOP_PURCHASE: ['Shop', 'warn'],
  TELEPORT: ['Teleport', 'info'],
  SALE: ['Sale', 'good'],
  EXCHANGE: ['Exchange', 'info'],
  COSMETIC: ['Cosmetic', 'warn'],
  GROUP: ['Group', 'info'],
};

// ---- Helpers ----

const $ = (id) => document.getElementById(id);

/** Marks the tab for the page you're on (aria-current needs a value, so never toggleAttribute). */
function markCurrent(a, on) {
  a.classList.toggle('active', on);
  if (on) a.setAttribute('aria-current', 'page');
  else a.removeAttribute('aria-current');
}

/** Builds an element. Strings become text nodes, so nothing is ever parsed as HTML. */
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

/** A heading's text with a muted "(N)" after it, in one span so flex headings keep them together. */
const withCount = (text, n) => el('span', {}, text, ' ', el('span', { class: 'count' }, `(${n.toLocaleString()})`));

/** An empty section's message, in a card like the sections that have content. */
const emptyCard = (text) => el('div', { class: 'card panel' }, el('p', { class: 'muted' }, text));

async function api(path, body) {
  const options = { credentials: 'same-origin', headers: {} };
  if (body !== undefined) {
    options.method = 'POST';
    options.headers['Content-Type'] = 'application/json';
    options.headers['X-CSRF-Token'] = state.me.csrf;
    options.body = JSON.stringify(body);
  }
  const res = await fetch(path, options);
  if (res.status === 401) {
    location.href = '/';
    throw new Error('Your session ended, please log in again.');
  }
  if (res.status === 423) {
    pollSpy();
    throw new Error('Finish your spy session first.');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
  return data;
}

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const formatTime = (ms) => dateFormat.format(new Date(ms));
const shortDateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });
const shortDate = (ms) => shortDateFormat.format(new Date(ms));

function formatRemaining(ms) {
  const minutes = Math.max(1, Math.ceil((ms - Date.now()) / 60000));
  const d = Math.floor(minutes / 1440), h = Math.floor(minutes / 60) % 24, m = minutes % 60;
  if (d) return h ? `${d}d ${h}h` : `${d}d`;
  if (h) return m ? `${h}h ${m}m` : `${h}h`;
  return `${m}m`;
}

/** "just now", "5 minutes ago", "1 day ago", "3 months ago", "2 years ago". */
function timeAgo(ms) {
  const seconds = Math.max(0, Math.floor((Date.now() - ms) / 1000));
  const units = [['year', 365 * 86400], ['month', 30 * 86400], ['day', 86400], ['hour', 3600], ['minute', 60]];
  for (const [unit, size] of units) {
    const n = Math.floor(seconds / size);
    if (n >= 1) return `${n} ${unit}${n === 1 ? '' : 's'} ago`;
  }
  return 'just now';
}

let toastTimer;
function toast(message, link) {
  const node = $('toast');
  node.replaceChildren(message, link ? el('a', { href: link.href, target: '_blank', rel: 'noopener' }, link.text) : '');
  node.hidden = false;
  clearTimeout(toastTimer);
  node.classList.remove('leaving');
  toastTimer = setTimeout(() => {
    node.classList.add('leaving');
    toastTimer = setTimeout(() => { node.hidden = true; node.classList.remove('leaving'); }, reducedMotion() ? 0 : 220);
  }, link ? 12000 : 3500);
}

/** The clipboard API only works on https; the old textarea trick also works on plain http. */
function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
  const area = el('textarea', { readonly: '', class: 'offscreen' });
  area.value = text;
  document.body.append(area);
  area.select();
  const ok = document.execCommand('copy');
  area.remove();
  return ok ? Promise.resolve() : Promise.reject(new Error('copy failed'));
}

function copyButton(text, what) {
  return el('button', {
    class: 'copy mono', type: 'button', title: `Copy ${what}: ${text}`,
    onclick: (e) => {
      e.preventDefault();
      e.stopPropagation();
      copyText(text).then(() => toast(`${what} copied`), () => toast('Couldn’t copy — select the text instead'));
    },
  }, text);
}

const discordHref = (id) => `https://discord.com/users/${id}`;
const discordLink = (p, ...children) =>
  el('a', { class: 'discord-link', href: discordHref(p.discordId), target: '_blank', rel: 'noopener', title: 'Open Discord profile' }, ...children);
const discordName = (p) => discordLink(p, el('span', { class: `name ${p.isAdmin ? 'name-admin' : ''}` }, p.discordName));
/** The avatar beside a Discord name: the same link again, so it's left out of the tab order and screen readers. */
const discordAvatarLink = (p) => {
  const a = discordLink(p, el('img', { class: 'avatar', src: p.discordAvatar, alt: '', loading: 'lazy' }));
  a.tabIndex = -1;
  a.setAttribute('aria-hidden', 'true');
  return a;
};

/** Blurred until hovered (or focused/tapped), so IPs don't show on a shared or streamed screen. */
const sensitive = (text) => el('span', { class: 'sensitive', tabindex: '0', title: 'Hover to show' }, text);
const IP_PATTERN = /\b\d{1,3}(?:\.\d{1,3}){3}\b|(?<![\w:])(?=[0-9a-f:]*::|(?:[0-9a-f]{1,4}:){3})[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}(?![\w:])/gi;
/** Text with every IP address in it blurred. */
function maskIps(text) {
  const parts = [];
  let last = 0;
  for (const match of text.matchAll(IP_PATTERN)) {
    parts.push(text.slice(last, match.index), sensitive(match[0]));
    last = match.index + match[0].length;
  }
  parts.push(text.slice(last));
  return parts.filter((part) => part !== '');
}

const isStaff = () => !!state.me?.staff;
const playerHref = (uuid) => `#/player/${uuid}`;
const headUrl = (uuid, size) => `https://mc-heads.net/avatar/${uuid}/${size}`;

/** Gold for admins, dark red for suspended players (suspended wins). */
function nameClass(p) {
  if (p.suspension) return 'name-suspended';
  if (p.isAdmin) return 'name-admin';
  return '';
}

function playerName(p, extraClass = '') {
  return el('a', { class: `name ${nameClass(p)} ${extraClass}`, href: playerHref(p.uuid) }, p.minecraftName);
}

function statusPills(p) {
  const pills = [];
  if (p.suspension) {
    pills.push(el('span', { class: 'pill suspended' }, `Suspended · ${formatRemaining(p.suspension.expiresAt)} left`));
  }
  if (p.discordOnly) {
    pills.push(el('span', { class: 'pill', title: `Uses the website without a linked Minecraft account. Last visit ${formatTime(p.lastSeen)}` },
      `Website only · ${timeAgo(p.lastSeen)}`));
  } else if (p.online) {
    pills.push(el('span', { class: 'pill online' }, p.server ? `Online · ${p.server}` : 'Online'));
  } else if (!p.suspension) {
    const seen = p.lastSeen ? `Offline · ${timeAgo(p.lastSeen)}` : 'Offline · never played';
    pills.push(el('span', { class: 'pill', title: p.lastSeen ? `Last seen ${formatTime(p.lastSeen)}` : null }, seen));
  }
  if (p.onWebsite) pills.push(el('span', { class: 'pill web', title: 'Has this website open right now' }, 'On the website'));
  return pills;
}

/** Minecraft's colours for &0-&f. */
const MC_COLORS = { 0: '#000000', 1: '#0000aa', 2: '#00aa00', 3: '#00aaaa', 4: '#aa0000', 5: '#aa00aa', 6: '#ffaa00',
  7: '#aaaaaa', 8: '#555555', 9: '#5555ff', a: '#55ff55', b: '#55ffff', c: '#ff5555', d: '#ff55ff', e: '#ffff55', f: '#ffffff' };

/** Text with Minecraft &-codes (&6, &#ff8800, &l bold...) shown in colour, like in game. */
function mcText(text, extraClass = '') {
  const root = el('span', { class: `mc-text ${extraClass}` });
  let color = null;
  let formats = new Set();
  let last = 0;
  const add = (part) => {
    if (!part) return;
    const span = el('span', { class: [...formats].map((f) => `mc-${f}`).join(' ') || null }, part);
    if (color) span.style.color = color;
    root.append(span);
  };
  for (const match of text.matchAll(/&(#[0-9a-f]{6}|[0-9a-fk-or])/gi)) {
    add(text.slice(last, match.index));
    const code = match[1].toLowerCase();
    if (code.startsWith('#') || MC_COLORS[code]) {
      color = code.startsWith('#') ? code : MC_COLORS[code];
      formats = new Set(); // like in game, a colour resets bold, italic...
    } else if (code === 'r') {
      color = null;
      formats = new Set();
    } else {
      formats.add(code);
    }
    last = match.index + match[0].length;
  }
  add(text.slice(last));
  return root;
}

// ---- Action dialog ----

let dialogHandler = null;

function openDialog({ title, text, confirmLabel, confirmClass, duration, amount, label, value, maxLength, checkbox, onConfirm }) {
  $('amount-fields').hidden = !amount;
  $('amount-label').textContent = amount ? amount.label : 'Amount';
  $('dialog-amount').value = '';
  $('dialog-check-row').hidden = !checkbox;
  $('dialog-check-label').textContent = checkbox || '';
  $('dialog-check').checked = false;
  $('dialog-title').textContent = title;
  $('dialog-text').textContent = text;
  $('duration-fields').hidden = !duration;
  $('dur-days').value = duration ? duration.days : 1;
  $('dur-hours').value = duration ? duration.hours : 0;
  $('dialog-label').textContent = label || 'Reason (required)';
  const reason = $('dialog-reason');
  reason.value = value || '';
  reason.maxLength = maxLength || 500;
  reason.rows = maxLength > 500 ? 5 : 3;
  $('dialog-error').hidden = true;
  const confirm = $('dialog-confirm');
  confirm.textContent = confirmLabel;
  confirm.className = `btn ${confirmClass || ''}`;
  confirm.disabled = false;
  dialogHandler = onConfirm;
  $('action-dialog').showModal();
  (amount ? $('dialog-amount') : reason).focus();
}

$('action-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const reason = $('dialog-reason').value.trim();
  const error = $('dialog-error');
  if (!reason) {
    error.textContent = 'Please fill this in.';
    error.hidden = false;
    return;
  }
  const days = parseInt($('dur-days').value, 10) || 0;
  const hours = parseInt($('dur-hours').value, 10) || 0;
  if (!$('duration-fields').hidden && days * 24 + hours < 1) {
    error.textContent = 'The suspension must be at least 1 hour.';
    error.hidden = false;
    return;
  }
  const amount = Number($('dialog-amount').value);
  if (!$('amount-fields').hidden && (!Number.isInteger(amount) || amount === 0)) {
    error.textContent = 'Enter a whole number other than 0. A negative number takes them away.';
    error.hidden = false;
    return;
  }
  const confirm = $('dialog-confirm');
  confirm.disabled = true;
  try {
    await dialogHandler({ reason, days, hours, amount, checked: $('dialog-check').checked });
    closeDialog();
    if (state.spy) pollSpy();
    else refreshCurrentView();
  } catch (e) {
    error.textContent = e.message;
    error.hidden = false;
    confirm.disabled = false;
  }
});
$('dialog-cancel').addEventListener('click', () => closeDialog());

/** Plays the closing animation, then closes. */
function closeDialog(dialog = $('action-dialog')) {
  if (!dialog.open || dialog.classList.contains('closing')) return;
  if (reducedMotion()) {
    dialog.close();
    return;
  }
  dialog.classList.add('closing');
  setTimeout(() => {
    dialog.classList.remove('closing');
    dialog.close();
  }, 180);
}

// ---- Confirm dialog: a yes/no question, e.g. before buying something ----

let confirmHandler = null;

function confirmDialog({ title, text, confirmLabel = 'OK', confirmClass = '', onConfirm }) {
  $('confirm-title').textContent = title;
  $('confirm-text').replaceChildren(...[text].flat().filter(Boolean).map((part) => (part instanceof Node ? part : el('p', {}, part))));
  $('confirm-error').hidden = true;
  const ok = $('confirm-ok');
  ok.textContent = confirmLabel;
  ok.className = `btn ${confirmClass}`;
  ok.disabled = false;
  confirmHandler = onConfirm;
  $('confirm-dialog').showModal();
  ok.focus();
}

$('confirm-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const ok = $('confirm-ok');
  ok.disabled = true;
  try {
    await confirmHandler();
    closeDialog($('confirm-dialog'));
  } catch (e) {
    $('confirm-error').textContent = e.message;
    $('confirm-error').hidden = false;
    ok.disabled = false;
  }
});
$('confirm-cancel').addEventListener('click', () => closeDialog($('confirm-dialog')));

// ---- Actions (shared by the Players list and the player page) ----

/** Compact (the Players list): every button is a quiet ghost one, so a column of filled buttons doesn't crowd each row. */
function actionButtons(p, { compact = false } = {}) {
  const small = (cls, label, handler) => el('button', {
    class: `btn ${cls} btn-small`, type: 'button', 'aria-label': `${label} ${p.minecraftName}`, onclick: handler,
  }, label);
  return el('div', { class: 'actions' },
    p.online && small('btn-ghost', 'Kick', () => kickDialog(p)),
    p.suspension
      ? [small('btn-ghost', 'Edit suspension', () => editSuspensionDialog(p)),
        small(compact ? 'btn-ghost' : 'btn-success', 'Unsuspend', () => unsuspendDialog(p))]
      : small(compact ? 'btn-ghost' : 'btn-warning', 'Suspend', () => suspendDialog(p)),
    small('btn-ghost', 'Note', () => noteDialog(p)),
    p.linked && small(compact ? 'btn-ghost danger' : 'btn-danger', 'Remove link', () => unlinkDialog(p)));
}

function suspendDialog(p) {
  openDialog({
    title: `Suspend ${p.minecraftName}`,
    text: 'They will be kicked if online, blocked from joining until it ends, and messaged on Discord.',
    confirmLabel: 'Suspend', confirmClass: 'btn-warning', duration: { days: 1, hours: 0 },
    onConfirm: async ({ reason, days, hours }) => {
      await api('/api/suspend', { uuid: p.uuid, reason, days, hours });
      toast(`${p.minecraftName} suspended`);
    },
  });
}

function editSuspensionDialog(p) {
  const hoursLeft = Math.max(1, Math.ceil((p.suspension.expiresAt - Date.now()) / 3600000));
  openDialog({
    title: `Edit suspension for ${p.minecraftName}`,
    text: `Currently ends ${formatTime(p.suspension.expiresAt)}. The new length is counted from now.`,
    confirmLabel: 'Save changes', confirmClass: 'btn-warning',
    duration: { days: Math.floor(hoursLeft / 24), hours: hoursLeft % 24 },
    value: p.suspension.reason,
    onConfirm: async ({ reason, days, hours }) => {
      await api('/api/suspension/edit', { uuid: p.uuid, reason, days, hours });
      toast(`Suspension for ${p.minecraftName} updated`);
    },
  });
}

function unsuspendDialog(p) {
  openDialog({
    title: `Unsuspend ${p.minecraftName}`,
    text: `Currently suspended until ${formatTime(p.suspension.expiresAt)}: "${p.suspension.reason}"`,
    confirmLabel: 'Unsuspend', confirmClass: 'btn-success',
    onConfirm: async ({ reason }) => {
      await api('/api/unsuspend', { uuid: p.uuid, reason });
      toast(`${p.minecraftName} unsuspended`);
    },
  });
}

function kickDialog(p) {
  openDialog({
    title: `Kick ${p.minecraftName}`,
    text: `Disconnects ${p.minecraftName}${p.server ? ` from ${p.server}` : ''}. They can rejoin straight away.`,
    confirmLabel: 'Kick', confirmClass: 'btn-warning',
    onConfirm: async ({ reason }) => {
      await api('/api/kick', { uuid: p.uuid, reason });
      toast(`${p.minecraftName} kicked`);
    },
  });
}

function noteDialog(p) {
  openDialog({
    title: `Add a note to ${p.minecraftName}`,
    text: 'Notes are only visible to staff. The player is not told.',
    confirmLabel: 'Add note', label: 'Note', maxLength: 2000,
    onConfirm: async ({ reason }) => {
      await api('/api/note', { uuid: p.uuid, note: reason });
      toast('Note added');
    },
  });
}

function unlinkDialog(p) {
  openDialog({
    title: `Remove link for ${p.minecraftName}`,
    text: `Unlinks ${p.minecraftName} from ${p.discordName}. They will be kicked if online and must link again to join.`,
    confirmLabel: 'Remove link', confirmClass: 'btn-danger',
    onConfirm: async ({ reason }) => {
      await api('/api/unlink', { uuid: p.uuid, reason });
      toast(`${p.minecraftName} unlinked`);
    },
  });
}

// ---- Players: everyone who has played. Staff also get the admin columns and actions. ----

/** localStorage, which can be unavailable (private windows, blocked storage). */
function stored(key) {
  try { return localStorage.getItem(key); } catch (e) { return null; }
}
function remember(key, value) {
  try { localStorage.setItem(key, value); } catch (e) { /* storage unavailable */ }
}

/** When they joined the community: when they linked, or else when they first played. */
const joinedAt = (p) => p.linkedAt || p.firstPlayed || null;

/** Minecraft name, or the Discord name for people who only use the website. */
const listName = (p) => p.minecraftName || p.discordName || '';

const SORTS = {
  seen: (a, b) => (b.online - a.online) || ((b.lastSeen || 0) - (a.lastSeen || 0)),
  online: (a, b) => (b.online - a.online) || SORTS['name-asc'](a, b),
  'name-asc': (a, b) => listName(a).localeCompare(listName(b), undefined, { sensitivity: 'base' }),
  'name-desc': (a, b) => listName(b).localeCompare(listName(a), undefined, { sensitivity: 'base' }),
  'joined-new': (a, b) => (joinedAt(b) || 0) - (joinedAt(a) || 0),
  'joined-old': (a, b) => (joinedAt(a) ?? Number.MAX_SAFE_INTEGER) - (joinedAt(b) ?? Number.MAX_SAFE_INTEGER),
  streak: (a, b) => ((b.streak || 0) - (a.streak || 0)) || SORTS.seen(a, b),
  admins: (a, b) => (b.isAdmin - a.isAdmin) || SORTS['name-asc'](a, b),
  suspended: (a, b) => (!!b.suspension - !!a.suspension) || SORTS['name-asc'](a, b),
};
// [value, label, staff only]
const PLAYER_SORTS = [['seen', 'Recently seen'], ['online', 'Online first'], ['name-asc', 'Name A–Z'],
  ['name-desc', 'Name Z–A'], ['joined-new', 'Newest members'], ['joined-old', 'Oldest members'], ['streak', 'Longest streak'],
  ['admins', 'Staff first'], ['suspended', 'Suspended first', true]];
const PLAYER_FILTERS = [['all', 'Everyone'], ['online', 'Online now'], ['linked', 'Linked members'],
  ['unlinked', 'Not linked'], ['staff', 'Staff'], ['suspended', 'Suspended', true]];
const PLAYER_FILTER_TESTS = {
  all: () => true, online: (p) => p.online, linked: (p) => p.linked, unlinked: (p) => !p.linked,
  staff: (p) => p.isAdmin, suspended: (p) => !!p.suspension,
};

/** The sort and filter menus differ for staff and everyone else. */
function setupPlayerControls() {
  const staff = isStaff();
  const fill = (select, options, current, fallback) => {
    select.replaceChildren(...options.filter(([, , staffOnly]) => staff || !staffOnly)
      .map(([value, label]) => el('option', { value }, label)));
    select.value = [...select.options].some((o) => o.value === current) ? current : fallback;
  };
  fill($('players-sort'), PLAYER_SORTS, $('players-sort').value || stored('dl-players-sort'), 'seen');
  fill($('players-show'), PLAYER_FILTERS, $('players-show').value, 'all');
  $('players-legend').replaceChildren(...[el('span', {}, el('i', { class: 'dot dot-admin' }), ' Staff'),
    staff && el('span', {}, el('i', { class: 'dot dot-suspended' }), ' Suspended')].filter(Boolean));
}

async function loadPlayers() {
  try {
    state.members = await api('/api/members');
    renderPlayers();
  } catch (e) {
    toast(e.message);
  }
}

function renderPlayers() {
  const staff = isStaff();
  const members = state.members || [];
  const query = $('players-search').value.trim().toLowerCase();
  const shown = members.filter(PLAYER_FILTER_TESTS[$('players-show').value] || PLAYER_FILTER_TESTS.all);
  const rows = shown
    .filter((p) => !query || [p.minecraftName || '', p.uuid || '', p.discordName || '', staff ? p.discordId || '' : '']
      .some((value) => value.toLowerCase().includes(query)))
    .sort(SORTS[$('players-sort').value] || SORTS.seen);
  $('players-count').textContent = query
    ? `(${rows.length.toLocaleString()} of ${shown.length.toLocaleString()})` : `(${shown.length.toLocaleString()})`;
  // Long lists render in steps so a server with thousands of players stays responsive.
  const limit = state.playersLimit || 200;
  const list = $('players-list');
  list.classList.toggle('staff', staff);
  list.replaceChildren(playerListHead(staff), ...rows.slice(0, limit).map(playerRow));
  list.hidden = rows.length === 0;
  $('players-more').hidden = rows.length <= limit;
  $('players-more').textContent = `Show more (${(rows.length - limit).toLocaleString()} left)`;
  const empty = $('players-empty');
  empty.textContent = members.length ? 'No players match your filter.' : 'No players yet.';
  empty.hidden = rows.length > 0;
}

const playerListHead = (staff) => el('div', { class: 'prow prow-head', 'aria-hidden': 'true' },
  el('div', {}, 'Player'), el('div', {}, 'Discord'), el('div', {}, 'Status'), el('div', { class: 'prow-joined' }, 'Member since'),
  staff && el('div', { class: 'prow-actions' }, 'Actions'));

/** A profile card picture behind something, fading out to the right. */
function cardBackdrop(url) {
  const node = el('div', { class: 'card-backdrop', 'aria-hidden': 'true' });
  node.style.setProperty('--card', `url("${url}")`);
  return node;
}

// The prow-* classes let the phone layout turn each row into a card.
function playerRow(p) {
  const staff = isStaff();
  const joined = joinedAt(p);
  const status = [...statusPills(p), streakChip(p.streak, p.bestStreak)];
  if (staff && p.suspension) {
    status.push(el('div', { class: 'suspension-note' },
      `Until ${formatTime(p.suspension.expiresAt)} — "${p.suspension.reason}" — by ${p.suspension.createdBy}`));
  }
  if (staff && p.linked && p.inGuild === false) status.push(el('div', { class: 'suspension-note' }, 'Not in the Discord server'));
  if (p.discordOnly) {
    // Logged in to the website with Discord but never linked Minecraft: no profile page or player actions.
    return el('div', { class: 'prow', role: 'listitem' },
      el('div', { class: 'prow-id' },
        el('span', { class: 'head head-none', 'aria-hidden': 'true' }),
        el('div', { class: 'prow-name muted' }, 'No Minecraft account')),
      el('div', { class: 'prow-discord' }, el('div', { class: 'identity' },
        discordAvatarLink(p),
        el('div', {}, discordName(p), staff && copyButton(p.discordId, 'User ID')))),
      el('div', { class: 'prow-status' }, status),
      el('div', { class: 'prow-joined muted', title: formatTime(p.firstPlayed) },
        el('span', { class: 'prow-label' }, 'Member since '), shortDate(p.firstPlayed)));
  }
  return el('div', { class: `prow ${p.card ? 'has-card' : ''}`, role: 'listitem' },
    p.card && cardBackdrop(p.card),
    el('div', { class: 'prow-id' },
      el('a', { href: playerHref(p.uuid), tabindex: '-1', 'aria-hidden': 'true' },
        el('img', { class: 'head', src: headUrl(p.uuid, 72), alt: '', loading: 'lazy' })),
      el('div', { class: 'prow-name' }, playerName(p), staff && copyButton(p.uuid, 'UUID'))),
    el('div', { class: 'prow-discord' }, p.linked
      ? el('div', { class: 'identity' },
        discordAvatarLink(p),
        el('div', {}, discordName(p), staff && copyButton(p.discordId, 'User ID')))
      : el('span', { class: 'muted' }, 'Not linked')),
    el('div', { class: 'prow-status' }, status),
    el('div', { class: 'prow-joined muted', title: joined ? formatTime(joined) : null },
      el('span', { class: 'prow-label' }, 'Member since '), joined ? shortDate(joined) : '—'),
    staff && el('div', { class: 'prow-actions' }, actionButtons(p, { compact: true })));
}

const resetPlayersLimit = () => { state.playersLimit = 200; renderPlayers(); };
$('players-search').addEventListener('input', resetPlayersLimit);
$('players-show').addEventListener('change', resetPlayersLimit);
$('players-sort').addEventListener('change', () => {
  remember('dl-players-sort', $('players-sort').value);
  resetPlayersLimit();
});
$('players-more').addEventListener('click', () => { state.playersLimit = (state.playersLimit || 200) + 400; renderPlayers(); });

// ---- Logs (the Logs page and each player page share this) ----

function logParams(uuid) {
  if (uuid) return new URLSearchParams({ filter: 'all', q: '', uuid });
  return new URLSearchParams({ filter: $('logs-filter').value, q: $('logs-search').value.trim() });
}

async function loadLogs(reset) {
  if (state.logsLoading) return;
  state.logsLoading = true;
  const params = logParams();
  if (!reset && state.logsBefore) params.set('before', state.logsBefore);
  try {
    const data = await api(`/api/logs?${params}`);
    const rows = data.entries.map((entry) => logRow(entry, true));
    if (reset) $('logs-body').replaceChildren(...rows);
    else $('logs-body').append(...rows);
    state.logsBefore = data.nextBefore;
    $('logs-more').hidden = !data.nextBefore;
    $('logs-empty').hidden = $('logs-body').children.length > 0;
  } catch (e) {
    toast(e.message);
  } finally {
    state.logsLoading = false;
  }
}

function logRow(entry, showPlayer) {
  const [label, tone] = EVENT_LABELS[entry.type] || [entry.type, ''];
  const player = entry.uuid
    ? el('a', { class: 'player-link', href: playerHref(entry.uuid) }, entry.player || entry.uuid)
    : (entry.player || '—');
  return el('tr', {},
    el('td', { class: 'nowrap muted' }, formatTime(entry.time)),
    el('td', {}, el('span', { class: `badge ${tone}` }, label)),
    showPlayer && el('td', { class: 'nowrap' }, player),
    // Without a player column, "by" gets its own line in the phone cards rather than widening the time column.
    el('td', { class: showPlayer ? null : 'own-line' }, entry.actor || '—'),
    el('td', { class: 'detail' }, maskIps(entry.detail || '')));
}

function downloadCsv(uuid) {
  location.href = `/api/logs/export.csv?${logParams(uuid)}`;
}

async function exportSheets(button, uuid) {
  const original = button.textContent;
  button.disabled = true;
  button.textContent = 'Exporting…';
  try {
    const params = logParams(uuid);
    const data = await api('/api/logs/export-sheets',
      { filter: params.get('filter'), q: params.get('q'), uuid: params.get('uuid') || '' });
    toast(`Exported ${data.rows} rows.`, { href: data.url, text: 'Open spreadsheet' });
  } catch (e) {
    toast(e.message);
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

let searchTimer;
$('logs-search').addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => loadLogs(true), 300);
});
$('logs-filter').addEventListener('change', () => loadLogs(true));
$('logs-more').addEventListener('click', () => loadLogs(false));
$('logs-csv').addEventListener('click', () => downloadCsv());
$('logs-sheets').addEventListener('click', (e) => exportSheets(e.currentTarget));

// ---- Player page ----

async function loadPlayer(uuid) {
  const view = $('view-player');
  if (!state.player || state.player.player.uuid !== uuid) view.replaceChildren(el('p', { class: 'empty shimmer' }, 'Loading…'));
  try {
    const changed = !state.player || state.player.player.uuid !== uuid;
    state.player = await api(`/api/player?uuid=${encodeURIComponent(uuid)}`);
    if (!isStaff()) {
      renderPublicPlayer();
      return;
    }
    if (changed) state.inventory = null;
    renderPlayer();
    loadPlayerLogs(true);
    if (currentRoute().inventory && state.player.player.online && changed) loadInventory();
  } catch (e) {
    view.replaceChildren(el('a', { class: 'back', href: homeHash() }, '← Back'), el('p', { class: 'empty' }, e.message));
  }
}

// ---- Login streaks ----

/** Streak tiers: the longer the streak, the wilder the badge. Always counted in days. */
const STREAK_TIERS = [[100, 't7', '👑'], [60, 't6', '🌈'], [30, 't5', '💠'], [14, 't4', '🔥'], [7, 't3', '🔥'], [3, 't2', '🔥'], [1, 't1', '🔥']];

function streakBadge(streak) {
  if (!streak) return null;
  const days = streak.current;
  const sub = [
    streak.best > days ? `Best: ${streak.best.toLocaleString()} day${streak.best === 1 ? '' : 's'}` : null,
    streak.rewardsOn ? (streak.joinedToday ? `Today's reward: +${streak.reward} ${streakCurrency()}`
      : `Join today for +${streak.reward} ${streakCurrency()}`) : null,
  ].filter(Boolean).join(' · ');
  if (!days) {
    return el('div', { class: 'streak t0' }, el('span', { class: 'streak-text' }, 'No login streak'),
      sub && el('span', { class: 'streak-sub' }, sub));
  }
  const [, tier, icon] = STREAK_TIERS.find(([from]) => days >= from);
  return el('div', { class: `streak ${tier}`, title: 'Days in a row this player has joined the server' },
    el('span', { class: 'streak-icon', 'aria-hidden': 'true' }, icon),
    el('span', { class: 'streak-text' }, `${days.toLocaleString()} day streak`),
    tier === 't7' && el('span', { class: 'streak-sparkles', 'aria-hidden': 'true' }, '✦ ✧ ✦'),
    sub && el('span', { class: 'streak-sub' }, sub));
}

const streakCurrency = () => state.player?.community?.currency?.many || 'credits';

/** A small streak badge for lists, with the same tiers as the profile badge. */
function streakChip(days, best) {
  if (!days) return null;
  const [, tier, icon] = STREAK_TIERS.find(([from]) => days >= from);
  return el('span', { class: `streak mini ${tier}`, title: `${days} day login streak${best > days ? ` (best ${best})` : ''}` },
    el('span', { class: 'streak-icon', 'aria-hidden': 'true' }, icon), el('span', { class: 'streak-text' }, `${days.toLocaleString()} day streak`));
}

async function teleportTo(p, mode, button) {
  button.disabled = true;
  const label = button.textContent;
  button.textContent = 'Teleporting…';
  try {
    const result = await api('/api/player/teleport', { uuid: p.uuid, mode });
    toast(result.message);
  } catch (e) {
    toast(e.message);
  } finally {
    button.disabled = false;
    button.textContent = label;
  }
}

/**
 * The top of a player page: head, name (with their bought prefix), badges and streak, over their profile card if
 * they show one. Staff also get teleports and the admin buttons.
 */
function profileHeader(p, staff) {
  const badges = [p.isAdmin && el('span', { class: 'badge warn' }, 'Staff'),
    p.linked ? el('span', { class: 'badge good' }, 'Linked') : el('span', { class: 'badge' }, 'Not linked'),
    staff && p.linked && !p.inGuild && el('span', { class: 'badge bad' }, 'Left the Discord server')];
  return el('div', { class: `card profile ${p.card ? 'has-card' : ''}` },
    p.card && cardBackdrop(p.card),
    el('img', { class: 'head-large', src: headUrl(p.uuid, 192), alt: '' }),
    el('div', { class: 'info' },
      el('h1', { class: nameClass(p) }, p.prefix && mcText(p.prefix, 'mc-chip'), p.minecraftName),
      el('div', { class: 'badges' }, badges, statusPills(p)),
      streakBadge(p.streak),
      copyButton(p.uuid, 'UUID'),
      el('div', { class: 'actions' },
        el('a', { class: 'btn btn-ghost btn-small', href: `https://namemc.com/profile/${p.uuid}`, target: '_blank', rel: 'noopener' },
          'View on NameMC ↗'),
        staff && el('button', { class: 'btn btn-ghost btn-small', type: 'button', title: 'Teleports your own Minecraft character',
          onclick: (e) => teleportTo(p, 'player', e.currentTarget) }, p.online ? 'Teleport to them' : 'Teleport to logout spot'),
        staff && el('button', { class: 'btn btn-ghost btn-small', type: 'button', title: 'Their bed or respawn anchor',
          onclick: (e) => teleportTo(p, 'bed', e.currentTarget) }, 'Teleport to their bed'))),
    staff && actionButtons(p));
}

function renderPlayer() {
  const { player: p, suspensions, notes, ips } = state.player;
  document.title = `${p.minecraftName} · DiscordLinked`;
  const profile = profileHeader(p, true);

  const discord = el('div', { class: 'card panel' },
    el('h2', {}, 'Discord'),
    p.linked
      ? el('div', { class: 'identity' },
        discordAvatarLink(p),
        el('div', {}, discordName(p), copyButton(p.discordId, 'User ID')))
      : el('p', { class: 'muted' }, 'This player has no linked Discord account.'),
    el('dl', { class: 'kv' },
      p.linked && [el('dt', {}, 'Linked'), el('dd', {}, formatTime(p.linkedAt))],
      p.firstPlayed && [el('dt', {}, 'First joined'), el('dd', {}, formatTime(p.firstPlayed))],
      el('dt', {}, 'Last seen'),
      el('dd', {}, p.online ? 'Online now' : (p.lastSeen ? `${timeAgo(p.lastSeen)} (${formatTime(p.lastSeen)})` : 'Never'))));

  const s = p.suspension;
  const suspension = el('div', { class: 'card panel' },
    el('h2', {}, 'Suspension'),
    s
      ? el('dl', { class: 'kv' },
        el('dt', {}, 'Status'), el('dd', { class: 'name-suspended' }, `Suspended · ${formatRemaining(s.expiresAt)} left`),
        el('dt', {}, 'Reason'), el('dd', {}, s.reason),
        el('dt', {}, 'Ends'), el('dd', {}, formatTime(s.expiresAt)),
        el('dt', {}, 'By'), el('dd', {}, s.createdBy),
        el('dt', {}, 'Since'), el('dd', {}, formatTime(s.createdAt)))
      : el('p', { class: 'muted' }, `Not suspended. ${suspensions.length} past suspension${suspensions.length === 1 ? '' : 's'}.`));

  const notesSection = [
    el('h2', { class: 'section-title' }, withCount('Notes', notes.length),
      el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => noteDialog(p) }, 'Add note')),
    notes.length
      ? el('div', { class: 'notes' }, notes.map((n) => el('div', { class: 'note' },
        el('p', {}, n.note), el('div', { class: 'meta' }, `${n.author} · ${formatTime(n.createdAt)}`))))
      : emptyCard('No notes yet.'),
  ];

  // Only when there is some: the Suspension card already says "0 past suspensions".
  const historySection = !suspensions.length ? [] : [
    el('h2', { class: 'section-title' }, withCount('Suspension history', suspensions.length)),
    el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
      el('thead', {}, el('tr', {}, ['Started', 'Ends', 'Reason', 'By', 'Outcome'].map((h) => el('th', {}, h)))),
      el('tbody', {}, suspensions.map((x) => el('tr', {},
        el('td', { class: 'nowrap muted' }, formatTime(x.createdAt)),
        el('td', { class: 'nowrap' }, formatTime(x.expiresAt)),
        el('td', { class: 'detail' }, x.reason),
        el('td', {}, x.createdBy),
        el('td', { class: 'detail' }, x.active
          ? el('span', { class: 'badge bad' }, 'Active')
          : x.liftedAt
            ? `Lifted ${formatTime(x.liftedAt)} by ${x.liftedBy}: "${x.liftReason}"`
            : 'Expired')))))),
  ];

  const ipSection = [
    el('h2', { class: 'section-title' }, withCount('IP addresses', ips.length)),
    ips.length ? el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
      el('thead', {}, el('tr', {}, ['IP', 'First seen', 'Last seen', 'Times', 'Other accounts on this IP'].map((h) => el('th', {}, h)))),
      el('tbody', {}, ips.map((ip) => el('tr', {},
        el('td', { class: 'mono' }, sensitive(ip.ip)),
        el('td', { class: 'nowrap muted' }, formatTime(ip.firstSeen)),
        el('td', { class: 'nowrap muted' }, formatTime(ip.lastSeen)),
        el('td', {}, ip.times),
        el('td', {}, ip.others.length
          ? el('div', { class: 'others' }, ip.others.map((o) => el('a', { class: 'chip', href: playerHref(o.uuid) }, o.name)))
          : el('span', { class: 'muted' }, 'None')))))))
      : emptyCard('No IPs recorded yet.'),
  ];

  const sheetsButton = state.me.sheetsEnabled && el('button', {
    class: 'btn btn-ghost btn-small', type: 'button', onclick: (e) => exportSheets(e.currentTarget, p.uuid),
  }, 'Export to Google Sheets');
  const activitySection = [
    el('h2', { class: 'section-title' }, 'Activity',
      el('div', { class: 'actions' },
        el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => downloadCsv(p.uuid) }, 'Download CSV'),
        sheetsButton)),
    el('div', { class: 'table-wrap' }, el('table', { class: 'table table-cards' },
      el('thead', {}, el('tr', {}, ['Time', 'Event', 'By', 'Details'].map((h) => el('th', {}, h)))),
      el('tbody', { id: 'player-logs' }))),
    el('div', { class: 'more' }, el('button', {
      class: 'btn btn-ghost', type: 'button', id: 'player-logs-more', hidden: true, onclick: () => loadPlayerLogs(false),
    }, 'Load more')),
  ];

  $('view-player').replaceChildren(
    el('a', { class: 'back', href: '#/players' }, '← All players'),
    profile,
    el('div', { class: 'grid' }, discord, suspension),
    ...inventorySection(p),
    ...unlocksSection(p, state.player.cosmetics),
    ...communitySection(p, state.player.community),
    ...notesSection, ...historySection, ...ipSection, ...activitySection);
}

// ---- Unlocks: the cosmetics a player has, switched on and off on their own profile ----

/** Cosmetic kinds in shop order: [kind, section title, one of them, what it does]. */
const COSMETIC_KINDS = [
  ['prefix', 'Prefixes', 'prefix', 'Shown in front of your name in chat and the tab list.'],
  ['trail', 'Trails', 'trail', 'Particles that follow you while you walk.'],
  ['aura', 'Auras', 'aura', 'Particles around you while you stand still.'],
  ['card', 'Profile cards', 'profile card', 'A picture behind your name in the player lists and on your profile.'],
  ['keepinv', 'Quality of life', 'KeepInventory', ''],
];

function keepInventoryText(delayMinutes) {
  return 'Keep your items and XP when you die. While it\'s on you can\'t hurt other players.'
    + (delayMinutes > 0 ? ` It starts working ${delayMinutes} minute${delayMinutes === 1 ? '' : 's'} after you switch it on.` : '');
}

async function equipCosmetic(item, on, button) {
  if (button) button.disabled = true;
  try {
    const result = await api('/api/cosmetics/equip', { id: item.id, on });
    toast(result.message);
  } catch (e) {
    toast(e.message);
  }
  refreshCurrentView();
}

function unlocksSection(p, cos) {
  if (!cos) return [];
  const self = cos.self;
  const keepFrom = cos.keepInventoryFrom;
  const groups = COSMETIC_KINDS.map(([kind, title]) => [title, cos.items.filter((item) => item.kind === kind)])
    .filter(([, items]) => items.length);
  const cur = state.player?.community?.currency || { one: 'credit', many: 'credits' };
  const row = (item) => {
    let sub = item.paid ? `Bought for ${credits(item.paid, cur)} · ${shortDate(item.acquiredAt)}` : `Given · ${shortDate(item.acquiredAt)}`;
    if (item.kind === 'keepinv' && item.equipped) {
      sub = keepFrom > cos.now ? `Starts working at ${new Intl.DateTimeFormat(undefined, { timeStyle: 'short' }).format(new Date(keepFrom))}`
        : 'Working: items and XP are kept on death';
    }
    let control;
    if (self) {
      const box = el('input', { type: 'checkbox', role: 'switch', 'aria-label': `Use ${item.name}` });
      box.checked = item.equipped;
      box.addEventListener('change', () => equipCosmetic(item, box.checked, box));
      control = el('label', { class: 'switch' }, box, el('span', { class: 'switch-track' }));
    } else {
      control = el('span', { class: `badge ${item.equipped ? 'good' : ''}` }, item.equipped ? 'In use' : 'Off');
    }
    const styled = item.kind === 'trail' || item.kind === 'aura';
    let look = null;
    if (styled && self) {
      // Their own look for it: saved as soon as a menu changes.
      look = styleEditor(item.myStyle, item.style, item.colorable, async (style) => {
        try {
          toast((await api('/api/cosmetics/style', { id: item.id, style })).message);
        } catch (e) {
          toast(e.message);
        }
        refreshCurrentView();
      });
    } else if (styled && styleText(item.myStyle)) {
      sub += ` · ${styleText(item.myStyle)}`;
    }
    return el('div', { class: `unlock ${item.equipped ? 'on' : ''}` },
      cosmeticPreview(item, { name: p.minecraftName, uuid: p.uuid }, true),
      el('div', { class: 'unlock-text' }, el('div', { class: 'unlock-name' }, item.name), el('div', { class: 'muted' }, sub)),
      look,
      control,
      cos.canGive && el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => confirmDialog({
        title: `Take ${item.name} away from ${p.minecraftName}?`,
        text: item.paid ? `They paid ${credits(item.paid, cur)} for it and don't get that back.` : 'It was given to them.',
        confirmLabel: 'Take it away', confirmClass: 'btn-danger',
        onConfirm: async () => {
          toast((await api('/api/cosmetics/revoke', { uuid: p.uuid, id: item.id })).message);
          refreshCurrentView();
        },
      }) }, 'Take away'));
  };
  let give = null;
  if (cos.canGive) {
    const have = new Set(cos.items.map((item) => item.id));
    const choices = cos.all.filter((c) => !have.has(c.id));
    const select = el('select', { 'aria-label': 'Cosmetic to give' },
      choices.map((c) => el('option', { value: c.id }, `${c.name} (${COSMETIC_KINDS.find(([k]) => k === c.kind)?.[2] || c.kind})`)));
    give = choices.length > 0 && el('div', { class: 'unlock-give' }, select,
      el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: async (e) => {
        e.currentTarget.disabled = true;
        try {
          toast((await api('/api/cosmetics/give', { uuid: p.uuid, id: Number(select.value) })).message);
        } catch (err) {
          toast(err.message);
        }
        refreshCurrentView();
      } }, 'Give for free'));
  }
  if (!self && !cos.items.length && !give) return [];
  return [
    el('h2', { class: 'section-title' }, self ? 'Your unlocks' : 'Unlocks',
      self && cos.open && el('a', { class: 'btn btn-ghost btn-small', href: '#/shop/cosmetics' }, 'Get more')),
    el('div', { class: 'card panel unlocks' },
      groups.length ? groups.flatMap(([title, items]) => [el('div', { class: 'unlock-group' }, title), ...items.map(row)])
        : el('p', { class: 'muted' }, self ? 'Nothing yet: prefixes, trails, auras, profile cards and KeepInventory are in the Shop.'
          : 'No cosmetics yet.'),
      give),
  ];
}

async function loadPlayerLogs(reset) {
  const uuid = state.player.player.uuid;
  const params = logParams(uuid);
  if (!reset && state.playerLogsBefore) params.set('before', state.playerLogsBefore);
  try {
    const data = await api(`/api/logs?${params}`);
    const body = $('player-logs');
    if (!body) return;
    const rows = data.entries.map((entry) => logRow(entry, false));
    if (reset) body.replaceChildren(...rows);
    else body.append(...rows);
    if (!body.children.length) body.append(el('tr', {}, el('td', { colspan: 4, class: 'empty' }, 'Nothing logged yet.')));
    state.playerLogsBefore = data.nextBefore;
    $('player-logs-more').hidden = !data.nextBefore;
  } catch (e) {
    toast(e.message);
  }
}

// ---- Inventory viewer ----

// Item icons come from the minecraft-textures package on jsDelivr, loaded by the admin's browser.
const TEXTURES = 'https://cdn.jsdelivr.net/npm/minecraft-textures@26.2.1/dist/textures';
const textureCache = {};

const versionKey = (v) => v.split('.').map((n) => parseInt(n, 10) || 0);
function compareVersions(a, b) {
  const x = versionKey(a), y = versionKey(b);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) - (y[i] || 0);
  }
  return 0;
}

/** id -> { readable, texture } for the closest texture set at or below the server's version. */
async function loadTextures(mcVersion) {
  try {
    textureCache.index ||= await fetch(`${TEXTURES}/manifest/index.json`).then((r) => r.json());
    const versions = textureCache.index.versions;
    const fit = versions.filter((v) => compareVersions(v, mcVersion || '0') <= 0).pop() || textureCache.index.latestVersion;
    if (!textureCache[fit]) {
      const manifest = await fetch(`${TEXTURES}/manifest/${fit}.json`).then((r) => r.json());
      textureCache[fit] = new Map(manifest.items.map((item) => [item.id, item]));
    }
    return textureCache[fit];
  } catch (e) {
    return new Map(); // icons unavailable: slots fall back to text
  }
}

const ROMAN = ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X'];
const titleCase = (id) => id.replace(/^minecraft:/, '').split('_').map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');

function itemName(item, textures) {
  return item.name || textures.get(item.id)?.readable || titleCase(item.id);
}

// Inventory panel: "Live" shows (and keeps refreshing) what they have now; "Snapshots" lists saved copies.
const LIVE_INVENTORY_MS = 3000;
let liveInventoryTimer = null;

const SNAPSHOT_LABELS = {
  DEATH: ['Death', 'bad'], JOIN: ['Join', 'info'], QUIT: ['Quit', ''], WORLD_CHANGE: ['World change', ''],
  MANUAL: ['Manual', 'good'], BEFORE_RESTORE: ['Before restore', 'warn'],
};

function stopLiveInventory() {
  clearTimeout(liveInventoryTimer);
  liveInventoryTimer = null;
}

function inventoryState() {
  state.inventory ||= { mode: 'live', tab: 'inventory', data: null, textures: new Map(), snapshots: null, snapshotId: null };
  return state.inventory;
}

/** Opens the live view (logged once) and keeps it refreshed while it stays on screen. */
async function loadInventory(refresh = false) {
  const inv = inventoryState();
  const uuid = state.player?.player.uuid;
  if (!uuid || !$('inventory-box')) return stopLiveInventory();
  inv.mode = 'live';
  if (!refresh) renderInventory();
  try {
    const data = await api(`/api/player/inventory?uuid=${encodeURIComponent(uuid)}${refresh ? '&live=1' : ''}`);
    if (state.player?.player.uuid !== uuid || inv.mode !== 'live') return;
    inv.textures = await loadTextures(data.mcVersion);
    inv.data = data;
    inv.updatedAt = Date.now();
    inv.error = null;
  } catch (e) {
    inv.error = e.message;
  }
  renderInventory();
  stopLiveInventory();
  if (inv.mode === 'live' && state.player?.player.online && !inv.error) {
    liveInventoryTimer = setTimeout(() => {
      if (!document.hidden && $('inventory-box') && currentRoute().view === 'player') loadInventory(true);
      else stopLiveInventory();
    }, LIVE_INVENTORY_MS);
  }
}

async function loadSnapshots(selectId) {
  const inv = inventoryState();
  const uuid = state.player?.player.uuid;
  stopLiveInventory();
  inv.mode = 'snapshots';
  try {
    const result = await api(`/api/player/snapshots?uuid=${encodeURIComponent(uuid)}`);
    if (state.player?.player.uuid !== uuid) return;
    inv.snapshots = result.snapshots;
    inv.pending = result.pendingRestore;
    inv.error = null;
    const id = selectId ?? inv.snapshotId ?? result.snapshots[0]?.id;
    if (id) await previewSnapshot(id, false);
  } catch (e) {
    inv.error = e.message;
  }
  renderInventory();
}

async function previewSnapshot(id, render = true) {
  const inv = inventoryState();
  const snapshot = await api(`/api/player/snapshot?id=${id}`);
  inv.snapshotId = id;
  inv.preview = snapshot;
  inv.textures = await loadTextures(snapshot.inventory.mcVersion);
  if (render) renderInventory();
}

async function saveSnapshot() {
  try {
    await api('/api/player/snapshot/save', { uuid: state.player.player.uuid });
    toast('Snapshot requested. It appears in the list in a moment.');
  } catch (e) {
    toast(e.message);
  }
}

function restoreDialog(snapshot) {
  const p = state.player.player;
  const [label] = SNAPSHOT_LABELS[snapshot.reason] || [snapshot.reason];
  openDialog({
    title: `Restore ${p.minecraftName}'s inventory`,
    text: `Replaces their items with the ${label.toLowerCase()} snapshot from ${formatTime(snapshot.createdAt)}. `
      + 'Their current items are saved as a "Before restore" snapshot first, so this can be undone.'
      + (p.online ? '' : ' They are offline, so it happens when they next join.'),
    confirmLabel: 'Restore', confirmClass: 'btn-warning',
    checkbox: 'Also restore their ender chest',
    onConfirm: async ({ reason, checked }) => {
      const result = await api('/api/player/snapshot/restore', { id: snapshot.id, reason, includeEnderChest: checked });
      toast(result.queued ? 'Restore queued for when they next join' : 'Inventory restored');
    },
  });
}

function inventorySection(p) {
  const inv = state.inventory;
  const box = el('div', { id: 'inventory-box' });
  const mode = inv?.mode || 'live';
  const modes = el('div', { class: 'tabs inv-modes', hidden: !state.inventory },
    el('a', { href: '#', class: mode === 'live' ? 'active' : '', onclick: (e) => { e.preventDefault(); loadInventory(); } }, 'Live'),
    el('a', { href: '#', class: mode === 'snapshots' ? 'active' : '', onclick: (e) => { e.preventDefault(); loadSnapshots(); } }, 'Snapshots'));
  queueMicrotask(() => {
    if (!state.inventory) {
      box.replaceChildren(el('p', { class: 'muted' }, p.online
        ? 'Live shows what they have right now and keeps updating. Snapshots are saved on death, join, quit and world change, and can be restored.'
        : 'They are offline. Open Snapshots to see saved copies of their inventory.'),
      el('div', { class: 'actions inv-start' },
        p.online && el('button', { class: 'btn btn-small', type: 'button', onclick: () => loadInventory() }, 'View live inventory'),
        el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => loadSnapshots() }, 'Browse snapshots')));
    } else {
      renderInventory();
    }
  });
  return [el('h2', { class: 'section-title' }, 'Inventory', modes), el('div', { class: 'card panel inventory-panel' }, box)];
}

function renderInventory() {
  const box = $('inventory-box');
  const inv = state.inventory;
  if (!box || !inv) return;
  document.querySelector('.inv-modes')?.removeAttribute('hidden');
  document.querySelectorAll('.inv-modes a').forEach((a, i) => a.classList.toggle('active', (i === 0) === (inv.mode === 'live')));
  if (inv.mode === 'live') {
    if (!state.player.player.online) {
      box.replaceChildren(el('p', { class: 'muted' }, 'They are offline, so there is no live inventory. Open Snapshots instead.'));
    } else if (inv.error) {
      box.replaceChildren(el('p', { class: 'error' }, inv.error));
    } else if (!inv.data) {
      box.replaceChildren(el('p', { class: 'muted shimmer' }, 'Loading inventory…'));
    } else {
      box.replaceChildren(
        el('div', { class: 'inv-livebar' },
          el('span', { class: 'live-dot' }), el('span', { class: 'muted' }, 'Live · updates every few seconds'),
          el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: saveSnapshot }, 'Save snapshot')),
        ...inventoryView(inv.data, inv.textures));
    }
    return;
  }

  // Snapshots
  if (inv.error) {
    box.replaceChildren(el('p', { class: 'error' }, inv.error));
    return;
  }
  if (!inv.snapshots) {
    box.replaceChildren(el('p', { class: 'muted shimmer' }, 'Loading snapshots…'));
    return;
  }
  if (!inv.snapshots.length) {
    box.replaceChildren(el('p', { class: 'muted' }, 'No snapshots yet. They are saved on death, join, quit and world change (needs the DiscordLinkedSpy plugin).'));
    return;
  }
  const list = el('div', { class: 'snap-list' }, inv.snapshots.map((snap) => {
    const [label, tone] = SNAPSHOT_LABELS[snap.reason] || [snap.reason, ''];
    return el('button', {
      class: `snap-item ${snap.id === inv.snapshotId ? 'active' : ''}`, type: 'button',
      onclick: () => previewSnapshot(snap.id).catch((e) => toast(e.message)),
    },
    el('div', { class: 'snap-top' }, el('span', { class: `badge ${tone}` }, label), el('span', { class: 'muted' }, timeAgo(snap.createdAt))),
    el('div', { class: 'snap-meta' }, `${formatTime(snap.createdAt)} · ${snap.server} · ${snap.stacks} stacks`),
    snap.detail && el('div', { class: 'snap-detail' }, snap.detail));
  }));
  const preview = inv.preview && inv.preview.id === inv.snapshotId ? inv.preview : null;
  box.replaceChildren(...[
    inv.pending && el('p', { class: 'self-status warn' },
      `A restore of snapshot #${inv.pending.snapshotId} is queued for when they next join (by ${inv.pending.actor}).`),
    el('div', { class: 'snap-layout' }, list,
      el('div', { class: 'snap-preview' },
        preview
          ? [el('div', { class: 'inv-livebar' },
            el('strong', {}, `Snapshot #${preview.id}`),
            el('span', { class: 'muted' }, formatTime(preview.createdAt)),
            el('button', { class: 'btn btn-warning btn-small', type: 'button', onclick: () => restoreDialog(preview) }, 'Restore this')),
          ...inventoryView(preview.inventory, inv.textures)]
          : el('p', { class: 'muted' }, 'Pick a snapshot to preview it.')))].filter(Boolean));
}

/** The Minecraft-style inventory grid for live data or a snapshot. */
function inventoryView(data, textures) {
  const inv = state.inventory;
  const slot = (item, extra = '', path = '') => inventorySlot(item, textures, extra, path);
  const tabs = el('div', { class: 'tabs inv-tabs' },
    ['inventory', 'ender'].map((name) => el('a', {
      href: '#', class: inv.tab === name ? 'active' : '',
      onclick: (e) => { e.preventDefault(); inv.tab = name; renderInventory(); },
    }, name === 'inventory' ? 'Inventory' : 'Ender chest')));
  const stats = el('div', { class: 'inv-stats' },
    el('span', { class: 'chip' }, `Health ${data.health} / 20`),
    el('span', { class: 'chip' }, `Food ${data.food} / 20`),
    el('span', { class: 'chip' }, `Level ${data.level}`),
    el('span', { class: 'chip' }, titleCase(data.gameMode.toLowerCase())),
    el('span', { class: 'chip mono' }, `${data.world} ${data.x}, ${data.y}, ${data.z}`),
    el('span', { class: 'muted' }, `on ${data.server}`));
  let body;
  if (inv.tab === 'ender') {
    body = el('div', { class: 'inv-rows' }, el('div', { class: 'inv-grid' }, data.enderChest.map((i, n) => slot(i, '', `enderChest.${n}`))));
  } else {
    const armor = [...data.armor].reverse(); // helmet first
    body = el('div', { class: 'inv-layout' },
      el('div', { class: 'inv-side' },
        armor.map((i, n) => slot(i, ['helmet', 'chestplate', 'leggings', 'boots'][n], `armor.${3 - n}`)),
        el('div', { class: 'inv-gap' }), slot(data.offhand, 'offhand', 'offhand')),
      el('div', { class: 'inv-rows' },
        el('div', { class: 'inv-grid' }, data.storage.slice(9).map((i, n) => slot(i, '', `storage.${n + 9}`))),
        el('div', { class: 'inv-grid hotbar' }, data.storage.slice(0, 9).map((i, n) =>
          slot(i, n === data.selectedSlot ? 'selected' : '', `storage.${n}`)))));
  }
  const contents = el('div', { id: 'inv-contents' });
  // Keep an opened shulker box open (and current) across live refreshes.
  if (inv.openPath) {
    const [group, index] = inv.openPath.split('.');
    const item = index === undefined ? data[group] : data[group]?.[Number(index)];
    if (item?.contents) queueMicrotask(() => showContents(item, textures));
    else inv.openPath = null;
  }
  return [stats, tabs, body, contents];
}

function inventorySlot(item, textures, extra, path = '') {
  const node = el('div', { class: `slot ${extra}`, tabindex: item ? 0 : -1, 'aria-label': item ? `${item.amount} × ${itemName(item, textures)}` : 'Empty slot' });
  if (!item) return node;
  const texture = textures.get(item.id)?.texture;
  if (texture) {
    const img = el('img', { src: `${TEXTURES}/assets/${texture}`, alt: '', loading: 'lazy' });
    img.addEventListener('error', () => img.replaceWith(slotFallback(item, textures)));
    node.append(img);
  } else {
    node.append(slotFallback(item, textures));
  }
  if (item.enchantments) node.classList.add('enchanted');
  if (item.amount > 1) node.append(el('span', { class: 'slot-count' }, item.amount));
  if (item.maxDurability && item.durability < item.maxDurability) {
    const fill = el('i');
    const pct = Math.max(0, item.durability / item.maxDurability);
    fill.style.width = `${Math.round(pct * 100)}%`;
    fill.style.background = `hsl(${Math.round(pct * 120)}, 85%, 45%)`;
    node.append(el('span', { class: 'slot-durability' }, fill));
  }
  const show = () => showTooltip(node, item, textures);
  node.addEventListener('mouseenter', show);
  node.addEventListener('focus', show);
  node.addEventListener('mouseleave', hideTooltip);
  node.addEventListener('blur', hideTooltip);
  node.addEventListener('click', () => {
    show();
    if (item.contents) {
      state.inventory.openPath = path || null;
      showContents(item, textures);
    }
  });
  return node;
}

function slotFallback(item, textures) {
  const name = itemName(item, textures);
  return el('span', { class: 'slot-fallback', title: name }, name.split(' ').map((w) => w[0]).join('').slice(0, 3));
}

function showContents(item, textures) {
  const target = $('inv-contents');
  if (!target) return;
  target.replaceChildren(el('div', { class: 'inv-contents' },
    el('div', { class: 'inv-contents-title' }, `Inside ${itemName(item, textures)}`,
      el('button', {
        class: 'btn btn-ghost btn-small', type: 'button',
        onclick: () => { state.inventory.openPath = null; target.replaceChildren(); },
      }, 'Close')),
    el('div', { class: 'inv-grid' }, item.contents.map((i) => inventorySlot(i, textures, '', '')))));
}

let tooltip;
function showTooltip(anchor, item, textures) {
  tooltip ||= document.body.appendChild(el('div', { class: 'mc-tooltip', role: 'tooltip' }));
  const lines = [el('div', { class: `tt-name ${item.enchantments ? 'tt-enchanted' : ''} ${item.name ? 'tt-custom' : ''}` }, itemName(item, textures))];
  for (const e of item.enchantments || []) {
    lines.push(el('div', { class: 'tt-enchant' }, `${titleCase(e.id)} ${ROMAN[e.level] || e.level}`));
  }
  for (const line of item.lore || []) lines.push(el('div', { class: 'tt-lore' }, line));
  if (item.contents) {
    const filled = item.contents.filter(Boolean);
    lines.push(el('div', { class: 'tt-lore' }, `${filled.length} stack${filled.length === 1 ? '' : 's'} inside · click to open`));
  }
  if (item.maxDurability) lines.push(el('div', { class: 'tt-meta' }, `Durability: ${item.durability} / ${item.maxDurability}`));
  lines.push(el('div', { class: 'tt-meta' }, `${item.id}${item.amount > 1 ? ` × ${item.amount}` : ''}`));
  tooltip.replaceChildren(...lines);
  tooltip.hidden = false;
  const r = anchor.getBoundingClientRect();
  const left = Math.min(window.innerWidth - tooltip.offsetWidth - 8, r.right + 8);
  const top = Math.max(8, Math.min(window.innerHeight - tooltip.offsetHeight - 8, r.top));
  tooltip.style.left = `${Math.max(8, left)}px`;
  tooltip.style.top = `${top}px`;
}

function hideTooltip() {
  if (tooltip) tooltip.hidden = true;
}

// ---- Global player search ----

let globalTimer;
let globalResults = [];
let globalActive = -1;

function renderGlobalResults(query) {
  const box = $('global-results');
  if (!query) {
    hideGlobalResults();
    return;
  }
  box.replaceChildren(...(globalResults.length
    ? globalResults.map((p, i) => el('a', {
      class: 'search-result', href: playerHref(p.uuid), role: 'option', id: `gs-${i}`,
      onclick: closeGlobalSearch, onmouseenter: () => setGlobalActive(i),
    },
    el('img', { class: 'head', src: headUrl(p.uuid, 56), alt: '' }),
    el('div', { class: 'grow' },
      el('div', { class: `name ${nameClass(p)}` }, p.minecraftName),
      el('div', { class: 'sub' }, p.linked ? `Linked · ${p.discordName}` : 'Not linked'),
      el('div', { class: 'search-pills' }, statusPills(p)))))
    : [el('div', { class: 'search-empty' }, 'No players found')]));
  box.hidden = false;
  $('global-search').setAttribute('aria-expanded', 'true');
  setGlobalActive(globalActive);
}

/** Moves the highlight in place (rebuilding the rows on every mouse move would be wasteful). */
function setGlobalActive(i) {
  globalActive = i;
  $('global-results').querySelectorAll('.search-result').forEach((n, j) => {
    n.classList.toggle('active', j === i);
    n.setAttribute('aria-selected', String(j === i));
  });
  if (i >= 0) $('global-search').setAttribute('aria-activedescendant', `gs-${i}`);
  else $('global-search').removeAttribute('aria-activedescendant');
}

function hideGlobalResults() {
  $('global-results').hidden = true;
  $('global-search').setAttribute('aria-expanded', 'false');
  $('global-search').removeAttribute('aria-activedescendant');
}

function closeGlobalSearch() {
  hideGlobalResults();
  $('global-search').value = '';
  globalResults = [];
  globalActive = -1;
}

$('global-search').addEventListener('input', () => {
  clearTimeout(globalTimer);
  const query = $('global-search').value.trim();
  if (!query) {
    renderGlobalResults('');
    return;
  }
  globalTimer = setTimeout(async () => {
    try {
      globalResults = await api(`/api/search?q=${encodeURIComponent(query)}`);
      globalActive = globalResults.length ? 0 : -1;
      if ($('global-search').value.trim() === query) renderGlobalResults(query);
    } catch (e) {
      toast(e.message);
    }
  }, 250);
});

$('global-search').addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    closeGlobalSearch();
  } else if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && globalResults.length) {
    e.preventDefault();
    setGlobalActive((globalActive + (e.key === 'ArrowDown' ? 1 : -1) + globalResults.length) % globalResults.length);
    if ($('global-results').hidden) renderGlobalResults($('global-search').value.trim()); // closed by a click elsewhere
    $(`gs-${globalActive}`)?.scrollIntoView({ block: 'nearest' });
  } else if (e.key === 'Enter' && globalResults[globalActive]) {
    location.hash = playerHref(globalResults[globalActive].uuid);
    closeGlobalSearch();
  }
});

document.addEventListener('click', (e) => {
  if (!e.target.closest('.global-search')) hideGlobalResults();
});

// ---- Community: credits and interactions (DiscordLinkedFAD) ----

/** "1 Pawbuck", "1,250 Pawbucks". */
const credits = (n, currency) => `${n.toLocaleString()} ${Math.abs(n) === 1 ? currency.one : currency.many}`;
const signedCredits = (n, currency) => `${n > 0 ? '+' : '−'}${credits(Math.abs(n), currency)}`;

/** Active playtime as hours, e.g. "76h 12m". */
function playtime(seconds) {
  const minutes = Math.floor(seconds / 60);
  return minutes >= 60 ? `${Math.floor(minutes / 60).toLocaleString()}h ${minutes % 60}m` : `${minutes}m`;
}

const stat = (label, value, title) => el('div', { class: 'stat', title },
  el('div', { class: 'stat-label' }, label), el('div', { class: 'stat-value' }, value));

const FRAMES = { CHALLENGE: ['Challenge', 'info'], GOAL: ['Goal', 'good'], TASK: ['Task', ''] };
const TRANSACTION_KINDS = { PLAYTIME: 'Active play', ADVANCEMENT: 'Advancement', ADMIN: 'Staff', SHOP: 'Shop', DAILY: 'Daily reward',
  SALE: 'Sold', EXCHANGE: 'Exchange', COSMETIC: 'Cosmetics', GROUP: 'Group' };

function creditsDialog(p, community) {
  const currency = community.currency;
  openDialog({
    title: `Give or take ${currency.many}: ${p.minecraftName}`,
    text: `They have ${credits(community.credits, currency)}. Use a negative number to take some away.`
      + (p.online ? ' They\'re told in chat.' : ''),
    confirmLabel: 'Save', amount: { label: `${currency.many} (+ to give, − to take)` },
    onConfirm: async ({ reason, amount }) => {
      const result = await api('/api/credits/adjust', { uuid: p.uuid, amount, reason });
      toast(`${p.minecraftName} now has ${credits(result.balance, currency)}`);
    },
  });
}

/** Credits, playtime and advancements on a player page. The community view gets no ledger, AFK time or editing. */
function communitySection(p, c) {
  if (!c || !c.enabled) return [];
  const staff = isStaff();
  const currency = c.currency;
  const summary = el('div', { class: 'card panel' },
    el('div', { class: 'stats-grid' },
      stat('Balance', [credits(c.credits, currency), c.rank ? el('span', { class: 'stat-sub' }, `Rank #${c.rank}`) : null]),
      stat('Earned in total', credits(c.earned, currency)),
      stat('Active playtime', [playtime(c.activeSeconds), staff ? el('span', { class: 'stat-sub' }, `${playtime(c.afkSeconds)} AFK`) : null],
        'Time on the servers, not counting AFK'),
      stat('Advancements', c.advancements.length.toLocaleString()),
      stat('Interactions', [`${c.given.toLocaleString()} given`, el('span', { class: 'stat-sub' }, `${c.received.toLocaleString()} received`)]),
      c.market && stat(c.microCurrency.many, [micro(c.micro, c.microCurrency),
        c.microRank ? el('span', { class: 'stat-sub' }, `Rank #${c.microRank}`) : null,
        c.group ? el('a', { class: 'stat-sub', href: '#/community/groups' }, `shared in ${c.group.name} (${c.group.members} players)`) : null]),
      c.market && stat('Earned by selling', micro(c.microEarned, c.microCurrency))),
    c.favourites.length > 0 && el('div', { class: 'others' },
      el('span', { class: 'muted' }, 'Favourite interactions:'),
      c.favourites.map((f) => el('span', { class: 'chip' }, `/${f.action} ×${f.count.toLocaleString()}`))),
    staff && c.optedOut && el('p', { class: 'muted' }, 'Has turned interactions off, so nobody can /hug, /kiss etc. them.'));

  const ledger = !staff ? null : c.transactions.length ? el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
    el('thead', {}, el('tr', {}, ['Time', 'Change', 'Balance', 'What'].map((h) => el('th', {}, h)))),
    el('tbody', {}, c.transactions.map((t) => el('tr', {},
      el('td', { class: 'nowrap muted', title: formatTime(t.time) }, formatTime(t.time)),
      el('td', { class: `nowrap ${t.amount > 0 ? 'credit-plus' : 'credit-minus'}` }, signedCredits(t.amount, currency)),
      el('td', { class: 'nowrap' }, credits(t.balance, currency)),
      el('td', { class: 'detail' }, TRANSACTION_KINDS[t.kind] || t.kind,
        t.detail && t.kind !== 'PLAYTIME' ? `: ${t.detail}` : '', t.actor ? ` — ${t.actor}` : ''))))))
    : emptyCard(`No ${currency.many} yet.`);

  const mc = c.microCurrency;
  const microLedger = !staff || !c.market ? null : c.microTransactions.length
    ? el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
      el('thead', {}, el('tr', {}, ['Time', 'Change', 'Balance', 'What'].map((h) => el('th', {}, h)))),
      el('tbody', {}, c.microTransactions.map((t) => el('tr', {},
        el('td', { class: 'nowrap muted', title: formatTime(t.time) }, formatTime(t.time)),
        el('td', { class: `nowrap ${t.amount > 0 ? 'credit-plus' : 'credit-minus'}` },
          `${t.amount > 0 ? '+' : '−'}${micro(Math.abs(t.amount), mc)}`),
        el('td', { class: 'nowrap' }, micro(t.balance, mc)),
        el('td', { class: 'detail' }, TRANSACTION_KINDS[t.kind] || t.kind, t.detail ? `: ${t.detail}` : '',
          t.actor ? ` — ${t.actor}` : ''))))))
    : emptyCard(`Hasn't sold anything yet.`);

  const advancements = c.advancements.length ? el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
    el('thead', {}, el('tr', {}, ['Advancement', 'Type', 'Reward', 'Server', 'When'].map((h) => el('th', {}, h)))),
    el('tbody', {}, c.advancements.map((a) => {
      const [frame, tone] = FRAMES[a.frame] || [a.frame, ''];
      return el('tr', {},
        el('td', { class: 'detail', title: a.key }, a.title),
        el('td', {}, el('span', { class: `badge ${tone}` }, frame)),
        el('td', { class: 'nowrap' }, a.credits ? `+${credits(a.credits, currency)}` : '—'),
        el('td', {}, a.server),
        el('td', { class: 'nowrap muted' }, formatTime(a.earnedAt)));
    }))))
    : emptyCard('No advancements made since DiscordLinkedFAD was installed.');

  return [
    el('h2', { class: 'section-title' }, `${currency.many} & interactions`,
      staff && el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => creditsDialog(p, c) },
        `Give or take ${currency.many}`)),
    summary,
    staff && el('h2', { class: 'section-title' }, `${currency.one} history`),
    ledger,
    microLedger && el('h2', { class: 'section-title' }, `${c.microCurrency.one} history`),
    microLedger,
    el('h2', { class: 'section-title' }, withCount('Advancements', c.advancements.length)),
    advancements,
  ].filter(Boolean); // the callers spread this into replaceChildren, which would print false/null
}

// ---- Community page: leaderboards and interactions ----

const MEDALS = ['🥇', '🥈', '🥉'];
const communityState = { board: stored('dl-board') || 'credits', stats: null, data: null, filter: '' };

async function loadCommunity() {
  const sub = ['interactions', 'groups'].includes(currentRoute().sub) ? currentRoute().sub : '';
  const interactions = sub === 'interactions';
  const groups = sub === 'groups';
  document.querySelectorAll('#community-subtabs a').forEach((a) => markCurrent(a, a.dataset.sub === sub));
  $('community-leaderboards').hidden = !!sub;
  $('community-interactions').hidden = !interactions;
  $('community-groups').hidden = !groups;
  try {
    const [stats, data, groupData] = await Promise.all([api('/api/community'),
      sub ? null : api(`/api/leaderboard?board=${encodeURIComponent(communityState.board)}`),
      groups ? api('/api/groups') : null]);
    communityState.stats = stats;
    if (data) communityState.data = data;
    if (groupData) groupState.data = groupData;
  } catch (e) {
    $('community-notice').replaceChildren(el('p', { class: 'empty card' }, e.message));
    return;
  }
  const d = communityState.stats;
  $('community-notice').replaceChildren(...(d.enabled ? [] : [el('div', { class: 'card panel' },
    el('p', {}, 'Community features (credits, leaderboards and interactions) are turned off.'),
    state.me.isOwner && el('a', { class: 'btn btn-small', href: '#/settings' }, 'Turn them on in Settings'))]));
  if (!d.enabled) {
    $('community-leaderboards').hidden = true;
    $('community-interactions').hidden = true;
    $('community-groups').hidden = true;
    return;
  }
  if (interactions) renderInteractions();
  else if (groups) renderGroups();
  else {
    renderCommunityStats();
    renderBoard();
  }
}

async function loadBoard() {
  try {
    communityState.data = await api(`/api/leaderboard?board=${encodeURIComponent(communityState.board)}`);
    renderBoard();
  } catch (e) {
    toast(e.message);
  }
}

function renderCommunityStats() {
  const d = communityState.stats;
  const t = d.totals;
  $('community-stats').replaceChildren(...[
    stat(`${d.currency.many} held`, t.credits.toLocaleString()),
    stat(`${d.currency.many} earned`, t.earned.toLocaleString()),
    d.market && stat(`${d.microCurrency.many} held`, t.micro.toLocaleString()),
    stat('Active playtime', playtime(t.activeSeconds)),
    stat('Advancements', t.advancements.toLocaleString()),
    stat('Interactions', [t.interactions.toLocaleString(), el('span', { class: 'stat-sub' }, `${t.interactionsToday.toLocaleString()} today`)]),
    stat(`Players with ${d.currency.many}`, t.players.toLocaleString())].filter(Boolean));
}

/** A leaderboard value in its own unit: credits, microcredits, playtime, days or a count of things. */
function boardValue(d, value) {
  switch (d.unit) {
    case 'credits': return credits(value, d.currency);
    case 'micro': return micro(value, d.microCurrency);
    case 'seconds': return playtime(value);
    case 'days': return `${value.toLocaleString()} day${value === 1 ? '' : 's'}`;
    default: {
      const noun = { advancements: 'advancement', given: 'interaction', received: 'interaction' }[d.board];
      return noun ? `${value.toLocaleString()} ${noun}${value === 1 ? '' : 's'}` : value.toLocaleString();
    }
  }
}

function renderBoard() {
  const d = communityState.data;
  if (!d) return;
  $('community-boards').replaceChildren(...d.boards.map((b) => el('button', {
    class: `board-chip ${b.key === d.board ? 'active' : ''}`, type: 'button', 'aria-pressed': String(b.key === d.board),
    onclick: () => {
      communityState.board = b.key;
      remember('dl-board', b.key);
      loadBoard();
    },
  }, b.label)));
  // On phones the chips scroll sideways: bring the chosen one into view without scrolling the page (it refreshes every minute).
  const chips = $('community-boards');
  const active = chips.querySelector('.active');
  if (active && chips.scrollWidth > chips.clientWidth) {
    chips.scrollLeft += active.getBoundingClientRect().left - chips.getBoundingClientRect().left
      - (chips.clientWidth - active.offsetWidth) / 2;
  }
  const filter = el('input', { type: 'search', placeholder: 'Find a player…', 'aria-label': 'Find a player on this board' });
  filter.value = communityState.filter;
  const body = el('div');
  const fill = () => {
    communityState.filter = filter.value;
    const q = filter.value.trim().toLowerCase();
    const ranked = d.rows.map((row, i) => [row, i + 1]);
    if (!ranked.length) {
      body.replaceChildren(el('p', { class: 'muted board-empty' }, 'Nobody is on this board yet.'));
      return;
    }
    if (q) {
      const matches = ranked.filter(([row]) => [row.name, ...(row.group?.members || []).map((m) => m.name)]
        .some((name) => name.toLowerCase().includes(q)));
      body.replaceChildren(matches.length ? el('div', { class: 'board-rows' }, matches.map(([row, rank]) => boardRow(d, row, rank)))
        : el('p', { class: 'muted board-empty' }, 'Nobody by that name on this board.'));
      return;
    }
    body.replaceChildren(...[
      el('div', { class: 'podium' }, ranked.slice(0, 3).map(([row, rank]) => podiumPlace(d, row, rank))),
      ranked.length > 3 && el('div', { class: 'board-rows' }, ranked.slice(3).map(([row, rank]) => boardRow(d, row, rank)))]
      .filter(Boolean));
  };
  filter.addEventListener('input', fill);
  fill();
  const me = d.me;
  const myGroup = me && me.rank && d.rows[me.rank - 1]?.group;
  const standing = me && (me.rank
    ? el('a', { class: 'board-me', href: myGroup ? '#/community/groups' : playerHref(me.uuid) }, myGroup
      ? `Your group ${me.name} is #${me.rank.toLocaleString()} with ${boardValue(d, me.value)}`
      : `You're #${me.rank.toLocaleString()} with ${boardValue(d, me.value)}`)
    : el('span', { class: 'board-me muted' }, 'You\'re not on this board yet.'));
  $('community-board').replaceChildren(...[
    el('div', { class: 'board-head' },
      el('h2', {}, d.label, d.board === 'micro' && el('span', { class: 'muted board-note' },
        d.ranking === 'earned' ? 'earned by selling, in total' : 'held right now')),
      d.rows.length > 0 && [standing, filter]), // an empty board only says so
    body,
    d.total > d.rows.length && el('p', { class: 'muted board-more' },
      `Showing the top ${d.rows.length} of ${d.total.toLocaleString()} ${d.board === 'micro' ? 'players and groups' : 'players'}.`)].filter(Boolean));
}

/** Whose place a board row is: a player, or a group (its members' heads, linking to the Groups tab). */
function boardEntry(d, row, rank, size) {
  const isMe = d.me && d.me.rank === rank;
  if (!row.group) {
    return { isMe, href: playerHref(row.uuid), heads: el('img', { class: 'head', src: headUrl(row.uuid, size), alt: '', loading: 'lazy' }),
      name: el('span', { class: 'name' }, row.name), online: row.online };
  }
  const members = row.group.members;
  return { isMe, href: '#/community/groups',
    heads: el('span', { class: 'group-heads', title: members.map((m) => m.name).join(', ') },
      members.slice(0, 3).map((m) => el('img', { class: 'head', src: headUrl(m.uuid, size), alt: '', loading: 'lazy' }))),
    name: el('span', { class: 'name' }, row.name, ' ', el('span', { class: 'badge group-tag' }, `${members.length} player${members.length === 1 ? '' : 's'}`)),
    online: members.some((m) => m.online) };
}

function podiumPlace(d, row, rank) {
  const e = boardEntry(d, row, rank, 96);
  return el('a', { class: `podium-place place-${rank} ${row.card ? 'has-card' : ''} ${row.group ? 'is-group' : ''} ${e.isMe ? 'is-me' : ''}`, href: e.href },
    row.card && cardBackdrop(row.card),
    el('span', { class: 'podium-medal', 'aria-label': `#${rank}` }, MEDALS[rank - 1]),
    e.heads, e.name,
    el('span', { class: 'podium-value' }, boardValue(d, row.value)),
    e.online && el('span', { class: 'pill online' }, 'Online'));
}

function boardRow(d, row, rank) {
  const e = boardEntry(d, row, rank, 48);
  return el('a', { class: `lrow ${row.card ? 'has-card' : ''} ${row.group ? 'is-group' : ''} ${e.isMe ? 'is-me' : ''}`, href: e.href },
    row.card && cardBackdrop(row.card),
    el('span', { class: 'lrow-rank' }, rank <= 3 ? MEDALS[rank - 1] : `#${rank}`),
    e.heads, e.name,
    e.online && el('span', { class: 'pill online' }, 'Online'),
    el('span', { class: 'lrow-value' }, boardValue(d, row.value)));
}

function renderInteractions() {
  const d = communityState.stats;
  const r = d.rewards;
  const person = (name, uuid) => (uuid ? el('a', { class: 'name', href: playerHref(uuid) }, name) : el('span', { class: 'name' }, name));
  const recent = el('div', { class: 'card panel' },
    el('h2', {}, 'Recent interactions'),
    d.recent.length ? el('div', { class: 'notes' }, d.recent.map((i) => el('div', { class: 'note' },
      el('p', {}, i.text),
      el('div', { class: 'meta' },
        person(i.actor, i.actorUuid), i.target ? [' → ', person(i.target, i.targetUuid)] : '',
        ` · /${i.action} · `, ({ discord: 'Discord', minecraft: 'Minecraft' })[i.source] || i.source, ' · ',
        el('span', { title: formatTime(i.time) }, timeAgo(i.time))))))
      : el('p', { class: 'muted' }, 'No interactions yet.'));
  const servers = d.servers.map((x) => `${x.name} (${x.version})`).join(', ');
  // Members only see the commands once DiscordLinkedFAD has sent them; staff are told how to get there.
  const actions = (d.synced || isStaff()) && el('div', { class: 'card panel' },
    el('h2', {}, d.synced ? withCount('Interaction commands', d.actions.length) : 'Interaction commands'),
    d.synced
      ? el('div', { class: 'others' }, d.actions.map((a) => el('span', {
        class: 'chip', title: `${a.description} · ${a.lines} lines · ${a.target === 'required' ? 'needs a player' : a.target === 'optional' ? 'player optional' : 'no player'}`,
      }, `/${a.name} · ${a.uses.toLocaleString()}`)))
      : el('p', { class: 'muted' }, d.servers.length
        ? `DiscordLinkedFAD is running on ${servers}; its commands show up here once a player joins.`
        : 'Put DiscordLinkedFAD on your Minecraft servers; its interactions show up here once a player joins one of them.'),
    d.synced && el('p', { class: 'muted' }, d.discordCommands
      ? 'These also work as slash commands in Discord (e.g. /hug @someone).'
      : isStaff() && 'Slash commands for these are turned off in Settings.'),
    d.synced && d.servers.length > 0 && el('p', { class: 'muted' }, `DiscordLinkedFAD is running on ${servers}.`),
    isStaff() && el('p', { class: 'muted' }, 'The lines, sounds and commands are set in DiscordLinkedFAD\'s config.yml on each server.'));
  const earning = el('div', { class: 'card panel' },
    el('h2', {}, `How to earn ${d.currency.many}`),
    el('ul', { class: 'market-how' },
      el('li', {}, `${credits(r.playtimeAmount, d.currency)} for every ${r.playtimeMinutes} minutes of active play`),
      el('li', {}, `${r.task} / ${r.goal} / ${credits(r.challenge, d.currency)} for each new advancement (task / goal / challenge)`),
      el('li', {}, 'A daily reward for your first join of the day, more the longer your streak')),
    state.me.isOwner && el('p', { class: 'muted' }, `Rename ${d.currency.many} or change what players earn in `,
      el('a', { href: '#/settings' }, 'Settings'), '.'));
  $('community-interactions').replaceChildren(el('div', { class: 'grid' }, recent, el('div', { class: 'stack' }, actions, earning)));
}

// ---- Community → Groups: players sharing their microcredits ----

const groupState = { data: null, logOpen: new Set() };
const GROUP_LOG_KINDS = { CREATE: 'Made', JOIN: 'Joined', LEAVE: 'Left', REMOVED: 'Removed', SALE: 'Sold', EXCHANGE: 'Exchanged',
  RENAME: 'Renamed', LEADER: 'New leader', GROUP: 'Moved' };

/** Runs a group action, shows its message and reloads the tab. */
async function groupAction(path, body = {}) {
  try {
    toast((await api(`/api/groups/${path}`, body)).message);
  } catch (e) {
    toast(e.message);
  }
  await loadCommunity();
}

/** What happened: a badge and the details, or just the details when they start with the badge's word ("Joined and brought…"). */
function groupLogWhat(e) {
  const kind = GROUP_LOG_KINDS[e.kind] || e.kind;
  const d = e.detail || '';
  if (d && d.toLowerCase().startsWith(kind.toLowerCase())) return d[0].toUpperCase() + d.slice(1);
  return [el('span', { class: 'badge' }, kind), ' ', el('span', { class: 'muted' }, d)];
}

function groupLogTable(log, cur) {
  if (!log.length) return el('p', { class: 'muted' }, 'Nothing yet.');
  return el('div', { class: 'table-wrap' }, el('table', { class: 'table table-cards' },
    el('thead', {}, el('tr', {}, ['Time', 'Who', 'What', 'Change', 'Balance'].map((h) => el('th', {}, h)))),
    el('tbody', {}, log.map((e) => el('tr', {},
      el('td', { class: 'nowrap muted', title: formatTime(e.time) }, timeAgo(e.time)),
      el('td', {}, e.uuid ? el('a', { class: 'name', href: playerHref(e.uuid) }, e.name) : e.name || '—'),
      el('td', { class: 'detail' }, groupLogWhat(e)),
      el('td', { class: `nowrap ${e.amount > 0 ? 'credit-plus' : e.amount < 0 ? 'credit-minus' : 'muted'}` },
        e.amount ? `${e.amount > 0 ? '+' : '−'}${micro(Math.abs(e.amount), cur)}` : '—'),
      el('td', { class: 'nowrap' }, micro(e.balance, cur)))))));
}

function memberHeads(members, size = 32) {
  return el('span', { class: 'group-heads' }, members.slice(0, 5).map((m) =>
    el('img', { class: 'head', src: headUrl(m.uuid, size), alt: '', title: m.name, loading: 'lazy' })));
}

function renderGroups() {
  const d = groupState.data;
  const cur = d.microCurrency;
  const parts = [];
  const rules = el('p', { class: 'muted group-rules' }, `Members share their ${cur.many}: whatever they earn by selling goes into the `
    + `group, any member can exchange it, and the leader sees who earned and spent what. Joining brings your ${cur.many} in; `
    + 'leaving (or being removed) takes an equal share out; if the group ends, it\'s split equally. You can be in one group.');
  const mine = d.mine;
  if (!d.linked) {
    parts.push(el('div', { class: 'card panel' }, el('h2', {}, 'Your group'),
      el('p', { class: 'muted' }, 'Link your Minecraft account to make or join a group (/link in Discord).'), rules));
  } else if (!mine) {
    const name = el('input', { type: 'text', maxlength: '32', placeholder: 'Group name', 'aria-label': 'Group name', required: true });
    const create = el('form', { class: 'group-create', onsubmit: (e) => { e.preventDefault(); groupAction('create', { name: name.value }); } },
      name, el('button', { class: 'btn', type: 'submit', disabled: !d.open }, 'Make a group'));
    parts.push(el('div', { class: 'card panel' }, el('h2', {}, 'Your group'),
      d.invites.length > 0 && el('div', { class: 'group-invites' }, d.invites.map((i) => el('div', { class: 'group-invite' },
        el('span', {}, el('b', {}, i.by), ' invited you to ', el('b', {}, i.name), el('span', { class: 'muted' }, ` · ${timeAgo(i.time)}`)),
        el('span', { class: 'actions' },
          el('button', { class: 'btn btn-small', type: 'button', disabled: !d.open, onclick: () => confirmDialog({
            title: `Join ${i.name}?`, text: `Your ${cur.many} go into the group and you share everything you earn from now on.`,
            confirmLabel: 'Join', onConfirm: () => groupAction('accept', { id: i.id }) }) }, 'Join'),
          el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => groupAction('decline', { id: i.id }) }, 'Decline'))))),
      d.open ? create : el('p', { class: 'muted' }, 'Groups are switched off right now.'), rules));
  } else {
    const leader = mine.isLeader;
    const members = el('div', { class: 'group-members' }, mine.members.map((m) => el('div', { class: 'group-member' },
      el('img', { class: 'head', src: headUrl(m.uuid, 48), alt: '', loading: 'lazy' }),
      el('div', { class: 'group-member-text' },
        el('a', { class: 'name', href: playerHref(m.uuid) }, m.name),
        m.online && [' ', el('span', { class: 'pill online' }, 'Online')],
        el('div', { class: 'muted' }, `${m.leader ? 'Leader' : 'Member'} · joined ${shortDate(m.joinedAt)}`)),
      leader && !m.leader && el('span', { class: 'actions' },
        el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => confirmDialog({
          title: `Make ${m.name} the leader?`, text: 'They can then invite and remove members, rename or end the group, and see the log. You stay a member.',
          confirmLabel: 'Make leader', onConfirm: () => groupAction('leader', { uuid: m.uuid }) }) }, 'Make leader'),
        el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => confirmDialog({
          title: `Remove ${m.name}?`, text: `They leave with an equal share: ${micro(Math.floor(mine.balance / mine.members.length), cur)}.`,
          confirmLabel: 'Remove', confirmClass: 'btn-danger', onConfirm: () => groupAction('remove', { uuid: m.uuid }) }) }, 'Remove')))));
    const invite = leader && (() => {
      const who = el('input', { type: 'text', placeholder: 'Minecraft name', 'aria-label': 'Player to invite', required: true, list: 'group-invite-names' });
      return el('form', { class: 'group-create', onsubmit: (e) => { e.preventDefault(); groupAction('invite', { name: who.value }); } },
        who, el('button', { class: 'btn', type: 'submit', disabled: !d.open }, 'Invite'));
    })();
    const pending = leader && mine.invites.length > 0 && el('div', { class: 'group-pending muted' }, 'Invited: ',
      mine.invites.map((i) => el('span', { class: 'chip' }, i.name, ' ',
        el('button', { class: 'chip-x', type: 'button', title: 'Take back the invitation', 'aria-label': `Take back ${i.name}'s invitation`,
          onclick: () => groupAction('cancel-invite', { uuid: i.uuid }) }, '×'))));
    const rename = leader && el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => openDialog({
      title: 'Rename the group', text: 'Up to 32 characters. Names are unique.', label: 'Name', value: mine.name, maxLength: 32,
      confirmLabel: 'Rename', onConfirm: async ({ reason }) => { await api('/api/groups/rename', { name: reason }); toast('Renamed'); loadCommunity(); } }) }, 'Rename');
    const share = Math.floor(mine.balance / mine.members.length);
    const leave = leader
      ? el('button', { class: 'btn btn-danger btn-small', type: 'button', onclick: () => confirmDialog({
        title: `End ${mine.name}?`, text: `Its ${micro(mine.balance, cur)} are split equally across the ${mine.members.length} member${mine.members.length === 1 ? '' : 's'} (about ${micro(share, cur)} each).`,
        confirmLabel: 'End the group', confirmClass: 'btn-danger', onConfirm: () => groupAction('end') }) }, 'End the group')
      : el('button', { class: 'btn btn-danger btn-small', type: 'button', onclick: () => confirmDialog({
        title: `Leave ${mine.name}?`, text: `You take an equal share with you: ${micro(share, cur)}.`,
        confirmLabel: 'Leave', confirmClass: 'btn-danger', onConfirm: () => groupAction('leave') }) }, 'Leave');
    parts.push(el('div', { class: 'card panel group-mine' },
      el('div', { class: 'group-title' }, el('h2', {}, mine.name), mine.rank && el('span', { class: 'badge good' }, `#${mine.rank} on the leaderboard`),
        el('span', { class: 'actions' }, rename, leave)),
      el('div', { class: 'stats-grid' },
        stat('Shared balance', micro(mine.balance, cur)),
        stat('Members', `${mine.members.length}${d.maxMembers ? ` / ${d.maxMembers}` : ''}`)),
      members, invite, pending, rules));
    if (leader) {
      parts.push(el('h2', { class: 'section-title' }, 'Group log'),
        el('p', { class: 'muted section-help' }, 'Who earned, spent, joined and left. Only you, as the leader, see this.'),
        groupLogTable(mine.log, cur));
    }
  }
  const staff = isStaff();
  parts.push(el('h2', { class: 'section-title' }, withCount('All groups', d.groups.length)),
    d.groups.length ? el('div', { class: 'group-grid' }, d.groups.map((g) => {
      const log = el('div', { class: 'group-log', hidden: !groupState.logOpen.has(g.id) });
      const loadLog = async () => {
        log.replaceChildren(el('p', { class: 'muted' }, 'Loading…'));
        try {
          log.replaceChildren(groupLogTable((await api(`/api/groups/log?id=${g.id}`)).log, cur));
        } catch (e) {
          log.replaceChildren(el('p', { class: 'muted' }, e.message));
        }
      };
      if (groupState.logOpen.has(g.id)) loadLog();
      const open = groupState.logOpen.has(g.id);
      const logBtn = el('button', { class: 'btn btn-ghost btn-small', type: 'button', 'aria-expanded': String(open), onclick: () => {
        if (groupState.logOpen.has(g.id)) groupState.logOpen.delete(g.id); else groupState.logOpen.add(g.id);
        log.hidden = !groupState.logOpen.has(g.id);
        if (!log.hidden) loadLog();
        logBtn.textContent = log.hidden ? 'Log' : 'Hide log';
        logBtn.setAttribute('aria-expanded', String(!log.hidden));
      } }, open ? 'Hide log' : 'Log');
      return el('div', { class: `card panel group-card ${mine && mine.id === g.id ? 'is-me' : ''}` },
        el('div', { class: 'group-title' }, el('h3', {}, g.name),
          g.rank && el('span', { class: 'badge good', title: `#${g.rank} on the ${cur.many} leaderboard` }, `#${g.rank}`)),
        el('div', { class: 'group-card-row' }, memberHeads(g.members),
          el('span', { class: 'muted' }, `${g.members.length} player${g.members.length === 1 ? '' : 's'}: `
            + g.members.map((m) => (m.leader ? `${m.name} (leader)` : m.name)).join(', '))),
        el('div', { class: 'group-card-row' }, el('b', {}, micro(g.balance, cur)), el('span', { class: 'muted' }, ' shared')),
        staff && el('div', { class: 'actions' },
          logBtn,
          el('button', { class: 'btn btn-danger btn-small', type: 'button', onclick: () => openDialog({
            title: `Take down ${g.name}?`, text: `The group ends and its ${micro(g.balance, cur)} are split equally across its ${g.members.length} member${g.members.length === 1 ? '' : 's'}. Members are told why.`,
            confirmLabel: 'Take down', confirmClass: 'btn-danger',
            onConfirm: async ({ reason }) => { toast((await api('/api/groups/takedown', { id: g.id, reason })).message); loadCommunity(); } }) }, 'Take down')),
        staff && log);
    })) : el('p', { class: 'muted' }, 'No groups yet.'));
  $('community-groups').replaceChildren(...parts.filter(Boolean));
}

// ---- Moderate page ----

async function loadModerate() {
  try {
    state.online = await api('/api/online');
    renderModerate();
  } catch (e) {
    toast(e.message);
  }
}

function renderModerate() {
  const data = state.online;
  if (!data) return;
  const select = $('moderate-server');
  const servers = [...new Set(data.players.map((p) => p.server).filter(Boolean))].sort();
  const chosen = select.value;
  select.replaceChildren(el('option', { value: '' }, 'All servers'), ...servers.map((s) => el('option', { value: s }, s)));
  select.value = servers.includes(chosen) ? chosen : '';

  const self = $('moderate-self');
  const selfText = {
    online: `Spy uses your Minecraft account ${data.selfName}. You'll be put in spectator and must stay within ${data.maxDistance} blocks.`,
    offline: `Join the server as ${data.selfName} to use Spy.`,
    unlinked: 'Spy needs your Discord account linked to a Minecraft account. Link it in-game first.',
  }[data.selfStatus];
  self.textContent = selfText;
  self.className = `self-status ${data.selfStatus === 'online' ? '' : 'warn'}`;

  const query = $('moderate-search').value.trim().toLowerCase();
  const players = data.players
    .filter((p) => !select.value || p.server === select.value)
    .filter((p) => !query || [p.minecraftName, p.discordName || ''].some((v) => v.toLowerCase().includes(query)))
    .sort(SORTS['name-asc']);
  $('moderate-count').textContent = `(${data.players.length})`;
  $('moderate-grid').replaceChildren(...players.map((p) => moderateCard(p, data.selfStatus === 'online')));
  const empty = $('moderate-empty');
  empty.textContent = data.players.length ? 'No players match your filter.' : 'Nobody is online right now.';
  empty.hidden = players.length > 0;
}

function moderateCard(p, canSpy) {
  const spyTitle = p.self ? "That's you" : canSpy ? `Spectate ${p.minecraftName}` : 'Join the server with your linked account first';
  const small = (cls, label, handler, extra = {}) =>
    el('button', { class: `btn ${cls} btn-small`, type: 'button', onclick: handler, ...extra }, label);
  return el('div', { class: 'card mod-card' },
    el('div', { class: 'mod-top' },
      el('a', { href: playerHref(p.uuid) }, el('img', { class: 'head mod-head', src: headUrl(p.uuid, 112), alt: '', loading: 'lazy' })),
      el('div', { class: 'mod-id' },
        playerName(p),
        el('div', { class: 'sub' }, p.linked ? discordLink(p, p.discordName) : 'Not linked'),
        el('div', { class: 'mod-meta' },
          el('span', { class: 'pill online' }, p.server || 'Connecting'),
          el('span', { class: 'muted mono' }, `${p.ping} ms`)))),
    p.suspension && el('span', { class: 'pill suspended' }, `Suspended · ${formatRemaining(p.suspension.expiresAt)} left`),
    el('div', { class: 'actions mod-actions' },
      small('btn-spy', 'Spy', () => startSpy(p), { disabled: p.self || !canSpy, title: spyTitle }),
      small('btn-ghost', 'Kick', () => kickDialog(p)),
      small('btn-ghost', 'Inventory', () => { location.hash = `#/player/${p.uuid}/inventory`; }),
      p.suspension
        ? small('btn-success', 'Unsuspend', () => unsuspendDialog(p))
        : small('btn-warning', 'Suspend', () => suspendDialog(p))));
}

$('moderate-search').addEventListener('input', renderModerate);
$('moderate-server').addEventListener('change', renderModerate);

// ---- Spy sessions: while one runs, the whole site is locked behind this panel ----

let spyTimer = null;

async function startSpy(p) {
  try {
    await api('/api/spy/start', { uuid: p.uuid });
    pollSpy();
  } catch (e) {
    toast(e.message);
  }
}

async function pollSpy() {
  clearTimeout(spyTimer);
  if (!isStaff()) return;
  let data;
  try {
    data = await api('/api/spy');
  } catch (e) {
    spyTimer = setTimeout(pollSpy, 3000);
    return;
  }
  if (data.ended) {
    state.spy = null;
    renderSpyEnded(data.ended);
    return;
  }
  state.spy = data.session;
  if (data.session) {
    renderSpyLock(data.session);
    spyTimer = setTimeout(pollSpy, 1000);
  } else if (!$('spy-lock').hidden && !state.spyEndedShown) {
    unlock();
  }
}

function lock() {
  $('spy-lock').hidden = false;
  $('topbar').inert = true;
  document.querySelector('main').inert = true;
}

function unlock() {
  state.spyEndedShown = false;
  $('spy-lock').hidden = true;
  $('topbar').inert = false;
  document.querySelector('main').inert = false;
  refreshCurrentView();
}

/** Adds a full stop unless the text already ends with punctuation. */
const sentence = (text) => (/[.!?]$/.test(text) ? text : `${text}.`);

function clock(seconds) {
  const m = Math.floor(seconds / 60), s = seconds % 60;
  return seconds >= 3600 ? `${Math.floor(seconds / 3600)}:${String(m % 60).padStart(2, '0')}:${String(s).padStart(2, '0')}`
    : `${m}:${String(s).padStart(2, '0')}`;
}

function renderSpyLock(s) {
  lock();
  const t = s.target;
  const starting = s.state === 'starting';
  const pct = s.distance >= 0 ? Math.min(100, (s.distance / s.maxDistance) * 100) : 0;
  const observed = `"(observed via spy for …)" is added to your reason automatically.`;
  $('spy-card').replaceChildren(...[
    el('div', { class: 'spy-eyebrow' }, el('span', { class: `live-dot ${starting ? 'pending' : ''}` }),
      starting ? 'Starting spy session…' : 'Spy session running'),
    el('img', { class: 'head-large spy-head', src: headUrl(t.uuid, 192), alt: '' }),
    el('h2', { id: 'spy-title', class: `spy-name ${nameClass(t)}` }, t.minecraftName),
    el('p', { class: 'muted' }, starting
      ? `Moving you to ${s.server} and switching you to spectator…`
      : `You're spectating on ${s.server}. The website is locked until you end the session.`),
    !starting && el('div', { class: 'spy-stats' },
      el('div', {}, el('div', { class: 'stat-label' }, 'Watching for'), el('div', { class: 'stat-value mono' }, clock(s.seconds))),
      el('div', {}, el('div', { class: 'stat-label' }, 'Distance'),
        el('div', { class: 'stat-value mono' }, s.distance >= 0 ? `${s.distance} / ${s.maxDistance}` : '…'),
        el('div', { class: `meter ${pct > 80 ? 'danger' : ''}` }, el('i', { class: 'meter-fill', 'data-pct': Math.round(pct) })))),
    el('div', { class: 'actions spy-actions' },
      el('button', { class: 'btn btn-ghost', type: 'button', onclick: endSpy }, starting ? 'Cancel' : 'End session'),
      !starting && el('button', {
        class: 'btn btn-ghost', type: 'button', onclick: () => openDialog({
          title: `Kick ${t.minecraftName}`, text: `Ends the session and kicks them. ${observed}`,
          confirmLabel: 'Kick', confirmClass: 'btn-warning',
          onConfirm: ({ reason }) => api('/api/spy/kick', { reason }),
        }),
      }, 'Kick'),
      !starting && !t.suspension && el('button', {
        class: 'btn btn-warning', type: 'button', onclick: () => openDialog({
          title: `Suspend ${t.minecraftName}`, text: `Ends the session and suspends them. ${observed}`,
          confirmLabel: 'Suspend', confirmClass: 'btn-warning', duration: { days: 1, hours: 0 },
          onConfirm: ({ reason, days, hours }) => api('/api/spy/suspend', { reason, days, hours }),
        }),
      }, 'Suspend'))].filter(Boolean));
  // CSP blocks inline styles, so the meter width is set through the CSSOM.
  const fill = $('spy-card').querySelector('.meter-fill');
  if (fill) fill.style.width = `${fill.dataset.pct}%`;
}

function renderSpyEnded(ended) {
  lock();
  state.spyEndedShown = true;
  $('spy-card').replaceChildren(
    el('div', { class: 'spy-eyebrow' }, el('span', { class: 'live-dot off' }), ended.failed ? "Spy session didn't start" : 'Spy session ended'),
    el('img', { class: 'head-large spy-head', src: headUrl(ended.uuid, 192), alt: '' }),
    el('h2', { id: 'spy-title', class: 'spy-name' }, ended.player),
    el('p', {}, ended.failed ? `Reason: ${sentence(ended.reason)}` : `Ended after ${ended.duration}: ${sentence(ended.reason)}`),
    el('p', { class: 'muted' }, ended.failed ? 'Nothing was changed in game.' : "You've been put back where you were in game."),
    el('div', { class: 'actions spy-actions' },
      el('a', { class: 'btn btn-ghost', href: playerHref(ended.uuid), onclick: unlock }, 'Open player page'),
      el('button', { class: 'btn', type: 'button', onclick: unlock }, 'Back to the website')));
}

async function endSpy() {
  try {
    await api('/api/spy/end', {});
  } catch (e) {
    toast(e.message);
  }
  pollSpy();
}

// ---- Settings (only the settings owner sees this page) ----

const settingsEdits = {};

async function loadSettings() {
  try {
    state.settings = await api('/api/settings');
  } catch (e) {
    $('settings-body').replaceChildren(el('p', { class: 'empty card' }, e.message));
    $('settings-bar').hidden = true;
    return;
  }
  for (const key of Object.keys(settingsEdits)) delete settingsEdits[key];
  try {
    state.domain = await api('/api/settings/domain');
  } catch (e) {
    state.domain = null;
  }
  renderSettings();
  watchDomain();
}

// ---- Your own address (Cloudflare) ----

let domainTimer = null;

/** While Cloudflare is being set up, keep the card up to date. */
function watchDomain() {
  clearTimeout(domainTimer);
  const d = state.domain;
  const switching = d && d.state === 'ready' && d.connectedHostname && d.publicUrl !== `https://${d.connectedHostname}`;
  if (!d || !(d.state === 'waiting' || d.state === 'working' || switching) || currentRoute().view !== 'settings') return;
  domainTimer = setTimeout(async () => {
    try {
      state.domain = await api('/api/settings/domain');
      const card = document.getElementById('domain-card');
      if (card) card.replaceWith(domainCard());
    } catch (e) { /* keep showing the last state */ }
    watchDomain();
  }, 2000);
}

function domainCard() {
  const d = state.domain;
  const card = el('div', { class: 'card panel settings-group domain-card', id: 'domain-card' }, el('h2', {}, 'Your own address'));
  if (!d) return card;
  if (!d.tunnel) {
    card.append(el('p', { class: 'muted' }, 'Your own address works with web-access=tunnel. This website uses web-access=direct, '
      + 'so its address is web-public-url in config.properties.'));
    return card;
  }
  const prefix = el('input', { type: 'text', value: d.prefix ?? 'dla', placeholder: 'dla', 'aria-label': 'Before your domain', class: 'domain-prefix', spellcheck: 'false' });
  const name = el('input', { type: 'text', value: d.domain || '', placeholder: 'example.com', 'aria-label': 'Your domain', class: 'domain-name', spellcheck: 'false' });
  const preview = el('div', { class: 'domain-preview mono' });
  const update = () => {
    const host = [prefix.value.trim().toLowerCase(), name.value.trim().toLowerCase()].filter(Boolean).join('.');
    preview.textContent = name.value.trim() ? `https://${host}` : 'https://dla.example.com';
  };
  prefix.addEventListener('input', update);
  name.addEventListener('input', update);
  update();
  const connected = d.state === 'ready' && d.connectedHostname;
  const live = connected && d.publicUrl === `https://${d.connectedHostname}`;
  const connect = el('button', { class: 'btn btn-small', type: 'button', onclick: async (e) => {
    const button = e.currentTarget;
    button.disabled = true;
    button.textContent = 'Starting…';
    try {
      state.domain = await api('/api/settings/domain/connect', { prefix: prefix.value, domain: name.value });
    } catch (err) {
      toast(err.message);
    }
    card.replaceWith(domainCard());
    watchDomain();
  } }, connected ? 'Change address' : d.tokenMode ? 'Use this address' : 'Connect with Cloudflare');
  const busy = d.state === 'waiting' || d.state === 'working';
  connect.disabled = busy;
  if (busy) connect.textContent = d.state === 'waiting' ? 'Waiting for Cloudflare…' : 'Setting up…';

  card.append(
    el('p', { class: 'muted' }, 'Put this website on your own domain, like dla.example.com, instead of an address that changes '
      + 'on every restart. The domain\'s DNS has to be on Cloudflare (the free plan is fine). You choose the part before the domain.'),
    el('div', { class: 'domain-fields' }, prefix, el('span', { class: 'domain-dot' }, '.'), name),
    preview);

  if (d.tokenMode) {
    card.append(el('p', { class: 'muted' }, 'Using the tunnel token from config.properties: the address itself is set in the Cloudflare '
      + 'dashboard (Zero Trust → Networks → Tunnels → Public hostname), so make sure it matches.'));
  }
  if (d.state === 'waiting') {
    card.append(el('div', { class: 'domain-step' },
      d.loginUrl
        ? [el('a', { class: 'btn', href: d.loginUrl, target: '_blank', rel: 'noopener' }, 'Open Cloudflare and authorize ↗'),
          el('p', { class: 'muted' }, `Log in, pick ${d.domain || 'your domain'} and click Authorize. This page continues by itself.`)]
        : el('p', { class: 'muted shimmer' }, 'Asking Cloudflare for a login link…')));
  } else if (d.state === 'working') {
    card.append(el('p', { class: 'domain-step shimmer' }, d.message || 'Setting things up…'));
  } else if (connected) {
    card.append(el('div', { class: 'domain-step' }, live
      ? [el('p', {}, el('span', { class: 'badge good' }, 'Live'), ' ', el('a', { href: `https://${d.connectedHostname}`, target: '_blank', rel: 'noopener' },
        `https://${d.connectedHostname}`)),
      location.host !== d.connectedHostname && el('button', { class: 'btn btn-small', type: 'button', onclick: async () => {
        try {
          const { link } = await api('/api/settings/domain/login-link', {});
          location.href = link;
        } catch (err) { toast(err.message); }
      } }, 'Open the new address'),
      location.host !== d.connectedHostname && el('p', { class: 'muted' }, 'This address keeps working for about a minute and a half; '
        + 'after that use the new one. /dla links use it from now on.')]
      : el('p', { class: 'muted shimmer' }, `Connecting https://${d.connectedHostname}…`)));
  }
  if (live) {
    card.append(el('div', { class: 'domain-login' },
      el('div', { class: 'setting-label' }, 'Log in with Discord',
        ' ', el('span', { class: `badge ${d.discordLogin ? 'good' : 'warn'}` }, d.discordLogin ? 'On' : 'Off')),
      d.discordLogin
        ? el('p', { class: 'muted' }, 'The login page has a Log in with Discord button. Its redirect, listed in Discord\'s Developer Portal '
          + '→ your app → OAuth2 → Redirects, must be:')
        : el('p', { class: 'muted' }, d.clientSecretSet
          ? 'Restart the proxy to turn the button on. Discord\'s Developer Portal → your app → OAuth2 → Redirects must list:'
          : 'For a Log in with Discord button: copy the Client Secret from Discord\'s Developer Portal → your app → OAuth2 into '
            + 'discord-client-secret in config.properties, add this redirect there, and restart the proxy:'),
      copyButton(`https://${d.connectedHostname}/callback`, 'Redirect')));
  }
  if (d.message && d.state !== 'working') card.append(el('p', { class: 'error' }, d.message));
  card.append(el('div', { class: 'actions inv-start' }, connect,
    connected && !d.tokenMode && el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: async (e) => {
      if (!confirm('Remove your own address? The website goes back to an automatic address that changes on restart.')) return;
      e.currentTarget.disabled = true;
      try {
        state.domain = await api('/api/settings/domain/disconnect', {});
        toast('Disconnected. Run /dla for a link to the new address.');
      } catch (err) { toast(err.message); }
      card.replaceWith(domainCard());
    } }, 'Disconnect')));
  return card;
}

const formatBytes = (n) => (n >= 1 << 30 ? `${(n / (1 << 30)).toFixed(2)} GB` : n >= 1 << 20 ? `${(n / (1 << 20)).toFixed(1)} MB`
  : n >= 1024 ? `${Math.round(n / 1024)} KB` : `${n} B`);

function settingInput(field) {
  const current = field.key in settingsEdits ? settingsEdits[field.key] : field.value;
  const onChange = (value) => {
    if (value === field.value) delete settingsEdits[field.key];
    else settingsEdits[field.key] = value;
    updateSettingsBar();
    document.querySelector(`[data-setting="${field.key}"]`)?.classList.toggle('changed', field.key in settingsEdits);
  };
  if (field.type === 'boolean') {
    const box = el('input', { type: 'checkbox', role: 'switch', 'aria-label': field.label });
    box.checked = current === 'true';
    box.addEventListener('change', () => onChange(box.checked ? 'true' : 'false'));
    return el('label', { class: 'switch' }, box, el('span', { class: 'switch-track' }));
  }
  if (field.type === 'choice') {
    const select = el('select', { 'aria-label': field.label }, field.choices.map((c) => el('option', { value: c }, c)));
    select.value = current;
    select.addEventListener('change', () => onChange(select.value));
    return select;
  }
  const input = el('input', {
    type: field.type === 'number' ? 'number' : 'text', 'aria-label': field.label, value: current,
    min: field.type === 'number' ? field.min : null, max: field.type === 'number' ? field.max : null,
    placeholder: field.type === 'discord_id' ? 'Empty' : field.type === 'discord_ids' ? 'Empty = chat channel' : null,
    inputmode: field.type === 'discord_id' ? 'numeric' : null,
    spellcheck: 'false',
  });
  input.addEventListener('input', () => onChange(input.value.trim()));
  return input;
}

function renderSettings() {
  const data = state.settings;
  $('settings-owner-note').textContent = data.ownerIsDefault
    ? 'Only you (the Discord server owner) can see this page.' : 'Only you (the settings owner) can see this page.';
  const groups = data.groups.map((group) => el('div', { class: 'card panel settings-group' },
    el('h2', {}, group.name),
    group.fields.map((field) => el('div', { class: `setting ${field.key in settingsEdits ? 'changed' : ''}`, 'data-setting': field.key },
      el('div', { class: 'setting-text' },
        el('div', { class: 'setting-label' }, field.label, field.restart && el('span', { class: 'badge warn' }, 'needs restart')),
        el('div', { class: 'setting-help' }, field.help),
        el('div', { class: 'setting-key mono' }, field.key)),
      el('div', { class: 'setting-input' }, settingInput(field)))),
    // The cosmetics themselves (prefixes, trails, cards) are edited in the shop; link straight to it.
    group.fields.some((f) => f.key === 'cosmetics-enabled') && el('div', { class: 'setting' },
      el('div', { class: 'setting-text' },
        el('div', { class: 'setting-label' }, 'Prefixes, trails, auras, profile cards and KeepInventory'),
        el('div', { class: 'setting-help' }, 'Add new ones, rename them, change their text or price, take them off sale or delete them (buyers get their credits back).')),
      el('div', { class: 'setting-input' }, el('a', { class: 'btn btn-ghost btn-small', href: '#/shop/cosmetics',
        onclick: () => { cos.edit = true; } }, 'Edit cosmetics')))));

  const secret = (label, set, hint) => el('div', { class: 'setting' },
    el('div', { class: 'setting-text' }, el('div', { class: 'setting-label' }, label), el('div', { class: 'setting-help' }, hint)),
    el('span', { class: `badge ${set ? 'good' : 'warn'}` }, set ? 'Set' : 'Not set'));
  const secrets = el('div', { class: 'card panel settings-group' },
    el('h2', {}, 'Secrets'),
    el('p', { class: 'muted' }, 'For safety these can only be changed in plugins/discordlinked/config.properties.'),
    secret('Bot token', data.secrets.botToken, 'bot-token'),
    secret('Discord client secret', data.secrets.clientSecret, 'discord-client-secret (optional, for the Log in with Discord button)'),
    secret('Google service account key', data.secrets.googleKey, 'the key file for Google Sheets export (optional)'),
    secret('Cloudflare tunnel token', data.secrets.tunnelToken, 'web-tunnel-token (optional: a tunnel made in the Cloudflare dashboard)'));

  const d = data.data;
  const dataCard = el('div', { class: 'card panel settings-group' },
    el('h2', {}, 'Data'),
    el('div', { class: 'stats-grid' },
      stat('Database size', formatBytes(d.databaseBytes)),
      stat('Linked players', d.linkedPlayers.toLocaleString()),
      stat('Log entries', [d.logEntries.toLocaleString(), el('span', { class: 'stat-sub' }, `${d.chatEntries.toLocaleString()} chat/commands`)]),
      stat('Inventory snapshots', [d.snapshots.toLocaleString(), el('span', { class: 'stat-sub' }, formatBytes(d.snapshotBytes))]),
      stat('Staff notes', d.notes.toLocaleString())),
    el('p', { class: 'muted' }, 'Cleanup runs automatically every hour using the retention settings above.'),
    el('div', { class: 'actions inv-start' },
      el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: (e) => settingsAction(e.currentTarget, 'cleanup') }, 'Clean up now'),
      el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: (e) => settingsAction(e.currentTarget, 'compact') }, 'Compact database')));

  $('settings-body').replaceChildren(el('div', { class: 'settings-grid' }, domainCard(), ...groups, secrets, dataCard));
  updateSettingsBar();
}

function updateSettingsBar() {
  const count = Object.keys(settingsEdits).length;
  $('settings-bar').hidden = count === 0;
  $('settings-bar-text').textContent = `${count} unsaved change${count === 1 ? '' : 's'}`;
}

async function settingsAction(button, action) {
  button.disabled = true;
  try {
    const result = await api(`/api/settings/${action}`, {});
    state.settings.data = result.data;
    toast(action === 'cleanup' ? `Cleanup done: ${result.summary}.` : `Database compacted, ${formatBytes(result.freed)} freed.`);
    renderSettings();
  } catch (e) {
    toast(e.message);
  } finally {
    button.disabled = false;
  }
}

$('settings-discard').addEventListener('click', () => {
  for (const key of Object.keys(settingsEdits)) delete settingsEdits[key];
  renderSettings();
});

$('settings-save').addEventListener('click', async () => {
  const ownerChange = 'web-owner-id' in settingsEdits;
  if (ownerChange && !confirm('Changing the settings owner hands this page to that Discord user right away. Continue?')) return;
  const button = $('settings-save');
  button.disabled = true;
  try {
    const result = await api('/api/settings', { changes: { ...settingsEdits } });
    toast(result.needRestart.length
      ? `Saved. Restart the proxy for: ${result.needRestart.join(', ')}.`
      : `Saved ${result.changed.length} setting${result.changed.length === 1 ? '' : 's'}.`);
    if (ownerChange || 'fad-enabled' in settingsEdits || 'shop-enabled' in settingsEdits) {
      state.me = await api('/api/me');
      applyMode();
    }
    await loadSettings();
  } catch (e) {
    toast(e.message);
  } finally {
    button.disabled = false;
  }
});

// ---- Live updates: one event stream for every page ----

// Events that can change what the list/player pages show.
// ---- Shop: Minecraft items for credits, delivered as a chest ----

/** The creative inventory's tabs, each with the item shown on its tab. */
const SHOP_TABS = [['Building Blocks', 'bricks'], ['Colored Blocks', 'cyan_wool'], ['Natural Blocks', 'grass_block'],
  ['Functional Blocks', 'crafting_table'], ['Redstone', 'redstone'], ['Tools & Utilities', 'iron_pickaxe'],
  ['Combat', 'golden_sword'], ['Food & Drinks', 'golden_apple'], ['Ingredients', 'iron_ingot']];
const creditFormat = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
/** Prices are kept in hundredths of a credit. */
const fromCents = (cents) => creditFormat.format(cents / 100);
const shop = { data: null, tab: SHOP_TABS[0][0], chest: [], textures: new Map(), hovered: null, edit: false, busy: false };
let shiftHeld = false;

async function loadShop() {
  let data;
  try {
    data = await api('/api/shop');
  } catch (e) {
    $('shop-notice').replaceChildren(el('p', { class: 'empty card' }, e.message));
    return;
  }
  const first = !shop.data;
  shop.data = data;
  shop.byId = new Map(data.items.map((item) => [item.id, item]));
  if (shop.chest.length !== data.slots) shop.chest = Array.from({ length: data.slots }, (_, i) => shop.chest[i] || null);
  // Items that are no longer for sale leave the chest.
  shop.chest = shop.chest.map((slot) => (slot && shop.byId.has(slot.id) && !shop.byId.get(slot.id).disabled ? slot : null));
  if (first || !shop.textures.size) shop.textures = await loadTextures('99');
  renderShop();
}

const shopName = (id) => shop.textures.get(`minecraft:${id}`)?.readable || titleCase(id);

function shopIcon(id) {
  const texture = shop.textures.get(`minecraft:${id}`)?.texture;
  if (!texture) return el('span', { class: 'slot-text' }, shopName(id).split(' ').map((w) => w[0]).join('').slice(0, 3));
  const img = el('img', { src: `${TEXTURES}/assets/${texture}`, alt: '', loading: 'lazy', draggable: 'false' });
  img.addEventListener('error', () => img.replaceWith(el('span', { class: 'slot-text' }, shopName(id).slice(0, 3))));
  return img;
}

function renderShop() {
  const d = shop.data;
  const b = d.buyer;
  $('shop-balance').textContent = b.linked ? `You have ${credits(b.balance, d.currency)}` : '';
  const notices = [];
  if (!d.open) {
    notices.push(el('div', { class: 'card panel shop-closed' }, d.manage
      ? ['The shop is closed for players. Open it in ', el('a', { href: '#/settings' }, 'Settings'), '.']
      : 'The shop is closed right now.'));
  }
  if (d.manage) {
    const toggle = el('button', { class: `btn btn-small ${shop.edit ? '' : 'btn-ghost'}`, type: 'button',
      onclick: () => { shop.edit = !shop.edit; renderShop(); } }, shop.edit ? 'Done editing' : 'Edit items');
    notices.push(el('div', { class: 'card panel shop-manage' },
      el('span', {}, `Prices are at ${d.percent}% (change it in `, el('a', { href: '#/settings' }, 'Settings'), '). ',
        shop.edit ? 'Click an item to turn it off or change its price.' : ''),
      toggle));
  }
  if (!b.linked) notices.push(el('p', { class: 'card panel muted' }, 'Link your Minecraft account to buy things (/link in Discord).'));
  else if (!b.online) notices.push(el('p', { class: 'card panel muted' }, `Join the Minecraft server to check out: the chest goes straight into ${b.name}'s inventory.`));
  else if (!b.serverReady) notices.push(el('p', { class: 'card panel muted' }, `${b.server} can't deliver orders. Move to another server to check out.`));
  $('shop-notice').replaceChildren(...notices);

  const shopTab = $('shop-search').value.trim() ? null : shop.tab; // searching shows every category
  $('shop-tabs').replaceChildren(...SHOP_TABS.map(([tab, icon]) => el('button', {
    class: `shop-tab ${tab === shopTab ? 'active' : ''}`, type: 'button', 'aria-pressed': String(tab === shopTab),
    title: tab, 'aria-label': tab, onclick: () => { shop.tab = tab; $('shop-search').value = ''; renderShop(); },
  }, shopIcon(icon), el('span', {}, tab))));
  renderShopGrid();
  renderGiftChoice();
  renderChest();
}

function renderShopGrid() {
  const query = $('shop-search').value.trim().toLowerCase().replace(/\s+/g, ' ');
  const items = shop.data.items.filter((item) => (query
    ? shopName(item.id).toLowerCase().includes(query) || item.id.includes(query.replace(/ /g, '_'))
    : item.tab === shop.tab));
  $('shop-grid').replaceChildren(...(items.length ? items.map(shopItemNode)
    : [el('p', { class: 'muted shop-empty' }, query ? 'No items match.' : 'Nothing for sale here.')]));
}

/**
 * An item in the shop's creative-style list. Like Minecraft's creative tabs: left-click picks one up (again for
 * one more), right- or middle-click picks up a full stack, shift-click sends a stack straight to the chest.
 */
function shopItemNode(item) {
  const node = el('button', {
    class: `slot shop-item ${item.disabled ? 'off' : ''} ${item.unavailable ? 'unavailable' : ''}`, type: 'button', role: 'listitem',
    'aria-label': `${shopName(item.id)}, ${fromCents(item.price)} each`,
  }, shopIcon(item.id));
  const show = () => {
    shop.hovered = item;
    if (!shop.cursor) showShopTooltip(node, item);
    if (!shop.cursor) renderShopInfo(item);
  };
  node.addEventListener('mouseenter', show);
  node.addEventListener('focus', show);
  node.addEventListener('mouseleave', () => { shop.hovered = null; hideTooltip(); });
  node.addEventListener('blur', () => { shop.hovered = null; hideTooltip(); });
  node.addEventListener('pointerdown', (e) => {
    // Touch picks on tap (click), so scrolling the list doesn't pick things up.
    if (e.pointerType !== 'mouse' || e.button > 2) return;
    e.preventDefault();
    e.shopHandled = true;
    hideTooltip();
    if (shop.edit) {
      if (e.button === 0) shopItemDialog(item);
      return;
    }
    if (item.disabled) return;
    if (e.button === 0 && e.shiftKey) addToChest(item, item.stack);
    else pickFromShop(item, e.button !== 0);
    shop.press = { from: 'shop' };
  });
  node.addEventListener('click', (e) => {
    if (shop.edit) {
      if (e.detail === 0 || e.pointerType !== 'mouse') shopItemDialog(item);
      return;
    }
    if (item.disabled) return;
    renderShopInfo(item);
    if (e.detail === 0) addToChest(item, 1); // keyboard: Enter / Space
    else if (lastPointerType !== 'mouse') pickFromShop(item, false);
  });
  return node;
}

function pickFromShop(item, fullStack) {
  if (fullStack) setCursor({ id: item.id, amount: item.stack });
  else if (shop.cursor?.id === item.id) setCursor({ id: item.id, amount: Math.min(item.stack, shop.cursor.amount + 1) });
  else setCursor({ id: item.id, amount: 1 });
}

const perStackLabel = (item) => (item.stack === 64 ? 'per 64 (stack)' : `per ${item.stack} (stack)`);

function showShopTooltip(anchor, item) {
  tooltip ||= document.body.appendChild(el('div', { class: 'mc-tooltip', role: 'tooltip' }));
  const d = shop.data;
  const lines = [el('div', { class: 'tt-name' }, shopName(item.id))];
  if (item.stack === 1) {
    lines.push(el('div', { class: 'tt-price' }, `${fromCents(item.price)} ${d.currency.many} each`));
    lines.push(el('div', { class: 'tt-meta' }, "Doesn't stack"));
  } else if (shiftHeld) {
    lines.push(el('div', { class: 'tt-price' }, `${fromCents(item.price * item.stack)} ${d.currency.many} ${perStackLabel(item)}`));
    lines.push(el('div', { class: 'tt-meta' }, `${fromCents(item.price)} each`));
  } else {
    lines.push(el('div', { class: 'tt-price' }, `${fromCents(item.price)} ${d.currency.many} each`));
    lines.push(el('div', { class: 'tt-meta' }, `Hold Shift: price ${perStackLabel(item)}`));
  }
  if (item.disabled) lines.push(el('div', { class: 'tt-lore' }, 'Not for sale (only you see it)'));
  if (item.unavailable) lines.push(el('div', { class: 'tt-lore' }, "Not in your servers' Minecraft version"));
  if (d.manage && item.percent !== 100) lines.push(el('div', { class: 'tt-lore' }, `Priced at ${item.percent}%`));
  tooltip.replaceChildren(...lines);
  tooltip.hidden = false;
  const r = anchor.getBoundingClientRect();
  tooltip.style.left = `${Math.max(8, Math.min(window.innerWidth - tooltip.offsetWidth - 8, r.right + 8))}px`;
  tooltip.style.top = `${Math.max(8, Math.min(window.innerHeight - tooltip.offsetHeight - 8, r.top))}px`;
}

/** The selected item with add buttons: how phones (no hover, no shift) see prices and add stacks. */
function renderShopInfo(item) {
  if (!item) return;
  const d = shop.data;
  $('shop-info').replaceChildren(
    el('span', { class: 'shop-info-icon' }, shopIcon(item.id)),
    el('div', { class: 'shop-info-text' },
      el('strong', {}, shopName(item.id)),
      el('span', { class: 'muted' }, `${fromCents(item.price)} ${d.currency.many} each`
        + (item.stack > 1 ? ` · ${fromCents(item.price * item.stack)} ${perStackLabel(item)}` : ''))),
    !shop.edit && !item.disabled && el('div', { class: 'actions' },
      el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => addToChest(item, 1) }, '+1'),
      item.stack > 1 && el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => addToChest(item, item.stack) }, `+${item.stack}`)));
}

/** Fills matching stacks first, then empty slots. */
function addToChest(item, amount, slotIndex) {
  let left = amount;
  const fill = (i) => {
    const slot = shop.chest[i];
    if (left <= 0 || (slot && slot.id !== item.id)) return;
    const have = slot ? slot.amount : 0;
    const add = Math.min(left, item.stack - have);
    if (add <= 0) return;
    shop.chest[i] = { id: item.id, amount: have + add };
    left -= add;
  };
  if (slotIndex !== undefined) fill(slotIndex);
  shop.chest.forEach((slot, i) => { if (slot?.id === item.id) fill(i); });
  shop.chest.forEach((slot, i) => { if (!slot) fill(i); });
  if (left > 0) toast(left === amount ? 'The chest is full' : `Only ${amount - left} fitted in the chest`);
  renderChest();
}

// ---- The stack in your hand, Minecraft style ----
//
// shop.cursor is the stack being held ({ id, amount } or null); it's drawn at the mouse. In the chest:
// left-click picks up a stack / puts down all / merges / swaps; right-click picks up half / puts down one;
// holding a button and moving across slots spreads the stack evenly (left) or one per slot (right);
// double-click gathers every matching stack; shift-click sends a slot back. Escape, or clicking outside the
// chest, puts the held stack back.

let lastPointerType = 'mouse';
const stackOf = (id) => shop.byId.get(id).stack;
/** How many more of {@code id} fit in a chest slot. */
const roomFor = (slot, id) => (!slot ? stackOf(id) : slot.id === id ? stackOf(id) - slot.amount : 0);

function setCursor(stack) {
  shop.cursor = stack && stack.amount > 0 ? stack : null;
  if (shop.cursor) hideTooltip();
  renderChest();
}

function renderCursor() {
  const shown = shop.cursor && shop.cursor.amount - plannedTotal();
  let node = $('shop-cursor');
  if (!node) node = document.body.appendChild(el('div', { id: 'shop-cursor', class: 'slot shop-cursor', 'aria-hidden': 'true' }));
  if (!shop.cursor || currentRoute().view !== 'shop') {
    node.hidden = true;
    return;
  }
  node.replaceChildren(shopIcon(shop.cursor.id), shown !== 1 && el('span', { class: 'slot-count' }, shown));
  node.classList.toggle('spent', shown <= 0);
  node.hidden = false;
  moveCursor();
}

function moveCursor() {
  const node = $('shop-cursor');
  if (!node || node.hidden || !shop.pointer) return;
  node.style.transform = `translate(${shop.pointer.x}px, ${shop.pointer.y}px)`;
}

/** Index of the chest slot under a point, or null. */
function chestSlotAt(x, y) {
  const node = document.elementFromPoint(x, y)?.closest('#shop-chest .chest-slot');
  return node ? Number(node.dataset.index) : null;
}

/** While dragging across slots: how many each slot gets. */
function dragPlan() {
  const plan = new Map();
  const d = shop.drag;
  if (!d || !shop.cursor || d.slots.length < 2) return plan;
  let left = shop.cursor.amount;
  const each = d.button === 2 ? 1 : Math.floor(shop.cursor.amount / d.slots.length);
  for (const i of d.slots) {
    const add = Math.min(each, left, roomFor(shop.chest[i], shop.cursor.id));
    if (add > 0) plan.set(i, add);
    left -= add;
  }
  return plan;
}

const plannedTotal = () => [...dragPlan().values()].reduce((a, b) => a + b, 0);

/** A single click on a chest slot while holding a stack. */
function clickSlot(i, button) {
  const slot = shop.chest[i];
  const held = shop.cursor;
  if (!held) return;
  if (!slot || slot.id === held.id) {
    const move = Math.min(button === 2 ? 1 : held.amount, roomFor(slot, held.id));
    if (move <= 0) return;
    shop.chest[i] = { id: held.id, amount: (slot ? slot.amount : 0) + move };
    shop.cursor = held.amount - move > 0 ? { ...held, amount: held.amount - move } : null;
  } else {
    shop.chest[i] = held; // different items swap
    shop.cursor = slot;
  }
}

/** Double-click: pull every matching stack into the hand, up to a full stack. */
function gather(id) {
  let amount = shop.cursor ? shop.cursor.amount : 0;
  shop.chest.forEach((slot, i) => {
    if (!slot || slot.id !== id || amount >= stackOf(id)) return;
    const take = Math.min(slot.amount, stackOf(id) - amount);
    amount += take;
    shop.chest[i] = slot.amount - take > 0 ? { ...slot, amount: slot.amount - take } : null;
  });
  shop.cursor = { id, amount };
}

function chestPointerDown(e, i) {
  if (e.button > 2 || shop.busy) return;
  e.preventDefault();
  e.shopHandled = true; // the chest is redrawn below, so the document listener can't tell where this was
  hideTooltip();
  const slot = shop.chest[i];
  const now = Date.now();
  const recent = shop.lastPickup && shop.lastPickup.index === i && now - shop.lastPickup.time < 350;
  shop.lastPickup = null;
  if (e.button === 0 && e.shiftKey) {
    shop.chest[i] = null; // back to the shop
  } else if (shop.cursor && e.button === 0 && recent) {
    gather(shop.cursor.id);
  } else if (shop.cursor) {
    shop.drag = { button: e.button, slots: [i], pointerId: e.pointerId };
  } else if (slot) {
    const take = e.button === 2 ? Math.ceil(slot.amount / 2) : slot.amount;
    shop.cursor = { id: slot.id, amount: take };
    shop.chest[i] = slot.amount - take > 0 ? { ...slot, amount: slot.amount - take } : null;
    shop.lastPickup = { index: i, time: now };
    shop.press = { from: 'chest', index: i };
  }
  renderChest();
}

document.addEventListener('pointermove', (e) => {
  lastPointerType = e.pointerType;
  shop.pointer = { x: e.clientX, y: e.clientY };
  moveCursor();
  const d = shop.drag;
  if (!d || !shop.cursor) return;
  const i = chestSlotAt(e.clientX, e.clientY);
  // Like Minecraft, a drag can't cover more slots than there are items in the hand.
  if (i !== null && !d.slots.includes(i) && d.slots.length < shop.cursor.amount && roomFor(shop.chest[i], shop.cursor.id) > 0) {
    d.slots.push(i);
    renderChest();
  }
});

document.addEventListener('pointerdown', (e) => {
  lastPointerType = e.pointerType;
  shop.pointer = { x: e.clientX, y: e.clientY };
  if (e.shopHandled || !shop.cursor || currentRoute().view !== 'shop') return;
  // Clicking anywhere but the chest, the item list, the tabs, search or checkout puts the held stack back.
  if (!e.target.closest('#shop-chest .chest-slot, #shop-grid .shop-item, .shop-tabs, #shop-search, .shop-checkout, .shop-info')) {
    setCursor(null);
  }
});

document.addEventListener('pointerup', (e) => {
  if (!shop.data) return;
  const d = shop.drag;
  if (d) {
    shop.drag = null;
    if (d.slots.length === 1) {
      clickSlot(d.slots[0], d.button);
    } else {
      const plan = (() => { shop.drag = d; const p = dragPlan(); shop.drag = null; return p; })();
      let used = 0;
      for (const [i, add] of plan) {
        const slot = shop.chest[i];
        shop.chest[i] = { id: shop.cursor.id, amount: (slot ? slot.amount : 0) + add };
        used += add;
      }
      shop.cursor = shop.cursor.amount - used > 0 ? { ...shop.cursor, amount: shop.cursor.amount - used } : null;
    }
    renderChest();
    return;
  }
  // Pressed on an item or a slot and let go over another slot: drop it there.
  const press = shop.press;
  shop.press = null;
  if (press && shop.cursor && e.pointerType === 'mouse') {
    const i = chestSlotAt(e.clientX, e.clientY);
    if (i !== null && !(press.from === 'chest' && press.index === i)) {
      clickSlot(i, 0);
      shop.lastPickup = null;
      renderChest();
    }
  }
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && shop.cursor && currentRoute().view === 'shop') setCursor(null);
});
window.addEventListener('hashchange', () => { if (shop.cursor) setCursor(null); });
// Right-click is part of moving items, so no browser menu over the shop.
document.addEventListener('contextmenu', (e) => {
  if (e.target.closest('.shop-window') && !e.target.closest('input')) e.preventDefault();
});

function renderChest() {
  const plan = dragPlan();
  $('shop-chest').replaceChildren(...shop.chest.map((slot, i) => {
    const added = plan.get(i) || 0;
    const shown = added ? { id: shop.cursor.id, amount: (slot ? slot.amount : 0) + added } : slot;
    const item = shown && shop.byId.get(shown.id);
    const node = el('button', {
      class: `slot chest-slot ${shown ? '' : 'empty'} ${shop.drag?.slots.includes(i) ? 'planned' : ''}`, type: 'button',
      'data-index': i, 'aria-label': shown ? `${shown.amount} × ${shopName(shown.id)}` : 'Empty slot',
    }, shown && shopIcon(shown.id), shown && shown.amount > 1 && el('span', { class: 'slot-count' }, shown.amount));
    node.addEventListener('pointerdown', (e) => chestPointerDown(e, i));
    if (slot) {
      const show = () => { if (!shop.cursor) showShopTooltip(node, item); };
      node.addEventListener('mouseenter', show);
      node.addEventListener('focus', show);
      node.addEventListener('mouseleave', hideTooltip);
      node.addEventListener('blur', hideTooltip);
      // Scrolling over a slot adds or takes one, without picking it up.
      node.addEventListener('wheel', (e) => {
        if (shop.cursor) return;
        e.preventDefault();
        const amount = Math.max(0, Math.min(item.stack, slot.amount + (e.deltaY < 0 ? 1 : -1)));
        shop.chest[i] = amount ? { ...slot, amount } : null;
        renderChest();
      }, { passive: false });
    }
    // Keyboard: Delete / Backspace empties the slot.
    node.addEventListener('keydown', (e) => {
      if ((e.key === 'Delete' || e.key === 'Backspace') && shop.chest[i]) {
        shop.chest[i] = null;
        renderChest();
      }
    });
    return node;
  }));
  renderCursor();
  renderShopTotal();
}

/** The order as it would be checked out: the chest, plus whatever is in your hand (it goes in at checkout). */
function shopTotal() {
  const filled = shop.chest.filter(Boolean).concat(shop.cursor && !shop.drag ? [shop.cursor] : []);
  if (!filled.length) return { cents: 0, credits: 0, items: 0 };
  const items = filled.reduce((sum, slot) => sum + shop.byId.get(slot.id).price * slot.amount, 0);
  const cents = items + shop.data.chestPrice;
  return { cents, credits: Math.ceil(cents / 100), items, stacks: filled.length };
}

/** "For me" or one of the players online right now (gifts only go to players who are on). */
function renderGiftChoice() {
  const select = $('shop-gift');
  const recipients = shop.data.recipients || [];
  const chosen = recipients.some((r) => r.uuid === select.value) ? select.value : '';
  select.replaceChildren(el('option', { value: '' }, 'For me'),
    ...recipients.map((r) => el('option', { value: r.uuid }, `Gift to ${r.name}${r.server ? ` (${r.server})` : ''}`)));
  select.value = chosen;
  select.disabled = !recipients.length;
  select.title = recipients.length ? 'Send this chest to someone who is online' : 'Nobody else is online to send a gift to';
}

function renderShopTotal() {
  const d = shop.data;
  const t = shopTotal();
  const b = d.buyer;
  const giftTo = (d.recipients || []).find((r) => r.uuid === $('shop-gift').value);
  const held = shop.cursor && !shop.drag
    ? el('div', { class: 'muted shop-holding' }, `In your hand: ${shop.cursor.amount} × ${shopName(shop.cursor.id)}`
      + (lastPointerType === 'mouse' ? ' (Esc puts it back)' : ' (tap a slot to put it down)'))
    : null;
  $('shop-total').replaceChildren(...(t.stacks
    ? [el('span', { class: 'muted' }, `${t.stacks} stack${t.stacks === 1 ? '' : 's'} ${fromCents(t.items)} + chest ${fromCents(d.chestPrice)} = `),
      el('strong', {}, credits(t.credits, d.currency))]
    : [el('span', { class: 'muted' }, 'The chest is empty.')]), ...[held].filter(Boolean));
  const buy = $('shop-buy');
  const reason = !d.open ? 'The shop is closed' : !b.linked ? 'Link your Minecraft account first'
    : !t.stacks ? 'Put something in the chest first' : t.credits > b.balance ? `You need ${credits(t.credits - b.balance, d.currency)} more`
      : giftTo ? '' : !b.online ? 'Join the Minecraft server to check out' : !b.serverReady ? `${b.server} can't deliver orders` : '';
  buy.disabled = !!reason || shop.busy;
  buy.title = reason;
  buy.textContent = shop.busy ? 'Delivering…' : !t.stacks || reason ? 'Check out'
    : giftTo ? `Gift to ${giftTo.name} for ${credits(t.credits, d.currency)}` : `Buy for ${credits(t.credits, d.currency)}`;
  $('shop-clear').disabled = !t.stacks;
}

$('shop-search').addEventListener('input', () => { renderShop(); });
$('shop-gift').addEventListener('change', renderShopTotal);
$('shop-clear').addEventListener('click', () => { shop.chest = shop.chest.map(() => null); renderChest(); });
$('shop-buy').addEventListener('click', async () => {
  // Like closing an inventory: whatever is in your hand goes into the chest.
  if (shop.cursor) {
    const held = shop.cursor;
    shop.cursor = null;
    addToChest(shop.byId.get(held.id), held.amount);
  }
  const items = shop.chest.filter(Boolean).map((slot) => ({ id: slot.id, amount: slot.amount }));
  shop.busy = true;
  renderShopTotal();
  try {
    const result = await api('/api/shop/checkout', { items, giftTo: $('shop-gift').value || '' });
    shop.chest = shop.chest.map(() => null);
    toast(result.message);
  } catch (e) {
    toast(e.message);
  } finally {
    shop.busy = false;
    await loadShop();
  }
});

// Holding Shift switches the tooltip to the price of a stack.
for (const type of ['keydown', 'keyup']) {
  document.addEventListener(type, (e) => {
    if (e.key !== 'Shift' || shiftHeld === (type === 'keydown')) return;
    shiftHeld = type === 'keydown';
    if (shop.hovered && !tooltip?.hidden) {
      const node = [...document.querySelectorAll('#shop-grid .shop-item')].find((n) => n.matches(':hover, :focus'));
      if (node) showShopTooltip(node, shop.hovered);
    }
  });
}
window.addEventListener('blur', () => { shiftHeld = false; });

function shopItemDialog(item) {
  $('shop-item-title').textContent = shopName(item.id);
  $('shop-item-text').textContent = `Normally ${fromCents(Math.round(item.price * 100 / item.percent))} each at ${shop.data.percent}% prices.`;
  $('shop-item-enabled').checked = !item.disabled;
  $('shop-item-percent').value = item.percent;
  $('shop-item-sell').hidden = !shop.data.market;
  $('shop-item-sellable').checked = !item.sellDisabled;
  $('shop-item-sell-percent').value = item.sellPercent;
  const normalSell = item.sellPercent ? Math.round(item.sell * 100 / item.sellPercent) : 0;
  $('shop-item-sell-text').textContent = item.sellDisabled
    ? `Players can't sell it. At 100% it would sell for ${formatMicro(normalSell || 0, shop.data.microCurrency)} each.`
    : `Sells for ${formatMicro(item.sell, shop.data.microCurrency)} each (${shop.data.sellPercent}% of the Shop price, `
      + `times this). It never sells for more than the Shop asks.`;
  $('shop-item-error').hidden = true;
  $('shop-item-form').onsubmit = async (e) => {
    e.preventDefault();
    try {
      await api('/api/shop/item', { id: item.id, enabled: $('shop-item-enabled').checked, percent: Number($('shop-item-percent').value),
        sellEnabled: $('shop-item-sellable').checked, sellPercent: Number($('shop-item-sell-percent').value) });
      $('shop-item-dialog').close();
      await loadShop();
    } catch (err) {
      $('shop-item-error').textContent = err.message;
      $('shop-item-error').hidden = false;
    }
  };
  $('shop-item-dialog').showModal();
}
$('shop-item-cancel').addEventListener('click', () => $('shop-item-dialog').close());

// ---- Shop → Cosmetics: prefixes, trails, auras, profile cards and KeepInventory, bought once with credits ----

/** Trail particles: [name, emoji for the preview]. */
const PARTICLES = {
  dust: ['Coloured dust', '🎨'], entity_effect: ['Potion swirls', '🌀'], heart: ['Hearts', '❤️'], flame: ['Flames', '🔥'], small_flame: ['Small flames', '🕯️'], soul_fire_flame: ['Soul fire', '💙'],
  note: ['Music notes', '🎵'], end_rod: ['Sparkles', '✨'], cherry_leaves: ['Cherry petals', '🌸'], pale_oak_leaves: ['Pale leaves', '🍂'],
  snowflake: ['Snowflakes', '❄️'], enchant: ['Magic runes', '🔮'], happy_villager: ['Happy sparks', '💚'],
  angry_villager: ['Angry clouds', '💢'], glow: ['Glow', '🌟'], totem_of_undying: ['Totem', '💛'], portal: ['Portal', '🟣'],
  reverse_portal: ['Reverse portal', '🟪'], witch: ['Witch magic', '🧪'], electric_spark: ['Electric sparks', '⚡'],
  wax_on: ['Wax on', '🟠'], wax_off: ['Wax off', '⚪'], scrape: ['Scrape', '🔷'], bubble_pop: ['Bubbles', '🫧'], cloud: ['Clouds', '☁️'],
  crit: ['Crits', '✴️'], enchanted_hit: ['Enchanted crits', '💠'], dripping_honey: ['Honey', '🍯'], falling_nectar: ['Nectar', '🌼'],
  falling_spore_blossom: ['Spore blossom', '🌺'], sculk_soul: ['Sculk souls', '👻'], soul: ['Souls', '💀'], white_ash: ['White ash', '🌫️'],
  composter: ['Compost', '🌱'], firework: ['Fireworks', '🎆'], nautilus: ['Nautilus', '🐚'], dolphin: ['Dolphin', '🐬'],
  glow_squid_ink: ['Glow ink', '💎'], squid_ink: ['Ink', '⚫'], egg_crack: ['Egg crack', '🥚'],
  trial_spawner_detection: ['Trial flames', '🔥'], ominous_spawning: ['Ominous', '🕸️'], dust_plume: ['Dust', '💨'],
};
/** Particles Minecraft can colour; every other one always looks like itself. */
const COLORABLE_PARTICLES = new Set(['dust', 'entity_effect']);
const particleName = (key) => PARTICLES[key]?.[0] || titleCase(key);
const DEFAULT_PRICES = { prefix: 200, trail: 300, aura: 350, card: 250, keepinv: 750 };

// ---- Trail and aura looks: shape, animation and colour ----

const STYLE_SHAPES = [['cloud', 'Cloud'], ['ring', 'Ring at the feet'], ['halo', 'Halo'], ['orbit', 'Orbit'],
  ['helix', 'Helix'], ['wings', 'Wings']];
const STYLE_ANIMATIONS = [['still', 'Still'], ['spin', 'Spin'], ['pulse', 'Pulse (wings flap)'], ['rise', 'Rise']];
const STYLE_COLORS = [['', 'Particle\'s own'], ['rainbow', 'Rainbow'], ['#ff4d4d', 'Red'], ['#ff9f1c', 'Orange'],
  ['#ffe14d', 'Yellow'], ['#7ddc3a', 'Lime'], ['#2ecc71', 'Green'], ['#3ad6e0', 'Aqua'], ['#4d7cff', 'Blue'],
  ['#a55eea', 'Purple'], ['#ff6fb5', 'Pink'], ['#ffffff', 'White'], ['custom', 'Custom…']];
const styleLabel = (list, key) => list.find(([k]) => k === key)?.[1] || key;
/** "Halo · Spin · Pink"; nothing for a plain still cloud. */
function styleText(style) {
  if (!style || style.shape === 'cloud' && style.animation === 'still' && !style.color) return '';
  const color = !style.color ? '' : STYLE_COLORS.find(([k]) => k === style.color)?.[1] || 'Custom colour';
  return [styleLabel(STYLE_SHAPES, style.shape), styleLabel(STYLE_ANIMATIONS, style.animation).replace(/ \(.*\)/, ''), color]
    .filter(Boolean).join(' · ');
}

/**
 * Where a trail's or aura's particles are, as offsets from the feet in blocks: the same maths as
 * DiscordLinkedFAD's ParticleShapes, so the preview looks like the game. Seen from the front (yaw 0).
 */
function stylePoints(style, tick) {
  const pts = [];
  const anim = style.animation;
  const pulse = anim === 'pulse' ? 1 + 0.3 * Math.sin(tick * 0.25) : 1;
  const spin = anim === 'spin' ? tick * 0.22 : 0;
  const up = (tick % 40) / 40;
  const bob = anim === 'rise' ? 0.12 * Math.sin(tick * 0.2) : 0;
  const circle = (y, radius, count) => {
    const shown = anim === 'spin' ? 3 : count;
    for (let i = 0; i < shown; i++) {
      const a = spin + (Math.PI * 2 * i) / shown;
      pts.push([Math.cos(a) * radius, y, Math.sin(a) * radius, i]);
    }
  };
  switch (style.shape) {
    case 'ring': circle(anim === 'rise' ? 0.1 + up * 1.9 : 0.1, 0.6 * pulse, 16); break;
    case 'halo': circle(2.15 + bob, 0.32 * pulse, 12); break;
    case 'orbit': circle(anim === 'rise' ? 1 + 0.8 * Math.sin(tick * 0.12) : 1, 0.75 * pulse, 10); break;
    case 'helix': {
      const r = 0.5 * pulse;
      if (anim === 'rise') {
        const y = up * 2;
        for (let k = 0; k < 2; k++) pts.push([Math.cos(y * Math.PI * 2 + k * Math.PI) * r, y, Math.sin(y * Math.PI * 2 + k * Math.PI) * r, k]);
      } else {
        for (let k = 0; k < 2; k++) {
          for (let i = 0; i < 10; i++) {
            const y = (2 * i) / 9;
            const a = y * Math.PI * 1.5 + k * Math.PI + spin;
            pts.push([Math.cos(a) * r, y, Math.sin(a) * r, k * 10 + i]);
          }
        }
      }
      break;
    }
    case 'wings': {
      const flap = anim === 'pulse' ? Math.sin(tick * 0.2) : anim === 'spin' ? Math.sin(tick * 0.45) : 0;
      const open = ((25 + 20 * flap) * Math.PI) / 180;
      let i = 0;
      for (const side of [-1, 1]) {
        for (const [u, v] of WING_OUTLINE) pts.push([-side * u * Math.cos(open), v + bob, -(0.25 + u * Math.sin(open)), i++]);
      }
      break;
    }
    default: { // cloud: a puff that changes every tick
      let seed = tick * 9301 + 49297;
      const rnd = () => { seed = (seed * 9301 + 49297) % 233280; return seed / 233280 - 0.5; };
      const y = anim === 'rise' ? up * 1.8 : 0.15 + Math.max(0, bob);
      for (let i = 0; i < 6; i++) pts.push([rnd() * 0.8, y + rnd() * 0.2, rnd() * 0.8, i]);
    }
  }
  return pts;
}
const WING_OUTLINE = (() => {
  const out = [];
  for (let i = 0; i <= 7; i++) out.push([0.15 + (i / 7) * 0.85, 1.35 + 0.55 * Math.sin((i / 7) * Math.PI * 0.85)]);
  for (let i = 6; i >= 1; i--) out.push([0.15 + (i / 7) * 0.85, 0.95 + 0.6 * (i / 7) ** 2]);
  return out;
})();

/** Every animated particle preview on the page, redrawn 20 times a second while it's in the page. */
const particlePreviews = new Set();
let particleTimer = null;
/** A trail is shown as if walking left: particles stay where they appeared and drift right as they fade. */
function trailPoints(preview, tick) {
  const live = preview.live || (preview.live = []);
  if (tick !== preview.lastTick && tick % 2 === 0) {
    for (const [x, y, z, index] of stylePoints(preview.style, tick)) live.push({ x, y, z, index, born: tick });
  }
  preview.lastTick = tick;
  while (live.length && tick - live[0].born > 14) live.shift();
  return live.slice(-60).map((p) => [p.x + (tick - p.born) * 0.13, p.y, p.z, p.index, 1 - (tick - p.born) / 15]);
}
function drawParticlePreview(preview, tick) {
  const { style, icon, field, size } = preview;
  const pts = preview.trail ? trailPoints(preview, tick) : stylePoints(style, tick);
  while (field.children.length < pts.length) field.append(el('i', {}, icon));
  [...field.children].forEach((dot, i) => {
    const p = pts[i];
    dot.hidden = !p;
    if (!p) return;
    const [x, y, z, index, fade = 1] = p;
    const scale = 0.75 + 0.25 * Math.max(-1, Math.min(1, z));
    dot.style.transform = `translate(${(x * size).toFixed(1)}px, ${(-y * size).toFixed(1)}px) scale(${scale.toFixed(2)})`;
    dot.style.opacity = ((0.55 + 0.45 * scale) * fade).toFixed(2);
    dot.style.zIndex = z > 0 ? 2 : 0;
    if (style.color === 'rainbow') dot.style.color = `hsl(${Math.round(((tick * 0.02 + index * 0.07) % 1) * 360)} 90% 60%)`;
  });
}
function tickParticlePreviews() {
  const tick = Math.floor(performance.now() / 50);
  for (const preview of particlePreviews) {
    if (!preview.root.isConnected) particlePreviews.delete(preview);
    else drawParticlePreview(preview, tick);
  }
  if (!particlePreviews.size) {
    clearInterval(particleTimer);
    particleTimer = null;
  }
}
/** A head with a trail's or aura's particles around it, moving like in game. */
function particlePreview(item, style, head, small) {
  const icon = PARTICLES[item.value]?.[1] || '✦';
  const colored = COLORABLE_PARTICLES.has(item.value);
  const field = el('span', { class: `pfield ${colored ? 'colored' : ''}`, 'aria-hidden': 'true' });
  if (colored && style.color !== 'rainbow') field.style.color = style.color || '#ffffff';
  // Drawn to scale (a player is 1.8 blocks tall): the head on a faint body, the particles around them.
  const root = el('div', { class: `cos-preview particles ${small ? 'small' : ''}`, title: [particleName(item.value), styleText(style)].filter(Boolean).join(' · ') },
    el('span', { class: 'pbody', 'aria-hidden': 'true' }, el('span', { class: 'ptorso' }), head), field);
  const preview = { root, style, icon: colored ? '●' : icon, field, size: small ? 16 : 28, trail: item.kind === 'trail' };
  if (reducedMotion()) {
    for (let t = 0; t <= 12; t++) drawParticlePreview(preview, t);
  } else {
    particlePreviews.add(preview);
    drawParticlePreview(preview, Math.floor(performance.now() / 50));
    particleTimer ??= setInterval(tickParticlePreviews, 50);
  }
  return root;
}

/**
 * Two small menus for a trail's or aura's look, shown next to it in Your unlocks: the look (a shape with an
 * animation, grouped by shape) and the colour (a custom one adds a colour picker). Owning the particle means owning
 * every look. {@code onChange} gets the new style.
 */
function styleEditor(style, fallback, colorable, onChange) {
  const current = { ...style };
  const lookName = (shape, animation) => `${styleLabel(STYLE_SHAPES, shape)} · ${styleLabel(STYLE_ANIMATIONS, animation).replace(/ \(.*\)/, '')}`;
  // "Default" is the particle's own look; picking it with the particle's own colour clears what they picked.
  const look = el('select', { 'aria-label': 'Look', title: 'Shape and animation' },
    el('option', { value: '' }, `Default (${lookName(fallback.shape, fallback.animation)})`),
    STYLE_SHAPES.map(([shape, shapeName]) => el('optgroup', { label: shapeName },
      STYLE_ANIMATIONS.map(([animation]) => el('option', { value: `${shape};${animation}` }, lookName(shape, animation))))));
  const isDefault = current.shape === fallback.shape && current.animation === fallback.animation;
  look.value = isDefault ? '' : `${current.shape};${current.animation}`;
  const known = STYLE_COLORS.some(([k]) => k === current.color);
  const color = el('select', { 'aria-label': 'Colour' }, STYLE_COLORS.map(([k, text]) => el('option', { value: k }, k ? text : 'White')));
  color.value = known ? current.color : 'custom';
  const picker = el('input', { type: 'color', 'aria-label': 'Custom colour', value: /^#[0-9a-f]{6}$/i.test(current.color) ? current.color : '#ff88cc' });
  picker.hidden = color.value !== 'custom';
  const changed = () => {
    picker.hidden = color.value !== 'custom';
    [current.shape, current.animation] = look.value ? look.value.split(';') : [fallback.shape, fallback.animation];
    current.color = color.value === 'custom' ? picker.value.toLowerCase() : color.value;
    onChange(!look.value && !current.color ? null : { ...current });
  };
  [look, color, picker].forEach((box) => box.addEventListener('change', changed));
  // Only particles Minecraft can colour (dust, potion swirls) get the colour menu.
  return el('div', { class: 'style-editor' }, look, colorable && el('span', { class: 'style-color' }, color, picker));
}

const cos = { data: null, edit: false };

/**
 * A little preview of a cosmetic: the prefix in chat, the trail's particles, the profile card behind a name, or
 * KeepInventory's shield. {@code who} is whose name and head to show.
 */
function cosmeticPreview(item, who, small = false) {
  const name = who?.name || 'You';
  const head = who?.uuid ? el('img', { class: 'head', src: headUrl(who.uuid, 64), alt: '', loading: 'lazy' })
    : el('span', { class: 'head head-blank', 'aria-hidden': 'true' }, '🙂');
  switch (item.kind) {
    case 'prefix':
      return el('div', { class: `cos-preview chat ${small ? 'small' : ''}` },
        el('span', {}, mcText(item.value || ''), ' ', el('span', { class: 'chat-name' }, name), !small && el('span', { class: 'chat-msg' }, ': hiii~')));
    case 'trail':
    case 'aura':
      return particlePreview(item, item.myStyle || item.style || { shape: 'cloud', animation: 'still', color: '' }, head, small);
    case 'card':
      return el('div', { class: `cos-preview cardp ${item.card ? 'has-card' : ''} ${small ? 'small' : ''}` },
        item.card ? cardBackdrop(item.card) : el('span', { class: 'muted cardp-empty' }, 'No picture yet'),
        head, el('span', { class: 'name' }, name));
    default:
      return el('div', { class: `cos-preview keepinv ${small ? 'small' : ''}` }, el('span', { 'aria-hidden': 'true' }, '🛡️'),
        !small && el('span', {}, 'Items & XP kept'));
  }
}

async function loadCosmetics() {
  try {
    cos.data = await api('/api/cosmetics');
  } catch (e) {
    $('shop-cosmetics').replaceChildren(el('p', { class: 'empty card' }, e.message));
    return;
  }
  renderCosmetics();
}

function renderCosmetics() {
  const d = cos.data;
  const b = d.buyer;
  $('shop-balance').textContent = b.linked ? `You have ${credits(b.balance, d.currency)}` : '';
  const parts = [];
  if (!d.open) {
    parts.push(el('div', { class: 'card panel shop-closed' }, d.manage
      ? ['The cosmetics shop is closed for players. Open it in ', el('a', { href: '#/settings' }, 'Settings'), '.']
      : 'The cosmetics shop is closed right now. What you already have keeps working.'));
  }
  if (!b.linked) parts.push(el('p', { class: 'card panel muted' }, 'Link your Minecraft account to buy cosmetics (/link in Discord).'));
  else if (!d.manage || !cos.edit) {
    parts.push(el('p', { class: 'card panel muted cos-intro' }, 'Bought once, yours to keep. Switch them on and off under ',
      el('a', { href: playerHref(b.uuid) }, 'Your unlocks'), ' on your profile.'));
  }
  if (d.manage) {
    parts.push(el('div', { class: 'card panel shop-manage' },
      el('span', {}, cos.edit ? 'Click Edit on an item to change it, or add new ones. Profile cards take a picture you drop in.'
        : 'You can add, change and price cosmetics.'),
      el('button', { class: `btn btn-small ${cos.edit ? '' : 'btn-ghost'}`, type: 'button',
        onclick: () => { cos.edit = !cos.edit; renderCosmetics(); } }, cos.edit ? 'Done editing' : 'Edit cosmetics')));
  }
  const editing = d.manage && cos.edit;
  for (const [kind, title, one, help] of COSMETIC_KINDS) {
    const items = d.items.filter((item) => item.kind === kind);
    const canAdd = editing && (kind !== 'keepinv' || !items.length);
    if (!items.length && !canAdd) continue;
    parts.push(el('section', { class: 'cos-section' },
      el('h2', { class: 'section-title' }, title,
        canAdd && el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => cosmeticDialog(null, kind) }, `New ${one}`)),
      help && el('p', { class: 'muted cos-help' }, help),
      items.length ? el('div', { class: 'cos-grid' }, items.map(cosmeticTile)) : el('p', { class: 'muted' }, 'None yet.')));
  }
  $('shop-cosmetics').replaceChildren(...parts);
}

function cosmeticTile(item) {
  const d = cos.data;
  const b = d.buyer;
  const editing = d.manage && cos.edit;
  let action;
  if (editing) {
    action = el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => cosmeticDialog(item) }, 'Edit');
  } else if (item.owned) {
    action = el('button', { class: `btn btn-small ${item.equipped ? 'btn-ghost' : ''}`, type: 'button',
      onclick: (e) => equipCosmetic(item, !item.equipped, e.currentTarget) }, item.equipped ? 'Stop using' : 'Use');
  } else {
    const short = b.linked && b.balance < item.price;
    action = el('button', { class: 'btn btn-small', type: 'button', disabled: !d.open || !b.linked || short,
      title: short ? `You need ${credits(item.price - b.balance, d.currency)} more` : null,
      onclick: () => buyCosmetic(item) }, item.price ? `Buy · ${credits(item.price, d.currency)}` : 'Get for free');
  }
  return el('div', { class: `card cos-tile ${item.equipped ? 'equipped' : ''} ${item.forSale ? '' : 'not-for-sale'}` },
    cosmeticPreview(item, b.linked ? { name: b.name, uuid: b.uuid } : null),
    el('div', { class: 'cos-info' },
      el('div', { class: 'cos-name' }, item.name,
        item.equipped ? el('span', { class: 'badge good' }, 'In use') : item.owned && el('span', { class: 'badge' }, 'Yours')),
      (item.kind === 'trail' || item.kind === 'aura') && particleName(item.value).toLowerCase() !== item.name.toLowerCase()
        && el('div', { class: 'muted cos-meta' }, particleName(item.value)),
      item.kind === 'keepinv' && el('p', { class: 'muted cos-desc' }, keepInventoryText(d.keepInventoryDelay)),
      editing && el('div', { class: 'muted cos-meta' }, `${credits(item.price, d.currency)} · ${item.owners} ${item.owners === 1 ? 'player has' : 'players have'} it`
        + (item.forSale ? '' : ' · not for sale') + (item.kind === 'card' && !item.card ? ' · needs a picture' : ''))),
    action);
}

function buyCosmetic(item) {
  const d = cos.data;
  confirmDialog({
    title: `Buy ${item.name}?`,
    text: [`${credits(item.price, d.currency)}. You have ${credits(d.buyer.balance, d.currency)}.`,
      item.kind === 'keepinv' ? keepInventoryText(d.keepInventoryDelay)
        : `It's switched on straight away; switch it off any time under Your unlocks on your profile${
          item.kind === 'trail' || item.kind === 'aura' ? `, where you also pick its look (halo, wings, helix…)${item.colorable ? ' and colour' : ''}` : ''}.`],
    confirmLabel: item.price ? `Buy for ${credits(item.price, d.currency)}` : 'Get it',
    onConfirm: async () => {
      const result = await api('/api/cosmetics/buy', { id: item.id });
      toast(result.message);
      await loadCosmetics();
    },
  });
}

/** Sends a profile card picture as it is; the server checks and shrinks it. */
async function uploadCardPicture(id, file) {
  const res = await fetch(`/api/cosmetics/card?id=${id}`, {
    method: 'POST', credentials: 'same-origin', body: file,
    headers: { 'X-CSRF-Token': state.me.csrf, 'Content-Type': file.type || 'application/octet-stream' },
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `Upload failed (${res.status})`);
  return data;
}

/** The settings owner's editor for one cosmetic ({@code item} null = a new one of {@code kind}). */
function cosmeticDialog(item, kind = item?.kind) {
  const d = cos.data;
  const [, , one] = COSMETIC_KINDS.find(([k]) => k === kind);
  $('cosmetic-title').textContent = item ? `Edit ${item.name}` : `New ${one}`;
  $('cosmetic-name').value = item?.name || (kind === 'keepinv' ? 'KeepInventory' : '');
  $('cosmetic-price').value = item?.price ?? DEFAULT_PRICES[kind];
  $('cosmetic-for-sale').checked = item ? item.forSale : true;
  $('cosmetic-error').hidden = true;
  $('cosmetic-note').textContent = item && item.owners
    ? `${item.owners} ${item.owners === 1 ? 'player has' : 'players have'} it. Deleting it gives back what they paid.` : '';
  let value = () => '';
  let picture = null;
  const box = $('cosmetic-value');
  if (kind === 'prefix') {
    const input = el('input', { type: 'text', maxlength: '64', required: true, spellcheck: 'false', placeholder: '&6[Tycoon]', value: item?.value || '' });
    const preview = el('div', { class: 'cos-preview chat' });
    const update = () => preview.replaceChildren(el('span', {}, mcText(input.value || ''), ' ',
      el('span', { class: 'chat-name' }, d.buyer.name || 'Steve'), el('span', { class: 'chat-msg' }, ': hiii~')));
    input.addEventListener('input', update);
    update();
    box.replaceChildren(el('label', { class: 'reason' }, 'Prefix', input),
      el('p', { class: 'muted' }, 'Colours with & codes: &6 gold, &b aqua, &d pink, &c red, &a green, &#ff8800 any colour; &l bold, &o italic.'),
      preview);
    value = () => input.value;
  } else if (kind === 'trail' || kind === 'aura') {
    const select = el('select', { 'aria-label': 'Particle' }, d.particles.map((key) => el('option', { value: key },
      `${PARTICLES[key]?.[1] || '✦'} ${particleName(key)}`)));
    select.value = item?.value || d.particles[0];
    const look = item?.style || (kind === 'aura' ? { shape: 'orbit', animation: 'spin', color: '' } : { shape: 'cloud', animation: 'still', color: '' });
    let preview = el('div');
    const who = d.buyer.linked ? { name: d.buyer.name, uuid: d.buyer.uuid } : null;
    const show = () => {
      const next = cosmeticPreview({ kind, value: select.value, style: look }, who);
      preview.replaceWith(next);
      preview = next;
    };
    select.addEventListener('change', show);
    box.replaceChildren(el('label', { class: 'reason' }, 'Particle', select), preview,
      el('p', { class: 'muted' }, 'Whoever buys it gets every look: they pick the shape and animation (and for coloured dust and potion swirls, the colour) under Your unlocks.'));
    show();
    value = () => select.value;
  } else if (kind === 'card') {
    const file = el('input', { type: 'file', accept: 'image/png,image/jpeg,image/gif', class: 'offscreen' });
    let preview = el('div');
    const who = d.buyer.linked ? { name: d.buyer.name, uuid: d.buyer.uuid } : null;
    const show = (url) => {
      const next = cosmeticPreview({ kind: 'card', card: url }, who);
      preview.replaceWith(next);
      preview = next;
    };
    const take = (chosen) => {
      if (!chosen) return;
      if (!/^image\/(png|jpeg|gif)$/.test(chosen.type)) {
        toast('Use a PNG, JPEG or GIF picture.');
        return;
      }
      picture = chosen;
      show(URL.createObjectURL(chosen));
    };
    file.addEventListener('change', () => take(file.files[0]));
    const drop = el('label', { class: 'dropzone' }, file, el('b', {}, 'Drop a picture here'), el('span', { class: 'muted' }, 'or click to choose one. '
      + 'Wide pictures work best (it fades out to the right); it\'s shrunk to at most 1600×900.'));
    ['dragenter', 'dragover'].forEach((type) => drop.addEventListener(type, (e) => { e.preventDefault(); drop.classList.add('over'); }));
    ['dragleave', 'drop'].forEach((type) => drop.addEventListener(type, () => drop.classList.remove('over')));
    drop.addEventListener('drop', (e) => {
      e.preventDefault();
      take(e.dataTransfer.files[0]);
    });
    box.replaceChildren(drop, preview);
    show(item?.card);
  } else {
    box.replaceChildren(el('p', { class: 'muted' }, keepInventoryText(d.keepInventoryDelay)));
  }
  const remove = $('cosmetic-delete');
  remove.hidden = !item;
  remove.onclick = () => confirmDialog({
    title: `Delete ${item.name}?`,
    text: item.owners ? `${item.owners} ${item.owners === 1 ? 'player has' : 'players have'} it. They lose it, and everyone who paid gets their ${d.currency.many} back.`
      : 'Nobody has it yet.',
    confirmLabel: 'Delete', confirmClass: 'btn-danger',
    onConfirm: async () => {
      await api('/api/cosmetics/delete', { id: item.id });
      closeDialog($('cosmetic-dialog'));
      toast(`${item.name} was deleted`);
      await loadCosmetics();
    },
  });
  $('cosmetic-form').onsubmit = async (e) => {
    e.preventDefault();
    const save = $('cosmetic-save');
    save.disabled = true;
    try {
      const saved = await api('/api/cosmetics/save', { id: item?.id || 0, kind, name: $('cosmetic-name').value,
        value: value(), price: Number($('cosmetic-price').value), forSale: $('cosmetic-for-sale').checked });
      if (picture) await uploadCardPicture(saved.id, picture);
      closeDialog($('cosmetic-dialog'));
      toast(`${saved.name} saved`);
      await loadCosmetics();
    } catch (err) {
      $('cosmetic-error').textContent = err.message;
      $('cosmetic-error').hidden = false;
    } finally {
      save.disabled = false;
    }
  };
  $('cosmetic-dialog').showModal();
}
$('cosmetic-cancel').addEventListener('click', () => closeDialog($('cosmetic-dialog')));

// ---- Shop page: Items, Cosmetics and Sell & exchange tabs ----

/** The Shop's tabs that are open (the settings owner sees them all, to set them up). */
function shopTabs() {
  const owner = state.me.isOwner && state.me.communityEnabled;
  return [['', state.me.shopOpen || owner], ['cosmetics', state.me.cosmeticsOpen || owner], ['sell', state.me.marketOpen || owner]]
    .filter(([, open]) => open).map(([sub]) => sub);
}

function loadShopView() {
  const tabs = shopTabs();
  const wanted = currentRoute().sub || '';
  const sub = tabs.includes(wanted) ? wanted : tabs[0] ?? '';
  document.querySelectorAll('#shop-subtabs a').forEach((a) => {
    a.hidden = !tabs.includes(a.dataset.sub);
    markCurrent(a, a.dataset.sub === sub);
  });
  $('shop-items').hidden = sub !== '';
  $('shop-cosmetics').hidden = sub !== 'cosmetics';
  $('shop-sell').hidden = sub !== 'sell';
  $('shop-balance').textContent = '';
  if (sub === 'cosmetics') loadCosmetics();
  else if (sub === 'sell') loadMarket();
  else loadShop();
}

// ---- Discord message log (settings owner only) ----

const msglog = { data: null, channel: null, messages: [], more: false, search: '', searchTimer: 0 };

async function loadMessageLog() {
  try {
    msglog.data = await api('/api/settings/messages');
  } catch (e) {
    $('messages-notice').replaceChildren(el('p', { class: 'empty card' }, e.message));
    return;
  }
  const d = msglog.data;
  const notices = [];
  const toggle = async (button, on) => {
    button.disabled = true;
    try {
      await api('/api/settings', { changes: { 'message-log-enabled': String(on) } });
      toast(on ? 'Saved. Restart the proxy to start logging.' : 'Logging is off after the next proxy restart. What was saved stays.');
      await loadMessageLog();
    } catch (err) {
      toast(err.message);
      button.disabled = false;
    }
  };
  if (!d.enabled) {
    const on = el('button', { class: 'btn btn-small', type: 'button' }, 'Turn logging on');
    on.addEventListener('click', () => toggle(on, true));
    notices.push(el('div', { class: 'card panel msglog-state' },
      el('span', {}, el('b', {}, 'Logging is off. '), 'Turn it on to save the messages of every channel the bot can see.'), on));
  } else {
    const off = el('button', { class: 'btn btn-ghost btn-small', type: 'button' }, 'Turn off');
    off.addEventListener('click', () => toggle(off, false));
    notices.push(el('div', { class: 'card panel msglog-state' },
      el('span', {}, el('span', { class: 'pill online' }, 'Logging'), ' every channel the bot can see.'), off));
    if (!d.messageContent) {
      notices.push(el('div', { class: 'card panel shop-closed' }, 'Not saving yet: turn on ', el('b', {}, 'Message Content Intent'),
        ' at discord.com/developers/applications → your app → Bot, then restart the proxy.'));
    }
  }
  if (!d.botReady) notices.push(el('p', { class: 'card panel muted' }, 'The Discord bot isn\'t connected, so only channels with saved messages are listed.'));
  notices.push(el('p', { class: 'muted msglog-help' }, 'The bot only reads: it never posts, reacts or leaves anything in these channels. '
    + 'New messages only (Discord doesn\'t hand out old history). Edits keep the first version and deleted messages stay, marked as deleted. ',
    d.retentionDays ? `The log goes back ${d.retentionDays} days` : 'The log keeps everything', ' (',
    el('a', { href: '#/settings' }, 'change it in Settings'), '). Channels the bot can\'t see aren\'t logged.'));
  $('messages-notice').replaceChildren(...notices);
  if (!msglog.channel || !d.channels.some((c) => c.id === msglog.channel)) {
    msglog.channel = (d.channels.find((c) => c.count > 0) || d.channels[0])?.id || null;
  }
  renderMessageChannels();
  loadMessageFeed();
}

function renderMessageChannels() {
  const q = $('msglog-filter').value.trim().toLowerCase();
  const groups = new Map();
  for (const c of msglog.data.channels) {
    if (q && !c.name.toLowerCase().includes(q)) continue;
    const key = c.category || 'No category';
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(c);
  }
  const rows = [];
  for (const [category, channels] of groups) {
    rows.push(el('div', { class: 'msglog-category' }, category));
    for (const c of channels) {
      rows.push(el('div', { class: `msglog-channel ${c.id === msglog.channel ? 'active' : ''} ${c.readable ? '' : 'unreadable'}` },
        el('button', { type: 'button', class: 'msglog-channel-name', onclick: () => { msglog.channel = c.id; msglog.search = ''; $('msglog-view').replaceChildren(); renderMessageChannels(); loadMessageFeed(); },
          title: c.type === 'gone' ? 'No longer in the server (or the bot can\'t see it any more)' : '' },
        el('span', { class: 'hash' }, c.type === 'voice' ? '🔊' : c.type === 'gone' ? '✕' : '#'), el('span', { class: 'name' }, c.name),
        c.count > 0 && el('span', { class: 'muted msglog-count' }, c.count.toLocaleString()))));
    }
  }
  $('msglog-channels').replaceChildren(...(rows.length ? rows : [el('p', { class: 'muted' }, 'No channels.')]));
}
$('msglog-filter').addEventListener('input', () => { if (msglog.data) renderMessageChannels(); });

/** The selected channel as one feed, oldest at the top, like Discord; older messages load on demand. */
async function loadMessageFeed(older = false) {
  const view = $('msglog-view');
  if (!msglog.channel) {
    view.replaceChildren(el('p', { class: 'muted' }, 'No channels yet.'));
    return;
  }
  const channel = msglog.channel;
  const q = msglog.search.trim();
  const before = older && msglog.messages.length ? `&before=${msglog.messages[0].id}` : '';
  let page;
  try {
    page = await api(`/api/settings/messages/feed?channel=${channel}${before}${q ? `&q=${encodeURIComponent(q)}` : ''}`);
  } catch (e) {
    view.replaceChildren(el('p', { class: 'muted' }, e.message));
    return;
  }
  if (channel !== msglog.channel || q !== msglog.search.trim()) return; // switched away meanwhile
  const batch = page.messages.reverse();
  msglog.messages = older ? [...batch, ...msglog.messages] : batch;
  msglog.more = page.more;
  renderMessageFeed(older ? 'keep' : 'bottom');
}

function renderMessageFeed(scroll) {
  const channel = msglog.data.channels.find((c) => c.id === msglog.channel);
  let list = $('msglog-feed');
  if (!list) {
    const search = el('input', { id: 'msglog-search', type: 'search', placeholder: 'Search this channel…', 'aria-label': 'Search this channel' });
    search.value = msglog.search;
    search.addEventListener('input', () => {
      msglog.search = search.value;
      clearTimeout(msglog.searchTimer);
      msglog.searchTimer = setTimeout(() => loadMessageFeed(), 300);
    });
    list = el('div', { id: 'msglog-feed', class: 'msglog-messages' });
    $('msglog-view').replaceChildren(
      el('div', { class: 'msglog-head' },
        el('h2', { id: 'msglog-title' }),
        search,
        el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => loadMessageFeed() }, 'Refresh')),
      el('p', { class: 'muted msglog-summary', id: 'msglog-summary' }),
      list);
  }
  $('msglog-title').textContent = channel ? `#${channel.name}` : 'Channel';
  const q = msglog.search.trim();
  $('msglog-summary').textContent = q
    ? `${msglog.messages.length.toLocaleString()}${msglog.more ? '+' : ''} messages match`
    : `${(channel?.count || 0).toLocaleString()} messages saved`
      + (msglog.data.retentionDays ? ` · goes back ${msglog.data.retentionDays} days` : '');
  const oldHeight = list.scrollHeight;
  const oldTop = list.scrollTop;
  const nodes = [];
  if (msglog.more) {
    nodes.push(el('button', { class: 'btn btn-ghost btn-small msglog-older', type: 'button',
      onclick: (e) => { e.currentTarget.disabled = true; loadMessageFeed(true); } }, 'Load older messages'));
  } else if (msglog.messages.length) {
    nodes.push(el('p', { class: 'muted msglog-start' }, q ? 'That\'s every match.'
      : 'This is as far back as the log goes.'));
  }
  let day = '';
  for (const m of msglog.messages) {
    const d = new Date(m.time).toDateString();
    if (d !== day) {
      day = d;
      nodes.push(el('div', { class: 'msglog-day' }, new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' }).format(new Date(m.time))));
    }
    nodes.push(el('div', { class: `msglog-msg ${m.deletedAt ? 'deleted' : ''}` },
      el('a', { href: discordHref(m.authorId), target: '_blank', rel: 'noopener' },
        el('img', { class: 'head', src: m.avatar || '', alt: '', loading: 'lazy' })),
      el('div', { class: 'msglog-body' },
        el('div', { class: 'msglog-meta' },
          el('a', { class: 'name', href: discordHref(m.authorId), target: '_blank', rel: 'noopener' }, m.author),
          m.bot && el('span', { class: 'badge info' }, 'Bot'),
          el('span', { class: 'muted', title: formatTime(m.time) }, new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' }).format(new Date(m.time))),
          m.editedAt && el('span', { class: 'badge', title: `Edited ${formatTime(m.editedAt)}` }, 'Edited'),
          m.deletedAt && el('span', { class: 'badge bad', title: `Deleted ${formatTime(m.deletedAt)}` }, 'Deleted')),
        m.content && el('div', { class: 'msglog-text' }, m.content),
        !m.content && !m.attachments.length && el('div', { class: 'msglog-text muted' }, '(no text)'),
        m.original && el('div', { class: 'msglog-original' }, el('span', { class: 'muted' }, 'Before the edit: '), m.original),
        m.attachments.length > 0 && el('div', { class: 'msglog-files' }, m.attachments.map((url) => el('a', { href: url, target: '_blank', rel: 'noopener' },
          `📎 ${decodeURIComponent(url.split('?')[0].split('/').pop())}`))))));
  }
  if (!msglog.messages.length) {
    nodes.push(el('p', { class: 'muted' }, q ? 'Nothing matches.' : msglog.data.enabled ? 'Nothing saved yet. New messages show up here.'
      : 'Nothing saved. Turn logging on to start.'));
  }
  list.replaceChildren(...nodes);
  // Start at the newest message; after loading older ones, stay where you were.
  list.scrollTop = scroll === 'keep' ? oldTop + (list.scrollHeight - oldHeight) : list.scrollHeight;
}

// ---- Shop → Sell & exchange: selling items in game for microcredits ----

/** Sell prices are kept in thousandths of a microcredit: "1,234 µc", or "0.3 µc" for fractions. */
function formatMicro(milli, currency) {
  const value = milli / 1000;
  const digits = milli % 1000 === 0 || milli >= 100000 ? 0 : milli >= 10000 ? 1 : 2;
  return `${value.toLocaleString(undefined, { maximumFractionDigits: digits })} ${currency.symbol}`;
}
/** Whole microcredits (balances): "12,345 µc". */
const micro = (n, currency) => `${n.toLocaleString()} ${currency.symbol}`;

const market = { data: null, tab: SHOP_TABS[0][0] };

async function loadMarket() {
  let data;
  try {
    data = await api('/api/market');
  } catch (e) {
    $('market-notice').replaceChildren(el('p', { class: 'empty card' }, e.message));
    return;
  }
  market.data = data;
  if (!shop.textures.size) shop.textures = await loadTextures('99');
  renderMarket();
}

function renderMarket() {
  const d = market.data;
  const cur = d.currency;
  const me = d.me;
  $('shop-balance').textContent = me.linked ? `You have ${credits(me.credits, d.credits)} · ${micro(me.micro, cur)}` : '';
  const notices = [];
  if (!d.open) {
    notices.push(el('div', { class: 'card panel shop-closed' }, d.manage
      ? ['Selling is turned off for players. Turn it on in ', el('a', { href: '#/settings' }, 'Settings'), '.']
      : 'Selling is closed right now.'));
  }
  $('market-notice').replaceChildren(...notices);

  // Exchanging: one way only, microcredits to credits.
  const exchange = $('market-exchange');
  if (!me.linked) {
    exchange.replaceChildren(el('h2', {}, 'Exchange'),
      el('p', { class: 'muted' }, 'Link your Minecraft account to sell and exchange (/link in Discord).'));
  } else {
    const can = Math.floor(me.micro / d.rate);
    const amount = el('input', { type: 'number', min: '1', step: '1', value: String(Math.max(1, Math.min(can, 10))),
      'aria-label': `${d.credits.many} to get`, inputmode: 'numeric' });
    const cost = el('span', { class: 'muted' });
    const updateCost = () => { cost.textContent = `costs ${micro((Number(amount.value) || 0) * d.rate, cur)}`; };
    amount.addEventListener('input', updateCost);
    updateCost();
    const run = async (button, body) => {
      button.disabled = true;
      try {
        const result = await api('/api/market/exchange', body);
        toast(`${result.message} You now have ${credits(result.credits, d.credits)}.`);
        await loadMarket();
      } catch (err) {
        toast(err.message);
        button.disabled = false;
      }
    };
    const one = el('button', { class: 'btn', type: 'button', disabled: !d.open || can < 1 ? '' : null },
      'Exchange');
    one.addEventListener('click', () => run(one, { credits: Number(amount.value) }));
    const all = el('button', { class: 'btn btn-ghost', type: 'button', disabled: !d.open || can < 1 ? '' : null },
      `All (${credits(can, d.credits)})`);
    all.addEventListener('click', () => run(all, { all: true }));
    exchange.replaceChildren(el('h2', {}, `${cur.many} → ${d.credits.many}`),
      el('div', { class: 'stats-grid' },
        stat('Yours', [micro(me.micro, cur), me.rank ? el('span', { class: 'stat-sub' }, `Rank #${me.rank}`) : null]),
        stat(d.credits.many, credits(me.credits, d.credits))),
      el('p', { class: 'muted' }, `1 ${d.credits.one} = ${micro(d.rate, cur)}. Exchanging only goes this way, `
        + (d.ranking === 'balance' ? 'and lowers your place on the leaderboard.' : 'and doesn\'t change your place on the leaderboard.')),
      el('div', { class: 'market-exchange-row' }, amount, cost, one, all),
      el('a', { class: 'muted market-leaderboard-link', href: '#/community', onclick: () => {
        communityState.board = 'micro';
        remember('dl-board', 'micro');
      } }, `${cur.many} leaderboard →`));
  }

  $('market-how').replaceChildren(el('h2', {}, 'How to sell'),
    el('ul', { class: 'market-how' },
      el('li', {}, el('code', {}, '/sell'), ' opens a chest: put items in and close it.'),
      el('li', {}, el('code', {}, '/sellhand'), ' sells what you hold, ', el('code', {}, '/sellhand all'), ' every one of it you have.'),
      el('li', {}, el('code', {}, '/worth'), ' shows what the item in your hand is worth.'),
      el('li', {}, el('code', {}, '/mct'), ' shows the leaderboard in game, ', el('code', {}, '/exchange'), ' turns them into ', d.credits.many, '.')),
    el('p', { class: 'muted' }, `Items sell for ${d.sellPercent}% of their Shop price. Enchanted, named, damaged or filled items can't be sold.`
      + (d.dropping ? ` Prices drop the more of an item everyone sells (half price after ${d.dropAfter.toLocaleString()} `
        + `${d.credits.many}' worth) and recover over time (halfway every ${d.recoveryHours} hours).` : '')),
    el('p', { class: 'muted market-totals' }, `${micro(d.held, cur)} held · ${micro(d.earned, cur)} earned by everyone`));

  const marketTab = $('market-search').value.trim() ? null : market.tab; // searching shows every category
  $('market-tabs').replaceChildren(...SHOP_TABS.map(([tab, icon]) => el('button', {
    class: `shop-tab ${tab === marketTab ? 'active' : ''}`, type: 'button', 'aria-pressed': String(tab === marketTab),
    title: tab, 'aria-label': tab, onclick: () => { market.tab = tab; $('market-search').value = ''; renderMarket(); },
  }, shopIcon(icon), el('span', {}, tab))));
  renderMarketList();
}

function renderMarketList() {
  const d = market.data;
  const cur = d.currency;
  const query = $('market-search').value.trim().toLowerCase().replace(/\s+/g, ' ');
  let items = d.items.filter((item) => (query
    ? shopName(item.id).toLowerCase().includes(query) || item.id.includes(query.replace(/ /g, '_'))
    : item.tab === market.tab));
  const sort = $('market-sort').value;
  if (sort === 'value') items = [...items].sort((a, b) => b.now - a.now);
  else if (sort === 'name') items = [...items].sort((a, b) => shopName(a.id).localeCompare(shopName(b.id)));
  $('market-list').replaceChildren(...(items.length ? items.map((item) => {
    const dropped = item.normal > 0 && item.now / item.normal < 0.95;
    return el('div', { class: 'market-item' },
      el('span', { class: 'slot' }, shopIcon(item.id)),
      el('div', { class: 'market-item-text' },
        el('span', { class: 'market-item-name' }, shopName(item.id)),
        el('span', { class: 'muted' }, `${formatMicro(item.now, cur)} each · ${formatMicro(item.now * item.stack, cur)} per ${item.stack}`)),
      dropped && el('span', { class: 'badge warn', title: `Normally ${formatMicro(item.normal, cur)} each` },
        `${Math.round(item.now * 100 / item.normal)}%`));
  }) : [el('p', { class: 'muted shop-empty' }, query ? 'No items match.' : 'Nothing sells here.')]));
}

$('market-search').addEventListener('input', () => { if (market.data) renderMarket(); });
$('market-sort').addEventListener('change', () => { if (market.data) renderMarketList(); });

// ---- Community view (everyone; admins see it when off duty) ----

function personRow(p, sub) {
  return el('a', { class: 'person', href: playerHref(p.uuid) },
    el('img', { class: 'head', src: headUrl(p.uuid, 72), alt: '', loading: 'lazy' }),
    el('div', { class: 'person-text' },
      el('div', { class: `name ${nameClass(p)}` }, p.minecraftName),
      el('div', { class: 'sub' }, sub)));
}

function onlineCard(p) {
  return el('a', { class: 'card person-card', href: playerHref(p.uuid) },
    el('img', { class: 'head', src: headUrl(p.uuid, 112), alt: '', loading: 'lazy' }),
    el('div', { class: 'person-text' },
      el('div', { class: `name ${nameClass(p)}` }, p.minecraftName),
      el('div', { class: 'sub' }, p.linked ? p.discordName : 'Not linked'),
      el('span', { class: 'pill online' }, p.server || 'Connecting')));
}

async function loadHome() {
  let data;
  try {
    data = await api('/api/home');
  } catch (e) {
    toast(e.message);
    return;
  }
  // The bigger dashboard tiles; who is online is the section right below, so it has no tile of its own.
  const homeStat = (label, value, title) => el('div', { class: 'card stat-card', title },
    el('div', { class: 'stat-label' }, label), el('div', { class: 'stat-value' }, value));
  $('home-stats').replaceChildren(
    homeStat('Minecraft players', data.playerCount.toLocaleString(), 'Everyone who has joined one of the servers'),
    homeStat('Linked members', data.linkedCount),
    homeStat('Staff online', data.staffOnline),
    homeStat('Servers online', data.servers.length, data.servers.map((s) => `${s.name}: ${s.players}`).join(' · ') || null));
  $('home-online-count').textContent = `(${data.onlineCount})`;
  $('home-online').replaceChildren(...data.online.map(onlineCard));
  $('home-online-empty').hidden = data.online.length > 0;
  $('home-newest').replaceChildren(...(data.newest.length
    ? data.newest.map((p) => personRow(p, `Joined the community ${timeAgo(p.linkedAt)}`))
    : [el('p', { class: 'muted' }, 'No members yet.')]));
  $('home-recent').replaceChildren(...(data.recent.length
    ? data.recent.map((p) => personRow(p, `Seen ${timeAgo(p.lastSeen)}`))
    : [el('p', { class: 'muted' }, 'Nobody has been online yet.')]));
}

/** The simple profile everyone can see: who they are, their Discord, and whether they're on. */
function renderPublicPlayer() {
  const p = state.player.player;
  document.title = `${p.minecraftName} · DiscordLinked`;
  const discord = el('div', { class: 'card panel' },
    el('h2', {}, 'Discord'),
    p.linked
      ? el('div', { class: 'identity' },
        discordAvatarLink(p),
        el('div', {}, discordName(p), copyButton(p.discordId, 'User ID')))
      : el('p', { class: 'muted' }, 'This player has no linked Discord account.'),
    p.linked && el('dl', { class: 'kv' }, el('dt', {}, 'Member since'), el('dd', {}, formatTime(p.linkedAt))));
  const server = el('div', { class: 'card panel' },
    el('h2', {}, 'On the server'),
    el('dl', { class: 'kv' },
      p.firstPlayed && [el('dt', {}, 'First joined'), el('dd', {}, formatTime(p.firstPlayed))],
      !p.online && [el('dt', {}, 'Last seen'),
        el('dd', {}, p.lastSeen ? `${timeAgo(p.lastSeen)} (${formatTime(p.lastSeen)})` : 'Never')]));
  $('view-player').replaceChildren(
    el('a', { class: 'back', href: '#/players' }, '← All players'),
    profileHeader(p, false),
    el('div', { class: 'grid' }, discord, server),
    ...unlocksSection(p, state.player.cosmetics),
    ...communitySection(p, state.player.community));
}

/** Community view: something about a player changed (members only hear that much). */
function onPresence(change) {
  const route = currentRoute();
  if (!change.uuid && route.view !== 'community') return;
  if (route.view === 'home') soon('home', loadHome);
  else if (route.view === 'community') soon('community', loadCommunity);
  else if (route.view === 'shop') soon('shop', loadShopView);
  else if (route.view === 'players') soon('players', loadPlayers);
  else if (route.view === 'player' && route.uuid === change.uuid) soon('player', () => loadPlayer(route.uuid));
}

// ---- On duty / off duty ----

const ADMIN_VIEWS = new Set(['players', 'logs', 'moderate', 'settings', 'messages', 'community', 'shop']);
const MEMBER_VIEWS = new Set(['home', 'players', 'community', 'shop']);
const homeView = () => (isStaff() ? 'players' : 'home');
const homeHash = () => `#/${homeView()}`;

function applyMode() {
  const staff = isStaff();
  document.body.classList.toggle('member-view', !staff);
  $('brand').href = homeHash();
  $('logs-sheets').hidden = !state.me.sheetsEnabled;
  $('settings-link').hidden = !state.me.isOwner;
  $('duty').hidden = !state.me.isAdmin;
  $('duty').classList.toggle('off', state.me.isAdmin && !state.me.onDuty);
  $('duty-switch').checked = state.me.onDuty;
  $('community-tab').hidden = !state.me.communityEnabled;
  $('shop-tab').hidden = shopTabs().length === 0;
  $('global-search').placeholder = staff ? 'Find any player…' : 'Find a player…';
  setupPlayerControls();
  updateTabPills();
}

$('duty-switch').addEventListener('change', async (e) => {
  const input = e.currentTarget;
  input.disabled = true;
  try {
    const me = await api('/api/duty', { onDuty: input.checked });
    state.me = { ...state.me, ...me };
    state.player = null;
    state.members = null;
    applyMode();
    toast(me.onDuty ? 'On duty: admin view' : 'Off duty: you see what everyone else sees');
    const route = currentRoute().view;
    const target = route === 'player' || route === 'players' || route === 'community' || route === 'shop'
      ? location.hash : homeHash();
    if (location.hash === target) showView();
    else location.hash = target;
    if (me.onDuty) pollSpy();
  } catch (err) {
    input.checked = !input.checked;
    toast(err.message);
  } finally {
    input.disabled = false;
  }
});

// Which log events change which page.
const STATUS_EVENTS = new Set(['JOIN', 'LEAVE', 'SERVER_SWITCH', 'LINK', 'UNLINK', 'SUSPEND', 'SUSPEND_EDIT',
  'UNSUSPEND', 'KICK', 'NOTE', 'DEATH', 'INVENTORY_RESTORE', 'DENIED_UNLINKED', 'DENIED_SUSPENDED', 'ADVANCEMENT',
  'CREDITS_ADJUST', 'COSMETIC']);
const COMMUNITY_EVENTS = new Set(['INTERACTION', 'ADVANCEMENT', 'CREDITS_ADJUST', 'SHOP_PURCHASE', 'SALE', 'EXCHANGE',
  'COSMETIC', 'GROUP', 'JOIN', 'LEAVE']);
const SHOP_EVENTS = new Set(['SHOP_PURCHASE', 'CREDITS_ADJUST', 'SALE', 'EXCHANGE', 'COSMETIC', 'ADVANCEMENT', 'JOIN',
  'LEAVE', 'SERVER_SWITCH', 'SETTINGS_CHANGE']);
const pendingRefresh = {};

/** Coalesces bursts of events into one refresh. */
function soon(key, fn, ms = 700) {
  clearTimeout(pendingRefresh[key]);
  pendingRefresh[key] = setTimeout(fn, ms);
}

function onLiveLog(entry) {
  const route = currentRoute();
  if (route.view === 'logs') prependLog(entry);
  else if (route.view === 'players' && STATUS_EVENTS.has(entry.type)) soon('players', loadPlayers);
  else if (route.view === 'moderate' && STATUS_EVENTS.has(entry.type)) soon('moderate', loadModerate);
  else if (route.view === 'community' && COMMUNITY_EVENTS.has(entry.type)) soon('community', loadCommunity);
  else if (route.view === 'shop' && SHOP_EVENTS.has(entry.type)) soon('shop', loadShopView);
  else if (route.view === 'player' && entry.uuid === route.uuid) {
    prependPlayerLog(entry);
    if (STATUS_EVENTS.has(entry.type)) soon('player', () => loadPlayer(route.uuid));
  }
}

function onLivePlayer(change) {
  const route = currentRoute();
  // Someone opened or left the website: lists and their page show it.
  if (change.kind === 'web') {
    if (route.view === 'moderate') soon('moderate', loadModerate);
    else onPresence(change);
    return;
  }
  if (route.view === 'player' && change.uuid === route.uuid && change.kind === 'snapshot'
      && state.inventory?.mode === 'snapshots') {
    soon('snapshots', () => loadSnapshots());
  }
}

function prependLog(entry) {
  const filter = $('logs-filter').value;
  const query = $('logs-search').value.trim().toLowerCase();
  if (filter !== 'all' && !entry.filters.includes(filter)) return;
  if (query && !`${entry.player} ${entry.actor}`.toLowerCase().includes(query)) return;
  const body = $('logs-body');
  const row = logRow(entry, true);
  row.classList.add('fresh');
  body.prepend(row);
  while (body.children.length > 500) body.lastElementChild.remove();
  $('logs-empty').hidden = true;
}

function prependPlayerLog(entry) {
  const body = $('player-logs');
  if (!body) return;
  body.querySelector('td.empty')?.parentElement.remove();
  const row = logRow(entry, false);
  row.classList.add('fresh');
  body.prepend(row);
}

/**
 * Live updates by long polling: each request waits on the server until something happens. (Server-Sent
 * Events would be simpler, but Cloudflare quick tunnels, the default https address, can't carry them.)
 */
async function connectLive() {
  const indicator = $('live-indicator');
  let since = null;
  for (;;) {
    try {
      const res = await fetch(`/api/poll${since === null ? '' : `?since=${since}`}`, { credentials: 'same-origin' });
      if (res.status === 401) {
        location.href = '/';
        return;
      }
      if (!res.ok) throw new Error(`poll failed (${res.status})`);
      const data = await res.json();
      indicator.textContent = 'Live';
      indicator.classList.add('on');
      indicator.classList.remove('off');
      indicator.title = 'Live: pages update as things happen';
      // Missed some (the tab slept or the proxy restarted): reload what's on screen instead.
      if (data.reset && since !== null) refreshCurrentView();
      since = data.seq;
      for (const event of data.events) {
        if (event.type === 'log') onLiveLog(event.data);
        else if (event.type === 'player') onLivePlayer(event);
        else onPresence(event);
      }
      if (data.busy) await new Promise((resolve) => setTimeout(resolve, 5000));
    } catch (e) {
      indicator.textContent = 'Reconnecting…';
      indicator.classList.remove('on');
      indicator.classList.add('off');
      indicator.title = 'Reconnecting… pages still refresh every so often';
      await new Promise((resolve) => setTimeout(resolve, 5000));
    }
  }
}

// ---- Motion: page entrances, liquid tab pills, ripples ----

const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/** Cards and rows rise in one after another when a page opens (not on live refreshes). */
function playEnter(section) {
  if (!section || reducedMotion()) return;
  section.classList.remove('entering');
  void section.offsetWidth; // restart the animation
  section.classList.add('entering');
  clearTimeout(section.enterTimer);
  section.enterTimer = setTimeout(() => section.classList.remove('entering'), 1200);
}

/** A glass pill that glides (with a little liquid squash) to the active tab of every .tabs group. */
function updateTabPills() {
  document.querySelectorAll('.tabs').forEach((tabs) => {
    const active = tabs.querySelector('a.active');
    let pill = tabs.querySelector(':scope > .tab-pill');
    if (!active) {
      pill?.classList.add('gone');
      return;
    }
    if (!pill) {
      pill = el('span', { class: 'tab-pill', 'aria-hidden': 'true' });
      tabs.prepend(pill);
      tabs.classList.add('has-pill');
      pill.classList.add('instant');
    }
    const x = active.offsetLeft;
    const w = active.offsetWidth;
    if (pill.dataset.x === String(x) && pill.dataset.w === String(w) && !pill.classList.contains('gone')) return;
    const moved = pill.dataset.x !== undefined && !pill.classList.contains('instant');
    pill.dataset.x = x;
    pill.dataset.w = w;
    pill.classList.remove('gone');
    pill.style.width = `${w}px`;
    pill.style.transform = `translateX(${x}px)`;
    if (moved && !reducedMotion()) {
      pill.classList.remove('squish');
      void pill.offsetWidth;
      pill.classList.add('squish');
    }
    requestAnimationFrame(() => pill.classList.remove('instant'));
  });
}

let pillFrame = 0;
new MutationObserver(() => {
  cancelAnimationFrame(pillFrame);
  pillFrame = requestAnimationFrame(updateTabPills);
}).observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['class'] });
window.addEventListener('resize', updateTabPills);

/** A liquid ripple from the point where a button is pressed. */
document.addEventListener('pointerdown', (e) => {
  const button = e.target.closest('.btn, .tabs a, .icon-btn, .snap-item, .search-result');
  if (!button || button.disabled || reducedMotion()) return;
  const rect = button.getBoundingClientRect();
  const size = Math.max(rect.width, rect.height) * 2.2;
  const ripple = el('span', { class: 'ripple', 'aria-hidden': 'true' });
  ripple.style.width = ripple.style.height = `${size}px`;
  ripple.style.left = `${e.clientX - rect.left - size / 2}px`;
  ripple.style.top = `${e.clientY - rect.top - size / 2}px`;
  button.append(ripple);
  ripple.addEventListener('animationend', () => ripple.remove());
  setTimeout(() => ripple.remove(), 1000); // in case the animation never ends (e.g. a download started)
});

// ---- Navigation ----

/** The page in the address: #/view, #/view/sub-page or #/player/<uuid>[/inventory]. */
function currentRoute() {
  const match = location.hash.match(/^#\/player\/([0-9a-fA-F-]{36})(\/inventory)?$/);
  if (match) return { view: 'player', uuid: match[1].toLowerCase(), inventory: !!match[2] && isStaff() };
  let [view = '', sub = ''] = location.hash.replace(/^#\/?/, '').split('/');
  // Pages from before 0.18 moved: the Linked list is part of Players, the Market is a Shop tab.
  if (view === 'linked') view = 'players';
  if (view === 'market') [view, sub] = ['shop', 'sell'];
  if ((view === 'settings' || view === 'messages') && !state.me.isOwner) return { view: homeView() };
  // Each view only exists in its own mode; anything else goes to that mode's home page.
  if (isStaff() ? ADMIN_VIEWS.has(view) : MEMBER_VIEWS.has(view)) return { view, sub };
  return { view: homeView() };
}

function refreshCurrentView() {
  const route = currentRoute();
  if (route.view === 'home') loadHome();
  else if (route.view === 'players') loadPlayers();
  else if (route.view === 'player') loadPlayer(route.uuid);
  else if (route.view === 'moderate') loadModerate();
  else if (route.view === 'settings') loadSettings();
  else if (route.view === 'community') loadCommunity();
  else if (route.view === 'shop') loadShopView();
  else if (route.view === 'messages') loadMessageLog();
  else if (route.view === 'logs') loadLogs(true);
}

const VIEWS = ['logs', 'player', 'moderate', 'settings', 'messages', 'community', 'home', 'players', 'shop'];

function showView() {
  const route = currentRoute();
  for (const name of VIEWS) $(`view-${name}`).hidden = name !== route.view;
  document.querySelectorAll('#main-tabs a').forEach((a) => markCurrent(a, a.dataset.view === route.view));
  markCurrent($('settings-link'), route.view === 'settings' || route.view === 'messages');
  // Player pages name the player once they've loaded (renderPlayer); the rest use their heading, without its count.
  if (route.view !== 'player') {
    const h1 = $(`view-${route.view}`).querySelector('h1');
    document.title = `${h1 ? h1.firstChild.textContent.trim() : 'DiscordLinked'} · DiscordLinked`;
  }
  // On phones the tab row scrolls; keep the current tab in sight.
  const tabs = $('main-tabs');
  const active = tabs.querySelector('a.active');
  if (active && tabs.scrollWidth > tabs.clientWidth) {
    tabs.scrollLeft = Math.max(0, active.offsetLeft - (tabs.clientWidth - active.offsetWidth) / 2);
  }
  refreshCurrentView();
  window.scrollTo(0, 0);
  playEnter($(`view-${route.view}`));
}

window.addEventListener('hashchange', showView);
// The phone tab rows scroll sideways: bring a tab into sight when the keyboard moves to it.
document.querySelectorAll('.tabs').forEach((t) => t.addEventListener('focusin', (e) => {
  e.target.closest('a')?.scrollIntoView({ inline: 'nearest', block: 'nearest' });
}));

const dialogOpen = () => document.querySelector('dialog[open]') !== null;

// Keep things fresh (online status, admin colours, expiring suspensions).
setInterval(() => {
  if (dialogOpen() || document.hidden) return;
  if (!$('view-home').hidden) loadHome();
  else if (!$('view-players').hidden) loadPlayers();
}, 30000);
setInterval(() => {
  if (dialogOpen() || document.hidden || state.spy) return;
  if (!$('view-moderate').hidden) loadModerate();
}, 10000);
// Playtime and credits change every minute without a log entry.
setInterval(() => {
  if (dialogOpen() || document.hidden) return;
  if (!$('view-community').hidden) loadCommunity();
}, 60000);

(async () => {
  try {
    state.me = await api('/api/me');
  } catch (e) {
    return;
  }
  $('me-name').textContent = state.me.name;
  $('me-avatar').src = state.me.avatar;
  // Your name opens your own profile (with your unlocks), once your Minecraft account is linked.
  if (state.me.minecraftUuid) $('me-link').href = playerHref(state.me.minecraftUuid);
  else $('me-link').title = 'Link your Minecraft account to get a profile';
  applyMode();
  showView();
  pollSpy();
  connectLive();
  // Admin status is looked up in the background; pick it up shortly after the first load.
  setTimeout(() => { if (!$('view-players').hidden) loadPlayers(); }, 3000);
})();
