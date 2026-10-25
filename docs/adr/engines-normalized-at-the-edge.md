# Engines are normalized at the edge

Status: accepted (2026-10; revised in 0.11.0)

## Context

opencode describes sessions, messages, tool calls and approvals in its own
shapes and changes them between releases (until 0.11 Codex did too, in
unrelated ones).

## Decision

The backend converts the engine's events and history into the shapes in
[`docs/bridge-protocol.md`](../bridge-protocol.md) (messages, parts, statuses,
approvals). The UI never sees engine-specific JSON.

## Consequences

When the engine changes a field, only its backend changes.
