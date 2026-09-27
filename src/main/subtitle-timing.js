'use strict';
// Timing analysis is intentionally independent of Electron and the sync engine.
function clock(value) {
  const p = value.replace(',', '.').split(':').map(Number);
  return p.length === 3 && p.every(Number.isFinite) ? p[0] * 3600 + p[1] * 60 + p[2] : NaN;
}
function cues(text, ext = '.srt') {
  text = text.replace(/^\uFEFF/, '').replace(/\r/g, '');
  if (ext === '.ass' || ext === '.ssa') {
    let format = ['Layer', 'Start', 'End', 'Style', 'Name', 'MarginL', 'MarginR', 'MarginV', 'Effect', 'Text'];
    let events = false;
    const out = [];
    for (const line of text.split('\n')) {
      if (/^\[/.test(line)) events = /^\[Events\]/i.test(line);
      if (!events) continue;
      if (/^Format:/i.test(line)) format = line.slice(line.indexOf(':') + 1).split(',').map(s => s.trim());
      if (!/^Dialogue:/i.test(line)) continue;
      const fields = line.slice(line.indexOf(':') + 1).trimStart().split(',');
      const start = clock(fields[format.findIndex(s => /^Start$/i.test(s))] || '');
      const end = clock(fields[format.findIndex(s => /^End$/i.test(s))] || '');
      const ti = format.findIndex(s => /^Text$/i.test(s));
      out.push({ start, end, text: fields.slice(ti).join(',') });
    }
    return out;
  }
  return text.split(/\n\s*\n/).flatMap(block => {
    const lines = block.split('\n');
    const i = lines.findIndex(s => s.includes('-->'));
    if (i < 0) return [];
    const match = lines[i].match(/(\d+:\d{2}:\d{2}[,.]\d+)\s*-->\s*(\d+:\d{2}:\d{2}[,.]\d+)/);
    return match ? [{ start: clock(match[1]), end: clock(match[2]), text: lines.slice(i + 1).join('\n') }] : [];
  });
}
function stamp(t) {
  const ms = Math.max(0, Math.round(t * 1000)), pad = (n, w = 2) => String(n).padStart(w, '0');
  return `${pad(Math.floor(ms / 3600000))}:${pad(Math.floor(ms / 60000) % 60)}:${pad(Math.floor(ms / 1000) % 60)},${pad(ms % 1000, 3)}`;
}
/* Downloaded SRTs sometimes carry one mangled timing line, e.g. a cue running
 * 00:00:00 → 00:32:19. It stays on screen for half an hour and ruins alignment,
 * because the aligner reads it as half an hour of speech. Repair only cues that
 * are out of order or impossibly long; everything else is left byte-for-byte. */
function repairSrt(text) {
  const blocks = text.replace(/^\uFEFF/, '').replace(/\r/g, '').split(/\n\s*\n/);
  const parsed = blocks.map(block => {
    const lines = block.split('\n'), i = lines.findIndex(s => s.includes('-->'));
    const m = i >= 0 && lines[i].match(/(\d+:\d{2}:\d{2}[,.]\d+)\s*-->\s*(\d+:\d{2}:\d{2}[,.]\d+)/);
    return m ? { lines, i, start: clock(m[1]), end: clock(m[2]) } : null;
  });
  const timed = parsed.filter(Boolean);
  const guess = c => Math.min(6, Math.max(1.5, c.lines.slice(c.i + 1).join(' ').length / 15));
  let repaired = 0;
  timed.forEach((c, k) => {
    const prev = timed[k - 1], next = timed[k + 1];
    let { start, end } = c;
    if (prev && start < prev.start - 1 && end > prev.end) start = Math.max(prev.end, end - guess(c));
    else if (end - start > 30) end = Math.max(start + 0.5, Math.min(next ? next.start : Infinity, start + guess(c)));
    if (start === c.start && end === c.end) return;
    c.lines[c.i] = `${stamp(start)} --> ${stamp(end)}`;
    Object.assign(c, { start, end });
    repaired++;
  });
  if (!repaired) return { text, repaired };
  return { text: blocks.map((b, k) => parsed[k] ? parsed[k].lines.join('\n') : b).join('\n\n'), repaired };
}
function valid(list) {
  return list.length > 0 && list.every(c => Number.isFinite(c.start) && Number.isFinite(c.end) && c.start >= 0 && c.end > c.start);
}
function overlap(list, speech) {
  const merged = [];
  for (const cue of [...speech].sort((a, b) => a.start - b.start)) {
    const last = merged.at(-1);
    if (last && cue.start <= last.end) last.end = Math.max(last.end, cue.end);
    else merged.push({ start: cue.start, end: cue.end });
  }
  let total = 0, matched = 0, hits = 0;
  for (const cue of list) {
    const duration = cue.end - cue.start;
    let amount = 0;
    let lo = 0, hi = merged.length;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (merged[mid].end <= cue.start) lo = mid + 1; else hi = mid; }
    for (let i = lo; i < merged.length; i++) {
      const ref = merged[i];
      if (ref.start >= cue.end) break;
      amount += Math.max(0, Math.min(cue.end, ref.end) - Math.max(cue.start, ref.start));
    }
    total += duration; matched += amount;
    if (amount / duration >= 0.2) hits++;
  }
  return { score: total ? matched / total : 0, coverage: list.length ? hits / list.length : 0 };
}
// alass rewrites the file and trims trailing spaces on each line, which is
// common in downloaded subtitles. Only the words themselves have to survive.
const words = text => text.replace(/\s+/g, ' ').trim();
function assess(before, after, reference, duration = Infinity) {
  if (!valid(before) || !valid(after) || !valid(reference)) throw Error('No usable subtitle or speech timings were found.');
  if (before.length !== after.length || before.some((c, i) => words(c.text) !== words(after[i].text))) throw Error('The sync tool altered the subtitle text, so the result was discarded. Your original subtitles are unchanged.');
  const old = overlap(before, reference), next = overlap(after, reference);
  const shifts = after.map((c, i) => c.start - before[i].start);
  const sorted = [...shifts].sort((a, b) => a - b);
  const maxShift = shifts.reduce((max, n) => Math.max(max, Math.abs(n)), 0);
  const sections = 1 + shifts.slice(1).filter((n, i) => Math.abs(n - shifts[i]) > 0.4).length;
  const reliable = after.length >= 8 && next.score >= 0.45 && next.coverage >= 0.65 &&
    next.score >= old.score - 0.03 && maxShift <= 600 && after.every(c => c.end <= duration + 15);
  return { lines: after.length, before: Math.round(old.score * 100), after: Math.round(next.score * 100),
    medianShift: +sorted[Math.floor(sorted.length / 2)].toFixed(2), sections, reliable,
    note: reliable ? 'Speech timing looks consistent. Please check a few lines.' : 'This match needs a listening check. Preview it or try another subtitle.' };
}
module.exports = { cues, valid, overlap, assess, repairSrt };
