/* Nova Player — renderer (library UI) */
'use strict';

let S = { folders: [], items: {}, playlists: [], history: [], settings: {}, prefs: {} };
let currentView = 'home';
let currentFolder = null;   // drill-down folder path (home view)
let searchQuery = '';
let playbackActive = false;

const $ = sel => document.querySelector(sel);
const views = $('#views');

// ---------------- helpers ----------------
function esc(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function fmtDur(s) {
  if (!s || !isFinite(s)) return '';
  s = Math.round(s);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}` : `${m}:${String(sec).padStart(2, '0')}`;
}
function fmtSize(b) {
  if (!b) return '';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
  return b.toFixed(i > 1 ? 1 : 0) + ' ' + u[i];
}
function fmtAgo(ts) {
  if (!ts) return '';
  const d = Date.now() - ts;
  const min = Math.floor(d / 60000);
  if (min < 1) return 'just now';
  if (min < 60) return `${min} min ago`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h} h ago`;
  const days = Math.floor(h / 24);
  if (days < 30) return `${days} d ago`;
  return new Date(ts).toLocaleDateString();
}
function thumbUrl(it) {
  return it && it.thumb ? `file:///${it.thumb.replace(/\\/g, '/')}` : null;
}
function toast(msg, isError) {
  const el = document.createElement('div');
  el.className = 'toast' + (isError ? ' error' : '');
  el.textContent = msg;
  $('#toast-root').appendChild(el);
  setTimeout(() => el.remove(), 3800);
}

function sortedItems() {
  return Object.entries(S.items).map(([path, it]) => ({ path, ...it }));
}
function dirOf(p) { return p.replace(/[\\/][^\\/]+$/, ''); }
function baseName(p) { return p.split(/[\\/]/).filter(Boolean).pop() || p; }

