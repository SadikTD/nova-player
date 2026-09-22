const { app, BrowserWindow, ipcMain, dialog, shell, Menu } = require('electron');
const path = require('path');
const fs = require('fs');
const store = require('./store');
const { MpvController } = require('./mpv');
const { Library, VIDEO_EXT } = require('./library');
const updater = require('./updater');
const subtitles = require('./subtitles');
const { SubtitleSync } = require('./subtitle-sync');

let win = null;
let mpv = null;
let library = null;
let closing = false;
let subtitleSync = null;
let subtitleSyncTimer = null;
let pendingOpen = collectFileArgs(process.argv);

// ---- single instance: a second launch (e.g. double-clicking a video) routes here
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', (_e, argv) => {
    const files = collectFileArgs(argv);
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
      if (files.length) playFiles(files, 0);
    }
  });
}

function collectFileArgs(argv) {
  return argv.slice(1).filter(a => {
    if (a.startsWith('-')) return false;
    try { return fs.existsSync(a) && VIDEO_EXT.has(path.extname(a).toLowerCase()); } catch (_) { return false; }
  });
}

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 860,
    minHeight: 540,
    frame: false,
    backgroundColor: '#0d1017',
    show: false,
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      spellcheck: false
    }
  });
  Menu.setApplicationMenu(null);
  win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  win.once('ready-to-show', () => win.show());
  win.on('closed', () => { win = null; });

  // Shutting down used to fire and forget: the window vanished while mpv was
  // still being asked to quit, so a wedged engine could survive as an orphan
  // process and the last few seconds of progress were never written.
  win.on('close', e => {
    if (updater.blocksQuit()) { e.preventDefault(); return; }
    if (updater.canInstall()) { e.preventDefault(); updater.install(false); return; }
    store.saveNow();
    if (closing || !mpv?.isActive()) return;
    e.preventDefault();
    closing = true;
    mpv.stopNow().catch(() => {}).finally(() => {
      store.saveNow();
      if (win && !win.isDestroyed()) win.destroy();
    });
    setTimeout(() => { if (win && !win.isDestroyed()) win.destroy(); }, 3000);
  });

  win.on('maximize', () => send('win-state', { maximized: true }));
  win.on('unmaximize', () => send('win-state', { maximized: false }));

  mpv = new MpvController(win, store, (name, payload) => {
    // A file with no subtitle stream is the one moment auto-fetch is useful;
    // everything else is just relayed to the renderer.
    if (name === 'file-changing') { subtitleSync?.fileChanged(); return; }
    if (name === 'file-ready') {
      clearTimeout(subtitleSyncTimer);
      subtitleSyncTimer = setTimeout(() => subtitleSync?.automatic(), 1500);
      return;
    }
    if (name === 'file-changed') {
      subtitleSync?.fileChanged();
      clearTimeout(subtitleSyncTimer);
      subtitleSyncTimer = setTimeout(() => subtitleSync?.automatic(), 1500);
      return;
    }
    if (name === 'playback-ended') subtitleSync?.fileChanged();
    if (name === 'no-subtitles') return void autoFetchSubtitles(payload.path);
    send(name, payload);
  });
  subtitleSync = new SubtitleSync({
    engine: () => mpv, settings: () => store.load().settings,
    cacheDir: path.join(app.getPath('userData'), 'synced-subtitles'),
    toolsDir: app.isPackaged ? path.join(process.resourcesPath, 'sync') : path.join(app.getAppPath(), 'vendor', 'sync'),
    notify: state => { send('subsync-state', state); mpv?.sendOverlay('subsync-state', state); }
  });
  library = new Library(store, mpv.mpvExe(), (ch, payload) => send(ch, payload));
}

function send(ch, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(ch, payload);
  if (ch === 'update-state') mpv?.sendOverlay(ch, payload);
}

async function playFiles(files, startIndex) {
  try {
    await mpv.play(files, startIndex);
  } catch (e) {
    send('play-error', { message: String(e.message || e) });
  }
}

