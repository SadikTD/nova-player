/*
 * Silent updates.
 *
 * Design rule: the user must never be interrupted. Nothing here shows a dialog,
 * a prompt, or a "restart now?" box. Updates download quietly in the background
 * and are applied when the app is next closed, so the app is simply newer the
 * next time it opens. State is exposed only as a passive line in Settings.
 */
const { app } = require('electron');

let updater = null;             // electron-updater's autoUpdater
let notify = () => {};
let state = { status: 'idle', version: null, error: null };

const SIX_HOURS = 6 * 60 * 60 * 1000;

function setState(patch) {
  state = { ...state, ...patch };
  try { notify('update-state', getState()); } catch (_) {}
}

function getState() {
  return { ...state, currentVersion: app.getVersion() };
}

function init(sendToRenderer) {
  notify = sendToRenderer || (() => {});

  // Updates only make sense for an installed build. A portable/unpacked copy has
  // nothing to replace, and dev runs must never try.
  if (!app.isPackaged) {
    setState({ status: 'disabled', reason: 'development build' });
    return;
  }

  try {
    ({ autoUpdater: updater } = require('electron-updater'));
  } catch (err) {
    setState({ status: 'disabled', reason: 'updater unavailable' });
    return;
  }

  updater.autoDownload = true;          // fetch quietly as soon as one is found
  updater.autoInstallOnAppQuit = true;  // apply on exit — never mid-session
  updater.allowPrerelease = false;
  updater.fullChangelog = false;
  updater.logger = null;

  updater.on('checking-for-update', () => setState({ status: 'checking', error: null }));
  updater.on('update-not-available', () => setState({ status: 'current', version: null }));
  updater.on('update-available', info => setState({ status: 'downloading', version: info?.version || null }));
  updater.on('download-progress', p => setState({ status: 'downloading', percent: Math.round(p?.percent || 0) }));
  updater.on('update-downloaded', info => setState({ status: 'ready', version: info?.version || null, percent: 100 }));
  updater.on('error', err => {
    // Offline, rate-limited, no release yet — all normal. Stay quiet.
    setState({ status: 'error', error: String((err && err.message) || err).slice(0, 200) });
  });

  check();
  setInterval(check, SIX_HOURS);
}

function check() {
  if (!updater) return;
  updater.checkForUpdates().catch(() => { /* handled by the error event */ });
}

module.exports = { init, check, getState };
