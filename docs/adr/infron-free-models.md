# Infron's free models, with the provider defined by the app

Status: accepted (2026-10, 0.6.0; reasoning levels in 0.6.1)

## Context

Infron is an OpenAI-compatible gateway (`https://llm.onerouter.pro/v1`) with a
set of free models: ids ending in `:free`, billed at $0, sharing 1,000
requests a day per account (reset at 00:00 UTC). Infron asks for a credit
balance before an account may use them ($5 for the standard free models in
October 2026); free use does not draw on it.

Unlike OrcaRouter ([orcarouter-free-models](orcarouter-free-models.md)),
Infron is not in models.dev, so opencode does not know how to reach it at all.
Its model list (`GET /v1/models`) needs no key and gives, per model, the lowest
prompt, completion and request prices, `supports_function_calling`, context
and output limits, input and output modalities, `category_type`, `deprecated`
and the providers behind it with the context each serves.

## Decision

Infron is a second gateway behind the same code as OrcaRouter
(`FreeGateway`): the key goes into opencode's auth store (`opencode.setKey`,
provider `infron`), and each opencode start reads the model list and writes a
`provider.infron` block. Because models.dev has no entry, the block also
carries how to reach Infron: `npm: "@ai-sdk/openai-compatible"`,
`name: "Infron"` and `options.baseURL`. opencode takes the key for a provider it
defines from config out of the auth store as for any other (verified with
opencode 1.19.0).

A model is offered when its id ends in `:free` and its prices are $0 (both:
a few paid models show $0 prices), it calls functions, it is a text LLM that
is neither deprecated nor display-only. Its context is the model's, but no
more than the largest any provider behind it serves.

The list does not say whether a model reasons. Infron's Chat Completions take
one `reasoning.effort` (`none`, `minimal`, `low`, `medium`, `high`, `xhigh`)
and convert it for whichever provider serves the model; a model without
reasoning ignores it. So every free model gets those six levels as opencode
`variants` (`{reasoning: {effort}}` in the request), and the `reasoning` flag
itself is left to opencode, which still passes reasoning back for the families
it knows (it did for DeepSeek, with tools, at `high`). opencode's own variants
for a reasoning model would send `reasoning_effort` instead, which Infron does
not document.

## Consequences

- Infron's base URL and its effort names are the values fixed in the app; the
  models are not.
- Without a level, a model keeps its provider's default: DeepSeek V4.1 Flash
  answers without thinking. Measured in October 2026: it thinks at every level
  but `none`, Ling 3.1 Flash and Apodex 1.1 Mini at every level, and both
  Qwen3.8 27B models only at `minimal` to `medium`; that mapping is Infron's.
- A free model Infron adds or retires follows at the next opencode start.
- Verified end to end with opencode 1.19.0, tensorlake-opencode 0.3.1 and the
  app's plugin: DeepSeek V4.1 Flash and Ling 3.1 Flash loaded a skill, read an
  attachment and ran `bash` in a Tensorlake sandbox.
- If models.dev adds Infron later, the app's block still wins for the fields it
  sets; dropping `connection()` from `Infron` then hands those back to opencode.
