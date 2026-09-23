#!/usr/bin/env bash
# Cross-compile alass-cli for Android, for Nova Player's automatic subtitle sync.
#
# alass 2.0.0 (https://github.com/kaegi/alass) is Rust + a C voice-activity
# module (webrtc-vad), GPL-3.0-or-later. We ship it as a SEPARATE EXECUTABLE so
# the GPL-3.0 work stays an aggregate alongside the GPL-2.0-or-later app+libmpv
# combination, instead of dragging the whole distribution to GPL-3.0.
#
# Android only lets apps execve() a file in nativeLibraryDir when it is named
# like a shared library (lib*.so) and the .so has actually been extracted to
# disk (packaging.jniLibs.useLegacyPackaging = true). So the executable is
# named libalass-cli.so.
#
# Nova calls it in SUBTITLE-TO-SUBTITLE mode only (no video reference), which
# means alass's ffmpeg/ffprobe code path is never taken -- no ffmpeg binaries
# ship in the APK. The audio/speech step is done by our own webrtc-vad JNI shim.
#
# Requires: Linux (or WSL2) + Rust + cargo-ndk.
set -euo pipefail

ALASS_TAG="${ALASS_TAG:-v2.0.0}"
ABIS="${ABIS:-arm64-v8a x86_64}"
# Android API level for the NDK target. mpv-android uses 23; keep in sync.
API_LEVEL="${API_LEVEL:-23}"
# Apply Android's page-size alignment to the separate executable too.
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(dirname "$SCRIPT_DIR")"
WORK="$APP_DIR/.native-work"
OUT_JNI="$APP_DIR/app/src/main/jniLibs"

command -v cargo >/dev/null || { echo "cargo (Rust) is required: https://rustup.rs" >&2; exit 1; }
command -v cargo-ndk >/dev/null || {
	echo "cargo-ndk is required: cargo install cargo-ndk" >&2
	exit 1
}

echo "==> work dir: $WORK"
mkdir -p "$WORK"
cd "$WORK"

if [ ! -d alass ]; then
	echo "==> cloning alass @ $ALASS_TAG"
	git clone --depth=1 --branch "$ALASS_TAG" https://github.com/kaegi/alass.git
fi
cd alass

# Build alass-cli for each ABI. The default feature is "ffmpeg-binary", which
# shells out to ffprobe/ffmpeg. That path is unreachable in subtitle-to-subtitle
# mode, so we keep the default feature (the alternative "ffmpeg-library" feature
# is commented out upstream and pins a 2019 ffmpeg binding -- do not use it).
for abi in $ABIS; do
	echo "==> building alass-cli for $abi"
	cargo ndk -t "$abi" -p "$API_LEVEL" build --release -p alass-cli

	# cargo-ndk places the binary at target/<triple>/release/alass-cli
	triple=""
	case "$abi" in
		arm64-v8a) triple=aarch64-linux-android ;;
		x86_64) triple=x86_64-linux-android ;;
		armeabi-v7a) triple=armv7a-linux-androideabi ;;
		x86) triple=i686-linux-android ;;
		*) echo "unsupported ABI: $abi" >&2; exit 1 ;;
	esac
	bin="target/$triple/release/alass-cli"
	[ -f "$bin" ] || { echo "missing $bin" >&2; exit 1; }

	mkdir -p "$OUT_JNI/$abi"
	# Rename so Android's execve() filter accepts it (lib*.so naming rule).
	cp -v "$bin" "$OUT_JNI/$abi/libalass-cli.so"
done

# Update the manifest with the new binaries.
echo "==> writing native-libs.sha256"
(
	cd "$OUT_JNI"
	find . -name '*.so' | sort | xargs sha256sum
) > "$OUT_JNI/native-libs.sha256"
cat "$OUT_JNI/native-libs.sha256"

echo "==> done. libalass-cli.so in $OUT_JNI"
