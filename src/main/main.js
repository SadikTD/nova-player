const { app, BrowserWindow, ipcMain, dialog, shell, Menu } = require('electron');
const path = require('path');
const fs = require('fs');
const store = require('./store');
const { MpvController } = require('./mpv');
const { Library, VIDEO_EXT } = require('./library');

let win = null;
let mpv = null;
let library = null;
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
  win.on('close', () => { if (mpv?.isActive()) mpv.stop(); store.saveNow(); });

  win.on('maximize', () => send('win-state', { maximized: true }));
  win.on('unmaximize', () => send('win-state', { maximized: false }));

  mpv = new MpvController(win, store, (name, payload) => send(name, payload));
  library = new Library(store, mpv.mpvExe(), (ch, payload) => send(ch, payload));
}

function send(ch, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(ch, payload);
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
  return { folders: d.folders, items: d.items, playlists: d.playlists, history: d.history, settings: d.settings };
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
  store.save();
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
  return { props: mpv.props, settings: store.load().settings };
});

ipcMain.handle('player-load-sub', async () => {
  if (!mpv?.isActive()) return null;
  const r = await dialog.showOpenDialog(win, {
    title: 'Load subtitle file',
    properties: ['openFile'],
    filters: [{ name: 'Subtitles', extensions: ['srt', 'ass', 'ssa', 'sub', 'vtt', 'sup', 'idx'] }]
  });
  if (r.canceled || !r.filePaths.length) return null;
  try { await mpv.exec(['sub-add', r.filePaths[0], 'select']); return r.filePaths[0]; }
  catch (err) { return { error: String(err.message || err) }; }
});

// settings changed from inside the player (e.g. subtitle position)
ipcMain.handle('player-save-setting', (_e, patch) => {
  if (!patch || typeof patch !== 'object') return;
  const ALLOWED = new Set(['subPos', 'subScale', 'subBorder', 'defaultSpeed', 'volumeMax']);
  const d = store.load();
  for (const [k, v] of Object.entries(patch)) {
    if (ALLOWED.has(k) && typeof v === 'number' && isFinite(v)) d.settings[k] = v;
  }
  store.save();
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
});

ipcMain.handle('app-info', () => ({
  version: app.getVersion(),
  userData: app.getPath('userData')
}));

// ---------------- lifecycle ----------------
app.whenReady().then(() => {
  createWindow();
  // first scan shortly after boot so the UI appears instantly
  setTimeout(() => library.scanAll(), 600);
  // launched via "Open with" on a video file
  if (pendingOpen.length) setTimeout(() => playFiles(pendingOpen, 0), 400);
});

app.on('window-all-closed', () => app.quit());
