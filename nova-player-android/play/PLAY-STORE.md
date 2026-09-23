# Publishing Nova Player on Google Play

Everything to paste into Play Console, in the order the Console asks for it.
Graphics are in this folder (`icon-512.png`, `feature-graphic.png`, `screenshots/`).

---

## 0. Before you start

- **Upload file:** `dist/android/Nova-Player-Android-<version>.aab` on this PC (or build with
  `gradlew :app:bundleRelease`). Play takes the AAB, not the APK.
- **Signing:** when Console asks about *Play App Signing*, choose
  **"Use my own key" → "Export and upload a key from Java keystore"** and follow its
  PEPK instructions with `%USERPROFILE%\NovaPlayer-signing\nova-release.jks`
  (alias `nova`, password in `keystore.properties`). This keeps the Play version and the
  GitHub APK on the same signature, so either can update the other.
- **Testing rule for new personal accounts:** before *Production* is unlocked you must run a
  **closed test with at least 12 testers opted in for 14 continuous days**.
  Plan: create the closed-testing track, add ~15 friends' Gmail addresses (a Google Group
  works too), share the opt-in link, and keep them opted in for the full 14 days.

---

## 1. Create app

| Field | Answer |
|---|---|
| App name | Nova Player |
| Default language | English (United States) – en-US |
| App or game | App |
| Free or paid | Free |
| Declarations | Tick both (Developer Program Policies, US export laws) |

## 2. Store listing

**App name (≤30):**
```
Nova Player
```

**Short description (≤80):**
```
Every format, auto subtitles, gestures and a beautiful library. No ads, ever.
```

**Full description (≤4000):**
```
Nova Player is a fast, beautiful video player for the files on your phone — with no ads, no accounts and no tracking.

PLAYS EVERYTHING
• MKV, MP4, AVI, MOV, WebM, TS and more, powered by the mpv engine
• HEVC/H.265, AV1, VP9, H.264, HDR10, Dolby Digital/Plus, DTS, TrueHD
• Hardware decoding, with a software fallback for difficult files
• Multiple audio tracks, full ASS/SSA subtitle styling, chapters

SUBTITLES THAT JUST WORK
• Automatically finds subtitles online when a video has none
• One-tap search in your languages, ranked for your exact copy
• Automatic sync: Nova listens to the dialogue on your phone and fixes badly timed subtitles by itself
• Size, outline, position and timing controls

A LIBRARY THAT ORGANISES ITSELF
• Your folders exactly as they are on your phone — seasons stay inside their show
• New downloads appear automatically, marked NEW
• Continue watching, resume points, watch history and playlists
• Multi-select: play, add to a playlist, mark watched, share or delete many videos at once
• Storage clean-up: free space by clearing episodes you've finished
• Rename, share and see full file details (codecs, bitrate, audio tracks)

GESTURES LIKE MX
• Swipe to seek, adjust brightness and volume (with boost above 100%)
• Double-tap to skip, pinch to zoom, press and hold for 2× speed
• Lock screen, playback speed, sleep timer, A–B repeat, screenshots
• Picture-in-picture and background playback

PRIVATE BY DESIGN
• No ads, no analytics, no account
• Everything stays on your phone; subtitle sync runs entirely on-device

Nova Player is open source (GPL). Source code: https://github.com/SadikTD/nova-player

Screenshots show "Sintel" © Blender Foundation | durian.blender.org, licensed CC BY 3.0.
```

**Graphics:** app icon `icon-512.png` · feature graphic `feature-graphic.png` ·
phone screenshots `screenshots/1-home.png` … `6-subtitle-tracks.png` (upload all six).
They show *Sintel* (© Blender Foundation, CC BY 3.0) and generated fractal clips — never
use shots of your own library.

**Category:** Video Players & Editors · **Tags:** Video player, Media player
**Contact email:** required by Play — use an address you're happy to show publicly
(it appears on the listing). **Website:** `https://github.com/SadikTD/nova-player`

## 3. App content (Policy → App content)

**Privacy policy URL:**
```
https://github.com/SadikTD/nova-player/blob/main/nova-player-android/PRIVACY.md
```

**App access:** All functionality is available without special access (no login).

**Ads:** No, my app does not contain ads.

**Content rating** (IARC questionnaire): Category **"All other app types"**.
Answer **No** to violence, sexuality, language, controlled substances, gambling,
user-to-user communication, sharing location, purchases. (It plays the user's own files;
it has no built-in content.) Expected result: Everyone / PEGI 3.

**Target audience:** 18 and over (keeps the app out of the Families policy;
it is a general utility).

**News app:** No · **COVID-19:** No · **Government app:** No ·
**Financial features:** None · **Health:** None.

**Data safety:**

| Question | Answer |
|---|---|
| Does your app collect or share any required user data types? | **Yes** |
| Is all data encrypted in transit? | **Yes** |
| Do you provide a way for users to request data deletion? | **No** (no account; nothing is stored off-device) |

Data types — tick only **Files and docs**:

| | |
|---|---|
| Collected | Yes |
| Shared | Yes (sent to OpenSubtitles.org for subtitle search) |
| Processed ephemerally | Yes |
| Required or optional | Optional (users can turn off online subtitles) |
| Purposes | App functionality |

(What is sent: title/season/episode from the file name, file size and the OpenSubtitles
hash — see PRIVACY.md. No personal info, location, contacts, identifiers or analytics.)

**Foreground service permissions** (declaration form for `FOREGROUND_SERVICE_MEDIA_PLAYBACK`):

- Task type: **Media playback**
- Description:
  ```
  Nova Player continues playing the user's video (as audio) when the user leaves the app
  or turns off the screen, and shows media controls in the notification and on the lock
  screen. Playback always starts from a user action and stops when the user pauses or
  closes the player.
  ```
- Video link: a 30-second screen recording (unlisted YouTube/Drive) showing a video
  playing → pressing Home → playback continuing with the notification controls.

**Other permissions:** `MANAGE_MEDIA` needs no Console declaration. No SMS, call log,
location, accessibility or all-files-access permissions are used.

## 4. Release

1. **Testing → Closed testing → Create track**, upload the `.aab`.
2. Release name `1.1.1 (4)`. Release notes:
   ```
   First release of Nova Player for Android.
   ```
3. Add testers, roll out, share the opt-in link. Wait 14 days with ≥12 testers.
4. **Production → Apply for production access**, answer the short questionnaire about the
   test, then create the production release from the same AAB.

**Every update:** bump `versionCode` (and `versionName`) in `app/build.gradle.kts`,
build with the same key, upload the new AAB.

## Known review risk

Online subtitles use OpenSubtitles' legacy REST API with its public "TemporaryUserAgent".
If a reviewer or OpenSubtitles objects, switch to the official opensubtitles.com API with a
registered (free) API key and user agent.
