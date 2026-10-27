import { create } from "zustand";
import { call, onEvent } from "./bridge";
import type {
  Accounts,
  AppInfo,
  ApprovalRequest,
  QuestionRequest,
  AttachError,
  Attachment,
  Engine,
  EngineOption,
  Listed,
  Message,
  ModelInfo,
  Part,
  RuntimeState,
  SessionInfo,
  Settings,
  PluginInfo,
  Shelf,
  SkillInfo,
  StorageInfo,
  TodoItem,
  ToolUpdateInfo,
} from "./types";

export type Screen =
  | { name: "home" }
  | { name: "chat"; sessionId: string }
  /** A folder of a chat's sandbox; [path] is absolute, the workspace when omitted. */
  | { name: "files"; sessionId: string; path?: string }
  | { name: "settings" }
  | { name: "diagnostics" }
  | { name: "licenses" }
  | { name: "outputs"; shelf: Shelf }
  | { name: "repos" };

export interface Toast {
  id: number;
  text: string;
  tone?: "error";
}

interface State {
  runtime: RuntimeState | null;
  info: AppInfo | null;
  settings: Settings | null;
  accounts: Accounts | null;
  models: ModelInfo[];
  modelErrors: Partial<Record<Engine, string>>;
  /** Engines being started once to learn their models (first run). */
  modelsPending: Engine[];
  sessions: SessionInfo[];
  stack: Screen[];
  messages: Record<string, Message[]>;
  todos: Record<string, TodoItem[]>;
  busy: Record<string, boolean>;
  /** Why a busy session is waiting (for example a rate-limit retry), if the engine says. */
  statusDetail: Record<string, string | null>;
  /** When the engine tries a waiting session again (epoch ms), if it said. */
  retryAt: Record<string, number | null>;
  /** What a chat's message waits for before its engine gets it: sandbox, upload, engine start. */
  stage: Record<string, string | null>;
  /** Background subagents still working per chat (opencode), after the chat's own turn ended. */
  background: Record<string, number>;
  errors: Record<string, string | null>;
  approvals: ApprovalRequest[];
  questions: QuestionRequest[];
  model: string | null;
  effort: string | null;
  toasts: Toast[];
  updates: Partial<Record<Engine, ToolUpdateInfo>>;
  updateProgress: Partial<Record<Engine, number>>;
  modelSheet: boolean;
  /** The picker for the subagents' model, opened from the options sheet. */
  subagentSheet: boolean;
  titleSheet: boolean;
  /** The Tensorlake API key sheet, opened from wherever a chat needs the key. */
  keySheet: boolean;
  /** The engine options sheet. */
  optionsSheet: boolean;
  /** The API key sheet for an opencode provider, if open. */
  providerKeySheet: ProviderKey | null;
  tinyfishSheet: boolean;
  /** While a key is being saved: what the host is doing ("starting", "saving", "models"), if it said. */
  keyStage: KeyStage | null;
  /** The key sheet was opened from the model picker, which comes back after a save. */
  keyFromPicker: boolean;
  /** A group the picker opens with besides the selected model's (a provider whose key was just added). */
  pickerOpenGroup: string | null;
  /** The optional features each engine offers, and which are on. */
  options: EngineOption[];
  engineOptions: string[];
  /** Text to put back into a chat's composer (a first message whose engine failed to start). */
  drafts: Record<string, string>;
  /** Files picked for the next message, per composer (a chat id, or [HOME_DRAFT]). */
  attachments: Record<string, Attachment[]>;
  /** Composers whose picked files are still being copied in. */
  attaching: Record<string, boolean>;
  /** Upload progress (0..1) while a chat's stage is "upload". */
  stageProgress: Record<string, number | null>;
  /** Installed skills (Settings); null until loaded. */
  skills: SkillInfo[] | null;
  /** Installed plugins (Settings); null until loaded. */
  plugins: PluginInfo[] | null;
  /** The plugin whose sheet is open in Settings. */
  pluginSheet: string | null;
  /** Where handed-over files go, and the connected bucket; null until loaded. */
  storage: StorageInfo | null;
  /** The bucket sheet in Settings. */
  s3Sheet: boolean;
}

