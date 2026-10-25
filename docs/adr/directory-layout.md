# Directory layout follows how often things change

Status: accepted (2026-10, revised in 0.3.0 and 0.4.0)

## Context

PoorHarness ties together pieces that change at very different rates: the UI with
almost every release, the engine clients with upstream releases, the Linux
runtime only on version bumps, the artwork almost never. When they are mixed, a
small change touches unrelated build steps and an upstream change is hard to
contain.

## Decision

```
webui/     UI, changes most often
android/   host app, changes with Android and engine protocols
runtime/   recipes for the on-device runtime, change on version bumps
tools/     scripts that tie the steps together
assets/    source artwork
docs/      references that span host and UI; docs/adr/ holds these decisions
```

Generated files go only to `android/app/build/generated/poordsh/` and are never
committed ([build-inputs](build-inputs.md)). Each step can run on its own;
`tools/build-apk.sh` reuses a built runtime unless asked to rebuild it
([scripts-only-orchestrate](scripts-only-orchestrate.md)).

In the host app, code that knows an engine's or a service's wire format stays
in that engine's or service's package:

| Package (`android/app/src/main/java/dev/mokouliszt/poordsh/`) | Role |
|---|---|
| `runtime/` | rootfs install, PRoot command line, guest files, process supervision, logs, safe archive unpacking |
| `engine/` | `EngineManager`: starts the engine on demand, stops it when idle, caches models |
| `chat/` | `ChatStore` (chats on disk), `ChatService` (engine events → chat events, turn copies), `Handoff` |
| `backend/` | `AgentBackend` contract, `EventBus`, composite ids, `Place`, `EngineOptions`, `SystemPrompt` |
| `backend/opencode/` | `opencode serve` client (HTTP + SSE, basic auth per launch); its tools live in sandboxes via `assets/opencode/poordsh-sandboxes.js` |
| `tensorlake/` | `TensorlakeApi` (management and sandbox HTTP APIs), `SandboxManager` (one sandbox per chat: create, suspend when idle, delete) |
| `phone/` | device jails for chats without a Tensorlake key: `PhoneSandboxManager` (one PRoot jail per chat), `PhoneAgent` and `AgentChannel` (the agent in a jail and its pipe protocol), `PhoneSandboxServer` (loopback bridge for opencode's plugin) |
| `skills/` | `SkillPackage` (checks a `.skill`/`.zip` like skill-creator), `SkillStore` (the user's skills on the phone), `FolderSync` (copies a store of folders into a chat's sandbox), `SkillSync` (where skills go) |
| `plugins/` | `PluginPackage` (reads a plugin archive and its manifest), `PluginStore` (the user's plugins); what opencode uses of them is `OpencodePlugins` |
| `outputs/` | `Outputs` (the outputs folder and what is handed over), `OutputService` (at the end of a turn), `OutputShelf` (kept on the phone or in the bucket), `PhoneOutputs`/`CloudOutputs` (reading a jail's or a Tensorlake sandbox's folder) |
| `storage/` | `S3Storage` (the user's S3-compatible bucket, one folder per use) |
| `git/` | `GitBucket` (repositories in the bucket, git-remote-s3's layout), `GitRepos` (open, create, save back), `SandboxShell` (scripts and files in a Tensorlake sandbox or a jail) |
| `attachments/` | `AttachmentService` (picked files: space checks, staging, into the sandbox), `Attachments` (names, what reaches the model directly), `AttachmentNote` (what the model is told) |
| `bridge/` | WebView bridge, JSON helpers, `FileExport` (sandbox files to Downloads or the share sheet), `DocumentPicker` (the system file picker) |
| `secrets/` | Keystore-encrypted API keys |
| `settings/` | plain preferences |
| `update/` | engine updates from npm |
| `service/` | foreground service and `Keepalive` (who needs it running) |

Hand-written assets, such as PoorHarness's opencode plugin, are source and live in
`android/app/src/main/assets/`.

The UI keeps everything that talks to the host in `webui/src/lib/`:

| Path (`webui/src/`) | Role |
|---|---|
| `lib/bridge.ts` | request/response and events with the host |
| `lib/store.ts` | all app state (zustand), navigation stack, event handling |
| `lib/types.ts` | shapes shared with the host (`docs/bridge-protocol.md`) |
| `lib/i18n.ts` | strings, and wording for the host's error codes |
| `lib/mock.ts` | browser stand-in for the host |
| `screens/` | one file per screen |
| `components/` | shared pieces; `components/ui/` holds primitives |

The runtime recipes run on the build host and write into the generated folder.
Versions and checksums live only in `runtime/versions.env`; every download is
verified before use.

| Path | Output |
|---|---|
| `runtime/proot/build.sh` | `jniLibs/arm64-v8a/libproot.so`, `libproot-loader.so` |
| `runtime/rootfs/build.sh` | `assets/runtime/rootfs.tar.xz`, `rootfs.json` |

| Script (`tools/`) | Does |
|---|---|
| `build-apk.sh` | PRoot → rootfs → web UI → APK; reuses built runtime pieces unless `--rebuild-runtime` |
| `make-icons.py` | launcher and status-bar icons and web logos from `assets/icon-source.png` |

`assets/icon-source.png` is the master launcher artwork (1254 × 1254, a figure
hanging from a rope on a black rounded tile).

Decisions are recorded in `docs/adr/`, one file per decision, named for what it
decides (index: [README](README.md)). Procedures that are not decisions, such
as version bumps, are in [`docs/maintenance.md`](../maintenance.md).

## Consequences

A UI-only change rebuilds only the web UI; a runtime bump is an edit to
`versions.env` plus a rebuild. An engine's protocol change stays inside its
`backend/<engine>/` package.
