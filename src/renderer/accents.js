/* Shared accent palette — used by both the library window and the player overlay.
 * Everything tinted lives behind these custom properties so a theme change is a
 * single call with no reload. */
'use strict';

const NOVA_ACCENTS = {
  blue:    { name: 'Nova blue', a: '#4facfe', b: '#2f6bff' },
  violet:  { name: 'Violet',    a: '#a78bfa', b: '#6d28d9' },
  emerald: { name: 'Emerald',   a: '#34d399', b: '#059669' },
  amber:   { name: 'Amber',     a: '#fbbf24', b: '#d97706' },
  rose:    { name: 'Rose',      a: '#fb7185', b: '#e11d48' },
  cyan:    { name: 'Cyan',      a: '#22d3ee', b: '#0891b2' }
};

function novaRgb(hex) {
  const h = hex.replace('#', '');
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}

function novaApplyAccent(key) {
  const c = NOVA_ACCENTS[key] || NOVA_ACCENTS.blue;
  const [r, g, b] = novaRgb(c.a);
  const s = document.documentElement.style;
  s.setProperty('--accent', c.a);
  s.setProperty('--accent-2', c.b);
  s.setProperty('--accent-grad', `linear-gradient(135deg, ${c.a}, ${c.b})`);
  s.setProperty('--accent-soft', `rgba(${r}, ${g}, ${b}, 0.12)`);
  s.setProperty('--accent-soft-2', `rgba(${r}, ${g}, ${b}, 0.2)`);
  s.setProperty('--accent-line', `rgba(${r}, ${g}, ${b}, 0.4)`);
  s.setProperty('--accent-glow', `rgba(${r}, ${g}, ${b}, 0.45)`);
}
