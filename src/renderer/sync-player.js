/* Player panel for on-device subtitle alignment. Shares the player's sheet UI. */
let SYNC = { status: 'idle', message: '' };
let syncMode = null;
const SYNC_METHODS = {
  smart: { name: 'Best match', help: 'Fixes timing that drifts or jumps after scene cuts and ad breaks.' },
  gentle: { name: 'Smooth', help: 'Makes fewer, gentler changes. Try it when Best match jumps around.' },
  offset: { name: 'Simple shift', help: 'Moves every subtitle by the same amount. Best when all lines are equally early or late.' }
};
const SYNC_ORDER = ['smart', 'gentle', 'offset'];
const SYNC_ICONS = {
  wave: '<svg viewBox="0 0 24 24"><path d="M3 12h2M7 8v8M11 5v14M15 9v6M19 7v10M21 12h0"/></svg>',
  check: '<svg viewBox="0 0 24 24"><path d="M5 12.5l4.2 4.2L19 7"/></svg>',
  alert: '<svg viewBox="0 0 24 24"><path d="M12 8v5M12 16.5v.5"/><circle cx="12" cy="12" r="9"/></svg>',
  undo: '<svg viewBox="0 0 24 24"><path d="M9 7L4 12l5 5M4 12h11a5 5 0 010 10h-2"/></svg>'
};
async function openSyncSheet(start = false) {
  closePopover(); sheetKind = 'sync'; syncMode = SET.subSyncMode || 'smart';
  paintSheet('Subtitle sync', '<div class="subs-status">One moment…</div>');
  try { SYNC = await window.player.subSyncState() || SYNC; } catch (_) {}
  if (sheetKind !== 'sync') return;
  if (SYNC.mode && !SYNC.busy) syncMode = SYNC.mode;
  paintSyncSheet();
  if (start && !SYNC.busy && !['applied', 'review'].includes(SYNC.status)) await syncAction(() => window.player.subSyncStart({ mode: syncMode }));
}
async function syncAction(action) {
  try {
    const result = await action();
    if (result?.error) toast(result.error);
    if (result?.status) SYNC = result;
    if (sheetKind === 'sync') paintSyncSheet();
  } catch (error) { toast(error.message); }
}
function nextSyncMode(mode) { return SYNC_ORDER[(SYNC_ORDER.indexOf(mode) + 1) % SYNC_ORDER.length]; }
function shiftText(summary) {
  if (!summary) return '';
  const s = Math.abs(summary.medianShift);
  if (s < 0.15) return 'Timing was already close';
  return `Moved about ${s < 10 ? s.toFixed(1) : Math.round(s)} s ${summary.medianShift > 0 ? 'later' : 'earlier'}`;
}
function paintSyncSheet() {
  const { busy, status, summary } = SYNC;
  const used = SYNC.mode || syncMode;
  const other = nextSyncMode(used);
  let tone = 'idle', icon = SYNC_ICONS.wave, title = 'Subtitles out of sync?', text = SYNC.message, actions = '', chips = '';
  if (busy) {
    tone = 'busy'; icon = '<span class="ss-spin"></span>'; title = 'Syncing subtitles…';
    const step = status === 'aligning' ? 2 : 1;
    chips = `<div class="ss-steps"><span class="${step >= 1 ? 'on' : ''}">1 · Listen to the dialogue</span><span class="${step >= 2 ? 'on' : ''}">2 · Line up the subtitles</span></div>`;
    actions = '<button class="ss-btn" id="as-cancel">Stop</button>';
  } else if (status === 'applied') {
    tone = 'ok'; icon = SYNC_ICONS.check; title = 'Subtitles are synced';
    text = 'If a line still looks off, try another method or undo.';
    chips = summary ? `<div class="ss-chips"><span>${esc(shiftText(summary))}</span><span>${esc(SYNC_METHODS[used]?.name || 'Custom')}</span></div>` : '';
    actions = `${SYNC.canUndo ? '<button class="ss-btn" id="as-undo">Undo</button>' : ''}`;
  } else if (status === 'review') {
    tone = 'warn'; icon = SYNC_ICONS.alert; title = 'Check this fix';
    chips = summary ? `<div class="ss-chips"><span>${esc(shiftText(summary))}</span><span>${esc(SYNC_METHODS[used]?.name || 'Custom')}</span></div>` : '';
    actions = '<button class="ss-btn primary" id="as-use">Try it</button><button class="ss-btn" id="as-keep">Keep original</button>';
  } else if (status === 'error') {
    tone = 'bad'; icon = SYNC_ICONS.alert; title = 'Couldn’t sync these subtitles';
    actions = '<button class="ss-btn primary" id="as-start">Try again</button>';
  } else {
    if (status === 'restored') { icon = SYNC_ICONS.undo; title = 'Original timing restored'; }
    else if (status === 'cancelled') title = 'Sync stopped';
    else text = 'Nova listens to the dialogue and moves each subtitle to match. It takes about a minute, and you can keep watching.';
    actions = '<button class="ss-btn primary" id="as-start">Sync subtitles</button>';
  }
  const offerOther = !busy && ['applied', 'review', 'error'].includes(status);
  paintSheet('Subtitle sync', `<div class="ss">
    <div class="ss-hero ss-${tone}" role="status">
      <div class="ss-icon">${icon}</div>
      <div class="ss-copy"><h3>${esc(title)}</h3>${text ? `<p>${esc(text)}</p>` : ''}${chips}</div>
    </div>
    ${actions ? `<div class="ss-actions">${actions}</div>` : ''}
    ${offerOther ? `<div class="ss-row">
      <div><b>Still not right?</b><span>Try the ${esc(SYNC_METHODS[other].name)} method instead.</span></div>
      <button class="ss-btn" id="as-other">Try ${esc(SYNC_METHODS[other].name)}</button></div>` : ''}
    <div class="ss-section">
      <div class="ss-label">Method</div>
      <div class="ss-seg" role="radiogroup" aria-label="Sync method">${SYNC_ORDER.map(m =>
        `<button role="radio" aria-checked="${m === syncMode}" class="${m === syncMode ? 'on' : ''}" data-mode="${m}" ${busy ? 'disabled' : ''}>${esc(SYNC_METHODS[m].name)}</button>`).join('')}</div>
      <p class="ss-hint">${esc(SYNC_METHODS[syncMode]?.help || '')}${syncMode === 'smart' ? ' Recommended.' : ''}</p>
    </div>
    <div class="ss-section">
      <div class="ss-label">Other ways to fix timing</div>
      <button class="ss-link" id="as-reference" ${busy ? 'disabled' : ''}><b>Match another subtitle file</b><span>Copy the timing from a subtitle you know is in sync.</span></button>
      <button class="ss-link" id="as-manual"><b>Adjust by hand</b><span>Nudge subtitles earlier or later yourself.</span></button>
    </div>
    <p class="ss-foot">Runs on this computer. Your subtitle file is never changed.</p>
  </div>`);
  sheet.querySelectorAll('[data-mode]').forEach(b => b.addEventListener('click', () => { syncMode = b.dataset.mode; paintSyncSheet(); }));
  $('#as-start')?.addEventListener('click', () => syncAction(() => window.player.subSyncStart({ mode: syncMode, force: status === 'error' })));
  $('#as-other')?.addEventListener('click', () => { syncMode = other; syncAction(() => window.player.subSyncStart({ mode: other })); });
  $('#as-reference')?.addEventListener('click', () => syncAction(() => window.player.subSyncReference(syncMode)));
  $('#as-cancel')?.addEventListener('click', () => syncAction(() => window.player.subSyncCancel()));
  $('#as-use')?.addEventListener('click', () => syncAction(() => window.player.subSyncApply()));
  $('#as-keep')?.addEventListener('click', () => syncAction(() => window.player.subSyncDismiss()));
  $('#as-undo')?.addEventListener('click', () => syncAction(() => window.player.subSyncUndo()));
  $('#as-manual').addEventListener('click', () => { closeSheet(); togglePopover('subs'); });
}
function syncStateChanged(state) {
  const previous = SYNC.status; SYNC = state;
  const badge = $('#sync-badge');
  badge.classList.toggle('hidden', !state.busy && !['review', 'applied', 'error'].includes(state.status));
  badge.textContent = state.busy ? 'Syncing subtitles…' : state.status === 'applied' ? '✓ Subtitles synced'
    : state.status === 'review' ? 'Check subtitle sync' : 'Subtitle sync failed';
  badge.dataset.tone = state.busy ? 'busy' : state.status;
  if (previous !== state.status && state.status !== 'idle') showUI();
  if (sheetKind === 'sync') paintSyncSheet();
  if (sheetKind !== 'sync' && previous !== state.status && ['applied', 'review', 'error'].includes(state.status)) {
    const lead = state.status === 'error' ? 'Couldn’t sync subtitles. ' : '';
    snack(lead + (state.message || ''), state.status === 'applied' ? 'Options' : 'View', () => openSyncSheet(false));
  }
}
window.player.onSubSync?.(syncStateChanged);
$('#sync-badge').addEventListener('click', () => openSyncSheet(false));
window.player.subSyncState?.().then(state => { if (state) syncStateChanged(state); }).catch(() => {});
