/* Nova Player — playback overlay logic. All video interaction lives here. */
'use strict';

const $ = s => document.querySelector(s);
const stage = $('#stage');

let P = {};                 // mpv property cache
let SET = {};               // app settings
let seeking = false;
let suppressUntil = 0;      // ignore time-pos updates right after a manual seek
let hideTimer = null;
let lastSeekSend = 0;

// ---------------- helpers ----------------
function fmt(s) {
  if (s == null || !isFinite(s)) return '0:00';
  s = Math.max(0, Math.round(s));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
           : `${m}:${String(sec).padStart(2, '0')}`;
}
function esc(x) {
  return String(x).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
const cmd = (...c) => window.player.cmd(...c);
const setProp = (name, v) => cmd('set_property', name, v);

let toastTimer;
function toast(msg) {
  const t = $('#p-toast');
  t.textContent = msg;
  t.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), 2200);
}

// ---------------- UI updates from properties ----------------
function upPlay() {
  const paused = P.pause === true;
  $('#ic-play').classList.toggle('hidden', !paused);
  $('#ic-pause').classList.toggle('hidden', paused);
  if (paused) showUI();
  scheduleHide();
}
function upTime() {
  if (!seeking && Date.now() > suppressUntil) {
    $('#t-cur').textContent = fmt(P['time-pos']);
    upSeekFill(P['time-pos']);
  }
  $('#t-dur').textContent = fmt(P.duration);
}
function upSeekFill(t) {
  const d = P.duration || 0;
  const pct = d > 0 ? Math.min(100, Math.max(0, (t / d) * 100)) : 0;
  $('#seek-fill').style.width = pct + '%';
  $('#seek-thumb').style.left = pct + '%';
}
function upVolume() {
  const v = Math.round(P.volume ?? 100);
  const slider = $('#vol-slider');
  if (document.activeElement !== slider) slider.value = v;
  const lbl = $('#vol-label');
  lbl.textContent = v + '%';
  lbl.classList.toggle('boost', v > 100);
  const muted = P.mute === true;
  $('#vol-w1').style.opacity = muted || v === 0 ? 0.25 : 1;
  $('#vol-w2').style.opacity = muted || v < 60 ? 0.25 : 1;
  $('#btn-mute').classList.toggle('on', muted);
}
function upSpeed() {
  const sp = P.speed || 1;
  $('#btn-speed').textContent = (Math.round(sp * 100) / 100) + '×';
  $('#btn-speed').classList.toggle('on', Math.abs(sp - 1) > 0.001);
}
function upTitle() {
  $('#v-title').textContent = P['media-title'] || '';
  const pos = (P['playlist-pos'] ?? -1) + 1, cnt = P['playlist-count'] || 0;
  $('#v-sub').textContent = cnt > 1 ? `${pos} of ${cnt} in queue` : ' ';
  $('#pl-count').textContent = cnt > 1 ? cnt : '';
}
function upAB() {
  const a = P['ab-loop-a'], b = P['ab-loop-b'];
  const btn = $('#btn-ab');
  const aSet = a != null && a !== 'no', bSet = b != null && b !== 'no';
  btn.classList.toggle('on', aSet);
  btn.textContent = aSet && bSet ? 'A·B ✓' : aSet ? 'A·–' : 'A·B';
}
function upFs() {
  const fs = P.fullscreen === true;
  $('#ic-fs').classList.toggle('hidden', fs);
  $('#ic-unfs').classList.toggle('hidden', !fs);
}
function upSpinner() {
  const buffering = P['paused-for-cache'] === true && P.pause !== true;
  $('#spinner').classList.toggle('hidden', !buffering);
}
function upEnd() {
  $('#end-screen').classList.toggle('hidden', P['eof-reached'] !== true);
  if (P['eof-reached'] === true) showUI();
}
function upChapters() {
  const list = P['chapter-list'] || [];
  const d = P.duration || 0;
  const wrap = $('#chapter-marks');
  if (!d || list.length < 2) { wrap.innerHTML = ''; return; }
  wrap.innerHTML = list
    .filter(c => c.time > 0)
    .map(c => `<div class="ch-mark" style="left:${(c.time / d) * 100}%"></div>`).join('');
}

