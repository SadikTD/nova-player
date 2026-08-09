/*
 * Online subtitle search and download.
 *
 * Source: OpenSubtitles' legacy REST bridge (rest.opensubtitles.org). It is the
 * only large subtitle index that still answers without an API key, which is the
 * whole point — Nova has to work the moment it is installed, not after the user
 * registers for a developer account somewhere.
 *
 * Two ways to find a subtitle, and they are not equally good:
 *
 *   1. moviehash — a 64-bit fingerprint of the actual file (size + first and
 *      last 64 KiB). When it hits, the subtitle was timed against *this exact
 *      release*, so it is frame-accurate with no delay nudging. Always try it.
 *   2. text query — parsed show/season/episode from the filename. Broad, but a
 *      subtitle timed for a different release can be seconds out.
 *
 * Everything downstream (scoring, the "perfect sync" badge, auto-fetch) exists
 * to push the user towards a hash match whenever one exists.
 */
const fs = require('fs');
const path = require('path');
const https = require('https');
const zlib = require('zlib');
const { app } = require('electron');

const API = 'https://rest.opensubtitles.org/search';
// OpenSubtitles gates the legacy bridge on this header. A made-up product
// string is accepted, but the documented public value is the safe one.
const UA = 'TemporaryUserAgent';

const SUB_EXT = new Set(['.srt', '.ass', '.ssa', '.sub', '.vtt', '.idx', '.sup']);

/* Where downloads land when the video's own folder cannot be written to
 * (read-only media, a network share, a locked-down Program Files install).
 * mpv is pointed at this directory too, so those subtitles still auto-load. */
function fallbackDir() {
  const d = path.join(app.getPath('userData'), 'subtitles');
  try { fs.mkdirSync(d, { recursive: true }); } catch (_) {}
  return d;
}

// ---------------------------------------------------------------- networking

function request(url, { headers = {}, timeout = 20000, redirects = 5 } = {}) {
  return new Promise((resolve, reject) => {
    const req = https.request(url, {
      method: 'GET',
      headers: { 'User-Agent': 'NovaPlayer', 'X-User-Agent': UA, ...headers },
      timeout
    }, res => {
      if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location) {
        res.resume();
        if (redirects <= 0) return reject(new Error('too many redirects'));
        let next;
        try { next = new URL(res.headers.location, url); } catch (_) {
          return reject(new Error('the service sent an invalid redirect'));
        }
        // The bridge answers some malformed requests with a redirect to the
        // literal host "_", which then surfaces as a DNS failure and reads to
        // the user as "no internet". Name it for what it is instead.
        if (!next.hostname.includes('.')) {
          return reject(new Error('the service rejected that search'));
        }
        return request(next.href, { headers, timeout, redirects: redirects - 1 }).then(resolve, reject);
      }
      const chunks = [];
      let size = 0;
      res.on('data', c => {
        chunks.push(c);
        size += c.length;
        // A subtitle is tens of kilobytes. Anything past a few megabytes is an
        // error page or a mis-linked archive, not something worth buffering.
        if (size > 8 * 1024 * 1024) { req.destroy(); reject(new Error('response too large')); }
      });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
    });
    req.on('error', err => reject(err));
    req.on('timeout', () => { req.destroy(); reject(new Error('timed out')); });
    req.end();
  });
}

/* The bridge rate-limits per IP, and a season batch fires a lot of requests in
 * a row. Keeping a floor between calls is cheaper than handling the 429. */
let lastCall = 0;
async function paced(fn) {
  const wait = Math.max(0, 350 - (Date.now() - lastCall));
  if (wait) await new Promise(r => setTimeout(r, wait));
  lastCall = Date.now();
  return fn();
}

function friendlyNetError(err) {
  const m = String((err && err.message) || err);
  if (/ENOTFOUND|EAI_AGAIN|ENETUNREACH/i.test(m)) return 'No internet connection';
  if (/timed out|ETIMEDOUT/i.test(m)) return 'The subtitle service did not respond';
  if (/ECONNRESET|ECONNREFUSED/i.test(m)) return 'The subtitle service refused the connection';
  return m;
}

