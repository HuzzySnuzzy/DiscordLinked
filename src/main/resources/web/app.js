'use strict';

const state = { me: null, linked: [], logsBefore: null, logsLoading: false, player: null, playerLogsBefore: null };

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
};

// ---- Helpers ----

const $ = (id) => document.getElementById(id);

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
    class: 'copy mono', type: 'button', title: `Copy ${what}`,
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
  if (p.online) {
    pills.push(el('span', { class: 'pill online' }, p.server ? `Online · ${p.server}` : 'Online'));
  } else if (!p.suspension) {
    const seen = p.lastSeen ? `Offline · ${timeAgo(p.lastSeen)}` : 'Offline · never joined';
    pills.push(el('span', { class: 'pill', title: p.lastSeen ? `Last seen ${formatTime(p.lastSeen)}` : null }, seen));
  }
  return pills;
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
$('dialog-cancel').addEventListener('click', closeDialog);

/** Plays the closing animation, then closes. */
function closeDialog() {
  const dialog = $('action-dialog');
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

// ---- Actions (shared by the Linked list and the player page) ----

function actionButtons(p) {
  const small = (cls, label, handler) => el('button', { class: `btn ${cls} btn-small`, type: 'button', onclick: handler }, label);
  return el('div', { class: 'actions' },
    p.online && small('btn-ghost', 'Kick', () => kickDialog(p)),
    p.suspension
      ? [small('btn-ghost', 'Edit suspension', () => editSuspensionDialog(p)),
        small('btn-success', 'Unsuspend', () => unsuspendDialog(p))]
      : small('btn-warning', 'Suspend', () => suspendDialog(p)),
    small('btn-ghost', 'Note', () => noteDialog(p)),
    p.linked && small('btn-danger', 'Remove link', () => unlinkDialog(p)));
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

// ---- Linked view ----

const SORTS = {
  'name-asc': (a, b) => a.minecraftName.localeCompare(b.minecraftName, undefined, { sensitivity: 'base' }),
  'name-desc': (a, b) => b.minecraftName.localeCompare(a.minecraftName, undefined, { sensitivity: 'base' }),
  online: (a, b) => (b.online - a.online) || SORTS.seen(a, b),
  seen: (a, b) => (b.online - a.online) || ((b.lastSeen || 0) - (a.lastSeen || 0)),
  'linked-new': (a, b) => b.linkedAt - a.linkedAt,
  'linked-old': (a, b) => a.linkedAt - b.linkedAt,
  'joined-new': (a, b) => (joinedAt(b) || 0) - (joinedAt(a) || 0),
  suspended: (a, b) => (!!b.suspension - !!a.suspension) || SORTS['name-asc'](a, b),
  admins: (a, b) => (b.isAdmin - a.isAdmin) || SORTS['name-asc'](a, b),
};

try {
  const saved = localStorage.getItem('dl-sort');
  if (saved && SORTS[saved]) $('linked-sort').value = saved;
} catch (e) { /* storage unavailable */ }

async function loadLinked() {
  try {
    state.linked = await api('/api/linked');
    renderLinked();
  } catch (e) {
    toast(e.message);
  }
}

function renderLinked() {
  const query = $('linked-search').value.trim().toLowerCase();
  const rows = state.linked
    .filter((p) => !query || [p.minecraftName, p.uuid, p.discordName, p.discordId]
      .some((value) => value.toLowerCase().includes(query)))
    .sort(SORTS[$('linked-sort').value] || SORTS['name-asc']);
  $('linked-count').textContent = `(${state.linked.length})`;
  $('linked-body').replaceChildren(...rows.map(linkedRow));
  const empty = $('linked-empty');
  empty.textContent = state.linked.length ? 'No players match your filter.' : 'No linked players yet.';
  empty.hidden = rows.length > 0;
}

function linkedRow(p) {
  const status = statusPills(p);
  if (p.suspension) {
    status.push(el('div', { class: 'suspension-note' },
      `Until ${formatTime(p.suspension.expiresAt)} — "${p.suspension.reason}" — by ${p.suspension.createdBy}`));
  }
  if (!p.inGuild) status.push(el('div', { class: 'suspension-note' }, 'Not in the Discord server'));

  // The cell-* classes let the phone layout turn each row into a card.
  return el('tr', {},
    el('td', { class: 'cell-mc' }, el('div', { class: 'identity' },
      el('a', { href: playerHref(p.uuid) }, el('img', { class: 'head', src: headUrl(p.uuid, 72), alt: '', loading: 'lazy' })),
      el('div', {}, playerName(p), copyButton(p.uuid, 'UUID')))),
    el('td', { class: 'cell-discord' }, el('div', { class: 'identity' },
      discordLink(p, el('img', { class: 'avatar', src: p.discordAvatar, alt: '', loading: 'lazy' })),
      el('div', {}, discordName(p), copyButton(p.discordId, 'User ID')))),
    el('td', { class: 'cell-status' }, status),
    el('td', { class: 'cell-linked nowrap muted', title: formatTime(p.linkedAt) },
      el('span', { class: 'when-long' }, formatTime(p.linkedAt)),
      el('span', { class: 'when-short' }, shortDate(p.linkedAt))),
    el('td', { class: 'cell-actions' }, actionButtons(p)));
}

$('linked-search').addEventListener('input', renderLinked);
$('linked-sort').addEventListener('change', () => {
  try { localStorage.setItem('dl-sort', $('linked-sort').value); } catch (e) { /* storage unavailable */ }
  renderLinked();
});

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
    el('td', {}, entry.actor || '—'),
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

function renderPlayer() {
  const { player: p, suspensions, notes, ips } = state.player;
  const badges = [
    p.isAdmin && el('span', { class: 'badge warn' }, 'Admin'),
    p.linked ? el('span', { class: 'badge good' }, 'Linked') : el('span', { class: 'badge' }, 'Not linked'),
    p.linked && !p.inGuild && el('span', { class: 'badge bad' }, 'Left the Discord server'),
    ...statusPills(p),
  ];

  const profile = el('div', { class: 'card profile' },
    el('img', { class: 'head-large', src: headUrl(p.uuid, 192), alt: '' }),
    el('div', { class: 'info' },
      el('h1', { class: nameClass(p) }, p.minecraftName),
      el('div', { class: 'badges' }, badges),
      copyButton(p.uuid, 'UUID'),
      el('div', { class: 'actions' },
        el('a', { class: 'btn btn-ghost btn-small', href: `https://namemc.com/profile/${p.uuid}`, target: '_blank', rel: 'noopener' },
          'View on NameMC ↗'))),
    actionButtons(p));

  const discord = el('div', { class: 'card panel' },
    el('h2', {}, 'Discord'),
    p.linked
      ? el('div', { class: 'identity' },
        discordLink(p, el('img', { class: 'avatar', src: p.discordAvatar, alt: '' })),
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
    el('h2', { class: 'section-title' }, `Notes (${notes.length})`,
      el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => noteDialog(p) }, 'Add note')),
    notes.length
      ? el('div', { class: 'notes' }, notes.map((n) => el('div', { class: 'note' },
        el('p', {}, n.note), el('div', { class: 'meta' }, `${n.author} · ${formatTime(n.createdAt)}`))))
      : el('p', { class: 'muted' }, 'No notes yet.'),
  ];

  const historySection = [
    el('h2', { class: 'section-title' }, `Suspension history (${suspensions.length})`),
    suspensions.length ? el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
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
            : 'Expired'))))))
      : el('p', { class: 'muted' }, 'Never suspended.'),
  ];

  const ipSection = [
    el('h2', { class: 'section-title' }, `IP addresses (${ips.length})`),
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
      : el('p', { class: 'muted' }, 'No IPs recorded yet.'),
  ];

  const sheetsButton = state.me.sheetsEnabled && el('button', {
    class: 'btn btn-ghost btn-small', type: 'button', onclick: (e) => exportSheets(e.currentTarget, p.uuid),
  }, 'Export to Google Sheets');
  const activitySection = [
    el('h2', { class: 'section-title' }, 'Activity',
      el('div', { class: 'actions' },
        el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => downloadCsv(p.uuid) }, 'Download CSV'),
        sheetsButton)),
    el('div', { class: 'table-wrap' }, el('table', { class: 'table' },
      el('thead', {}, el('tr', {}, ['Time', 'Event', 'By', 'Details'].map((h) => el('th', {}, h)))),
      el('tbody', { id: 'player-logs' }))),
    el('div', { class: 'more' }, el('button', {
      class: 'btn btn-ghost', type: 'button', id: 'player-logs-more', hidden: true, onclick: () => loadPlayerLogs(false),
    }, 'Load more')),
  ];

  $('view-player').replaceChildren(
    el('a', { class: 'back', href: '#/linked' }, '← All linked players'),
    profile,
    el('div', { class: 'grid' }, discord, suspension),
    inventorySection(p),
    ...communitySection(p, state.player.community),
    ...notesSection, ...historySection, ...ipSection, ...activitySection);
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
  const modes = el('div', { class: 'tabs inv-modes' },
    el('a', { href: '#', class: mode === 'live' ? 'active' : '', onclick: (e) => { e.preventDefault(); loadInventory(); } }, 'Live'),
    el('a', { href: '#', class: mode === 'snapshots' ? 'active' : '', onclick: (e) => { e.preventDefault(); loadSnapshots(); } }, 'Snapshots'));
  const section = el('div', { class: 'card panel inventory-panel' },
    el('h2', {}, 'Inventory', modes),
    box);
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
  return section;
}

