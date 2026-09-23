#!/usr/bin/env bash
# Build libmpv + libass + libav* for Android, for Nova Player.
#
# This is a thin, PINNED wrapper around mpv-android's buildscripts (MIT).
# We deliberately do not use their buildscripts/include/ci.sh as-is because it
# (a) fetches mpv from master -- unpinned, and (b) hardcodes armv7l+arm64 as the
# CI arch set. Everything else is their machinery, unmodified.
#
# Pins (change these deliberately, and update native-libs.sha256 consumers):
#   MPV_ANDROID_TAG  -- mpv-android buildscripts snapshot
#   MPV_TAG          -- mpv version (matches the desktop's mpv 0.41)
#
# Requires: Linux (or WSL2). Produces jniLibs/<abi>/*.so + native-libs.sha256.
set -euo pipefail

MPV_ANDROID_TAG="${MPV_ANDROID_TAG:-2026-09-17}"
MPV_TAG="${MPV_TAG:-v0.41.0}"
ABIS="${ABIS:-arm64-v8a x86_64}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(dirname "$SCRIPT_DIR")"
WORK="$APP_DIR/.native-work"
OUT_JNI="$APP_DIR/app/src/main/jniLibs"

# Map Android ABI -> mpv-android's arch name (armv7l|arm64|x86|x86_64)
abi_to_arch() {
	case "$1" in
		arm64-v8a) echo arm64 ;;
		x86_64) echo x86_64 ;;
		armeabi-v7a) echo armv7l ;;
		x86) echo x86 ;;
		*) echo "unsupported ABI: $1" >&2; exit 1 ;;
	esac
}

# Map mpv-android arch name -> its prefix dir name (arm64|x86_64|armv7l|x86)
arch_to_prefix() {
	case "$1" in
		arm64) echo arm64 ;;
		x86_64) echo x86_64 ;;
		armv7l) echo armv7l ;;
		x86) echo x86 ;;
	esac
}

echo "==> work dir: $WORK"
mkdir -p "$WORK"
cd "$WORK"

# ---------------------------------------------------------------- mpv-android
if [ ! -d mpv-android ]; then
	echo "==> cloning mpv-android @ $MPV_ANDROID_TAG"
	git clone --depth=1 --branch "$MPV_ANDROID_TAG" \
		https://github.com/mpv-android/mpv-android.git
fi
cd mpv-android/buildscripts
. ./include/depinfo.sh

# Their scripts expect to run from buildscripts/ and manage sdk/, deps/, prefix/.
export WGET="wget --progress=bar:force"
export IN_CI=1

echo "==> installing SDK + NDK"
./include/download-sdk.sh

echo "==> fetching dependency sources"
./include/download-deps.sh

# ------------------------------------------------------------------- pin mpv
# ci.sh would fetch mpv from master. We want a pinned release instead.
echo "==> pinning mpv to $MPV_TAG (overrides upstream's unpinned master fetch)"
rm -rf deps/mpv
mkdir -p deps/mpv
git clone --depth=1 --branch "$MPV_TAG" \
	https://github.com/mpv-player/mpv.git deps/mpv

# -------------------------------------------------------------------- build
for abi in $ABIS; do
	arch="$(abi_to_arch "$abi")"
	prefix="$(arch_to_prefix "$arch")"
	echo "==> building deps + mpv for $abi (arch=$arch)"
	# --only-deps mpv: builds the whole transitive dep tree (static) for mpv
	./buildall.sh --only-deps mpv --arch "$arch"
	# -n mpv: build just mpv (libmpv.so), linking against that prefix
	./buildall.sh -n mpv --arch "$arch"

	src="prefix/$prefix/lib"
	[ -d "$src" ] || { echo "missing $src" >&2; exit 1; }
	mkdir -p "$OUT_JNI/$abi"
	# Deps are built default_library=static (see buildall.sh setup_prefix) and are
	# absorbed into libmpv.so. What is shared is ffmpeg's libav*.so, libass and
	# libmpv itself -- plus anything else the prefix happens to ship as .so.
	# Copying every .so is the robust choice; native-libs.sha256 records the set.
	find "$src" -maxdepth 1 -name '*.so' -exec cp -v {} "$OUT_JNI/$abi/" \;
	# Ship the C++ runtime used to link libmpv, not the JNI bridge's NDK version.
	case "$abi" in
		arm64-v8a) runtime_triple=aarch64-linux-android ;;
		x86_64) runtime_triple=x86_64-linux-android ;;
		armeabi-v7a) runtime_triple=arm-linux-androideabi ;;
		x86) runtime_triple=i686-linux-android ;;
	esac
	runtime=(sdk/android-ndk-"$v_ndk"/toolchains/llvm/prebuilt/*/sysroot/usr/lib/"$runtime_triple"/libc++_shared.so)
	[ "${#runtime[@]}" -eq 1 ] && [ -f "${runtime[0]}" ] || { echo "missing/ambiguous C++ runtime" >&2; exit 1; }
	cp -v "${runtime[0]}" "$OUT_JNI/$abi/libc++_shared.so"
done

# -------------------------------------------------------------- mpv headers
# ABI-independent public headers, consumed by app/src/main/jni/CMakeLists.txt.
echo "==> installing mpv headers"
mkdir -p "$APP_DIR/app/src/main/jni/include/mpv"
cp -v deps/mpv/include/mpv/client.h \
      deps/mpv/include/mpv/render.h \
      deps/mpv/include/mpv/render_gl.h \
      "$APP_DIR/app/src/main/jni/include/mpv/" 2>/dev/null || \
	cp -v deps/mpv/include/mpv/client.h "$APP_DIR/app/src/main/jni/include/mpv/"

# ----------------------------------------------------------------- manifest
echo "==> writing native-libs.sha256"
(
	cd "$OUT_JNI"
	find . -name '*.so' | sort | xargs sha256sum
) > "$OUT_JNI/native-libs.sha256"
cat "$OUT_JNI/native-libs.sha256"

echo "==> done. Native libs in $OUT_JNI"