const UPDATERS = {
  'pause': upPlay,
  'time-pos': upTime, 'duration': () => { upTime(); upChapters(); },
  'volume': upVolume, 'mute': upVolume,
  'speed': upSpeed,
  'media-title': upTitle, 'playlist-pos': upTitle, 'playlist-count': upTitle,
  'ab-loop-a': upAB, 'ab-loop-b': upAB,
  'fullscreen': upFs,
  'paused-for-cache': upSpinner,
  'eof-reached': upEnd,
  'chapter-list': upChapters
};

window.player.onProp(({ name, data }) => {
  P[name] = data;
  const fn = UPDATERS[name];
  if (fn) fn();
  // live-refresh an open popover, but never while the user is interacting with it
  if (popoverKind && !popPointerDown && POP_REFRESH[popoverKind] &&
      POP_REFRESH_PROPS[popoverKind]?.includes(name)) {
    POP_REFRESH[popoverKind]();
  }
});

// ---------------- auto-hide ----------------
function showUI() {
  stage.classList.remove('hide-ui');
  scheduleHide();
}
function scheduleHide() {
  clearTimeout(hideTimer);
  hideTimer = setTimeout(() => {
    const hoverBar = document.querySelector('.bar:hover, #popover:hover');
    if (P.pause === true || popoverKind || seeking || hoverBar || P['eof-reached'] === true) {
      scheduleHide();
      return;
    }
    stage.classList.add('hide-ui');
    closeCtx();
  }, 2600);
}
document.addEventListener('mousemove', showUI);

// ---------------- seekbar ----------------
const seekRow = $('#seek-row');
function seekTimeAt(clientX) {
  const r = $('#seek-track').getBoundingClientRect();
  const frac = Math.min(1, Math.max(0, (clientX - r.left) / r.width));
  return frac * (P.duration || 0);
}
seekRow.addEventListener('pointerdown', e => {
  if (!P.duration) return;
  seeking = true;
  seekRow.classList.add('dragging');
  seekRow.setPointerCapture(e.pointerId);
  onSeekMove(e, true);
});
seekRow.addEventListener('pointermove', e => {
  if (seeking) onSeekMove(e, false);
  const t = seekTimeAt(e.clientX);
  const tip = $('#seek-tip');
  tip.textContent = fmt(t);
  tip.classList.remove('hidden');
  const r = seekRow.getBoundingClientRect();
  tip.style.left = (e.clientX - r.left) + 'px';
});
seekRow.addEventListener('pointerleave', () => $('#seek-tip').classList.add('hidden'));
seekRow.addEventListener('pointerup', e => {
  if (!seeking) return;
  seeking = false;
  seekRow.classList.remove('dragging');
  const t = seekTimeAt(e.clientX);
  suppressUntil = Date.now() + 500;
  cmd('seek', t, 'absolute+exact');
  $('#t-cur').textContent = fmt(t);
  upSeekFill(t);
});
function onSeekMove(e, force) {
  const t = seekTimeAt(e.clientX);
  $('#t-cur').textContent = fmt(t);
  upSeekFill(t);
  const now = Date.now();
  if (force || now - lastSeekSend > 150) {
    lastSeekSend = now;
    cmd('seek', t, 'absolute+keyframes');
    suppressUntil = now + 400;
  }
}

// ---------------- basic controls ----------------
$('#btn-play').addEventListener('click', () => cmd('cycle', 'pause'));
$('#btn-prev').addEventListener('click', () => cmd('playlist-prev'));
$('#btn-next').addEventListener('click', () => cmd('playlist-next'));
$('#btn-b10').addEventListener('click', () => relSeek(-seekStep()));
$('#btn-f10').addEventListener('click', () => relSeek(seekStep()));
$('#btn-mute').addEventListener('click', () => cmd('cycle', 'mute'));
$('#btn-ab').addEventListener('click', () => { cmd('ab-loop'); toast('A-B loop point'); });
$('#btn-shot').addEventListener('click', () => { cmd('screenshot'); toast('Screenshot saved to Desktop'); });
$('#btn-fs').addEventListener('click', () => setProp('fullscreen', !(P.fullscreen === true)));
$('#btn-back').addEventListener('click', () => cmd('quit-watch-later'));
$('#btn-replay').addEventListener('click', () => { cmd('seek', 0, 'absolute'); setProp('pause', false); });
$('#btn-end-back').addEventListener('click', () => cmd('quit-watch-later'));
$('#btn-min').addEventListener('click', () => window.player.winCmd('min'));
$('#btn-max').addEventListener('click', () => window.player.winCmd('max'));
$('#btn-close').addEventListener('click', () => window.player.winCmd('close'));