function renderInventory() {
  const box = $('inventory-box');
  const inv = state.inventory;
  if (!box || !inv) return;
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
    box.hidden = true;
    return;
  }
  box.replaceChildren(...(globalResults.length
    ? globalResults.map((p, i) => el('a', {
      class: `search-result ${i === globalActive ? 'active' : ''}`, href: playerHref(p.uuid), role: 'option',
      onclick: closeGlobalSearch,
    },
    el('img', { class: 'head', src: headUrl(p.uuid, 56), alt: '' }),
    el('div', { class: 'grow' },
      el('div', { class: `name ${nameClass(p)}` }, p.minecraftName),
      el('div', { class: 'sub' }, p.linked ? `Linked · ${p.discordName}` : 'Not linked')),
    statusPills(p)))
    : [el('div', { class: 'search-empty' }, 'No players found')]));
  box.hidden = false;
}

function closeGlobalSearch() {
  $('global-results').hidden = true;
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
    e.currentTarget.blur();
  } else if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && globalResults.length) {
    e.preventDefault();
    globalActive = (globalActive + (e.key === 'ArrowDown' ? 1 : -1) + globalResults.length) % globalResults.length;
    renderGlobalResults($('global-search').value.trim());
  } else if (e.key === 'Enter' && globalResults[globalActive]) {
    location.hash = playerHref(globalResults[globalActive].uuid);
    closeGlobalSearch();
  }
});

