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
    if (this.proc) {
      await this.command(['playlist-clear']);
      await this.command(['loadfile', files[startIndex], 'replace']);
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
        }, 500);
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
      if (this.overlay) this.overlay.show();
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
          this.sock = sock;
          sock.setEncoding('utf8');
          sock.on('data', d => this._onData(d));
          sock.on('error', () => {});
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

  _onMpvEvent(msg) {
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

  _trackProgress() {
    const now = Date.now();
    if (this._lastSave && now - this._lastSave < 3000) return;
    this._lastSave = now;
    this._saveProgress(false);
  }

  _saveProgress(finished) {
    if (!this._path) return;
    const data = this.store.load();
    const it = data.items[this._path] || (data.items[this._path] = { addedAt: Date.now() });
    it.lastPlayed = Date.now();
    if (this._duration) it.duration = this._duration;
    it.progress = finished ? (this._duration || 0) : (this._timePos || 0);
    this.store.save();
    this.onEvent('progress', { path: this._path, progress: it.progress, duration: it.duration });
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
    try { await this.command(['quit-watch-later']); } catch (_) { try { this.proc.kill(); } catch (_) {} }
  }

  _onExit() {
    clearInterval(this._raiseTimer);
    this._raiseTimer = null;
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
