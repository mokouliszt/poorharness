# Nothing about models is hardcoded

Status: accepted (2026-10, revised in 0.4.1, 0.5.0 and 0.11.0)

## Context

Engines add and retire models and reasoning levels on their own schedule.
Anything the app fixed would need an app update to follow them.

## Decision

Model lists, default models and reasoning levels come from the engines at run
time (`/config/providers` from opencode). The app only remembers the user's
last choice (and the one for subagents, [subagent-model](subagent-model.md)).
opencode keeps its catalog current: it fetches models.dev's catalog (`models.opencode.ai`) when its copy is
over five minutes old. NVIDIA NIM models come through opencode's catalog too,
limited to the ones NIM's own list still serves ([nvidia-nim](nvidia-nim.md)).
OrcaRouter's and Infron's free models come from each gateway's own catalog,
written into the opencode config at each start
([orcarouter-free-models](orcarouter-free-models.md),
[infron-free-models](infron-free-models.md)).

A running opencode keeps the catalog it read first: one that arrives later
reaches neither `/config/providers` nor `/instance/dispose`, and its
`models-dev.refreshed` event is not sent to clients. So after starting
opencode the app waits, at most 12 s, for a fetch it started to land before it
reads providers (`ModelCatalog`); offline (opencode logs the failed fetch) or
with a fresh copy it does not wait.

## Consequences

New models appear without an app update, on the next start of their engine
(engines stop after a minute idle). The picker shows what the engines reported
on their last start, cached on disk ([activity-tokens](activity-tokens.md)). A
model that needs a newer engine appears once the engine is updated from
Settings ([engine-updates](engine-updates.md)). The wait relies on where
opencode keeps its copy (`~/.cache/opencode/models*.json`); if that moves, the
app simply does not wait, and a new model shows one start later.

The cached list is also dropped when the app version changes
(`EngineManager.dropStaleCatalogs`): an update does not start an engine, so
without this the picker would keep showing what the previous version stored (a
price or badge added since would be missing) until the next chat. Discovery then
starts the engine once and learns the list again.
