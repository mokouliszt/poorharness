# Codex in a sandbox, over WebSocket

Status: superseded by [opencode-only](opencode-only.md) (0.11.0); accepted in 0.2.0, revised in 0.3.0

## Context

Codex's app server refuses a non-loopback WebSocket listener without its own
token auth, which uses the `Authorization` header that Tensorlake's proxy needs
for the API key; it also rejects requests whose `Host`/`Origin` are not local,
and needs `codex-code-mode-host` next to the binary for tool calls.

## Decision

`CodexSandbox` installs the official npm vendor layout and runs
`codex app-server --listen ws://127.0.0.1:4501` plus a small Python relay on
port 4500 that rewrites only the first request head, both through the
sandbox's process API (they survive suspend and resume).
`~/.poordsh/server.txt` records the version and sign-in a server was started
with, so a resumed sandbox keeps its server unless one changed. A dropped
socket mid-turn reconnects for up to 2 minutes and re-attaches with
`thread/resume`; the turn keeps running in the sandbox meanwhile. The ChatGPT
sign-in is the phone's copy: written into a sandbox before its server starts
and read back after every turn, keeping whichever has the newer `last_refresh`
(refresh tokens rotate).

## Consequences

Sign-in is device code only (a browser redirect would land on the sandbox's
localhost). In a device jail the same server runs over stdio instead, with no
relay ([phone-sandboxes](phone-sandboxes.md)). Options reach the server as
`-c features.<name>=true`, recorded in `server.txt` like the version, because
`--enable` makes it exit on a feature it does not know. Two sandboxes running at once (paid plans) can each rotate the
token; the newer one wins and the other server is restarted with it on its
next connect.
