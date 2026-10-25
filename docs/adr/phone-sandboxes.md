# Without a Tensorlake key, chats work in jails on the device

Status: accepted (2026-10, 0.3.0; revised in 0.11.0, opencode only)

## Context

Tensorlake should be optional. Without a key the app should still work, on the
phone's storage, with the agent still sandboxed, for every provider's models.
Sandboxes that use bubblewrap or Landlock do not work under PRoot, and
Android's seccomp policy for app processes does not allow the Landlock system
calls, so the Landlock-based isolation some on-device agents use is not open to
a normal app. A chat must keep one workspace when it switches model or
provider, as it does in Tensorlake.

## Decision

One sandbox per chat, shared by every provider, provided by PoorHarness: a
PRoot jail. `files/sandboxes/<chat id>/` holds the chat's `home`,
`tmp` and `workspace`, bound as `/root`, `/tmp` and `/tmp/workspace` into a view
of the app's Ubuntu image (`Proot.Jail`). The app's own home (opencode's data
and keys) and the phone's storage are not in the view. A chat's place,
`cloud` or `phone`, is fixed on its first message: Tensorlake when a key is set
([chat-sandboxes](chat-sandboxes.md)), otherwise the jail.

- opencode's tools keep tensorlake-opencode's implementations. PoorHarness's plugin
  routes the command and file operations of a `phone:<chat>` sandbox to a
  loopback bridge (`PhoneSandboxServer`, a per-process token), which passes them
  to an agent running in the jail (`PhoneAgent`, python3, JSON lines over its
  pipes, `PhoneSandboxManager`).
- The app itself reaches the jail the same way, for [git repositories](git-repos.md)
  (scripts through the agent, files directly in the jail's `tmp`).
- The agent starts on first use and stops 60 s after the last. A stop ends the
  jail's processes; the files stay until the chat is deleted.
- The Files screen reads the workspace folder directly and refuses anything
  whose real path leaves it.

## Consequences

The jail confines paths; it is not a kernel boundary like a Tensorlake machine.
Programs in it run as the app's Android user, so a program written to look can
see the app's other processes in `/proc`, including the environment of the
opencode server (its password, the device bridge's token, and the Tensorlake key
when one is set). The OS image is shared by all jails and writable: a package
installed in one chat is there in the others, and "Reinstall Ubuntu" restores
the image. Work from untrusted sources belongs in Tensorlake.

A jail's background processes end when it goes idle. A `cloud` chat stays in
the cloud if the key is removed later, and fails until the key is back.

Supersedes [ui-tensorlake-key-gate](ui-tensorlake-key-gate.md).
