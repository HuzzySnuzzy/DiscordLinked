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
  SPY_START: ['Spy started', 'info'],
  SPY_END: ['Spy ended', ''],
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
    if (state.spy) pollSpy();
    else refreshCurrentView();
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
    const changed = !state.player || state.player.player.uuid !== uuid;
    state.player = await api(`/api/player?uuid=${encodeURIComponent(uuid)}`);
    if (changed) state.inventory = null;
    renderPlayer();
    loadPlayerLogs(true);
    if (currentRoute().inventory && state.player.player.online && changed) loadInventory();
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
    inventorySection(p),
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

async function loadInventory() {
  const box = $('inventory-box');
  if (!box) return;
  box.replaceChildren(el('p', { class: 'muted' }, 'Loading inventory…'));
  try {
    const data = await api(`/api/player/inventory?uuid=${encodeURIComponent(state.player.player.uuid)}`);
    const textures = await loadTextures(data.mcVersion);
    state.inventory = { data, textures, tab: 'inventory' };
    renderInventory();
  } catch (e) {
    box.replaceChildren(el('p', { class: 'error' }, e.message));
  }
}

function inventorySection(p) {
  const box = el('div', { id: 'inventory-box' });
  const section = el('div', { class: 'card panel inventory-panel' },
    el('h2', {}, 'Inventory',
      p.online && el('button', { class: 'btn btn-ghost btn-small', type: 'button', id: 'inventory-button', onclick: loadInventory },
        state.inventory ? 'Refresh' : 'View inventory')),
    box);
  if (!p.online) box.append(el('p', { class: 'muted' }, 'The inventory can be viewed while the player is online.'));
  else if (state.inventory) queueMicrotask(renderInventory);
  else box.append(el('p', { class: 'muted' }, 'Shows their live inventory, armour, offhand and ender chest. Viewing is logged.'));
  return section;
}

function renderInventory() {
  const box = $('inventory-box');
  if (!box || !state.inventory) return;
  const { data, textures, tab } = state.inventory;
  const slot = (item, extra = '') => inventorySlot(item, textures, extra);
  const tabs = el('div', { class: 'tabs inv-tabs' },
    ['inventory', 'ender'].map((name) => el('a', {
      href: '#', class: tab === name ? 'active' : '',
      onclick: (e) => { e.preventDefault(); state.inventory.tab = name; renderInventory(); },
    }, name === 'inventory' ? 'Inventory' : 'Ender chest')));
  const stats = el('div', { class: 'inv-stats' },
    el('span', { class: 'chip' }, `Health ${data.health} / 20`),
    el('span', { class: 'chip' }, `Food ${data.food} / 20`),
    el('span', { class: 'chip' }, `Level ${data.level}`),
    el('span', { class: 'chip' }, titleCase(data.gameMode.toLowerCase())),
    el('span', { class: 'chip mono' }, `${data.world} ${data.x}, ${data.y}, ${data.z}`),
    el('span', { class: 'muted' }, `on ${data.server}`));

  let body;
  if (tab === 'ender') {
    body = el('div', { class: 'inv-rows' }, el('div', { class: 'inv-grid' }, data.enderChest.map((i) => slot(i))));
  } else {
    const armor = [...data.armor].reverse(); // helmet first
    body = el('div', { class: 'inv-layout' },
      el('div', { class: 'inv-side' },
        armor.map((i, n) => slot(i, ['helmet', 'chestplate', 'leggings', 'boots'][n])),
        el('div', { class: 'inv-gap' }), slot(data.offhand, 'offhand')),
      el('div', { class: 'inv-rows' },
        el('div', { class: 'inv-grid' }, data.storage.slice(9).map((i) => slot(i))),
        el('div', { class: 'inv-grid hotbar' }, data.storage.slice(0, 9).map((i, n) => slot(i, n === data.selectedSlot ? 'selected' : '')))));
  }
  box.replaceChildren(stats, tabs, body, el('div', { id: 'inv-contents' }));
  const button = $('inventory-button');
  if (button) button.textContent = 'Refresh';
}

function inventorySlot(item, textures, extra) {
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
    if (item.contents) showContents(item, textures);
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
      el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => target.replaceChildren() }, 'Close')),
    el('div', { class: 'inv-grid' }, item.contents.map((i) => inventorySlot(i, textures, '')))));
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
        el('div', { class: 'sub' }, p.linked ? p.discordName : 'Not linked'),
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
  $('spy-card').replaceChildren(
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
      }, 'Suspend')));
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

// ---- Navigation ----

function currentRoute() {
  const match = location.hash.match(/^#\/player\/([0-9a-fA-F-]{36})(\/inventory)?$/);
  if (match) return { view: 'player', uuid: match[1].toLowerCase(), inventory: !!match[2] };
  const views = { '#/logs': 'logs', '#/moderate': 'moderate' };
  return { view: views[location.hash] || 'linked' };
}

function refreshCurrentView() {
  const route = currentRoute();
  if (route.view === 'linked') loadLinked();
  else if (route.view === 'player') loadPlayer(route.uuid);
  else if (route.view === 'moderate') loadModerate();
}

function showView() {
  const route = currentRoute();
  for (const name of ['linked', 'logs', 'player', 'moderate']) $(`view-${name}`).hidden = name !== route.view;
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
setInterval(() => {
  if ($('action-dialog').open || document.hidden || state.spy) return;
  if (!$('view-moderate').hidden) loadModerate();
}, 10000);

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
  pollSpy();
  // Admin status is looked up in the background; pick it up shortly after the first load.
  setTimeout(() => { if (!$('view-linked').hidden) loadLinked(); }, 3000);
})();