function seekStep() {
  const v = Number(SET.seekStep);
  return isFinite(v) && v > 0 ? v : 5;
}
function relSeek(sec) {
  // "exact" so a 5s skip really moves 5s — a plain relative seek snaps to the
  // nearest keyframe, which on many files is 10s or more away.
  cmd('seek', sec, 'relative+exact');
  suppressUntil = 0;
}

$('#vol-slider').addEventListener('input', e => setProp('volume', +e.target.value));

// window dragging via the top bar (custom title bar on a child window)
$('#top-bar').addEventListener('mousedown', e => {
  if (e.button !== 0 || e.target.closest('.pbtn')) return;
  window.player.dragStart();
  const up = () => { window.player.dragEnd(); removeEventListener('mouseup', up); };
  addEventListener('mouseup', up);
});
$('#top-bar').addEventListener('dblclick', e => {
  if (!e.target.closest('.pbtn')) window.player.winCmd('max');
});

// ---------------- popovers ----------------
let popoverKind = null;
let popPointerDown = false;
const pop = $('#popover');
const POP_REFRESH = {};
pop.addEventListener('pointerdown', () => { popPointerDown = true; });
document.addEventListener('pointerup', () => { popPointerDown = false; });

/* Buttons toggle; property changes re-render in place. */
function togglePopover(kind) {
  if (popoverKind === kind) return closePopover();
  popoverKind = kind;
  POP_REFRESH[kind]();
}
const POP_REFRESH_PROPS = {
  audio: ['track-list'],
  subs: ['track-list', 'sub-delay', 'sub-visibility', 'sub-scale', 'sub-pos'],
  speed: ['speed'],
  settings: ['video-zoom', 'video-rotate', 'loop-file', 'video-aspect-override'],
  playlist: ['playlist-pos']
};

function closePopover() { popoverKind = null; pop.classList.add('hidden'); }
function openPopoverKeep(kind, html) {
  popoverKind = kind;
  pop.innerHTML = html;
  pop.classList.remove('hidden');
}
document.addEventListener('mousedown', e => {
  if (popoverKind && !e.target.closest('#popover') &&
      !e.target.closest('#btn-audio,#btn-subs,#btn-speed,#btn-settings,#btn-playlist')) {
    closePopover();
  }
  if (!e.target.closest('#ctx')) closeCtx();
});

function tracks(type) {
  return (P['track-list'] || []).filter(t => t.type === type);
}
function trackLabel(t) {
  const bits = [];
  if (t.title) bits.push(t.title);
  if (t.lang) bits.push(t.lang.toUpperCase());
  if (!bits.length) bits.push(`Track ${t.id}`);
  return bits.join(' · ');
}
function trackSide(t) {
  const bits = [];
  if (t.codec) bits.push(t.codec);
  if (t['demux-channel-count']) bits.push(t['demux-channel-count'] + 'ch');
  if (t['demux-h']) bits.push(t['demux-h'] + 'p');
  return bits.join(' ');
}

// --- audio ---
$('#btn-audio').addEventListener('click', () => togglePopover('audio'));
POP_REFRESH.audio = () => {
  const list = tracks('audio');
  const html = `<div class="pop-head">Audio tracks</div>` +
    (list.length ? list.map(t => `
      <div class="pop-item ${t.selected ? 'sel' : ''}" data-aid="${t.id}">
        <span class="check">${t.selected ? '✓' : ''}</span>
        <span class="p-main">${esc(trackLabel(t))}</span>
        <span class="p-side">${esc(trackSide(t))}</span>
      </div>`).join('') : '<div class="pop-row"><label>No audio tracks</label></div>') +
    `<div class="pop-sep"></div>
     <div class="pop-row"><label>Audio sync</label>
       <div><button class="mini-btn" id="ad-minus">−0.1s</button>
       <button class="mini-btn" id="ad-plus">+0.1s</button>
       <button class="mini-btn acc" id="ad-zero">Reset</button></div></div>`;
  openPopoverKeep('audio', html);
  pop.querySelectorAll('[data-aid]').forEach(el =>
    el.addEventListener('click', () => { setProp('aid', +el.dataset.aid); }));
  $('#ad-minus').addEventListener('click', () => cmd('add', 'audio-delay', -0.1));
  $('#ad-plus').addEventListener('click', () => cmd('add', 'audio-delay', 0.1));
  $('#ad-zero').addEventListener('click', () => setProp('audio-delay', 0));
};