const initial: State = {
  runtime: null,
  info: null,
  settings: null,
  accounts: null,
  models: [],
  modelErrors: {},
  modelsPending: [],
  sessions: [],
  stack: [{ name: "home" }],
  messages: {},
  todos: {},
  busy: {},
  statusDetail: {},
  retryAt: {},
  stage: {},
  background: {},
  errors: {},
  approvals: [],
  questions: [],
  model: null,
  effort: null,
  toasts: [],
  updates: {},
  updateProgress: {},
  modelSheet: false,
  subagentSheet: false,
  titleSheet: false,
  keySheet: false,
  optionsSheet: false,
  providerKeySheet: null,
  tinyfishSheet: false,
  keyStage: null,
  keyFromPicker: false,
  pickerOpenGroup: null,
  options: [],
  engineOptions: [],
  drafts: {},
  attachments: {},
  attaching: {},
  stageProgress: {},
  skills: null,
  plugins: null,
  pluginSheet: null,
  storage: null,
  s3Sheet: false,
};

export const useStore = create<State>(() => initial);
const set = useStore.setState;
const get = useStore.getState;

let toastSeq = 1;
export function toast(text: string, tone?: "error") {
  const id = toastSeq++;
  set((s) => ({ toasts: [...s.toasts, { id, text, tone }] }));
  setTimeout(() => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), tone ? 5000 : 2600);
}

// --- navigation -------------------------------------------------------------

export function push(screen: Screen) {
  set((s) => ({ stack: [...s.stack, screen] }));
}

/**
 * Sheets a screen keeps in its own state register how back closes them; the
 * latest one opened closes first. Returns the unregister function.
 */
const closers: (() => void)[] = [];
export function onBackClose(close: () => void): () => void {
  closers.push(close);
  return () => {
    const i = closers.lastIndexOf(close);
    if (i >= 0) closers.splice(i, 1);
  };
}

/** Handles the system back gesture; returns false when there is nothing to close. */
export function pop(): boolean {
  const s = get();
  const close = closers[closers.length - 1];
  if (close) {
    close();
    return true;
  }
  if (s.modelSheet) {
    set({ modelSheet: false });
    return true;
  }
  if (s.subagentSheet) {
    setSubagentSheet(false);
    return true;
  }
  if (s.titleSheet) {
    setTitleSheet(false);
    return true;
  }
  if (s.keySheet) {
    set({ keySheet: false });
    return true;
  }
  if (s.optionsSheet) {
    set({ optionsSheet: false });
    return true;
  }
  if (s.providerKeySheet) {
    set({ providerKeySheet: null });
    return true;
  }
  if (s.pluginSheet) {
    set({ pluginSheet: null });
    return true;
  }
  if (s.s3Sheet) {
    set({ s3Sheet: false });
    return true;
  }
  if (s.tinyfishSheet) {
    set({ tinyfishSheet: false });
    return true;
  }
  if (s.stack.length <= 1) return false;
  set({ stack: s.stack.slice(0, -1) });
  return true;
}

export const currentScreen = (s: State) => s.stack[s.stack.length - 1];

export function setModelSheet(open: boolean) {
  set({ modelSheet: open });
}

export function setKeySheet(open: boolean) {
  set({ keySheet: open, modelSheet: open ? false : get().modelSheet });
}

/** With a key, new chats run in Tensorlake sandboxes; without one, on the device. Unknown counts as set. */
export const hasTensorlakeKey = (s: State) => s.accounts?.tensorlake?.keySet !== false;

export const hasTinyfishKey = (s: State) => !!s.accounts?.tinyfish?.keySet;

export function setTinyfishSheet(open: boolean) {
  set({ tinyfishSheet: open });
}

