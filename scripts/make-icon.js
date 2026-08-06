/* Generates build/icon.png and build/icon.ico from an inline SVG. Run: npm run icon */
const fs = require('fs');
const path = require('path');
const { Resvg } = require('@resvg/resvg-js');
const pngToIco = require('png-to-ico');

const ROOT = path.join(__dirname, '..');
const BUILD = path.join(ROOT, 'build');
fs.mkdirSync(BUILD, { recursive: true });

const svg = `
<svg width="512" height="512" viewBox="0 0 512 512" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#4facfe"/>
      <stop offset="1" stop-color="#2151d1"/>
    </linearGradient>
    <linearGradient id="sheen" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#ffffff" stop-opacity="0.22"/>
      <stop offset="0.5" stop-color="#ffffff" stop-opacity="0"/>
    </linearGradient>
  </defs>
  <rect x="16" y="16" width="480" height="480" rx="112" fill="url(#bg)"/>
  <rect x="16" y="16" width="480" height="480" rx="112" fill="url(#sheen)"/>
  <circle cx="256" cy="256" r="150" fill="#ffffff" fill-opacity="0.12"/>
  <path d="M 208 168 L 368 256 L 208 344 Z" fill="#ffffff"/>
</svg>`;

async function main() {
  const sizes = [16, 24, 32, 48, 64, 128, 256];
  const pngs = [];
  for (const s of sizes) {
    const resvg = new Resvg(svg, { fitTo: { mode: 'width', value: s } });
    const buf = resvg.render().asPng();
    const p = path.join(BUILD, `icon-${s}.png`);
    fs.writeFileSync(p, buf);
    pngs.push(p);
  }
  fs.copyFileSync(path.join(BUILD, 'icon-256.png'), path.join(BUILD, 'icon.png'));
  const ico = await pngToIco(pngs);
  fs.writeFileSync(path.join(BUILD, 'icon.ico'), ico);
  console.log('icon.ico + icon.png generated');
}

main().catch(e => { console.error(e); process.exit(1); });
