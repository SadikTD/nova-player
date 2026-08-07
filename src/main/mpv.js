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

/* Windows paths differ only by case and slash direction; mpv echoes back the
 * string it was given, which is not always byte-identical to ours. */
function samePath(a, b) {
  if (!a || !b) return false;
  return String(a).replace(/\//g, '\\').toLowerCase() === String(b).replace(/\//g, '\\').toLowerCase();
}

const OBSERVED = [
  'time-pos', 'duration', 'pause', 'path', 'media-title', 'fullscreen',
  'eof-reached', 'speed', 'volume', 'mute', 'playlist-pos', 'playlist-count',
  'track-list', 'chapter-list', 'chapter', 'paused-for-cache', 'sub-delay',
  'sub-visibility', 'sub-pos', 'brightness', 'contrast', 'gamma', 'saturation',
  'video-zoom', 'ab-loop-a', 'ab-loop-b', 'loop-file', 'video-rotate',
  'video-aspect-override', 'sub-scale', 'demuxer-cache-time'
];

const ALLOWED_CMDS = new Set([
  'seek', 'set_property', 'cycle', 'add', 'multiply', 'keypress',
  'playlist-next', 'playlist-prev', 'playlist-play-index', 'ab-loop',
  'screenshot', 'frame-step', 'frame-back-step',
  'sub-add', 'show-text', 'cycle-values'
]);

/* Player state that survives both file changes and app restarts. Everything
 * here is global in mpv (not per-file), so restoring it at spawn is enough.
 * Deliberately excluded: zoom, rotation and aspect override — those are edits
 * for one particular video and would be baffling if they stuck around. */
const STICKY = {
  'speed':          { def: s => s.defaultSpeed || 1, arg: 'speed',          type: 'number' },
  'volume':         { def: () => 100,                arg: 'volume',         type: 'number' },
  'mute':           { def: () => false,              arg: 'mute',           type: 'bool' },
  'brightness':     { def: () => 0,                  arg: 'brightness',     type: 'number' },
  'contrast':       { def: () => 0,                  arg: 'contrast',       type: 'number' },
  'gamma':          { def: () => 0,                  arg: 'gamma',          type: 'number' },
  'saturation':     { def: () => 0,                  arg: 'saturation',     type: 'number' },
  'sub-scale':      { def: s => s.subScale || 1,     arg: 'sub-scale',      type: 'number' },
  'sub-pos':        { def: s => s.subPos ?? 90,      arg: 'sub-pos',        type: 'number' },
  'sub-visibility': { def: () => true,               arg: 'sub-visibility', type: 'bool' }
};

// Per-video adjustments the reset button should also clear, even though they
// are never persisted.
const TRANSIENT_RESET = {
  'video-zoom': 0, 'video-rotate': 0, 'video-aspect-override': '-1',
  'audio-delay': 0, 'sub-delay': 0, 'loop-file': 'no'
};

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
    this._files = [];               // current queue, kept for crash recovery
    this._recoveries = 0;
  }

  mpvExe() { return resPath(app.isPackaged ? 'mpv' : 'vendor/mpv', 'mpv.exe'); }
  configDir() { return resPath('mpv-config'); }

  isActive() { return !!this.proc; }

  // ---------- remembered player state ----------

  /* Values to start the next session with: the stored defaults, overridden by
   * whatever the user last adjusted in the player. */
  effectivePrefs() {
    const d = this.store.load();
    const s = d.settings;
    const out = {};
    for (const [name, spec] of Object.entries(STICKY)) out[name] = spec.def(s);
    if (s.rememberPlayerState !== false) {
      for (const [name, v] of Object.entries(d.prefs || {})) {
        if (name in STICKY && v != null) out[name] = v;
      }
    }
    // a remembered boost can outlive a lowered boost ceiling
    const max = s.volumeMax || 200;
    if (typeof out.volume === 'number') out.volume = Math.max(0, Math.min(out.volume, max));
    return out;
  }

  defaultPrefs() {
    const s = this.store.load().settings;
    const out = {};
    for (const [name, spec] of Object.entries(STICKY)) out[name] = spec.def(s);
    return out;
  }

  /* Settle before writing: dragging a slider or holding the speed boost fires
   * dozens of property changes, and only where it lands is worth remembering. */
  _recordPref(name, value) {
    const spec = STICKY[name];
    if (!spec) return;
    if (this.store.load().settings.rememberPlayerState === false) return;
    if (spec.type === 'number' && (typeof value !== 'number' || !isFinite(value))) return;
    if (spec.type === 'bool' && typeof value !== 'boolean') return;
    this._prefTimers = this._prefTimers || {};
    clearTimeout(this._prefTimers[name]);
    this._prefTimers[name] = setTimeout(() => {
      const d = this.store.load();
      const def = spec.def(d.settings);
      // a value back at its default is not a customisation — drop it so the
      // remembered list only ever names things the user actually changed
      const isDefault = spec.type === 'number' ? Math.abs(value - def) < 1e-6 : value === def;
      if (isDefault) {
        if (!(name in d.prefs)) return;
        delete d.prefs[name];
      } else {
        if (d.prefs[name] === value) return;
        d.prefs[name] = value;
      }
      this.store.save();
    }, 1500);
  }

  /* Put every remembered value back to its default, live. */
  async resetPrefs() {
    for (const t of Object.values(this._prefTimers || {})) clearTimeout(t);
    this._prefTimers = {};
    const d = this.store.load();
    d.prefs = {};
    this.store.save();
    const defs = this.defaultPrefs();
    if (this.sock) {
      for (const [name, v] of Object.entries({ ...defs, ...TRANSIENT_RESET })) {
        try { await this.command(['set_property', name, v]); } catch (_) {}
      }
    }
    return defs;
  }

  // ---------- playback ----------

  async play(files, startIndex = 0, opts = {}) {
    files = (files || []).filter(Boolean);
    if (!files.length) throw new Error('nothing to play');
    startIndex = Math.max(0, Math.min(startIndex, files.length - 1));
    this._files = files.slice();
    this._queueIndex = startIndex;

    const resume = this._storedResume(files[startIndex]);
    this._pendingResume = resume;
    if (!opts.recovered) this._recoveries = 0;

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
    const prefs = this.effectivePrefs();
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
      `--sub-border-size=${s.subBorder ?? 2}`,
      s.hwdec ? '--hwdec=auto-safe' : '--hwdec=no',
      // Nova owns resume: our store is written every few seconds and survives a
      // kill, mpv's watch-later file only exists after a clean quit. Letting
      // both restore a position means the stale one sometimes wins.
      '--no-resume-playback',
      '--no-save-position-on-quit'
    ];
    for (const [name, spec] of Object.entries(STICKY)) {
      const v = prefs[name];
      args.push(`--${spec.arg}=${spec.type === 'bool' ? (v ? 'yes' : 'no') : v}`);
    }
    if (s.audioLang) args.push(`--alang=${s.audioLang}`);
    if (s.subLang) args.push(`--slang=${s.subLang}`);
    // Start at the stored position from frame one. Seeking after the fact races
    // with mpv's own startup and used to be missed entirely on the first video
    // of a session, which is why resume "only worked sometimes".
    if (resume) args.push(`--start=${resume.pos.toFixed(3)}`);
    args.push('--', files[startIndex]);

    this.props = {};
    this.proc = spawn(this.mpvExe(), args, { windowsHide: false });
    this.proc.on('exit', () => this._onExit());
    this.proc.on('error', () => this._onExit());
    this.proc.stderr?.on('data', d => { this._lastErr = String(d).slice(0, 2000); });

    try {
      await this._connect();
    } catch (err) {
      // don't leave a running-but-uncontrollable engine behind
      await this._hardStop();
      throw err;
    }
    this._startWatchdog();
    await this._appendRest(files, startIndex);

    this._hookWindow(hwnd);
    const hadOverlay = !!(this.overlay && !this.overlay.isDestroyed());
    this._createOverlay();
    if (resume) this._resumeNotice = { pos: resume.pos, recovered: !!opts.recovered };
    // a restart reuses the existing overlay, so ready-to-show will not fire again
    if (hadOverlay) this._flushResumeNotice();
    this.onEvent('playback-started', { path: files[startIndex] });
    this._verifyResume(resume);
  }

  /* Belt and braces: if --start was ignored for any reason, land on the stored
   * position anyway once the file is actually rolling. */
  _verifyResume(resume) {
    if (!resume) return;
    setTimeout(async () => {
      if (!this.proc || !this.sock) return;
      try {
        const at = await this.command(['get_property', 'time-pos']);
        if (typeof at === 'number' && at < Math.min(5, resume.pos - 5)) {
          await this.command(['seek', resume.pos, 'absolute+exact']);
        }
      } catch (_) {}
    }, 1800);
  }

  /* The playing entry loads first (instant start), the rest append in order,
   * then the playing entry is moved back to its natural position so the
   * playlist reads E01, E02, … with the current one in the right place. */
  async _appendRest(files, startIndex) {
    for (let i = 0; i < files.length; i++) {
      if (i !== startIndex) {
        try { await this.command(['loadfile', files[i], 'append']); } catch (_) {}
      }
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
        this._raises = 0;
        // Checking costs a window-manager read; only a real displacement makes
        // us touch mpv's window at all (see win32.ensureOnTop).
        this._raiseTimer = setInterval(() => {
          if (!this.proc || !this.childHwnd) return;
          if (win32.ensureOnTop(this.childHwnd)) this._raises++;
        }, 3000);
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
    if (!this.childHwnd || !this.proc || this.win.isDestroyed()) return;
    const [w, h] = this.win.getContentSize();
    const factor = require('electron').screen.getDisplayMatching(this.win.getBounds()).scaleFactor || 1;
    win32.fitChild(this.childHwnd, 0, 0, Math.round(w * factor), Math.round(h * factor));
  }

  _createOverlay() {
    if (this.overlay && !this.overlay.isDestroyed()) return;
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
      this._flushResumeNotice();
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
      if (this.proc && !this._restarting) this.stop();
    });
  }

  _flushResumeNotice() {
    if (!this._resumeNotice) return;
    const n = this._resumeNotice;
    this._resumeNotice = null;
    setTimeout(() => this._sendOverlay('resumed', n), 500);
  }

  _syncOverlayBounds() {
    if (!this.overlay || this.overlay.isDestroyed() || this.win.isDestroyed()) return;
    try { this.overlay.setBounds(this.win.getContentBounds()); } catch (_) {}
  }

  _sendOverlay(ch, payload) {
    if (this.overlay && !this.overlay.isDestroyed()) {
      this.overlay.webContents.send(ch, payload);
    }
  }

  sendOverlay(ch, payload) { this._sendOverlay(ch, payload); }

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
    if (!this.proc || this._reconnecting || this._stopping || this._restarting) return;
    if (this.sock) { try { this.sock.destroy(); } catch (_) {} }
    this.sock = null;
    // The pipe also closes when mpv is quitting normally, which is not a fault.
    // Give the exit handler a moment to settle before treating this as a drop.
    setTimeout(() => {
      if (!this.proc || this._reconnecting || this._stopping || this._restarting) return;
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

    let wedged = 0;
    for (let attempt = 1; attempt <= 6 && this.proc && !this._restarting; attempt++) {
      // A pending window call means mpv's message loop is not running. Nothing
      // is coming back over the pipe either; go straight to a restart.
      if (win32.isStuck()) break;
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
        // The pipe accepts us but the engine never answers: it is wedged, not
        // flaky. Retrying that another four times just prolongs a dead screen.
        if (++wedged >= 2) break;
      }
    }

    // The pipe is there but the engine is not answering — it is wedged. Restart
    // it on the same video at the same spot instead of dumping the user out.
    this._reconnecting = false;
    console.error('[mpv] control channel unrecoverable — restarting engine');
    this._recover('the playback engine stopped responding');
  }

  /* Poll the engine. Two things can go wrong and both used to look identical
   * from the outside — a frozen picture and dead controls:
   *   1. the IPC channel stops answering (engine wedged)
   *   2. it answers, but the playback clock has stopped advancing (decode stuck)
   */
  _startWatchdog() {
    clearInterval(this._watchdog);
    this._stallSince = 0;
    this._stallPos = null;
    this._goodBeats = 0;
    this._watchdog = setInterval(() => this._healthCheck(), 4000);
  }

  async _healthCheck() {
    if (!this.proc || this._reconnecting || this._stopping || this._restarting) return;
    if (!this.sock) return this._onSocketLost('no socket');

    let pos;
    try {
      pos = await this.command(['get_property', 'time-pos'], 3000);
      this._missedBeats = 0;
    } catch (_) {
      this._missedBeats = (this._missedBeats || 0) + 1;
      console.warn('[mpv] heartbeat missed', this._missedBeats);
      // One missed beat plus a window that will not answer WM_NULL is already
      // conclusive: the engine is wedged, and no amount of reconnecting to its
      // pipe will help. Going straight to a restart turns a ~15 second dead
      // screen into about four.
      const probe = await win32.probeAlive(this.childHwnd, 700);
      if (this.childHwnd && probe && !probe.alive) {
        this._diagnose('window-hung', { probeMs: probe.ms, missed: this._missedBeats });
        this._missedBeats = 0;
        return this._recover('the video engine stopped responding');
      }
      if (this._missedBeats >= 2) {
        this._diagnose('ipc-unresponsive', { probeMs: probe && probe.ms });
        this._missedBeats = 0;
        this._onSocketLost('unresponsive');
      }
      return;
    }

    const p = this.props;
    const running = p.pause !== true && p['eof-reached'] !== true &&
                    p['paused-for-cache'] !== true && typeof pos === 'number';
    if (!running) { this._stallSince = 0; this._stallPos = pos; return; }

    // The clock must move; at any speed 4 seconds of wall time is plenty.
    if (this._stallPos != null && Math.abs(pos - this._stallPos) < 0.05) {
      this._goodBeats = 0;
      if (!this._stallSince) this._stallSince = Date.now();
      else if (Date.now() - this._stallSince > 8000) {
        this._stallSince = 0;
        this._diagnose('clock-stalled', { at: pos });
        this._recover('playback froze');
      }
    } else {
      this._stallSince = 0;
      // a minute of healthy playback clears the restart budget
      if (++this._goodBeats > 15) { this._goodBeats = 0; this._recoveries = 0; }
    }
    this._stallPos = pos;
  }

  /* A freeze should never be a mystery twice. Whenever the watchdog trips, note
   * enough to tell the failure modes apart afterwards: a wedged window thread
   * (nothing answers WM_NULL) is a different bug from a wedged decoder (window
   * fine, playback clock frozen) or a dropped pipe. */
  _diagnose(kind, extra = {}) {
    try {
      const p = this.props;
      const line = JSON.stringify({
        t: new Date().toISOString(), kind, ...extra,
        pid: this.proc && this.proc.pid,
        raises: this._raises || 0,
        winCallOutstanding: win32.isStuck(),
        pos: p['time-pos'], dur: p.duration, speed: p.speed,
        paused: p.pause, cacheStall: p['paused-for-cache'], cache: p['demuxer-cache-time'],
        file: this._path && path.basename(this._path),
        err: (this._lastErr || '').slice(-400)
      });
      const f = path.join(app.getPath('userData'), 'nova-diagnostics.log');
      try { if (fs.statSync(f).size > 65536) fs.renameSync(f, f + '.1'); } catch (_) {}
      fs.appendFileSync(f, line + '\n');
      console.warn('[mpv] ' + line);
    } catch (_) { /* diagnostics must never break playback */ }
  }

  /* Restart the engine in place: same queue, same position, same overlay. The
   * user sees a short black flash and a note, not a dead window. */
  async _recover(reason) {
    if (this._restarting || this._stopping || !this.proc) return;
    if (this._recoveries >= 3) {
      console.error('[mpv] repeated failures — returning to the library');
      this._sendOverlay('mpv-link', { state: 'lost' });
      return this.stopNow();
    }
    this._diagnose('restart', { reason, attempt: this._recoveries + 1 });
    this._recoveries++;
    this._restarting = true;
    this._sendOverlay('mpv-link', { state: 'recovering', reason });

    this._saveProgress(false);
    const files = this._files.length ? this._files.slice() : (this._path ? [this._path] : []);
    let idx = files.findIndex(f => samePath(f, this._path));
    if (idx < 0) idx = Math.min(this._queueIndex || 0, files.length - 1);

    await this._hardStop();
    this._restarting = false;
    if (!files.length) return this.onEvent('playback-ended', { path: this._path });

    try {
      await this.play(files, Math.max(0, idx), { recovered: true });
      this._sendOverlay('mpv-link', { state: 'recovered', reason });
    } catch (e) {
      this.onEvent('play-error', { message: String(e.message || e) });
    }
  }

  async _init() {
    for (let i = 0; i < OBSERVED.length; i++) {
      await this.command(['observe_property', i + 1, OBSERVED[i]], 2500);
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
        const { resolve, reject, timer } = this.pending.get(msg.request_id);
        this.pending.delete(msg.request_id);
        clearTimeout(timer);
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
    const d = this.store.load();
    if (d.settings.rememberPosition === false) return null;
    const it = d.items[file];
    if (!it || !it.progress || it.progress < 10) return null;              // barely started
    if (it.duration && it.progress > it.duration - 15) return null;        // effectively finished
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

    if (name in STICKY) this._recordPref(name, data);

    if (name === 'time-pos' && typeof data === 'number') {
      this._timePos = data;
      this._trackProgress();
      const now = Date.now();
      if (now - this._lastTimePosRelay < 150) return;   // throttle relay only
      this._lastTimePosRelay = now;
    } else if (name === 'duration' && typeof data === 'number') {
      this._duration = data;
    } else if (name === 'path' && data) {
      const changed = !samePath(this._path, data);
      this._path = data;
      this._markHistory(data);
      if (changed) {
        this._stallSince = 0;
        this._stallPos = null;
      }
    } else if (name === 'fullscreen' && typeof data === 'boolean') {
      if (!this.win.isDestroyed() && this.win.isFullScreen() !== data) this.win.setFullScreen(data);
    } else if (name === 'eof-reached' && data === true) {
      this._saveProgress(true);
    }

    this._sendOverlay('mpv-prop', { name, data });
  }

  /* Seek to our stored position once the file is actually open. Used when a new
   * file is loaded into a running engine (the first file of a session is placed
   * with --start instead). */
  async _applyPendingResume() {
    const r = this._pendingResume;
    this._pendingResume = null;
    if (!r) return;
    // file-loaded arrives before the `path` property change, so this.path is
    // still the *previous* file here — ask the engine directly.
    let target = this._path;
    if (!samePath(r.file, target)) target = await this.getProp('path').catch(() => null);
    if (!samePath(r.file, target)) return;
    try {
      const at = await this.command(['get_property', 'time-pos']);
      if (typeof at === 'number' && Math.abs(at - r.pos) < 5) return;  // already there
      await this.command(['seek', r.pos, 'absolute+exact']);
      this._resumeNotice = { pos: r.pos };
      this._flushResumeNotice();
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
    if (!entry.title) entry.title = path.basename(this._path, path.extname(this._path));
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

  command(cmd, timeoutMs = 5000) {
    return new Promise((resolve, reject) => {
      if (!this.sock) return reject(new Error('mpv not running'));
      const id = this.reqId++;
      const timer = setTimeout(() => {
        if (this.pending.has(id)) { this.pending.delete(id); reject(new Error('mpv command timeout')); }
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      try {
        this.sock.write(JSON.stringify({ command: cmd, request_id: id }) + '\n');
      } catch (err) {
        this.pending.delete(id);
        clearTimeout(timer);
        reject(err);
      }
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
    return this.command(['get_property', name], 3000);
  }

  keypress(name) {
    if (!this.proc) return;
    this.command(['keypress', name]).catch(() => {});
  }

  /* Graceful stop used when the window closes. */
  async stop() {
    if (!this.proc) return;
    this._stopping = true;
    this._saveProgress(false);
    await this._hardStop();
  }

  /* Leave playback right now, whatever state the engine is in. This is the path
   * behind Esc and the back button, so it must never depend on mpv answering. */
  async stopNow() {
    if (!this.proc) return;
    this._stopping = true;
    this._saveProgress(false);
    await this._hardStop();
  }

  /* Ask nicely, then insist. Resolves once the process is really gone. */
  _hardStop() {
    const p = this.proc;
    if (!p) return Promise.resolve();
    return new Promise(resolve => {
      let settled = false;
      const finish = () => { if (!settled) { settled = true; clearTimeout(t1); clearTimeout(t2); resolve(); } };
      p.once('exit', finish);
      p.once('error', finish);
      if (this.sock) { this.command(['quit']).catch(() => {}); }
      const t1 = setTimeout(() => { try { p.kill(); } catch (_) {} }, 700);
      const t2 = setTimeout(() => { try { p.kill('SIGKILL'); } catch (_) {} finish(); }, 2500);
      if (p.exitCode !== null || p.signalCode !== null) finish();
    });
  }

  _onExit() {
    if (!this.proc) return;
    clearInterval(this._raiseTimer);
    this._raiseTimer = null;
    clearInterval(this._watchdog);
    this._watchdog = null;
    this._reconnecting = false;
    this._pendingResume = null;
    this._saveProgress(false);
    this.proc = null;
    try { this.sock?.destroy(); } catch (_) {}
    this.sock = null;
    this.childHwnd = null;
    for (const { reject, timer } of this.pending.values()) {
      clearTimeout(timer);
      try { reject(new Error('mpv exited')); } catch (_) {}
    }
    this.pending.clear();
    this.props = {};

    // A restart keeps the overlay and the session alive; a real stop tears down.
    if (this._restarting) return;

    clearInterval(this._focusTimer);
    this._focusTimer = null;
    this._stopping = false;
    if (this.overlay && !this.overlay.isDestroyed()) {
      const o = this.overlay;
      this.overlay = null;
      o.destroy();
    }
    if (!this.win.isDestroyed() && this.win.isFullScreen()) this.win.setFullScreen(false);
    this.onEvent('playback-ended', { path: this._path });
    this._path = null; this._timePos = null; this._duration = null;
    this._files = [];
  }
}

module.exports = { MpvController, resPath, STICKY };
