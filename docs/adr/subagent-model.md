# One model for subagents, from any provider

Status: accepted (2026-10, 0.11.0)

## Context

opencode's task tool starts subagents (its own `general` and `explore`, and the
agents plugins add). By default a subagent runs on the model and reasoning
level of the turn that starts it (checked with opencode 1.19.0). Users want to
hand subagent work to another model, a cheaper or faster one, possibly from
another provider than the chat's.

opencode's agent config takes `model` (`<provider>/<model>`) and `variant` (the
reasoning level) per agent. Setting only those fields of a built-in agent keeps
its prompt and tools (checked: `explore` kept its prompt, `general` its
permissions, and a subagent started with the task tool ran on the configured
model and variant while the parent stayed on its own).

## Decision

- One setting, in the options sheet under the subagents switch: "Same as the
  chat" (the default) or any model of the picker, with its reasoning level.
  It is chosen from the same live catalog as the chat's model; nothing is
  hardcoded.
- When set, every subagent gets it: `agent.general`, `agent.explore` and each
  plugin agent receive `model` and, if a level was picked, `variant`.
- It reaches opencode at its next start, like the other options. A model whose
  provider has no key any more (OpenCode Zen's free models need none) is left
  out at start, so subagents fall back to the turn's model instead of failing.

## Consequences

- A subagent's cost and limits are those of its model's provider.
- opencode's published config schema (October 2026) marks the `agent` block
  deprecated in favour of `agents`; 1.19.0 reads `agent`. When an opencode
  release drops it, `OpencodeConfig.agents` changes shape, not this decision.
