Nova Player 1.3.1 fixes episode resume and improves update installation.

- Next episode starts at 0:00 instead of inheriting the previous episode's resume time.
- Progress is saved from a coherent, file-specific engine snapshot. Late events and rapid playlist changes cannot mark another episode watched.
- Opening a video directly still resumes its own position; Start over clears its previous resume point.
- A top notice in the library and playback controls shows new versions and download progress, with Restart & update when ready.
- Updates stop the playback engine and save progress before launching the installer, and explicitly target the running installation.
- Failed installation attempts are reported on the next launch, with Retry and Download installer actions. Diagnostic details are written to nova-updates.log.
- Portable ZIP copies offer a new download instead of attempting to update a different installed copy.

If your older version downloads updates but stays unchanged, run the 1.3.1 Setup EXE manually once to receive the updater fixes. Your library and settings are retained.

Validation: actual bundled mpv test for episode 5 at 20:15 of 24:25 → episode 6 at zero; resume/progress race regressions; updater lifecycle and failure tests; Electron UI tests in both windows, including minimum window size; subtitle regressions; package and update-metadata integrity checks. Windows installer behavior on every machine cannot be guaranteed; installation failures now have a visible recovery path.
