# Official engine, not a reimplementation

Status: accepted (2026-10, revised in 0.2.0 and 0.11.0)

## Context

OpenCode Zen's free tier only answers the official opencode client (other
clients get `403 FreeTierError` since 2026-09). Until 0.11 Codex also ran, for
ChatGPT sign-in ([opencode-only](opencode-only.md)).

## Decision

Use the unmodified upstream build and talk to it through its own server
interface: opencode `linux-arm64` in the app's Ubuntu image (`opencode serve`,
HTTP + SSE). The official `tensorlake-opencode` plugin provides its sandbox
tools. No forks, no patched binaries.

## Consequences

Upstream protocol changes can break the app. The client is kept small
(`android/.../backend/`) and the protocol is documented in
[`docs/bridge-protocol.md`](../bridge-protocol.md).