/** Providers whose key the app stores for opencode: OpenCode Zen ("opencode"), NVIDIA NIM, OrcaRouter, Infron or Venice. */
export type ProviderKey = "opencode" | "nvidia" | "orcarouter" | "infron" | "venice";

export const hasProviderKey = (s: State, p: ProviderKey) => !!s.accounts?.opencode?.keys?.includes(p);

export function setProviderKeySheet(provider: ProviderKey | null) {
  const fromPicker = !!provider && get().modelSheet;
  set({ providerKeySheet: provider, keyFromPicker: provider ? fromPicker : get().keyFromPicker, modelSheet: provider ? false : get().modelSheet });
}

/** The picker consumes the group it was asked to open. */
export function takePickerOpenGroup(): string | null {
  const g = get().pickerOpenGroup;
  if (g) set({ pickerOpenGroup: null });
  return g;
}

export type KeyStage = "checking" | "starting" | "saving" | "models";

export function setKeyStage(stage: KeyStage | null) {
  set({ keyStage: stage });
}

/**
 * Stores (or with null, removes) an opencode provider key, then reloads accounts and
 * models. A key added from the model picker brings the picker back, that provider open.
 */
export async function setProviderKey(provider: ProviderKey, key: string | null) {
  try {
    await call("opencode.setKey", { provider, key });
    set({ keyStage: "models" });
    await refreshAccounts();
    await refreshModels();
  } finally {
    set({ keyStage: null });
  }
  if (key) {
    set({ pickerOpenGroup: `p:${provider}` });
    if (get().keyFromPicker) set({ keyFromPicker: false, providerKeySheet: null, modelSheet: true });
  }
}

export function setOptionsSheet(open: boolean) {
  set({ optionsSheet: open, modelSheet: open ? false : get().modelSheet });
}

/** Opened from the options sheet, which comes back when this closes. */
export function setSubagentSheet(open: boolean) {
  set({ subagentSheet: open, optionsSheet: !open });
}

/** Opened from the options sheet, which comes back when this closes. */
export function setTitleSheet(open: boolean) {
  set({ titleSheet: open, optionsSheet: !open });
}

/** The model chat titles are written with; null leaves it to the chat's model (docs/adr/title-model.md). */
export async function chooseTitleModel(id: string | null) {
  await updateSettings({ titleModel: id });
}

/** The model subagents run on; null keeps them on the chat's model (docs/adr/subagent-model.md). */
export async function chooseSubagentModel(id: string | null, effort: string | null) {
  await updateSettings({ subagentModel: id, subagentEffort: id ? effort : null });
}

export async function loadOptions() {
  try {
    const res = await call<{ items: EngineOption[]; enabled: string[] }>("options.get");
    set({ options: res.items, engineOptions: res.enabled });
  } catch {
    /* the sheet shows nothing until the host answers */
  }
}

/** Turns an engine option on or off; the engine picks it up on its next start. */
export async function setOption(id: string, on: boolean) {
  const cur = get().engineOptions;
  const next = on ? [...cur.filter((x) => x !== id), id] : cur.filter((x) => x !== id);
  set({ engineOptions: next });
  try {
    const res = await call<{ enabled: string[] }>("options.set", { enabled: next });
    set({ engineOptions: res.enabled });
  } catch (e) {
    set({ engineOptions: cur });
    throw e;
  }
}

// --- loading ------------------------------------------------------------------

let initialized = false;
export async function init() {
  if (initialized) return;
  initialized = true;
  onEvent(handleEvent);
  const [info, settings, runtime] = await Promise.all([
    call<AppInfo>("app.info"),
    call<Settings>("settings.get"),
    call<RuntimeState>("runtime.state"),
  ]);
  set({ info, settings, runtime });
  // Chats, the model list and accounts are kept by the host: none of them waits for Ubuntu or an engine.
  if (runtime.phase !== "ready" && runtime.phase !== "installing") void call("runtime.start");
  await refreshAll();
}

