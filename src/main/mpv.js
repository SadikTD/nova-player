/*
 * MpvController — spawns the bundled mpv engine embedded inside the Electron
 * window (--wid), talks to it over a named-pipe JSON IPC, and manages the
 * transparent overlay window that carries all playback controls.
 *
 * Why an overlay window: mpv's embedded child window on Windows is hit-test
 * transparent — mouse input falls through it to whatever is beneath. So the
 * video surface is display-only, and ALL interaction happens in a separate
 * transparent BrowserWindow positioned exactly over it.
 */
const { spawn } = require('child_process');
const net = require('net');
const path = require('path');
const fs = require('fs');
const { app, BrowserWindow } = require('electron');
const win32 = require('./win32');

function resPath(...p) {
  const base = app.isPackaged ? process.resourcesPath : path.join(app.getAppPath());
  return path.join(base, ...p);
}

const OBSERVED = [
  'time-pos', 'duration', 'pause', 'path', 'media-title', 'fullscreen',
  'eof-reached', 'speed', 'volume', 'mute', 'playlist-pos', 'playlist-count',
  'track-list', 'chapter-list', 'chapter', 'paused-for-cache', 'sub-delay',
  'sub-visibility', 'sub-pos', 'brightness', 'contrast', 'gamma', 'saturation',
  'video-zoom', 'ab-loop-a', 'ab-loop-b', 'loop-file', 'video-rotate',
  'video-aspect-override', 'sub-scale'
];

const ALLOWED_CMDS = new Set([
  'seek', 'set_property', 'cycle', 'add', 'multiply', 'keypress',
  'playlist-next', 'playlist-prev', 'playlist-play-index', 'ab-loop',
  'screenshot', 'quit-watch-later', 'frame-step', 'frame-back-step',
  'sub-add', 'show-text', 'cycle-values'
]);

class MpvController {
  constructor(win, storeMod, onEvent) {
    this.win = win;                 // main BrowserWindow (library + video surface)
    this.store = storeMod;
    this.onEvent = onEvent;
    this.proc = null;
    this.sock = null;
    this.overlay = null;            // transparent controls window
    this.childHwnd = null;
    this.reqId = 1;
    this.pending = new Map();
    this.props = {};                // latest observed property values
    this.buf = '';
    this._resizeHooked = false;
    this._lastTimePosRelay = 0;
  }

  mpvExe() { return resPath(app.isPackaged ? 'mpv' : 'vendor/mpv', 'mpv.exe'); }
  configDir() { return resPath('mpv-config'); }

  isActive() { return !!this.proc; }

  async play(files, startIndex = 0) {
    this._pendingResume = this._storedResume(files[startIndex]);
    if (this.proc) {
      await this.command(['playlist-clear']);
      await this.command(['loadfile', files[startIndex], 'replace']);
      // mpv stays paused if the previous file ended (keep-open pauses at EOF),
      // which otherwise leaves the newly chosen video frozen on frame one.
      await this.command(['set_property', 'pause', false]);
      await this._appendRest(files, startIndex);
      return;
    }

    const s = this.store.load().settings;
    const hwnd = win32.hwndFromBuffer(this.win.getNativeWindowHandle());
    this.pipeName = `nova-mpv-${process.pid}-${Date.now()}`;
    const watchLaterDir = path.join(app.getPath('userData'), 'watch_later');
    fs.mkdirSync(watchLaterDir, { recursive: true });

    const args = [
      `--wid=${hwnd}`,
      `--input-ipc-server=\\\\.\\pipe\\${this.pipeName}`,
      `--config-dir=${this.configDir()}`,
      `--watch-later-dir=${watchLaterDir}`,
      '--force-window=yes',
      '--idle=no',
      '--keep-open=yes',
      `--volume-max=${s.volumeMax || 200}`,
      `--sub-scale=${s.subScale || 1}`,
      `--sub-border-size=${s.subBorder ?? 2}`,
      `--sub-pos=${s.subPos ?? 90}`,
      `--speed=${s.defaultSpeed || 1}`,
      s.hwdec ? '--hwdec=auto-safe' : '--hwdec=no',
      s.rememberPosition ? '--save-position-on-quit' : '--no-resume-playback',
    ];
    if (s.audioLang) args.push(`--alang=${s.audioLang}`);
    if (s.subLang) args.push(`--slang=${s.subLang}`);
    args.push('--', files[startIndex]);

    this.props = {};
    this.proc = spawn(this.mpvExe(), args, { windowsHide: false });
    this.proc.on('exit', () => this._onExit());
    this.proc.stderr?.on('data', d => { this._lastErr = String(d).slice(0, 2000); });

    await this._connect();
    this._startWatchdog();
    await this._appendRest(files, startIndex);

    this._hookWindow(hwnd);
    this._createOverlay();
    this.onEvent('playback-started', { path: files[startIndex] });
  }

