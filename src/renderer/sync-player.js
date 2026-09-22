/* Player panel for on-device subtitle alignment. Shares the player's sheet UI. */
let SYNC = { status: 'idle', message: 'Ready to match subtitles to dialogue.' };
let syncMode = null;
async function openSyncSheet(start = false) {
  closePopover(); sheetKind = 'sync'; syncMode = SET.subSyncMode || 'smart';
  paintSheet('Automatic subtitle sync', '<div class="subs-status">Reading sync status…</div>');
  try { SYNC = await window.player.subSyncState() || SYNC; } catch (_) {}
  if (sheetKind !== 'sync') return;
  paintSyncSheet();
  if (start && !SYNC.busy) await syncAction(() => window.player.subSyncStart({ mode: syncMode }));
}
async function syncAction(action) {
  try {
    const result = await action();
    if (result?.error) toast(result.error);
    if (result?.status) SYNC = result;
    if (sheetKind === 'sync') paintSyncSheet();
  } catch (error) { toast(error.message); }
}
function paintSyncSheet() {
  const busy = SYNC.busy;
  const summary = SYNC.summary;
  const ready = !busy && ['review', 'restored'].includes(SYNC.status) && !!summary;
  const labels = { idle: 'Ready', analyzing: 'Analyzing dialogue', aligning: 'Aligning subtitles', review: 'Review result', applied: 'Correction active', restored: 'Original restored', cancelled: 'Cancelled', error: 'Could not sync' };
  paintSheet('Automatic subtitle sync', `
    <div class="as-intro"><span class="sync-local">PRIVATE · ON DEVICE</span><h3>Let the dialogue set the timing.</h3>
      <p>Uses your selected audio track. For a translated subtitle, speech patterns can still help. A correctly timed reference subtitle is another option.</p></div>
    <div class="as-status" role="status"><strong>${busy ? '<span class="as-spinner"></span>' : ''}${esc(labels[SYNC.status] || 'Ready')}</strong><p>${esc(SYNC.message || '')}</p></div>
    ${summary ? `<div class="as-stats"><div><b>${summary.lines}</b><span>subtitle lines</span></div><div><b>${summary.before}% → ${summary.after}%</b><span>speech overlap estimate</span></div><div><b>${summary.medianShift > 0 ? '+' : ''}${summary.medianShift}s</b><span>typical timing change</span></div></div><p class="as-note">${esc(summary.note)} This estimate is not a guarantee of correct dialogue.</p>` : ''}
    <div class="as-option"><label for="as-mode">Correction mode</label><select id="as-mode" ${busy ? 'disabled' : ''}>
      <option value="smart" ${syncMode === 'smart' ? 'selected' : ''}>Smart — drift &amp; scene cuts</option>
      <option value="gentle" ${syncMode === 'gentle' ? 'selected' : ''}>Gentle — fewer timing changes</option>
      <option value="offset" ${syncMode === 'offset' ? 'selected' : ''}>Offset only — one delay</option></select></div>
    <div class="as-option"><label for="as-apply">Apply results that pass the timing checks</label><input id="as-apply" type="checkbox" ${SET.subSyncApply !== false ? 'checked' : ''}></div>
    <div class="as-option"><label for="as-reuse">Reuse saved corrections next time</label><input id="as-reuse" type="checkbox" ${SET.subSyncReuse !== false ? 'checked' : ''}></div>
    <div class="as-buttons">${busy ? '<button class="mini-btn" id="as-cancel">Cancel sync</button>' : `<button class="mini-btn acc" id="as-start">${summary ? 'Analyze again' : 'Sync to audio'}</button><button class="mini-btn" id="as-reference">Use reference subtitle…</button>`}
      ${ready ? '<button class="mini-btn acc" id="as-use">Try corrected subtitles</button>' : ''}
      ${SYNC.canUndo ? '<button class="mini-btn" id="as-undo">Restore original</button>' : ''}</div>
    <p class="as-note">SRT, ASS and SSA files · Originals are never overwritten · Switching videos cancels analysis.<br>You can close this panel and keep watching. Automatic syncing of downloads and local files can be enabled in Settings.</p>`);
  $('#as-mode').addEventListener('change', e => { syncMode = e.target.value; });
  for (const [id, key] of [['as-apply', 'subSyncApply'], ['as-reuse', 'subSyncReuse']]) {
    $('#' + id).addEventListener('change', e => { SET[key] = e.target.checked; window.player.saveSettings({ [key]: e.target.checked }); });
  }
  $('#as-start')?.addEventListener('click', () => syncAction(() => window.player.subSyncStart({ mode: syncMode, force: !!summary })));
  $('#as-reference')?.addEventListener('click', () => syncAction(() => window.player.subSyncReference(syncMode)));
  $('#as-cancel')?.addEventListener('click', () => syncAction(() => window.player.subSyncCancel()));
  $('#as-use')?.addEventListener('click', () => syncAction(() => window.player.subSyncApply()));
  $('#as-undo')?.addEventListener('click', () => syncAction(() => window.player.subSyncUndo()));
}
function syncStateChanged(state) {
  const previous = SYNC.status; SYNC = state;
  const badge = $('#sync-badge');
  badge.classList.toggle('hidden', !state.busy && !['review', 'applied', 'error'].includes(state.status));
  badge.textContent = state.busy ? 'Syncing subtitles…' : state.status === 'applied' ? 'Subtitles synced' : 'Review subtitle sync';
  if (previous !== state.status && state.status !== 'idle') showUI();
  if (sheetKind === 'sync') paintSyncSheet();
  if (previous !== state.status && ['applied', 'review', 'error'].includes(state.status)) snack(state.message, 'Details', () => openSyncSheet(false));
}
window.player.onSubSync?.(syncStateChanged);
$('#sync-badge').addEventListener('click', () => openSyncSheet(false));
window.player.subSyncState?.().then(state => { if (state) syncStateChanged(state); }).catch(() => {});
