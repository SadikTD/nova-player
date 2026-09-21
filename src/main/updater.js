/* Background checks, visible status, and an explicit, recoverable install path. */
const { app } = require('electron');
const fs = require('fs');
const path = require('path');

let updater = null;
let notify = () => {};
let prepare = async () => {};
let state = { status: 'idle', version: null, error: null };
let checking = false;
let installing = false;
let preparing = false;
const markerFile = () => path.join(app.getPath('userData'), 'update-attempt.json');

function log(level, message) {
  try {
    const file = path.join(app.getPath('userData'), 'nova-updates.log');
    if (fs.existsSync(file) && fs.statSync(file).size > 1024 * 1024) fs.renameSync(file, file + '.old');
    fs.appendFileSync(file, `${new Date().toISOString()} [${level}] ${String(message)}\n`);
  } catch (_) {}
}
function setState(patch) {
  state = { ...state, ...patch };
  try { notify('update-state', getState()); } catch (_) {}
}
function getState() { return { ...state, currentVersion: app.getVersion() }; }
function canInstall() { return !!updater && !state.portable && state.status === 'ready' && !installing; }

function init(sendToRenderer, prepareForInstall) {
  if (updater) return;
  notify = sendToRenderer || (() => {});
  prepare = prepareForInstall || (async () => {});
  if (!app.isPackaged) return setState({ status: 'disabled', reason: 'development build' });
  try {
    ({ autoUpdater: updater } = require('electron-updater'));
  } catch (_) { return setState({ status: 'disabled', reason: 'updater unavailable' }); }

  const installDir = path.dirname(app.getPath('exe'));
  const portable = !fs.existsSync(path.join(installDir, 'Uninstall Nova Player.exe'));
  state.portable = portable;
  updater.autoDownload = !portable;
  // Nova shuts down mpv and flushes progress BEFORE launching the installer.
  updater.autoInstallOnAppQuit = false;
  updater.installDirectory = installDir;
  updater.allowPrerelease = false;
  updater.fullChangelog = false;
  updater.logger = Object.fromEntries(['info', 'warn', 'error', 'debug'].map(level => [level, message => log(level, message)]));

  try {
    const previous = JSON.parse(fs.readFileSync(markerFile(), 'utf8'));
    if (previous.from === app.getVersion()) {
      state.installFailed = true;
      log('warn', `Previous installation of ${previous.to} did not replace version ${previous.from}`);
    } else {
      log('info', `Updated successfully to ${app.getVersion()}`);
      fs.unlinkSync(markerFile());
    }
  } catch (_) {}

  updater.on('checking-for-update', () => setState({ status: 'checking', error: null }));
  updater.on('update-not-available', () => setState({ status: 'current', version: null, percent: null, installFailed: false }));
  updater.on('update-available', info => setState({ status: portable ? 'available' : 'downloading', version: info?.version || null, percent: 0, error: null }));
  updater.on('download-progress', p => setState({ status: 'downloading', percent: Math.round(p?.percent || 0) }));
  updater.on('update-downloaded', info => setState({ status: 'ready', version: info?.version || null, percent: 100, error: null }));
  updater.on('error', err => {
    installing = false;
    preparing = false;
    log('error', err?.stack || err);
    setState({ status: 'error', error: String(err?.message || err).slice(0, 200) });
  });
  check();
  setInterval(check, 6 * 60 * 60 * 1000).unref();
}

async function check() {
  if (!updater || checking || installing || ['ready', 'downloading'].includes(state.status)) return getState();
  checking = true;
  try { await updater.checkForUpdates(); }
  catch (err) { log('error', err?.message || err); setState({ status: 'error', error: String(err?.message || err).slice(0, 200) }); }
  finally { checking = false; }
  return getState();
}

async function install(restart = true) {
  if (!canInstall()) return { error: 'The update is not ready to install yet.' };
  installing = true;
  preparing = true;
  setState({ status: 'installing', error: null });
  try {
    await prepare();
    fs.writeFileSync(markerFile(), JSON.stringify({ from: app.getVersion(), to: state.version, at: new Date().toISOString() }));
    log('info', `Installing ${state.version} into ${updater.installDirectory}; restart=${restart}`);
    // Explicit action opens the installer so permission/failure messages are visible.
    // Closing the app keeps the normal silent-install behavior.
    preparing = false;
    updater.quitAndInstall(!restart, restart);
    return getState();
  } catch (err) {
    installing = false;
    preparing = false;
    log('error', err?.stack || err);
    setState({ status: 'error', error: String(err?.message || err).slice(0, 200) });
    return { error: state.error };
  }
}
module.exports = { init, check, getState, canInstall, install, blocksQuit: () => preparing };