document.addEventListener('click', (e) => {
  if (!e.target.closest('.global-search')) $('global-results').hidden = true;
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
const TRANSACTION_KINDS = { PLAYTIME: 'Active play', ADVANCEMENT: 'Advancement', ADMIN: 'Staff', SHOP: 'Shop' };

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
      stat(c.rank ? `Balance · rank #${c.rank}` : 'Balance', credits(c.credits, currency)),
      stat('Earned in total', credits(c.earned, currency)),
      stat('Active playtime', playtime(c.activeSeconds), 'Time on the servers, not counting AFK'),
      staff && stat('AFK time', playtime(c.afkSeconds)),
      stat('Advancements', c.advancements.length.toLocaleString()),
      stat('Interactions', `${c.given.toLocaleString()} given · ${c.received.toLocaleString()} received`)),
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
    : el('p', { class: 'muted' }, `No ${currency.many} yet.`);

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
    : el('p', { class: 'muted' }, 'No advancements made since DiscordLinkedFAD was installed.');

  return [
    el('h2', { class: 'section-title' }, `${currency.many} & interactions`,
      staff && el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => creditsDialog(p, c) },
        `Give or take ${currency.many}`)),
    summary,
    staff && el('h2', { class: 'section-title' }, `${currency.one} history`),
    ledger,
    el('h2', { class: 'section-title' }, `Advancements (${c.advancements.length})`),
    advancements,
  ].filter(Boolean); // the callers spread this into replaceChildren, which would print false/null
}

const COMMUNITY_SORTS = [['credits', (c) => `Most ${c.many}`], ['earned', () => 'Most earned in total'],
  ['playtime', () => 'Most active playtime'], ['advancements', () => 'Most advancements'],
  ['given', () => 'Most interactions given'], ['received', () => 'Most interactions received']];

async function loadCommunity() {
  try {
    state.community = await api(`/api/community?sort=${encodeURIComponent(state.communitySort || 'credits')}`);
    renderCommunity();
  } catch (e) {
    $('community-body').replaceChildren(el('p', { class: 'empty card' }, e.message));
  }
}

