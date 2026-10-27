// Stand-in for the Kotlin host when the UI runs in a plain browser (vite dev,
// screenshots, design review). It mimics the bridge contract in
// docs/bridge-protocol.md with canned data and a fake streaming reply.
// URL flags: ?setup (first-run unpack),
// ?loading (opencode still reporting its models), ?nokey (no Tensorlake key: chats run on the device),
// ?nim (an NVIDIA NIM key is set), ?orca (an OrcaRouter key is set), ?infron (an Infron key is set), ?venice (a Venice key is set), ?ratelimit (a sent message waits for a
// provider's daily limit), ?full (picked files do not fit in the sandbox),
// ?updated (opencode was updated in the app and a newer release is out), ?prompt (a system prompt is set), ?titlemodel (a title model is picked),
// ?nos3 (no bucket connected), ?ask (a sent message gets questions back first), ?en (English UI).

/* eslint-disable @typescript-eslint/no-explicit-any */
type Emit = (e: Record<string, unknown>) => void;

const now = Date.now();
const H = 3_600_000;
/** The versions this app ships with. */
const ENGINE_VERSION: Record<string, string> = { opencode: "1.19.0" };

function compareVersions(a: string, b: string): number {
  const x = a.split(".").map(Number);
  const y = b.split(".").map(Number);
  for (let i = 0; i < Math.max(x.length, y.length); i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) - (y[i] ?? 0);
  return 0;
}
/** The levels the app gives every Infron model (Infron's own `reasoning.effort`). */
const INFRON_EFFORTS = ["none", "minimal", "low", "medium", "high", "xhigh"];
/** The host stops an idle engine after 60 s; the preview does it sooner. */
const IDLE_STOP_MS = 8_000;

