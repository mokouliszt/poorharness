# Engine options: useful features off by default, and subagents

Status: accepted (2026-10, 0.3.0; subagent switches in 0.9.0; opencode only and the subagents' model in 0.11.0)

## Context

opencode has useful features that are off by default, behind flags and
config keys that change between releases. Finding them should not be the
user's job. A feature that is already on needs no switch, with one exception:
subagents, which some users want to turn off (they run in parallel, use more
of a model's quota and make turns harder to follow).

## Decision

- Always on, with no switch: web search (Parallel and Exa, no key needed).
- On by default, with a switch: subagents. The task tool is allowed and runs
  in the background (`OPENCODE_EXPERIMENTAL_BACKGROUND_SUBAGENTS=1`,
  [subagent-sessions](subagent-sessions.md)); off, `task` falls under the
  config's `"*": "deny"` and plugin agents are not defined. Under the switch,
  the model subagents run on ([subagent-model](subagent-model.md)). Each option
  has a default; Settings keep a state for every option
  (`engine_option_states`), so a default-on one can stay off.
- The options sheet (the sliders button on Home and in a chat) shows a short
  catalog the host owns (`EngineOptions`): the batch tool, pruning old tool
  output, and experimental models. An option can name one it needs (shown
  dimmed without it). The host knows how each is applied (opencode config or
  environment); the UI only shows the ids, their defaults and their wording. A
  change applies when opencode is next idle.
- Left out: language servers (opencode runs them in its own process on the
  phone, where the chat's files are not), and modes that are CLI-only or run
  model-written code in opencode's process. The question tool is not an option:
  it is always on ([questions](questions.md)).

## Consequences

Upstream dropping a feature does not break a start: opencode ignores config
keys it does not know, and a stored option this build no longer lists (Codex's,
before 0.11) is dropped when settings are read. Settings
from 0.3–0.8 (only the ids turned on) are read as those plus the defaults. The
catalog is reviewed when engine versions move
([`docs/maintenance.md`](../maintenance.md)).
