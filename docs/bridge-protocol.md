# Bridge protocol

How the web UI and the Kotlin host talk, how chats map onto their sandboxes
(Tensorlake, or a jail on the device), and how opencode maps onto them. Types on the UI side:
`webui/src/lib/types.ts`.

## Transport

- UI → host: `PoorDSHNative.request(JSON.stringify({ id, method, params }))`
- host → UI reply: `window.__poordsh.onResponse({ id, ok, result | error })`
- host → UI events: `window.__poordsh.onEvent({ type, ... })`
- back gesture: host calls `window.__poordsh.onBack()`; `true` means handled

Chats belong to the app: a "session" in this protocol is a chat (`c_<hex>`),
stored by the host under `files/chats/` and readable while every engine is
stopped. A chat is a run of segments, each one engine session; the UI never
sees engine session ids. Approval requests and models are `"<engine>:<raw
id>"`; the engine is `opencode` (stored chats from before 0.11 also hold
`codex` segments, [opencode-only](adr/opencode-only.md)).

Engines run on demand (see [activity-tokens](adr/activity-tokens.md)): a message starts its
engine, and the engine stops 60 s after its last turn.

## Sandboxes

Every chat works in one sandbox, in `/tmp/workspace`, chosen on its first
message and kept for its life (`SessionInfo.place`): with a Tensorlake key a
Tensorlake sandbox (`cloud`), without one a PRoot jail on the device (`phone`).
Every provider in a chat uses that one sandbox, so files stay put when a chat
moves between models. Chats from before 0.3 are
`cloud`. A `cloud` chat whose key was removed fails with
`tensorlake_key_missing` until the key is back.

### Tensorlake (`cloud`)

Named `poordsh-<chat id>` (`SandboxManager.nameFor`). Nothing of the phone is
visible to the agent: opencode (which stays on the phone) gets only tools that
run there.

- Created on the chat's first message (1 vCPU, 1 GB, 10 GB disk: the free
  plan's limits), with no port exposed.
- Suspended 60 s after the last thing that used it (a turn, a message waiting
  to start, a file download); suspending keeps disk, memory and processes, and
  any use resumes it. Deleted with the chat (retried on the next start if
  Tensorlake could not be reached).
- The free plan runs one sandbox at a time. Starting another first suspends idle
  `poordsh-*` sandboxes; if the running one is busy, the call fails with
  `quota_busy`. Sandboxes with other names (for example `opencode-*` from the
  user's own opencode) are never touched.
- Tensorlake may delete a suspended sandbox after 7 days. The next message then
  creates a new one, and the chat starts a new segment whose hand-off says the
  old files are gone.

### Device jail (`phone`)

`files/sandboxes/<chat id>/{home,tmp,workspace}`, bound as `/root`, `/tmp` and
`/tmp/workspace` into a PRoot view of the app's Ubuntu image (`Proot.Jail`,
`phone/PhoneSandboxManager.kt`). The app's own home (opencode's data and keys)
and the phone's storage are not bound; the OS image is shared by every jail and
writable, like a container's base layer
([phone-sandboxes](adr/phone-sandboxes.md)).

- opencode's sandbox tools reach the jail through the device bridge (below): a
  JSON-lines agent (`PhoneAgent`, python3) runs in the jail and does `run`,
  `read`, `write`, `list`. It starts on first use and stops 60 s after the last
  use, which ends the jail's processes; the files stay.
- Files never expire; the jail is deleted with the chat. `files.*` read the
  workspace folder directly and refuse paths whose real location is outside it.

### Device bridge

`PhoneSandboxServer`: HTTP/1.1 on `127.0.0.1:<random>`, one per app process,
with `Authorization: Bearer <token>`; requests without it get 401. Bodies are
JSON with `chatId`, except `/repo` and `/web`.

