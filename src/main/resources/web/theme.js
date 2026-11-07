/* Runs synchronously in <head>, before style.css, on every page (the panel, login and message pages): applies the
   viewer's stored theme before the first paint, so there's no flash, and for 2049 with full effects plays the boot
   sequence once per tab session. The choice lives only in this browser (localStorage), never on the server. */
(function () {
  'use strict';
  var root = document.documentElement, theme = null, fx = null, seen = '1', timers = [];
  try { theme = localStorage.getItem('dl-theme'); fx = localStorage.getItem('dl-fx'); } catch (e) { /* storage blocked */ }
  if (theme === 'light' || theme === 'dark' || theme === '2049') root.setAttribute('data-theme', theme);
  root.setAttribute('data-fx', fx === 'calm' ? 'calm' : 'full');
  var LINES = ['DL/OS 2.049 cold start', 'uplink ........ locked', 'roster sync ... nominal', 'optics ........ online'];

  function still() {
    return root.getAttribute('data-fx') === 'calm'
      || !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  }
  /* The curtain opens: the page's own entrance (app.js) waits for this. */
  function reveal() {
    if (root.getAttribute('data-boot') !== '1') return;
    root.setAttribute('data-boot', '2');
    document.dispatchEvent(new Event('hud:reveal'));
  }
  function end() {
    timers.forEach(clearTimeout);
    timers = [];
    reveal();
    var overlayNode = document.querySelector('.hud-boot');
    if (overlayNode) overlayNode.remove();
    root.removeAttribute('data-boot');
    removeEventListener('keydown', end, true);
    removeEventListener('pointerdown', end, true);
  }
  function overlay() {
    if (root.getAttribute('data-boot') == null || document.querySelector('.hud-boot')) return;
    var box = document.createElement('div');
    box.className = 'hud-boot';
    box.setAttribute('aria-hidden', 'true');
    var line = document.createElement('i');
    line.className = 'hud-boot-line';
    var log = document.createElement('div');
    log.className = 'hud-boot-log';
    LINES.forEach(function (text, i) {
      var p = document.createElement('p');
      p.textContent = text;
      p.style.setProperty('--i', i);
      p.style.setProperty('--n', text.length);
      log.append(p);
    });
    box.append(line, log);
    document.body.append(box);
  }
  /* Plays the boot (2049, full effects, motion allowed). Never blocks input: any key or press ends it at once. */
  function boot() {
    if (root.getAttribute('data-theme') !== '2049' || still()) return false;
    end();
    try { sessionStorage.setItem('dl-2049-boot', '1'); } catch (e) { /* storage blocked */ }
    root.setAttribute('data-boot', '1');
    if (document.body) overlay(); else document.addEventListener('DOMContentLoaded', overlay, { once: true });
    timers.push(setTimeout(reveal, 560), setTimeout(end, 1400));
    addEventListener('keydown', end, true);
    addEventListener('pointerdown', end, true);
    return true;
  }
  window.hudBoot = boot;
  if (theme === '2049') {
    try { seen = sessionStorage.getItem('dl-2049-boot'); } catch (e) { seen = '1'; }
    if (!seen) boot();
  }
})();