// --- subtitles ---
$('#btn-subs').addEventListener('click', () => togglePopover('subs'));
POP_REFRESH.subs = () => {
  const list = tracks('sub');
  const anySel = list.some(t => t.selected);
  const visible = P['sub-visibility'] !== false;
  const html = `<div class="pop-head">Subtitles
      <button class="mini-btn" id="sub-load">Load file…</button></div>
    <div class="pop-item ${!anySel || !visible ? 'sel' : ''}" id="sub-off">
      <span class="check">${!anySel || !visible ? '✓' : ''}</span><span class="p-main">Off</span></div>` +
    list.map(t => `
      <div class="pop-item ${t.selected && visible ? 'sel' : ''}" data-sid="${t.id}">
        <span class="check">${t.selected && visible ? '✓' : ''}</span>
        <span class="p-main">${esc(trackLabel(t))}</span>
        <span class="p-side">${esc(t.codec || '')}</span>
      </div>`).join('') +
    `<div class="pop-sep"></div>
     <div class="pop-row"><label>Sync ${(P['sub-delay'] ?? 0).toFixed(1)}s</label>
       <div><button class="mini-btn" id="sd-minus">−0.1s</button>
       <button class="mini-btn" id="sd-plus">+0.1s</button>
       <button class="mini-btn acc" id="sd-zero">0</button></div></div>
     <div class="pop-row"><label>Size ${Math.round((P['sub-scale'] || 1) * 100)}%</label>
       <div><button class="mini-btn" id="ss-minus">A−</button>
       <button class="mini-btn" id="ss-plus">A+</button></div></div>
     <div class="pop-row"><label>Position</label>
       <div><button class="mini-btn" id="sp-up">▲ Higher</button>
       <button class="mini-btn" id="sp-down">▼ Lower</button>
       <button class="mini-btn acc" id="sp-def">Default</button></div></div>`;
  openPopoverKeep('subs', html);
  $('#sub-off').addEventListener('click', () => setProp('sid', 'no'));
  pop.querySelectorAll('[data-sid]').forEach(el =>
    el.addEventListener('click', () => { setProp('sub-visibility', true); setProp('sid', +el.dataset.sid); }));
  $('#sub-load').addEventListener('click', async () => {
    const r = await window.player.loadSubtitle();
    if (r && !r.error) toast('Subtitle loaded');
  });
  $('#sd-minus').addEventListener('click', () => cmd('add', 'sub-delay', -0.1));
  $('#sd-plus').addEventListener('click', () => cmd('add', 'sub-delay', 0.1));
  $('#sd-zero').addEventListener('click', () => setProp('sub-delay', 0));
  $('#ss-minus').addEventListener('click', () => cmd('add', 'sub-scale', -0.05));
  $('#ss-plus').addEventListener('click', () => cmd('add', 'sub-scale', 0.05));
  // position: lower sub-pos value = higher on screen; remembered across sessions
  const setSubPos = v => {
    v = Math.max(20, Math.min(150, v));
    setProp('sub-pos', v);
    window.player.saveSetting({ subPos: v });
  };
  $('#sp-up').addEventListener('click', () => setSubPos((P['sub-pos'] ?? 90) - 5));
  $('#sp-down').addEventListener('click', () => setSubPos((P['sub-pos'] ?? 90) + 5));
  $('#sp-def').addEventListener('click', () => setSubPos(90));
};

// --- speed ---
$('#btn-speed').addEventListener('click', () => togglePopover('speed'));
POP_REFRESH.speed = () => {
  const cur = P.speed || 1;
  const presets = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 3];
  const html = `<div class="pop-head">Playback speed</div>
    <div class="chip-row">${presets.map(v =>
      `<button class="chip ${Math.abs(cur - v) < 0.01 ? 'sel' : ''}" data-sp="${v}">${v}×</button>`).join('')}</div>
    <div class="pop-row"><label>Custom</label>
      <input type="range" id="sp-slider" min="0.25" max="4" step="0.05" value="${cur}">
      <span class="val">${cur.toFixed(2)}×</span></div>`;
  openPopoverKeep('speed', html);
  pop.querySelectorAll('[data-sp]').forEach(el =>
    el.addEventListener('click', () => setProp('speed', +el.dataset.sp)));
  $('#sp-slider').addEventListener('input', e => {
    setProp('speed', +e.target.value);
    e.target.parentElement.querySelector('.val').textContent = (+e.target.value).toFixed(2) + '×';
  });
};

