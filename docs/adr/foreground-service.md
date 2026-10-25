# A foreground service only while something runs

Status: accepted (2026-10, revised in 0.1.2)

## Context

Android stops background processes, so an engine needs a foreground service
while it works. A permanent notification for an idle app is noise.

## Decision

`AgentService` (type `specialUse`) holds the process while an engine is
running or Ubuntu is unpacking; `Keepalive` starts it with the first holder
and stops it with the last. It takes a partial wake lock only while a chat is
working. The notification's Stop ends every engine at once (without waiting on
locks a slow start may hold), removes the notification and closes the app.

## Consequences

An idle app shows no notification.
