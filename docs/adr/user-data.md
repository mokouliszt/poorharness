# User data outside the image

Status: accepted (2026-10, revised in 0.2.0, 0.3.0, 0.4.0, 0.8.0, 0.9.0, 0.10.0 and 0.11.0)

## Context

The rootfs image is replaceable: a new app version or "Reinstall Ubuntu"
unpacks a fresh one.

## Decision

Engine sign-ins and opencode's session store live in `files/runtime/home`,
bind-mounted as `/root`. Engine updates live in `files/runtime/tools`. Chats as
the app shows them live in `files/chats`
([chats-belong-to-the-app](chats-belong-to-the-app.md)). Work files live in each chat's
sandbox: in Tensorlake ([chat-sandboxes](chat-sandboxes.md)), or, for chats
without a key, in `files/sandboxes/<chat id>`
([phone-sandboxes](phone-sandboxes.md)). The user's skills live in `files/skills`
([skills](skills.md)), their plugins in `files/plugins` ([plugins](plugins.md));
files picked for a message wait in `files/attachments` until it is sent
([attachments](attachments.md)). Files agents hand over are kept in
`files/runtime/home/outputs` (the Ubuntu home's `outputs`) or the user's bucket
([outputs](outputs.md)). Agents' git repositories are in the bucket; which chats
have one open is in `files/git-chats.json` ([git-repos](git-repos.md)). The
system prompt and the subagents' model are among the app's settings.

## Consequences

Replacing the image loses nothing the user made or signed in with.