| Path | Body | Reply |
|---|---|---|
| `/run` | `{chatId, command, cwd, timeoutMs}` | `{exitCode, stdout, stderr}` (`sh -c`; 124 on timeout; output goes to temp files, so `cmd &` does not hold the reply) |
| `/read` | `{chatId, path}` | `{ok, dataB64}` or `{error}` |
| `/write` | `{chatId, path, dataB64}` | `{ok, bytes}` (parent folders created) |
| `/list` | `{chatId, path}` | `{entries[{name, is_dir, size}]}` |
| `/web` | `{kind: "fetch", url, format}` or `{kind: "search", query, numResults}` | `{text}`: TinyFish's answer, shaped like opencode's ([web-in-sandbox](adr/web-in-sandbox.md)); `{error}` (500) with TinyFish's reason, or when no key is set |
| `/repo` | `{sandbox, action: "list" \| "open" \| "create", name}` | `{text}`: what the model reads ([git-repos](adr/git-repos.md)); `sandbox` is the map's value for the session's top session, from which the host finds the chat |

## Methods

| Method | Params | Result |
|---|---|---|
| `app.info` | – | `{version, workspace, android, device, abi}` (`workspace` is the folder in every sandbox and jail) |
| `app.notices` | – | third-party notices (markdown) |
| `runtime.state` | – | `RuntimeState` (phase `idle \| installing \| ready \| failed`, plus `backends`) |
| `runtime.start` | – | – (unpacks Ubuntu if needed) |
| `runtime.reinstall` / `runtime.logs` | – | – (refused while anything runs) / `[{source, lines}]` |
| `engines.start` | `{target: engine}` | – (starts it; it stops again when idle) |
| `engines.stop` | – | – (stops idle engines now, busy ones when their turn ends) |
| `engines.versions` | – | `{opencode: {current, bundled}}` (no network; `bundled`: the version this app ships with is in use) |
| `models.list` | – | `{items: ModelInfo[], errors: {engine: message}, pending: engine[]}` from the host's cache; `pending` engines are starting once to report their models |
| `sessions.list` | – | `{items: SessionInfo[], errors}` |
| `sessions.create` | – | `SessionInfo` (an empty chat; no engine or sandbox is started) |
| `sessions.messages` | `{sessionId}` | `Message[]` (stored copy; mid-turn, refreshed from the engine first) |
| `sessions.delete` | `{sessionId}` | – (engine copies are deleted now or on the engine's next start; the sandbox goes too) |
| `chat.send` | `{sessionId, text, model, effort?, modelName?, attachments?: id[]}` | – after the sandbox is running, its skills and plugins are synced, the files are in it and the engine has taken the prompt; the reply streams as events. `text` may be empty when files are attached. The first message fixes the chat's place. A chat that needs a new session gets its history handed over (below) |
| `attachments.add` | `{sessionId \| null}` | `{items[{id, name, size, mime}], errors[{name, error, where?, needed?, free?}]}` — opens the system picker (several files) and copies them in; a file that would not fit on the phone (`where: "device"`) or in the chat's sandbox (`"sandbox"`) is refused with `attach_no_space` ([attachments](adr/attachments.md)) |
| `attachments.discard` | `{ids}` | – (removed from the composer) |
| `skills.list` | – | `[{name, description, files, bytes}]` |
| `skills.add` | – | `{name, description, files, bytes}` or `null` (picker closed); an archive skill-creator would not accept fails with `skill_invalid: <reason>` ([skills](adr/skills.md)) |
| `skills.delete` | `{name}` | – |
| `plugins.list` | – | `PluginInfo[]`: `{name, version, description, format, files, bytes, skills[{name, description}], mcpServers[{name, remote, url, command}], hooks[{event, command}], agents[{name, description}], commands, apps}`; `format` is `agent-plugins`, `codex`, `claude` or `cursor` ([plugins](adr/plugins.md)) |
| `plugins.add` | – | `PluginInfo` or `null` (picker closed); an archive that is not one plugin fails with `plugin_invalid:<reason>: <message>` |
| `plugins.delete` | `{name}` | – |
| `storage.get` | – | `{destination: "device" \| "s3", s3: {endpoint, bucket, accessKeyId, region, prefix} \| null}` (never the secret) |
| `storage.setS3` | `{endpoint, bucket, accessKeyId, secret?, region?, prefix?}` | `{s3}` after a test object was written, read back and deleted; `secret` may be left out to keep the stored one for the same key ID. Fails with `s3_denied`, `s3_no_bucket`, `s3_unreachable`, `s3_bad_endpoint`, `s3_secret_missing` or `s3_failed: …` ([s3-storage](adr/s3-storage.md)) |
| `storage.clearS3` | – | – (new outputs go to the phone again; opencode drops the repo tool when idle) |
| `storage.setDestination` | `{destination}` | – (`s3_not_connected` without a bucket) |
| `outputs.list` | `{shelf}` | `{items[{chatId, chatTitle, name, size, modifiedAt}], total}` |
| `outputs.export` | `{shelf, chatId, name, action: "save" \| "share"}` | `{where, name}`; `output_missing` when it is no longer kept |
| `outputs.delete` | `{shelf, chatId, name}` | – |
| `outputs.deleteAll` | `{shelf}` | – (everything on that shelf) |
| `git.list` | – | `{items[{name, head, branches[], size, modifiedAt, url}], total}` — the bucket's repositories; `url` is their git-remote-s3 address ([git-repos](adr/git-repos.md)) |
| `git.export` | `{name, action}` | `{where, name}` — the default branch's bundle |
| `git.delete` / `git.deleteAll` | `{name}` / – | – |
| `chat.abort` | `{sessionId}` | – (while the sandbox or engine is still starting, the message is dropped; the chat's opencode subagents are stopped too) |
| `approval.reply` | `{requestId, decision: "once" \| "session" \| "deny"}` | – |
| `question.reply` | `{requestId, answers?: string[][]}` | – (one list of labels or typed text per question, in order; without `answers` the questions are dismissed and the turn ends; [questions](adr/questions.md)) |
| `accounts.get` | – | `{opencode: {keys, state}, tensorlake: {keySet}, tinyfish: {keySet}}` — `keys`: the providers below whose key is stored |
| `opencode.setKey` | `{provider: "opencode" \| "nvidia" \| "orcarouter" \| "infron" \| "venice", key \| null}` | – (OpenCode Zen, NVIDIA NIM, OrcaRouter, Infron or Venice key in opencode's own store; the model list is refreshed) |
| `options.get` | – | `{items[{id, engine, default, requires}], enabled: id[]}` (switchable engine features, `default` true for subagents; `requires` an option it does nothing without; [engine-options](adr/engine-options.md)) |
| `options.set` | `{enabled: id[]}` | `{enabled}` (every option is stored as on or off; unknown ids dropped; applied when the engine next starts) |
| `tinyfish.set` | `{key \| null}` | – (checked with TinyFish before it is kept: `tinyfish_key_invalid`; read at each web fallback, so nothing restarts) |
| `tensorlake.set` | `{key \| null}` | – (checked with Tensorlake before it is kept: `tensorlake_key_invalid`) |
| `files.list` | `{sessionId, path?}` | `{path, entries[{name, dir, size, modifiedAt}], missing?}` — folders first; `missing` when the chat has no sandbox (never ran, or Tensorlake deleted it) |
| `files.export` | `{sessionId, path, action: "save" \| "share"}` | `{where: "downloads" \| "share", name}` — saved to `Download/PoorHarness` (Android 10+) or offered to the share sheet |
| `files.zip` | `{sessionId, action}` | same; the whole workspace without `node_modules`, `.venv`, `venv`, `__pycache__` |
| `settings.get` / `settings.set` | – / partial `Settings` | `Settings` (`autoUpdate`: install newer engine releases found at launch; turning it on installs them at once. `systemPrompt`: given to opencode from the next message, `system_prompt_too_long` past 20,000 characters. `subagentModel` (`"opencode:<provider>/<model>"` or null for the chat's) and `subagentEffort`: what subagents run on, from opencode's next start, [subagent-model](adr/subagent-model.md)) |
| `updates.check` | – | `{opencode: ToolUpdateInfo}` |
| `updates.last` | – | the check run when the app opened, same shape, or `null` if none ran yet |
| `updates.apply` / `updates.reset` | `{tool}` | `{version}` / – |
| `app.openUrl` / `app.copy` / `app.share` | `{url}` / `{text}` / `{text}` | – |
| `app.requestNotifications` | – | – |

Paths in `files.*` must be `/tmp/workspace` or below it, without `..`. For
`cloud` chats the `files.*` methods wake a suspended sandbox but never create
one; for `phone` chats they read the jail's folder without starting it.

## Events

| type | payload |
|---|---|
| `runtime.state` | `RuntimeState` without `backends` (keep the last ones) |
| `backend.state` | `{backend, state}` — `stopped` is normal; `failed` means a start failed (`detail` may be an error code); opencode reports `starting` with `detail: "plugin"` while its first start downloads the Tensorlake plugin |
| `backend.resync` | `{backend}` — the event stream reconnected; reload |
| `models.changed`, `account.changed` | `{backend}` (`tensorlake` and `tinyfish` too) — re-read `models.list` / `accounts.get` |
| `updates.changed` | `{result}` — an update check ran (at launch, or after an automatic update); `result` is what `updates.check` returns |
| `key.progress` | `{provider, stage: "starting" \| "saving" \| "models"}` — during `opencode.setKey`: opencode is starting, the key (and a gateway's catalog) is being stored, the models are reloading |
| `sessions.upsert` | `{session}` |
| `sessions.changed` | – re-read `sessions.list` (chats imported from an engine) |
| `chat.stage` | `{sessionId, stage: "sandbox" \| "upload" \| "outputs" \| "repos" \| null, progress?}` — what a message waits for: the sandbox, its attached files going in (`progress` 0..1); at the end of a turn, its outputs being handed over and its git repositories saved; `null` when done |
| `chat.background` | `{sessionId, count}` — opencode subagents of the chat still working after its own turn ended |
| `chat.message` | `{sessionId, message}` (parts may be empty; carries `segment`, `engine`, `segmentModel`) |
| `chat.part` | `{sessionId, messageId, part}` (replace by part id) |
| `chat.delta` | `{sessionId, messageId, partId, field: "text" \| "output", delta}` |
| `chat.partRemoved` | `{sessionId, messageId, partId}` |
| `chat.status` | `{sessionId, status: "busy" \| "idle", detail?, retryAt?}` — `idle` comes after the turn is stored, so `sessions.messages` then has it; `detail` and `retryAt` (epoch ms) when the engine waits to try again, e.g. after a provider's rate limit |
| `chat.error` | `{sessionId, message}` |
| `chat.todo` | `{sessionId, items: [{text, status}]}` |
| `approval.request` / `approval.resolved` | `{requestId, sessionId, kind, title, detail}` / `{requestId}` — a subagent's requests carry its chat's id; `kind` is `bash`, `edit`, `tool` (a plugin's MCP tool or the repo tool) or the engine's own name for the permission |
| `question.request` / `question.resolved` | `{requestId, sessionId, questions[{header, question, options[{label, description}], multiple, custom}]}` / `{requestId}` — the agent's questions; `resolved` also when the turn stopped with them open |
| `update.progress` | `{tool, progress}` |

Part kinds: `text`, `reasoning`, `tool` (`tool`, `title`, `status`, `input`,
`output`, `diff`, `exitCode`), `diff` (`files[{path, change, diff}]`), `notice`
(`notice: files | retry | compacted`), `usage` (token counts), `attachments`
(`items[{name, path, size, mime, inline}]`: files sent with a user message, now
at `path` in the sandbox; `inline` when the model also got them directly),
`outputs` (`files[{name, size, shelf, chatId, error}]`: files the agent handed
over at the end of the turn, kept on `shelf` `device` or `s3`; `error` when one
could not be kept; [outputs](adr/outputs.md)), `question` (`status: asking |
answered | dismissed`, `questions[…, answer]`: the question tool's call with the
user's answers).

`ModelInfo.inputs` lists what a model takes besides text (`image`, `pdf`,
`audio`, `video`), from opencode's catalog.

`SessionInfo`: `{id, title, createdAt, updatedAt, busy, engines[], engine,
model, effort, place}` — `engines` in the order used, `engine` the current
segment's, `model`/`effort` those of the last message (reopening a chat selects
them), `place` `cloud` or `phone` once the chat has run.
Stored messages carry `segment` (engine session id), `engine` and
`segmentModel` (the model name when the segment began).

## Errors

Replies, `chat.error` messages and `backend.state` details may be one of these
codes (or contain one); the UI words them (`describeError` in
`webui/src/lib/i18n.ts`). Anything else is shown as given.

| Code | Meaning |
|---|---|
| `tensorlake_key_missing` | a `cloud` chat with no Tensorlake key (new chats then run on the device) |
| `tensorlake_key_invalid` | Tensorlake refused the key being saved |
| `quota_busy` | the plan's sandbox slot is taken by a chat that is working |
| `sandbox_gone` | the sandbox was deleted while starting; sending again makes a new one |
| `sandbox_missing` | `files.*` on a chat without a sandbox |
| `file_missing` | the file is no longer in the sandbox |
| `tensorlake_plugin_unavailable` | opencode started without its sandbox tools (see below); it is stopped again |
| `attach_no_space` | attached files do not fit on the phone or in the chat's sandbox |
| `attachment_missing` | a file named in `chat.send` is no longer held (the app restarted) |
| `skill_invalid: …` | `skills.add`: the archive is not a skill skill-creator would accept; the rest says why |
| `s3_denied`, `s3_no_bucket`, `s3_unreachable`, `s3_bad_endpoint`, `s3_secret_missing`, `s3_not_connected` | the bucket in Settings |
| `output_missing`, `output_no_space`, `output_failed` | a handed-over file: no longer kept; did not fit on the phone; could not be fetched |
| `plugin_invalid:<reason>: …` | `plugins.add`: `is_skill`, `is_marketplace`, `no_manifest`, `bad_manifest`, `no_name`, `bad_name`, or an archive problem (`not_zip`, `bad_path`, `too_large`) |

## Handing a chat to a new session

When a chat needs a new engine session (its Tensorlake sandbox is new while the
chat has history, or its last segment is a Codex thread from before 0.11), the
host starts one and sends the conversation so far with the first message,
wrapped in `<poordsh-context>…</poordsh-context>` (`ContextTag`): plain-text
turns, tool calls as one line each, at most 24 000 characters (the first
message plus the latest ones). If the sandbox is new, the hand-off says the
files are gone. A device jail's files never go, so it never resets. Text
starting with the tag is hidden from every history view.

Attached files are described the same way, in a note wrapped in
`<poordsh-attachments>…</poordsh-attachments>` (`AttachmentNote`): one line per
file, `- <path> (<bytes> bytes, <mime>[, also attached to this message])`. It is
a synthetic text part (the visible one when the message has no text); history
views show it as an `attachments` part. Files the model takes directly are added
as `file` parts (data URLs).

Both are text parts with `synthetic: true` before the user's text. The model
reads them; opencode stores them as synthetic and its own UIs hide them.

## opencode mapping (verified with 1.19.0, tensorlake-opencode 0.3.1)

- Server: `opencode serve --hostname 127.0.0.1 --port <random>` on the phone,
  basic auth `opencode:<OPENCODE_SERVER_PASSWORD>`, working directory an empty
  folder. Health: `GET /global/health`.
- Plugins, in this order: `tensorlake-opencode@<pinned>` (installed by opencode
  from npm on its first start, about 200 MB, so `/config/providers` can take
  minutes then) and `file:///root/.config/poordsh/poordsh-sandboxes.js`
  (`android/app/src/main/assets/opencode/`). PoorHarness's plugin calls the official
  plugin's entry point to get its tools (`bash`, `read`, `write`, `edit`,
  `multiedit`, `apply_patch`, `ls`, `glob`, `grep`) and registers them again,
  last, so they win:
  - their sandbox lookups go through `POORDSH_SANDBOX_MAP`
    (`/root/.config/poordsh/sandboxes.json`, `"opencode-<session>"` → the chat's
    Tensorlake sandbox name, or `"phone:<chat id>"`; written by the host for
    every session it creates or continues); a session without an entry is
    refused;
  - for `phone:` sandboxes, `TensorlakeClient`'s `getOrCreateSandbox`,
    `executeCommand`, `readFile`, `writeFile`, `listDirectory` and
    `deleteSandbox` go to the device bridge (`POORDSH_PHONE_URL`,
    `POORDSH_PHONE_TOKEN`) instead of Tensorlake (`readFileBounded` is built on
    the first two). Without a Tensorlake key, `TENSORLAKE_API_KEY` is a
    placeholder so the official plugin's "no credentials" check passes; it is
    never sent anywhere;
  - `bash` asks `ctx.ask({permission:"bash"})` and edits ask `"edit"`, so the
    approval setting applies (the official tools never ask);
  - if the official plugin is missing, stand-ins that refuse take those names;
    a `tool.execute.before` hook refuses any tool not on its list;
  - it never lets the official plugin suspend a chat sandbox;
  - it writes `{launch, ready, error}` to `POORDSH_STATUS`; after start-up the
    host requests `/config/providers` (which loads the plugins) and stops
    opencode with `tensorlake_plugin_unavailable` unless `ready` is true for
    this launch (`POORDSH_LAUNCH`).
- Permissions (`permission` in the app's config file, `OPENCODE_CONFIG`):
  `"*": "deny"` plus `read`, `glob`, `grep`, `ls`, `task`, `todowrite`,
  `todoread`, `invalid`, `question` allowed, and `bash`, `edit`, `webfetch`, `websearch`,
  `codesearch`, `external_directory`, `doom_loop` allowed or `ask` per the
  approval setting; `batch` allowed when its option is on; `repo` while a
  bucket is connected (`ask` in "Ask every time"). Every subagent gets
  `permission.question: deny`.
- Questions ([questions](adr/questions.md)): `OPENCODE_ENABLE_QUESTION_TOOL=1`.
  `question.asked` → `question.request`; `question.replied` / `question.rejected`
  → `question.resolved`. Replies go to `POST /question/{id}/reply {answers}` or
  `/reject`. A session that goes idle with a question open (opencode 1.19.0 keeps
  an aborted turn's question pending) has it rejected and resolved by the host.
  The tool's part becomes a `question` part (`metadata.answers` holds the answers).
- Skills ([skills](adr/skills.md)): `skills.paths: ["/tmp/poordsh/skills"]`, where
  PRoot binds the app's store (`files/skills`), and, when there are skills,
  `permission.skill: {"*": "deny", "<each skill>": "allow"}` and
  `POORDSH_EXTRA_TOOLS=skill` for the plugin. The `skill` tool reads `SKILL.md`
  on the phone and reports `/tmp/poordsh/skills/<name>` as the skill's folder,
  where the chat's sandbox has its copy. opencode reads the list at start, so a
  change restarts it when idle.
- Plugins ([plugins](adr/plugins.md)), read at start like skills: each plugin's
  skill folders under `/tmp/poordsh/plugins/<name>/` join `skills.paths` (and
  the `skill` permission); MCP servers with a URL go into `mcp` as
  `{type: "remote", url, headers}` with `permission["<server>_*"]` and
  `POORDSH_MCP_SERVERS=<server>,…` for the app's plugin, which otherwise refuses
  tools it does not know; Claude-style agents go into `agent` as
  `"<plugin>:<agent>": {mode: "subagent", description, prompt, permission}`.
  A permission request for `<server>_<tool>` is sent as kind `tool`.
- Subagents' model ([subagent-model](adr/subagent-model.md)): with one set,
  `agent.general`, `agent.explore` and every plugin agent get `model:
  "<provider>/<model>"` and, with a level, `variant`; only those fields, so
  opencode's own prompts and permissions stay. Left out when the provider has
  no key (Zen needs none).
- Git ([git-repos](adr/git-repos.md)): while a bucket is connected,
  `POORDSH_EXTRA_TOOLS` includes `repo` and the instructions file says what it
  is for. The app's plugin builds the tool with the zod that tensorlake-opencode
  brings (`@opencode-ai/plugin`'s `tool.schema`), traces a subagent's session
  to its top session (`client.session.get`'s `parentID`, as tensorlake-opencode
  does), asks `ctx.ask({permission: "repo", metadata: {title}})` for `open` and
  `create`, and posts to the device bridge's `/repo`.
- Web ([web-in-sandbox](adr/web-in-sandbox.md)): the app's plugin registers
  `webfetch` and `websearch` (opencode's arguments, so they replace the built-in
  tools). Each asks `ctx.ask({permission: "webfetch" | "websearch"})`, runs
  `poordsh-web.py` (written beside the plugin) in the chat's sandbox through the
  official `bash` tool's `execute` (python3 `-c`, script and arguments as base64;
  the answer is one line, `\u0001POORDSH-WEB\u0001` + JSON `{ok, kind, text | b64,
  error, limit}`), and only when `limit` is true (HTTP 402/429, or a limit message
  from Exa) posts the request to the device bridge's `/web`. Images come back as a
  `{type: "file", mime, url: "data:…"}` attachment (plugin tools may return
  `{title, output, metadata, attachments}`, verified with 1.19.0).
- Model catalog: opencode reads `models.opencode.ai/api.json` into
  `~/.cache/opencode/models.json` at start when its copy is over 5 minutes old
  (and hourly). The catalog a server reads first stays for its whole run, and
  `models-dev.refreshed` reaches neither `/event` nor `/global/event`
  (verified with 1.19.0), so the host waits for that file to be rewritten (at
  most 12 s, or until opencode logs `Failed to fetch models.dev`) before its
  first `/config/providers`.
- Attachments: images (and PDFs, for models whose `capabilities.input.pdf` is
  true) as `{type: "file", mime, filename, url: "data:…"}` parts; opencode on the
  phone cannot read the sandbox copy.
- Always on: `OPENCODE_ENABLE_PARALLEL=1` and `OPENCODE_ENABLE_EXA=1` (web
  search with any provider and no key; Exa answers `codesearch`. `websearch`
  and `webfetch` are the app plugin's, see Web below) and `OPENCODE_EXPERIMENTAL_BACKGROUND_SUBAGENTS=1`
  (the `task` tool may run subagents in the background).
- Options ([engine-options](adr/engine-options.md)): `batch_tool` →
  `experimental.batch_tool` plus `POORDSH_EXTRA_TOOLS=batch`; `prune` →
  `compaction.prune`; `experimental_models` →
  `OPENCODE_ENABLE_EXPERIMENTAL_MODELS=1`. opencode drops config keys it does
  not know, so a key removed upstream does not stop it from starting.
- Subagents: a subagent's session has a `parentID` (`session.created`) and its
  events report under its own id. The host maps it to the chat
  (`AgentBackend.rootSession`), forwards its approval requests, keeps the
  chat's sandbox up while it is busy, and keeps its messages out of the chat
  ([subagent-sessions](adr/subagent-sessions.md)).
- Models: `GET /config/providers` → `{providers[{id, name, key, models{id:
  {name, cost, limit, variants, status, capabilities{toolcall}}}}],
  default{provider: model}}`. Signed out, the `opencode` provider lists only
  free models. Variants are the reasoning levels. Models with
  `capabilities.toolcall: false` are left out; only the `opencode` provider's
  $0 models count as free (NVIDIA NIM's and OrcaRouter's are $0 too). The reply
  includes each provider's stored key, one more reason the server sits behind
  its password.
- OrcaRouter ([orcarouter-free-models](adr/orcarouter-free-models.md)): with
  its key stored, the app's config has `provider.orcarouter = {whitelist: [ids],
  models: {id: {name, tool_call, reasoning, attachment?, modalities?, limit?,
  cost}}}`, built from `GET https://api.orcarouter.ai/api/pricing` (no key):
  `data[]` entries with `is_free_tier`, `"tools"` in `supported_parameters` and
  text in `output_modalities`; `model_name`, `display_name`, `context_length`,
  `max_completion_tokens`, `input_modalities`. opencode merges these over
  models.dev's entry, rejects a `limit` without both `context` and `output`
  (the whole config then fails), and reads the config file again on `POST
  /instance/dispose`.
- NVIDIA NIM ([nvidia-nim](adr/nvidia-nim.md)): with its key stored,
  `provider.nvidia = {whitelist: [ids]}` from `GET
  https://integrate.api.nvidia.com/v1/models` (no key; `data[].id`); models.dev
  keeps the definitions.
- Infron ([infron-free-models](adr/infron-free-models.md)): not in models.dev,
  so `provider.infron` also has `npm: "@ai-sdk/openai-compatible"`, `name` and
  `options.baseURL: "https://llm.onerouter.pro/v1"`; the key comes from the
  auth store (`PUT /auth/infron`). Models from `GET
  https://llm.onerouter.pro/v1/models` (no key): `data[]` with `id` ending in
  `:free`, `min_prompt_price` and `min_completion_price` 0 (and
  `min_request_price` absent or 0), `supports_function_calling`, `category_type:
  "LLM"`, not `deprecated` or `is_display_only`; `display_name`,
  `context_length` (capped by the largest `providers[].context_length`),
  `max_output_tokens`, `input_modalities`. Each model also gets `variants`
  `none` … `xhigh` = `{reasoning: {effort}}`; opencode sends that object in
  the request body when the prompt names the variant.
- Venice ([venice](adr/venice.md)): not reached through models.dev's npm package,
  so `provider.venice` has `npm: "@ai-sdk/openai-compatible"`, `name`,
  `options.baseURL: "https://api.venice.ai/api/v1"` and a `whitelist`; the key
  comes from the auth store. Models from `GET
  https://api.venice.ai/api/v1/models` (no key), kept when `model_spec.uncensored`
  is true, `model_spec.privacy` is `private`, the model is online and both USD
  prices per million tokens are at most 0.35 (input) and 1.5 (output); fields:
  `model_spec.name`,
  `availableContextTokens`, `maxCompletionTokens`, `pricing`,
  `capabilities.supportsFunctionCalling` / `supportsReasoning` /
  `reasoningEffortOptions` / `supportsVision`, `offline`. Variants are
  `{reasoningEffort: level}` for the offered levels and `{disabled: true}` for
  the rest. A model without function calling is `chatOnly` in `models.list`;
  the config has an agent `chat` (`permission {"*": "deny"}`), and `chat.send`
  to such a model passes `agent: "chat"` to `prompt_async`.
- Prompt: `POST /session/{id}/prompt_async` with `{model{providerID, modelID},
  parts[{type:"text", text}], variant?, agent?}`.
- Events (`GET /event`, SSE): `message.updated`, `message.part.updated`,
  `message.part.delta` (`field`, `delta`), `session.status` (`busy`/`retry`/
  `idle`), `session.idle`, `session.error`, `todo.updated`, `permission.asked`
  (`id`, `sessionID`, `permission`, `patterns`, `metadata.command`),
  `permission.replied`.
- Approvals: `POST /session/{sid}/permissions/{pid}` `{response: once | always
  | reject}`.
- Provider keys: `PUT /auth/<provider> {type:"api", key}` or `DELETE
  /auth/<provider>` (`opencode` = Zen, `nvidia` = NIM, `orcarouter`, `infron`, `venice`), then `POST
  /instance/dispose`; without it `/config/providers` keeps the old provider
  set until a restart.
- The guest environment must be clean: opencode enables providers from
  environment variables it finds (for example a `GH_TOKEN` turns on GitHub
  Copilot).
