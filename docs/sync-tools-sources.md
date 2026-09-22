# Subtitle synchronization tools

Nova invokes these tools as separate local processes. They are not linked into Nova's MIT-licensed JavaScript.

- alass 2.0.0, GPL-3.0-or-later. Source and pinned build: https://github.com/kaegi/alass/tree/v2.0.0 . Windows package: https://github.com/kaegi/alass/releases/tag/v2.0.0 . License is included as ALASS-LICENSE.txt.
- FFmpeg 8.1.3, LGPL shared build. FFmpeg source: https://github.com/FFmpeg/FFmpeg/tree/n8.1.3 . Build configuration, dependency recipes and toolchain: https://github.com/BtbN/FFmpeg-Builds . Exact binary release: https://github.com/BtbN/FFmpeg-Builds/releases/tag/autobuild-2026-09-21-13-55 . License is included as LICENSE.txt. The shared libraries remain replaceable.
- Original artifact names and SHA-256 checksums are pinned in scripts/setup-sync.js in the Nova Player source. The original downloaded alass package's older FFmpeg is not used.

The alass source includes Cargo.lock and the build instructions for its dependencies, including speech activity detection. The FFmpeg build repository includes source URLs and build recipes for the libraries in this LGPL distribution. Corresponding source can be obtained at the links above; report an unavailable source link through Nova Player's issue tracker so it can be restored.
