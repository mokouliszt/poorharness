# opencode is the only engine

Status: accepted (2026-10, 0.11.0). Supersedes [codex-over-websocket](codex-over-websocket.md)
and the Codex parts of the decisions that name it.

## Context

From 0.1 to 0.10 the app ran two engines, opencode and the Codex app-server, and
a chat could move between them. Codex needed a ChatGPT sign-in copied into every
sandbox, its own install in each sandbox or jail, a relay port, its own plugin,
skill, subagent and approval mappings, and an update path of its own. Most of
the app's code and of its maintenance steps existed for the second engine. The
owner decided to keep opencode alone.

## Decision

- opencode is the only engine. Everything Codex needed is gone: the app-server
  client and its sandbox setup, the ChatGPT sign-in, the relay port on Tensorlake
  sandboxes, Codex's options, plugin marketplace and hook approvals, its update
  check, and the model group in the picker.
- The [AgentBackend](../../android/app/src/main/java/dev/mokouliszt/poordsh/backend/AgentBackend.kt)
  interface stays, with one implementation, so the chat layer does not depend on
  opencode's HTTP API. Ids keep their engine prefix ([ids-carry-their-engine](ids-carry-their-engine.md)).
- Chats from before 0.11 keep everything the app stored of them. One whose last
  session was a Codex thread goes on in opencode with its history as hidden
  context, the way a chat on a fresh sandbox does ([chats-belong-to-the-app](chats-belong-to-the-app.md)).
  Deleting it deletes nothing more in the sandbox, where the thread lived.
- What the app kept for Codex on the phone (the ChatGPT sign-in in the Ubuntu
  home, the plugin marketplace file, hook approvals) is not read again; the
  plugin store removes its leftovers.

## Consequences

- GPT models through a ChatGPT subscription are no longer offered.
- A plugin's hooks, commands and MCP servers started as a command are shown as
  not used ([plugins](plugins.md)).
- Fewer moving parts to follow upstream: one engine's releases, one protocol.
