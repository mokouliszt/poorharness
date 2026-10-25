# Android targets

Status: accepted (2026-10, revised in 0.2.0 and 0.11.0)

## Context

The on-device runtime is a linux-arm64 userland.

## Decision

compileSdk/targetSdk 36, minSdk 26, arm64-v8a only (opencode runs on the phone
from its linux-arm64 build). AGP and Kotlin versions are in `build.gradle.kts`.

## Consequences

The APK does not install on 32-bit-only phones or x86 emulators.
