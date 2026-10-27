# Design decisions

One file per decision: the context that made it necessary, what was decided,
and what follows from it. Read the ones about the part you are changing before
you change it.

To change a decision, revise its file and note the version in its status line.
When the reasoning itself no longer holds, add a new file and mark the old one
`superseded by <new file>`; superseded files stay, so the history of a choice
can be followed. Procedures that are not decisions are in
[`docs/maintenance.md`](../maintenance.md).

## Whole project

- [opencode is the only engine](opencode-only.md)
- [Official engine, not a reimplementation](official-engines.md)
- [Nothing about models is hardcoded](models-from-engines.md)
- [The engine updates without an app update](engine-updates.md)
- [Directory layout follows how often things change](directory-layout.md)
- [Licensing](licensing.md)
- [User data outside the image](user-data.md)
- [Secrets](secrets.md)
- [Chats belong to the app, not to an engine](chats-belong-to-the-app.md)
- [With a Tensorlake key, every chat works in its own sandbox](chat-sandboxes.md)
- [Without a Tensorlake key, chats work in jails on the device](phone-sandboxes.md)
- [NVIDIA NIM through opencode's own provider](nvidia-nim.md)
- [OrcaRouter's free models, listed by OrcaRouter](orcarouter-free-models.md)
- [Infron's free models, with the provider defined by the app](infron-free-models.md)
- [Venice's uncensored models under a price cap, with the provider defined by the app](venice.md)
- [The app is PoorHarness; its identifiers keep the old name](app-name.md)
- [Engine options: useful features off by default, and subagents](engine-options.md)
- [Skills live in one place, read where they are used](skills.md)
- [Plugins: opencode gets the parts it has](plugins.md)
- [Attached files go to the sandbox, and to the model when it can take them](attachments.md)
- [Agents hand files over through one flat outputs folder](outputs.md)
- [An S3-compatible bucket, one folder per use](s3-storage.md)
- [One system prompt for every chat](system-prompt.md)
- [Git repositories for agents, kept in the bucket](git-repos.md)
- [One model for subagents, from any provider](subagent-model.md)
- [One model for chat titles, from any provider](title-model.md)
- [The agent can ask the user questions](questions.md)
- [Web access from the chat's sandbox, with TinyFish for usage limits](web-in-sandbox.md)
- [Tensorlake on the free plan by default](tensorlake-free-plan.md)
- [The engine runs only while it is needed](engines-on-demand.md)
- [The phone is the sandbox](phone-is-the-sandbox.md) (superseded)

## On-device runtime (`runtime/`)

- [On-device Linux via PRoot from nativeLibraryDir](proot-on-android.md)
- [PRoot from termux/proot, built with the NDK](proot-build.md)
- [Ubuntu 24.04 base, packages installed with apt under qemu](ubuntu-image.md)
- [Archive format of the image](rootfs-archive.md)
- [opencode inside the image, its Tensorlake plugin on the device](engine-installs.md)

## Host app (`android/`)

- [One Activity, one WebView, one bridge](one-webview.md)
- [A foreground service only while something runs](foreground-service.md)
- [Build inputs are generated, never committed](build-inputs.md)
- [Android targets](android-targets.md)
- [Tests](tests.md)
- [Engines are normalized at the edge](engines-normalized-at-the-edge.md)
- [Ids carry their engine](ids-carry-their-engine.md)
- [Each engine fails on its own](engines-fail-on-their-own.md)
- [Loopback is not trusted](loopback-is-not-trusted.md)
- [The opencode client heals itself](opencode-self-healing.md)
- [Engine lifetime is counted in activity tokens](activity-tokens.md)
- [Stopping PRoot means SIGQUIT](stopping-proot.md)
- [Codex in a sandbox, over WebSocket](codex-over-websocket.md) (superseded)
- [Subagent sessions report to their chat](subagent-sessions.md)
- [opencode's tools fail closed](opencode-tools-fail-closed.md)

## Interface (`webui/`)

- [The UI runs without the phone](ui-runs-without-the-phone.md)
- [Monochrome on true black](ui-monochrome.md)
- [Old WebViews](ui-old-webviews.md)
- [Copy](ui-copy.md)
- [One store, plain functions](ui-one-store.md)
- [Navigation is a stack in the store](ui-navigation-stack.md)
- [Model choice](ui-model-choice.md)
- [The Tensorlake key gates every chat](ui-tensorlake-key-gate.md) (superseded)
- [Files are taken out from the Files screen](ui-files-screen.md)

## Build (`tools/`)

- [Build scripts only orchestrate](scripts-only-orchestrate.md)
- [Icons are generated, then committed](generated-icons.md)
