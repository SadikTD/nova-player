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
  settings: {
    hwdec: true,
    volumeMax: 200,
    subScale: 1.0,
    subBorder: 2.0,
    subPos: 90,             // 100 = bottom edge; lower = higher on screen
    rememberPosition: true,
    defaultSpeed: 1.0,
    seekStep: 5,            // seconds per arrow-key / skip-button press
    audioLang: '',
    subLang: ''
  }
};

let data = null;
let saveTimer = null;

function load() {
  if (data) return data;
  try {
    // tolerate a UTF-8 BOM — external tools sometimes add one
    const raw = fs.readFileSync(FILE(), 'utf8').replace(/^﻿/, '');
    data = { ...DEFAULTS, ...JSON.parse(raw) };
    data.settings = { ...DEFAULTS.settings, ...(data.settings || {}) };
  } catch (err) {
    // never silently wipe a store that exists but failed to parse — keep a backup
    try {
      if (fs.existsSync(FILE())) fs.copyFileSync(FILE(), FILE() + '.corrupt-' + Date.now());
    } catch (_) {}
    data = JSON.parse(JSON.stringify(DEFAULTS));
  }
  return data;
}

function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try { fs.writeFileSync(FILE(), JSON.stringify(data)); } catch (e) { console.error('store save failed', e); }
  }, 400);
}

function saveNow() {
  clearTimeout(saveTimer);
  try { fs.writeFileSync(FILE(), JSON.stringify(data)); } catch (e) { console.error('store save failed', e); }
}

module.exports = { load, save, saveNow };
