#!/usr/bin/env bash
# Build the Ubuntu arm64 userland that PoorHarness runs its agents in.
#
#   runtime/rootfs/build.sh [output-dir]
#
# Output (in <output-dir>):
#   rootfs.tar.xz     Ubuntu 24.04 arm64 + tools + opencode
#   rootfs.json       manifest: versions, sha256 and sizes, read by the app
#
# Packages are installed with apt inside the arm64 rootfs, run through
# proot + qemu-user on the build host, so maintainer scripts run for real and
# dpkg's database matches what is on disk. Needs: proot, qemu-aarch64-static,
# curl, xz, openssl.
#
# POORDSH_REUSE_APT=1 keeps a previous run's base+apt stage (slow under qemu).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=../versions.env
source "$REPO/runtime/versions.env"

OUT="${1:-$REPO/android/app/build/generated/poordsh/assets/runtime}"
CACHE="${POORDSH_CACHE:-$REPO/.cache/runtime}"
WORK="$CACHE/rootfs-work"
ROOT="$WORK/root"
log() { printf '[rootfs] %s\n' "$*"; }

for tool in proot qemu-aarch64-static curl xz openssl; do
  command -v "$tool" >/dev/null || { echo "missing build tool: $tool" >&2; exit 1; }
done

fetch() { # url dest
  [ -s "$2" ] || { curl -fsSL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"; }
}
verify_sha256() { echo "$2  $1" | sha256sum -c - >/dev/null; }
verify_integrity() { # file sha512-<base64>
  local got
  got="sha512-$(openssl dgst -sha512 -binary "$1" | base64 -w0)"
  [ "$got" = "$2" ] || { echo "integrity mismatch for $1" >&2; exit 1; }
}

# Run a command inside the arm64 rootfs on the x86_64 build host.
guest() {
  proot -q qemu-aarch64-static -0 -r "$ROOT" -w / \
    -b /dev -b /proc -b /sys -b "$WORK/resolv.conf:/etc/resolv.conf" \
    /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LANG=C.UTF-8 DEBIAN_FRONTEND=noninteractive \
    ${http_proxy:+http_proxy=$http_proxy} ${https_proxy:+https_proxy=$https_proxy} \
    "$@"
}

mkdir -p "$CACHE" "$OUT"
if [ "${POORDSH_REUSE_APT:-0}" = 1 ] && [ -f "$WORK/apt.done" ]; then
  log "reusing base + apt stage from $WORK"
else
  rm -rf "$WORK"
  mkdir -p "$ROOT"

  # --- base -----------------------------------------------------------------
  BASE="$CACHE/$(basename "$UBUNTU_BASE_URL")"
  log "ubuntu base: $(basename "$BASE")"
  fetch "$UBUNTU_BASE_URL" "$BASE"
  verify_sha256 "$BASE" "$UBUNTU_BASE_SHA256"
  tar --numeric-owner -xpzf "$BASE" -C "$ROOT"
  cp /etc/resolv.conf "$WORK/resolv.conf"

  # apt cannot drop privileges to _apt under proot's fake root (build host and
  # device alike), so this file stays in the image.
  echo 'APT::Sandbox::User "root";' > "$ROOT/etc/apt/apt.conf.d/01poordsh-sandbox"
  # Keep the image small: no recommends, no docs except copyright files.
  cat > "$ROOT/etc/apt/apt.conf.d/02poordsh-slim" <<'EOF'
APT::Install-Recommends "false";
APT::Install-Suggests "false";
EOF
  cat > "$ROOT/etc/dpkg/dpkg.cfg.d/01poordsh-nodoc" <<'EOF'
path-exclude=/usr/share/doc/*
path-include=/usr/share/doc/*/copyright
path-exclude=/usr/share/man/*
path-exclude=/usr/share/info/*
path-exclude=/usr/share/locale/*
path-include=/usr/share/locale/locale.alias
path-exclude=/usr/share/lintian/*
EOF

  log "apt-get install: $UBUNTU_PACKAGES"
  guest /usr/bin/apt-get update -qq
  # shellcheck disable=SC2086
  guest /usr/bin/apt-get install -y -qq $UBUNTU_PACKAGES >"$WORK/apt.log" 2>&1 \
    || { tail -40 "$WORK/apt.log" >&2; exit 1; }
  guest /usr/bin/apt-get clean
  rm -rf "$ROOT"/var/lib/apt/lists/* "$ROOT"/var/cache/apt/*.bin "$ROOT"/var/log/*.log \
    "$ROOT"/var/log/apt/*
  # The app writes resolv.conf/hosts at start-up from the device's network.
  : > "$ROOT/etc/resolv.conf"
  touch "$WORK/apt.done"
fi

# Earlier runs (or a reused stage) may have left agent binaries behind.
rm -rf "$ROOT/opt/poordsh"

# --- opencode ---------------------------------------------------------------
log "opencode $OPENCODE_VERSION"
OC_TGZ="$CACHE/opencode-linux-arm64-$OPENCODE_VERSION.tgz"
fetch "$OPENCODE_TARBALL" "$OC_TGZ"
verify_integrity "$OC_TGZ" "$OPENCODE_INTEGRITY"
OC_DIR="$ROOT/opt/poordsh/opencode/$OPENCODE_VERSION"
mkdir -p "$OC_DIR"
tar -xzf "$OC_TGZ" -C "$WORK" package/bin/opencode
install -m 755 "$WORK/package/bin/opencode" "$OC_DIR/opencode"
rm -rf "$WORK/package"
ln -sfn "/opt/poordsh/opencode/$OPENCODE_VERSION/opencode" "$ROOT/usr/local/bin/opencode"

# The Tensorlake plugin is not bundled: its npm tree is ~200 MB with
# platform-native modules, and opencode installs it on the device on first
# start (docs/adr/engine-installs.md).

# --- metadata and packing ---------------------------------------------------
cat > "$ROOT/opt/poordsh/versions.json" <<EOF
{ "opencode": "$OPENCODE_VERSION",
  "tensorlakePlugin": "$TENSORLAKE_PLUGIN_VERSION", "ubuntu": "$(basename "$UBUNTU_BASE_URL" .tar.gz)" }
EOF

log "packing"
# Hard links are stored as separate files so the on-device extractor only has
# to handle directories, regular files and symlinks.
tar --numeric-owner --owner=0 --group=0 --hard-dereference --sort=name \
  --mtime='2026-01-01 00:00:00' -C "$ROOT" -cf - . | xz -T0 -9 > "$OUT/rootfs.tar.xz"

UNPACKED=$(du -sb "$ROOT" | cut -f1)
PACKED=$(stat -c%s "$OUT/rootfs.tar.xz")
SHA=$(sha256sum "$OUT/rootfs.tar.xz" | cut -d' ' -f1)
cat > "$OUT/rootfs.json" <<EOF
{
  "format": 1,
  "id": "$(printf '%s' "$SHA" | cut -c1-16)",
  "archive": "rootfs.tar.xz",
  "sha256": "$SHA",
  "packedBytes": $PACKED,
  "unpackedBytes": $UNPACKED,
  "opencode": "$OPENCODE_VERSION",
  "tensorlakePlugin": "$TENSORLAKE_PLUGIN_VERSION"
}
EOF
log "done: $(numfmt --to=iec "$PACKED") packed, $(numfmt --to=iec "$UNPACKED") unpacked"