// --- settings (video) ---
$('#btn-settings').addEventListener('click', () => togglePopover('settings'));
POP_REFRESH.settings = () => {
  const zoom = P['video-zoom'] || 0;
  const rot = P['video-rotate'] || 0;
  const asp = P['video-aspect-override'] || -1;
  const aspects = [['-1', 'Auto'], ['16:9', '16:9'], ['4:3', '4:3'], ['2.35:1', '2.35:1']];
  const html = `<div class="pop-head">Video settings
      <button class="mini-btn acc" id="vs-reset">Reset</button></div>
     <div class="pop-row"><label>Zoom</label>
       <input type="range" id="vz" min="-0.5" max="1.5" step="0.05" value="${zoom}">
       <span class="val">${Math.round(Math.pow(2, zoom) * 100)}%</span></div>
     <div class="pop-row"><label>Aspect ratio</label></div>
     <div class="chip-row">${aspects.map(([v, l]) =>
       `<button class="chip ${String(asp) === v ? 'sel' : ''}" data-asp="${v}">${l}</button>`).join('')}</div>
     <div class="pop-row"><label>Rotate</label>
       <div><button class="mini-btn" id="rot-btn">↻ ${rot}°</button></div></div>
     <div class="pop-row"><label>Loop this video</label>
       <div><button class="mini-btn ${P['loop-file'] && P['loop-file'] !== 'no' ? 'acc' : ''}" id="loop-btn">${P['loop-file'] && P['loop-file'] !== 'no' ? 'On' : 'Off'}</button></div></div>`;
  openPopoverKeep('settings', html);
  $('#vz').addEventListener('input', e => {
    setProp('video-zoom', +e.target.value);
    e.target.parentElement.querySelector('.val').textContent = Math.round(Math.pow(2, +e.target.value) * 100) + '%';
  });
  pop.querySelectorAll('[data-asp]').forEach(el =>
    el.addEventListener('click', () => setProp('video-aspect-override', el.dataset.asp)));
  $('#rot-btn').addEventListener('click', () => setProp('video-rotate', ((P['video-rotate'] || 0) + 90) % 360));
  $('#loop-btn').addEventListener('click', () =>
    setProp('loop-file', P['loop-file'] && P['loop-file'] !== 'no' ? 'no' : 'inf'));
  $('#vs-reset').addEventListener('click', () => {
    // also quietly clears anything the brightness gesture or hotkeys changed
    ['brightness', 'contrast', 'saturation', 'gamma'].forEach(k => setProp(k, 0));
    setProp('video-zoom', 0); setProp('video-rotate', 0); setProp('video-aspect-override', '-1');
  });
};

// --- playlist ---
$('#btn-playlist').addEventListener('click', () => togglePopover('playlist'));
POP_REFRESH.playlist = async () => {
  const list = await window.player.get('playlist') || [];
  const html = `<div class="pop-head">Playlist — ${list.length}</div>` +
    list.map((it, i) => {
      const name = it.title || (it.filename || '').split(/[\\/]/).pop();
      return `<div class="pop-item ${it.current ? 'sel' : ''}" data-pli="${i}">
        <span class="check">${it.current ? '▶' : (i + 1)}</span>
        <span class="p-main">${esc(name)}</span></div>`;
    }).join('');
  openPopoverKeep('playlist', html);
  pop.querySelectorAll('[data-pli]').forEach(el =>
    el.addEventListener('click', () => { cmd('playlist-play-index', +el.dataset.pli); closePopover(); }));
};

// ---------------- gestures on the video area ----------------
const G = { down: false, mode: null, sx: 0, sy: 0, t0: 0, startVal: 0, moved: false };
const isStageTarget = e => !e.target.closest('.bar, #popover, #ctx, #end-screen, #p-toast');

