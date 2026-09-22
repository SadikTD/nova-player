Nova Player 1.4.0 adds on-device automatic subtitle synchronization.

Player: Subtitles → Auto-sync now, or Options & results.

- Analyze the selected audio track across the video and correct subtitle offsets, drift, and timing changes after cuts.
- Smart, Gentle, and Offset-only correction modes.
- Optional correctly timed reference subtitle instead of audio analysis.
- Progress/status badge, background processing, cancellation, result review, and Restore original.
- Saved corrections for faster reuse on subsequent playback, including reference-based corrections.
- Automatically reset manual subtitle delay/speed when applying a correction; restore those values when undoing it.

Settings: Automatic subtitle sync.

- Optional automatic syncing after subtitle downloads (exact-match downloads are skipped).
- Optional syncing of local subtitles when a video opens.
- Default mode, automatic application of results that pass the timing checks, saved-result reuse, and access to saved files.

Original subtitles are never overwritten. Switching videos cancels active analysis. All processing is local; there is no account, upload, or API fee. Automatic analysis is opt-in. Uncertain results wait for review.

Supported: external SRT, ASS and SSA with local videos. Embedded/image subtitles and streaming URLs are not supported by auto-sync yet. Speech overlap is an estimate, not a guarantee; subtitles for a different episode may still need replacing.

Validation: real alass reference alignment with +2s/+7s segmented offsets; synthetic-speech audio test with +2s/+5s offsets; output/content checks; apply/undo, cancellation, episode switches and cache reuse; Electron player/settings interaction and small-window layout tests; existing resume/updater/subtitle-timing regressions.

Installer and portable ZIP include alass 2.0.0 and FFmpeg 8.1.3 LGPL shared tools with their licenses and source provenance.
