/* Download pinned local subtitle-sync tools. No runtime downloads or accounts. */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { pipeline } = require('stream/promises');
const seven = require('7zip-min');
const root = path.resolve(__dirname, '..');
const target = path.join(root, 'vendor', 'sync');
const tmp = path.join(root, 'vendor', 'sync-download');
const assets = [
  { name: 'alass-windows64.zip', url: 'https://github.com/kaegi/alass/releases/download/v2.0.0/alass-windows64.zip', sha256: 'e81a72f97f592910e909a2352d6b8c0de0801c51ac1383bad4ebf3f2eCDD2FD8'.toLowerCase() },
  { name: 'ffmpeg-n8.1.3-win64-lgpl-shared-8.1.zip', url: 'https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-09-21-13-55/ffmpeg-n8.1.3-win64-lgpl-shared-8.1.zip', sha256: '790da87e0b4c25c2063fa0306848ae4846e346c507a097b05be58310fa41d96f' }
];
async function main() {
  fs.mkdirSync(target, { recursive: true }); fs.mkdirSync(tmp, { recursive: true });
  for (const asset of assets) {
    const archive = path.join(tmp, asset.name);
    if (!fs.existsSync(archive)) {
      console.log('Downloading', asset.name);
      const response = await fetch(asset.url);
      if (!response.ok) throw Error(`Could not download ${asset.name}: HTTP ${response.status}`);
      await pipeline(response.body, fs.createWriteStream(archive));
    }
    const actual = crypto.createHash('sha256').update(fs.readFileSync(archive)).digest('hex');
    if (actual !== asset.sha256) throw Error(`${asset.name}: checksum mismatch. Remove the archive and retry.`);
    await new Promise((resolve, reject) => seven.unpack(archive, tmp, error => error ? reject(error) : resolve()));
  }
  const alass = path.join(tmp, 'alass-windows64', 'bin');
  fs.copyFileSync(path.join(alass, 'alass-cli.exe'), path.join(target, 'alass-cli.exe'));
  fs.copyFileSync(path.join(alass, 'LICENSE.txt'), path.join(target, 'ALASS-LICENSE.txt'));
  const ffmpeg = path.join(tmp, 'ffmpeg-n8.1.3-win64-lgpl-shared-8.1');
  for (const name of fs.readdirSync(path.join(ffmpeg, 'bin'))) {
    if (name.endsWith('.dll') || ['ffmpeg.exe', 'ffprobe.exe'].includes(name)) fs.copyFileSync(path.join(ffmpeg, 'bin', name), path.join(target, name));
  }
  fs.copyFileSync(path.join(ffmpeg, 'LICENSE.txt'), path.join(target, 'LICENSE.txt'));
  fs.copyFileSync(path.join(root, 'docs', 'sync-tools-sources.md'), path.join(target, 'SOURCES.md'));
  console.log('Local subtitle-sync tools are ready.');
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