document.addEventListener('mousedown', e => {
  if (e.button !== 0 || !isStageTarget(e)) return;
  G.down = true; G.mode = null; G.moved = false;
  G.sx = e.clientX; G.sy = e.clientY; G.t0 = Date.now();
});
document.addEventListener('mousemove', e => {
  if (!G.down) return;
  const dx = e.clientX - G.sx, dy = e.clientY - G.sy;
  if (!G.mode) {
    if (Math.abs(dx) < 18 && Math.abs(dy) < 18) return;
    G.moved = true;
    if (Math.abs(dx) > Math.abs(dy)) {
      G.mode = 'seek'; G.startVal = P['time-pos'] || 0;
    } else if (G.sx < innerWidth / 2) {
      G.mode = 'bright'; G.startVal = P.brightness || 0;
    } else {
      G.mode = 'vol'; G.startVal = P.volume ?? 100;
    }
  }
  if (G.mode === 'seek') {
    const span = Math.min(Math.max(P.duration || 60, 60), 600);
    let t = G.startVal + (dx / innerWidth) * span;
    t = Math.max(0, Math.min(t, P.duration || t));
    const now = Date.now();
    if (now - lastSeekSend > 150) {
      lastSeekSend = now;
      cmd('seek', t, 'absolute+keyframes');
      suppressUntil = now + 400;
    }
    upSeekFill(t);
    $('#t-cur').textContent = fmt(t);
    const delta = t - G.startVal;
    $('#seek-hud').innerHTML = `<div class="big-t">${delta >= 0 ? '+' : '−'}${fmt(Math.abs(delta))}</div>
      <div class="sub-t">${fmt(t)} / ${fmt(P.duration)}</div>`;
    $('#seek-hud').classList.remove('hidden');
  } else if (G.mode === 'vol') {
    const max = SET.volumeMax || 200;
    let v = Math.round(G.startVal + (-dy / innerHeight) * 150);
    v = Math.max(0, Math.min(v, max));
    setProp('volume', v);
    gestureHud('🔊', v / max, `Volume ${v}%`);
  } else if (G.mode === 'bright') {
    let b = Math.round(G.startVal + (-dy / innerHeight) * 150);
    b = Math.max(-100, Math.min(b, 100));
    setProp('brightness', b);
    gestureHud('☀️', (b + 100) / 200, `Brightness ${b}`);
  }
});
document.addEventListener('mouseup', e => {
  if (!G.down) return;
  G.down = false;
  $('#hud').classList.add('hidden');
  $('#seek-hud').classList.add('hidden');
  if (G.mode === 'seek') {
    const dx = e.clientX - G.sx;
    const span = Math.min(Math.max(P.duration || 60, 60), 600);
    let t = G.startVal + (dx / innerWidth) * span;
    t = Math.max(0, Math.min(t, P.duration || t));
    suppressUntil = Date.now() + 500;
    cmd('seek', t, 'absolute+exact');
  } else if (!G.mode && !G.moved && Date.now() - G.t0 < 400 && isStageTarget(e) && e.button === 0) {
    if (popoverKind) { closePopover(); }
    else { cmd('cycle', 'pause'); flash(); }
  }
  G.mode = null;
});
document.addEventListener('dblclick', e => {
  if (isStageTarget(e)) setProp('fullscreen', !(P.fullscreen === true));
});
document.addEventListener('wheel', e => {
  if (!isStageTarget(e)) return;
  const max = SET.volumeMax || 200;
  let v = Math.round((P.volume ?? 100) + (e.deltaY < 0 ? 5 : -5));
  v = Math.max(0, Math.min(v, max));
  setProp('volume', v);
  gestureHud('🔊', v / max, `Volume ${v}%`);
  clearTimeout(gestureHud._t);
  gestureHud._t = setTimeout(() => $('#hud').classList.add('hidden'), 900);
});

function gestureHud(icon, frac, text) {
  $('#hud-icon').textContent = icon;
  $('#hud-fill').style.width = Math.round(frac * 100) + '%';
  $('#hud-text').textContent = text;
  $('#hud').classList.remove('hidden');
}

function flash() {
  const f = $('#flash');
  const paused = P.pause === true;   // state BEFORE toggle applies
  f.innerHTML = paused
    ? '<svg viewBox="0 0 24 24"><path d="M8 5.5 L19 12 L8 18.5 Z"/></svg>'
    : '<svg viewBox="0 0 24 24"><path d="M8 5 H10.5 V19 H8 Z M13.5 5 H16 V19 H13.5 Z"/></svg>';
  f.classList.remove('hidden', 'anim');
  void f.offsetWidth;
  f.classList.add('anim');
}

