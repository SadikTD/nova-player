const { contextBridge, ipcRenderer, webUtils } = require('electron');

const EVENTS = [
  'library-updated', 'item-updated', 'progress', 'playback-started',
  'playback-ended', 'play-error', 'win-state', 'update-state', 'settings-changed'
];

contextBridge.exposeInMainWorld('nova', {
  getState: () => ipcRenderer.invoke('get-state'),
  addFolder: () => ipcRenderer.invoke('add-folder'),
  removeFolder: f => ipcRenderer.invoke('remove-folder', f),
  rescan: () => ipcRenderer.invoke('rescan'),
  play: (files, startIndex) => ipcRenderer.invoke('play', { files, startIndex }),
  playUrl: url => ipcRenderer.invoke('play-url', url),
  openFileDialog: () => ipcRenderer.invoke('open-file-dialog'),
  saveSettings: s => ipcRenderer.invoke('save-settings', s),
  resetPlayerPrefs: () => ipcRenderer.invoke('reset-player-prefs'),
  playlistCreate: name => ipcRenderer.invoke('playlist-create', name),
  playlistDelete: id => ipcRenderer.invoke('playlist-delete', id),
  playlistAdd: (id, paths) => ipcRenderer.invoke('playlist-add', { id, paths }),
  playlistRemoveItem: (id, path) => ipcRenderer.invoke('playlist-remove-item', { id, path }),
  clearHistory: () => ipcRenderer.invoke('clear-history'),
  removeProgress: p => ipcRenderer.invoke('remove-progress', p),
  showInFolder: p => ipcRenderer.invoke('show-in-folder', p),
  winCmd: c => ipcRenderer.invoke('win-cmd', c),
  mpvKey: name => ipcRenderer.invoke('mpv-key', name),
  playerExit: () => ipcRenderer.invoke('player-exit'),
  resizeStart: edge => ipcRenderer.invoke('win-resize-start', edge),
  resizeEnd: () => ipcRenderer.invoke('win-resize-end'),
  appInfo: () => ipcRenderer.invoke('app-info'),
  updateState: () => ipcRenderer.invoke('update-state'),
  updateCheck: () => ipcRenderer.invoke('update-check'),
  pathForFile: f => webUtils.getPathForFile(f),
  on: (event, cb) => {
    if (!EVENTS.includes(event)) return;
    ipcRenderer.on(event, (_e, payload) => cb(payload));
  }
});
