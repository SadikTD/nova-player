const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('player', {
  cmd: (...c) => ipcRenderer.invoke('player-cmd', c),
  get: name => ipcRenderer.invoke('player-get', name),
  init: () => ipcRenderer.invoke('player-init'),
  winCmd: c => ipcRenderer.invoke('win-cmd', c),
  dragStart: () => ipcRenderer.invoke('win-drag-start'),
  dragEnd: () => ipcRenderer.invoke('win-drag-end'),
  resizeStart: edge => ipcRenderer.invoke('win-resize-start', edge),
  resizeEnd: () => ipcRenderer.invoke('win-resize-end'),
  saveSetting: patch => ipcRenderer.invoke('player-save-setting', patch),
  loadSubtitle: () => ipcRenderer.invoke('player-load-sub'),
  onProp: cb => ipcRenderer.on('mpv-prop', (_e, p) => cb(p)),
  onLink: cb => ipcRenderer.on('mpv-link', (_e, p) => cb(p))
});
