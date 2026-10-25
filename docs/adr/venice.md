# Venice: five uncensored models, with the provider defined by the app

Status: accepted (2026-10, 0.14.0)

## Context

Venice (`https://api.venice.ai/api/v1`) is an OpenAI-compatible service that
bills per token; there is no free tier. opencode knows `venice` from models.dev,
but through `venice-ai-sdk-provider`, an npm package the phone would have to
download. The model list (`GET /models`, no key) gives, per model, the context
and output limits, prices, and `capabilities`: `supportsFunctionCalling`,
`supportsReasoning`, `reasoningEffortOptions`, `supportsVision`, `supportsE2EE`.

The five models asked for are `deepseek-v4-1-flash-uncensored`,
`olafangensan-glm-4.7-flash-heretic`, `gemma-4-uncensored`,
`e2ee-gemma-4-26b-a4b-uncensored-p` and `venice-uncensored-1-2`. The E2EE one
cannot call functions and rejects a request that carries `tools`.

## Decision

Venice is defined by the app, as Infron is ([infron-free-models](infron-free-models.md)):
`provider.venice` has `npm: "@ai-sdk/openai-compatible"`, `name` and
`options.baseURL`, so opencode uses the OpenAI-compatible client it already
ships. The key goes into opencode's auth store (`opencode.setKey`, provider
`venice`). Each opencode start reads `/models` and writes the block.

The five ids are the only hardcoded part: they are the whole offer, a
`whitelist` and not a filter over everything Venice sells. Everything else
(names, limits, prices, tool and vision support, reasoning levels) comes from the
list. A model Venice marks offline or that is not text is left out; if none of
the five is listed, the refresh fails and the cached copy is used.

**Reasoning.** Venice takes `reasoning_effort`, which opencode sends for a
variant `{reasoningEffort: level}`. Each model gets exactly the levels in its
`reasoningEffortOptions`. opencode derives other levels for a reasoning model
and Venice rejects those it does not offer, so the rest of
`none … xhigh, max` are written as `{"disabled": true}`.

**A model without function calling** (the E2EE Gemma) is "chat only": opencode
sends tools even for `tool_call: false`, so a primary agent `chat` with
`permission {"*": "deny"}` removes them, and prompts for such a model name that
agent (`agent: "chat"` in `prompt_async`). Chat-only models stay in the chat
picker with a badge and are left out of the subagent picker. They get no
sandbox tools.

Venice injects its own system prompt (about 1.5k cached tokens) unless
`venice_parameters.include_venice_system_prompt` is false. It is left at its
default: the app does not add provider-specific request fields.

## Consequences

- The E2EE model is called as an ordinary model. The service runs it in a TEE,
  but the app does not do the client-side encryption (secp256k1 ECDH, HKDF,
  AES-GCM and attestation); the README says so. Doing it would need a local
  encrypting proxy in front of opencode.
- Checked end to end with opencode 1.19.0 and a real key in October 2026:
  GLM heretic answered at `none` and `low`, the E2EE Gemma answered through
  the `chat` agent with no tools in the request. Under $0.001 in total.
- A Venice model added later is not offered until its id is added to
  `Venice.MODELS`.
- If Venice stops being listed by its own path (models.dev drops the npm
  package, or opencode ships it), dropping the connection fields hands those
  back to opencode.
