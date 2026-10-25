# The phone is the sandbox

Status: superseded by [chat-sandboxes](chat-sandboxes.md) (0.2.0)

## Context

bubblewrap and Landlock do not work under PRoot, so Codex could not use its own
sandbox on the phone.

## Decision

Codex ran with `danger-full-access`; agents ran as the app's UID and reached
only the app's private storage and the network. Approvals defaulted to full
auto; the settings offered "ask before commands" and "ask every time", mapped
onto each engine's permission settings (opencode `permission`, Codex
`approvalPolicy` per thread and turn — recent Codex refuses
`approval_policy = "untrusted"` in its config file but accepts it per turn).

## Consequences

Agents could read everything in the app's private storage, including other
chats' work. 0.2.0 moved all work into one Tensorlake sandbox per chat.
