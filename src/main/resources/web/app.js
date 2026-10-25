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
  toastTimer = setTimeout(() => { node.hidden = true; }, link ? 12000 : 3500);
}

function copyButton(text) {
  return el('button', {
    class: 'copy mono', type: 'button', title: 'Click to copy',
    onclick: () => navigator.clipboard.writeText(text)
      .then(() => toast('Copied'), () => toast('Copying needs https — select the text instead')),
  }, text);
}

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

function openDialog({ title, text, confirmLabel, confirmClass, duration, label, value, maxLength, onConfirm }) {
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
  reason.focus();
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
  const confirm = $('dialog-confirm');
  confirm.disabled = true;
  try {
    await dialogHandler({ reason, days, hours });
    $('action-dialog').close();
    refreshCurrentView();
  } catch (e) {
    error.textContent = e.message;
    error.hidden = false;
    confirm.disabled = false;
  }
});
$('dialog-cancel').addEventListener('click', () => $('action-dialog').close());

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
      el('div', {}, playerName(p), copyButton(p.uuid)))),
    el('td', { class: 'cell-discord' }, el('div', { class: 'identity' },
      el('img', { class: 'avatar', src: p.discordAvatar, alt: '', loading: 'lazy' }),
      el('div', {}, el('div', { class: `name ${p.isAdmin ? 'name-admin' : ''}` }, p.discordName), copyButton(p.discordId)))),
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
    el('td', { class: 'detail' }, entry.detail || ''));
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
  if (!state.player || state.player.player.uuid !== uuid) view.replaceChildren(el('p', { class: 'empty' }, 'Loading…'));
  try {
    state.player = await api(`/api/player?uuid=${encodeURIComponent(uuid)}`);
    renderPlayer();
    loadPlayerLogs(true);
  } catch (e) {
    view.replaceChildren(el('a', { class: 'back', href: '#/linked' }, '← Back'), el('p', { class: 'empty' }, e.message));
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
      copyButton(p.uuid),
      el('div', { class: 'actions' },
        el('a', { class: 'btn btn-ghost btn-small', href: `https://namemc.com/profile/${p.uuid}`, target: '_blank', rel: 'noopener' },
          'View on NameMC ↗'))),
    actionButtons(p));

  const discord = el('div', { class: 'card panel' },
    el('h2', {}, 'Discord'),
    p.linked
      ? el('div', { class: 'identity' },
        el('img', { class: 'avatar', src: p.discordAvatar, alt: '' }),
        el('div', {}, el('div', { class: `name ${p.isAdmin ? 'name-admin' : ''}` }, p.discordName), copyButton(p.discordId)))
      : el('p', { class: 'muted' }, 'This player has no linked Discord account.'),
    el('dl', { class: 'kv' },
      p.linked && [el('dt', {}, 'Linked'), el('dd', {}, formatTime(p.linkedAt))],
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
        el('td', { class: 'mono' }, ip.ip),
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

// ---- Navigation ----

function currentRoute() {
  const match = location.hash.match(/^#\/player\/([0-9a-fA-F-]{36})$/);
  if (match) return { view: 'player', uuid: match[1].toLowerCase() };
  return { view: location.hash === '#/logs' ? 'logs' : 'linked' };
}

function refreshCurrentView() {
  const route = currentRoute();
  if (route.view === 'linked') loadLinked();
  else if (route.view === 'player') loadPlayer(route.uuid);
}

function showView() {
  const route = currentRoute();
  for (const name of ['linked', 'logs', 'player']) $(`view-${name}`).hidden = name !== route.view;
  document.querySelectorAll('.tabs a').forEach((a) => a.classList.toggle('active', a.dataset.view === route.view));
  if (route.view === 'logs') loadLogs(true);
  else refreshCurrentView();
  window.scrollTo(0, 0);
}

window.addEventListener('hashchange', showView);

// Keep things fresh (online status, admin colours, expiring suspensions).
setInterval(() => {
  if ($('action-dialog').open || document.hidden) return;
  if (!$('view-linked').hidden) loadLinked();
}, 30000);

(async () => {
  try {
    state.me = await api('/api/me');
  } catch (e) {
    return;
  }
  $('me-name').textContent = state.me.name;
  $('me-avatar').src = state.me.avatar;
  $('logs-sheets').hidden = !state.me.sheetsEnabled;
  showView();
  // Admin status is looked up in the background; pick it up shortly after the first load.
  setTimeout(() => { if (!$('view-linked').hidden) loadLinked(); }, 3000);
})();
