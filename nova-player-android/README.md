# Nova Player for Android

Status: **development prototype, not a release-ready Android app**. A debug APK
builds and passes package checks; MKV picture and sound are confirmed on the
owner's Redmi K80 running Android 16. No
Google Play bundle has been produced. The Windows application remains in
the parent project. The Android application will be free and ad-free, with no
accounts or tracking, as confirmed by the owner.

## Required feature parity

The acceptance checklist is [FEATURE-PARITY.md](FEATURE-PARITY.md). A feature is
complete only after it works on a device; the presence of an mpv command or a UI
button is not sufficient. Do not describe the prototype as having full parity.

## Build prerequisites

- JDK 17, Android SDK 36, Android NDK and CMake 3.22.1.
- Linux or WSL for the native dependencies, plus Rust/cargo-ndk for alass.
- Native libraries for arm64-v8a and x86_64, their checksum manifest and mpv
  headers. `scripts/build-native.sh` and `scripts/build-alass.sh` produce these.
- The included Gradle 8.13 wrapper downloads the pinned distribution and
  verifies its SHA-256 checksum. Its wrapper JAR was also verified against
  Gradle's published checksum when added.

After installing the prerequisites and building native libraries, run
`./gradlew :app:assembleDebug` from this directory (Windows: `gradlew.bat`).
On this Windows machine, `scripts/build-debug.ps1` locates the portable JDK
installed under `%LOCALAPPDATA%/NovaPlayerBuild` and the SDK under
`%LOCALAPPDATA%/Android/Sdk` without changing system-wide environment settings.

The native build is a prerequisite, not an optional fallback. The current
launcher is a playback experiment with a document picker and descriptor-based
file access. It still needs broader device validation and the actual library/player
screens.

First physical test device: Xiaomi Redmi K80, Android 16. A successful source
compilation is not evidence of playback or subtitle feature parity.

## Current state (2026-09-23, evening)

The prototype launcher was replaced by the full app UI in `app/src/main/java/com/sadik/novaplayer/ui/`
(Theme, Components, LibraryScreen, PlayerScreen, PlayerPanels, SettingsScreen), styled after
the desktop's tokens and six accents. See FEATURE-PARITY.md for what is verified on the phone.
Subtitle fonts: libmpv here has no fontconfig/fallback, so Nova copies a curated set of the
phone's Noto/Roboto fonts to `filesDir/fonts` and picks the family per subtitle track
(`NovaRuntime.fitSubFont`). Without this, libass drops spaces and non-Latin text is boxes.

## Verified earlier (2026-09-23)

- Playback test 2 (`1.0.1-prototype`, version code 2) was installed over USB on
  the Redmi K80. A generated H.264/AAC MKV displayed visible video; the device
  log reported MediaCodec hardware decoding and AudioTrack output. The owner
  then opened the same MKV that failed in test 1 and confirmed both picture and
  sound work. This does not establish full format/subtitle feature parity.
- The failed-opening fix selects Android's EGL/OpenGL ES renderer and audio
  output explicitly, passes provider descriptors as `fd://` instead of
  reopening `/proc/self/fd` paths, supplies surface dimensions and restores
  video output after the file picker. Startup options retain an idle engine.
- Native load errors and command failures now reach the UI. A 20-second load
  timeout replaces indefinite "Opening video"; Copy diagnostics provides local
  error details. No diagnostics are sent automatically.

- `:app:testDebugUnitTest`: Kotlin/Java sources compile; six subtitle timing
  tests pass, with no failures or skipped tests.
- The C++ JNI bridge passes Android arm64 Clang syntax checking against mpv
  0.41.0 headers using NDK 27.0.12077973. This does not verify linking/playback.
- Manifest XML, build-helper PowerShell syntax and both native-build shell
  scripts pass syntax checks.
- Gradle wrapper, Gradle distribution, portable JDK and downloaded SDK tools
  were checked against their publishers' checksums.
- `:app:assembleDebug` succeeds for arm64-v8a and x86_64. The resulting test APK
  passes signing verification, ZIP alignment and 16 KB ELF alignment checks
  for all 22 packaged native libraries. Its C++ runtime exports were checked
  against upstream library imports (an initial incompatible runtime was fixed).
- Release builds reject the temporary debug-native provenance marker. This
  test payload cannot silently become the production app bundle.

## First phone test

The build is `app/build/outputs/apk/debug/app-debug.apk`, about 42 MB. It installs
as **Nova Player Test**, package `com.sadik.novaplayer.debug`, using debug signing.
It is a minimal test screen, not the final mobile design.

1. Install on the Redmi K80 running Android 16.
2. Choose **Open video**, select a local MP4 or MKV, and check sound and picture.
3. Try a HEVC/10-bit file and note the displayed decoder.
4. Choose **Load subtitle** and select SRT or styled ASS; check visible text.
5. Report installation errors, crashes, blank video, missing audio/subtitles,
   and the displayed decoder. Device logs can be inspected over authorized ADB.

The temporary native libraries come from mpv-android's official
[2026-09-17 release](https://github.com/mpv-android/mpv-android/releases/tag/2026-09-17),
asset `app-default-universal-release.apk`, verified against the GitHub asset
SHA-256 `c3b505e45b919b767b9867e7f0d16fd823d77bc7c9a6e6b181b80ea60c018a70`.
Only media libraries and their matching C++ runtime were extracted; upstream
application code/UI and JNI bridge were not packaged. Nova's bridge is built
from this project's sources. The local `jniLibs/debug-origin.json` records this
provenance. Alass and speech-analysis JNI are absent, so automatic sync is not
available in this test APK. Build and validate the complete source payload and
complete the license/source distribution gate before replacing this provenance
marker for a release.

Playback test 2 APK SHA-256:
`4e60a826826a1fb25cae197d49dedd16502c63bfbdea95ea971a4dc67b1fb49b`.

## Release gates

1. Build and validate native playback on the owner's Android phone and an
   emulator; verify HEVC, embedded ASS subtitles and hardware decoding.
2. Implement and test every feature in the parity checklist.
3. Verify lifecycle, storage permissions, background playback, audio focus,
   interruptions, Bluetooth, rotation, picture-in-picture and accessibility.
4. Test 16 KB memory-page compatibility for every bundled native library.
5. Produce a signed Android App Bundle; keep the upload key and passwords out
   of source control. Configure Play App Signing in the owner's account.
6. Complete the privacy policy, Data safety form, content rating, screenshots,
   listing, third-party notices and corresponding-source distribution.
7. Run Play internal testing and resolve device/pre-launch report failures
   before production publication. Any account-specific testing gates must be
   checked in Play Console.

Official requirements checked on 2026-09-23:

- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/guide/practices/page-sizes

Do not publish the experimental build or claim store approval before these
gates are satisfied.