// ------------------------------------------------------------- moviehash

/* OpenSubtitles' hash: the file size plus every 64-bit little-endian word of
 * the first and last 64 KiB, summed with 64-bit wraparound. Files smaller than
 * 128 KiB have no defined hash. */
const HASH_CHUNK = 65536;

async function movieHash(file) {
  let fd;
  try {
    const stat = await fs.promises.stat(file);
    const size = stat.size;
    if (size < HASH_CHUNK * 2) return null;
    fd = await fs.promises.open(file, 'r');
    const head = Buffer.alloc(HASH_CHUNK);
    const tail = Buffer.alloc(HASH_CHUNK);
    await fd.read(head, 0, HASH_CHUNK, 0);
    await fd.read(tail, 0, HASH_CHUNK, size - HASH_CHUNK);
    const MASK = (1n << 64n) - 1n;
    let sum = BigInt(size) & MASK;
    for (const buf of [head, tail]) {
      for (let off = 0; off < HASH_CHUNK; off += 8) {
        sum = (sum + buf.readBigUInt64LE(off)) & MASK;
      }
    }
    return { hash: sum.toString(16).padStart(16, '0'), size };
  } catch (_) {
    return null;                       // hashing is an optimisation, never fatal
  } finally {
    try { await fd?.close(); } catch (_) {}
  }
}

// ------------------------------------------------------------ name parsing

/* Release noise that must not end up in the search query. Ordered loosely by
 * how often it shows up in scene and web-rip names. */
const NOISE = new RegExp('\\b(' + [
  '\\d{3,4}[pi]', '4k', 'uhd', 'hd', 'sd',
  'x26[45]', 'h ?26[45]', 'hevc', 'avc', 'xvid', 'divx', 'vp9', 'av1',
  '10 ?bit', '8 ?bit', 'hdr10?', 'dolby ?vision', 'dv', 'sdr',
  'blu ?ray', 'bluray', 'brrip', 'bdrip', 'bdremux', 'remux',
  'web ?dl', 'webrip', 'web', 'hdtv', 'pdtv', 'dvdrip', 'dvdscr', 'hdrip', 'camrip', 'cam',
  'aac[0-9. ]*', 'ac3', 'eac3', 'dd[p+]?[0-9. ]*', 'dts(?: ?hd)?(?: ?ma)?', 'truehd', 'atmos',
  'flac', 'mp3', 'opus', '[257][ .]1(?:ch)?',
  'amzn', 'nf', 'netflix', 'dsnp', 'hmax', 'hulu', 'atvp', 'pcok', 'stan', 'itunes',
  'proper', 'repack', 'internal', 'limited', 'extended', 'uncut', 'unrated', 'remastered',
  'dual ?audio', 'multi', 'subbed', 'dubbed', 'esub[s]?', 'msub[s]?',
  'complete', 'season', 'series'
].join('|') + ')\\b', 'ig');

const SEASON_EPISODE = [
  /\bs(\d{1,2})[ ._-]*e(\d{1,3})\b/i,          // S01E05, S01.E05
  /\b(\d{1,2})x(\d{1,3})\b/i,                  // 1x05
  /\bseason[ ._-]*(\d{1,2})[ ._-]*episode[ ._-]*(\d{1,3})\b/i
];

/* What we can work out about a video from its filename alone. Deliberately
 * conservative: a wrong season number sends the search somewhere useless, so
 * anything ambiguous is left undetected and the user can type it themselves. */
