#!/usr/bin/env bash
# Build AdGuard's TrustTunnel Android adapter (an .aar) at core/REVISION with the Tunnel split hook, arm64 only.
# Needs: Python 3.13 + conan 2, JDK 17, Android SDK with NDK 29.0.14206865, Rust (cargo-ndk), Go, CMake >= 3.24, Ninja.
# Output: core/out-android/trusttunnel.aar
set -euo pipefail
CORE="$(cd "$(dirname "$0")" && pwd)"
SRC="$CORE/src-android"
REV="$(tr -d '[:space:]' < "$CORE/REVISION")"

if [ ! -d "$SRC/.git" ]; then
  git init -q "$SRC"
  git -C "$SRC" remote add origin https://github.com/TrustTunnel/TrustTunnelClient.git
fi
git -C "$SRC" fetch -q --depth 1 origin "$REV"
git -C "$SRC" checkout -q --force FETCH_HEAD
python3 "$CORE/apply_split.py" "$SRC"

# arm64 phones only: every ABI is a full native build of the engine and its dependencies
GR="$SRC/platform/android/lib/build.gradle.kts"
grep -q 'abiFilters' "$GR" || sed -i 's/^\(\s*\)minSdk = 26/\1minSdk = 26\n\1ndk { abiFilters += listOf("arm64-v8a") }/' "$GR"
grep -q 'abiFilters' "$GR" || { echo "could not limit the ABIs"; exit 1; }

cd "$SRC"
rustup target add aarch64-linux-android
command -v cargo-ndk >/dev/null || cargo install cargo-ndk --locked
python3 scripts/bootstrap_conan_deps.py

cd platform/android
{
  echo "sdk.dir=$ANDROID_HOME"
  echo "cmake.dir=$(dirname "$(dirname "$(command -v cmake)")")"
} > local.properties
chmod +x gradlew
./gradlew --no-daemon -q :lib:assembleRelease

mkdir -p "$CORE/out-android"
cp "$(find lib/build/outputs/aar -name '*release*.aar' | head -1)" "$CORE/out-android/trusttunnel.aar"
ls -la "$CORE/out-android"