export async function refreshAll() {
  await refreshAccounts();
  await Promise.all([refreshModels(), refreshSessions(), loadOptions()]);
}

export async function refreshModels() {
  try {
    const res = await call<Listed<ModelInfo>>("models.list");
    set({ models: res.items, modelErrors: res.errors, modelsPending: res.pending ?? [] });
    reconcileSelection();
  } catch (e) {
    toast((e as Error).message, "error");
  }
}

export async function refreshSessions() {
  try {
    const res = await call<Listed<SessionInfo>>("sessions.list");
    set((s) => {
      const busy = { ...s.busy };
      for (const x of res.items) if (x.busy) busy[x.id] = true;
      return { sessions: sortSessions(res.items), busy };
    });
  } catch (e) {
    toast((e as Error).message, "error");
  }
}

export async function refreshAccounts() {
  try {
    set({ accounts: await call<Accounts>("accounts.get") });
  } catch {
    /* shown as unknown */
  }
}

function sortSessions(list: SessionInfo[]) {
  return [...list].sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0));
}

/** Picks the model to use without hardcoding any: last choice, else the engines' defaults. */
function reconcileSelection() {
  const s = get();
  const usable = s.models.filter((m) => m.available);
  const pick = (id: string | null) => usable.find((m) => m.id === id);
  const chosen =
    pick(s.model) ??
    pick(s.settings?.lastModel ?? null) ??
    usable.find((m) => m.backend === "opencode" && m.isDefault && m.free) ??
    usable.find((m) => m.free) ??
    usable[0];
  if (!chosen) {
    set({ model: null, effort: null });
    return;
  }
  const keepEffort = chosen.id === s.model ? s.effort : s.settings?.lastEffort ?? null;
  const effort = keepEffort && chosen.efforts.includes(keepEffort) ? keepEffort : chosen.defaultEffort;
  set({ model: chosen.id, effort });
}

export function chooseModel(id: string, effort: string | null) {
  set({ model: id, effort });
  void call("settings.set", { lastModel: id, lastEffort: effort });
}

/** The engine behind the model currently picked, if any. */
export const selectedEngine = (s: State): Engine | null => s.models.find((m) => m.id === s.model)?.backend ?? null;

// --- sessions & chat ----------------------------------------------------------

export async function openSession(id: string) {
  adoptChatModel(id);
  push({ name: "chat", sessionId: id });
  await loadMessages(id);
}

/**
 * Reopening a chat picks the model it last used, so a follow-up stays where the
 * chat was. Picking another model in the chat still moves it, history and all.
 */
function adoptChatModel(id: string) {
  const s = get();
  const chat = s.sessions.find((x) => x.id === id);
  if (!chat) return;
  const usable = s.models.filter((m) => m.available);
  let m = chat.model ? usable.find((x) => x.id === chat.model) : undefined;
  // Chats from before 0.1.2 have no model of record: stay on their engine.
  if (!m && !chat.model && chat.engine && selectedEngine(s) !== chat.engine) {
    m = usable.find((x) => x.backend === chat.engine && x.isDefault) ?? usable.find((x) => x.backend === chat.engine);
  }
  if (!m) return;
  const effort = chat.effort && m.efforts.includes(chat.effort) ? chat.effort : m.id === s.model ? s.effort : m.defaultEffort;
  if (m.id !== s.model || effort !== s.effort) set({ model: m.id, effort });
}

export async function loadMessages(id: string) {
  try {
    const list = await call<Message[]>("sessions.messages", { sessionId: id });
    set((s) => ({ messages: { ...s.messages, [id]: list } }));
  } catch (e) {
    set((s) => ({ errors: { ...s.errors, [id]: (e as Error).message } }));
  }
}

