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
const HWND_TOP = 0n;

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
  SetWindowPos(childHwnd, HWND_TOP, x, y, w, h, SWP_NOACTIVATE | SWP_SHOWWINDOW);
}

function raiseChild(childHwnd) {
  SetWindowPos(childHwnd, HWND_TOP, 0, 0, 0, 0, SWP_NOACTIVATE | SWP_NOMOVE | SWP_NOSIZE);
}

function showChild(childHwnd, visible) {
  ShowWindowFn(childHwnd, visible ? 5 /* SW_SHOW */ : 0 /* SW_HIDE */);
}

module.exports = { hwndFromBuffer, findMpvChild, fitChild, raiseChild, showChild, classNameOf };
