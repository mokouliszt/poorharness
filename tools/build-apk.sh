#!/usr/bin/env bash
# Build the PoorHarness APK end to end.
#
#   tools/build-apk.sh [--rebuild-runtime]
#
# Steps (each reuses its previous output unless asked to rebuild):
#   1. PRoot for arm64           runtime/proot/build.sh
#   2. Ubuntu arm64 rootfs       runtime/rootfs/build.sh
#   3. Web UI                    webui (npm ci && npm run build)
#   4. Notices + APK             android (./gradlew assembleRelease)
#
# Signing: set POORDSH_KEYSTORE, POORDSH_KEYSTORE_PASSWORD, POORDSH_KEY_ALIAS and
# POORDSH_KEY_PASSWORD for a release key; otherwise the debug key is used.
# Output: android/app/build/outputs/apk/release/app-release.apk
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GEN="$REPO/android/app/build/generated/poordsh"
REBUILD=0
[ "${1:-}" = "--rebuild-runtime" ] && REBUILD=1
log() { printf '\n== %s\n' "$*"; }

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

if [ "$REBUILD" = 1 ] || [ ! -f "$GEN/jniLibs/arm64-v8a/libproot.so" ]; then
  log "PRoot"
  "$REPO/runtime/proot/build.sh" "$GEN/jniLibs"
fi

if [ "$REBUILD" = 1 ] || [ ! -f "$GEN/assets/runtime/rootfs.json" ]; then
  log "Ubuntu rootfs"
  "$REPO/runtime/rootfs/build.sh" "$GEN/assets/runtime"
fi

log "Web UI"
(
  cd "$REPO/webui"
  [ -d node_modules ] || npm ci --no-audit --no-fund
  npm run build
)
rm -rf "$GEN/assets/webui"
mkdir -p "$GEN/assets/webui" "$GEN/assets/notices"
cp -r "$REPO/webui/dist/." "$GEN/assets/webui/"
cp "$REPO/THIRD_PARTY_NOTICES.md" "$GEN/assets/notices/"

log "APK"
(cd "$REPO/android" && ./gradlew --console=plain assembleRelease)
APK="$REPO/android/app/build/outputs/apk/release/app-release.apk"
log "done: $APK ($(du -h "$APK" | cut -f1))"