/* Group library items by the folder that directly contains them (MX-style) */
function folderGroups() {
  const groups = new Map();
  for (const it of sortedItems()) {
    if (/^[a-z]+:\/\//i.test(it.path)) continue;
    const dir = dirOf(it.path);
    if (!groups.has(dir)) groups.set(dir, []);
    groups.get(dir).push(it);
  }
  return [...groups.entries()]
    .map(([dir, items]) => ({
      dir,
      name: baseName(dir),
      items: items.sort((a, b) => a.path.localeCompare(b.path)),
      thumb: (items.find(i => i.thumb) || {}).thumb || null
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
}
function matchesSearch(it) {
  if (!searchQuery) return true;
  return (it.title || it.path).toLowerCase().includes(searchQuery);
}

const SORTS = {
  name:     { label: 'Name (A–Z)',      cmp: (a, b) => (a.title || a.path).localeCompare(b.title || b.path) },
  added:    { label: 'Recently added',  cmp: (a, b) => (b.addedAt || 0) - (a.addedAt || 0) },
  played:   { label: 'Recently played', cmp: (a, b) => (b.lastPlayed || 0) - (a.lastPlayed || 0) },
  longest:  { label: 'Longest first',   cmp: (a, b) => (b.duration || 0) - (a.duration || 0) },
  largest:  { label: 'Largest first',   cmp: (a, b) => (b.size || 0) - (a.size || 0) }
};
function sortComparator() {
  return (SORTS[S.settings.sortBy] || SORTS.name).cmp;
}

// ---------------- cards ----------------
function resLabel(it) {
  if (!it.height) return '';
  if (it.height >= 2000) return '4K';
  if (it.height >= 1400) return '2K';
  if (it.height >= 1000) return 'HD 1080';
  return it.height >= 700 ? 'HD 720' : 'SD';
}

function cardHTML(it, opts = {}) {
  const pct = it.duration && it.progress ? Math.min(100, (it.progress / it.duration) * 100) : 0;
  const t = thumbUrl(it);
  const res = resLabel(it);
  return `<div class="card" data-path="${esc(it.path)}">
    <div class="thumb" ${t ? `style="background-image:url('${esc(t)}')"` : ''}>
      ${t ? '' : '<div class="ph">▶</div>'}
      ${it.duration ? `<div class="dur">${fmtDur(it.duration)}</div>` : ''}
      <div class="play-badge"><span>▶</span></div>
      ${pct > 1 && pct < 98 ? `<div class="progress"><div style="width:${pct}%"></div></div>` : ''}
    </div>
    <div class="card-body">
      <div class="card-title" title="${esc(it.path)}">${esc(it.title || it.path)}</div>
      <div class="card-meta">
        ${res ? `<span>${res}</span>` : ''}
        ${it.size ? `<span>${fmtSize(it.size)}</span>` : ''}
        ${opts.showAgo && it.lastPlayed ? `<span>${fmtAgo(it.lastPlayed)}</span>` : ''}
      </div>
    </div>
  </div>`;
}

// ---------------- views ----------------
function render() {
  let fn = {
    home: renderHome, folders: renderFolders, playlists: renderPlaylists,
    history: renderHistory, network: renderNetwork, settings: renderSettings
  }[currentView] || renderHome;
  if (currentView === 'home' && currentFolder && !searchQuery) fn = renderFolderDetail;
  views.innerHTML = fn();
  views.scrollTop = 0;
  bindView();
}

function folderCardHTML(g) {
  const t = g.thumb ? `file:///${g.thumb.replace(/\\/g, '/')}` : null;
  return `<div class="card folder-card" data-folderdir="${esc(g.dir)}" title="${esc(g.dir)}">
    <div class="thumb" ${t ? `style="background-image:url('${esc(t)}')"` : ''}>
      ${t ? '' : '<div class="ph">📁</div>'}
      <div class="folder-veil"></div>
      <div class="folder-tag">📁 ${g.items.length} video${g.items.length !== 1 ? 's' : ''}</div>
    </div>
    <div class="card-body">
      <div class="card-title" style="min-height:auto">${esc(g.name)}</div>
      <div class="card-meta"><span>${esc(g.dir)}</span></div>
    </div>
  </div>`;
}

/* The one thing you are most likely to want the moment the app opens: the
 * episode you were part-way through, with the artwork behind it. */
function spotlightHTML(it) {
  const t = thumbUrl(it);
  const left = it.duration ? Math.max(0, it.duration - it.progress) : 0;
  const pct = it.duration ? Math.min(100, (it.progress / it.duration) * 100) : 0;
  const res = resLabel(it);
  const bits = [
    left ? `${fmtDur(left)} left` : '',
    res, it.lastPlayed ? fmtAgo(it.lastPlayed) : ''
  ].filter(Boolean);
  return `<div class="spotlight" data-sppath="${esc(it.path)}">
    <div class="sp-bg" ${t ? `style="background-image:url('${esc(t)}')"` : ''}></div>
    <div class="sp-shade"></div>
    <div class="sp-body">
      <div class="sp-kicker">Pick up where you left off</div>
      <div class="sp-title">${esc(it.title || baseName(it.path))}</div>
      <div class="sp-meta">${bits.map(esc).join('<span class="dot">•</span>')}</div>
      <div class="sp-bar"><i style="width:${pct}%"></i></div>
      <div class="sp-actions">
        <button class="btn accent" data-spresume>▶ Resume at ${fmtDur(it.progress)}</button>
        <button class="btn ghost" data-sprestart>Start over</button>
      </div>
    </div>
  </div>`;
}

function renderFolderDetail() {
  const g = folderGroups().find(x => x.dir === currentFolder);
  if (!g) { currentFolder = null; return renderHome(); }
  return `<div style="display:flex;align-items:center;gap:12px;margin-top:14px">
      <button class="btn ghost" id="folder-back">← Back</button>
      <div><div class="view-title" style="margin:0">📁 ${esc(g.name)}</div>
      <div class="view-sub" style="margin:2px 0 0">${esc(g.dir)} — ${g.items.length} video${g.items.length !== 1 ? 's' : ''}</div></div>
    </div>
    <div class="section"><div class="grid">${g.items.map(i => cardHTML(i)).join('')}</div></div>`;
}

function renderHome() {
  const items = sortedItems().filter(matchesSearch);
  if (searchQuery) {
    if (!items.length) return `<div class="empty"><div class="big">🔍</div>No results for “${esc(searchQuery)}”</div>`;
    return `<div class="section"><div class="section-head"><div class="section-title">Search results</div>
      <div class="section-sub">${items.length} video${items.length > 1 ? 's' : ''}</div></div>
      <div class="grid">${items.map(i => cardHTML(i)).join('')}</div></div>`;
  }

  const continueWatching = items
    .filter(i => i.progress > 10 && i.duration && i.progress / i.duration < 0.97)
    .sort((a, b) => (b.lastPlayed || 0) - (a.lastPlayed || 0)).slice(0, 12);
  const recent = items.slice().sort(SORTS.added.cmp).slice(0, 18);
  const all = items.slice().sort(sortComparator());

  let html = '';
  if (!S.folders.length && !all.length) {
    html += `<div class="hero">
      <h1>Welcome to Nova Player ✦</h1>
      <p>Your personal MX Player for the desktop. Add your video folders and Nova builds a beautiful library with thumbnails, resume points and watch history — and plays <b>everything</b>: MKV, HEVC, 10-bit anime, network streams, you name it.</p>
      <button class="btn accent" id="hero-add">+ Add your first folder</button>
      <span style="display:inline-block;width:8px"></span>
      <button class="btn ghost" id="hero-open">…or just open a file</button>
    </div>
    <div class="section"><div class="section-head"><div class="section-title">Quick tips</div></div>
      <div class="view-sub" style="line-height:2.1">
        Drag &amp; drop any video anywhere to play it instantly.<br>
        In the player: <kbd>drag ⇆</kbd> seek &nbsp; <kbd>drag ⇅ right</kbd> volume &nbsp; <kbd>drag ⇅ left</kbd> brightness &nbsp; <kbd>[</kbd>/<kbd>]</kbd> speed &nbsp; <kbd>l</kbd> A–B repeat &nbsp; <kbd>right-click</kbd> full menu.
      </div></div>`;
    return html;
  }
  if (continueWatching.length) {
    html += spotlightHTML(continueWatching[0]);
    const rest = continueWatching.slice(1);
    if (rest.length) {
      html += `<div class="section"><div class="section-head"><div class="section-title">Continue watching</div>
        <div class="section-sub">${rest.length} more</div></div>
        <div class="row-scroll">${rest.map(i => cardHTML(i, { showAgo: true })).join('')}</div></div>`;
    }
  }
  const groups = folderGroups();
  if (groups.length) {
    html += `<div class="section"><div class="section-head"><div class="section-title">Folders</div>
      <div class="section-sub">${groups.length} folder${groups.length !== 1 ? 's' : ''}</div></div>
      <div class="grid">${groups.map(folderCardHTML).join('')}</div></div>`;
  }
  if (recent.length) {
    html += `<div class="section"><div class="section-head"><div class="section-title">Recently added</div></div>
      <div class="row-scroll">${recent.map(i => cardHTML(i)).join('')}</div></div>`;
  }
  html += `<div class="section"><div class="section-head"><div class="section-title">All videos</div>
    <div class="section-sub">${all.length} video${all.length !== 1 ? 's' : ''}</div>
    <select id="sort-by" title="Sort">${Object.entries(SORTS).map(([k, v]) =>
      `<option value="${k}" ${S.settings.sortBy === k ? 'selected' : ''}>${v.label}</option>`).join('')}</select></div>
    ${all.length ? `<div class="grid">${all.map(i => cardHTML(i)).join('')}</div>`
      : `<div class="empty"><div class="big">🎬</div>No videos yet — scanning your folders…</div>`}</div>`;
  return html;
}

function renderFolders() {
  const byFolder = new Map();
  for (const it of sortedItems().filter(matchesSearch)) {
    const dir = it.path.replace(/[\\/][^\\/]+$/, '');
    if (!byFolder.has(dir)) byFolder.set(dir, []);
    byFolder.get(dir).push(it);
  }
  let html = `<div class="view-title">Folders</div>
    <div class="view-sub">Library roots — Nova scans these (and their subfolders) for videos.</div>
    <div class="rowlist" style="max-width:640px">
      ${S.folders.map(f => `<div class="folder-chip"><span>📁</span><span class="fc-path">${esc(f)}</span>
        <button class="icon-btn" data-removefolder="${esc(f)}" title="Remove from library">✕</button></div>`).join('')}
    </div>
    <div style="margin-top:12px"><button class="btn accent" id="folders-add">+ Add folder</button>
    <button class="btn ghost" id="folders-rescan" style="margin-left:8px">↻ Rescan</button></div>`;
  for (const [dir, its] of [...byFolder.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
    html += `<div class="section"><div class="section-head"><div class="section-title" style="font-size:14px">${esc(dir)}</div>
      <div class="section-sub">${its.length}</div></div>
      <div class="grid">${its.map(i => cardHTML(i)).join('')}</div></div>`;
  }
  return html;
}

function renderPlaylists() {
  let html = `<div class="view-title">Playlists</div>
    <div class="view-sub">Right-click any video → “Add to playlist”. Playing a playlist queues every item.</div>
    <div style="margin:10px 0 4px"><button class="btn accent" id="pl-new">+ New playlist</button></div>`;
  if (!S.playlists.length) {
    html += `<div class="empty"><div class="big">🎞️</div>No playlists yet</div>`;
    return html;
  }
  for (const pl of S.playlists) {
    html += `<div class="section"><div class="section-head">
      <div class="section-title">${esc(pl.name)}</div>
      <div class="section-sub">${pl.items.length} item${pl.items.length !== 1 ? 's' : ''}</div>
      ${pl.items.length ? `<button class="btn ghost" data-plplay="${pl.id}" style="padding:5px 12px;font-size:12px">▶ Play all</button>` : ''}
      <button class="btn danger" data-pldel="${pl.id}" style="padding:5px 12px;font-size:12px">Delete</button>
    </div><div class="rowlist">`;
    for (const p of pl.items) {
      const it = S.items[p] || { title: p.split(/[\\/]/).pop(), path: p };
      const t = thumbUrl(it);
      html += `<div class="rowitem" data-plitem="${pl.id}" data-path="${esc(p)}">
        <div class="ri-thumb" ${t ? `style="background-image:url('${esc(t)}')"` : ''}></div>
        <div class="ri-main"><div class="ri-title">${esc(it.title || p)}</div><div class="ri-sub">${esc(p)}</div></div>
        <div class="ri-side">${fmtDur(it.duration)}</div>
        <button class="icon-btn" data-plremove="${pl.id}" data-rpath="${esc(p)}" title="Remove">✕</button>
      </div>`;
    }
    html += `</div></div>`;
  }
  return html;
}

function renderHistory() {
  let html = `<div class="view-title">History</div>
    <div class="view-sub">Everything you've played, newest first.</div>
    ${S.history.length ? '<div style="margin:8px 0"><button class="btn danger" id="hist-clear">Clear history</button></div>' : ''}
    <div class="rowlist">`;
  if (!S.history.length) return html + `</div><div class="empty"><div class="big">🕘</div>Nothing played yet</div>`;
  for (const h of S.history) {
    const it = S.items[h.path] || { title: h.path.split(/[\\/]/).pop() };
    const t = thumbUrl(it);
    const pct = it.duration && it.progress ? Math.round((it.progress / it.duration) * 100) : null;
    html += `<div class="rowitem" data-histpath="${esc(h.path)}">
      <div class="ri-thumb" ${t ? `style="background-image:url('${esc(t)}')"` : ''}></div>
      <div class="ri-main"><div class="ri-title">${esc(it.title || h.path)}</div>
        <div class="ri-sub">${esc(h.path)}</div></div>
      <div class="ri-side">${pct != null && pct < 97 ? `watched ${pct}% · ` : ''}${fmtAgo(h.at)}</div>
    </div>`;
  }
  return html + '</div>';
}

function renderNetwork() {
  return `<div class="view-title">Network stream</div>
    <div class="view-sub">Play a direct video URL — http(s), streaming links (m3u8/HLS), SMB shares mapped as drives, etc. Just like MX Player's “Network stream”.</div>
    <div class="settings-card" style="margin-top:14px">
      <div style="display:flex;gap:8px">
        <input type="text" class="box" id="net-url" placeholder="https://example.com/video.mp4  or  https://…/stream.m3u8" style="flex:1">
        <button class="btn accent" id="net-play">▶ Play</button>
      </div>
    </div>`;
}

const PREF_LABELS = {
  'speed': v => v + '× speed',
  'volume': v => 'volume ' + Math.round(v) + '%',
  'mute': v => (v ? 'muted' : 'unmuted'),
  'brightness': v => 'brightness ' + v,
  'contrast': v => 'contrast ' + v,
  'gamma': v => 'gamma ' + v,
  'saturation': v => 'saturation ' + v,
  'sub-scale': v => 'subtitle size ' + Math.round(v * 100) + '%',
  'sub-pos': v => 'subtitle position ' + v,
  'sub-visibility': v => (v ? 'subtitles on' : 'subtitles off')
};

function renderSettings() {
  const s = S.settings;
  const remembered = Object.entries(S.prefs || {})
    .filter(([k]) => PREF_LABELS[k])
    .map(([k, v]) => PREF_LABELS[k](v));
  return `<div class="view-title">Settings</div>
  <div class="view-sub">Changes apply the next time playback starts.</div>
  <div class="settings-card"><h3>Playback</h3>
    <div class="setting-row"><div><div class="setting-label">Hardware acceleration</div>
      <div class="setting-hint">GPU decoding — smooth 4K/HEVC with near-zero CPU use</div></div>
      <label class="switch"><input type="checkbox" id="set-hwdec" ${s.hwdec ? 'checked' : ''}><span class="knob"></span></label></div>
    <div class="setting-row"><div><div class="setting-label">Resume where I left off</div>
      <div class="setting-hint">Every video remembers its position, audio track and subtitles</div></div>
      <label class="switch"><input type="checkbox" id="set-resume" ${s.rememberPosition ? 'checked' : ''}><span class="knob"></span></label></div>
    <div class="setting-row"><div><div class="setting-label">Volume boost limit</div>
      <div class="setting-hint">Allow volume above 100% (like MX Player's boost)</div></div>
      <select id="set-volmax">
        ${[100, 150, 200, 300].map(v => `<option value="${v}" ${s.volumeMax == v ? 'selected' : ''}>${v}%</option>`).join('')}
      </select></div>
    <div class="setting-row"><div><div class="setting-label">Default speed</div></div>
      <select id="set-speed">
        ${[0.5, 0.75, 1, 1.25, 1.5, 2].map(v => `<option value="${v}" ${s.defaultSpeed == v ? 'selected' : ''}>${v}×</option>`).join('')}
      </select></div>
    <div class="setting-row"><div><div class="setting-label">Arrow key skip</div>
      <div class="setting-hint">How far ← and → jump, and the skip buttons in the player</div></div>
      <select id="set-seekstep">
        ${[3, 5, 10, 15, 30].map(v => `<option value="${v}" ${(s.seekStep ?? 5) == v ? 'selected' : ''}>${v} seconds</option>`).join('')}
      </select></div>
    <div class="setting-row"><div><div class="setting-label">Remember my player adjustments</div>
      <div class="setting-hint">Speed, volume, mute and picture tweaks carry over to the next video and the next session</div></div>
      <label class="switch"><input type="checkbox" id="set-remember-state" ${s.rememberPlayerState !== false ? 'checked' : ''}><span class="knob"></span></label></div>
    <div class="setting-row"><div><div class="setting-label">“Next up” card</div>
      <div class="setting-hint">Shows what plays next in the last 20 seconds of an episode</div></div>
      <label class="switch"><input type="checkbox" id="set-nextup" ${s.nextUpCard !== false ? 'checked' : ''}><span class="knob"></span></label></div>
    <div class="setting-row"><div><div class="setting-label">Reset player settings</div>
      <div class="setting-hint">${remembered.length ? 'Currently remembering: ' + esc(remembered.join(', ')) : 'Nothing customised yet — everything is at its default'}</div></div>
      <button class="btn ghost" id="set-reset-prefs" style="padding:6px 14px;font-size:12px">Reset to default</button></div>
  </div>
  <div class="settings-card"><h3>Appearance</h3>
    <div class="setting-row"><div><div class="setting-label">Accent colour</div>
      <div class="setting-hint">Applies to the library and the player instantly</div></div>
      <div class="swatches">${Object.entries(NOVA_ACCENTS).map(([k, c]) =>
        `<button class="swatch ${(s.accent || 'blue') === k ? 'sel' : ''}" data-accent="${k}" title="${esc(c.name)}"
          style="background:linear-gradient(135deg, ${c.a}, ${c.b})"></button>`).join('')}</div></div>
  </div>
  <div class="settings-card"><h3>Subtitles</h3>
    <div class="setting-row"><div><div class="setting-label">Subtitle size</div></div>
      <div style="display:flex;align-items:center;gap:10px">
        <input type="range" id="set-subscale" min="0.6" max="2" step="0.05" value="${s.subScale || 1}">
        <span id="subscale-val" style="width:42px;text-align:right">${Math.round((s.subScale || 1) * 100)}%</span></div></div>
    <div class="setting-row"><div><div class="setting-label">Subtitle outline</div></div>
      <div style="display:flex;align-items:center;gap:10px">
        <input type="range" id="set-subborder" min="0" max="5" step="0.2" value="${s.subBorder ?? 2}">
        <span id="subborder-val" style="width:42px;text-align:right">${s.subBorder ?? 2}</span></div></div>
    <div class="setting-row"><div><div class="setting-label">Preferred subtitle language</div>
      <div class="setting-hint">e.g. en, eng — blank = automatic</div></div>
      <input type="text" class="box" id="set-slang" value="${esc(s.subLang || '')}" style="width:110px"></div>
    <div class="setting-row"><div><div class="setting-label">Preferred audio language</div></div>
      <input type="text" class="box" id="set-alang" value="${esc(s.audioLang || '')}" style="width:110px"></div>
  </div>
  <div class="settings-card"><h3>About</h3>
    <div class="setting-row"><div><div class="setting-label">Version</div>
      <div class="setting-hint" id="upd-status">Checking…</div></div>
      <button class="btn ghost" id="upd-check" style="padding:6px 14px;font-size:12px">Check now</button></div>
    <div class="setting-row"><div><div class="setting-label">Diagnostics</div>
      <div class="setting-hint">If playback ever freezes, Nova writes what it saw to <code>nova-diagnostics.log</code></div></div>
      <button class="btn ghost" id="open-data" style="padding:6px 14px;font-size:12px">Show files</button></div>
    <div class="setting-hint" style="line-height:1.9;margin-top:10px">
      Nova Player — built on the mpv playback engine.<br>
      No telemetry. No ads. Updates install quietly when you close the app —
      <b>you will never get an update pop-up.</b>
    </div>
  </div>`;
}

// ---------------- interactions ----------------
function bindView() {
  views.querySelectorAll('.card:not(.folder-card)').forEach(c => {
    c.addEventListener('click', () => playSingle(c.dataset.path));
    c.addEventListener('contextmenu', e => { e.preventDefault(); showCtxMenu(e, c.dataset.path); });
  });
  views.querySelectorAll('.folder-card').forEach(c => c.addEventListener('click', () => {
    currentFolder = c.dataset.folderdir;
    render();
  }));
  const back = views.querySelector('#folder-back');
  if (back) back.addEventListener('click', () => { currentFolder = null; render(); });

  const sp = views.querySelector('.spotlight');
  if (sp) {
    const p = sp.dataset.sppath;
    sp.addEventListener('click', () => playSingle(p));
    sp.querySelector('[data-sprestart]').addEventListener('click', async e => {
      e.stopPropagation();
      await window.nova.removeProgress(p);
      if (S.items[p]) S.items[p].progress = 0;
      playSingle(p);
    });
  }
  views.querySelectorAll('[data-histpath]').forEach(r =>
    r.addEventListener('click', () => playSingle(r.dataset.histpath)));

  const on = (sel, ev, fn) => { const el = views.querySelector(sel); if (el) el.addEventListener(ev, fn); };
  on('#hero-add', 'click', addFolder);
  on('#folders-add', 'click', addFolder);
  on('#hero-open', 'click', () => window.nova.openFileDialog());
  on('#folders-rescan', 'click', () => { window.nova.rescan(); toast('Rescanning library…'); });
  on('#hist-clear', 'click', async () => { await window.nova.clearHistory(); await refresh(); });
  on('#pl-new', 'click', () => promptModal('New playlist', 'Name your playlist', async name => {
    if (name) { await window.nova.playlistCreate(name); await refresh(); }
  }));
  on('#net-play', 'click', playNetUrl);
  on('#net-url', 'keydown', e => { if (e.key === 'Enter') playNetUrl(); });
  on('#sort-by', 'change', async e => {
    S.settings.sortBy = e.target.value;
    await window.nova.saveSettings({ sortBy: e.target.value });
    render();
  });

  views.querySelectorAll('[data-removefolder]').forEach(b => b.addEventListener('click', async e => {
    e.stopPropagation();
    await window.nova.removeFolder(b.dataset.removefolder);
    await refresh();
  }));
  views.querySelectorAll('[data-plplay]').forEach(b => b.addEventListener('click', () => {
    const pl = S.playlists.find(p => p.id === b.dataset.plplay);
    if (pl && pl.items.length) window.nova.play(pl.items, 0);
  }));
  views.querySelectorAll('[data-pldel]').forEach(b => b.addEventListener('click', async () => {
    await window.nova.playlistDelete(b.dataset.pldel);
    await refresh();
  }));
  views.querySelectorAll('[data-plremove]').forEach(b => b.addEventListener('click', async e => {
    e.stopPropagation();
    await window.nova.playlistRemoveItem(b.dataset.plremove, b.dataset.rpath);
    await refresh();
  }));
  views.querySelectorAll('[data-plitem]').forEach(r => r.addEventListener('click', () => {
    const pl = S.playlists.find(p => p.id === r.dataset.plitem);
    if (!pl) return;
    const idx = pl.items.indexOf(r.dataset.path);
    window.nova.play(pl.items, Math.max(0, idx));
  }));

  // settings bindings
  if (currentView === 'settings') {
    const save = patch => window.nova.saveSettings(patch).then(() => { Object.assign(S.settings, patch); });
    on('#set-hwdec', 'change', e => save({ hwdec: e.target.checked }));
    on('#set-resume', 'change', e => save({ rememberPosition: e.target.checked }));
    on('#set-volmax', 'change', e => save({ volumeMax: +e.target.value }));
    on('#set-speed', 'change', e => save({ defaultSpeed: +e.target.value }));
    on('#set-seekstep', 'change', e => save({ seekStep: +e.target.value }));
    on('#set-subscale', 'input', e => {
      $('#subscale-val').textContent = Math.round(e.target.value * 100) + '%';
      save({ subScale: +e.target.value });
    });
    on('#set-subborder', 'input', e => {
      $('#subborder-val').textContent = e.target.value;
      save({ subBorder: +e.target.value });
    });
    on('#set-slang', 'change', e => save({ subLang: e.target.value.trim() }));
    on('#set-alang', 'change', e => save({ audioLang: e.target.value.trim() }));
    on('#set-remember-state', 'change', async e => {
      await save({ rememberPlayerState: e.target.checked });
      if (!e.target.checked) S.prefs = {};
      render();
    });
    on('#set-nextup', 'change', e => save({ nextUpCard: e.target.checked }));
    on('#set-reset-prefs', 'click', async () => {
      await window.nova.resetPlayerPrefs();
      S.prefs = {};
      toast('Player settings reset to default');
      render();
    });
    views.querySelectorAll('[data-accent]').forEach(b => b.addEventListener('click', async () => {
      await save({ accent: b.dataset.accent });
      novaApplyAccent(b.dataset.accent);
      render();
    }));
    on('#open-data', 'click', () => window.nova.openDataFolder());
    on('#upd-check', 'click', async () => { paintUpdate(await window.nova.updateCheck()); });
    window.nova.updateState().then(paintUpdate);
  }
}

function paintUpdate(u) {
  const el = $('#upd-status');
  if (!el || !u) return;
  const v = u.currentVersion ? 'v' + u.currentVersion : '';
  const text = {
    checking: 'Checking for updates…',
    current: 'Up to date',
    downloading: `Downloading ${u.version ? 'v' + u.version : 'update'}${u.percent ? ' — ' + u.percent + '%' : ''}…`,
    ready: `v${u.version} ready — installs when you close the app`,
    disabled: u.reason === 'development build' ? 'Development build' : 'Updates unavailable',
    error: 'Could not check right now',
    idle: ''
  }[u.status] || '';
  el.textContent = [v, text].filter(Boolean).join(' · ');
}

function playSingle(path) {
  if (playbackActive) return;   // video covers the UI; ignore stray clicks
  // queue the rest of the same folder after it (MX-style folder playback)
  const dir = path.replace(/[\\/][^\\/]+$/, '');
  const siblings = sortedItems()
    .filter(i => i.path.startsWith(dir + '\\') || i.path.startsWith(dir + '/'))
    .filter(i => i.path.replace(/[\\/][^\\/]+$/, '') === dir)
    .sort((a, b) => a.path.localeCompare(b.path))
    .map(i => i.path);
  const idx = siblings.indexOf(path);
  if (idx >= 0) window.nova.play(siblings, idx);
  else window.nova.play([path], 0);
}

function playNetUrl() {
  const url = $('#net-url').value.trim();
  if (!url) return;
  window.nova.playUrl(url).then(r => {
    if (r && r.error) toast(r.error, true);
  });
}

async function addFolder() {
  const p = await window.nova.addFolder();
  if (p) { toast('Added ' + p + ' — scanning…'); await refresh(); }
}

// ---------------- context menu ----------------
function showCtxMenu(e, path) {
  const menu = $('#ctx-menu');
  const plItems = S.playlists.map(pl =>
    `<div class="ctx-item" data-act="pladd" data-pl="${pl.id}">➕ Add to “${esc(pl.name)}”</div>`).join('');
  menu.innerHTML = `
    <div class="ctx-item" data-act="play">▶ Play</div>
    <div class="ctx-item" data-act="restart">⟲ Play from beginning</div>
    <div class="ctx-sep"></div>
    ${plItems}
    <div class="ctx-item" data-act="plnew">➕ Add to new playlist…</div>
    <div class="ctx-sep"></div>
    <div class="ctx-item" data-act="folder">📂 Show in folder</div>`;
  menu.classList.remove('hidden');
  const rect = { w: menu.offsetWidth, h: menu.offsetHeight };
  menu.style.left = Math.min(e.clientX, innerWidth - rect.w - 8) + 'px';
  menu.style.top = Math.min(e.clientY, innerHeight - rect.h - 8) + 'px';
  menu.querySelectorAll('.ctx-item').forEach(mi => mi.addEventListener('click', async () => {
    const act = mi.dataset.act;
    menu.classList.add('hidden');
    if (act === 'play') playSingle(path);
    else if (act === 'restart') { await window.nova.removeProgress(path); playSingle(path); }
    else if (act === 'folder') window.nova.showInFolder(path);
    else if (act === 'pladd') { await window.nova.playlistAdd(mi.dataset.pl, [path]); toast('Added to playlist'); await refresh(); }
    else if (act === 'plnew') promptModal('New playlist', 'Name your playlist', async name => {
      if (!name) return;
      const pl = await window.nova.playlistCreate(name);
      await window.nova.playlistAdd(pl.id, [path]);
      toast('Added to “' + name + '”');
      await refresh();
    });
  }));
}
document.addEventListener('click', e => {
  if (!e.target.closest('#ctx-menu')) $('#ctx-menu').classList.add('hidden');
});

// ---------------- modal ----------------
function promptModal(title, placeholder, cb) {
  const root = $('#modal-root');
  root.innerHTML = `<div class="modal">
    <h2>${esc(title)}</h2><div class="m-sub">&nbsp;</div>
    <input type="text" id="modal-input" placeholder="${esc(placeholder)}">
    <div class="m-actions">
      <button class="btn ghost" id="modal-cancel">Cancel</button>
      <button class="btn accent" id="modal-ok">Create</button>
    </div></div>`;
  root.classList.remove('hidden');
  const input = $('#modal-input');
  input.focus();
  const close = () => { root.classList.add('hidden'); root.innerHTML = ''; };
  $('#modal-cancel').addEventListener('click', close);
  $('#modal-ok').addEventListener('click', () => { const v = input.value.trim(); close(); cb(v); });
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter') { const v = input.value.trim(); close(); cb(v); }
    if (e.key === 'Escape') close();
  });
}

// ---------------- drag & drop ----------------
let dragDepth = 0;
addEventListener('dragenter', e => { e.preventDefault(); dragDepth++; $('#dropzone').classList.add('active'); });
addEventListener('dragleave', e => { e.preventDefault(); if (--dragDepth <= 0) { dragDepth = 0; $('#dropzone').classList.remove('active'); } });
addEventListener('dragover', e => e.preventDefault());
addEventListener('drop', e => {
  e.preventDefault();
  dragDepth = 0;
  $('#dropzone').classList.remove('active');
  const paths = [...e.dataTransfer.files].map(f => window.nova.pathForFile(f)).filter(Boolean);
  if (paths.length) window.nova.play(paths, 0);
});

// ---------------- keyboard → player forwarding ----------------
// The video surface can't take keyboard focus, so while playback is active we
// translate keys to mpv key names and forward them over IPC.
const KEY_MAP = {
  ' ': 'SPACE', 'Enter': 'ENTER', 'Escape': 'ESC', 'Backspace': 'BS', 'Tab': 'TAB',
  'ArrowLeft': 'LEFT', 'ArrowRight': 'RIGHT', 'ArrowUp': 'UP', 'ArrowDown': 'DOWN',
  '#': 'SHARP'
};
document.addEventListener('keydown', e => {
  if (!playbackActive) {
    if (e.key === 'Escape' && currentFolder && currentView === 'home') { currentFolder = null; render(); }
    return;
  }
  if (e.target && /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName)) return;
  // Esc must get you out even if the engine has stopped taking keypresses
  if (e.key === 'Escape') { e.preventDefault(); return void window.nova.playerExit(); }
  let name = KEY_MAP[e.key];
  if (!name) {
    if (e.key.length !== 1) return;             // unmapped special key
    name = e.key;                               // printable char (case/shift already applied)
  }
  const mods = [];
  if (e.ctrlKey) mods.push('Ctrl');
  if (e.altKey) mods.push('Alt');
  if (e.shiftKey && KEY_MAP[e.key]) mods.push('Shift');  // only for special keys
  window.nova.mpvKey(mods.length ? mods.join('+') + '+' + name : name);
  e.preventDefault();
}, true);

// ---------------- wiring ----------------
$('#btn-min').addEventListener('click', () => window.nova.winCmd('min'));
$('#btn-max').addEventListener('click', () => window.nova.winCmd('max'));
$('#btn-close').addEventListener('click', () => window.nova.winCmd('close'));
$('#btn-add-folder').addEventListener('click', addFolder);
$('#btn-open-file').addEventListener('click', () => window.nova.openFileDialog());

document.querySelectorAll('.nav-item').forEach(b => b.addEventListener('click', () => {
  document.querySelectorAll('.nav-item').forEach(x => x.classList.remove('active'));
  b.classList.add('active');
  currentView = b.dataset.view;
  currentFolder = null;
  render();
}));

let searchTimer;
$('#search').addEventListener('input', e => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    searchQuery = e.target.value.trim().toLowerCase();
    if (searchQuery && currentView !== 'home' && currentView !== 'folders') {
      currentView = 'home';
      document.querySelectorAll('.nav-item').forEach(x => x.classList.toggle('active', x.dataset.view === 'home'));
    }
    render();
  }, 150);
});

