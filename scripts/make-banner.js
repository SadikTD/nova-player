/* Generates docs/images/banner.png for the README. Run: node scripts/make-banner.js */
const fs = require('fs');
const path = require('path');
const { Resvg } = require('@resvg/resvg-js');

const OUT = path.join(__dirname, '..', 'docs', 'images');
fs.mkdirSync(OUT, { recursive: true });

const svg = `
<svg width="1280" height="420" viewBox="0 0 1280 420" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#0d1017"/>
      <stop offset="1" stop-color="#141b28"/>
    </linearGradient>
    <linearGradient id="accent" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#4facfe"/>
      <stop offset="1" stop-color="#2f6bff"/>
    </linearGradient>
    <radialGradient id="glow" cx="0.5" cy="0.5" r="0.5">
      <stop offset="0" stop-color="#2f6bff" stop-opacity="0.35"/>
      <stop offset="1" stop-color="#2f6bff" stop-opacity="0"/>
    </radialGradient>
  </defs>

  <rect width="1280" height="420" fill="url(#bg)"/>
  <ellipse cx="250" cy="210" rx="420" ry="300" fill="url(#glow)"/>
  <ellipse cx="1120" cy="380" rx="360" ry="240" fill="url(#glow)" opacity="0.6"/>

  <!-- app mark -->
  <rect x="96" y="126" width="168" height="168" rx="42" fill="url(#accent)"/>
  <path d="M 158 174 L 218 210 L 158 246 Z" fill="#ffffff"/>

  <!-- wordmark -->
  <text x="306" y="205" font-family="Segoe UI, Arial, sans-serif" font-size="72" font-weight="700" fill="#eef2f8">Nova Player</text>
  <text x="310" y="252" font-family="Segoe UI, Arial, sans-serif" font-size="26" font-weight="400" fill="#8b94a7">A media player for Windows that just plays everything.</text>

  <!-- feature chips -->
  <g font-family="Segoe UI, Arial, sans-serif" font-size="19" font-weight="600">
    <rect x="310" y="284" width="150" height="42" rx="21" fill="#4facfe" fill-opacity="0.12" stroke="#4facfe" stroke-opacity="0.35"/>
    <text x="385" y="311" fill="#4facfe" text-anchor="middle">MKV · HEVC</text>

    <rect x="474" y="284" width="152" height="42" rx="21" fill="#4facfe" fill-opacity="0.12" stroke="#4facfe" stroke-opacity="0.35"/>
    <text x="550" y="311" fill="#4facfe" text-anchor="middle">Subtitles</text>

    <rect x="640" y="284" width="176" height="42" rx="21" fill="#4facfe" fill-opacity="0.12" stroke="#4facfe" stroke-opacity="0.35"/>
    <text x="728" y="311" fill="#4facfe" text-anchor="middle">No ads</text>

    <rect x="830" y="284" width="234" height="42" rx="21" fill="#4facfe" fill-opacity="0.12" stroke="#4facfe" stroke-opacity="0.35"/>
    <text x="947" y="311" fill="#4facfe" text-anchor="middle">No update nags</text>
  </g>
</svg>`;

const resvg = new Resvg(svg, { fitTo: { mode: 'width', value: 1280 } });
fs.writeFileSync(path.join(OUT, 'banner.png'), resvg.render().asPng());
console.log('banner.png written');
