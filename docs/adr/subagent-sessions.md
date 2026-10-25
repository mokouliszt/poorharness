# Subagent sessions report to their chat

Status: accepted (2026-10, 0.3.0; Codex in 0.9.1, removed in 0.11.0 with [opencode-only](opencode-only.md))

## Context

With background subagents on ([engine-options](engine-options.md)), opencode's
task tool can start subagents that keep working after the chat's own turn has
ended, and wake the chat when they finish. Each subagent has a session of its
own, and its events, approval requests included, carry that session's id. The
app did not create it and used to drop its events, so a subagent asking for
approval waited forever, and the chat's sandbox could pause under it.

## Decision

opencode's client remembers each subagent session's parent (`parentID` on
`session.created`), and `ChatService` maps a subagent's events to the chat that
owns its root session (`AgentBackend.rootSession`). Its approval requests go to
that chat. While it is busy it holds the chat's sandbox the way a turn does, and
the UI shows how many are working. Its own messages stay out of the chat; the
parent's task tool reports them. Stopping a chat also stops its subagents.

Which model a subagent runs on is [subagent-model](subagent-model.md).

## Consequences

A chat can be idle while its subagents work; a line under its messages says so,
and the composer stays usable. Subagents started before an engine restart are
not tracked after it.