// ---------------- floating resume button (like MX mobile) ----------------
function lastPlayedItem() {
  const played = sortedItems().filter(i => i.lastPlayed);
  if (!played.length) return null;
  played.sort((a, b) => b.lastPlayed - a.lastPlayed);
  // prefer the most recent unfinished one; fall back to the most recent
  return played.find(i => !i.duration || i.progress / i.duration < 0.97) || played[0];
}
function fabIsResume(it) {
  return it.progress > 10 && (!it.duration || it.progress / it.duration < 0.97);
}
function updateFab() {
  const wrap = $('#fab-wrap');
  const it = lastPlayedItem();
  if (!it || playbackActive) { wrap.classList.add('hidden'); return; }
  wrap.classList.remove('hidden');
  const t = thumbUrl(it);
  $('#fab-thumb').style.backgroundImage = t ? `url("${t}")` : '';
  const resume = fabIsResume(it);
  const name = it.title || baseName(it.path);
  $('#fab-kicker').textContent = resume ? `Resume · ${fmtDur(it.progress)}` : 'Play again';
  $('#fab-name').textContent = name;
  const pct = it.duration && it.progress ? Math.min(100, (it.progress / it.duration) * 100) : 0;
  $('#fab-fill').style.width = pct + '%';
  $('#fab').title = (resume ? `Resume ${name} at ${fmtDur(it.progress)}` : `Play ${name}`);
}
$('#fab').addEventListener('click', () => {
  const it = lastPlayedItem();
  if (it) playSingle(it.path);
});
$('#fab-restart').addEventListener('click', async e => {
  e.stopPropagation();
  const it = lastPlayedItem();
  if (!it) return;
  await window.nova.removeProgress(it.path);
  if (S.items[it.path]) S.items[it.path].progress = 0;
  playSingle(it.path);
});

