# Ids carry their engine

Status: accepted (2026-10, revised in 0.2.0 and 0.11.0)

## Context

Sessions, requests and models of both engines meet in one UI, and any model can
be picked in any chat.

## Decision

Engine sessions, requests and models are `"<engine>:<raw id>"`, so every call
routes itself, also now that opencode is the only engine
([opencode-only](opencode-only.md)): stored chats from before 0.11 hold
`codex:<chat id>/<thread id>` sessions, which no engine answers. Chats are the app's own (`c_<hex>`,
[chats-belong-to-the-app](chats-belong-to-the-app.md)): `ChatService` maps
engine session ids to chat ids on the way to the UI and drops events of
sessions the app did not create.

## Consequences

A session of an engine this build does not have is never called; its chat
goes on in a new opencode segment.
