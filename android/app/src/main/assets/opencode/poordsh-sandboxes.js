// PoorHarness: opencode's tools run only in the chat's sandbox (Tensorlake, or a
// PRoot jail on the device when there is no Tensorlake key).
//
// opencode's config lists this plugin after tensorlake-opencode. It takes that
// plugin's tools (by calling the plugin the way opencode does) and registers
// them again, last, so these are the tools the model gets:
//
// - Each runs in the chat's sandbox. tensorlake-opencode names a sandbox after
//   the opencode session ("opencode-<session>"); PoorHarness writes
//   POORDSH_SANDBOX_MAP, a JSON object from those names to the chat's sandbox,
//   and the plugin's sandbox lookup goes through it. A session with no entry is
//   refused, so no stray sandbox is created.
// - Commands and edits ask first when PoorHarness's approval setting says so
//   (opencode decides from its `permission` config; tensorlake-opencode's own
//   tools never ask).
// - If tensorlake-opencode is missing or cannot be routed, stand-ins that refuse
//   take the tools' place, so nothing ever runs on the phone. Tools PoorHarness does
//   not know are refused too, except those of the remote MCP servers its plugins
//   add (POORDSH_MCP_SERVERS): they run on their server, not on the phone.
//
// The chat's sandbox belongs to PoorHarness (it suspends and deletes it); this
// plugin keeps tensorlake-opencode from suspending it. The outcome is written
// to POORDSH_STATUS for PoorHarness to check after opencode starts.
//
// webfetch and websearch are registered here too, so they run in the chat's sandbox
// (poordsh-web.py, python3 there) instead of on the phone. If that fails, PoorHarness's
// loopback bridge repeats the request with TinyFish, but only when the search or
// the site reports a usage limit (HTTP 402/429); the key stays in the app
// (docs/adr/web-in-sandbox.md).
//
// With "repo" in POORDSH_EXTRA_TOOLS (a bucket is connected), the repo tool lists,
// opens and creates the user's git repositories. PoorHarness does the work through
// its loopback bridge, since the bucket's key stays in the app (docs/adr/git-repos.md).
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const MAP = process.env.POORDSH_SANDBOX_MAP;
const STATUS = process.env.POORDSH_STATUS;
const LAUNCH = process.env.POORDSH_LAUNCH ?? "";
const SPEC = process.env.POORDSH_TENSORLAKE_PLUGIN ?? ""; // e.g. tensorlake-opencode@0.3.1
// The on-device bridge, used for chats whose sandbox is "phone:<chat id>"
// (no Tensorlake key). The same opencode process can serve cloud and phone
// chats at once; the sandbox id's prefix picks the route.
const PHONE_URL = process.env.POORDSH_PHONE_URL ?? "";
const PHONE_TOKEN = process.env.POORDSH_PHONE_TOKEN ?? "";
// Extra tools PoorHarness allows this launch: "batch" when the batch tool is on, and
// "skill" when the user has skills (it reads them from PoorHarness's store on the
// phone; their copy in the chat's sandbox sits at the same path).
const list = (value) => (value ?? "").split(",").map((s) => s.trim()).filter(Boolean);
const EXTRA_TOOLS = list(process.env.POORDSH_EXTRA_TOOLS);
// Remote MCP servers from the user's plugins; opencode names their tools "<server>_<tool>".
const MCP_PREFIXES = list(process.env.POORDSH_MCP_SERVERS).map((name) => `${name}_`);

// Tools that read files or run commands: these exist only as sandbox tools.
const SANDBOX_TOOLS = ["bash", "read", "write", "edit", "multiedit", "apply_patch", "ls", "glob", "grep"];
// Tools that touch neither the phone nor the sandbox ("question" asks the user in the app).
const OTHER_TOOLS = ["task", "webfetch", "websearch", "codesearch", "todowrite", "todoread", "invalid", "question"];
const ALLOWED = new Set([...SANDBOX_TOOLS, ...OTHER_TOOLS, ...EXTRA_TOOLS]);
const allowed = (tool) => ALLOWED.has(tool) || MCP_PREFIXES.some((p) => tool.startsWith(p));

// The outputs folder takes files only (PoorHarness hands them to the user when the
// turn ends; a folder there is handed over as a zip). Writes into a subfolder, and
// mkdir of one, are refused so the model packs a .zip instead.
const OUTPUTS = "/tmp/workspace/outputs/";
const NESTED_OUTPUT =
  "Not run: /tmp/workspace/outputs takes files only, no folders. To hand over several files, pack them into one .zip in that folder.";