export async function startChat(text: string, files: Attachment[] = []) {
  const s = get();
  if (!s.model) throw new Error("No model selected");
  const session = await call<SessionInfo>("sessions.create");
  set((st) => ({
    sessions: sortSessions([
      { ...session, title: session.title || text.slice(0, 60), updatedAt: Date.now() },
      ...st.sessions.filter((x) => x.id !== session.id),
    ]),
    messages: { ...st.messages, [session.id]: [] },
  }));
  push({ name: "chat", sessionId: session.id });
  try {
    await send(session.id, text, files);
  } catch {
    // The chat shows the error; give the text and files back to its composer for another try.
    set((st) => ({
      drafts: { ...st.drafts, [session.id]: text },
      attachments: files.length ? { ...st.attachments, [session.id]: files } : st.attachments,
    }));
  }
}

/** The composer key of the Home screen's message box (a chat not created yet). */
export const HOME_DRAFT = "home";

/**
 * Opens the system picker and copies the chosen files in for composer [key].
 * Returns the files refused (no room where the chat's files go, or unreadable).
 */
export async function addAttachments(key: string, sessionId?: string): Promise<AttachError[]> {
  set((s) => ({ attaching: { ...s.attaching, [key]: true } }));
  try {
    const r = await call<{ items: Attachment[]; errors: AttachError[] }>("attachments.add", { sessionId: sessionId ?? null });
    if (r.items.length) set((s) => ({ attachments: { ...s.attachments, [key]: [...(s.attachments[key] ?? []), ...r.items] } }));
    return r.errors;
  } finally {
    set((s) => ({ attaching: { ...s.attaching, [key]: false } }));
  }
}

export function removeAttachment(key: string, id: string) {
  set((s) => ({ attachments: { ...s.attachments, [key]: (s.attachments[key] ?? []).filter((a) => a.id !== id) } }));
  void call("attachments.discard", { ids: [id] });
}

/** After a send: the host took the files. */
export function clearAttachments(key: string) {
  set((s) => {
    const attachments = { ...s.attachments };
    delete attachments[key];
    return { attachments };
  });
}

/** Puts files back into composer [key] (a send that failed), ahead of any picked since. */
export function restoreAttachments(key: string, files: Attachment[]) {
  if (!files.length) return;
  set((s) => ({ attachments: { ...s.attachments, [key]: [...files, ...(s.attachments[key] ?? []).filter((a) => !files.includes(a))] } }));
}

export async function loadSkills() {
  const skills = await call<SkillInfo[]>("skills.list");
  set({ skills });
}

/** Opens the picker for a .skill or .zip; returns the added skill's name, or null if none was picked. */
export async function addSkill(): Promise<string | null> {
  const added = await call<SkillInfo | null>("skills.add");
  await loadSkills();
  return added?.name ?? null;
}

export async function deleteSkill(name: string) {
  await call("skills.delete", { name });
  set((s) => ({ skills: (s.skills ?? []).filter((x) => x.name !== name) }));
}

export async function loadPlugins() {
  const plugins = await call<PluginInfo[]>("plugins.list");
  set({ plugins });
}

/** Opens the picker for a plugin archive; returns the added plugin's name, or null if none was picked. */
export async function addPlugin(): Promise<string | null> {
  const added = await call<PluginInfo | null>("plugins.add");
  await loadPlugins();
  return added?.name ?? null;
}

export async function deletePlugin(name: string) {
  await call("plugins.delete", { name });
  set((s) => ({ plugins: (s.plugins ?? []).filter((x) => x.name !== name) }));
}

export async function loadStorage() {
  set({ storage: await call<StorageInfo>("storage.get") });
}

export function setS3Sheet(open: boolean) {
  set({ s3Sheet: open });
}

export async function saveS3(fields: { endpoint: string; bucket: string; accessKeyId: string; secret: string; region: string; prefix: string }) {
  await call("storage.setS3", fields);
  await loadStorage();
}

export async function clearS3() {
  await call("storage.clearS3");
  await loadStorage();
}

