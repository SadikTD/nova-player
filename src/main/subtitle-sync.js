'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');
const timing = require('./subtitle-timing');
const SUPPORTED = new Set(['.srt', '.ass', '.ssa']);
const MODES = new Set(['smart', 'gentle', 'offset']);
const same = (a, b) => !!a && !!b && path.resolve(a).toLowerCase() === path.resolve(b).toLowerCase();
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const readJson = file => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch (_) { return null; } };
function signature(file) {
  const stat = fs.statSync(file);
  if (!stat.isFile()) throw Error('Choose a local video and subtitle file.');
  return `${path.resolve(file).toLowerCase()}|${stat.size}|${stat.mtimeMs}`;
}
function readText(file) {
  if (fs.statSync(file).size > 8 * 1024 * 1024) throw Error('This subtitle is too large to sync (8 MB maximum).');
  const bytes = fs.readFileSync(file);
  if (bytes[0] === 0xff && bytes[1] === 0xfe) return bytes.subarray(2).toString('utf16le');
  const text = bytes.toString('utf8').replace(/^\uFEFF/, '');
  if (text.includes('\uFFFD')) throw Error('Save this subtitle as UTF-8 before syncing it.');
  return text;
}

class SubtitleSync {
  constructor({ engine, settings, cacheDir, toolsDir, notify }) {
    Object.assign(this, { engine, settings, cacheDir, toolsDir, notify });
    this.state = { status: 'idle', message: 'Sync subtitles to the dialogue, even when timing changes midway.' };
    this.job = null;
    this.result = null;
    this.appliedResult = null;
    this.generation = 0;
    this.attempted = new Set();
    fs.mkdirSync(cacheDir, { recursive: true });
  }
  getState() { return { ...this.state, busy: !!this.job, engineReady: this.available(), canUndo: !!this.appliedResult?.applied }; }
  emit(patch) { this.state = { ...this.state, ...patch }; this.notify(this.getState()); }
  available() { return ['alass-cli.exe', 'ffmpeg.exe', 'ffprobe.exe'].every(f => fs.existsSync(path.join(this.toolsDir, f))); }
  async context() {
    const engine = this.engine();
    if (!engine?.isActive()) throw Error('Open a video first.');
    const generation = this.generation;
    const [video, tracks, duration, delay, speed] = await Promise.all(['path', 'track-list', 'duration', 'sub-delay', 'sub-speed'].map(p => engine.getProp(p)));
    if (generation !== this.generation) throw Error('The video changed. Try again on this episode.');
    if (!video || /^[a-z]+:\/\//i.test(video)) throw Error('Auto-sync currently supports local video files.');
    const track = tracks?.find(t => t.type === 'sub' && t.selected);
    if (!track?.external || !track['external-filename']) throw Error('Select a downloaded or local SRT, ASS, or SSA subtitle first. Embedded and image subtitles are not supported yet.');
    let source = track['external-filename'];
    const existing = this.metadataFor(source);
    if (existing) source = existing.source;
    if (!SUPPORTED.has(path.extname(source).toLowerCase())) throw Error('Auto-sync supports SRT, ASS, and SSA text subtitles.');
    const audio = tracks.find(t => t.type === 'audio' && t.selected);
    const audioIndex = audio && !audio.external ? (Number.isInteger(audio['ff-index']) ? String(audio['ff-index']) : `a:${tracks.filter(t => t.type === 'audio' && !t.external).indexOf(audio)}`) : null;
    return { video, videoSignature: signature(video), source, sourceHash: hash(readText(source)), selectedFile: track['external-filename'],
      audioIndex, duration, delay: delay || 0, speed: speed || 1, generation };
  }
  metadataFor(file) {
    if (!same(path.dirname(file), this.cacheDir)) return null;
    const stem = path.basename(file, path.extname(file));
    if (!/^[a-f0-9]{64}$/.test(stem)) return null;
    const metadata = readJson(path.join(this.cacheDir, stem + '.json'));
    return metadata && same(metadata.output, file) ? metadata : null;
  }
  validSaved(record) {
    try { return record && same(path.dirname(record.output), this.cacheDir) && record.outputHash === hash(readText(record.output)); } catch (_) { return false; }
  }
  async start({ mode, reference = null, automatic = false, force = false } = {}) {
    if (this.job) return { error: 'A sync is already running. Cancel it before starting another.' };
    try {
      if (!this.available()) throw Error('The local sync tools are missing. Reinstall Nova Player to restore them.');
      const context = await this.context();
      if (this.job) return { error: 'A sync is already running.' };
      mode = MODES.has(mode) ? mode : (MODES.has(this.settings().subSyncMode) ? this.settings().subSyncMode : 'smart');
      if (!reference && !context.audioIndex) throw Error('Select an audio track inside this video, or use a correctly timed reference subtitle.');
      if (reference && !SUPPORTED.has(path.extname(reference).toLowerCase())) throw Error('Choose an SRT, ASS, or SSA reference subtitle.');
      const referenceHash = reference ? hash(readText(reference)) : null;
      const key = hash(JSON.stringify([context.videoSignature, context.source, context.sourceHash, context.audioIndex, mode, referenceHash]));
      const output = path.join(this.cacheDir, key + path.extname(context.source).toLowerCase());
      const metadataFile = path.join(this.cacheDir, key + '.json');
      const previous = readJson(metadataFile);
      if (!force && previous && same(previous.output, output) && this.validSaved(previous)) {
        if (automatic && (this.settings().subSyncReuse === false || !previous.approved)) return this.getState();
        this.result = { ...previous, context, metadataFile, applied: false };
        this.emit({ status: 'review', message: 'Saved correction is ready.', summary: previous.summary, video: context.video });
        if (previous.approved && this.settings().subSyncApply !== false) await this.apply();
        return this.getState();
      }
      if (automatic && previous?.disabled) return this.getState();
      const job = { context, mode, reference, output, metadataFile, cancelled: false, child: null,
        temp: fs.mkdtempSync(path.join(this.cacheDir, 'work-')) };
      this.job = job;
      this.result = null;
      this.emit({ status: 'analyzing', message: reference ? 'Reading the reference subtitle…' : 'Listening for speech throughout the video…', summary: null, video: context.video });
      job.done = this.run(job).catch(error => {
        if (context.generation === this.generation) this.emit({ status: job.cancelled ? 'cancelled' : 'error', message: job.cancelled ? 'Sync cancelled. Your original subtitles are unchanged.' : error.message });
      }).finally(() => {
        if (this.job === job) this.job = null;
        // Only remove the private temporary directory allocated for this job.
        if (path.dirname(job.temp) === this.cacheDir && path.basename(job.temp).startsWith('work-')) {
          try { fs.rmSync(job.temp, { recursive: true, force: true }); } catch (_) {} 
        }
        this.notify(this.getState());
      });
      return this.getState();
    } catch (error) {
      if (!automatic) this.emit({ status: 'error', message: error.message });
      return { error: error.message };
    }
  }
  async run(job) {
    const { context, mode } = job;
    const ext = path.extname(context.source).toLowerCase();
    const input = path.join(job.temp, 'original' + ext);
    fs.writeFileSync(input, readText(context.source));
    const before = timing.cues(readText(input), ext);
    if (!timing.valid(before) || before.length > 30000) throw Error('Choose a valid text subtitle with fewer than 30,000 lines.');
    let reference = job.reference;
    if (!reference) {
      const audio = path.join(job.temp, 'dialogue.wav');
      await this.process(job, 'ffmpeg.exe', ['-nostdin', '-hide_banner', '-v', 'error', '-threads', '2', '-y', '-i', context.video, '-map', `0:${context.audioIndex}`, '-vn', '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', audio]);
      reference = path.join(job.temp, 'speech.srt');
      await this.process(job, 'alass-cli.exe', [audio, '_', reference]);
    } else {
      const local = path.join(job.temp, 'reference' + path.extname(reference).toLowerCase());
      fs.writeFileSync(local, readText(reference)); reference = local;
    }
    const refCues = timing.cues(readText(reference), path.extname(reference));
    if (!timing.valid(refCues) || refCues.length < 3) throw Error('Not enough speech was detected. Try a different audio track or a reference subtitle.');
    this.ensure(job);
    this.emit({ status: 'aligning', message: mode === 'offset' ? 'Finding the best overall timing…' : 'Correcting drift and timing changes across the video…' });
    const corrected = path.join(job.temp, 'corrected' + ext);
    const args = [reference, input, corrected, '--interval', '10', '--encoding-inc', 'utf-8', '--encoding-ref', 'utf-8'];
    if (mode === 'offset') args.push('--no-split', '--disable-fps-guessing');
    else args.push('--split-penalty', mode === 'gentle' ? '20' : '7');
    await this.process(job, 'alass-cli.exe', args);
    this.ensure(job);
    const after = timing.cues(readText(corrected), ext);
    const summary = timing.assess(before, after, refCues, context.duration || Infinity);
    if (signature(context.video) !== context.videoSignature || hash(readText(context.source)) !== context.sourceHash) throw Error('The video or subtitle changed during analysis. Please try again.');
    fs.copyFileSync(corrected, job.output);
    this.result = { context, source: context.source, output: job.output, metadataFile: job.metadataFile, mode, summary,
      videoSignature: context.videoSignature, sourceHash: context.sourceHash, audioIndex: context.audioIndex, outputHash: hash(readText(corrected)), createdAt: Date.now(), approved: false, applied: false };
    this.saveResult();
    this.emit({ status: 'review', message: summary.reliable ? 'Sync complete. Your correction is ready.' : 'Sync complete — review recommended before applying.', summary });
    if (summary.reliable && this.settings().subSyncApply !== false) await this.apply(true);
    this.ensure(job);
  }
  ensure(job) {
    if (job.cancelled || job.context.generation !== this.generation) throw Error('Sync cancelled.');
  }
  process(job, exe, args) {
    this.ensure(job);
    return new Promise((resolve, reject) => {
      const child = spawn(path.join(this.toolsDir, exe), args, { windowsHide: true, shell: false,
        env: { ...process.env, ALASS_FFMPEG_PATH: path.join(this.toolsDir, 'ffmpeg.exe'), ALASS_FFPROBE_PATH: path.join(this.toolsDir, 'ffprobe.exe') } });
      job.child = child;
      let output = '', ended = false;
      const timer = setTimeout(() => { job.timedOut = true; this.kill(job); }, 20 * 60 * 1000);
      const finish = error => {
        if (ended) return; ended = true; clearTimeout(timer); if (job.child === child) job.child = null;
        if (error) reject(error); else resolve();
      };
      child.stdout.on('data', d => { output = (output + d).slice(-4000); });
      child.stderr.on('data', d => { output = (output + d).slice(-4000); });
      child.on('error', error => finish(Error(`Could not start the sync tool: ${error.message}`)));
      child.on('close', code => {
        if (job.timedOut) return finish(Error('Sync took longer than 20 minutes. Try offset-only mode or a reference subtitle.'));
        if (job.cancelled) return finish(Error('Sync cancelled.'));
        if (code !== 0) return finish(Error('The sync tool could not process this file. Try another subtitle or audio track. ' + output.replace(/[\r\n]+/g, ' ').slice(-250)));
        finish();
      });
    });
  }
  kill(job) {
    const child = job?.child;
    if (!child?.pid) return;
    if (process.platform === 'win32') {
      const killer = spawn(path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'taskkill.exe'), ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true });
      killer.on('error', () => { try { child.kill(); } catch (_) {} });
    } else child.kill();
  }
  cancel() { if (this.job) { this.job.cancelled = true; this.kill(this.job); this.emit({ message: 'Cancelling…' }); } return this.getState(); }
  async apply(automatic = false) {
    const result = this.result;
    if (!result) return { error: 'There is no corrected subtitle to apply.' };
    if (result.applied) return this.getState();
    try {
      const context = await this.context();
      if (automatic && this.job?.cancelled) throw Error('Sync cancelled.');
      if (context.generation !== result.context.generation || !same(context.video, result.context.video) || !same(context.source, result.source) || context.sourceHash !== result.sourceHash || context.videoSignature !== result.videoSignature || context.audioIndex !== result.context.audioIndex) throw Error('The video or subtitle selection changed. Run sync on the current selection.');
      if (automatic && (context.delay !== result.context.delay || context.speed !== result.context.speed)) return this.getState();
      const prior = this.appliedResult;
      const restore = prior && same(context.selectedFile, prior.output) && same(prior.source, result.source) ? prior.context : context;
      result.context.delay = restore.delay; result.context.speed = restore.speed;
      await this.engine().addSubtitle(result.output, true);
      if (this.generation !== context.generation) return this.getState();
      await this.engine().exec(['set_property', 'sub-delay', 0]);
      await this.engine().exec(['set_property', 'sub-speed', 1]);
      this.appliedResult = result;
      result.applied = true; result.approved = true; result.disabled = false;
      this.saveResult();
      this.emit({ status: 'applied', message: 'Corrected subtitles are playing. You can restore the original anytime.' });
      return this.getState();
    } catch (error) { this.emit({ status: 'review', message: error.message }); return { error: error.message }; }
  }
  async undo() {
    if (this.job) return { error: 'Cancel the running sync before restoring the original.' };
    const result = this.appliedResult;
    if (!result?.applied) return { error: 'No applied correction to undo.' };
    try {
      const current = await this.context();
      if (current.generation !== result.context.generation || !same(current.source, result.source)) throw Error('Switch back to the corrected subtitle before restoring it.');
      await this.engine().addSubtitle(result.source, true);
      if (this.generation !== current.generation) return this.getState();
      await this.engine().exec(['set_property', 'sub-delay', result.context.delay]);
      await this.engine().exec(['set_property', 'sub-speed', result.context.speed]);
      result.applied = false; result.approved = false; result.disabled = true; this.saveResult(result);
      this.appliedResult = null;
      this.emit({ status: 'restored', message: 'Original subtitles restored. Automatic reuse is disabled for this correction.' });
      return this.getState();
    } catch (error) { return { error: error.message }; }
  }
  saveResult(result = this.result) {
    const { context, metadataFile, ...metadata } = result;
    fs.writeFileSync(metadataFile + '.tmp', JSON.stringify(metadata));
    fs.renameSync(metadataFile + '.tmp', metadataFile);
  }
  fileChanged() {
    this.cancel(); this.generation++; this.result = null; this.appliedResult = null; this.attempted.clear();
    this.state = { status: 'idle', message: 'Sync subtitles to the dialogue, even when timing changes midway.' };
    this.notify(this.getState());
  }
  async automatic({ download = false, exact = false } = {}) {
    const s = this.settings();
    if (this.job) return;
    try {
      const ctx = await this.context();
      const attempt = `${ctx.generation}|${ctx.source}|${ctx.audioIndex}`;
      if (this.attempted.has(attempt)) return;
      this.attempted.add(attempt);
      // First reuse an explicitly approved correction; new analysis is opt-in.
      const mode = MODES.has(s.subSyncMode) ? s.subSyncMode : 'smart';
      const key = hash(JSON.stringify([ctx.videoSignature, ctx.source, ctx.sourceHash, ctx.audioIndex, mode, null]));
      const metadataFile = path.join(this.cacheDir, key + '.json');
      let saved = readJson(metadataFile);
      let savedFile = metadataFile;
      if (!saved?.approved && s.subSyncReuse !== false) {
        // Reference-based corrections are reusable too, without asking for the
        // reference file again. The video, source text and audio must still match.
        for (const name of fs.readdirSync(this.cacheDir).filter(n => /^[a-f0-9]{64}\.json$/.test(n))) {
          const file = path.join(this.cacheDir, name), candidate = readJson(file);
          if (candidate?.approved && candidate.videoSignature === ctx.videoSignature &&
              same(candidate.source, ctx.source) && candidate.sourceHash === ctx.sourceHash &&
              candidate.audioIndex === ctx.audioIndex && candidate.mode === mode && this.validSaved(candidate)) {
            if (!saved?.approved || candidate.createdAt > saved.createdAt) { saved = candidate; savedFile = file; }
          }
        }
      }
      if (s.subSyncReuse !== false && saved?.approved && this.validSaved(saved)) {
        if (ctx.generation !== this.generation || this.job) return;
        this.result = { ...saved, context: ctx, metadataFile: savedFile, applied: false };
        this.emit({ status: 'review', message: 'Saved correction is ready.', summary: saved.summary, video: ctx.video });
        if (s.subSyncApply !== false) await this.apply(true);
        return;
      }
      const allowed = download ? s.subSyncDownloads && !exact : s.subSyncLocal;
      if (allowed) await this.start({ mode, automatic: true });
    } catch (_) { /* Unsupported tracks do not interrupt playback. */ }
  }
}
module.exports = { SubtitleSync, readText, signature };
