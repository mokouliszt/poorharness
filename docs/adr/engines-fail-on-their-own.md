# Each engine fails on its own

Status: accepted (2026-10, revised in 0.2.0)

## Context

The two engines start, crash and update independently. One failing should not
take the other, or the app, with it.

## Decision

`RuntimeManager` only prepares Ubuntu; engines are started by `EngineManager`
when a chat needs them. A failed start fails that message and marks the engine
`failed` (Home offers "Try again"); the other engine is unaffected. When an
engine stops or dies, every running turn on it ends (`chat.status idle`, with
an error if it died) and its pending approvals are withdrawn. A sandbox that
cannot start (no key, the plan's slot taken) fails only the message that
needed it.

## Consequences

Nothing waits on an engine that is gone.