// ---------------- IPC ----------------
ipcMain.handle('get-state', () => {
  const d = store.load();
  return {
    folders: d.folders, items: d.items, playlists: d.playlists,
    history: d.history, settings: d.settings, prefs: d.prefs
  };
});

ipcMain.handle('add-folder', async () => {
  const r = await dialog.showOpenDialog(win, { properties: ['openDirectory'] });
  if (r.canceled || !r.filePaths.length) return null;
  const d = store.load();
  const p = r.filePaths[0];
  if (!d.folders.includes(p)) { d.folders.push(p); store.save(); }
  library.scanAll();
  return p;
});

ipcMain.handle('remove-folder', (_e, folder) => {
  const d = store.load();
  d.folders = d.folders.filter(f => f !== folder);
  store.save();
  library.scanAll();
});

ipcMain.handle('rescan', () => library.scanAll());

ipcMain.handle('play', (_e, { files, startIndex }) => playFiles(files, startIndex || 0));

ipcMain.handle('play-url', (_e, url) => {
  if (!/^[a-z]+:\/\//i.test(url)) return { error: 'Not a valid URL' };
  return playFiles([url], 0);
});

ipcMain.handle('open-file-dialog', async () => {
  const r = await dialog.showOpenDialog(win, {
    properties: ['openFile', 'multiSelections'],
    filters: [{ name: 'Video files', extensions: [...VIDEO_EXT].map(e => e.slice(1)) }]
  });
  if (r.canceled || !r.filePaths.length) return;
  return playFiles(r.filePaths, 0);
});

ipcMain.handle('save-settings', (_e, settings) => {
  const d = store.load();
  d.settings = { ...d.settings, ...settings };
  // Changing a default in Settings should visibly change the default, not be
  // shadowed forever by a value the player remembered earlier.
  if ('defaultSpeed' in settings) delete d.prefs.speed;
  if ('subScale' in settings) delete d.prefs['sub-scale'];
  if ('subPos' in settings) delete d.prefs['sub-pos'];
  if (settings.rememberPlayerState === false) d.prefs = {};
  // Changing what to search for is the usual reason to want another go at a
  // video auto-fetch already gave up on.
  if ('subLangs' in settings || 'autoSubs' in settings || 'onlineSubs' in settings) autoTried.clear();
  if ('subSyncMode' in settings && !['smart', 'gentle', 'offset'].includes(settings.subSyncMode)) d.settings.subSyncMode = 'smart';
  if (Object.keys(settings).some(k => k.startsWith('subSync'))) subtitleSync?.attempted.clear();
  store.save();
  send('settings-changed', d.settings);
  mpv?.sendOverlay('settings-changed', d.settings);
});

// "Reset to default" — from the player's own button or the Settings view.
ipcMain.handle('reset-player-prefs', async () => {
  if (mpv) return mpv.resetPrefs();
  const d = store.load();
  d.prefs = {};
  store.save();
  return null;
});

ipcMain.handle('playlist-create', (_e, name) => {
  const d = store.load();
  const pl = { id: 'pl_' + Date.now(), name, items: [] };
  d.playlists.push(pl);
  store.save();
  return pl;
});

ipcMain.handle('playlist-delete', (_e, id) => {
  const d = store.load();
  d.playlists = d.playlists.filter(p => p.id !== id);
  store.save();
});

ipcMain.handle('playlist-add', (_e, { id, paths }) => {
  const d = store.load();
  const pl = d.playlists.find(p => p.id === id);
  if (!pl) return;
  for (const p of paths) if (!pl.items.includes(p)) pl.items.push(p);
  store.save();
});

ipcMain.handle('playlist-remove-item', (_e, { id, path: p }) => {
  const d = store.load();
  const pl = d.playlists.find(pp => pp.id === id);
  if (!pl) return;
  pl.items = pl.items.filter(x => x !== p);
  store.save();
});

ipcMain.handle('clear-history', () => {
  const d = store.load();
  d.history = [];
  store.save();
});

ipcMain.handle('remove-progress', (_e, p) => {
  const d = store.load();
  if (d.items[p]) { d.items[p].progress = 0; store.save(); }
});

ipcMain.handle('show-in-folder', (_e, p) => shell.showItemInFolder(p));

// Opens the folder holding nova-diagnostics.log, so a freeze can be reported
// with evidence instead of a description.
ipcMain.handle('open-data-folder', () => shell.openPath(app.getPath('userData')));

ipcMain.handle('mpv-key', (_e, name) => {
  if (mpv?.isActive() && typeof name === 'string' && name.length <= 24) mpv.keypress(name);
});

// ---- overlay player IPC ----
ipcMain.handle('player-cmd', async (_e, cmd) => {
  if (!mpv?.isActive()) return null;
  try { return await mpv.exec(cmd); } catch (err) { return { error: String(err.message || err) }; }
});

ipcMain.handle('player-get', async (_e, name) => {
  if (!mpv?.isActive()) return null;
  try { return await mpv.getProp(name); } catch (_) { return null; }
});

ipcMain.handle('player-init', () => {
  if (!mpv?.isActive()) return null;
  return {
    props: mpv.props,
    settings: store.load().settings,
    alwaysOnTop: !!win?.isAlwaysOnTop()
  };
});

/* Leaving playback must work even when the engine is wedged — that is exactly
 * when the user most wants out. This path never waits on mpv's IPC. */
ipcMain.handle('player-exit', async () => {
  if (mpv) await mpv.stopNow();
  return true;
});

ipcMain.handle('player-reset', () => (mpv ? mpv.resetPrefs() : null));

// One round-trip for the media-info panel instead of a dozen.
const STAT_PROPS = [
  'filename', 'file-format', 'file-size', 'video-format', 'video-codec',
  'width', 'height', 'container-fps', 'estimated-vf-fps', 'video-bitrate',
  'hwdec-current', 'audio-codec-name', 'audio-params/channel-count',
  'audio-params/samplerate', 'audio-bitrate', 'frame-drop-count',
  'decoder-frame-drop-count', 'demuxer-cache-duration', 'avsync', 'path'
];
ipcMain.handle('player-stats', async () => {
  if (!mpv?.isActive()) return null;
  const out = {};
  await Promise.all(STAT_PROPS.map(async n => {
    try { out[n] = await mpv.getProp(n); } catch (_) { out[n] = null; }
  }));
  return out;
});

ipcMain.handle('player-load-sub', async () => {
  if (!mpv?.isActive()) return null;
  const r = await dialog.showOpenDialog(win, {
    title: 'Load subtitle file',
    properties: ['openFile'],
    filters: [{ name: 'Subtitles', extensions: ['srt', 'ass', 'ssa', 'sub', 'vtt', 'sup', 'idx'] }]
  });
  if (r.canceled || !r.filePaths.length) return null;
  try { await mpv.addSubtitle(r.filePaths[0], true); subtitleSync?.automatic(); return r.filePaths[0]; }
  catch (err) { return { error: String(err.message || err) }; }
});

// Automatic subtitle alignment stays in the main process; renderer supplies no paths.
ipcMain.handle('subsync-state', () => subtitleSync?.getState());
ipcMain.handle('subsync-start', (_e, options = {}) => subtitleSync?.start({ mode: options.mode, force: options.force === true }));
ipcMain.handle('subsync-cancel', () => subtitleSync?.cancel());
ipcMain.handle('subsync-apply', () => subtitleSync?.apply());
ipcMain.handle('subsync-undo', () => subtitleSync?.undo());
ipcMain.handle('subsync-folder', () => shell.openPath(path.join(app.getPath('userData'), 'synced-subtitles')));
ipcMain.handle('subsync-reference', async (_e, mode) => {
  const r = await dialog.showOpenDialog(win, { title: 'Choose a correctly timed subtitle as a reference', properties: ['openFile'], filters: [{ name: 'Text subtitles', extensions: ['srt', 'ass', 'ssa'] }] });
  if (r.canceled || !r.filePaths.length) return null;
  return subtitleSync?.start({ mode, reference: r.filePaths[0], force: true });
});

// ---------------- online subtitles ----------------

function samePath(a, b) {
  if (!a || !b) return false;
  return String(a).replace(/\//g, '\\').toLowerCase() === String(b).replace(/\//g, '\\').toLowerCase();
}

/* Search results are kept here rather than sent back and forth: the renderer
 * only ever needs to name the one it picked, and a download link that has been
 * round-tripped through the UI is a link the UI could have rewritten. */
let lastResults = [];

function subtitleSettings() {
  const s = store.load().settings;
  return {
    onlineSubs: s.onlineSubs !== false,
    autoSubs: s.autoSubs !== false,
    langs: subtitles.normLangs(s.subLangs)
  };
}

async function runSearch(file, opts = {}) {
  const s = subtitleSettings();
  if (!s.onlineSubs) return { results: [], error: 'Online subtitles are switched off in Settings' };
  if (!file) return { results: [], error: 'Nothing is playing' };
  const r = await subtitles.search(file, {
    langs: opts.langs && opts.langs.length ? opts.langs : s.langs,
    query: opts.query,
    season: opts.season,
    episode: opts.episode
  });
  lastResults = r.results;
  return r;
}

/* Fetch a subtitle by itself for a video that has none.
 *
 * Deliberately quiet: it reports what it did through the same passing toast the
 * rest of the player uses and never puts a dialog in the way. If nothing is
 * found it says so once and stops — a video with no subtitles anywhere should
 * not nag on every replay. */
const autoTried = new Set();

async function autoFetchSubtitles(file) {
  const s = subtitleSettings();
  if (!s.onlineSubs || !s.autoSubs) return;
  if (!file || /^[a-z]+:\/\//i.test(file)) return;      // streams: no hash, rarely a hit
  if (autoTried.has(file.toLowerCase())) return;
  autoTried.add(file.toLowerCase());
  if (subtitles.hasLocalSubtitle(file)) return;

  mpv?.sendOverlay('subs-auto', { state: 'searching' });
  try {
    const r = await subtitles.search(file, { langs: s.langs });
    const best = r.results[0];
    if (!best) {
      mpv?.sendOverlay('subs-auto', { state: 'none', error: r.error });
      return;
    }
    const got = await subtitles.download(file, best);
    // The search and the download together take a few seconds; by then the user
    // may well have skipped to the next episode.
    if (!mpv?.isActive() || !samePath(mpv.currentPath(), file)) return;
    await mpv.addSubtitle(got.file, true);
    subtitleSync?.automatic({ download: true, exact: best.hashMatch });
    mpv.sendOverlay('subs-auto', {
      state: 'applied',
      lang: best.lang,
      langName: best.langName,
      exact: best.hashMatch,
      name: best.name,
      file: got.file
    });
  } catch (err) {
    mpv?.sendOverlay('subs-auto', { state: 'error', error: subtitles.friendlyNetError(err) });
  }
}

ipcMain.handle('subs-context', () => {
  const file = mpv?.isActive() ? mpv.currentPath() : null;
  return {
    path: file,
    parsed: file ? subtitles.parseName(file) : null,
    languages: subtitles.LANGUAGES,
    ...subtitleSettings()
  };
});

ipcMain.handle('subs-search', async (_e, opts = {}) => {
  const file = mpv?.isActive() ? mpv.currentPath() : null;
  try {
    return await runSearch(file, opts);
  } catch (err) {
    return { results: [], error: subtitles.friendlyNetError(err) };
  }
});

/* Download the chosen result and switch to it immediately. */
ipcMain.handle('subs-apply', async (_e, id) => {
  const file = mpv?.isActive() ? mpv.currentPath() : null;
  if (!file) return { error: 'Nothing is playing' };
  const sub = lastResults.find(s => s.id === String(id));
  if (!sub) return { error: 'That subtitle is no longer in the results — search again' };
  try {
    const got = await subtitles.download(file, sub);
    if (!mpv?.isActive() || !samePath(mpv.currentPath(), file)) return { error: 'The video changed while the subtitle was downloading.' };
    await mpv.addSubtitle(got.file, true);
    subtitleSync?.automatic({ download: true, exact: sub.hashMatch });
    return { ok: true, file: got.file, adsRemoved: got.adsRemoved, langName: sub.langName, exact: sub.hashMatch };
  } catch (err) {
    return { error: subtitles.friendlyNetError(err) };
  }
});

/* Whole-folder fetch, which is the real answer to "I downloaded a series and
 * none of it has subtitles". Strictly sequential: the free service is rate
 * limited, and a burst of parallel requests gets the connection throttled for
 * everyone using it. */
let batchToken = 0;

ipcMain.handle('subs-batch', async (_e, paths) => {
  const s = subtitleSettings();
  if (!s.onlineSubs) return { error: 'Online subtitles are switched off in Settings' };
  const files = (Array.isArray(paths) ? paths : []).filter(p => typeof p === 'string' && p && !/^[a-z]+:\/\//i.test(p));
  if (!files.length) return { error: 'No videos to fetch subtitles for' };

  const token = ++batchToken;
  const summary = { total: files.length, done: 0, added: 0, skipped: 0, failed: 0, cancelled: false };

  for (const file of files) {
    if (token !== batchToken) { summary.cancelled = true; break; }
    const name = path.basename(file);
    send('subs-batch', { ...summary, current: name, state: 'searching' });
    try {
      if (subtitles.hasLocalSubtitle(file)) {
        summary.skipped++;
      } else {
        const r = await subtitles.search(file, { langs: s.langs });
        const best = r.results[0];
        if (!best) summary.failed++;
        else { await subtitles.download(file, best); summary.added++; }
      }
    } catch (err) {
      summary.failed++;
      summary.lastError = subtitles.friendlyNetError(err);
    }
    summary.done++;
    send('subs-batch', { ...summary, current: name, state: 'progress' });
  }

  send('subs-batch', { ...summary, state: 'done' });
  return summary;
});

ipcMain.handle('subs-batch-cancel', () => { batchToken++; });

/* Search on behalf of a video that is not the one playing (library view). */
ipcMain.handle('subs-search-file', async (_e, { path: file, ...opts }) => {
  try {
    return await runSearch(file, opts);
  } catch (err) {
    return { results: [], error: subtitles.friendlyNetError(err) };
  }
});

ipcMain.handle('subs-download-file', async (_e, { path: file, id }) => {
  const sub = lastResults.find(s => s.id === String(id));
  if (!sub) return { error: 'That subtitle is no longer in the results — search again' };
  try {
    const got = await subtitles.download(file, sub);
    // If it happens to be what is on screen, put it up straight away.
    if (mpv?.isActive() && samePath(mpv.currentPath(), file)) {
      try { await mpv.addSubtitle(got.file, true); subtitleSync?.automatic({ download: true, exact: sub.hashMatch }); } catch (_) {}
    }
    return { ok: true, file: got.file, adsRemoved: got.adsRemoved, langName: sub.langName };
  } catch (err) {
    return { error: subtitles.friendlyNetError(err) };
  }
});

// window dragging from the overlay's custom title bar
let dragTimer = null, dragOffset = null;
ipcMain.handle('win-drag-start', () => {
  if (!win || win.isFullScreen() || win.isMaximized()) return;
  const { screen } = require('electron');
  const cur = screen.getCursorScreenPoint();
  const b = win.getBounds();
  dragOffset = { dx: cur.x - b.x, dy: cur.y - b.y };
  clearInterval(dragTimer);
  dragTimer = setInterval(() => {
    if (!win || win.isDestroyed()) return clearInterval(dragTimer);
    const p = screen.getCursorScreenPoint();
    win.setPosition(p.x - dragOffset.dx, p.y - dragOffset.dy);
  }, 16);
});
ipcMain.handle('win-drag-end', () => { clearInterval(dragTimer); dragTimer = null; });

// edge-resize driven from the overlay (its grips cover the native borders)
let resizeTimer = null, resizeCtx = null;
const MIN_W = 860, MIN_H = 540;
ipcMain.handle('win-resize-start', (_e, edge) => {
  if (!win || win.isFullScreen() || win.isMaximized()) return;
  if (typeof edge !== 'string' || !/^[nsew]{1,2}$/.test(edge)) return;
  const { screen } = require('electron');
  resizeCtx = { edge, start: screen.getCursorScreenPoint(), bounds: win.getBounds() };
  clearInterval(resizeTimer);
  resizeTimer = setInterval(() => {
    if (!win || win.isDestroyed() || !resizeCtx) return clearInterval(resizeTimer);
    const p = screen.getCursorScreenPoint();
    const dx = p.x - resizeCtx.start.x, dy = p.y - resizeCtx.start.y;
    const b = { ...resizeCtx.bounds };
    const e = resizeCtx.edge;
    if (e.includes('e')) b.width = Math.max(MIN_W, resizeCtx.bounds.width + dx);
    if (e.includes('s')) b.height = Math.max(MIN_H, resizeCtx.bounds.height + dy);
    if (e.includes('w')) {
      b.width = Math.max(MIN_W, resizeCtx.bounds.width - dx);
      b.x = resizeCtx.bounds.x + (resizeCtx.bounds.width - b.width);
    }
    if (e.includes('n')) {
      b.height = Math.max(MIN_H, resizeCtx.bounds.height - dy);
      b.y = resizeCtx.bounds.y + (resizeCtx.bounds.height - b.height);
    }
    win.setBounds(b);
  }, 16);
});
ipcMain.handle('win-resize-end', () => { clearInterval(resizeTimer); resizeTimer = null; resizeCtx = null; });

ipcMain.handle('win-cmd', (_e, cmd) => {
  if (!win) return;
  if (cmd === 'min') win.minimize();
  else if (cmd === 'max') win.isMaximized() ? win.unmaximize() : win.maximize();
  else if (cmd === 'close') win.close();
  else if (cmd === 'top') {
    const on = !win.isAlwaysOnTop();
    win.setAlwaysOnTop(on, 'screen-saver');
    return on;
  }
  return null;
});

ipcMain.handle('app-info', () => ({
  version: app.getVersion(),
  userData: app.getPath('userData')
}));

ipcMain.handle('update-state', () => updater.getState());
ipcMain.handle('update-check', () => updater.check());
ipcMain.handle('update-install', () => updater.install(true));
ipcMain.handle('update-download-page', () => shell.openExternal('https://github.com/SadikTD/nova-player/releases/latest'));

// ---------------- lifecycle ----------------
app.whenReady().then(() => {
  createWindow();
  // first scan shortly after boot so the UI appears instantly
  setTimeout(() => library.scanAll(), 600);
  // Checks run in the background; status is visible in both library and player.
  setTimeout(() => updater.init(send, async () => {
    if (mpv?.isActive()) await mpv.stopNow();
    store.saveNow();
  }), 4000);
  // launched via "Open with" on a video file
  if (pendingOpen.length) setTimeout(() => playFiles(pendingOpen, 0), 400);
});

app.on('window-all-closed', () => app.quit());

// Last resort: never leave a stray engine process holding a video file open.
app.on('before-quit', e => {
  if (updater.blocksQuit()) { e.preventDefault(); return; }
  if (updater.canInstall()) { e.preventDefault(); updater.install(false); return; }
  closing = true;
  subtitleSync?.cancel();
  store.saveNow();
  if (mpv?.isActive()) { try { mpv.proc.kill(); } catch (_) {} }
});
