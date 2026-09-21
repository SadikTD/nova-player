const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { spawn } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const tmp = path.join(root, 'dist', 'resume-test');
fs.mkdirSync(tmp, { recursive: true });
const data = { settings: { rememberPosition: true, rememberPlayerState: false }, items: {}, history: [], prefs: {} };
const store = { load: () => data, save() {} };
const moduleMock = { exports: {} };
const sandbox = {
  module: moduleMock, exports: moduleMock.exports, console, process, Buffer,
  setTimeout, clearTimeout, setInterval, clearInterval,
  require(name) {
    if (name === 'electron') return { app: { isPackaged: false, getPath: () => tmp, getAppPath: () => root }, BrowserWindow: {} };
    if (name === './win32') return { hwndFromBuffer: () => 0 };
    if (name === './subtitles') return { fallbackDir: () => tmp };
    if (name === 'child_process') return { spawn(exe, args) {
      assert(!args.some(a => a.startsWith('--start=')), 'resume must never be a persistent startup option');
      return spawn(exe, ['--no-config', '--vo=null', '--ao=null', '--pause=yes', ...args.filter(a => !a.startsWith('--wid=') && !a.startsWith('--config-dir=') && !a.startsWith('--force-window='))], { windowsHide: true });
    } };
    return require(name);
  }
};
vm.runInNewContext(fs.readFileSync(path.join(root, 'src/main/mpv.js'), 'utf8'), sandbox);
const { MpvController } = moduleMock.exports;
const win = { getNativeWindowHandle: () => Buffer.alloc(8), isDestroyed: () => true };
const controller = new MpvController(win, store, () => {});
for (const method of ['_hookWindow', '_createOverlay', '_startWatchdog', '_scheduleSubtitleCheck', '_flushResumeNotice']) controller[method] = () => {};

// Silent WAVs allow a deterministic 24:25 episode without using personal media.
function episode(name) {
  const file = path.join(tmp, name + '.wav');
  const bytes = 1465 * 8000 * 2;
  const wave = Buffer.alloc(44 + bytes);
  wave.write('RIFF'); wave.writeUInt32LE(36 + bytes, 4); wave.write('WAVEfmt ', 8);
  wave.writeUInt32LE(16, 16); wave.writeUInt16LE(1, 20); wave.writeUInt16LE(1, 22);
  wave.writeUInt32LE(8000, 24); wave.writeUInt32LE(16000, 28); wave.writeUInt16LE(2, 32); wave.writeUInt16LE(16, 34);
  wave.write('data', 36); wave.writeUInt32LE(bytes, 40); fs.writeFileSync(file, wave); return file;
}
const files = [episode('episode-5'), episode('episode-6'), episode('episode-7')];
const delay = ms => new Promise(r => setTimeout(r, ms));
async function until(check) {
  for (let n = 0; n < 80; n++) { try { if (await check()) return; } catch (_) {} await delay(50); }
  throw Error('Timed out waiting for playback');
}
(async () => {
  data.items[files[0]] = { progress: 1215, duration: 1465 };
  await controller.play(files, 0);
  await until(async () => (await controller.getProp('time-pos')) > 1214).catch(e => { console.error({pending:controller._pendingResume, loading:controller._loadingFile, props:controller.props,err:controller._lastErr});throw e; });
  await controller._captureProgress();
  assert(Math.abs(data.items[files[0]].progress - 1215) < 1, 'episode 5 resumes at 20:15');
  await controller.exec(['playlist-next']);
  await until(async () => (await controller.getProp('path')) === files[1] && !controller._loadingFile);
  await controller._captureProgress();
  assert((await controller.getProp('time-pos')) < 1, 'episode 6 starts at zero');
  assert(data.items[files[1]].progress < 1, 'episode 6 must not inherit watched progress');
  assert(data.items[files[0]].progress > 1214, 'episode 5 keeps its own progress');
  await controller.exec(['seek', 600, 'absolute+exact']);
  await until(async () => (await controller.getProp('time-pos')) > 599);
  await controller._captureProgress();
  await controller.exec(['seek', 0, 'absolute+exact']);
  await until(async () => (await controller.getProp('time-pos')) < 1);
  await controller._captureProgress();
  assert(data.items[files[1]].progress < 1, 'Start over clears the previous resume point');
  await controller.exec(['playlist-next']);
  await until(async () => (await controller.getProp('path')) === files[2] && !controller._loadingFile);
  assert((await controller.getProp('time-pos')) < 1, 'another Next also starts at zero');
  data.items[files[1]].progress = 300;
  await controller.play(files, 1);
  await until(async () => Math.abs((await controller.getProp('time-pos')) - 300) < 1);
  await controller._captureProgress();
  assert(Math.abs(data.items[files[1]].progress - 300) < 1, 'explicitly opened episode uses only its own resume');
  await controller.stopNow();
  data.items[files[0]].progress = 1215;
  await controller.play(files, 0);
  await until(async () => (await controller.getProp('time-pos')) > 1214).catch(e => { console.error({pending:controller._pendingResume, applying:!!controller._resumeApplying,loading:controller._loadingFile, props:controller.props,err:controller._lastErr});throw e; });
  await controller._captureProgress();
  assert(data.items[files[0]].progress > 1214, 'a fresh engine after closing playback resumes correctly');
  const race = new MpvController(win, store, () => {});
  race.sock = {};
  race._progressSnapshot = { file: files[0], pos: 1215, duration: 1465 };
  race._path = files[2];
  race._saveProgress(false);
  assert.equal(data.items[files[0]].progress, 1215, 'snapshot filename owns its progress, not the mutable property cache');
  const replies = [];
  race.command = () => new Promise(resolve => replies.push(resolve));
  const reading = race._captureProgress();
  race._onMpvEvent({ event: 'start-file' });
  const before = JSON.stringify(data.items);
  replies[0](files[2]); replies[1](1215); replies[2](1465);
  await reading;
  assert.equal(JSON.stringify(data.items), before, 'late replies from a previous generation never write history');
  race._loadingFile = false;
  race._pendingResume = { file: files[0], pos: 1215 };
  race.getProp = async name => name === 'path' ? files[2] : 0;
  let seeks = 0; race.command = async () => { seeks++; };
  await race._applyPendingResume();
  assert.equal(seeks, 0, 'resume cannot seek another episode');
  assert.equal(race._pendingResume, null, 'stale request cannot revive later');
  data.settings.rememberPosition = false;
  assert.equal(controller._storedResume(files[0]), null, 'remember-position setting is honored');
  console.log('PASS: event-order races, stale resume cancellation, disabled resume');
  console.log('PASS: actual mpv episode 5 at 20:15 → episode 6 at zero; independent history; Start over; repeated Next; explicit resume');
})().catch(e => { console.error(e); process.exitCode = 1; }).finally(async () => {
  await controller.stopNow();
});
