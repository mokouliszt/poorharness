# PRoot from termux/proot, built with the NDK

Status: accepted (2026-10)

## Context

PRoot has to run as an Android executable from the APK's native library
directory ([proot-on-android](proot-on-android.md)).

## Decision

termux/proot is the PRoot fork maintained for Android. `runtime/proot/build.sh`
builds it from a pinned tag and commit with talloc linked statically, so the
runner depends only on bionic. Build flags: API 26, 16 KiB page alignment
(Android 15+ devices), `-Wno-error=implicit-function-declaration` (newer
clang), 32-bit loader off. The build needs `gawk`.

## Consequences

The exact source and recipe of the shipped PRoot are in the repository, as its
license requires ([licensing](licensing.md)).
