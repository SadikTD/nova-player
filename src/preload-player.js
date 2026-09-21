const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('player', {
  updateInstall: () => ipcRenderer.invoke('update-install'),
  updateDownloadPage: () => ipcRenderer.invoke('update-download-page'),
  updateState: () => ipcRenderer.invoke('update-state'),
  updateCheck: () => ipcRenderer.invoke('update-check'),
  onUpdate: cb => ipcRenderer.on('update-state', (_e, p) => cb(p)),
  cmd: (...c) => ipcRenderer.invoke('player-cmd', c),
  get: name => ipcRenderer.invoke('player-get', name),
  init: () => ipcRenderer.invoke('player-init'),
  stats: () => ipcRenderer.invoke('player-stats'),
  winCmd: c => ipcRenderer.invoke('win-cmd', c),
  dragStart: () => ipcRenderer.invoke('win-drag-start'),
  dragEnd: () => ipcRenderer.invoke('win-drag-end'),
  resizeStart: edge => ipcRenderer.invoke('win-resize-start', edge),
  resizeEnd: () => ipcRenderer.invoke('win-resize-end'),
  loadSubtitle: () => ipcRenderer.invoke('player-load-sub'),
  subsContext: () => ipcRenderer.invoke('subs-context'),
  subsSearch: opts => ipcRenderer.invoke('subs-search', opts),
  subsApply: id => ipcRenderer.invoke('subs-apply', id),
  saveSettings: s => ipcRenderer.invoke('save-settings', s),
  // never routed through the engine — works even when it stops answering
  exit: () => ipcRenderer.invoke('player-exit'),
  resetPrefs: () => ipcRenderer.invoke('player-reset'),
  onProp: cb => ipcRenderer.on('mpv-prop', (_e, p) => cb(p)),
  onLink: cb => ipcRenderer.on('mpv-link', (_e, p) => cb(p)),
  onResumed: cb => ipcRenderer.on('resumed', (_e, p) => cb(p)),
  onSettings: cb => ipcRenderer.on('settings-changed', (_e, p) => cb(p)),
  onAutoSubs: cb => ipcRenderer.on('subs-auto', (_e, p) => cb(p))
});
