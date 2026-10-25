# The opencode client heals itself

Status: accepted (2026-10), narrowed by [activity-tokens](activity-tokens.md)

## Context

On a phone, `opencode serve` was reported to stop answering after the app sat
idle for a while (cause not yet pinned down; on Linux the same binary keeps
answering and sends `server.heartbeat` every 10 s).

## Decision

The client never reuses pooled loopback connections; treats 35 s without any
event as a dead stream; checks `/global/health` every 15 s and restarts the
server after two misses or when the process exits; and, if a prompt fails
while the server is unhealthy, restarts it and sends the prompt once more.
Restarts are capped at 3 per 10 minutes. Each step is written to the opencode
log in Diagnostics. Since 0.1.2 it restarts only while work is waiting on it (a
turn, a sign-in, a call in progress); otherwise it stops, and the next message
starts it fresh. Launches are serialized, so a restart and a start never run
two servers.

## Consequences

A hung server costs a restart instead of a stuck chat.
