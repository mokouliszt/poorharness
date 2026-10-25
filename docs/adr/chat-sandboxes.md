# With a Tensorlake key, every chat works in its own sandbox

Status: accepted (2026-10, 0.2.0; revised in 0.3.0: without a key, chats run in
[device jails](phone-sandboxes.md); in 0.11.0, opencode only)

## Context

Work should stay off the phone: no phone storage as the working folder,
results downloaded from the sandbox, and no agent able to see the phone's
files. Tensorlake deleting files after 7 days is acceptable.

## Decision

Each chat gets one sandbox, `poordsh-<chat id>`, working folder
`/tmp/workspace` (`tensorlake/SandboxManager.kt`). It is created on the chat's
first message, suspended 60 s after its last use, and deleted with the chat.
No port is exposed. opencode stays on the phone, because OpenCode Zen's free tier is limited per
IP and only answers the official client, but every tool it has that reads
files or runs commands is tensorlake-opencode's, routed to the chat's sandbox
by PoorHarness's plugin, which refuses to run anything if those tools are missing
([opencode-tools-fail-closed](opencode-tools-fail-closed.md)). Web fetches and
searches run there too ([web-in-sandbox](web-in-sandbox.md)).

Results leave the sandbox through the Files screen (one file or a zip, to
`Download/PoorHarness` or the share sheet; [ui-files-screen](ui-files-screen.md)),
the [outputs folder](outputs.md) and [git repositories](git-repos.md).
On the free plan one sandbox runs at a time: starting a chat suspends idle
ones, and a busy one makes it fail with `quota_busy`. Approvals still apply,
through opencode's `permission` config, which PoorHarness's plugin asks against.

## Consequences

This applies to chats whose first message was sent with a key; the place is
kept for the chat's life ([phone-sandboxes](phone-sandboxes.md)). The first
opencode start downloads the plugin. On the free plan only one chat works at a time. A
chat whose sandbox expired continues in a new, empty one.

Supersedes [phone-is-the-sandbox](phone-is-the-sandbox.md).
