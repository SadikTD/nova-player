/*
 * Downloads the playback engine (mpv) and in-video UI components (uosc, thumbfast)
 * into vendor/ and mpv-config/. Run once: `npm run setup`
 */
const fs = require('fs');
const path = require('path');
const { pipeline } = require('stream/promises');

const ROOT = path.join(__dirname, '..');
const VENDOR = path.join(ROOT, 'vendor');
const MPV_DIR = path.join(VENDOR, 'mpv');
const CFG = path.join(ROOT, 'mpv-config');
const TMP = path.join(VENDOR, '_tmp');

const UA = { 'User-Agent': 'nova-player-setup' };

async function download(url, dest) {
  console.log('Downloading:', url);
  const res = await fetch(url, { headers: UA, redirect: 'follow' });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  await pipeline(res.body, fs.createWriteStream(dest));
  const mb = (fs.statSync(dest).size / 1048576).toFixed(1);
  console.log('  saved', path.basename(dest), `(${mb} MB)`);
}

async function latestMpvAssetUrl() {
  // zhongfly/mpv-winbuild publishes up-to-date official mpv Windows builds
  const res = await fetch('https://api.github.com/repos/zhongfly/mpv-winbuild/releases/latest', { headers: UA });
  if (!res.ok) throw new Error('GitHub API error ' + res.status);
  const rel = await res.json();
  // plain x86_64 build (works on every 64-bit CPU; v3 variant needs AVX2)
  const asset = rel.assets.find(a => /^mpv-x86_64-\d{8}-git-[0-9a-f]+\.7z$/.test(a.name))
    || rel.assets.find(a => /^mpv-x86_64-.*\.7z$/.test(a.name) && !a.name.includes('v3') && !a.name.includes('dev'));
  if (!asset) throw new Error('No suitable mpv asset found in latest release');
  return { url: asset.browser_download_url, name: asset.name, size: asset.size };
}

function extract7z(archive, outDir) {
  const _7z = require('7zip-min');
  return new Promise((resolve, reject) => {
    _7z.unpack(archive, outDir, err => (err ? reject(err) : resolve()));
  });
}

async function extractZip(archive, outDir) {
  // 7zip-min handles zip as well
  return extract7z(archive, outDir);
}

async function main() {
  fs.mkdirSync(MPV_DIR, { recursive: true });
  fs.mkdirSync(TMP, { recursive: true });
  fs.mkdirSync(path.join(CFG, 'scripts'), { recursive: true });
  fs.mkdirSync(path.join(CFG, 'fonts'), { recursive: true });

  // --- mpv engine ---
  if (!fs.existsSync(path.join(MPV_DIR, 'mpv.exe'))) {
    const asset = await latestMpvAssetUrl();
    const archive = path.join(TMP, asset.name);
    await download(asset.url, archive);
    const out = path.join(TMP, 'mpv-extract');
    fs.rmSync(out, { recursive: true, force: true });
    await extract7z(archive, out);
    // archive may extract flat or into a subfolder — find mpv.exe
    let src = out;
    if (!fs.existsSync(path.join(src, 'mpv.exe'))) {
      const sub = fs.readdirSync(out).find(d => fs.existsSync(path.join(out, d, 'mpv.exe')));
      if (!sub) throw new Error('mpv.exe not found in archive');
      src = path.join(out, sub);
    }
    fs.cpSync(src, MPV_DIR, { recursive: true });
    console.log('mpv installed to vendor/mpv');
  } else {
    console.log('mpv already present, skipping');
  }

  // (in-video UI is Nova's own overlay window — no third-party OSC needed)

  fs.rmSync(TMP, { recursive: true, force: true });
  console.log('\nVendor setup complete.');
}

main().catch(err => { console.error(err); process.exit(1); });
