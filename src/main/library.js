/*
 * Library — scans configured folders for videos, extracts duration/resolution
 * and a thumbnail per file using the bundled mpv in headless mode.
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');
const { app } = require('electron');

const VIDEO_EXT = new Set([
  '.mkv', '.mp4', '.m4v', '.avi', '.mov', '.wmv', '.flv', '.webm', '.ts',
  '.m2ts', '.mts', '.3gp', '.mpg', '.mpeg', '.vob', '.ogv', '.rmvb', '.divx'
]);

class Library {
  constructor(storeMod, mpvExePath, notify) {
    this.store = storeMod;
    this.mpvExe = mpvExePath;
    this.notify = notify;           // (channel, payload) => renderer
    this.queue = [];
    this.running = 0;
    this.thumbDir = path.join(app.getPath('userData'), 'thumbs');
    fs.mkdirSync(this.thumbDir, { recursive: true });
  }

  isVideo(p) { return VIDEO_EXT.has(path.extname(p).toLowerCase()); }

  scanAll() {
    const data = this.store.load();
    const found = new Set();
    for (const folder of data.folders) this._walk(folder, found, 0);
    // register new files
    let changed = false;
    for (const p of found) {
      if (!data.items[p]) {
        let st;
        try { st = fs.statSync(p); } catch (_) { continue; }
        data.items[p] = {
          title: path.basename(p, path.extname(p)),
          size: st.size, mtime: st.mtimeMs, addedAt: Date.now(),
          progress: 0
        };
        changed = true;
      }
    }
    // drop items whose files vanished (keep network history untouched)
    for (const p of Object.keys(data.items)) {
      if (/^[a-z]+:\/\//i.test(p)) continue;
      const insideLibrary = data.folders.some(f => p.toLowerCase().startsWith(f.toLowerCase()));
      if (insideLibrary && !found.has(p) && !fs.existsSync(p)) { delete data.items[p]; changed = true; }
    }
    if (changed) this.store.save();
    this.notify('library-updated', null);
    // queue metadata/thumb extraction for items missing them
    for (const [p, it] of Object.entries(data.items)) {
      if (/^[a-z]+:\/\//i.test(p)) continue;
      if (!it.thumb || !it.duration) this.enqueue(p);
    }
    this._pump();
  }

  _walk(dir, found, depth) {
    if (depth > 6) return;
    let entries;
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (_) { return; }
    for (const e of entries) {
      if (e.name.startsWith('.')) continue;
      const full = path.join(dir, e.name);
      if (e.isDirectory()) this._walk(full, found, depth + 1);
      else if (this.isVideo(full)) found.add(full);
    }
  }

  enqueue(p) {
    if (!this.queue.includes(p)) this.queue.push(p);
  }

  _pump() {
    while (this.running < 2 && this.queue.length) {
      const p = this.queue.shift();
      this.running++;
      this._extract(p).catch(() => {}).finally(() => {
        this.running--;
        this._pump();
      });
    }
  }

  _extract(file) {
    return new Promise((resolve) => {
      const data = this.store.load();
      const it = data.items[file];
      if (!it || !fs.existsSync(file)) return resolve();
      const hash = crypto.createHash('sha1').update(file).digest('hex').slice(0, 20);
      const outDir = path.join(this.thumbDir, '_' + hash);
      fs.mkdirSync(outDir, { recursive: true });

      const args = [
        '--no-config', '--load-scripts=no', '--no-audio', '--no-sub',
        '--frames=1', '--start=20%', '--vo=image',
        '--vo-image-format=jpg', '--vo-image-jpeg-quality=80',
        `--vo-image-outdir=${outDir}`,
        '--msg-level=all=error,term-msg=info',
        `--term-playing-msg=NOVA_META:\${=duration}|\${width}|\${height}`,
        '--', file
      ];
      const proc = spawn(this.mpvExe, args, { windowsHide: true });
      let out = '';
      proc.stdout.on('data', d => { out += d; });
      proc.stderr.on('data', () => {});
      const timer = setTimeout(() => { try { proc.kill(); } catch (_) {} }, 30000);
      proc.on('exit', () => {
        clearTimeout(timer);
        try {
          const m = out.match(/NOVA_META:([\d.]+)\|(\d+)?\|(\d+)?/);
          if (m) {
            it.duration = parseFloat(m[1]) || it.duration;
            it.width = parseInt(m[2]) || it.width;
            it.height = parseInt(m[3]) || it.height;
          }
          const files = fs.existsSync(outDir) ? fs.readdirSync(outDir).filter(f => f.endsWith('.jpg')) : [];
          if (files.length) {
            const dest = path.join(this.thumbDir, hash + '.jpg');
            fs.copyFileSync(path.join(outDir, files[0]), dest);
            it.thumb = dest;
          }
          fs.rmSync(outDir, { recursive: true, force: true });
          this.store.save();
          this.notify('item-updated', { path: file, item: it });
        } catch (e) { /* ignore per-file failures */ }
        resolve();
      });
    });
  }
}

module.exports = { Library, VIDEO_EXT };
