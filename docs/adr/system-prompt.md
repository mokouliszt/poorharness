# One system prompt for every chat

Status: accepted (2026-10, 0.10.0; revised in 0.11.0, opencode only)

## Context

Users want to give the agent standing instructions (language, tone, habits)
once, not in every chat. opencode has a built-in prompt that its tools depend
on, and a channel that adds to it: `instructions`, files whose text joins the
system prompt. opencode builds the system prompt for every request and reads
the instruction files again each time (checked with opencode 1.19.0): a changed
file applies from the next message, in chats that already ran too, with no
restart.

0.10 also passed the prompt to Codex, as `developerInstructions` plus a
message for threads that had already started; that went with Codex
([opencode-only](opencode-only.md)).

## Decision

- One prompt, set in Settings (up to 20,000 characters), kept in the app's
  settings. `SystemPrompt.compose` puts it after the app's own instructions
  ([outputs](outputs.md), and [git-repos](git-repos.md) while a bucket is
  connected).
- The composed text is the app's instructions file
  (`/root/.config/poordsh/instructions.md`), written at every start and again
  when the prompt is saved.

## Consequences

- A saved prompt applies from each chat's next message, with no restart.
- Verified with opencode 1.19.0 serve and an Infron model: the file changed
  between two messages of one session, and the second followed the new text.
