# The engine runs only while it is needed

Status: accepted (2026-10, revised in 0.2.0, 0.3.0 and 0.11.0)

## Context

Keeping opencode up all the time cost memory on the phone, and a crashed
engine used to restart behind a full-screen "starting" page.

## Decision

A message starts opencode; it stops 60 s after its last turn (`EngineManager`,
[activity-tokens](activity-tokens.md)). The model list is cached on disk, so
the picker, chat history and settings work with the engine stopped. Only
unpacking Ubuntu takes over the screen; an engine start and the sandbox show
inline in the chat. The foreground service exists only while the engine runs
or Ubuntu is unpacking ([foreground-service](foreground-service.md)).

## Consequences

The first message after a pause waits for a start (seconds on a phone; a
suspended sandbox resumes in a few more). Settings that need a restart are
applied when the engine is next idle, never in the middle of a turn.