  /* The playing entry loads first (instant start), the rest append in order,
   * then the playing entry is moved back to its natural position so the
   * playlist reads E01, E02, … with the current one in the right place. */
  async _appendRest(files, startIndex) {
    for (let i = 0; i < files.length; i++) {
      if (i !== startIndex) await this.command(['loadfile', files[i], 'append']);
    }
    if (startIndex > 0) {
      try { await this.command(['playlist-move', 0, startIndex + 1]); } catch (_) {}
    }
  }

  // ---------- window management ----------

  _hookWindow(parentHwnd) {
    const locate = () => {
      if (!this.proc) return;
      this.childHwnd = win32.findMpvChild(parentHwnd);
      if (this.childHwnd) {
        this._fit();
        clearInterval(this._raiseTimer);
        this._raiseTimer = setInterval(() => {
          if (this.proc && this.childHwnd) win32.raiseChild(this.childHwnd);
        }, 2000);
      } else setTimeout(locate, 120);
    };
    locate();

    if (!this._resizeHooked) {
      this._resizeHooked = true;
      const fit = () => { this._fit(); this._syncOverlayBounds(); };
      this.win.on('resize', fit);
      this.win.on('move', () => this._syncOverlayBounds());
      this.win.on('maximize', fit);
      this.win.on('unmaximize', fit);
      this.win.on('restore', fit);
      this.win.on('enter-full-screen', () => setTimeout(fit, 60));
      this.win.on('leave-full-screen', () => setTimeout(fit, 60));
      this.win.on('focus', () => {
        if (this.overlay && !this.overlay.isDestroyed()) this.overlay.focus();
      });
    }
  }

  _fit() {
    if (!this.childHwnd || !this.proc) return;
    const [w, h] = this.win.getContentSize();
    const factor = require('electron').screen.getDisplayMatching(this.win.getBounds()).scaleFactor || 1;
    win32.fitChild(this.childHwnd, 0, 0, Math.round(w * factor), Math.round(h * factor));
  }

  _createOverlay() {
    if (this.overlay) return;
    this.overlay = new BrowserWindow({
      parent: this.win,
      frame: false,
      transparent: true,
      hasShadow: false,
      resizable: false,
      movable: false,
      minimizable: false,
      maximizable: false,
      skipTaskbar: true,
      show: false,
      backgroundColor: '#00000000',
      webPreferences: {
        preload: path.join(__dirname, '..', 'preload-player.js'),
        contextIsolation: true,
        nodeIntegration: false,
        spellcheck: false
      }
    });
    this.overlay.loadFile(path.join(__dirname, '..', 'renderer', 'player.html'));
    this.overlay.once('ready-to-show', () => {
      this._syncOverlayBounds();
      if (!this.overlay) return;
      this.overlay.show();
      this.overlay.focus();
      // Keyboard shortcuts (including Esc) only reach the overlay while it holds
      // focus, so take it back whenever the app is activated.
      clearInterval(this._focusTimer);
      this._focusTimer = setInterval(() => {
        if (!this.overlay || this.overlay.isDestroyed() || !this.proc) return;
        if (this.win.isFocused() && !this.overlay.isFocused()) this.overlay.focus();
      }, 1000);
    });
    this.overlay.on('closed', () => {
      this.overlay = null;
      if (this.proc) this.stop();
    });
  }

  _syncOverlayBounds() {
    if (!this.overlay || this.overlay.isDestroyed()) return;
    try { this.overlay.setBounds(this.win.getContentBounds()); } catch (_) {}
  }

  _sendOverlay(ch, payload) {
    if (this.overlay && !this.overlay.isDestroyed()) {
      this.overlay.webContents.send(ch, payload);
    }
  }

  // ---------- mpv IPC ----------

  _connect() {
    return new Promise((resolve, reject) => {
      const started = Date.now();
      const tryConnect = () => {
        if (!this.proc) return reject(new Error('mpv exited during startup: ' + (this._lastErr || '')));
        const sock = net.connect(`\\\\.\\pipe\\${this.pipeName}`);
        sock.on('connect', () => {
          this._attachSocket(sock);
          this._init().then(resolve, reject);
        });
        sock.on('error', () => {
          sock.destroy();
          if (Date.now() - started > 15000) reject(new Error('mpv IPC connect timeout'));
          else setTimeout(tryConnect, 120);
        });
      };
      tryConnect();
    });
  }

