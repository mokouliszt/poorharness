# Build scripts only orchestrate

Status: accepted (2026-10)

## Context

Each build step (PRoot, the image, the web UI, the APK) has its own tool and is
useful on its own.

## Decision

Real work stays in the step that owns it (`runtime/*/build.sh`,
`npm run build`, Gradle). The scripts in `tools/` must keep working when run
from any directory and must not write outside the repository.

## Consequences

A failing step can be rerun by itself; `tools/build-apk.sh` stays a sequence of
calls.
