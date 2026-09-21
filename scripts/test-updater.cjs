const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../src/main/updater.js'), 'utf8');
function fixture({ portable = false, previous = null, failPrepare = false } = {}) {
  const backend = new EventEmitter();
  const events = [];
  const files = new Map();
  if (previous) files.set('update-attempt.json', JSON.stringify(previous));
  let checks = 0;
  backend.checkForUpdates = async () => { checks++; backend.emit('checking-for-update'); };
  backend.quitAndInstall = (...args) => events.push(['install', ...args]);
  const module = { exports: {} };
  const fakeFs = {
    existsSync: file => file.endsWith('Uninstall Nova Player.exe') ? !portable : files.has(path.basename(file)),
    readFileSync: file => { if (!files.has(path.basename(file))) throw Error('missing'); return files.get(path.basename(file)); },
    writeFileSync: (file, text) => { events.push(['marker']); files.set(path.basename(file), text); },
    appendFileSync() {}, statSync: () => ({ size: 0 }), unlinkSync: file => files.delete(path.basename(file))
  };
  vm.runInNewContext(source, {
    module, console, setInterval: () => ({ unref() {} }),
    require(name) {
      if (name === 'electron') return { app: { isPackaged: true, getVersion: () => '1.3.1', getPath: name => name === 'exe' ? path.resolve('Installed/Nova Player.exe') : path.resolve('UserData') } };
      if (name === 'electron-updater') return { autoUpdater: backend };
      if (name === 'fs') return fakeFs;
      return require(name);
    }
  });
  const api = module.exports;
  api.init(() => {}, async () => { events.push(['saved-playback']); if (failPrepare) throw Error('Save failed'); });
  return { api, backend, events, files, checks: () => checks };
}
(async () => {
  const f = fixture();
  await Promise.resolve();
  assert.equal(f.backend.autoInstallOnAppQuit, false, 'Nova must own shutdown ordering');
  assert.equal(f.backend.installDirectory, path.resolve('Installed'), 'installer replaces the running installation');
  f.backend.emit('update-available', { version: '1.3.2' });
  f.backend.emit('download-progress', { percent: 42 });
  assert.equal(f.api.getState().percent, 42);
  f.backend.emit('update-downloaded', { version: '1.3.2' });
  await f.api.check();
  assert.equal(f.api.getState().status, 'ready', 'checking must not erase a downloaded update');
  assert.equal(f.checks(), 1, 'ready update is not downloaded again');
  await f.api.install(true);
  assert.deepEqual(f.events.map(e => e[0]), ['saved-playback', 'marker', 'install']);
  assert.deepEqual(f.events.at(-1), ['install', false, true], 'explicit action uses a visible installer and relaunch');
  await f.api.install(true);
  assert.equal(f.events.filter(e => e[0] === 'install').length, 1, 'double click cannot launch two installers');
  const close = fixture(); close.backend.emit('update-downloaded', { version: '1.3.2' });
  await close.api.install(false);
  assert.deepEqual(close.events.at(-1), ['install', true, false], 'close installs silently without reopening');
  const portable = fixture({ portable: true });
  assert.equal(portable.backend.autoDownload, false);
  portable.backend.emit('update-available', { version: '1.3.2' });
  assert.equal(portable.api.getState().status, 'available');
  assert.equal(portable.api.canInstall(), false);
  const failed = fixture({ previous: { from: '1.3.1', to: '1.3.2' } });
  assert.equal(failed.api.getState().installFailed, true, 'unsuccessful installation is reported next launch');
  failed.backend.emit('error', Error('Installer blocked'));
  assert.equal(failed.api.getState().error, 'Installer blocked');
  const broken = fixture({ failPrepare: true });
  broken.backend.emit('update-downloaded', { version: '1.3.2' });
  await broken.api.install();
  assert.equal(broken.api.blocksQuit(), false, 'failed preparation must not trap the app open');
  assert.equal(broken.api.getState().status, 'error');
  const success = fixture({ previous: { from: '1.3.0', to: '1.3.1' } });
  assert.equal(success.files.has('update-attempt.json'), false, 'successful update clears attempt marker');
  console.log('PASS: update state, install target, graceful shutdown ordering, visible restart, silent close, duplicate actions, portable builds, failure recovery');
})().catch(e => { console.error(e); process.exitCode = 1; });
