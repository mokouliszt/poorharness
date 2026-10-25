# The Tensorlake key gates every chat

Status: superseded by [phone-sandboxes](phone-sandboxes.md) (0.3.0): without
a key, chats run on the device and the key is optional in Settings

## Context

Chats run in Tensorlake sandboxes ([chat-sandboxes](chat-sandboxes.md)).

## Decision

Without the key, Home and every chat show a banner and the composer cannot
send; ChatGPT sign-in (which runs in a sandbox) asks for the key first. All of
these open the same sheet (`setKeySheet`). Host errors that are codes
([`docs/bridge-protocol.md`](../bridge-protocol.md), "Errors") are worded by
`describeError` wherever they are shown.

## Consequences

Nothing can be tried before a Tensorlake account exists.