export function createMockHost(emit: Emit) {
  const flags = location.search;
  const runtime: any = {
    phase: flags.includes("setup") ? "installing" : "ready",
    progress: flags.includes("setup") ? 0.42 : 1,
    error: null,
    // Engines run only while a chat needs them.
    backends: { opencode: { phase: "stopped", detail: null } },
  };
  // In use, and what npm has: with ?updated, opencode was updated here and a newer release followed.
  const updated = flags.includes("updated");
  const inUse: Record<string, string> = updated ? { opencode: "1.19.2" } : { ...ENGINE_VERSION };
  const published: Record<string, string> = updated ? { opencode: "1.19.4" } : { opencode: "1.19.2" };
  const versionOf = (e: string) => ({ current: inUse[e], bundled: inUse[e] === ENGINE_VERSION[e] });
  let pending: string[] = flags.includes("loading") ? ["opencode"] : [];
  const settings: any = {
    approval: "full_auto",
    lastModel: null,
    lastEffort: null,
    language: flags.includes("en") ? "en" : "system",
    disableSeccomp: false,
    autoUpdate: false,
    systemPrompt: flags.includes("prompt") ? "返答は日本語で、結論から短く書く。\nコードを変えたらテストも足す。" : "",
    subagentModel: flags.includes("submodel") ? "opencode:opencode/big-pickle" : null,
    subagentEffort: null,
    titleModel: flags.includes("titlemodel") ? "opencode:opencode/big-pickle" : null,
  };
  const tensorlake = { keySet: !flags.includes("nokey") };
  const tinyfish = { keySet: false };
  const keys: Record<string, boolean> = { opencode: false, nvidia: flags.includes("nim"), orcarouter: flags.includes("orca"), infron: flags.includes("infron"), venice: flags.includes("venice") };
  let enabledOptions: string[] = ["subagents", "prune"];
  /** Git repositories agents keep in the bucket. */
  let repos = [
    { name: "api-client", head: "main", branches: ["dev", "main"], size: 931_002, modifiedAt: now - 3 * H, url: "s3://my-bucket/git/api-client" },
    { name: "notes", head: "main", branches: ["main", "main-c_csv"], size: 48_213, modifiedAt: now - 30 * H, url: "s3://my-bucket/git/notes" },
  ];
  const OPTIONS = [
    { id: "subagents", engine: "opencode", default: true },
    { id: "batch_tool", engine: "opencode" },
    { id: "prune", engine: "opencode" },
    { id: "experimental_models", engine: "opencode" },
  ];

  const models = () =>
    pending.length
      ? []
      : [
          m("opencode:opencode/deepseek-v4.1-flash-free", "opencode", "opencode", "OpenCode Zen", "DeepSeek V4.1 Flash", true, [], null, true, true, null, 1_000_000),
          m("opencode:opencode/big-pickle", "opencode", "opencode", "OpenCode Zen", "Big Pickle", true, [], null, false, true, null, 200_000),
          m("opencode:opencode/nemotron-3.5-lightning-free", "opencode", "opencode", "OpenCode Zen", "Nemotron 3.5 Lightning", true, ["low", "high"], null, false, true, null, 128_000),
          ...(keys.nvidia ? [
          m("opencode:nvidia/z-ai/glm-5.3-flash", "opencode", "nvidia", "Nvidia", "GLM 5.3 Flash", false, ["high"], null, true, true, null, 200_000),
          m("opencode:nvidia/deepseek-ai/deepseek-v4.1-flash", "opencode", "nvidia", "Nvidia", "DeepSeek V4.1 Flash", false, [], null, false, true, null, 128_000),
          ] : []),
          ...(keys.orcarouter ? [
          m("opencode:orcarouter/deepseek/deepseek-v4.1-flash-free", "opencode", "orcarouter", "OrcaRouter", "DeepSeek V4.1 Flash", false, ["low", "medium", "high", "max"], null, false, true, null, 1_048_576),
          m("opencode:orcarouter/tencent/hy4-preview-free", "opencode", "orcarouter", "OrcaRouter", "Hy4 preview", false, ["none", "low", "high"], null, false, true, null, 1_048_576),
          m("opencode:orcarouter/z-ai/glm-5.3-flash-free", "opencode", "orcarouter", "OrcaRouter", "GLM 5.3 Flash", false, ["low", "high", "max"], null, false, true, null, 1_000_000),
          ] : []),
          ...(keys.infron ? [
          m("opencode:infron/deepseek/deepseek-v4.1-flash:free", "opencode", "infron", "Infron", "Deepseek V4.1 Flash", false, INFRON_EFFORTS, null, false, true, null, 1_000_000),
          m("opencode:infron/inclusionai/ling-3.1-flash:free", "opencode", "infron", "Infron", "Ling 3.1 Flash", false, INFRON_EFFORTS, null, false, true, null, 262_144),
          m("opencode:infron/qwen/qwen3.8-27b:free", "opencode", "infron", "Infron", "Qwen3.8 27B Uncensored", false, INFRON_EFFORTS, null, false, true, null, 204_000),
          ] : []),
          ...(keys.venice ? [
          m("opencode:venice/deepseek-v4-1-flash-uncensored", "opencode", "venice", "Venice", "DeepSeek V4.1 Flash Uncensored", false, ["none", "low", "high", "max"], null, false, true, "$0.33 in · $1.32 out / 1M", 1_000_000),
          m("opencode:venice/olafangensan-glm-4.7-flash-heretic", "opencode", "venice", "Venice", "GLM 4.7 Flash Heretic", false, ["none", "low", "medium", "high"], null, false, true, "$0.07 in · $0.4 out / 1M", 200_000),
          m("opencode:venice/gemma-4-uncensored", "opencode", "venice", "Venice", "Gemma 4 Uncensored", false, [], null, false, true, "$0.163 in · $0.5 out / 1M", 256_000),
          { ...m("opencode:venice/e2ee-gemma-4-26b-a4b-uncensored-p", "opencode", "venice", "Venice", "Gemma 4 26B A4B Uncensored", false, [], null, false, true, "$0.19 in · $0.88 out / 1M", 64_000), chatOnly: true },
          m("opencode:venice/venice-uncensored-1-2", "opencode", "venice", "Venice", "Venice Uncensored 1.2", false, [], null, false, true, "$0.2 in · $0.9 out / 1M", 128_000),
          ] : []),
        ];
  if (pending.length) {
    setTimeout(() => {
      pending = [];
      emit({ type: "models.changed" });
    }, 4000);
  }

  // Chats belong to the app; each segment is one engine session.
  const chats: any[] = [
    chat("c_csv", "Fix flaky CSV parser test", now - 2 * H, now - H, [
      seg("opencode", "opencode:s1", "DeepSeek V4.1 Flash"),
    ], "opencode:opencode/deepseek-v4.1-flash-free", null),
    chat("c_http", "Add retry to the HTTP client", now - 26 * H, now - 25 * H, [seg("opencode", "opencode:s4", "Big Pickle")], "opencode:opencode/big-pickle", null),
    chat("c_build", "Explain the build script", now - 80 * H, now - 79 * H, [seg("opencode", "opencode:s2", "Big Pickle")], "opencode:opencode/big-pickle", null),
  ];
  // Without a key the preview's chats run on the device, as the host's would.
  for (const c of chats) c.place = tensorlake.keySet ? "cloud" : "phone";
  const s1 = chats[0].segments[0];
  const s2 = chats[0].segments[0];
  const history: Record<string, any[]> = {
    c_csv: [
      stamp(s1, {
        id: "m1",
        role: "user",
        createdAt: now - 2 * H,
        parts: [
          { id: "p1", kind: "text", text: "The parser test fails about one run in five. Find out why." },
          {
            id: "p1f",
            kind: "attachments",
            items: [{ name: "ci-run-4182.log", path: "/tmp/workspace/attachments/ci-run-4182.log", size: 184_320, mime: "text/plain" }],
          },
        ],
      }),
      stamp(s1, {
        id: "m2",
        role: "assistant",
        createdAt: now - 2 * H,
        parts: [
          { id: "r1", kind: "reasoning", text: "The failure is intermittent, so look for shared state or ordering between tests." },
          { id: "t1", kind: "tool", tool: "grep", title: "tempfile", status: "completed", output: "tests/test_parser.py:12: TMP = tempfile.gettempdir() + '/fixture.csv'" },
          { id: "x1", kind: "text", text: "Two tests write the same fixture path in the shared temp directory, so parallel runs overwrite each other's file." },
        ],
      }),
      stamp(s2, { id: "u:2", role: "user", createdAt: now - 1.5 * H, parts: [{ id: "p2", kind: "text", text: "Fix it, and run the suite until it's stable." }] }),
      stamp(s2, {
        id: "a:2",
        role: "assistant",
        createdAt: now - 1.5 * H,
        parts: [
          { id: "t2", kind: "tool", tool: "bash", title: "pytest -q -n 4 --count 50 tests/test_parser.py", status: "completed", output: "200 passed in 9.84s", exitCode: 0 },
          { id: "t3", kind: "tool", tool: "edit", title: "tests/test_parser.py", status: "completed", diff: "@@ -10,3 +10,3 @@\n-TMP = tempfile.gettempdir() + '/fixture.csv'\n+TMP = tmp_path / 'fixture.csv'\n" },
          { id: "x2", kind: "text", text: "Each test now writes to its own `tmp_path`. 50 runs with `-n 4`: no failures. The run log and the patch are in outputs." },
          { id: "us", kind: "usage", input: 18200, output: 940, reasoning: 210, cost: 0 },
          {
            id: "outputs:a:2",
            kind: "outputs",
            files: [
              { name: "flaky-runs.log", size: 48_210, shelf: "device", chatId: "c_csv" },
              { name: "fix-parser-test.patch", size: 1_204, shelf: "s3", chatId: "c_csv" },
            ],
          },
        ],
      }),
    ],
    c_http: [],
    c_build: [],
  };

  let seq = 1;
  const idleTimers: Record<string, ReturnType<typeof setTimeout>> = {};

  function setEngine(engine: string, phase: string) {
    runtime.backends[engine] = { phase, detail: phase === "ready" ? inUse[engine] : null };
    emit({ type: "backend.state", backend: engine, state: runtime.backends[engine] });
  }

  async function ensure(engine: string) {
    clearTimeout(idleTimers[engine]);
    if (runtime.backends[engine].phase === "ready") return;
    setEngine(engine, "starting");
    await sleep(1400);
    setEngine(engine, "ready");
  }

  function idleLater(engine: string) {
    clearTimeout(idleTimers[engine]);
    idleTimers[engine] = setTimeout(() => setEngine(engine, "stopped"), IDLE_STOP_MS);
  }

  function meta(c: any) {
    const engines = [...new Set(c.segments.map((s: any) => s.engine))];
    const { segments: _segments, ...rest } = c;
    return { ...rest, engines, engine: c.segments[c.segments.length - 1]?.engine ?? null };
  }

  // Files picked in the composer, until they are sent.
  const picked: Record<string, any> = {};
  let pickSeq = 1;
  const skills: any[] = [
    { name: "csv-tidy", description: "Cleans up CSV files and reports rows with the wrong number of columns.", files: 3, bytes: 826 },
  ];
  const storage: any = {
    destination: "device",
    s3: flags.includes("nos3") ? null : { endpoint: "https://s3.us-east-1.amazonaws.com", bucket: "my-bucket", accessKeyId: "AKIAIOSFODNN7EXAMPLE", region: "", prefix: "" },
  };
  const kept: Record<string, any[]> = {
    device: [
      { chatId: "c_csv", name: "flaky-runs.log", size: 48_210, modifiedAt: now - 1.5 * H },
      { chatId: "c_build", name: "build-report.md", size: 6_120, modifiedAt: now - 30 * H },
      { chatId: "c_gone", name: "notes.zip", size: 812_004, modifiedAt: now - 70 * H },
    ],
    s3: [{ chatId: "c_csv", name: "fix-parser-test.patch", size: 1_204, modifiedAt: now - 1.5 * H }],
  };
  const titleOf = (id: string) => chats.find((c: any) => c.id === id)?.title ?? null;
  const plugins: any[] = [
    {
      name: "review-kit",
      version: "1.2.0",
      description: "Code review helpers: a checklist, a reviewer agent and a formatter run after edits.",
      format: "claude",
      files: 9,
      bytes: 12_480,
      hooksAllowed: false,
      skills: [{ name: "checklist", description: "A review checklist." }],
      mcpServers: [
        { name: "docs", remote: true, url: "https://example.com/mcp" },
        { name: "lint", remote: false, command: "node" },
      ],
      hooks: [{ event: "PostToolUse", command: "${CLAUDE_PLUGIN_ROOT}/scripts/format.sh" }],
      agents: [{ name: "reviewer", description: "Reviews code" }],
      commands: 2,
      apps: 0,
    },
  ];

  async function send(chatId: string, text: string, model: string, effort: string | null, modelName: string | null, ids: string[] = []) {
    const c = chats.find((x) => x.id === chatId)!;
    // Fixed on the first message: a Tensorlake sandbox with a key, a device jail without.
    if (!c.place) c.place = tensorlake.keySet ? "cloud" : "phone";
    if (c.place === "cloud" && !tensorlake.keySet) throw new Error("tensorlake_key_missing");
    const engine = model.split(":")[0];
    const files = ids.map((id) => picked[id]).filter(Boolean);
    if (!c.title) c.title = (text.split("\n")[0] || files[0]?.name || "").slice(0, 60);
    c.model = model;
    c.effort = effort;
    c.updatedAt = Date.now();
    emit({ type: "sessions.upsert", session: meta(c) });
    // Like the host: the chat's sandbox first, then the files, then the engine.
    emit({ type: "chat.stage", sessionId: chatId, stage: "sandbox" });
    await sleep(900);
    if (files.length) {
      for (const progress of [0, 0.35, 0.7, 1]) {
        emit({ type: "chat.stage", sessionId: chatId, stage: "upload", progress });
        await sleep(350);
      }
      ids.forEach((id) => delete picked[id]);
    }
    await ensure(engine);
    emit({ type: "chat.stage", sessionId: chatId, stage: null });
    let s = c.segments[c.segments.length - 1];
    if (!s || s.engine !== engine) {
      s = seg(engine, `${engine}:x${seq++}`, modelName);
      c.segments.push(s);
    }
    void stream(c, s, text, files);
  }

  const pendingQuestions: Record<string, (answers: string[][] | null) => void> = {};

  async function stream(c: any, s: any, text: string, files: any[] = []) {
    const turn = `mock-${seq++}`;
    const sessionId = c.id;
    const parts: any[] = text ? [{ id: "u", kind: "text", text }] : [];
    if (files.length) {
      parts.push({
        id: "uf",
        kind: "attachments",
        items: files.map((f) => ({ name: f.name, path: `${WORKSPACE}/attachments/${f.name}`, size: f.size, mime: f.mime })),
      });
    }
    const user = stamp(s, { id: `u-${turn}`, role: "user", createdAt: Date.now(), parts });
    emit({ type: "chat.message", sessionId, message: user });
    emit({ type: "chat.status", sessionId, status: "busy" });
    if (flags.includes("ratelimit")) {
      // A spent daily allowance: the engine waits until the next window (00:00 UTC).
      const next = new Date();
      next.setUTCHours(24, 0, 0, 0);
      emit({ type: "chat.status", sessionId, status: "busy", detail: "Free model capacity is limited right now.", retryAt: next.getTime() });
      return;
    }
    const mid = `a-${turn}`;
    const reply = stamp(s, { id: mid, role: "assistant", createdAt: Date.now(), parts: [] as any[] });
    emit({ type: "chat.message", sessionId, message: reply });
    await sleep(500);
    const asked: any[] = [];
    if (flags.includes("ask")) {
      const en = flags.includes("en");
      const questions = [
        {
          header: en ? "Fix" : "直し方",
          question: en ? "The test shares a temp file between runs. How should I fix it?" : "テストが実行間で一時ファイルを共有しています。どう直しますか？",
          options: [
            { label: en ? "One file per test" : "テストごとに別ファイル", description: en ? "Each test gets its own temp directory." : "テストごとに一時フォルダを作ります。" },
            { label: en ? "Run serially" : "直列で実行", description: en ? "Keep the file; stop running these tests in parallel." : "ファイルはそのまま、並列実行をやめます。" },
          ],
          multiple: false,
          custom: true,
        },
        {
          header: en ? "Also" : "あわせて",
          question: en ? "Anything else while I'm there?" : "ついでに行う作業はありますか？",
          options: [
            { label: en ? "Add a regression test" : "回帰テストを追加", description: null },
            { label: en ? "Update the CI cache" : "CIのキャッシュを更新", description: null },
          ],
          multiple: true,
          custom: true,
        },
      ];
      const part: any = { id: "q", kind: "question", status: "asking", questions };
      emit({ type: "chat.part", sessionId, messageId: mid, part });
      const answers = await new Promise<string[][] | null>((resolve) => {
        const requestId = `opencode:que_${seq++}`;
        pendingQuestions[requestId] = resolve;
        emit({ type: "question.request", requestId, sessionId, questions });
      });
      const done = answers
        ? { ...part, status: "answered", questions: questions.map((q, i) => ({ ...q, answer: answers[i] })) }
        : { ...part, status: "dismissed" };
      emit({ type: "chat.part", sessionId, messageId: mid, part: done });
      asked.push(done);
      if (!answers) {
        reply.parts = asked;
        history[sessionId] = [...(history[sessionId] ?? []), user, reply];
        emit({ type: "chat.status", sessionId, status: "idle" });
        return;
      }
    }
    emit({ type: "chat.part", sessionId, messageId: mid, part: { id: "tool", kind: "tool", tool: "bash", title: "ls", input: "ls", status: "running" } });
    await sleep(600);
    const tool = { id: "tool", kind: "tool", tool: "bash", title: "ls", input: "ls", status: "completed", output: "README.md\nsrc\ntests", exitCode: 0 };
    emit({ type: "chat.part", sessionId, messageId: mid, part: tool });
    const answer = "The workspace has a `src` and a `tests` folder. Tell me what to change and I'll start there.";
    let sent = "";
    for (const word of answer.split(/(?<= )/)) {
      sent += word;
      emit({ type: "chat.delta", sessionId, messageId: mid, partId: "txt", field: "text", delta: word });
      await sleep(40);
    }
    // Like the host: the turn is stored before the chat goes idle.
    reply.parts = [...asked, tool, { id: "txt", kind: "text", text: sent }];
    history[sessionId] = [...(history[sessionId] ?? []), user, reply];
    c.updatedAt = Date.now();
    emit({ type: "chat.status", sessionId, status: "idle" });
    emit({ type: "sessions.upsert", session: meta(c) });
    idleLater(s.engine);
  }

  return async (method: string, p: Record<string, any>): Promise<unknown> => {
    await sleep(60);
    switch (method) {
      case "app.info":
        return { version: "0.9.1", workspace: WORKSPACE, android: "15", device: "Browser preview", abi: "arm64-v8a" };
      case "app.notices":
        return "# Third-party notices\n\nPreview build: notices are bundled in the APK.";
      case "runtime.state":
        return runtime;
      case "runtime.start":
      case "runtime.reinstall":
        return null;
      case "runtime.logs":
        return [
          { source: "app", lines: ["12:00:01 runtime ready"] },
          { source: "installer", lines: ["12:00:00 rootfs ready"] },
          { source: "opencode", lines: ["12:00:01 opencode 1.19.0 on 127.0.0.1:41235"] },
        ];
      case "engines.start":
        await ensure(p.target);
        idleLater(p.target);
        return null;
      case "engines.stop":
        for (const e of Object.keys(runtime.backends)) if (runtime.backends[e].phase !== "stopped") setEngine(e, "stopped");
        return null;
      case "engines.versions":
        return { opencode: versionOf("opencode") };
      case "models.list":
        return { items: models(), errors: {}, pending };
      case "sessions.list":
        return { items: chats.map((c) => ({ ...meta(c), busy: false })), errors: {} };
      case "sessions.create": {
        const c = chat(`c_new${seq++}`, "", Date.now(), Date.now(), [], null, null);
        chats.unshift(c);
        history[c.id] = [];
        return { ...meta(c), busy: false };
      }
      case "sessions.messages":
        return history[p.sessionId] ?? [];
      case "sessions.delete": {
        const i = chats.findIndex((c) => c.id === p.sessionId);
        if (i >= 0) chats.splice(i, 1);
        return null;
      }
      case "chat.send":
        await send(p.sessionId, p.text, p.model, p.effort ?? null, p.modelName ?? null, p.attachments ?? []);
        return null;
      case "attachments.add": {
        await sleep(500);
        const items = [
          { id: `f${pickSeq++}`, name: "whiteboard.jpg", size: 3_355_443, mime: "image/jpeg" },
          { id: `f${pickSeq++}`, name: "orders-2026-09.csv", size: 48_128, mime: "text/csv" },
        ];
        items.forEach((f) => (picked[f.id] = f));
        const errors = flags.includes("full")
          ? [{ name: "dataset.tar", error: "attach_no_space", where: "sandbox", needed: 12_884_901_888, free: 9_126_805_504 }]
          : [];
        return { items, errors };
      }
      case "attachments.discard":
        for (const id of p.ids ?? []) delete picked[id];
        return null;
      case "skills.list":
        return skills.map((x) => ({ ...x }));
      case "skills.add": {
        await sleep(400);
        const sk = { name: "pdf-forms", description: "Fills in PDF forms from a table of values and checks every field was set.", files: 5, bytes: 41_984 };
        if (!skills.some((x) => x.name === sk.name)) skills.push(sk);
        return sk;
      }
      case "skills.delete": {
        const i = skills.findIndex((x) => x.name === p.name);
        if (i >= 0) skills.splice(i, 1);
        return null;
      }
      case "storage.get":
        return JSON.parse(JSON.stringify(storage));
      case "storage.setS3": {
        await sleep(900);
        if (p.secret === "bad") throw new Error("s3_denied");
        storage.s3 = { endpoint: p.endpoint, bucket: p.bucket, accessKeyId: p.accessKeyId, region: p.region ?? "", prefix: p.prefix ?? "" };
        return { s3: storage.s3 };
      }
      case "storage.clearS3":
        storage.s3 = null;
        if (storage.destination === "s3") storage.destination = "device";
        return null;
      case "storage.setDestination":
        if (p.destination === "s3" && !storage.s3) throw new Error("s3_not_connected");
        storage.destination = p.destination;
        return null;
      case "outputs.list": {
        const items = (kept[p.shelf] ?? []).map((x) => ({ ...x, chatTitle: titleOf(x.chatId) }));
        return { items, total: items.reduce((n, x) => n + x.size, 0) };
      }
      case "outputs.export":
        await sleep(400);
        if (!(kept[p.shelf] ?? []).some((x) => x.chatId === p.chatId && x.name === p.name)) throw new Error("output_missing");
        return { where: p.action === "share" ? "share" : "downloads", name: p.name };
      case "outputs.delete":
        kept[p.shelf] = (kept[p.shelf] ?? []).filter((x) => !(x.chatId === p.chatId && x.name === p.name));
        return null;
      case "git.list":
        return { items: repos, total: repos.reduce((n, r) => n + r.size, 0) };
      case "git.export":
        await sleep(600);
        return { where: "downloads", name: `${p.name}-main.bundle` };
      case "git.delete":
        repos = repos.filter((r) => r.name !== p.name);
        return null;
      case "git.deleteAll":
        repos = [];
        return null;
      case "outputs.deleteAll":
        kept[p.shelf] = [];
        return null;
      case "plugins.list":
        return plugins.map((x) => ({ ...x }));
      case "plugins.add": {
        await sleep(500);
        if (flags.includes("badplugin")) throw new Error("plugin_invalid:is_skill: This is a skill, not a plugin.");
        const pl = {
          name: "summarize", version: "0.3.0", description: "Summaries of long files.", format: "agent-plugins",
          files: 4, bytes: 3_210, hooksAllowed: false,
          skills: [{ name: "summarize", description: "Summarizes a file." }, { name: "outline", description: "Outlines a file." }],
          mcpServers: [], hooks: [], agents: [], commands: 0, apps: 0,
        };
        if (!plugins.some((x) => x.name === pl.name)) plugins.push(pl);
        return { ...pl };
      }
      case "plugins.delete": {
        const i = plugins.findIndex((x) => x.name === p.name);
        if (i >= 0) plugins.splice(i, 1);
        return null;
      }
      case "chat.abort":
        emit({ type: "chat.status", sessionId: p.sessionId, status: "idle" });
        return null;
      case "approval.reply":
        return null;
      case "question.reply": {
        const resolve = pendingQuestions[p.requestId];
        delete pendingQuestions[p.requestId];
        emit({ type: "question.resolved", requestId: p.requestId });
        resolve?.(p.answers ?? null);
        return null;
      }
      case "accounts.get":
        return {
          opencode: { keys: Object.keys(keys).filter((k) => keys[k]), state: runtime.backends.opencode },
          tensorlake,
          tinyfish,
        };
      case "opencode.setKey":
        // Like the host: opencode may have to start first, then the key is stored and the models reload.
        if (runtime.backends.opencode.phase !== "ready") {
          emit({ type: "key.progress", provider: p.provider, stage: "starting" });
          await sleep(1800);
        }
        emit({ type: "key.progress", provider: p.provider, stage: "saving" });
        await sleep(700);
        emit({ type: "key.progress", provider: p.provider, stage: "models" });
        await sleep(600);
        keys[p.provider ?? "opencode"] = !!p.key;
        emit({ type: "models.changed" });
        return null;
      case "options.get":
        return { items: OPTIONS, enabled: enabledOptions };
      case "options.set":
        enabledOptions = (p.enabled ?? []).filter((id: string) => OPTIONS.some((o) => o.id === id));
        return { enabled: enabledOptions };
      case "tensorlake.set":
        await sleep(500);
        if (p.key === "bad") throw new Error("tensorlake_key_invalid");
        tensorlake.keySet = !!p.key;
        emit({ type: "account.changed", backend: "tensorlake" });
        return null;
      case "tinyfish.set":
        await sleep(500);
        if (p.key === "bad") throw new Error("tinyfish_key_invalid");
        tinyfish.keySet = !!p.key;
        emit({ type: "account.changed", backend: "tinyfish" });
        return null;
      case "files.list": {
        const c = chats.find((x) => x.id === p.sessionId);
        await sleep(700);
        // c_build's cloud sandbox is past Tensorlake's retention in this preview.
        if (!c || !c.segments.length || (c.id === "c_build" && c.place === "cloud")) return { path: p.path ?? WORKSPACE, entries: [], missing: true };
        const path = p.path ?? WORKSPACE;
        return { path, entries: FILES[path] ?? [] };
      }
      case "files.export":
        await sleep(400);
        return { where: "downloads", name: String(p.path).split("/").pop() };
      case "files.zip":
        await sleep(1500);
        return { where: p.action === "share" ? "share" : "downloads", name: "workspace.zip" };
      case "settings.get":
        return { ...settings };
      case "settings.set":
        Object.assign(settings, p);
        // A copy, as the host's JSON reply is: the store sees a new object.
        return { ...settings };
      case "updates.last": // the check the host runs when the app opens
      case "updates.check":
        return Object.fromEntries(
          ["opencode"].map((e) => [e, { ...versionOf(e), latest: published[e], updateAvailable: compareVersions(published[e], inUse[e]) > 0 }]),
        );
      case "updates.apply": {
        if (compareVersions(published[p.tool], inUse[p.tool]) <= 0) return { version: null };
        for (const progress of [0.2, 0.6, 1]) {
          emit({ type: "update.progress", tool: p.tool, progress });
          await sleep(300);
        }
        inUse[p.tool] = published[p.tool];
        return { version: inUse[p.tool] };
      }
      case "updates.reset":
        inUse[p.tool] = ENGINE_VERSION[p.tool];
        return null;
      case "app.openUrl":
      case "app.copy":
      case "app.share":
      case "app.requestNotifications":
        return null;
    }
    throw new Error(`mock: unknown method ${method}`);
  };
}

