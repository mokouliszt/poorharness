# On-device Linux via PRoot from nativeLibraryDir

Status: accepted (2026-10)

## Context

opencode is a Linux program and the phone is not rooted. Android 10+ only
allows `exec()` from the APK's native library directory.

## Decision

PRoot and its loader ship as `libproot.so` / `libproot-loader.so`; guest
binaries are mapped by PRoot and need no exec permission. The approach follows
dphmoblie/deepseek-harness-android (MIT); no code from it, and nothing from its
AGPL dependencies, is used.

## Consequences

Kernel sandboxes that PRoot cannot provide, such as bubblewrap and Landlock, do
not work in the guest. How PRoot and the image are built:
[proot-build](proot-build.md), [ubuntu-image](ubuntu-image.md).
