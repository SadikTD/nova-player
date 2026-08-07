const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('player', {
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
  // never routed through the engine — works even when it stops answering
  exit: () => ipcRenderer.invoke('player-exit'),
  resetPrefs: () => ipcRenderer.invoke('player-reset'),
  onProp: cb => ipcRenderer.on('mpv-prop', (_e, p) => cb(p)),
  onLink: cb => ipcRenderer.on('mpv-link', (_e, p) => cb(p)),
  onResumed: cb => ipcRenderer.on('resumed', (_e, p) => cb(p)),
  onSettings: cb => ipcRenderer.on('settings-changed', (_e, p) => cb(p))
});
