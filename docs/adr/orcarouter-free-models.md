# OrcaRouter's free models, listed by OrcaRouter

Status: accepted (2026-10, 0.5.0; shared with Infron in 0.6.0)

## Context

OrcaRouter is an OpenAI-compatible gateway with a free tier: models with a
`-free` id that cost nothing and never fall back to a paid model. opencode
already knows OrcaRouter from models.dev (provider `orcarouter`, key
`ORCAROUTER_API_KEY` or the auth store), but that copy has two problems for an
app that only wants the free tier:

- it lists all of OrcaRouter's models, paid ones included, and a router
  (`orcarouter/auto`) priced at $0 that is not free;
- it lags OrcaRouter: in October 2026 it lacked the two newest free models.

OrcaRouter publishes its own catalog without a key
(`https://api.orcarouter.ai/api/pricing`). Each entry says whether it is on the
free tier (`is_free_tier`) and gives its context and output limits, input
modalities and supported parameters. A request that carries a bad key gets 401
instead, so the catalog is fetched without one.

## Decision

The OrcaRouter key is stored like NIM's ([nvidia-nim](nvidia-nim.md)):
`opencode.setKey`, provider `orcarouter`. With a key stored, each opencode start
fetches the catalog (`FreeGateway.FreeModels`, the code it shares with
[Infron](infron-free-models.md)) and writes a `provider.orcarouter` block into
the app's opencode config:

- `whitelist`: the free models that take `tools` and answer in text, so opencode
  offers nothing else from OrcaRouter, for the chat model or for its own small
  tasks such as titles;
- `models`: a definition of each of them from OrcaRouter's fields (name, limits,
  modalities, reasoning, cost 0). For a model models.dev also knows, these
  override its fields and the rest stay opencode's (how reasoning is sent back,
  effort variants); a model models.dev does not know yet is defined by them alone.

The list is kept in `/root/.config/poordsh/orcarouter-free.json` (each gateway
has its own `<id>-free.json`). A start that
cannot reach OrcaRouter uses it; with neither, the whitelist is empty and no
OrcaRouter model is offered. Saving the key writes the config again and
disposes opencode's instance, which reads the config file anew (verified with
opencode 1.19.0), so the models appear without a restart.

OrcaRouter's models keep their own group in the model picker; "Free" stays
OpenCode Zen's keyless free tier.

## Consequences

- A free model OrcaRouter adds shows up at the next start, with no app update
  and no wait for models.dev. One it retires disappears the same way.
- The router `orcarouter/free`, which picks one of the free models per request,
  is not offered: the catalog does not list it, and offering it would mean a
  model id fixed in the app.
- opencode rejects a model whose `limit` lacks either number, and a rejected
  config stops opencode altogether. When OrcaRouter gives no output limit, the
  context size is used (opencode caps a reply at 32k tokens anyway), and
  modalities outside opencode's set are dropped.
- OrcaRouter's free tier is small for an agent: for an account that has never
  paid, 10 requests a minute and 50 a day across most free models, with a few
  allowing more (Hy4 preview: 250 a day) (October 2026), and every tool step is
  a request. A spent daily allowance answers HTTP 429 with a `Retry-After` up to
  00:00 UTC; opencode waits that long and tries again, and the chat shows when
  (`chat.status.retryAt`) until the person stops it. A paid model would answer
  402 with no credits; the whitelist never sends one.
- Verified end to end with opencode 1.19.0, tensorlake-opencode 0.3.1 and the
  app's plugin: Hy4 preview loaded a skill, read an attachment and ran `bash` in
  a Tensorlake sandbox.
- Like NIM's, the key is not checked when saved; a wrong key shows as an
  authorization error on the first message.
