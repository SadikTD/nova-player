/* Simple persistent JSON store (library, progress, playlists, settings). */
const fs = require('fs');
const path = require('path');
const { app } = require('electron');

const FILE = () => path.join(app.getPath('userData'), 'nova-store.json');

const DEFAULTS = {
  folders: [],            // library root folders
  items: {},              // path -> { title, size, mtime, duration, width, height, thumb, progress, lastPlayed, addedAt }
  playlists: [],          // { id, name, items: [path] }
  history: [],            // [{ path, at }] most recent first, capped
  prefs: {},              // last-used player state (mpv property name -> value)
  settings: {
    hwdec: true,
    volumeMax: 200,
    subScale: 1.0,
    subBorder: 2.0,
    subPos: 90,             // 100 = bottom edge; lower = higher on screen
    rememberPosition: true,
    rememberPlayerState: true,  // carry speed/volume/picture tweaks between sessions
    defaultSpeed: 1.0,
    seekStep: 5,            // seconds per arrow-key / skip-button press
    audioLang: '',
    subLang: '',
    accent: 'blue',         // UI accent colour
    sortBy: 'name',         // library "All videos" ordering
    nextUpCard: true        // "Next up" card near the end of an episode
  }
};

let data = null;
let saveTimer = null;

function load() {
  if (data) return data;
  for (const candidate of [FILE(), FILE() + '.bak']) {
    try {
      // tolerate a UTF-8 BOM — external tools sometimes add one
      const raw = fs.readFileSync(candidate, 'utf8').replace(/^﻿/, '');
      const parsed = JSON.parse(raw);
      data = { ...DEFAULTS, ...parsed };
      data.settings = { ...DEFAULTS.settings, ...(data.settings || {}) };
      data.prefs = data.prefs && typeof data.prefs === 'object' ? data.prefs : {};
      return data;
    } catch (_) { /* try the backup, then fall through to a fresh store */ }
  }
  // never silently wipe a store that exists but failed to parse — keep a copy
  try {
    if (fs.existsSync(FILE())) fs.copyFileSync(FILE(), FILE() + '.corrupt-' + Date.now());
  } catch (_) {}
  data = JSON.parse(JSON.stringify(DEFAULTS));
  return data;
}

/* Write through a temp file and rename into place. A plain writeFileSync that is
 * interrupted (app killed, power loss) leaves a truncated file behind, and the
 * whole library — every resume point included — is gone on the next launch. */
function writeAtomic() {
  if (!data) return;
  const file = FILE();
  const tmp = file + '.tmp';
  try {
    fs.writeFileSync(tmp, JSON.stringify(data));
    try { if (fs.existsSync(file)) fs.copyFileSync(file, file + '.bak'); } catch (_) {}
    fs.renameSync(tmp, file);
  } catch (e) {
    console.error('store save failed', e);
    try { fs.rmSync(tmp, { force: true }); } catch (_) {}
  }
}

function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(writeAtomic, 400);
}

function saveNow() {
  clearTimeout(saveTimer);
  writeAtomic();
}

module.exports = { load, save, saveNow, DEFAULTS };
