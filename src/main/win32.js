/* Thin Win32 layer (via koffi FFI) to manage the embedded mpv child window. */
const koffi = require('koffi');

const user32 = koffi.load('user32.dll');

const FindWindowExW = user32.func('__stdcall', 'FindWindowExW', 'uintptr_t',
  ['uintptr_t', 'uintptr_t', 'str16', 'str16']);
const SetWindowPos = user32.func('__stdcall', 'SetWindowPos', 'bool',
  ['uintptr_t', 'uintptr_t', 'int', 'int', 'int', 'int', 'uint']);
const ShowWindowFn = user32.func('__stdcall', 'ShowWindow', 'bool', ['uintptr_t', 'int']);
const GetClassNameW = user32.func('__stdcall', 'GetClassNameW', 'int', ['uintptr_t', 'void*', 'int']);

const SWP_NOACTIVATE = 0x0010;
const SWP_SHOWWINDOW = 0x0040;
const SWP_NOMOVE = 0x0002;
const SWP_NOSIZE = 0x0001;
// Post the request to the owning thread instead of blocking on it — but note
// this only takes effect when the two threads are on *different* input queues,
// which is exactly what an embedded child window is not. See below.
const SWP_ASYNCWINDOWPOS = 0x4000;
const HWND_TOP = 0n;

/*
 * Everything that touches mpv's window goes through the FFI thread pool rather
 * than the main thread.
 *
 * mpv's window is a child of ours, which attaches the two processes' input
 * queues. Once attached, SetWindowPos delivers WM_WINDOWPOSCHANGING to mpv's
 * thread synchronously and SWP_ASYNCWINDOWPOS no longer helps: if mpv's message
 * loop is wedged, the call never returns. It used to run on the Electron main
 * thread, so a stuck engine took the entire app down with it — no controls, no
 * Esc, no watchdog, nothing but the Windows "not responding" close prompt.
 *
 * Calling asynchronously means a wedged engine costs one pool thread and the
 * app keeps running, notices the stall, and restarts the engine. `busy` keeps
 * at most one call outstanding so a hang can never drain the pool; `queued`
 * holds the most recent geometry so nothing is lost while one is in flight.
 */
let busy = false;
let queued = null;

function flush() {
  if (busy || !queued) return;
  const job = queued;
  queued = null;
  busy = true;
  SetWindowPos.async(job.hwnd, HWND_TOP, job.x, job.y, job.w, job.h, job.flags, () => {
    busy = false;
    flush();
  });
}

function hwndFromBuffer(buf) {
  // Electron's getNativeWindowHandle() returns HWND as a native-endian buffer
  return buf.length === 8 ? buf.readBigUInt64LE(0) : BigInt(buf.readUInt32LE(0));
}

function classNameOf(hwnd) {
  const buf = Buffer.alloc(512);
  const n = GetClassNameW(hwnd, buf, 256);
  return n > 0 ? buf.toString('utf16le', 0, n * 2) : '';
}

function findMpvChild(parentHwnd) {
  // enumerate all direct children; mpv registers its window class as "mpv"
  let child = FindWindowExW(parentHwnd, 0n, null, null);
  let guard = 0;
  while (child && guard++ < 64) {
    if (classNameOf(child).toLowerCase() === 'mpv') return child;
    child = FindWindowExW(parentHwnd, child, null, null);
  }
  return null;
}

/* Size the child AND keep it above the Chromium render surface (z-order top). */
function fitChild(childHwnd, x, y, w, h) {
  queued = {
    hwnd: childHwnd, x, y, w, h,
    flags: SWP_NOACTIVATE | SWP_SHOWWINDOW | SWP_ASYNCWINDOWPOS
  };
  flush();
}

function raiseChild(childHwnd) {
  // never let a raise displace a pending resize — the resize already raises
  if (queued) return;
  queued = {
    hwnd: childHwnd, x: 0, y: 0, w: 0, h: 0,
    flags: SWP_NOACTIVATE | SWP_NOMOVE | SWP_NOSIZE | SWP_ASYNCWINDOWPOS
  };
  flush();
}

function showChild(childHwnd, visible) {
  ShowWindowFn.async(childHwnd, visible ? 5 /* SW_SHOW */ : 0 /* SW_HIDE */, () => {});
}

/* True while a window call is still outstanding — a strong hint that the engine
 * has stopped pumping messages. */
function isStuck() { return busy; }

module.exports = {
  hwndFromBuffer, findMpvChild, fitChild, raiseChild, showChild, classNameOf, isStuck
};
