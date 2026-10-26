# Maintenance

Procedures that keep PoorHarness building and talking to current engines. The
reasons behind them are in [`docs/adr/`](adr/README.md).

## Bumping a pinned version

1. Edit `runtime/versions.env` (version, URL, checksum or npm `integrity`).
2. Run `tools/build-apk.sh --rebuild-runtime`.
3. For opencode, re-check [`bridge-protocol.md`](bridge-protocol.md) against
   the new server; it has changed field names between releases before. For the Tensorlake plugin, re-check what PoorHarness's plugin relies on
   ([opencode-tools-fail-closed](adr/opencode-tools-fail-closed.md)) with the
   checks in `bridge-protocol.md`, "opencode mapping": the `TensorlakeClient`
   methods the device route patches must still be the ones its tools call.
4. Review the engine options ([engine-options](adr/engine-options.md)): check
   opencode's config schema and `OPENCODE_*` flags (new switches are
   candidates; removed ones should leave `EngineOptions`).
5. Re-check the model catalog wait
   ([models-from-engines](adr/models-from-engines.md)): opencode should still
   keep its copy in `~/.cache/opencode/models*.json` and refresh it at start; if
   a newer opencode applies a late catalog to a running server (or sends
   `models-dev.refreshed` to clients), `ModelCatalog` can go.
6. Re-check skills ([skills](adr/skills.md)): opencode still reads
   `skills.paths` and filters the `skill` tool by `permission.skill`; `GET
   /skill` on a server with the app's config should list the store's skills.
7. Re-check the free-model gateways and NIM's list
   ([orcarouter-free-models](adr/orcarouter-free-models.md),
   [infron-free-models](adr/infron-free-models.md),
   [venice](adr/venice.md),
   [nvidia-nim](adr/nvidia-nim.md)): run `FreeGatewaysLiveTest`
   with `FREE_GATEWAYS_LIVE=1` (no key needed) and start the new opencode with
   each block it writes to `android/app/build/<id>-provider-live.json` as
   `provider.<id>` (and that key in the auth store); `GET /config/providers`
   should list exactly those models. If a catalog's field names change, that
   gateway's `parse` and its fixture (`src/test/resources/<id>/`) follow. For
   Venice also check `reasoningEffortOptions` against the variants, that the
   `chat` agent still removes tools (`tools` absent from the request), and that
   `uncensored`, `privacy` and `pricing` (USD per million tokens) keep their
   meaning: the live whitelist should match the caps in `Venice.kt`.
8. Re-check plugins ([plugins](adr/plugins.md)): start opencode with `android/app/build/opencode-plugins-config.json` (written by
   `OpencodePluginsTest`, `"*"` moved first as on Android, the MCP URL pointed at
   a local server); `GET /skill`, `GET /mcp` and `GET /agent` should list the
   plugin's skill, server and `review-kit:reviewer`.
9. Re-check outputs and the system prompt ([outputs](adr/outputs.md),
   [system-prompt](adr/system-prompt.md)): opencode still reads `instructions`
   from config, re-reads the file for every request, and calls
   `tool.execute.before` with `output.args.filePath` / `.command`.
   `OutputsLiveTest` covers handing over from a Tensorlake sandbox (with S3_*
   set, into a bucket).
10. Re-check the subagent model ([subagent-model](adr/subagent-model.md)):
    `agent.<name>.model` and `variant` still apply to `general`, `explore` and
    plugin agents without replacing their prompts (`GET /agent` shows them), and
    `agent` is still read; when it is not, move `OpencodeConfig.agents` to the
    `agents` shape.
11. Re-check the repo tool ([git-repos](adr/git-repos.md)): the app's plugin
    (`assets/opencode/poordsh-sandboxes.js`) imports zod from
    `@opencode-ai/plugin/dist/tool.js` next to tensorlake-opencode, and reads
    `parentID` from `client.session.get`; both must still resolve. For a new
    git-remote-s3, check that its layout (`HEAD`, `<ref>/<sha>.bundle`,
    `LOCK#.lock`) is unchanged; `GitReposTest.theLayoutIsGitRemoteS3s` pins it,
    and `GitReposLiveTest` clones through it when it is on `PATH`.
12. Re-check the question tool ([questions](adr/questions.md)): `question.asked`,
    `POST /question/{id}/reply` and `/reject` keep their shapes (`/doc` lists
    them), `OPENCODE_ENABLE_QUESTION_TOOL` still exists, subagents still get the
    tool denied by default (`GET /agent`), and whether a stopped turn still leaves
    its question pending; if opencode starts rejecting it, the host's clean-up can go.
13. Re-check web access ([web-in-sandbox](adr/web-in-sandbox.md)): opencode's
    `webfetch` and `websearch` arguments (`/experimental/tool/ids`, the tool
    list's schemas) against the plugin's copies; Exa's MCP call
    (`web_search_exa` at `mcp.exa.ai/mcp`, as opencode makes it); TinyFish's
    Search and Fetch APIs (`docs.tinyfish.ai`); and that plugin tools may still
    return `{title, output, metadata, attachments}`. `WebScriptTest` and
    `TinyFishTest` cover the script and the client; to try it for real, run
    `poordsh-web.py` in a Tensorlake test sandbox the way the plugin does.

## Following skill-creator

`SkillPackage` mirrors the checks in Anthropic's skill-creator
(`scripts/quick_validate.py`) and what its packager leaves out
(`scripts/package_skill.py`). When skill-creator changes either (new frontmatter
keys, different limits), update `SkillPackage` and `SkillPackageTest`, and
re-package `android/app/src/test/resources/skills/csv-tidy.skill` with the new
packager.

## Changing the bridge

[`bridge-protocol.md`](bridge-protocol.md) is the contract between `webui/` and
`android/`, plus what opencode actually sends. Update it in the same change
as any code that alters a method, event or part shape, and give the mock host
(`webui/src/lib/mock.ts`) an answer for every new method.

## Changing the launcher artwork

Replace `assets/icon-source.png`, run `tools/make-icons.py` and commit the
PNGs; the build does not regenerate them. If the new artwork has a different
composition, re-measure the constants at the top of the script first (the
art's box, the rope's columns, the centre); the script's comments say what
each one is ([generated-icons](adr/generated-icons.md)).

## Build host

Maven Central sometimes rate-limits cloud build machines (HTTP 429). A Gradle
init script on the build host can put Google's mirror first
(`https://maven-central.storage-download.googleapis.com/maven2/`); the project
itself does not depend on it.
