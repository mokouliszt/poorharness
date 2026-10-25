# Build inputs are generated, never committed

Status: accepted (2026-10)

## Context

PRoot, the Ubuntu image and the built web UI are large, reproducible from
recipes, and change on their own schedules.

## Decision

`android/app/build.gradle.kts` adds `build/generated/poordsh/{jniLibs,assets}`
as source sets and fails `preBuild` with a clear message if PRoot, the rootfs
or the web UI is missing; `tools/build-apk.sh` produces them. Native libraries
use legacy packaging so PRoot is extracted to a real file; `.xz` is stored
uncompressed. Hand-written assets (PoorHarness's opencode plugin,
`assets/opencode/`) are source and live in `app/src/main/assets/`.

## Consequences

A fresh clone needs one run of `tools/build-apk.sh` before Gradle alone can
build.