  _attachSocket(sock) {
    this.sock = sock;
    this.buf = '';
    this._lastEventAt = Date.now();
    sock.setEncoding('utf8');
    sock.on('data', d => { this._lastEventAt = Date.now(); this._onData(d); });
    // A dropped control channel used to be swallowed here, leaving the app alive
    // but unable to control or observe playback — every button a silent no-op.
    sock.on('error', err => this._onSocketLost('error: ' + (err && err.message)));
    sock.on('close', () => this._onSocketLost('closed'));
    sock.on('end', () => this._onSocketLost('ended'));
  }

  _onSocketLost(why) {
    if (!this.proc || this._reconnecting || this._stopping) return;
    if (this.sock) { try { this.sock.destroy(); } catch (_) {} }
    this.sock = null;
    // The pipe also closes when mpv is quitting normally, which is not a fault.
    // Give the exit handler a moment to settle before treating this as a drop.
    setTimeout(() => {
      if (!this.proc || this._reconnecting || this._stopping) return;
      if (this.proc.exitCode !== null || this.proc.signalCode !== null) return;
      console.warn('[mpv] control channel lost (' + why + ') — reconnecting');
      this._reconnect();
    }, 400);
  }

  /* Re-establish the control channel without interrupting playback. */
  async _reconnect() {
    if (this._reconnecting || !this.proc) return;
    this._reconnecting = true;
    this._sendOverlay('mpv-link', { state: 'reconnecting' });

    for (let attempt = 1; attempt <= 12 && this.proc; attempt++) {
      await new Promise(r => setTimeout(r, 250));
      const ok = await new Promise(resolve => {
        const sock = net.connect(`\\\\.\\pipe\\${this.pipeName}`);
        const done = v => { sock.removeAllListeners(); resolve(v); if (!v) sock.destroy(); };
        sock.once('connect', () => { this._attachSocket(sock); done(true); });
        sock.once('error', () => done(false));
        setTimeout(() => done(false), 1500);
      });
      if (!ok) continue;

      try {
        this.reqId = 1;
        this.pending.clear();
        await this._init();                       // re-register property observers
        this._reconnecting = false;
        console.warn('[mpv] control channel restored');
        this._sendOverlay('mpv-link', { state: 'ok' });
        return;
      } catch (_) {
        if (this.sock) { try { this.sock.destroy(); } catch (_) {} }
        this.sock = null;
      }
    }

    // Could not recover: stop cleanly rather than sit there looking frozen.
    this._reconnecting = false;
    console.error('[mpv] control channel unrecoverable — stopping playback');
    this._sendOverlay('mpv-link', { state: 'lost' });
    try { this.proc.kill(); } catch (_) {}
  }

  /* Poll the engine; two consecutive failures mean the channel is stale even if
   * the socket still looks open (which is how the original freeze presented). */
  _startWatchdog() {
    clearInterval(this._watchdog);
    this._watchdog = setInterval(async () => {
      if (!this.proc || this._reconnecting) return;
      if (!this.sock) return this._onSocketLost('no socket');
      try {
        await this.command(['get_property', 'time-pos']);
        this._missedBeats = 0;
      } catch (_) {
        this._missedBeats = (this._missedBeats || 0) + 1;
        if (this._missedBeats >= 2) {
          this._missedBeats = 0;
          this._onSocketLost('unresponsive');
        }
      }
    }, 10000);
  }

  async _init() {
    for (let i = 0; i < OBSERVED.length; i++) {
      await this.command(['observe_property', i + 1, OBSERVED[i]]);
    }
  }

  _onData(chunk) {
    this.buf += chunk;
    let idx;
    while ((idx = this.buf.indexOf('\n')) >= 0) {
      const line = this.buf.slice(0, idx).trim();
      this.buf = this.buf.slice(idx + 1);
      if (!line) continue;
      let msg;
      try { msg = JSON.parse(line); } catch (_) { continue; }
      if (msg.request_id && this.pending.has(msg.request_id)) {
        const { resolve, reject } = this.pending.get(msg.request_id);
        this.pending.delete(msg.request_id);
        msg.error === 'success' ? resolve(msg.data) : reject(new Error(msg.error));
      } else if (msg.event) {
        this._onMpvEvent(msg);
      }
    }
  }

  /* Resume point from our own store. mpv's watch-later file is only written on a
   * clean exit, so it is lost whenever the app is killed — ours is written every
   * few seconds and survives that. */
  _storedResume(file) {
    const it = this.store.load().items[file];
    if (!it || !it.progress || !it.duration) return null;
    if (it.progress < 15) return null;                       // barely started
    if (it.progress > it.duration - 20) return null;         // effectively finished
    return { file, pos: it.progress };
  }

