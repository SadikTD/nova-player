# Nova Player for Android

The Android edition of Nova Player: Kotlin + Jetpack Compose on **libmpv**, with the
desktop's feature set rebuilt touch-first. Free, no ads, no accounts, no tracking.

**Download:** the signed APK is attached to the `android-vX.Y.Z` releases on this repository.
Privacy policy: [PRIVACY.md](PRIVACY.md). Play Store submission kit: [play/PLAY-STORE.md](play/PLAY-STORE.md).
Feature checklist against the desktop: [FEATURE-PARITY.md](FEATURE-PARITY.md).

## Layout

```
app/src/main/java/com/sadik/novaplayer/
  MainActivity.kt      activity, intents, pickers, delete/rename/share, PiP, keys
  NovaRuntime.kt       player state, mpv control, subtitles, fonts, volume
  NovaStore.kt         library (MediaStore + folder trees), history, playlists
  OnlineSubtitles.kt   OpenSubtitles search/download, SRT repair
  SubtitleJobs.kt      on-device subtitle sync (speech detection + alignment)
  PlaybackService.kt   background playback notification
  core/                JNI bridge to libmpv and the speech detector
  ui/                  Compose screens (library, player, panels, settings, selection)
app/src/main/jni/      nova_mpv.cpp bridge + vendored libfvad (voice activity detection)
play/                  Play Store listing text, form answers, graphics
scripts/               native build scripts, Windows build helpers
```

## Building

Prerequisites: JDK 17, Android SDK 36, NDK 27.0.12077973, CMake 3.22.1.

1. **Native libraries** (libmpv, FFmpeg, libass…) are not committed. Download
   `native-libs.tar.gz` from the latest `native-libs-*` release, extract it, and copy its
   `jniLibs/` to `app/src/main/jniLibs/` and its `include/` to `app/src/main/jni/include/`.
   Or rebuild them with the
   `native-libs` GitHub Actions workflow (`.github/workflows/native-libs.yml`), which
   compiles them from pinned upstream sources. Gradle verifies every library against
   `native-libs.sha256` before packaging.
2. Build:
   - `gradlew :app:assembleRelease` / `:app:bundleRelease` — the signed APK / Play bundle.
     Signing reads `%USERPROFILE%/NovaPlayer-signing/keystore.properties`
     (or the path in `NOVA_SIGNING_PROPERTIES`); without it the release is unsigned.
   - `scripts/build-fast.ps1` — release-speed build installed as `com.sadik.novaplayer.debug`
     next to the real app, for testing on a phone.
   - `scripts/build-debug.ps1` — debuggable build.
3. Unit tests: `gradlew :app:testDebugUnitTest`.

**Keep the signing key safe.** Every update must be signed with the same key, and it must
never be committed. Back up the whole `NovaPlayer-signing` folder.

## License

Nova's own source files are MIT, like the desktop app. The Android app links **libmpv**
(GPL-2.0-or-later) and FFmpeg into the same process, so the APK as distributed is a
GPL-2.0-or-later combined work. This repository, together with the `native-libs` workflow and
its pinned upstream versions, is the corresponding source. libfvad (BSD-3-Clause) is vendored
in `app/src/main/jni/third_party/fvad` with its license.