export async function setDestination(destination: Shelf) {
  await call("storage.setDestination", { destination });
  set((s) => ({ storage: s.storage ? { ...s.storage, destination } : s.storage }));
}

export function setPluginSheet(name: string | null) {
  set({ pluginSheet: name });
}

export function takeDraft(sessionId: string): string | undefined {
  const text = get().drafts[sessionId];
  if (text === undefined) return undefined;
  set((s) => {
    const drafts = { ...s.drafts };
    delete drafts[sessionId];
    return { drafts };
  });
  return text;
}

let askedNotifications = false;

export async function send(sessionId: string, text: string, files: Attachment[] = []) {
  const s = get();
  if (!s.model) throw new Error("No model selected");
  // Any model works in any chat: a model of the other engine carries the chat over.
  const modelName = s.models.find((m) => m.id === s.model)?.name ?? null;
  const engine = selectedEngine(s) ?? undefined;
  // Placed in the segment it will land in, so a switch shows its divider before the engine answers.
  const last = [...(s.messages[sessionId] ?? [])].reverse().find((m) => m.segment);
  const pendingMsg: Message = {
    id: `pending-${Date.now()}`,
    role: "user",
    createdAt: Date.now(),
    parts: [
      ...(text ? [{ id: "p", kind: "text" as const, text }] : []),
      ...(files.length
        ? [{ id: "f", kind: "attachments" as const, items: files.map((f) => ({ name: f.name, path: "", size: f.size, mime: f.mime })) }]
        : []),
    ],
    pending: true,
    engine,
    segment: last && last.engine !== engine ? `pending:${engine}` : last?.segment,
    segmentModel: modelName,
  };
  set((st) => ({
    messages: { ...st.messages, [sessionId]: [...(st.messages[sessionId] ?? []), pendingMsg] },
    busy: { ...st.busy, [sessionId]: true },
    errors: { ...st.errors, [sessionId]: null },
  }));
  try {
    await call("chat.send", { sessionId, text, model: s.model, effort: s.effort, modelName, attachments: files.map((f) => f.id) });
    if (!askedNotifications) {
      askedNotifications = true;
      void call("app.requestNotifications");
    }
  } catch (e) {
    set((st) => ({
      busy: { ...st.busy, [sessionId]: false },
      errors: { ...st.errors, [sessionId]: (e as Error).message },
      messages: { ...st.messages, [sessionId]: (st.messages[sessionId] ?? []).filter((m) => m !== pendingMsg) },
    }));
    throw e;
  }
}

export async function abort(sessionId: string) {
  await call("chat.abort", { sessionId });
}

export async function deleteSession(sessionId: string) {
  await call("sessions.delete", { sessionId });
  set((s) => ({ sessions: s.sessions.filter((x) => x.id !== sessionId) }));
}

export async function replyApproval(requestId: string, decision: "once" | "session" | "deny") {
  set((s) => ({ approvals: s.approvals.filter((a) => a.requestId !== requestId) }));
  await call("approval.reply", { requestId, decision });
}

/** One list of answers per question, in order; null dismisses the questions (the turn stops). */
export async function answerQuestion(requestId: string, answers: string[][] | null) {
  set((s) => ({ questions: s.questions.filter((q) => q.requestId !== requestId) }));
  await call("question.reply", answers ? { requestId, answers } : { requestId });
}

// --- accounts -------------------------------------------------------------------


export async function updateSettings(patch: Partial<Settings>) {
  const settings = await call<Settings>("settings.set", patch);
  set({ settings });
}

/** Installed engine versions (no network), and what the check at launch found, if it ran. */
export async function loadVersions() {
  try {
    const [res, last] = await Promise.all([
      call<Partial<Record<Engine, ToolUpdateInfo>>>("engines.versions"),
      call<Partial<Record<Engine, ToolUpdateInfo>> | null>("updates.last").catch(() => null),
    ]);
    set((s) => {
      const updates = { ...s.updates };
      for (const [k, v] of Object.entries(res) as [Engine, ToolUpdateInfo][]) updates[k] = { ...v, ...(last?.[k] ?? {}), ...updates[k] };
      return { updates };
    });
  } catch {
    /* versions are also shown after a check */
  }
}

