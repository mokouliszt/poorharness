# opencode's tools fail closed

Status: accepted (2026-10, 0.2.0; revised in 0.3.0 and 0.4.0)

## Context

opencode runs on the phone, and its built-in tools would read and run things
there. tensorlake-opencode replaces them with sandbox tools, but if its install
fails (it is ~200 MB from npm on the first start), opencode quietly keeps its
own.

## Decision

opencode's config denies every tool by default and allows only the sandbox
tools and ones that touch neither phone nor sandbox (`OpencodeConfig`).
PoorHarness's plugin (`android/app/src/main/assets/opencode/poordsh-sandboxes.js`),
loaded last, re-registers the official plugin's tools (routed to the chat's
Tensorlake sandbox or, for `phone:<chat>`, to its device jail through the
loopback bridge; with approval asks) or, if they are missing, stand-ins that refuse;
its `tool.execute.before` hook refuses tools it does not know. It reports to a
status file, and `OpencodeBackend` loads the plugins right after start-up and
stops opencode with `tensorlake_plugin_unavailable` unless the report is good
for this launch.

## Consequences

opencode's `question` tool is let through since 0.12 ([questions](questions.md)); its
`skill` tool only when the user has skills, for those skills alone ([skills](skills.md)). A new opencode tool
stays unavailable until PoorHarness lists it. The plugin relies on
tensorlake-opencode's package layout: its entry point (`dist/index.js`, the
plugin function opencode itself calls) and `TensorlakeClient` in
`dist/tensorlake/core/client.js`: it patches `getOrCreateSandbox`,
`executeCommand`, `readFile`, `writeFile`, `listDirectory`, `deleteSandbox`
and, when present, `suspendSandboxSync`. Device jails also rely on the official
plugin's "no credentials" check accepting a placeholder key. When bumping
`TENSORLAKE_PLUGIN_VERSION`, re-run the checks in
[`docs/bridge-protocol.md`](../bridge-protocol.md), "opencode mapping".
