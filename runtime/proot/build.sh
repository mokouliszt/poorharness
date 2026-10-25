#!/usr/bin/env bash
# Build PRoot for Android (arm64-v8a) from pinned sources with the Android NDK.
#
#   runtime/proot/build.sh [output-dir]
#
# Output: <output-dir>/arm64-v8a/libproot.so and libproot-loader.so.
# They ship as "native libraries" so Android extracts them to nativeLibraryDir,
# the only app-owned location from which exec() is allowed on Android 10+.
# talloc is linked statically, so the runner has no dependency outside bionic.
# Build host needs: git, curl, make, gawk, python3 (for talloc's waf).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=../versions.env
source "$REPO/runtime/versions.env"

OUT="${1:-$REPO/android/app/build/generated/poordsh/jniLibs}"
CACHE="${POORDSH_CACHE:-$REPO/.cache/runtime}"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/$(ls "$SDK/ndk" 2>/dev/null | sort -V | tail -1)}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
TARGET="aarch64-linux-android${ANDROID_API}"
ABI=arm64-v8a

log() { printf '[proot] %s\n' "$*"; }
[ -x "$TOOLCHAIN/bin/clang" ] || { echo "NDK not found at $NDK" >&2; exit 1; }
command -v gawk >/dev/null || { echo "gawk is required (PRoot's build script uses it)" >&2; exit 1; }

export CC="$TOOLCHAIN/bin/${TARGET}-clang"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"

mkdir -p "$CACHE" "$OUT/$ABI"
WORK="$CACHE/proot-$ABI"
rm -rf "$WORK" && mkdir -p "$WORK"

# --- talloc (static) --------------------------------------------------------
TALLOC_TGZ="$CACHE/talloc-$TALLOC_VERSION.tar.gz"
[ -s "$TALLOC_TGZ" ] || curl -fsSL --retry 3 -o "$TALLOC_TGZ" "$TALLOC_URL"
echo "$TALLOC_SHA256  $TALLOC_TGZ" | sha256sum -c - >/dev/null
tar -xzf "$TALLOC_TGZ" -C "$WORK"
TALLOC_SRC="$WORK/talloc-$TALLOC_VERSION"

log "building talloc $TALLOC_VERSION"
# waf cannot run test binaries for the target, so it is told the answers.
cat > "$TALLOC_SRC/cross-answers.txt" <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: NO
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: NO
Checking getconf large file support flags work: OK
EOF
(
  cd "$TALLOC_SRC"
  ./configure --prefix="$WORK/talloc-install" --disable-rpath --disable-python \
    --cross-compile --cross-answers=cross-answers.txt >"$WORK/talloc-configure.log" 2>&1 \
    || { tail -40 "$WORK/talloc-configure.log" >&2; exit 1; }
  make -j"$(nproc)" >"$WORK/talloc-make.log" 2>&1 || { tail -40 "$WORK/talloc-make.log" >&2; exit 1; }
)
mkdir -p "$WORK/talloc-static/include" "$WORK/talloc-static/lib"
OBJ="$(find "$TALLOC_SRC/bin/default" -name 'talloc.c.*.o' | head -1)"
[ -n "$OBJ" ] || { echo "talloc object not found" >&2; exit 1; }
"$AR" rcs "$WORK/talloc-static/lib/libtalloc.a" "$OBJ"
cp "$TALLOC_SRC/talloc.h" "$WORK/talloc-static/include/"

# --- PRoot ------------------------------------------------------------------
log "building PRoot $PROOT_TAG"
git clone -q --depth 1 --branch "$PROOT_TAG" "$PROOT_REPO" "$WORK/proot"
[ "$(git -C "$WORK/proot" rev-parse HEAD)" = "$PROOT_COMMIT" ] \
  || { echo "PRoot tag $PROOT_TAG does not point at the pinned commit" >&2; exit 1; }
(
  cd "$WORK/proot/src"
  # Flags go through the environment so the makefile's own "+=" additions
  # (-ltalloc, -O2, noexecstack) still apply. 16 KiB alignment keeps both
  # binaries loadable on devices with 16 KiB pages (Android 15+).
  # Newer clang makes implicit declarations an error; PRoot still has a few.
  # HAS_LOADER_32BIT is cleared on the command line: only 64-bit guests run here.
  export CFLAGS="-I$WORK/talloc-static/include -fPIE -Wno-error=implicit-function-declaration"
  export LDFLAGS="-L$WORK/talloc-static/lib -pie -Wl,-z,max-page-size=16384"
  export LOADER_LDFLAGS="-Wl,-z,max-page-size=16384"
  export PROOT_UNBUNDLE_LOADER=.
  make -j"$(nproc)" V=1 CC="$CC" STRIP="$STRIP" OBJCOPY="$OBJCOPY" HAS_LOADER_32BIT= \
    proot >"$WORK/proot-make.log" 2>&1 \
    || { tail -60 "$WORK/proot-make.log" >&2; exit 1; }
)
cp "$WORK/proot/src/proot" "$OUT/$ABI/libproot.so"
cp "$WORK/proot/src/loader/loader" "$OUT/$ABI/libproot-loader.so"
"$STRIP" "$OUT/$ABI/libproot.so" "$OUT/$ABI/libproot-loader.so" 2>/dev/null || true
chmod 755 "$OUT/$ABI"/*.so
log "done: $(ls -la "$OUT/$ABI" | tail -n +2 | awk '{print $5, $9}' | tr '\n' ' ')"
