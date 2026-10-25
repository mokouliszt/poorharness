# opencode inside the image, its Tensorlake plugin on the device

Status: accepted (2026-10, revised in 0.2.0, 0.3.0 and 0.11.0)

## Context

opencode runs on the phone, its tools in each chat's sandbox
([chat-sandboxes](chat-sandboxes.md)), and the Tensorlake plugin is too big to
bundle ([tensorlake-free-plan](tensorlake-free-plan.md)). Until 0.11 Codex was
installed into each sandbox from npm; it is gone ([opencode-only](opencode-only.md)).

## Decision

opencode is copied from its npm package into `/opt/poordsh/opencode/<version>/`
with a symlink in `/usr/local/bin`. The Tensorlake plugin is not bundled;
opencode installs it on the device on its first start.

## Consequences

The first opencode start downloads the plugin from npm (about 200 MB).
