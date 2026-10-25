# Chats belong to the app, not to an engine

Status: accepted (2026-10; revised in 0.11.0, opencode only)

## Context

A chat outlives the engine session that holds it: a sandbox that expired
starts a new session, and until 0.11 a chat could move between opencode and
Codex in one conversation.

## Decision

The app stores each chat (`files/chats/c_*.json`) as segments, one engine
session per segment, and copies each finished turn from the engine. A chat
that needs a new session (a fresh sandbox, or a chat whose last segment is a
Codex thread from before 0.11, [opencode-only](opencode-only.md)) starts a
segment and hands over the conversation as hidden context
([`docs/bridge-protocol.md`](../bridge-protocol.md), "Handing a chat to a new
session"); the chat's sandbox stays the same ([chat-sandboxes](chat-sandboxes.md)),
so files carry over by themselves.

## Consequences

The new session sees a text summary (at most 24 000 characters), not the old
session's internal state. Chats from before 0.1.2 are imported from opencode
once, on its first start.