// ---------------- context menu ----------------
function closeCtx() { $('#ctx').classList.add('hidden'); }
document.addEventListener('contextmenu', e => {
  e.preventDefault();
  if (!isStageTarget(e)) return;
  const ctx = $('#ctx');
  const items = [
    ['⏯ Play / Pause', () => cmd('cycle', 'pause')],
    ['⏭ Frame forward', () => cmd('frame-step')],
    ['⏮ Frame back', () => cmd('frame-back-step')],
    ['📷 Screenshot', () => { cmd('screenshot'); toast('Screenshot saved to Desktop'); }],
    ['↻ Rotate 90°', () => setProp('video-rotate', ((P['video-rotate'] || 0) + 90) % 360)],
    ['🔁 A-B loop point', () => cmd('ab-loop')],
    ['⏹ Back to library', () => cmd('quit-watch-later')]
  ];
  ctx.innerHTML = items.map(([label], i) => `<div class="pop-item" data-ci="${i}">${label}</div>`).join('');
  ctx.querySelectorAll('[data-ci]').forEach(el =>
    el.addEventListener('click', () => { items[+el.dataset.ci][1](); closeCtx(); }));
  ctx.classList.remove('hidden');
  ctx.style.left = Math.min(e.clientX, innerWidth - 230) + 'px';
  ctx.style.top = Math.min(e.clientY, innerHeight - ctx.offsetHeight - 10) + 'px';
});

// ---------------- keyboard ----------------
const KEY_MAP = {
  ' ': 'SPACE', 'Enter': 'ENTER', 'Backspace': 'BS',
  'ArrowLeft': 'LEFT', 'ArrowRight': 'RIGHT', 'ArrowUp': 'UP', 'ArrowDown': 'DOWN',
  '#': 'SHARP'
};
document.addEventListener('keydown', e => {
  showUI();
  if (e.key === 'Escape') {
    if (popoverKind) return closePopover();
    if (P.fullscreen === true) return setProp('fullscreen', false);
    return cmd('quit-watch-later');
  }
  if (e.key === 'q' && !e.ctrlKey && !e.altKey) return cmd('quit-watch-later');
  if (e.target && /^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName)) return;
  // Arrow seeking is handled here so the step stays user-configurable
  if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && !e.ctrlKey && !e.altKey) {
    const step = e.shiftKey ? 60 : seekStep();
    relSeek(e.key === 'ArrowRight' ? step : -step);
    e.preventDefault();
    return;
  }
  let name = KEY_MAP[e.key];
  if (!name) {
    if (e.key.length !== 1) return;
    name = e.key;
  }
  const mods = [];
  if (e.ctrlKey) mods.push('Ctrl');
  if (e.altKey) mods.push('Alt');
  if (e.shiftKey && KEY_MAP[e.key]) mods.push('Shift');
  cmd('keypress', mods.length ? mods.join('+') + '+' + name : name);
  e.preventDefault();
});

// ---------------- window resize grips ----------------
// The overlay covers the native resize borders, so it provides its own.
(function buildGrips() {
  const edges = ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw'];
  for (const edge of edges) {
    const el = document.createElement('div');
    el.className = 'grip grip-' + edge;
    el.addEventListener('mousedown', e => {
      if (e.button !== 0 || P.fullscreen === true) return;
      e.stopPropagation();
      e.preventDefault();
      window.player.resizeStart(edge);
      const up = () => { window.player.resizeEnd(); removeEventListener('mouseup', up); };
      addEventListener('mouseup', up);
    });
    stage.appendChild(el);
  }
})();

// ---------------- engine connection status ----------------
// The control channel to the playback engine can drop; the app used to give no
// sign of it and simply stop responding to every control.
window.player.onLink(({ state }) => {
  if (state === 'reconnecting') {
    toast('Reconnecting to the playback engine…');
    showUI();
  } else if (state === 'ok') {
    toast('Reconnected');
  } else if (state === 'lost') {
    toast('Lost the playback engine — returning to the library');
  }
});

// ---------------- init ----------------
(async function init() {
  const st = await window.player.init();
  if (!st) return;
  P = st.props || {};
  SET = st.settings || {};
  $('#vol-slider').max = SET.volumeMax || 200;
  const step = seekStep();
  $('#btn-b10').title = `Back ${step}s (←)`;
  $('#btn-f10').title = `Forward ${step}s (→)`;
  for (const el of document.querySelectorAll('#btn-b10 .t, #btn-f10 .t')) el.textContent = step;
  Object.values(UPDATERS).forEach(fn => { try { fn(); } catch (_) {} });
  showUI();
})();