function renderCommunity() {
  const d = state.community;
  const currency = d.currency;
  const r = d.rewards;
  $('community-earning').textContent = d.enabled
    ? `${credits(r.playtimeAmount, currency)} per ${r.playtimeMinutes} active minutes · ${r.task} / ${r.goal} / ${r.challenge} `
      + 'per advancement (task / goal / challenge)'
    : '';
  if (!d.enabled) {
    $('community-body').replaceChildren(el('div', { class: 'card panel' },
      el('p', {}, 'Community features (interactions and credits) are turned off.'),
      state.me.isOwner && el('a', { class: 'btn btn-small', href: '#/settings' }, 'Turn them on in Settings')));
    return;
  }
  const t = d.totals;
  const stats = el('div', { class: 'card panel' }, el('div', { class: 'stats-grid' },
    stat(`${currency.many} held`, t.credits.toLocaleString()),
    stat('Earned in total', t.earned.toLocaleString()),
    stat('Active playtime', playtime(t.activeSeconds)),
    stat('Advancements made', t.advancements.toLocaleString()),
    stat('Interactions', `${t.interactions.toLocaleString()} · ${t.interactionsToday.toLocaleString()} today`),
    stat('Players', t.players.toLocaleString())),
    state.me.isOwner && el('p', { class: 'muted' }, `Rename ${currency.many} or change how many players earn in `,
      el('a', { href: '#/settings' }, 'Settings'), '.'));

  const sort = el('select', { 'aria-label': 'Sort the leaderboard' },
    COMMUNITY_SORTS.map(([value, label]) => el('option', { value }, label(currency))));
  sort.value = d.sort;
  sort.addEventListener('change', () => {
    state.communitySort = sort.value;
    loadCommunity();
  });
  const filter = el('input', { type: 'search', placeholder: 'Find a player…', 'aria-label': 'Filter the leaderboard' });
  filter.value = state.communityFilter || '';
  const rows = el('tbody');
  const fillRows = () => {
    state.communityFilter = filter.value;
    const q = filter.value.trim().toLowerCase();
    const shown = d.leaderboard.map((row, i) => [row, i + 1]).filter(([row]) => !q || row.name.toLowerCase().includes(q));
    rows.replaceChildren(...shown.map(([row, rank]) => el('tr', {},
      el('td', { class: 'muted' }, `#${rank}`),
      el('td', {}, el('div', { class: 'identity' },
        el('a', { href: playerHref(row.uuid) }, el('img', { class: 'head', src: headUrl(row.uuid, 64), alt: '', loading: 'lazy' })),
        el('div', { class: 'leader-name' }, el('a', { class: 'name', href: playerHref(row.uuid) }, row.name),
          row.online && el('span', { class: 'pill online' }, 'Online')))),
      el('td', { class: 'nowrap' }, row.credits.toLocaleString()),
      // Phones only show rank, player and balance; the rest is on each player's page.
      el('td', { class: 'nowrap col-extra' }, playtime(row.activeSeconds)),
      el('td', { class: 'col-extra' }, row.advancements.toLocaleString()),
      el('td', { class: 'col-extra' }, row.given.toLocaleString()),
      el('td', { class: 'col-extra' }, row.received.toLocaleString()))));
    if (!shown.length) {
      rows.append(el('tr', {}, el('td', { colspan: 7, class: 'empty' },
        d.leaderboard.length ? 'No players match.' : `Nobody has earned any ${currency.many} yet.`)));
    }
  };
  filter.addEventListener('input', fillRows);
  fillRows();
  const leaderboard = el('div', { class: 'card panel' },
    el('h2', {}, 'Leaderboard', el('div', { class: 'actions' }, sort, filter)),
    el('div', { class: 'table-wrap flat' }, el('table', { class: 'table leaderboard' },
      el('thead', {}, el('tr', {}, ['#', 'Player', currency.many, 'Active playtime', 'Advancements', 'Given', 'Received']
        .map((h, i) => el('th', { class: i >= 3 ? 'col-extra' : null }, h)))),
      rows)));

  const person = (name, uuid) => (uuid ? el('a', { class: 'name', href: playerHref(uuid) }, name) : el('span', { class: 'name' }, name));
  const recent = el('div', { class: 'card panel' },
    el('h2', {}, 'Recent interactions'),
    d.recent.length ? el('div', { class: 'notes' }, d.recent.map((i) => el('div', { class: 'note' },
      el('p', {}, i.text),
      el('div', { class: 'meta' },
        person(i.actor, i.actorUuid), i.target ? [' → ', person(i.target, i.targetUuid)] : '',
        ` · /${i.action} · `, i.source === 'discord' ? 'Discord' : i.source, ' · ',
        el('span', { title: formatTime(i.time) }, timeAgo(i.time))))))
      : el('p', { class: 'muted' }, 'No interactions yet.'));

  const actions = el('div', { class: 'card panel' },
    el('h2', {}, `Interactions (${d.actions.length})`),
    d.synced
      ? el('div', { class: 'others' }, d.actions.map((a) => el('span', {
        class: 'chip', title: `${a.description} · ${a.lines} lines · ${a.target === 'required' ? 'needs a player' : a.target === 'optional' ? 'player optional' : 'no player'}`,
      }, `/${a.name} · ${a.uses.toLocaleString()}`)))
      : el('p', { class: 'muted' }, isStaff()
        ? 'None yet. Put DiscordLinkedFAD on your Minecraft servers; its interactions show up here once a player joins one of them.'
        : 'None yet.'),
    d.synced && el('p', { class: 'muted' }, d.discordCommands
      ? 'These also work as slash commands in Discord (e.g. /hug @someone).'
      : isStaff() && 'Slash commands for these are turned off in Settings.'),
    d.servers.length > 0 && el('p', { class: 'muted' }, 'DiscordLinkedFAD is running on ',
      d.servers.map((s) => `${s.name} (${s.version})`).join(', '), '.'),
    isStaff() && el('p', { class: 'muted' }, 'The lines, sounds and commands are set in DiscordLinkedFAD\'s config.yml on each server.'));

  $('community-body').replaceChildren(stats, leaderboard, el('div', { class: 'grid' }, recent, actions));
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
        class: 'btn btn-warning', type: 'button', onclick: () => openDialog({
          title: `Kick ${t.minecraftName}`, text: `Ends the session and kicks them. ${observed}`,
          confirmLabel: 'Kick', confirmClass: 'btn-warning',
          onConfirm: ({ reason }) => api('/api/spy/kick', { reason }),
        }),
      }, 'Kick'),
      !starting && !t.suspension && el('button', {
        class: 'btn btn-danger', type: 'button', onclick: () => openDialog({
          title: `Suspend ${t.minecraftName}`, text: `Ends the session and suspends them. ${observed}`,
          confirmLabel: 'Suspend', confirmClass: 'btn-danger', duration: { days: 1, hours: 0 },
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
  renderSettings();
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
    placeholder: field.type === 'discord_id' ? 'Empty' : null, inputmode: field.type === 'discord_id' ? 'numeric' : null,
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
      el('div', { class: 'setting-input' }, settingInput(field))))));

  const secret = (label, set, hint) => el('div', { class: 'setting' },
    el('div', { class: 'setting-text' }, el('div', { class: 'setting-label' }, label), el('div', { class: 'setting-help' }, hint)),
    el('span', { class: `badge ${set ? 'good' : 'warn'}` }, set ? 'Set' : 'Not set'));
  const secrets = el('div', { class: 'card panel settings-group' },
    el('h2', {}, 'Secrets'),
    el('p', { class: 'muted' }, 'For safety these can only be changed in plugins/discordlinked/config.properties.'),
    secret('Bot token', data.secrets.botToken, 'bot-token'),
    secret('Discord client secret', data.secrets.clientSecret, 'discord-client-secret (optional, for the Log in with Discord button)'),
    secret('Google service account key', data.secrets.googleKey, 'the key file for Google Sheets export (optional)'));

  const d = data.data;
  const stat = (label, value) => el('div', { class: 'stat' }, el('div', { class: 'stat-label' }, label), el('div', { class: 'stat-value' }, value));
  const dataCard = el('div', { class: 'card panel settings-group' },
    el('h2', {}, 'Data'),
    el('div', { class: 'stats-grid' },
      stat('Database size', formatBytes(d.databaseBytes)),
      stat('Linked players', d.linkedPlayers.toLocaleString()),
      stat('Log entries', `${d.logEntries.toLocaleString()} (${d.chatEntries.toLocaleString()} chat/commands)`),
      stat('Inventory snapshots', `${d.snapshots.toLocaleString()} (${formatBytes(d.snapshotBytes)})`),
      stat('Staff notes', d.notes.toLocaleString())),
    el('p', { class: 'muted' }, 'Cleanup runs automatically every hour using the retention settings above.'),
    el('div', { class: 'actions inv-start' },
      el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: (e) => settingsAction(e.currentTarget, 'cleanup') }, 'Clean up now'),
      el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: (e) => settingsAction(e.currentTarget, 'compact') }, 'Compact database')));

  $('settings-body').replaceChildren(el('div', { class: 'settings-grid' }, ...groups, secrets, dataCard));
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

  $('shop-tabs').replaceChildren(...SHOP_TABS.map(([tab, icon]) => el('button', {
    class: `shop-tab ${tab === shop.tab && !$('shop-search').value.trim() ? 'active' : ''}`, type: 'button', role: 'tab',
    title: tab, 'aria-label': tab, onclick: () => { shop.tab = tab; $('shop-search').value = ''; renderShop(); },
  }, shopIcon(icon), el('span', {}, tab))));
  renderShopGrid();
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

