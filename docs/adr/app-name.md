# The app is PoorHarness; its identifiers keep the old name

Status: accepted (2026-10, 0.5.0)

## Context

The app was called PoorDSH after
[deepseek-harness-android](https://github.com/dphmoblie/deepseek-harness-android)
(DSH), whose way of running Linux on Android it follows. By 0.4 little else of
DSH was left: the engines were opencode and Codex (opencode alone since 0.11),
and the models come from several providers. The name is now PoorHarness.

The old name is also in identifiers that outlive an install: the application
id, Kotlin packages, the Keystore alias of the secret store, the Tensorlake
sandbox prefix (`poordsh-`), guest paths (`/root/.config/poordsh`,
`/opt/poordsh`, `/tmp/poordsh/skills`), the tags hidden in chat history
(`<poordsh-context>`, `<poordsh-attachments>`), and the plugin's environment
(`POORDSH_*`).

## Decision

Everything a person reads says PoorHarness: the launcher label, the UI, the
Download folder for saved files (`Download/PoorHarness`), the notes the agent
reads, the git identity of its commits, and the documentation.

The identifiers keep `poordsh`. Changing the application id would make 0.5.0 a
separate app that starts with no chats or keys; changing the others would
orphan existing sandboxes, stored keys and history.

## Consequences

Updates from 0.4 install over the old app and keep everything. Files saved
before 0.5.0 stay in `Download/PoorDSH`. Code and docs mix the two names: the
product is PoorHarness, and `poordsh` in an identifier is not a typo to fix.
