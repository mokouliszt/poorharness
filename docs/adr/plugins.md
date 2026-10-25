# Plugins: opencode gets the parts it has

Status: accepted (2026-10, 0.8.0; revised in 0.11.0, opencode only)

## Context

Users want to add plugin archives the way they add skills. A plugin is a
folder with a manifest and parts in known places: skills, MCP servers, hooks,
subagents, slash commands, apps. Several formats are in use:

- Agent Plugins (agent-plugins.org), an open format: root `plugin.json` with
  `$schema`, `skills/` and `mcp.json`.
- Codex's `.codex-plugin/plugin.json`, Claude's `.claude-plugin/plugin.json`
  (with `commands/`, `agents/`, `hooks/hooks.json`, `.mcp.json`) and Cursor's
  `.cursor-plugin/plugin.json`. A manifest can point at its parts elsewhere.

opencode (1.19) has no bundles like these. Its plugins are JavaScript run in
its own process, on the phone, where PoorHarness runs no tools
([opencode-tools-fail-closed](opencode-tools-fail-closed.md)). It does document
skill folders (`skills.paths`), remote and command MCP servers (`mcp`), and
subagents (`agent`). Every chat runs in its own sandbox, Tensorlake or a device
jail.

From 0.8 to 0.10, Codex also installed the plugins through its own plugin
system, with a marketplace file and per-hook trust; that went with Codex
([opencode-only](opencode-only.md)).

## Decision

- The store is `files/plugins/<name>/` (`PluginStore`). Settings adds a `.zip`
  or `.plugin` archive and removes one. An archive is accepted when it holds one
  plugin, at its top or in its single top folder, with one of the four
  manifests; a root `plugin.json` counts only with the Agent Plugins `$schema`.
  The name follows the Agent Plugins rule (lowercase letters, digits, `-` and
  `.`, at most 64 characters) and must be the same in every manifest present.
  A skill or a marketplace of several plugins is refused with its own message.
  Unsafe paths and oversized archives are refused as for skills; files that
  start with `#!` or are Linux binaries are made executable, since zip seldom
  keeps Unix modes and skills run such files by path.
- `/tmp/poordsh/plugins` means the same everywhere, as for skills: opencode's
  PRoot binds the store there, and before every turn the chat's sandbox gets a
  copy there (`FolderSync`, shared with skills: one manifest of digests, only
  changed folders move, executables are marked).
- At each start (`OpencodePlugins`):
  - the plugins' skill folders go into `skills.paths`, their names into the
    `skill` permission;
  - MCP servers with a URL become remote servers in `mcp`; their tools
    (`<server>_*`) are allowed in full auto and asked for otherwise, and the
    app's opencode plugin lets them through (`POORDSH_MCP_SERVERS`). Servers
    started as a command are not used: under opencode they would run on the
    phone, outside the chat's sandbox;
  - Claude-style agents become subagents named `<plugin>:<agent>`, the name
    Claude gives them, so they never replace opencode's own; a `tools` list
    is mapped onto opencode's permissions and the rest are denied. With a
    [subagent model](subagent-model.md) set, they run on it.
  Adding or removing a plugin restarts opencode when it is next idle.
- Hooks, commands and apps are not used. Settings shows, for each part of a
  plugin, whether it is.

## Consequences

- Nothing in a plugin runs on the phone.
- MCP servers that need a sign-in (OAuth) do not connect: no sign-in flow is
  offered in the app.
- A plugin skill keeps its own name, so one named like another skill can hide it.
- Verified with opencode 1.19.0 with tensorlake-opencode 0.3.1 (a plugin skill
  with a reference file, a remote MCP tool, a plugin subagent). Updating
  opencode means re-checking these ([`docs/maintenance.md`](../maintenance.md)).