  _onMpvEvent(msg) {
    if (msg.event === 'file-loaded') {
      this._applyPendingResume();
      return;
    }
    if (msg.event !== 'property-change') return;
    const { name, data } = msg;
    this.props[name] = data;

    if (name === 'time-pos' && typeof data === 'number') {
      this._timePos = data;
      this._trackProgress();
      const now = Date.now();
      if (now - this._lastTimePosRelay < 150) return;   // throttle relay only
      this._lastTimePosRelay = now;
    } else if (name === 'duration' && typeof data === 'number') {
      this._duration = data;
    } else if (name === 'path' && data) {
      this._path = data;
      this._markHistory(data);
    } else if (name === 'fullscreen' && typeof data === 'boolean') {
      if (this.win.isFullScreen() !== data) this.win.setFullScreen(data);
    } else if (name === 'eof-reached' && data === true) {
      this._saveProgress(true);
    }

    this._sendOverlay('mpv-prop', { name, data });
  }

  /* Seek to our stored position once the file is actually open. If mpv's own
   * watch-later already restored it, this lands on the same spot and is a no-op. */
  async _applyPendingResume() {
    const r = this._pendingResume;
    this._pendingResume = null;
    if (!r || r.file !== this._path) return;
    try {
      const at = await this.command(['get_property', 'time-pos']);
      if (typeof at === 'number' && Math.abs(at - r.pos) < 5) return;  // already there
      await this.command(['seek', r.pos, 'absolute+exact']);
    } catch (_) { /* resume is best-effort */ }
  }

  _trackProgress() {
    const now = Date.now();
    if (this._lastSave && now - this._lastSave < 3000) return;
    this._lastSave = now;
    this._saveProgress(false);
  }

  _saveProgress(finished) {
    if (!this._path) return;
    const pos = finished ? (this._duration || 0) : (this._timePos || 0);
    const data = this.store.load();
    const it = data.items[this._path];
    // Never let a just-loaded file sitting at 0 wipe out an existing resume point.
    if (!finished && pos < 1 && it && it.progress > 1) return;
    const entry = it || (data.items[this._path] = { addedAt: Date.now() });
    entry.lastPlayed = Date.now();
    if (this._duration) entry.duration = this._duration;
    entry.progress = pos;
    this.store.save();
    this.onEvent('progress', { path: this._path, progress: entry.progress, duration: entry.duration });
  }

  _markHistory(p) {
    const data = this.store.load();
    data.history = [{ path: p, at: Date.now() }, ...data.history.filter(h => h.path !== p)].slice(0, 200);
    this.store.save();
  }

  command(cmd) {
    return new Promise((resolve, reject) => {
      if (!this.sock) return reject(new Error('mpv not running'));
      const id = this.reqId++;
      this.pending.set(id, { resolve, reject });
      this.sock.write(JSON.stringify({ command: cmd, request_id: id }) + '\n');
      setTimeout(() => {
        if (this.pending.has(id)) { this.pending.delete(id); reject(new Error('mpv command timeout')); }
      }, 5000);
    });
  }

  /* Whitelisted command execution for the overlay UI */
  exec(cmd) {
    if (!Array.isArray(cmd) || !cmd.length || !ALLOWED_CMDS.has(String(cmd[0]))) {
      return Promise.reject(new Error('command not allowed'));
    }
    return this.command(cmd);
  }

  getProp(name) {
    if (typeof name !== 'string' || name.length > 64) return Promise.reject(new Error('bad property'));
    return this.command(['get_property', name]);
  }

  keypress(name) {
    if (!this.proc) return;
    this.command(['keypress', name]).catch(() => {});
  }

  async stop() {
    if (!this.proc) return;
    this._stopping = true;
    try { await this.command(['quit-watch-later']); } catch (_) { try { this.proc.kill(); } catch (_) {} }
  }

  _onExit() {
    clearInterval(this._raiseTimer);
    this._raiseTimer = null;
    clearInterval(this._watchdog);
    this._watchdog = null;
    clearInterval(this._focusTimer);
    this._focusTimer = null;
    this._reconnecting = false;
    this._stopping = false;
    this._pendingResume = null;
    this._saveProgress(false);
    this.proc = null;
    this.sock?.destroy();
    this.sock = null;
    this.childHwnd = null;
    this.pending.clear();
    this.props = {};
    if (this.overlay && !this.overlay.isDestroyed()) {
      const o = this.overlay;
      this.overlay = null;
      o.destroy();
    }
    if (this.win.isFullScreen()) this.win.setFullScreen(false);
    this.onEvent('playback-ended', { path: this._path });
    this._path = null; this._timePos = null; this._duration = null;
  }
}

module.exports = { MpvController, resPath };