function renderShopTotal() {
  const d = shop.data;
  const t = shopTotal();
  const b = d.buyer;
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
      : !b.online ? 'Join the Minecraft server to check out' : !b.serverReady ? `${b.server} can't deliver orders` : '';
  buy.disabled = !!reason || shop.busy;
  buy.title = reason;
  buy.textContent = shop.busy ? 'Delivering…' : t.stacks && !reason ? `Buy for ${credits(t.credits, d.currency)}` : 'Check out';
  $('shop-clear').disabled = !t.stacks;
}

$('shop-search').addEventListener('input', () => { renderShop(); });
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
    const result = await api('/api/shop/checkout', { items });
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
  $('shop-item-error').hidden = true;
  $('shop-item-form').onsubmit = async (e) => {
    e.preventDefault();
    try {
      await api('/api/shop/item', { id: item.id, enabled: $('shop-item-enabled').checked, percent: Number($('shop-item-percent').value) });
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
  const stat = (label, value, extra) => el('div', { class: 'card stat-card' },
    el('div', { class: 'stat-label' }, label), el('div', { class: 'stat-value' }, value), extra);
  $('home-stats').replaceChildren(
    stat('Online now', data.onlineCount),
    stat('Players', data.playerCount.toLocaleString()),
    stat('Linked members', data.linkedCount),
    stat('Staff online', data.staffOnline),
    stat('Servers', data.servers.length,
      data.servers.length > 0 && el('div', { class: 'stat-sub' }, data.servers.map((s) => `${s.name} ${s.players}`).join(' · '))));
  $('home-online-count').textContent = `(${data.onlineCount})`;
  $('home-online').replaceChildren(...data.online.map(onlineCard));
  $('home-online-empty').hidden = data.online.length > 0;
  $('home-newest').replaceChildren(...(data.newest.length
    ? data.newest.map((p) => personRow(p, `Joined the community ${timeAgo(p.linkedAt)}`))
    : [el('p', { class: 'muted' }, 'No members yet.')]));
  $('home-recent').replaceChildren(...(data.recent.length
    ? data.recent.map((p) => personRow(p, `Seen ${timeAgo(p.lastSeen)}`))
    : [el('p', { class: 'muted' }, 'Nobody yet.')]));
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
  const members = state.members || [];
  const query = $('players-search').value.trim().toLowerCase();
  const linkedOnly = $('players-show').value === 'linked';
  const shown = linkedOnly ? members.filter((p) => p.linked) : members;
  const rows = shown
    .filter((p) => !query || [p.minecraftName, p.discordName || ''].some((v) => v.toLowerCase().includes(query)))
    .sort(SORTS[$('players-sort').value] || SORTS['name-asc']);
  $('players-count').textContent = `(${shown.length})`;
  // Long lists render in steps so a server with thousands of players stays responsive.
  const limit = state.playersLimit || 200;
  $('players-body').replaceChildren(...rows.slice(0, limit).map((p) => {
    const joined = joinedAt(p);
    return el('tr', {},
      el('td', { class: 'cell-mc' }, el('div', { class: 'identity' },
        el('a', { href: playerHref(p.uuid) }, el('img', { class: 'head', src: headUrl(p.uuid, 72), alt: '', loading: 'lazy' })),
        el('div', {}, playerName(p)))),
      el('td', { class: 'cell-discord' }, p.linked
        ? el('div', { class: 'identity' },
          discordLink(p, el('img', { class: 'avatar', src: p.discordAvatar, alt: '', loading: 'lazy' })),
          el('div', {}, discordName(p)))
        : el('span', { class: 'muted' }, 'Not linked')),
      el('td', { class: 'cell-status' }, statusPills(p)),
      el('td', { class: 'cell-linked nowrap muted', title: joined ? formatTime(joined) : '' },
        el('span', { class: 'when-long' }, joined ? shortDate(joined) : '—'),
        el('span', { class: 'when-short' }, joined ? shortDate(joined) : '—')));
  }));
  $('players-more').hidden = rows.length <= limit;
  $('players-more').textContent = `Show more (${(rows.length - limit).toLocaleString()} left)`;
  const empty = $('players-empty');
  empty.textContent = members.length ? 'No players match your filter.' : 'No players yet.';
  empty.hidden = rows.length > 0;
}

/** When they joined the community: when they linked, or else when they first played. */
const joinedAt = (p) => p.linkedAt || p.firstPlayed || null;

$('players-show').addEventListener('change', () => { state.playersLimit = 200; renderPlayers(); });
$('players-more').addEventListener('click', () => { state.playersLimit = (state.playersLimit || 200) + 400; renderPlayers(); });

$('players-search').addEventListener('input', () => { state.playersLimit = 200; renderPlayers(); });
$('players-sort').addEventListener('change', () => { state.playersLimit = 200; renderPlayers(); });

/** The simple profile everyone can see: who they are, their Discord, and whether they're on. */
function renderPublicPlayer() {
  const p = state.player.player;
  const profile = el('div', { class: 'card profile' },
    el('img', { class: 'head-large', src: headUrl(p.uuid, 192), alt: '' }),
    el('div', { class: 'info' },
      el('h1', { class: nameClass(p) }, p.minecraftName),
      el('div', { class: 'badges' },
        p.isAdmin && el('span', { class: 'badge warn' }, 'Staff'),
        p.linked ? el('span', { class: 'badge good' }, 'Member') : el('span', { class: 'badge' }, 'Not linked'),
        ...statusPills(p)),
      copyButton(p.uuid, 'UUID'),
      el('div', { class: 'actions' },
        el('a', { class: 'btn btn-ghost btn-small', href: `https://namemc.com/profile/${p.uuid}`, target: '_blank', rel: 'noopener' },
          'View on NameMC ↗'))));
  const discord = el('div', { class: 'card panel' },
    el('h2', {}, 'Discord'),
    p.linked
      ? el('div', { class: 'identity' },
        discordLink(p, el('img', { class: 'avatar', src: p.discordAvatar, alt: '' })),
        el('div', {}, discordName(p), copyButton(p.discordId, 'User ID')))
      : el('p', { class: 'muted' }, 'This player has no linked Discord account.'),
    p.linked && el('dl', { class: 'kv' }, el('dt', {}, 'Member since'), el('dd', {}, formatTime(p.linkedAt))));
  const server = el('div', { class: 'card panel' },
    el('h2', {}, 'On the server'),
    el('dl', { class: 'kv' },
      el('dt', {}, 'Status'), el('dd', {}, p.online ? `Online${p.server ? ` on ${p.server}` : ''}` : 'Offline'),
      p.firstPlayed && [el('dt', {}, 'First joined'), el('dd', {}, formatTime(p.firstPlayed))],
      !p.online && [el('dt', {}, 'Last seen'),
        el('dd', {}, p.lastSeen ? `${timeAgo(p.lastSeen)} (${formatTime(p.lastSeen)})` : 'Never')]));
  $('view-player').replaceChildren(
    el('a', { class: 'back', href: '#/players' }, '← All players'),
    profile,
    el('div', { class: 'grid' }, discord, server),
    ...communitySection(p, state.player.community));
}

function onPresence(change) {
  const route = currentRoute();
  if (!change.uuid && route.view !== 'community') return;
  if (route.view === 'home') soon('home', loadHome);
  else if (route.view === 'community') soon('community', loadCommunity);
  else if (route.view === 'shop') soon('shop', loadShop);
  else if (route.view === 'players') soon('players', loadPlayers);
  else if (route.view === 'player' && route.uuid === change.uuid) soon('player', () => loadPlayer(route.uuid));
}

// ---- On duty / off duty ----

const ADMIN_VIEWS = new Set(['linked', 'players', 'logs', 'moderate', 'settings', 'community', 'shop']);
const MEMBER_VIEWS = new Set(['home', 'community', 'players', 'shop']);
const homeHash = () => (isStaff() ? '#/linked' : '#/home');

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
  $('shop-tab').hidden = !(state.me.shopOpen || (state.me.isOwner && state.me.communityEnabled));
  $('global-search').placeholder = staff ? 'Find any player…' : 'Find a player…';
  updateTabPills();
}

