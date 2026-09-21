/* The same passive update notice in the library and playback controls. */
(() => {
  const api = window.nova || window.player;
  const banner = document.getElementById('update-banner');
  if (!api?.updateState || !banner) return;
  const message = banner.querySelector('.update-message');
  const action = banner.querySelector('.update-action');
  const download = banner.querySelector('.update-download');
  let latest;
  function paint(u) {
    latest = u;
    const version = u.version ? `Nova Player ${u.version}` : 'A new version';
    const failed = u.installFailed;
    const texts = {
      available: `${version} is available. Download the latest version.`,
      downloading: `${version} is available — downloading ${u.percent || 0}%. You can keep watching.`,
      ready: failed ? 'The last update did not finish. Run the installer to try again.' : `${version} is ready. Restart to update, or install when you close the app.`,
      installing: 'Preparing the update — saving your playback progress…',
      error: 'Could not finish the update. Retry or download the installer.',
      checking: failed ? 'The last update did not finish. Checking for an update…' : ''
    };
    message.textContent = texts[u.status] || '';
    banner.classList.toggle('hidden', !message.textContent);
    action.hidden = !['ready', 'error', 'available'].includes(u.status);
    action.disabled = false;
    action.textContent = u.status === 'ready' ? 'Restart & update' : u.status === 'available' ? 'Download update' : 'Retry';
    download.hidden = !(u.status === 'error' || failed);
    banner.title = u.error || '';
  }
  action.addEventListener('click', async () => {
    action.disabled = true;
    try {
      if (latest.status === 'ready') {
        const result = await api.updateInstall();
        if (result?.error) paint({ ...latest, status: 'error', error: result.error });
      } else if (latest.status === 'available') await api.updateDownloadPage();
      else paint(await api.updateCheck());
    } catch (e) { paint({ ...latest, status: 'error', error: e.message }); }
    finally { action.disabled = false; }
  });
  download.addEventListener('click', () => api.updateDownloadPage());
  if (api.onUpdate) api.onUpdate(paint);
  else api.on('update-state', paint);
  api.updateState().then(paint).catch(() => {});
})();
