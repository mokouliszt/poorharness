# Engine lifetime is counted in activity tokens

Status: accepted (2026-10, revised in 0.2.0, 0.3.0 and 0.11.0)

## Context

Engines and sandboxes should stop when nothing needs them
([engines-on-demand](engines-on-demand.md)), and many things can need them:
turns, messages waiting for a start, sign-ins, one-off calls.

## Decision

`EngineManager` keeps, per engine, a set of tokens: `turn:<session>` from
`chat.status` busy/idle, `send:<chat>` while a message waits for its engine,
and one-off calls through `use()`. When the last token goes, a 60 s idle timer starts; at its end the
engine stops unless a token came back. `SandboxManager` counts each sandbox's
use the same way (`send:<chat>`, `turn:<session>`, `files`, `git:<id>` while
the app moves a repository) and suspends it 60 s after the last token; making room on the free
plan only ever suspends a sandbox with no tokens. `PhoneSandboxManager` does the
same for device jails (plus `op:` while a bridge call runs) and stops a jail's
agent 60 s after its last token. A busy subagent holds its chat's sandbox with
its own `turn:` token ([subagent-sessions](subagent-sessions.md)). A settings change that needs
a restart (approvals, seccomp, Tensorlake, engine version) stops an idle
engine at once and a busy one when its last token goes. After each start the
model list is refreshed in the background and written to `files/cache/`;
`models.list` reads only that cache. On first run, or with no cache, the engine
is started once to fill it.

## Consequences

An engine or sandbox runs as long as any token is held, so every token is
released on every path, errors included.