$('duty-switch').addEventListener('change', async (e) => {
  const input = e.currentTarget;
  input.disabled = true;
  try {
    const me = await api('/api/duty', { onDuty: input.checked });
    state.me = { ...state.me, ...me };
    state.player = null;
    applyMode();
    toast(me.onDuty ? 'On duty: admin view' : 'Off duty: you see what everyone else sees');
    const target = currentRoute().view === 'player' ? location.hash : homeHash();
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

const STATUS_EVENTS = new Set(['JOIN', 'LEAVE', 'SERVER_SWITCH', 'LINK', 'UNLINK', 'SUSPEND', 'SUSPEND_EDIT',
  'UNSUSPEND', 'KICK', 'NOTE', 'DEATH', 'INVENTORY_RESTORE', 'DENIED_UNLINKED', 'DENIED_SUSPENDED', 'ADVANCEMENT',
  'CREDITS_ADJUST']);
// Events that change the Community page.
// Events that can change the Shop page (balances, whether you're online).
const SHOP_EVENTS = new Set(['SHOP_PURCHASE', 'CREDITS_ADJUST', 'ADVANCEMENT', 'JOIN', 'LEAVE', 'SERVER_SWITCH', 'SETTINGS_CHANGE']);
const COMMUNITY_EVENTS = new Set(['INTERACTION', 'ADVANCEMENT', 'CREDITS_ADJUST', 'JOIN', 'LEAVE']);
const pendingRefresh = {};

/** Coalesces bursts of events into one refresh. */
function soon(key, fn, ms = 700) {
  clearTimeout(pendingRefresh[key]);
  pendingRefresh[key] = setTimeout(fn, ms);
}

function onLiveLog(entry) {
  const route = currentRoute();
  if (route.view === 'logs') prependLog(entry);
  else if (route.view === 'linked' && STATUS_EVENTS.has(entry.type)) soon('linked', loadLinked);
  else if (route.view === 'players' && STATUS_EVENTS.has(entry.type)) soon('players', loadPlayers);
  else if (route.view === 'moderate' && STATUS_EVENTS.has(entry.type)) soon('moderate', loadModerate);
  else if (route.view === 'community' && COMMUNITY_EVENTS.has(entry.type)) soon('community', loadCommunity);
  else if (route.view === 'shop' && SHOP_EVENTS.has(entry.type)) soon('shop', loadShop);
  else if (route.view === 'player' && entry.uuid === route.uuid) {
    prependPlayerLog(entry);
    if (STATUS_EVENTS.has(entry.type)) soon('player', () => loadPlayer(route.uuid));
  }
}

function onLivePlayer(change) {
  const route = currentRoute();
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
      indicator.classList.add('on');
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
      indicator.classList.remove('on');
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

function currentRoute() {
  const match = location.hash.match(/^#\/player\/([0-9a-fA-F-]{36})(\/inventory)?$/);
  if (match) return { view: 'player', uuid: match[1].toLowerCase(), inventory: !!match[2] && isStaff() };
  const view = location.hash.replace(/^#\//, '');
  // Each view only exists in its own mode; anything else goes to that mode's home page.
  if (isStaff() ? ADMIN_VIEWS.has(view) : MEMBER_VIEWS.has(view)) return { view };
  return { view: isStaff() ? 'linked' : 'home' };
}

function refreshCurrentView() {
  const route = currentRoute();
  if (route.view === 'linked') loadLinked();
  else if (route.view === 'home') loadHome();
  else if (route.view === 'players') loadPlayers();
  else if (route.view === 'player') loadPlayer(route.uuid);
  else if (route.view === 'moderate') loadModerate();
  else if (route.view === 'settings') loadSettings();
  else if (route.view === 'community') loadCommunity();
  else if (route.view === 'shop') loadShop();
}

function showView() {
  const route = currentRoute();
  for (const name of ['linked', 'logs', 'player', 'moderate', 'settings', 'community', 'home', 'players', 'shop']) {
    $(`view-${name}`).hidden = name !== route.view;
  }
  document.querySelectorAll('.tabs a').forEach((a) => a.classList.toggle('active', a.dataset.view === route.view));
  if (route.view === 'logs') loadLogs(true);
  else refreshCurrentView();
  window.scrollTo(0, 0);
  playEnter($(`view-${route.view}`));
}

window.addEventListener('hashchange', showView);

// Keep things fresh (online status, admin colours, expiring suspensions).
setInterval(() => {
  if ($('action-dialog').open || document.hidden) return;
  if (!$('view-linked').hidden) loadLinked();
  else if (!$('view-home').hidden) loadHome();
  else if (!$('view-players').hidden) loadPlayers();
}, 30000);
setInterval(() => {
  if ($('action-dialog').open || document.hidden || state.spy) return;
  if (!$('view-moderate').hidden) loadModerate();
}, 10000);
// Playtime and credits change every minute without a log entry.
setInterval(() => {
  if ($('action-dialog').open || document.hidden) return;
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
  applyMode();
  showView();
  pollSpy();
  connectLive();
  // Admin status is looked up in the background; pick it up shortly after the first load.
  setTimeout(() => { if (!$('view-linked').hidden) loadLinked(); else if (!$('view-players').hidden) loadPlayers(); }, 3000);
})();
