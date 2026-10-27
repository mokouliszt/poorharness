# One model for chat titles, from any provider

Status: accepted (2026-10, 0.16.0)

## Context

After a chat's first message, opencode writes its title with a hidden agent,
`title`, in a separate request (checked with opencode 1.19.0). Its model is the
agent's `model` when one is configured; otherwise the chat provider's small
model, if opencode finds one; otherwise the chat's own model. A small model is
`small_model` from config, or a model of the chat's provider in the `gpt-nano`,
`gemini-flash` or `claude-haiku` family (`gpt-nano` only for OpenCode Zen). The
free lists the app offers have none of those, so in practice titles are written
by the chat's model. With an OpenCode Zen key, they are written by Zen's
GPT-5 Nano.

That costs one request of the chat's model per chat: one of a gateway's daily
free requests, or paid tokens on Venice. A cheaper or free model can write titles instead.

The title request carries no tools and is marked as a small request: opencode
uses the model's lightest settings and does not apply a variant, so a reasoning
level cannot be set for it.

## Decision

- One setting at the bottom of the options sheet, "Chat title model": "Same as
  the chat" (the default) or any model of the picker, from the same live catalog
  as the chat's model. No reasoning level.
- When set, `agent.title` gets `model` only, so opencode keeps the agent's
  prompt. Checked: the title request went to the configured model with the title
  prompt and no tools, and the chat's request went to the chat's model.
- "Same as the chat" writes nothing: opencode's own choice above stays as it is.
- Models without tool calls (Venice's E2EE Gemma) can be picked, since a title
  request has no tools.
- It reaches opencode at its next start, like the other options. A model whose
  provider has no key any more (OpenCode Zen's free models need none) is left out
  at start, so titles fall back to opencode's choice instead of failing.

## Consequences

- A title's cost and limits are those of its model's provider.
- If opencode starts applying variants to title requests, a level could be added
  as for subagents ([subagent-model](subagent-model.md)).
- The `agent` block note in [subagent-model](subagent-model.md) applies here too.
