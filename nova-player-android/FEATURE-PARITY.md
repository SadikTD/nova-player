# Android parity acceptance checklist

Inventory derived from the desktop README, preload APIs and stored settings.
Unchecked items are not yet verified on Android. Desktop references are in
`../src/main` and `../src/renderer`.

Device evidence (2026-09-23): playback test 2 renders an H.264/AAC MKV using
MediaCodec and AudioTrack on the Redmi K80 (Android 16). The owner also confirmed
picture and sound for the MKV that failed in test 1, opened through the document
picker. Broader format, Open with, subtitle and lifecycle acceptance stays open.

Device evidence (2026-09-23, Redmi K80): library scan with
real folder grouping and file dates, thumbnails, natural episode order, folder
queue with automatic advancement (E01→E02→E03→E10), resume at ≥10 s with a
"Start over" action, chapter-segmented seek bar, double-tap seek, swipe seek,
volume swipe into 150 % boost, hold-for-2×, auto portrait/landscape per video,
picture-in-picture, media notification, embedded and "open with" external
subtitles, Bengali/Unicode subtitle rendering (font fix), OpenSubtitles search
and download, and automatic sync of a mis-timed SRT (−5.3 s found, applied,
original kept). Built but not yet exercised on the phone: network streams,
HEVC/10-bit, frame stepping, screenshots, sleep timer, media info, playlists
creation/reorder, A–B loop, reference-subtitle sync, batch subtitle download.

## Playback

- [x] Local video opening through Android document providers and Open with.
- [ ] MKV, MP4, AVI, HEVC/10-bit, VP9 and the other desktop-supported formats.
- [ ] Hardware decoding, software fallback, selectable quality settings.
- [ ] Network URLs and HLS streams, useful errors and buffering feedback.
- [x] Play/pause, seek slider, chapter markers and preview times.
- [x] Previous/next, configurable skip intervals and episode queue.
- [ ] Speed presets 0.25–3x, fine control and temporary press-and-hold boost.
- [ ] Volume up to 200%, mute, brightness and touch gestures.
- [ ] Audio track/language selection and ±600-second audio timing controls.
- [ ] Aspect ratio, zoom, rotation, loop and A–B repeat.
- [ ] Frame stepping, screenshots saved through Android MediaStore.
- [ ] Sleep timer presets and stop at the end of the current video.
- [x] Next-up card and automatic advancement starting the next item at zero.
- [ ] Media information, decoder status and dropped-frame statistics.
- [ ] Persisted player preferences and one-action reset to defaults.
- [x] Engine failure feedback and recovery without trapping navigation.

## Library and persistence

- [ ] User-selected folders, recursive discovery, refresh and folder removal.
- [ ] Generated thumbnails, folder grouping, search and sorting.
- [x] Continue watching, last-played shortcut and crash-safe per-item progress.
- [ ] Resume saved audio/subtitle selections for each individual video.
- [ ] Start over reliably clears the video's previous resume position.
- [x] Watch history and clear-history action.
- [ ] Playlist creation/deletion and adding/removing/reordering queue items.
- [x] Six accent colors and all applicable desktop settings.
- [ ] Revoked storage grants and moved/deleted files have recoverable errors.

## Subtitles

- [x] Embedded text/image subtitle selection and external SRT/ASS/SSA loading.
- [ ] Styled ASS rendering, Unicode and preferred-language handling.
- [ ] Delay input/slider/buttons, speed correction, size and vertical position.
- [x] Search/download matching subtitles using filename and video hash.
- [ ] Automatic fetching, exact-match preference and folder batch downloads.
- [x] Download cancellation, UTF-8 storage and the desktop cue-cleaning behavior.
- [ ] Safe storage of subtitles alongside accessible videos or in app storage.
- [ ] Automatic sync: smart, gentle and offset-only modes.
- [ ] Audio-derived speech timing and subtitle-file reference input.
- [ ] Background progress, cancellation and switching-video cancellation.
- [ ] Timing drift/cut correction, overlap estimate and uncertain-result review.
- [ ] Apply corrected copy, preserve/restore original and reuse approved results.
- [ ] Download/local-open opt-ins and exact-match analysis skipping.
- [ ] Explicit limits for embedded/image subtitles and network-stream auto-sync.

## Android equivalents and additions

- [x] Touch-first controls replace desktop mouse interactions.
- [ ] External keyboard shortcuts where Android delivers the keys.
- [x] Fullscreen/rotation replace desktop maximize/window sizing.
- [x] Picture-in-picture provides the mobile floating-player experience.
- [x] Background playback service, media notification and lockscreen controls.
- [ ] Audio focus, incoming calls, unplugged headphones and Bluetooth controls.
- [ ] Google Play distributes updates instead of the Windows self-updater.
- [ ] Android file picker/share/export replace Windows Explorer actions.
- [ ] Screen stays awake while watching; display/lifecycle changes preserve state.
- [ ] TalkBack labels, scalable text, usable touch targets and tablet layout.

## Release acceptance

- [ ] All features above demonstrated on the owner's phone.
- [ ] arm64 and emulator builds pass; every native library supports 16 KB pages.
- [ ] No ads, user accounts, analytics or tracking SDKs.
- [ ] Privacy text accurately discloses subtitle-search metadata leaving the device.
- [ ] Native component licenses and corresponding sources included/distributed.
- [ ] Signed AAB validated by Play internal testing; no debug signing in release.
- [ ] Store listing, screenshots and privacy/Data safety declarations completed.

Publication is a separate final action after a tested release is reviewable.
