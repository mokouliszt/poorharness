# Model choice

Status: accepted (2026-10, revised in 0.1.2, 0.6.3 and 0.11.0)

## Context

Models come from opencode ([models-from-engines](models-from-engines.md)), and
a chat keeps its history across sessions
([chats-belong-to-the-app](chats-belong-to-the-app.md)).

## Decision

The selected model is whatever opencode lists. On first run the app picks
opencode's default free model; after that it keeps the user's last choice.
Opening a chat selects the model (and effort) last sent in it, if still
offered. Any model can be picked in a chat. Where a chat went on in a new
session (a fresh sandbox, or a chat from Codex before 0.11) it shows a divider
with the model.

The picker groups models by where they come from (OpenCode's own models with
its free ones first, then each provider). With several keys the list gets long, so only the
group holding the selected model opens with the picker (with none selected, the
first group with models); the others show their name and count and open on a
tap. A search opens every group, so no match is hidden. A provider with nothing
to list yet (no key) is one row with its action, and a key added
from the picker brings the picker back with that provider open.

## Consequences

Opening a chat whose last model is no longer offered keeps the current
selection.

The same picker, opened from the options sheet, picks the subagents' model
([subagent-model](subagent-model.md)): a first row keeps them on the chat's
model, and providers without a key are left out.
