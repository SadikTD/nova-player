'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');
const timing = require('./subtitle-timing');
const SUPPORTED = new Set(['.srt', '.ass', '.ssa']);
const MODES = new Set(['smart', 'gentle', 'offset']);
// Text codecs mpv reports for subtitles built into a video. Picture formats
// (PGS, VobSub, DVB) have no text to line up.
const TEXT_CODECS = new Set(['subrip', 'srt', 'text', 'webvtt', 'mov_text', 'ass', 'ssa']);
const embeddedIndex = (track, tracks) => Number.isInteger(track['ff-index']) ? String(track['ff-index'])
  : `s:${tracks.filter(t => t.type === 'sub' && !t.external).indexOf(track)}`;
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
    this.state = { status: 'idle', message: '' };
    this.job = null;
    this.result = null;
    this.appliedResult = null;
    this.generation = 0;
    this.attempted = new Set();
    fs.mkdirSync(cacheDir, { recursive: true });
  }
  getState() {
    return { ...this.state, busy: !!this.job, engineReady: this.available(), canUndo: !!this.appliedResult?.applied,
      mode: this.job?.mode || this.result?.mode || null };
  }
  savedCount() { try { return this.records().length; } catch (_) { return 0; } }
  records() {
    return fs.readdirSync(this.cacheDir).filter(n => /^[a-f0-9]{64}\.json$/.test(n))
      .map(n => ({ file: path.join(this.cacheDir, n), record: readJson(path.join(this.cacheDir, n)) }))
      .filter(r => r.record?.output);
  }
  /* The fix this video was last watched with, whichever method produced it.
   * Picking by the current default method instead brought back an older result
   * the user had already replaced. */
  lastUsed(videoSignature, match = () => true) {
    let best = null;
    for (const { file, record } of this.records()) {
      if (record.videoSignature !== videoSignature || !record.approved || record.disabled || !match(record)) continue;
      const at = record.appliedAt || record.createdAt || 0;
      if ((!best || at > best.at) && this.validSaved(record)) best = { file, record, at };
    }
    return best;
  }
  emit(patch) { this.state = { ...this.state, ...patch }; this.notify(this.getState()); }
  available() { return ['alass-cli.exe', 'ffmpeg.exe', 'ffprobe.exe'].every(f => fs.existsSync(path.join(this.toolsDir, f))); }
  /* extract: allow pulling a built-in subtitle out of the video (takes a few
   * seconds). Automatic runs pass false so opening a video never does this;
   * they still see a copy made earlier by a manual sync. */
  async context({ extract = true, onExtract = () => {} } = {}) {
    const engine = this.engine();
    if (!engine?.isActive()) throw Error('Open a video first.');
    const generation = this.generation;
    const [video, tracks, duration, delay, speed] = await Promise.all(['path', 'track-list', 'duration', 'sub-delay', 'sub-speed'].map(p => engine.getProp(p)));
    if (generation !== this.generation) throw Error('The video changed. Try again on this episode.');
    if (!video || /^[a-z]+:\/\//i.test(video)) throw Error('Auto-sync currently supports local video files.');
    const track = tracks?.find(t => t.type === 'sub' && t.selected);
    if (!track) throw Error('Turn on a subtitle first, then sync it.');
    const videoSignature = signature(video);
    let source, embedded = null;
    if (track.external && track['external-filename']) {
      source = track['external-filename'];
      const existing = this.metadataFor(source);
      if (existing) { source = existing.source; embedded = existing.embedded ?? null; }
    } else {
      embedded = embeddedIndex(track, tracks);
      source = await this.extractEmbedded(video, videoSignature, track, embedded, extract && onExtract);
      if (generation !== this.generation) throw Error('The video changed. Try again on this episode.');
    }
    if (!SUPPORTED.has(path.extname(source).toLowerCase())) throw Error('Auto-sync supports SRT, ASS, and SSA text subtitles.');
    const audio = tracks.find(t => t.type === 'audio' && t.selected);
    const audioIndex = audio && !audio.external ? (Number.isInteger(audio['ff-index']) ? String(audio['ff-index']) : `a:${tracks.filter(t => t.type === 'audio' && !t.external).indexOf(audio)}`) : null;
    return { video, videoSignature, source, sourceHash: hash(readText(source)), selectedFile: track['external-filename'] || null, embedded,
      audioIndex, duration, delay: delay || 0, speed: speed || 1, generation };
  }
  /* Copy a subtitle built into the video out to Nova's cache so it can be
   * aligned like a file. The copy is reused, keyed by video and stream.
   * onExtract === false means only an existing copy may be used. */
  async extractEmbedded(video, videoSignature, track, index, onExtract) {
    const codec = String(track.codec || '').toLowerCase();
    if (!TEXT_CODECS.has(codec)) throw Error('This subtitle is stored as pictures, not text, so it can’t be synced. Choose a text subtitle instead.');
    const ass = codec === 'ass' || codec === 'ssa';
    const dir = path.join(this.cacheDir, 'embedded');
    const file = path.join(dir, hash(videoSignature + '|' + index) + (ass ? '.ass' : '.srt'));
    try { if (fs.statSync(file).size > 0) return file; } catch (_) {}
    if (!onExtract) throw Error('Built-in subtitles are only synced when you ask.');
    if (!this.available()) throw Error('Subtitle sync is not installed. Reinstall Nova Player to restore it.');
    onExtract();
    fs.mkdirSync(dir, { recursive: true });
    const part = `${file}.${crypto.randomBytes(4).toString('hex')}.part`;
    try {
      await this.tool('ffmpeg.exe', ['-nostdin', '-hide_banner', '-v', 'error', '-y', '-i', video, '-map', `0:${index}`,
        '-c:s', ass ? 'ass' : 'srt', '-f', ass ? 'ass' : 'srt', part], 5 * 60 * 1000);
      if (!fs.statSync(part).size) throw Error('empty');
      fs.renameSync(part, file);
    } catch (error) {
      try { fs.rmSync(part, { force: true }); } catch (_) {}
      throw Error('Nova couldn’t read the subtitle built into this video. ' + (error.message === 'empty' ? 'It has no text lines.' : error.message));
    }
    return file;
  }
  /* A short tool run outside a sync job (no cancel button yet). */
  tool(exe, args, timeout) {
    return new Promise((resolve, reject) => {
      const child = spawn(path.join(this.toolsDir, exe), args, { windowsHide: true, shell: false });
      let output = '';
      const timer = setTimeout(() => { try { child.kill(); } catch (_) {} }, timeout);
      child.stderr.on('data', d => { output = (output + d).slice(-2000); });
      child.stdout.on('data', () => {});
      child.on('error', error => { clearTimeout(timer); reject(error); });
      child.on('close', code => { clearTimeout(timer); code === 0 ? resolve() : reject(Error(output.replace(/[\r\n]+/g, ' ').slice(-250) || `exit ${code}`)); });
    });
  }
  /* The track a fix was made from: the external file, or the built-in stream. */
  sourceTrack(tracks, record) {
    const subs = (tracks || []).filter(t => t.type === 'sub');
    if (record.embedded != null) return subs.find(t => !t.external && embeddedIndex(t, subs) === record.embedded) || null;
    return subs.find(t => t.external && same(t['external-filename'], record.source)) || null;
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
      if (!this.available()) throw Error('Subtitle sync is not installed. Reinstall Nova Player to restore it.');
      const context = await this.context({ onExtract: () => { if (!automatic) this.emit({ status: 'analyzing', message: 'Reading the subtitles built into the video…', summary: null }); } });
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
        if (automatic && !previous.approved) return this.getState();
        this.result = { ...previous, context, metadataFile, applied: false };
        if (previous.approved) return this.apply(false, 'Using the subtitle fix saved for this video.');
        this.emit({ status: 'review', message: 'Nova found a new timing but isn’t sure it’s right. Try it and see.', summary: previous.summary, video: context.video });
        return this.getState();
      }
      if (automatic && previous?.disabled) return this.getState();
      const job = { context, mode, reference, output, metadataFile, cancelled: false, child: null,
        temp: fs.mkdtempSync(path.join(this.cacheDir, 'work-')) };
      this.job = job;
      this.result = null;
      this.emit({ status: 'analyzing', message: reference ? 'Reading the other subtitle file…' : 'Listening to the dialogue. You can keep watching.', summary: null, video: context.video });
      job.done = this.run(job).catch(error => {
        // Remember an automatic failure so reopening the video does not redo
        // a minute of analysis that will fail the same way. Manual runs still try.
        if (automatic && !job.cancelled && context.generation === this.generation) {
          try { fs.writeFileSync(metadataFile, JSON.stringify({ failed: true, disabled: true, source: context.source, videoSignature: context.videoSignature, mode, createdAt: Date.now() })); } catch (_) {}
        }
        if (context.generation === this.generation) this.emit({ status: job.cancelled ? 'cancelled' : 'error', message: job.cancelled ? 'Sync stopped. Your subtitles were not changed.' : error.message });
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
    const text = readText(context.source);
    fs.writeFileSync(input, ext === '.srt' ? timing.repairSrt(text).text : text);
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
    this.emit({ status: 'aligning', message: 'Lining the subtitles up with the dialogue…' });
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
      videoSignature: context.videoSignature, sourceHash: context.sourceHash, embedded: context.embedded, audioIndex: context.audioIndex, outputHash: hash(readText(corrected)), createdAt: Date.now(), approved: false, applied: false };
    this.saveResult();
    this.emit({ status: 'review', message: summary.reliable ? 'A fix is ready.' : 'Nova found a new timing but isn’t sure it’s right. Try it and see.', summary });
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
  dismiss() {
    if (this.job || this.state.status !== 'review') return this.getState();
    const active = this.appliedResult?.applied ? this.appliedResult : null;
    this.result = active;
    this.emit(active ? { status: 'applied', message: 'Subtitles synced to the dialogue.', summary: active.summary }
      : { status: 'idle', message: 'Kept the original timing.', summary: null });
    return this.getState();
  }
  /* Delete saved fixes for a video, or only those made from one subtitle file.
   * Everything touched lives in Nova's own cache folder. */
  forget({ video = null, source = null } = {}) {
    let videoSignature = null;
    try { if (video) videoSignature = signature(video); } catch (_) {}
    let removed = 0;
    for (const name of fs.readdirSync(this.cacheDir).filter(n => /^[a-f0-9]{64}.json$/.test(n))) {
      const file = path.join(this.cacheDir, name), record = readJson(file);
      if (!record) continue;
      const hit = (videoSignature && record.videoSignature === videoSignature) || (source && same(record.source, source));
      if (!hit) continue;
      if (record.output && same(path.dirname(record.output), this.cacheDir)) { try { fs.rmSync(record.output, { force: true }); } catch (_) {} }
      // The copy of a built-in subtitle is Nova's own; the video keeps the original.
      if (record.embedded != null && same(path.dirname(record.source), path.join(this.cacheDir, 'embedded'))) { try { fs.rmSync(record.source, { force: true }); } catch (_) {} }
      try { fs.rmSync(file, { force: true }); removed++; } catch (_) {}
    }
    const gone = r => r && ((videoSignature && r.videoSignature === videoSignature) || (source && same(r.source, source)));
    if (gone(this.result) || gone(this.appliedResult)) {
      this.result = null; this.appliedResult = null;
      if (!this.job) this.emit({ status: 'idle', message: '', summary: null });
    }
    this.attempted.clear();
    return removed;
  }
  isFix(file) { return !!this.metadataFor(file); }
  cancel() { if (this.job) { this.job.cancelled = true; this.kill(this.job); this.emit({ message: 'Cancelling…' }); } return this.getState(); }
  async apply(automatic = false, message = 'Subtitles synced to the dialogue.') {
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
      await this.engine().addSubtitle(result.output, true, 'Synced to dialogue');
      if (this.generation !== context.generation) return this.getState();
      await this.engine().exec(['set_property', 'sub-delay', 0]);
      await this.engine().exec(['set_property', 'sub-speed', 1]);
      this.appliedResult = result;
      result.applied = true; result.approved = true; result.disabled = false; result.appliedAt = Date.now();
      this.saveResult();
      this.emit({ status: 'applied', message, summary: result.summary, video: context.video });
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
      const original = result.embedded != null && this.sourceTrack(await this.engine().getProp('track-list'), result);
      if (original) await this.engine().exec(['set_property', 'sid', original.id]);
      else await this.engine().addSubtitle(result.source, true);
      if (this.generation !== current.generation) return this.getState();
      await this.engine().exec(['set_property', 'sub-delay', result.context.delay]);
      await this.engine().exec(['set_property', 'sub-speed', result.context.speed]);
      result.applied = false; result.approved = false; result.disabled = true; this.saveResult(result);
      this.appliedResult = null;
      this.emit({ status: 'restored', message: 'Back to the original timing. Nova will not reapply this fix.' });
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
    this.state = { status: 'idle', message: '' };
    this.notify(this.getState());
  }
  /* On open, bring back the subtitle file the last fix was made from, even if
   * mpv auto-selected a different one (a folder often holds two downloads). */
  async restoreLast(generation) {
    const engine = this.engine();
    const [video, tracks] = await Promise.all([engine.getProp('path'), engine.getProp('track-list')]);
    if (generation !== this.generation || !video || /^[a-z]+:\/\//i.test(video) || !Array.isArray(tracks)) return;
    const last = this.lastUsed(signature(video))?.record;
    if (!last || !fs.existsSync(last.source)) return;
    const subs = tracks.filter(t => t.type === 'sub');
    const current = subs.find(t => t.selected);
    if (current?.external && [last.source, last.output].some(f => same(current['external-filename'], f))) return;
    const loaded = this.sourceTrack(tracks, last);
    if (last.embedded != null) {
      if (loaded && !loaded.selected) await engine.exec(['set_property', 'sid', loaded.id]);
      return;
    }
    if (loaded) await engine.exec(['set_property', 'sid', loaded.id]);
    else await engine.addSubtitle(last.source, true);
  }
  async automatic({ download = false, exact = false, opened = false } = {}) {
    const s = this.settings();
    if (this.job) return;
    try {
      if (opened) await this.restoreLast(this.generation);
      const ctx = await this.context({ extract: false });
      const attempt = `${ctx.generation}|${ctx.source}|${ctx.audioIndex}`;
      if (this.attempted.has(attempt)) return;
      this.attempted.add(attempt);
      // A fix the user already accepted for this exact subtitle comes back as-is,
      // including reference-based ones, without asking for the reference again.
      const saved = this.lastUsed(ctx.videoSignature, r => same(r.source, ctx.source) && r.sourceHash === ctx.sourceHash && r.audioIndex === ctx.audioIndex);
      if (saved) {
        if (ctx.generation !== this.generation || this.job) return;
        this.result = { ...saved.record, context: ctx, metadataFile: saved.file, applied: false };
        if (s.subSyncApply !== false) await this.apply(true, 'Using the subtitle fix saved for this video.');
        else this.emit({ status: 'review', message: 'Nova has a saved fix for this video. Try it?', summary: saved.record.summary, video: ctx.video });
        return;
      }
      const allowed = download ? s.subSyncDownloads && !exact : s.subSyncLocal;
      // Built-in subtitles are usually already in sync; only analyze them on request.
      if (allowed && ctx.embedded == null) await this.start({ mode: MODES.has(s.subSyncMode) ? s.subSyncMode : 'smart', automatic: true });
    } catch (_) { /* Unsupported tracks do not interrupt playback. */ }
  }
}
module.exports = { SubtitleSync, readText, signature };