const nestedOutput = (p) => {
  if (typeof p !== "string") return false;
  const full = p.startsWith("/") ? p : "/tmp/workspace/" + p.replace(/^\.\//, "");
  if (!full.startsWith(OUTPUTS)) return false;
  return full.slice(OUTPUTS.length).replace(/\/+$/, "").includes("/");
};
const mkdirInOutputs = (command) => {
  if (typeof command !== "string") return false;
  // Each simple command; only mkdir's own arguments count.
  return command.split(/&&|\|\||[;|\n]/).some((part) => {
    const words = part.trim().split(/\s+/).map((w) => w.replace(/^['"]|['"]$/g, ""));
    const at = words.findIndex((w) => !/^[A-Za-z_][A-Za-z0-9_]*=/.test(w) && w !== "sudo");
    if (words[at] !== "mkdir") return false;
    return words.slice(at + 1).some((w) => !w.startsWith("-") && nestedOutput(w.replace(/\/+$/, "") + "/x"));
  });
};

const PHONE_PREFIX = "phone:";
const isPhone = (id) => typeof id === "string" && id.startsWith(PHONE_PREFIX);

/** One operation against a chat's on-device jail, through PoorHarness's loopback bridge. */
async function phone(path, payload) {
  const res = await fetch(PHONE_URL + path, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${PHONE_TOKEN}` },
    body: JSON.stringify(payload),
  });
  const text = await res.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    throw new Error(`phone ${path}: ${res.status} ${text.slice(0, 200)}`);
  }
  if (!res.ok || data.error) throw new Error(data.error || `phone ${path}: ${res.status}`);
  return data;
}

const UNAVAILABLE = "Not run: PoorHarness runs tools only in the chat's sandbox, which is not available right now.";

/** The name tensorlake-opencode gives a session's sandbox (the key of POORDSH_SANDBOX_MAP). */
const sandboxName = (sessionId) => `opencode-${sessionId}`.toLowerCase().replace(/[^a-z0-9-]/g, "-").slice(0, 63);

/** A subagent's session works in its top session's sandbox, as tensorlake-opencode routes it. */
async function rootSession(client, id) {
  const seen = new Set([id]);
  for (let depth = 0; depth < 8 && client?.session?.get; depth++) {
    let parent = null;
    try {
      const res = await client.session.get({ path: { id } });
      const info = res?.data ?? res;
      parent = typeof info?.parentID === "string" && info.parentID !== id ? info.parentID : null;
    } catch {
      parent = null;
    }
    if (!parent || seen.has(parent)) break;
    seen.add(parent);
    id = parent;
  }
  return id;
}

/** The zod that tensorlake-opencode brings, for tools' argument schemas. */
async function zodOf(dir) {
  const { tool } = await import(pathToFileURL(path.join(dir, "..", "@opencode-ai", "plugin", "dist", "tool.js")).href);
  return tool.schema;
}

/** The repo tool, its argument schema made with the zod that tensorlake-opencode brings. */
async function repoTool(dir, client) {
  const z = await zodOf(dir);
  return {
    description:
      "The user's git repositories, kept in their own storage. list: their names and branches. " +
      "open: makes one a bare repository at /tmp/git/<name>.git in this sandbox, to clone from and push to. " +
      "create: a new, empty one there. What is pushed there is saved when the turn ends.",
    args: {
      action: z.enum(["list", "open", "create"]).describe("list, open or create"),
      name: z.string().optional().describe("The repository, for open and create"),
    },
    async execute(args, ctx) {
      const chat = chatSandbox(sandboxName(await rootSession(client, ctx.sessionID)));
      if (!chat) return `Not run: no chat sandbox is bound to this session.`;
      if (args.action !== "list") {
        const name = String(args.name ?? "");
        await ctx.ask({ permission: "repo", patterns: [name], always: ["*"], metadata: { title: `repo ${args.action} ${name}` } });
      }
      const r = await phone("/repo", { sandbox: chat, action: args.action, name: args.name ?? "" });
      return String(r.text ?? "");
    },
  };
}

// --- webfetch and websearch -------------------------------------------------

/** poordsh-web.py, which PoorHarness writes beside this plugin; null when it is missing. */
function webScript() {
  try {
    return fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), "poordsh-web.py")).toString("base64");
  } catch {
    return null;
  }
}

const WEB_MARK = "\u0001POORDSH-WEB\u0001";

/**
 * Runs poordsh-web.py in the chat's sandbox through tensorlake-opencode's bash tool
 * (so the sandbox is found, woken and routed exactly as for the model's own
 * commands) and returns its answer, {ok, ...}. Throws when the sandbox cannot run it.
 */
async function inSandbox(bash, script, request, ctx) {
  if (!script) throw new Error("poordsh-web.py is missing");
  const args = Buffer.from(JSON.stringify(request)).toString("base64");
  const command = `python3 -c 'import sys,base64;exec(base64.b64decode(sys.argv.pop(1)))' ${script} ${args}`;
  const out = String(await bash.execute({ command }, ctx));
  const at = out.lastIndexOf(WEB_MARK);
  if (at < 0) {
    const exit = /^Exit code: (-?\d+)/.exec(out);
    throw new Error(`the sandbox could not run the request${exit ? ` (exit ${exit[1]})` : ""}: ${out.slice(0, 200).trim()}`);
  }
  return JSON.parse(out.slice(at + WEB_MARK.length).split("\n")[0]);
}

/** The sandbox first; TinyFish through the app only if that hit a usage limit. Other errors go to the model. */
async function web(bash, script, request, ctx) {
  const r = await inSandbox(bash, script, request, ctx);
  if (r.ok) return { ...r, via: "sandbox" };
  const failure = r.error;
  if (!r.limit || ctx.abort?.aborted) throw new Error(failure);
  console.error(`[poordsh] ${request.kind} in the sandbox failed (${failure}); trying TinyFish`);
  try {
    const r = await phone("/web", request);
    return { ok: true, kind: "text", text: String(r.text ?? ""), via: "tinyfish" };
  } catch (err) {
    throw new Error(`${failure}. TinyFish fallback failed too: ${err?.message ?? err}`);
  }
}

/** webfetch and websearch with opencode's own arguments, run by [web]. */
async function webTools(dir, bash) {
  const z = await zodOf(dir);
  const script = webScript();
  return {
    webfetch: {
      description:
        "Fetches the content of a URL and returns it as markdown, text or HTML (HTTP is upgraded to HTTPS; images are returned as attachments). " +
        "Read-only. Use it to read a page you already have the address of; use websearch to find pages.",
      args: {
        url: z.string().describe("The URL to fetch content from"),
        format: z.enum(["text", "markdown", "html"]).default("markdown").describe("The format to return the content in. Defaults to markdown."),
        timeout: z.number().describe("Optional timeout in seconds (max 120)").optional(),
      },
      async execute(args, ctx) {
        const format = args.format ?? "markdown";
        await ctx.ask({
          permission: "webfetch",
          patterns: [args.url],
          always: ["*"],
          metadata: { url: args.url, format, timeout: args.timeout },
        });
        const r = await web(bash, script, { kind: "fetch", url: args.url, format, timeout: args.timeout }, ctx);
        const title = `${args.url}${r.contentType ? ` (${r.contentType})` : ""}`;
        if (r.kind === "image") {
          return {
            title,
            output: "Image fetched successfully",
            metadata: { via: r.via },
            attachments: [{ type: "file", mime: r.mime, url: `data:${r.mime};base64,${r.b64}` }],
          };
        }
        return { title, output: r.text, metadata: { via: r.via } };
      },
    },
    websearch: {
      description:
        "Searches the web and returns the most relevant results with page excerpts. " +
        `Use it for current events, recent documentation and anything past your knowledge cutoff. The current year is ${new Date().getFullYear()}; use it when searching for recent information.`,
      args: {
        query: z.string().describe("Web search query"),
        numResults: z.number().optional().describe("Number of search results to return (default: 8)"),
        livecrawl: z.enum(["fallback", "preferred"]).optional().describe("Live crawl mode: 'fallback' uses live crawling only when cached content is unavailable, 'preferred' prioritizes it"),
        type: z.enum(["auto", "fast", "deep"]).optional().describe("Search type: 'auto' balanced (default), 'fast' quick, 'deep' comprehensive"),
        contextMaxCharacters: z.number().optional().describe("Maximum characters of context for LLMs (default: 10000)"),
      },
      async execute(args, ctx) {
        await ctx.ask({
          permission: "websearch",
          patterns: [args.query],
          always: ["*"],
          metadata: { query: args.query, numResults: args.numResults, livecrawl: args.livecrawl, type: args.type, contextMaxCharacters: args.contextMaxCharacters },
        });
        const r = await web(bash, script, { kind: "search", ...args }, ctx);
        return { title: `Web search: ${args.query}`, output: r.text, metadata: { via: r.via } };
      },
    },
  };
}

/** Chat sandboxes the official plugin has bound, by id: PoorHarness suspends these, not the plugin. */
const chatSandboxIds = new Set();

function chatSandbox(name) {
  try {
    const value = JSON.parse(fs.readFileSync(MAP, "utf8"))[name];
    return typeof value === "string" ? value : null;
  } catch {
    return null;
  }
}

/** The installed tensorlake-opencode package: where opencode keeps plugins, else found below its cache. */
function packageDir() {
  const cache = path.join(process.env.XDG_CACHE_HOME || path.join(os.homedir(), ".cache"), "opencode");
  const version = SPEC.slice(SPEC.lastIndexOf("@") + 1);
  const known = path.join(cache, "packages", SPEC, "node_modules", "tensorlake-opencode");
  const matches = (dir) => {
    try {
      return JSON.parse(fs.readFileSync(path.join(dir, "package.json"), "utf8")).version === version;
    } catch {
      return false;
    }
  };
  if (matches(known)) return known;
  const queue = [[cache, 0]];
  while (queue.length) {
    const [dir, depth] = queue.shift();
    let entries = [];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const e of entries) {
      if (!e.isDirectory()) continue;
      const child = path.join(dir, e.name);
      if (e.name === "tensorlake-opencode" && matches(child)) return child;
      if (depth < 5) queue.push([child, depth + 1]);
    }
  }
  throw new Error(`${SPEC} is not installed`);
}

/**
 * Routes the official plugin's sandbox operations to the chat's sandbox: a
 * Tensorlake sandbox named in the map, or, when the mapped value is
 * "phone:<chat>", the device jail through PoorHarness's loopback bridge. Only the
 * client methods the tools use are routed; the rest keep upstream behaviour.
 */
async function route(dir) {
  const { TensorlakeClient } = await import(pathToFileURL(path.join(dir, "dist", "tensorlake", "core", "client.js")).href);
  const proto = TensorlakeClient?.prototype;
  if (typeof proto?.getOrCreateSandbox !== "function") throw new Error("unexpected tensorlake-opencode layout");
  if (proto.__poordsh) return;

  const getOrCreate = proto.getOrCreateSandbox;
  proto.getOrCreateSandbox = async function (name, opts) {
    const chat = chatSandbox(name);
    if (!chat) throw new Error(`Not run: no chat sandbox is bound to ${name}.`);
    // A device jail is managed by PoorHarness; no Tensorlake sandbox is created.
    if (isPhone(chat)) return { sandboxId: chat, outcome: "attached" };
    const bound = await getOrCreate.call(this, chat, opts);
    chatSandboxIds.add(bound.sandboxId);
    return bound;
  };

  const exec = proto.executeCommand;
  proto.executeCommand = async function (id, command, workingDir = "/tmp/workspace", timeoutMs = 120000) {
    if (!isPhone(id)) return exec.call(this, id, command, workingDir, timeoutMs);
    const r = await phone("/run", { chatId: id.slice(PHONE_PREFIX.length), command, cwd: workingDir, timeoutMs });
    return { exitCode: r.exitCode ?? -1, stdout: r.stdout ?? "", stderr: r.stderr ?? "" };
  };

  const readFile = proto.readFile;
  proto.readFile = async function (id, p) {
    if (!isPhone(id)) return readFile.call(this, id, p);
    const r = await phone("/read", { chatId: id.slice(PHONE_PREFIX.length), path: p });
    return Buffer.from(r.dataB64 ?? "", "base64");
  };

  const writeFile = proto.writeFile;
  proto.writeFile = async function (id, p, content) {
    if (!isPhone(id)) return writeFile.call(this, id, p, content);
    const buf = Buffer.isBuffer(content) ? content : Buffer.from(content);
    await phone("/write", { chatId: id.slice(PHONE_PREFIX.length), path: p, dataB64: buf.toString("base64") });
  };

  const listDirectory = proto.listDirectory;
  proto.listDirectory = async function (id, p) {
    if (!isPhone(id)) return listDirectory.call(this, id, p);
    const r = await phone("/list", { chatId: id.slice(PHONE_PREFIX.length), path: p });
    return (r.entries ?? []).map((e) => ({ name: e.name, is_dir: e.is_dir, size: e.size }));
  };

  const del = proto.deleteSandbox;
  proto.deleteSandbox = async function (id) {
    if (isPhone(id)) return false; // PoorHarness deletes the jail with the chat
    return del.call(this, id);
  };

  const suspend = proto.suspendSandboxSync;
  if (typeof suspend === "function") {
    proto.suspendSandboxSync = function (id) {
      if (!isPhone(id) && !chatSandboxIds.has(id)) return suspend.call(this, id);
    };
  }
  proto.__poordsh = true;
}

/** What opencode should ask before a tool runs; null when it never asks. */
function permissionFor(id, args) {
  if (id === "bash") {
    const command = String(args?.command ?? "");
    return { permission: "bash", patterns: [command], always: ["*"], metadata: { command } };
  }
  if (id === "write" || id === "edit" || id === "multiedit") {
    const filePath = String(args?.filePath ?? "");
    return { permission: "edit", patterns: [filePath], always: ["*"], metadata: { filePath } };
  }
  if (id === "apply_patch") {
    const text = String(args?.patchText ?? args?.patch ?? "");
    const files = [...text.matchAll(/^\*\*\* (?:Add|Update|Delete) File: (.+)$/gm)].map((m) => m[1].trim());
    return { permission: "edit", patterns: files.length ? files : ["*"], always: ["*"], metadata: { files } };
  }
  return null;
}

function asking(id, def) {
  return {
    ...def,
    async execute(args, ctx) {
      const ask = permissionFor(id, args);
      if (ask) await ctx.ask(ask);
      return def.execute(args, ctx);
    },
  };
}

const standIn = {
  description: `Unavailable. ${UNAVAILABLE}`,
  args: {},
  async execute() {
    throw new Error(UNAVAILABLE);
  },
};

function writeStatus(status) {
  if (!STATUS) return;
  try {
    fs.mkdirSync(path.dirname(STATUS), { recursive: true });
    fs.writeFileSync(STATUS, JSON.stringify({ launch: LAUNCH, ...status }));
  } catch (err) {
    console.error(`[poordsh] could not write ${STATUS}: ${err?.message ?? err}`);
  }
}

export const PoorDshSandboxes = async (input, options) => {
  let tools = null;
  let error = null;
  try {
    const dir = packageDir();
    await route(dir);
    const official = await import(pathToFileURL(path.join(dir, "dist", "index.js")).href);
    const hooks = await official.default(input, options);
    tools = {};
    for (const id of SANDBOX_TOOLS) {
      if (hooks?.tool?.[id]) tools[id] = asking(id, hooks.tool[id]);
    }
    if (!tools.bash || !tools.read) throw new Error("tensorlake-opencode did not provide its tools");
    try {
      Object.assign(tools, await webTools(dir, hooks.tool.bash));
    } catch (err) {
      // opencode's own webfetch and websearch (on the phone) stay in place.
      console.error(`[poordsh] web tools unavailable: ${err?.message ?? err}`);
    }
    if (EXTRA_TOOLS.includes("repo")) {
      try {
        tools.repo = await repoTool(dir, input?.client);
      } catch (err) {
        console.error(`[poordsh] repo tool unavailable: ${err?.message ?? err}`);
      }
    }
  } catch (err) {
    error = String(err?.message ?? err);
    tools = null;
    console.error(`[poordsh] sandbox tools unavailable: ${error}`);
  }
  const ready = tools !== null;
  writeStatus({ ready, error });
  return {
    // Registered after every other plugin, so these win over opencode's own tools.
    tool: tools ?? Object.fromEntries(SANDBOX_TOOLS.map((id) => [id, standIn])),
    "tool.execute.before": async ({ tool }, output) => {
      if (!allowed(tool)) throw new Error(`Not run: PoorHarness does not offer the ${tool} tool.`);
      const args = output?.args ?? {};
      if (["write", "edit", "multiedit"].includes(tool) && nestedOutput(args.filePath)) throw new Error(NESTED_OUTPUT);
      if (tool === "bash" && mkdirInOutputs(args.command)) throw new Error(NESTED_OUTPUT);
      if (!ready && SANDBOX_TOOLS.includes(tool)) throw new Error(UNAVAILABLE);
    },
  };
};
