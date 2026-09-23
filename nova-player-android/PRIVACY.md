# Nova Player for Android — Privacy Policy

_Last updated: 24 September 2026_

Nova Player is a video player made by Sadik Hossain. It has no accounts, no ads,
no analytics and no tracking. This page explains the little data the app handles.

## What stays on your phone

Everything Nova keeps is stored only on your device, inside the app's private storage:

- your library (the list of videos Nova found, their titles, sizes and folders)
- resume points, watch history and playlists
- thumbnails, downloaded subtitles and synced subtitle copies
- your settings

None of this is uploaded anywhere. Uninstalling the app, or clearing its storage in
Android's settings, deletes it.

## Permissions

- **Videos** (`READ_MEDIA_VIDEO` / storage): to find and play the videos on your phone.
- **Media management** (optional, Android 12+): only if you turn on *Quick delete*, so
  deleting a video needs just Nova's confirmation.
- **Notifications and background playback**: to show playback controls while a video plays
  in the background or on the lock screen.
- **Internet**: only for online subtitles and for streams you open yourself.

## Online subtitles (OpenSubtitles)

When a video has no subtitles, Nova can look for them on **OpenSubtitles.org**
(`rest.opensubtitles.org`). This is on by default and can be switched off in
**Settings → Subtitles → Online subtitles** (or *Fetch automatically*).

For each search Nova sends, over an encrypted HTTPS connection:

- the title, season and episode read from the file name, and your preferred languages
- the file's size and an OpenSubtitles "movie hash" (a fingerprint calculated from the
  first and last 64 KB of the file, used to find subtitles that match your exact copy)

Your video itself is never uploaded. OpenSubtitles' handling of these requests is covered
by its own privacy policy: https://www.opensubtitles.org/en/privacy

## Network streams

If you open a stream address (HTTP, HLS, RTSP, SMB, FTP…), Nova connects directly to that
address to play it. Nova does not send the address anywhere else.

## Subtitle sync

Automatic subtitle sync listens to the video's audio **on your phone**. No audio or
subtitle leaves the device.

## Children

Nova Player is not directed at children and does not knowingly collect data from anyone.

## Changes and contact

Changes to this policy are published in this file on GitHub, with the date above.
Questions: open an issue at https://github.com/SadikTD/nova-player/issues
