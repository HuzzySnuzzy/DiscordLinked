'use strict';

const state = { me: null, linked: [], logsBefore: null, logsLoading: false };

const EVENT_LABELS = {
  LINK: ['Linked', 'good'],
  UNLINK: ['Unlinked', 'bad'],
  SUSPEND: ['Suspended', 'bad'],
  KICK: ['Kicked', 'warn'],
  UNSUSPEND: ['Unsuspended', 'good'],
  JOIN: ['Join', 'info'],
  LEAVE: ['Leave', ''],
  SERVER_SWITCH: ['Server switch', ''],
  CHAT: ['Chat', ''],
  COMMAND: ['Command', ''],
  DENIED_UNLINKED: ['Denied: not linked', 'warn'],
  DENIED_SUSPENDED: ['Denied: suspended', 'warn'],
  WEB_LOGIN: ['Website login', 'info'],
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
function toast(message) {
  const node = $('toast');
  node.textContent = message;
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.hidden = true; }, 3500);
}

function copyButton(text) {
  return el('button', {
    class: 'copy mono', type: 'button', title: 'Click to copy',
    onclick: () => navigator.clipboard.writeText(text)
      .then(() => toast('Copied'), () => toast('Copying needs https — select the text instead')),
  }, text);
}

// ---- Action dialog ----

let dialogHandler = null;

function openDialog({ title, text, confirmLabel, confirmClass, duration, onConfirm }) {
  $('dialog-title').textContent = title;
  $('dialog-text').textContent = text;
  $('duration-fields').hidden = !duration;
  $('dur-days').value = 1;
  $('dur-hours').value = 0;
  $('dialog-reason').value = '';
  $('dialog-error').hidden = true;
  const confirm = $('dialog-confirm');
  confirm.textContent = confirmLabel;
  confirm.className = `btn ${confirmClass || ''}`;
  confirm.disabled = false;
  dialogHandler = onConfirm;
  $('action-dialog').showModal();
  $('dialog-reason').focus();
}

$('action-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const reason = $('dialog-reason').value.trim();
  const error = $('dialog-error');
  if (!reason) {
    error.textContent = 'Please enter a reason.';
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
    await loadLinked();
  } catch (e) {
    error.textContent = e.message;
    error.hidden = false;
    confirm.disabled = false;
  }
});
$('dialog-cancel').addEventListener('click', () => $('action-dialog').close());

// ---- Linked view ----

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
  const rows = state.linked.filter((p) => !query || [p.minecraftName, p.uuid, p.discordName, p.discordId]
    .some((value) => value.toLowerCase().includes(query)));
  $('linked-count').textContent = `(${state.linked.length})`;
  $('linked-body').replaceChildren(...rows.map(linkedRow));
  const empty = $('linked-empty');
  empty.textContent = state.linked.length ? 'No players match your search.' : 'No linked players yet.';
  empty.hidden = rows.length > 0;
}

function linkedRow(p) {
  const status = [];
  if (p.suspension) {
    status.push(el('span', { class: 'pill suspended' }, `Suspended · ${formatRemaining(p.suspension.expiresAt)} left`));
    status.push(el('div', { class: 'suspension-note' },
      `Until ${formatTime(p.suspension.expiresAt)} — "${p.suspension.reason}" — by ${p.suspension.createdBy}`));
  } else if (p.online) {
    status.push(el('span', { class: 'pill online' }, p.server ? `Online · ${p.server}` : 'Online'));
  } else {
    const seen = p.lastSeen ? `Offline · ${timeAgo(p.lastSeen)}` : 'Offline · never joined';
    status.push(el('span', { class: 'pill', title: p.lastSeen ? `Last seen ${formatTime(p.lastSeen)}` : null }, seen));
  }

  const actions = el('div', { class: 'actions' },
    p.online && el('button', { class: 'btn btn-ghost btn-small', type: 'button', onclick: () => kickDialog(p) }, 'Kick'),
    p.suspension
      ? el('button', { class: 'btn btn-success btn-small', type: 'button', onclick: () => unsuspendDialog(p) }, 'Unsuspend')
      : el('button', { class: 'btn btn-warning btn-small', type: 'button', onclick: () => suspendDialog(p) }, 'Suspend'),
    el('button', { class: 'btn btn-danger btn-small', type: 'button', onclick: () => unlinkDialog(p) }, 'Remove link'));

  return el('tr', {},
    el('td', {}, el('div', { class: 'identity' },
      el('img', { class: 'head', src: `https://mc-heads.net/avatar/${p.uuid}/72`, alt: '', loading: 'lazy' }),
      el('div', {}, el('div', { class: 'name' }, p.minecraftName), copyButton(p.uuid)))),
    el('td', {}, el('div', { class: 'identity' },
      el('img', { class: 'avatar', src: p.discordAvatar, alt: '', loading: 'lazy' }),
      el('div', {}, el('div', { class: 'name' }, p.discordName), copyButton(p.discordId)))),
    el('td', {}, status),
    el('td', { class: 'nowrap muted' }, formatTime(p.linkedAt)),
    el('td', {}, actions));
}

function suspendDialog(p) {
  openDialog({
    title: `Suspend ${p.minecraftName}`,
    text: 'They will be kicked if online and blocked from joining until the suspension ends.',
    confirmLabel: 'Suspend', confirmClass: 'btn-warning', duration: true,
    onConfirm: async ({ reason, days, hours }) => {
      await api('/api/suspend', { uuid: p.uuid, reason, days, hours });
      toast(`${p.minecraftName} suspended`);
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

$('linked-search').addEventListener('input', renderLinked);

// ---- Logs view ----

async function loadLogs(reset) {
  if (state.logsLoading) return;
  state.logsLoading = true;
  const params = new URLSearchParams({ filter: $('logs-filter').value, q: $('logs-search').value.trim() });
  if (!reset && state.logsBefore) params.set('before', state.logsBefore);
  try {
    const data = await api(`/api/logs?${params}`);
    const rows = data.entries.map(logRow);
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

function logRow(entry) {
  const [label, tone] = EVENT_LABELS[entry.type] || [entry.type, ''];
  return el('tr', {},
    el('td', { class: 'nowrap muted' }, formatTime(entry.time)),
    el('td', {}, el('span', { class: `badge ${tone}` }, label)),
    el('td', { class: 'nowrap' }, entry.player || '—'),
    el('td', {}, entry.actor || '—'),
    el('td', { class: 'detail' }, entry.detail || ''));
}

let searchTimer;
$('logs-search').addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => loadLogs(true), 300);
});
$('logs-filter').addEventListener('change', () => loadLogs(true));
$('logs-more').addEventListener('click', () => loadLogs(false));

// ---- Navigation ----

function showView() {
  const view = location.hash === '#/logs' ? 'logs' : 'linked';
  for (const name of ['linked', 'logs']) $(`view-${name}`).hidden = name !== view;
  document.querySelectorAll('.tabs a').forEach((a) => a.classList.toggle('active', a.dataset.view === view));
  if (view === 'linked') loadLinked();
  else loadLogs(true);
}

window.addEventListener('hashchange', showView);

// Keep the Linked page fresh (online status, expiring suspensions).
setInterval(() => {
  if (!$('view-linked').hidden && !$('action-dialog').open && !document.hidden) loadLinked();
}, 30000);

(async () => {
  try {
    state.me = await api('/api/me');
  } catch (e) {
    return;
  }
  $('me-name').textContent = state.me.name;
  $('me-avatar').src = state.me.avatar;
  showView();
})();