export async function checkUpdates() {
  const res = await call<Partial<Record<Engine, ToolUpdateInfo>>>("updates.check");
  set({ updates: res });
}

export async function applyUpdate(tool: Engine) {
  set((s) => ({ updateProgress: { ...s.updateProgress, [tool]: 0 } }));
  try {
    await call("updates.apply", { tool });
    await checkUpdates();
  } finally {
    set((s) => {
      const next = { ...s.updateProgress };
      delete next[tool];
      return { updateProgress: next };
    });
  }
}

// --- events -----------------------------------------------------------------------

function upsertMessage(list: Message[], incoming: Message): Message[] {
  const i = list.findIndex((m) => m.id === incoming.id);
  if (i >= 0) {
    const prev = list[i];
    const next = list.slice();
    next[i] = { ...prev, ...incoming, parts: incoming.parts?.length ? incoming.parts : prev.parts };
    return next;
  }
  let next = list;
  if (incoming.role === "user") {
    // The engine echoed our message: drop the optimistic copy.
    const idx = next.findIndex((m) => m.pending);
    if (idx >= 0) next = [...next.slice(0, idx), ...next.slice(idx + 1)];
  }
  return [...next, { ...incoming, parts: incoming.parts ?? [] }];
}

function withMessage(sessionId: string, messageId: string, fn: (m: Message) => Message) {
  set((s) => {
    const list = s.messages[sessionId];
    if (!list) return {};
    let found = false;
    const next = list.map((m) => {
      if (m.id !== messageId) return m;
      found = true;
      return fn(m);
    });
    if (!found) next.push(fn({ id: messageId, role: "assistant", createdAt: Date.now(), parts: [] }));
    return { messages: { ...s.messages, [sessionId]: next } };
  });
}

function upsertPart(parts: Part[], part: Part): Part[] {
  const i = parts.findIndex((p) => p.id === part.id);
  if (i < 0) return [...parts, part];
  const next = parts.slice();
  next[i] = part;
  return next;
}

