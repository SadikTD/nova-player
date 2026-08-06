# Third-party notices

Nova Player's own source code is released under the [MIT licence](LICENSE).
The released **installer** and **portable archive** bundle third-party software,
listed below with its licence and source.

---

## mpv

Nova Player uses [mpv](https://mpv.io) as its playback engine. `mpv.exe` is shipped
inside the installer and portable archive, and runs as a **separate process** that
Nova Player controls over a JSON IPC pipe.

- **Project:** https://mpv.io
- **Source:** https://github.com/mpv-player/mpv
- **Licence:** GNU General Public License version 2 or later (GPLv2+).
  mpv is a combination of GPL and LGPL code; the Windows builds distributed here
  are GPL-licensed. Full licence text: https://github.com/mpv-player/mpv/blob/master/LICENSE.GPL

The specific Windows build bundled with Nova Player is produced by
[zhongfly/mpv-winbuild](https://github.com/zhongfly/mpv-winbuild), whose repository
contains the complete build scripts and toolchain used to compile it:

- **Build scripts / toolchain:** https://github.com/zhongfly/mpv-winbuild
- **Binary releases:** https://github.com/zhongfly/mpv-winbuild/releases

The exact binary shipped in a Nova Player release can be reproduced by running
`npm run setup` in this repository, which downloads it directly from the release
page linked above.

mpv statically links [FFmpeg](https://ffmpeg.org) and a number of other libraries
(libass, libplacebo, zlib and others). Their licences and sources are documented in
the mpv and mpv-winbuild repositories linked above.

### Obtaining the source

Anyone who receives a Nova Player release is entitled under the GPL to the
corresponding source of the bundled mpv build. It is available from the mpv and
mpv-winbuild repositories linked above. If those links are ever unreachable, open
an issue on this repository and a copy will be provided.

---

## Electron

The application shell is [Electron](https://www.electronjs.org/), which bundles
Chromium and Node.js.

- **Source:** https://github.com/electron/electron
- **Licence:** MIT (Electron), with Chromium and its own dependencies under their
  respective licences — the full text is included as `LICENSES.chromium.html`
  inside the installed application folder.

---

## koffi

Used to call Windows APIs for managing the embedded video surface.

- **Source:** https://github.com/Koromix/koffi
- **Licence:** MIT
