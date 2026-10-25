# Loopback is not trusted

Status: accepted (2026-10, revised in 0.2.0, 0.3.0 and 0.11.0)

## Context

Any app on the device can reach 127.0.0.1.

## Decision

`opencode serve` gets a random password on every launch, and the device bridge
to the chats' jails and the repo tool (`PhoneSandboxServer`) a random bearer
token per app process. Tensorlake sandboxes expose no port.

## Consequences

Anything else that listens on the phone needs its own per-launch secret.