const WORKSPACE = "/tmp/workspace";

/** The preview's sandbox folders (the same tree for every chat that has run). */
const FILES: Record<string, any[]> = {
  [WORKSPACE]: [
    file("src", true),
    file("tests", true),
    file("README.md", false, 1_240, 0.4),
    file("report.pdf", false, 245_760, 0.3),
  ],
  [`${WORKSPACE}/src`]: [file("parser.py", false, 6_210, 0.3), file("http_client.py", false, 3_980, 1.1)],
  [`${WORKSPACE}/tests`]: [file("test_parser.py", false, 2_150, 0.3)],
};

function file(name: string, dir: boolean, size: number | null = null, hoursAgo = 0) {
  return { name, dir, size: dir ? null : size, modifiedAt: dir ? null : now - hoursAgo * H };
}

function chat(id: string, title: string, createdAt: number, updatedAt: number, segments: any[], model: string | null, effort: string | null) {
  return { id, title, createdAt, updatedAt, segments, model, effort, place: null as string | null };
}

function seg(engine: string, sessionId: string, model: string | null) {
  return { engine, sessionId, model };
}

function stamp(s: any, message: any) {
  return { ...message, segment: s.sessionId, engine: s.engine, segmentModel: s.model };
}

function m(
  id: string, backend: string, provider: string, providerName: string, name: string, free: boolean,
  efforts: string[], defaultEffort: string | null, isDefault: boolean, available: boolean,
  description: string | null, contextWindow: number | null = null,
) {
  return { id, backend, provider, providerName, name, free, efforts, defaultEffort, isDefault, available, description, contextWindow };
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
