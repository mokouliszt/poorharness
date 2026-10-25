# Navigation is a stack in the store

Status: accepted (2026-10, revised in 0.2.0)

## Context

Android's back gesture should behave as in native apps: close a sheet first,
then go back one screen.

## Decision

No router: the store keeps a screen stack, and the Android back gesture calls
`pop()` through the bridge (closing sheets first). Each folder of a chat's
files is its own stack entry, so back goes up one folder. Only while Ubuntu is
being unpacked (first run), or if that failed, is the screen limited to setup
and diagnostics; chats and settings work with the engines stopped. Settings are
reachable from a chat; actions that would cut a running turn short are
disabled while one runs.

## Consequences

Screens take their arguments from their stack entry, not from a URL.