function applyDelta(parts: Part[], partId: string, field: string, delta: string): Part[] {
  const i = parts.findIndex((p) => p.id === partId);
  if (i < 0) return field === "text" ? [...parts, { id: partId, kind: "text", text: delta }] : parts;
  const p = parts[i];
  const next = parts.slice();
  if (field === "output" && p.kind === "tool") next[i] = { ...p, output: (p.output ?? "") + delta };
  else if (p.kind === "text" || p.kind === "reasoning") next[i] = { ...p, text: p.text + delta };
  return next;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function handleEvent(e: Record<string, any>) {
  switch (e.type) {
    case "runtime.state": {
      const wasReady = get().runtime?.phase === "ready";
      // runtime.state from the host carries no engine states; keep the ones we have.
      set((s) => ({ runtime: { ...(e as unknown as RuntimeState), backends: e.backends ?? s.runtime?.backends ?? {} } }));
      // After a first unpack, engines start once to learn their models.
      if (e.phase === "ready" && !wasReady) void refreshModels();
      break;
    }
    case "backend.state":
      set((s) => ({
        runtime: s.runtime ? { ...s.runtime, backends: { ...s.runtime.backends, [e.backend]: e.state } } : s.runtime,
      }));
      break;
    case "sessions.changed":
      void refreshSessions();
      break;
    case "backend.resync": {
      void refreshSessions();
      const screen = currentScreen(get());
      if (screen.name === "chat") void loadMessages(screen.sessionId);
      break;
    }
    case "models.changed":
      void refreshModels();
      break;
    case "account.changed":
      void refreshAccounts().then(refreshModels);
      break;
    case "key.progress":
      set({ keyStage: e.stage ?? null });
      break;
    case "chat.message":
      set((s) => {
        const list = s.messages[e.sessionId];
        if (!list) return {};
        return { messages: { ...s.messages, [e.sessionId]: upsertMessage(list, e.message) } };
      });
      break;
    case "chat.part":
      withMessage(e.sessionId, e.messageId, (m) => ({ ...m, parts: upsertPart(m.parts, e.part) }));
      break;
    case "chat.delta":
      withMessage(e.sessionId, e.messageId, (m) => ({ ...m, parts: applyDelta(m.parts, e.partId, e.field, e.delta) }));
      break;
    case "chat.partRemoved":
      withMessage(e.sessionId, e.messageId, (m) => ({ ...m, parts: m.parts.filter((p) => p.id !== e.partId) }));
      break;
    case "chat.stage":
      set((s) => ({
        stage: { ...s.stage, [e.sessionId]: e.stage ?? null },
        stageProgress: { ...s.stageProgress, [e.sessionId]: typeof e.progress === "number" ? e.progress : null },
      }));
      break;
    case "chat.background":
      set((s) => ({ background: { ...s.background, [e.sessionId]: e.count ?? 0 } }));
      break;
    case "chat.status": {
      const busy = e.status === "busy";
      set((s) => ({
        busy: { ...s.busy, [e.sessionId]: busy },
        statusDetail: { ...s.statusDetail, [e.sessionId]: busy ? (e.detail ?? null) : null },
        retryAt: { ...s.retryAt, [e.sessionId]: busy ? (e.retryAt ?? null) : null },
        stage: busy ? s.stage : { ...s.stage, [e.sessionId]: null },
        sessions: s.sessions.map((x) => (x.id === e.sessionId ? { ...x, busy, updatedAt: Date.now() } : x)),
      }));
      if (!busy) {
        // The host has copied the finished turn; show the stored version.
        const screen = currentScreen(get());
        if (screen.name === "chat" && screen.sessionId === e.sessionId) void loadMessages(e.sessionId);
      }
      break;
    }
    case "chat.error":
      set((s) => ({ errors: { ...s.errors, [e.sessionId]: e.message } }));
      break;
    case "chat.todo":
      set((s) => ({ todos: { ...s.todos, [e.sessionId]: e.items } }));
      break;
    case "sessions.upsert":
      set((s) => {
        const prev = s.sessions.find((x) => x.id === e.session.id);
        const merged = { ...prev, ...e.session, title: e.session.title || prev?.title || "" };
        return { sessions: sortSessions([merged, ...s.sessions.filter((x) => x.id !== e.session.id)]) };
      });
      break;
    case "approval.request":
      set((s) => ({ approvals: [...s.approvals.filter((a) => a.requestId !== e.requestId), e as unknown as ApprovalRequest] }));
      break;
    case "approval.resolved":
      set((s) => ({ approvals: s.approvals.filter((a) => a.requestId !== e.requestId) }));
      break;
    case "question.request":
      set((s) => ({ questions: [...s.questions.filter((q) => q.requestId !== e.requestId), e as unknown as QuestionRequest] }));
      break;
    case "question.resolved":
      set((s) => ({ questions: s.questions.filter((q) => q.requestId !== e.requestId) }));
      break;
    case "update.progress":
      set((s) => ({ updateProgress: { ...s.updateProgress, [e.tool]: e.progress } }));
      break;
    case "updates.changed": {
      // A check ran (at launch, or after an automatic update): its result replaces the last.
      const result = (e.result ?? {}) as Partial<Record<Engine, ToolUpdateInfo>>;
      set((s) => {
        const progress = { ...s.updateProgress };
        for (const k of Object.keys(result) as Engine[]) if (progress[k] === 1) delete progress[k];
        return { updates: { ...s.updates, ...result }, updateProgress: progress };
      });
      break;
    }
  }
}