async function refresh() {
  S = await window.nova.getState();
  novaApplyAccent(S.settings.accent);
  render();
  updateFab();
}

// resize grips (frameless window edges)
(function buildGrips() {
  for (const edge of ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw']) {
    const el = document.createElement('div');
    el.className = 'grip grip-' + edge;
    el.addEventListener('mousedown', e => {
      if (e.button !== 0) return;
      e.stopPropagation();
      e.preventDefault();
      window.nova.resizeStart(edge);
      const up = () => { window.nova.resizeEnd(); removeEventListener('mouseup', up); };
      addEventListener('mouseup', up);
    });
    document.body.appendChild(el);
  }
})();

window.nova.on('update-state', u => { if (currentView === 'settings') paintUpdate({ ...u, currentVersion: u.currentVersion }); });

window.nova.on('library-updated', refresh);

/* Thumbnail extraction reports one file at a time. Re-rendering the whole grid
 * on each of them made a fresh scan flicker, and it wiped out whatever you were
 * typing if you happened to be on Settings. */
const ITEM_VIEWS = new Set(['home', 'folders', 'playlists', 'history']);
let repaintTimer = null;
window.nova.on('item-updated', ({ path, item }) => {
  S.items[path] = { ...(S.items[path] || {}), ...item };
  if (!ITEM_VIEWS.has(currentView)) return;
  clearTimeout(repaintTimer);
  repaintTimer = setTimeout(() => { render(); updateFab(); }, 250);
});
window.nova.on('progress', ({ path, progress, duration }) => {
  const it = S.items[path] || (S.items[path] = { title: baseName(path), addedAt: Date.now() });
  it.progress = progress;
  if (duration) it.duration = duration;
  it.lastPlayed = Date.now();
});
window.nova.on('settings-changed', s => {
  S.settings = { ...S.settings, ...s };
  novaApplyAccent(S.settings.accent);
});
window.nova.on('playback-started', () => {
  playbackActive = true;
  updateFab();
  if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
});
window.nova.on('playback-ended', () => { playbackActive = false; refresh(); });
window.nova.on('play-error', ({ message }) => { playbackActive = false; toast('Playback failed: ' + message, true); });

refresh();
