# Tests

Status: accepted (2026-10, revised in 0.2.0, 0.3.0 and 0.11.0)

## Context

Most of the risk is in talking to an engine and services that change
upstream, and much of that code has no Android dependency.

## Decision

Plain-JVM unit tests live in `android/app/src/test`
(`./gradlew testReleaseUnitTest`) and cover code with no Android dependency:
the chat store, the hand-off text, opencode's permission rules and config,
git storage against real git (`GitReposTest`), and, against the real services,
the Tensorlake client (`TensorlakeLiveTest`), outputs and git repositories in
a sandbox and an S3-compatible bucket (`OutputsLiveTest`, `GitReposLiveTest`),
and the device bridge end to end (`PhoneBridgeTest`: the loopback server and
the pipe protocol against the real agent in python3, without PRoot). The live
tests run only when `TENSORLAKE_API_KEY` (and, for the bucket, `S3_ENDPOINT`,
`S3_BUCKET`, `S3_KEY_ID`, `S3_SECRET`) are set, and delete what they create.

## Consequences

Code that drives PRoot or opencode is verified against the real binaries on
Linux (see [`docs/bridge-protocol.md`](../bridge-protocol.md)) and on a
device, not in unit tests.
