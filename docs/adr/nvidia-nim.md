# NVIDIA NIM through opencode's own provider

Status: accepted (2026-10, 0.3.0; limited to the models NIM serves in 0.6.2)

## Context

NVIDIA's NIM API offers many open models with a free key. opencode already
knows it: its models.dev catalog has an `nvidia` provider (OpenAI-compatible,
`integrate.api.nvidia.com`), enabled by a stored key.

## Decision

NIM runs through opencode like any other opencode provider. The key goes into
opencode's own auth store (`opencode.setKey`, provider `nvidia`), and the models
come from `/config/providers` like OpenCode Zen's. Saving or removing a key
disposes opencode's instance so the provider list is rebuilt, which also fixes
a new Zen key not showing until a restart. Models that cannot call tools (many
NIM models are chat-only) are left out of every provider's list. NIM models cost
$0 in the catalog but keep their own group; "Free" stays OpenCode Zen's free
tier, which needs no key.

models.dev's copy can lag NIM both ways. In October 2026 it listed 52 NIM
models against the 85 NIM's own list (`GET
https://integrate.api.nvidia.com/v1/models`, no key needed) served, and one
model the app offered (`nvidia/nemotron-voicechat`) was no longer served. NIM's
list gives only ids, not whether a model calls tools. So since 0.6.2, with a
NIM key stored, each opencode start reads NIM's list (`NvidiaNim`, cached in
`/root/.config/poordsh/nvidia-served.json`) and writes it as
`provider.nvidia.whitelist`: a model is offered when both lists have it and
models.dev says it calls tools. Without NIM's list (offline on the first start,
or an empty answer) no whitelist is written and models.dev's list stays.

## Consequences

New NIM models appear when models.dev lists them, without an app update; ones
NIM stops serving disappear at the next start even before models.dev drops them. The
key is not checked when saved, because NIM's model list answers any key; a
wrong key shows as an authorization error on the first message. NIM runs
wherever the chat runs (Tensorlake or the device), since only opencode's tools
touch the sandbox.