function parseName(filePath) {
  const base = path.basename(String(filePath || ''), path.extname(String(filePath || '')));
  let work = base.replace(/[._]+/g, ' ').replace(/\s+/g, ' ').trim();

  // release group: trailing "-GROUP", or a leading "[Group]" on anime rips
  let group = '';
  const trailing = base.match(/-([A-Za-z0-9]{2,})(?:\[[^\]]*\])?$/);
  if (trailing) group = trailing[1];
  const bracket = base.match(/^\[([^\]]+)\]/);
  if (!group && bracket) group = bracket[1];

  const source = (base.match(/\b(web[ ._-]?dl|webrip|bluray|blu[ ._-]?ray|brrip|bdrip|hdtv|dvdrip|remux|hdrip)\b/i) || [])[1] || '';
  const resolution = (base.match(/\b(\d{3,4})[pi]\b/i) || [])[1] || '';

  let season = null, episode = null;
  for (const re of SEASON_EPISODE) {
    const m = work.match(re);
    if (m) { season = +m[1]; episode = +m[2]; break; }
  }

  // Cut the title at the season/episode marker — everything after it is release
  // metadata, never part of the show name.
  let title = work;
  if (season != null) {
    const idx = work.search(SEASON_EPISODE.find(re => re.test(work)));
    if (idx > 0) title = work.slice(0, idx);
  }

  // A four-digit year is the movie equivalent of the S01E05 marker: it ends the
  // title. Ignore anything that is really an episode number or a resolution.
  let year = null;
  const ym = title.match(/\b(19\d{2}|20\d{2})\b/);
  if (ym && season == null) {
    year = +ym[1];
    title = title.slice(0, title.indexOf(ym[0]));
  }

  title = title
    .replace(/^\[[^\]]*\]/, '')                 // leading [Group]
    .replace(/\([^)]*\)/g, ' ')
    .replace(NOISE, ' ')
    // Cutting the title at a year or an S01E05 marker regularly leaves the
    // opening half of a bracket behind ("Interstellar (").
    .replace(/[([{<]\s*$/, ' ')
    .replace(/^[)\]}>\s]+/, '')
    .replace(/[-–—_]+$/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  // Anime style: "[Group] Show Name - 07" — a bare number after a dash is the
  // episode, with no season anywhere in the name.
  if (season == null) {
    const anime = base.replace(/^\[[^\]]*\]\s*/, '').match(/^(.+?)\s-\s(\d{1,3})(?:v\d)?\s*(?:\[|\(|$)/);
    if (anime) {
      title = anime[1].replace(NOISE, ' ').replace(/\s+/g, ' ').trim();
      season = 1;
      episode = +anime[2];
    }
  }

  return {
    title: title || base,
    season, episode, year, group, source, resolution,
    isEpisode: season != null && episode != null
  };
}

// ------------------------------------------------------------- searching

/* Three-letter ISO codes, because that is what the bridge speaks. The picker in
 * the UI works in these directly so nothing has to be translated twice. */
const LANGUAGES = [
  ['eng', 'English'], ['ben', 'Bengali'], ['hin', 'Hindi'], ['urd', 'Urdu'],
  ['ara', 'Arabic'], ['spa', 'Spanish'], ['fre', 'French'], ['ger', 'German'],
  ['ita', 'Italian'], ['por', 'Portuguese'], ['pob', 'Portuguese (BR)'],
  ['rus', 'Russian'], ['tur', 'Turkish'], ['dut', 'Dutch'], ['pol', 'Polish'],
  ['swe', 'Swedish'], ['dan', 'Danish'], ['fin', 'Finnish'], ['nor', 'Norwegian'],
  ['chi', 'Chinese'], ['zht', 'Chinese (trad)'], ['jpn', 'Japanese'], ['kor', 'Korean'],
  ['tha', 'Thai'], ['vie', 'Vietnamese'], ['ind', 'Indonesian'], ['may', 'Malay'],
  ['tam', 'Tamil'], ['tel', 'Telugu'], ['mal', 'Malayalam'], ['per', 'Persian'],
  ['heb', 'Hebrew'], ['gre', 'Greek'], ['cze', 'Czech'], ['rum', 'Romanian'],
  ['hun', 'Hungarian'], ['ukr', 'Ukrainian'], ['bul', 'Bulgarian'], ['srp', 'Serbian'],
  ['hrv', 'Croatian'], ['slv', 'Slovenian'], ['slo', 'Slovak'], ['est', 'Estonian'],
  ['lav', 'Latvian'], ['lit', 'Lithuanian'], ['alb', 'Albanian'], ['mac', 'Macedonian'],
  ['fil', 'Filipino'], ['sin', 'Sinhalese'], ['nep', 'Nepali'], ['bur', 'Burmese']
];
const LANG_NAME = new Map(LANGUAGES);

function normLangs(list) {
  const out = [];
  for (const raw of (Array.isArray(list) ? list : [list])) {
    const v = String(raw || '').trim().toLowerCase();
    if (/^[a-z]{3}$/.test(v) && !out.includes(v)) out.push(v);
  }
  return out.length ? out.slice(0, 5) : ['eng'];
}

/* The bridge is fussy about the query segment in ways that are not documented
 * and fail badly rather than loudly:
 *   - an upper-case letter earns a 302 to "https://_/search/…", a host that
 *     does not exist, so the search dies as a DNS error
 *   - an apostrophe earns a 301 that re-encodes the "+" separators into "%2B"
 *     and returns nothing
 * Both are avoided by normalising here instead of relying on redirects. */
function queryParam(text) {
  const clean = String(text || '')
    .toLowerCase()
    .replace(/['’`]/g, '')                      // "marvel's" -> "marvels"
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim()
    .replace(/\s+/g, ' ');
  return encodeURIComponent(clean).replace(/%20/g, '+');
}

/* The bridge rejects multiple values in one parameter, so languages are one
 * request each. They run in parallel; a language that fails is simply absent
 * rather than failing the whole search. */
async function fetchSet(pathParts, langs) {
  const results = await Promise.all(langs.map(async lang => {
    const url = `${API}/${[...pathParts, `sublanguageid-${lang}`].sort().join('/')}`;
    try {
      const r = await paced(() => request(url, { headers: { Accept: 'application/json' } }));
      if (r.status !== 200) return { lang, rows: [], status: r.status };
      const parsed = JSON.parse(r.body.toString('utf8'));
      return { lang, rows: Array.isArray(parsed) ? parsed : [] };
    } catch (err) {
      return { lang, rows: [], error: err };
    }
  }));
  return results;
}

/* Turn a bridge row into the shape the UI and the downloader both use. */
function shape(row, langs, parsed) {
  const name = row.SubFileName || '';
  const release = row.MovieReleaseName || '';
  const hay = (name + ' ' + release).toLowerCase();
  return {
    id: String(row.IDSubtitleFile || row.IDSubtitle || ''),
    name,
    release,
    lang: String(row.SubLanguageID || '').toLowerCase(),
    langName: row.LanguageName || LANG_NAME.get(String(row.SubLanguageID || '').toLowerCase()) || row.SubLanguageID,
    format: String(row.SubFormat || 'srt').toLowerCase(),
    encoding: row.SubEncoding || '',
    downloads: +row.SubDownloadsCnt || 0,
    rating: +row.SubRating || 0,
    votes: +row.SubSumVotes || 0,
    size: +row.SubSize || 0,
    bad: +row.SubBad > 0,
    hearingImpaired: String(row.SubHearingImpaired) === '1',
    // "Forced" tracks translate only the odd foreign-language line. Picking one
    // as the main subtitle leaves you watching an episode with twelve captions
    // in it, which looks like the download simply failed.
    forced: /\b(forced|foreign[ ._-]?parts?|signs?[ ._-]?(and|&)[ ._-]?songs?)\b/i.test(hay),
    fps: +row.MovieFPS || 0,
    uploader: row.UserNickName || '',
    added: row.SubAddDate || '',
    hashMatch: row.MatchedBy === 'moviehash',
    url: row.SubDownloadLink || '',
    // pre-computed so the scorer stays cheap and the UI can explain itself
    groupMatch: !!(parsed.group && hay.includes(parsed.group.toLowerCase())),
    sourceMatch: !!(parsed.source && hay.includes(parsed.source.toLowerCase().replace(/[ ._-]/g, ''))),
    langRank: Math.max(0, langs.indexOf(String(row.SubLanguageID || '').toLowerCase()))
  };
}

/* Rank order, most to least important:
 *   hash match  — timed against this exact file, nothing else comes close
 *   language    — the user's first choice beats their fifth
 *   same release group / source — the usual reason a subtitle drifts
 *   popularity  — hundreds of thousands of downloads is a strong quality signal
 *   rating      — real but sparse; most entries have no votes at all
 */
function score(s) {
  let v = 0;
  if (s.hashMatch) v += 1000;
  v += Math.max(0, 60 - s.langRank * 60);
  if (s.groupMatch) v += 120;
  if (s.sourceMatch) v += 40;
  if (s.format === 'srt') v += 25;              // universally supported, stylable
  v += Math.min(90, Math.log10(s.downloads + 1) * 18);
  if (s.votes >= 2) v += Math.min(30, (s.rating - 5) * 6);
  if (s.bad) v -= 200;
  if (s.hearingImpaired) v -= 8;                // fine, just not the default pick
  // Anything this small cannot be a full transcript. Measured against the real
  // archive: a 45-minute episode runs 30–90 KB, a forced track under 3 KB.
  if (s.forced) v -= 400;
  else if (s.size > 0 && s.size < 12000) v -= Math.min(180, (12000 - s.size) / 50);
  return v;
}

/* Search for one video.
 *
 * `opts.query` overrides the parsed title (the UI lets the user retype it when
 * the filename is unhelpful); `opts.season`/`opts.episode` likewise. */
async function search(filePath, opts = {}) {
  const langs = normLangs(opts.langs);
  const parsed = { ...parseName(filePath), ...(opts.override || {}) };
  const isLocal = filePath && !/^[a-z]+:\/\//i.test(filePath);

  const rawQuery = String(opts.query != null ? opts.query : parsed.title).trim();
  const query = queryParam(rawQuery);
  const season = opts.season != null ? opts.season : parsed.season;
  const episode = opts.episode != null ? opts.episode : parsed.episode;

  const batches = [];
  const errors = [];

  // 1. hash search — only possible for a real file on disk
  if (isLocal && opts.useHash !== false) {
    const h = await movieHash(filePath);
    if (h) batches.push(fetchSet([`moviehash-${h.hash}`, `moviebytesize-${h.size}`], langs));
  }

  // 2. text search
  if (query) {
    const parts = [`query-${query}`];
    if (season != null) parts.push(`season-${season}`);
    if (episode != null) parts.push(`episode-${episode}`);
    batches.push(fetchSet(parts, langs));
  }

  if (!batches.length) return { results: [], parsed, langs, error: 'Nothing to search for' };

  const sets = (await Promise.all(batches)).flat();
  const byId = new Map();
  for (const set of sets) {
    if (set.error) errors.push(friendlyNetError(set.error));
    for (const row of set.rows) {
      const s = shape(row, langs, parsed);
      if (!s.url || !s.id) continue;
      // The same subtitle can come back from both searches; the hash-matched
      // copy is the one worth keeping.
      const prev = byId.get(s.id);
      if (prev) { if (s.hashMatch) prev.hashMatch = true; continue; }
      byId.set(s.id, s);
    }
  }

  const results = [...byId.values()]
    .map(s => ({ ...s, score: score(s) }))
    .sort((a, b) => b.score - a.score)
    .slice(0, 60);

  return {
    results,
    parsed: { ...parsed, title: rawQuery, season, episode },
    langs,
    error: results.length ? null : (errors[0] || null)
  };
}

// ------------------------------------------------------------- downloading

/* Legacy encodings still dominate the archive — a Spanish or Turkish subtitle
 * from 2012 is very often CP1252 or CP1254, and reading it as UTF-8 produces a
 * screenful of question marks. Electron ships a full ICU, so TextDecoder can
 * handle all of these directly. */
const ENCODING_ALIASES = {
  'cp1250': 'windows-1250', 'cp1251': 'windows-1251', 'cp1252': 'windows-1252',
  'cp1253': 'windows-1253', 'cp1254': 'windows-1254', 'cp1255': 'windows-1255',
  'cp1256': 'windows-1256', 'cp1257': 'windows-1257', 'cp1258': 'windows-1258',
  'cp850': 'ibm866', 'cp866': 'ibm866', 'ascii': 'utf-8', 'us-ascii': 'utf-8'
};

function decodeSubtitle(buf, declared) {
  if (buf.length >= 3 && buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) {
    return buf.slice(3).toString('utf8');                     // explicit UTF-8
  }
  if (buf.length >= 2 && ((buf[0] === 0xff && buf[1] === 0xfe) || (buf[0] === 0xfe && buf[1] === 0xff))) {
    try { return new TextDecoder(buf[0] === 0xff ? 'utf-16le' : 'utf-16be').decode(buf); } catch (_) {}
  }
  const candidates = [];
  const d = String(declared || '').toLowerCase().replace(/\s+/g, '');
  if (d) candidates.push(ENCODING_ALIASES[d] || d);
  candidates.push('utf-8', 'windows-1252');

  for (const enc of candidates) {
    try {
      const text = new TextDecoder(enc, { fatal: enc === 'utf-8' }).decode(buf);
      // A non-fatal decode can still be wrong; U+FFFD is the giveaway.
      if (!text.includes('�')) return text;
    } catch (_) { /* wrong or unknown encoding — try the next */ }
  }
  return buf.toString('latin1');                               // never fails
}

/* Uploaders' advertising, injected as real subtitle cues. It shows up over the
 * first frame of the episode and there is no reason to keep it. */
const AD_PATTERNS = [
  /opensubtitles/i, /addic7ed/i, /subscene/i, /yifysubtitles/i,
  /https?:\/\//i,
  // A bare domain is the giveaway on the newer injected ads (osdb.link,
  // getray.app). Real dialogue almost never contains one.
  /\b[a-z0-9][a-z0-9-]{2,}\.(com|net|org|app|link|tv|io|me|co|info|xyz)\b/i,
  /watch online movies/i, /become vip member/i, /advertise your product/i,
  /support us and become/i, /remove all ads/i
];

function looksLikeAd(text) {
  const t = text.replace(/<[^>]+>/g, ' ').trim();
  if (!t) return false;
  return AD_PATTERNS.some(re => re.test(t));
}

const TIME_LINE = /^\s*-?\d{1,3}:\d{2}:\d{2}[.,]\d{1,3}\s*-->\s*-?\d{1,3}:\d{2}:\d{2}[.,]\d{1,3}/;

/* Strip ad cues and renumber.
 *
 * Cues are found by their timing lines rather than by splitting on blank lines,
 * because the files that most need cleaning are exactly the ones where that
 * would fail: the ad injector overwrites the cue's index line, leaving two
 * timing lines stacked with no separator between them. Splitting on blank lines
 * then glues the advert onto a real subtitle and keeps both. Anchoring on the
 * timing lines re-cuts the file correctly and hands back valid SRT even when
 * what came down the wire was not.
 *
 * Anything with no recognisable timing line at all is passed through untouched —
 * better an advert than a mangled subtitle. */
function cleanSrt(text) {
  const normalised = text.replace(/\r\n?/g, '\n').replace(/^﻿/, '');
  const lines = normalised.split('\n');
  const anchors = [];
  for (let i = 0; i < lines.length; i++) if (TIME_LINE.test(lines[i])) anchors.push(i);
  if (!anchors.length) return { text: normalised, removed: 0 };

  const cues = [];
  let removed = 0;
  for (let k = 0; k < anchors.length; k++) {
    const at = anchors[k];
    const stop = k + 1 < anchors.length ? anchors[k + 1] : lines.length;
    const body = lines.slice(at + 1, stop).join('\n')
      .replace(/\n\s*\d{1,6}\s*$/, '')      // the following cue's index number
      .replace(/\s+$/, '');
    if (!body.trim()) continue;             // nothing left to show
    if (looksLikeAd(body)) { removed++; continue; }
    cues.push(lines[at].trim() + '\n' + body);
  }
  if (!cues.length) return { text: normalised, removed: 0 };
  return { text: cues.map((c, i) => `${i + 1}\n${c}`).join('\n\n') + '\n', removed };
}

/* Where to write. Next to the video is what everyone expects — it means mpv
 * finds the subtitle again by itself next time. Nova never overwrites a file it
 * did not create: if the natural name is taken, it uses its own stable slot so
 * re-downloading replaces Nova's copy and nothing else. */
function targetPath(videoPath, lang, ext) {
  const isLocal = videoPath && !/^[a-z]+:\/\//i.test(videoPath);
  const base = (isLocal
    ? path.basename(videoPath, path.extname(videoPath))
    : 'stream-' + Date.now()).replace(/[<>:"/\\|?*]/g, '_');
  const dir = isLocal ? path.dirname(videoPath) : fallbackDir();

  const preferred = path.join(dir, `${base}.${lang}${ext}`);
  const owned = path.join(dir, `${base}.${lang}-nova${ext}`);
  let chosen = preferred;
  try { if (fs.existsSync(preferred)) chosen = owned; } catch (_) {}
  return { primary: chosen, fallback: path.join(fallbackDir(), `${base}.${lang}${ext}`) };
}

/* fs.access is not a reliable writability test on Windows — a folder can pass
 * it and still reject the write. The only honest probe is the write itself. */
async function writeSubtitle(videoPath, lang, ext, text) {
  const { primary, fallback } = targetPath(videoPath, lang, ext);
  try {
    await fs.promises.writeFile(primary, text, 'utf8');
    return primary;
  } catch (err) {
    if (primary === fallback) throw err;
    await fs.promises.writeFile(fallback, text, 'utf8');
    return fallback;
  }
}

async function download(videoPath, sub) {
  if (!sub || !sub.url) throw new Error('That subtitle has no download link');
  const r = await paced(() => request(sub.url, { timeout: 30000 }));
  if (r.status !== 200) {
    // The archive answers with a plain page once the per-IP daily allowance is
    // used up, which is otherwise indistinguishable from a broken link.
    if (r.status === 403 || r.status === 429) {
      throw new Error('OpenSubtitles has hit its free daily download limit for your connection — try again tomorrow');
    }
    throw new Error(`Download failed (HTTP ${r.status})`);
  }

  let raw = r.body;
  if (raw.length > 2 && raw[0] === 0x1f && raw[1] === 0x8b) {
    try { raw = zlib.gunzipSync(raw); } catch (_) { throw new Error('The downloaded subtitle was corrupt'); }
  }
  const head = raw.slice(0, 400).toString('latin1').toLowerCase();
  if (/^\s*<(!doctype|html)/.test(head)) {
    throw new Error('OpenSubtitles returned a web page instead of a subtitle — the daily free limit is probably reached');
  }
  if (!raw.length) throw new Error('The downloaded subtitle was empty');

  let ext = '.' + String(sub.format || 'srt').replace(/^\./, '');
  if (!SUB_EXT.has(ext)) ext = '.srt';

  let text = decodeSubtitle(raw, sub.encoding);
  let removed = 0;
  if (ext === '.srt') {
    const cleaned = cleanSrt(text);
    text = cleaned.text;
    removed = cleaned.removed;
  }

  // Always written back as UTF-8: mpv then needs no --sub-codepage guessing,
  // which is where accented and Bengali text used to turn into boxes.
  const out = await writeSubtitle(videoPath, (sub.lang || 'sub').slice(0, 3), ext, text);
  return { file: out, adsRemoved: removed, lang: sub.lang, name: sub.name };
}

// --------------------------------------------------------- local detection

/* Does this video already have a subtitle sitting beside it? Used to decide
 * whether auto-fetch has anything to do. Matches mpv's own fuzzy rule: any
 * subtitle file in the folder whose name starts with the video's name. */
function hasLocalSubtitle(videoPath) {
  try {
    if (!videoPath || /^[a-z]+:\/\//i.test(videoPath)) return false;
    const dir = path.dirname(videoPath);
    const base = path.basename(videoPath, path.extname(videoPath)).toLowerCase();
    for (const entry of fs.readdirSync(dir)) {
      const ext = path.extname(entry).toLowerCase();
      if (!SUB_EXT.has(ext)) continue;
      if (entry.toLowerCase().startsWith(base)) return true;
    }
  } catch (_) {}
  return false;
}

module.exports = {
  search, download, movieHash, parseName, hasLocalSubtitle,
  fallbackDir, friendlyNetError, LANGUAGES, normLangs
};
