// Shapes exchanged with the Kotlin host. Kept in sync with docs/bridge-protocol.md.

export type Engine = "opencode";

export type RuntimePhase = "idle" | "installing" | "ready" | "failed";
export type BackendPhase = "stopped" | "starting" | "ready" | "failed";

export interface BackendStatus {
  phase: BackendPhase;
  detail: string | null;
}

export interface RuntimeState {
  phase: RuntimePhase;
  progress: number;
  error: string | null;
  backends: Partial<Record<Engine, BackendStatus>>;
}

export interface ModelInfo {
  /** "<engine>:<model id>"; for opencode the model id is "<provider>/<model>". */
  id: string;
  backend: Engine;
  provider: string;
  providerName: string;
  name: string;
  free: boolean;
  contextWindow: number | null;
  efforts: string[];
  defaultEffort: string | null;
  isDefault: boolean;
  /** False when the model cannot run right now. */
  available: boolean;
  description: string | null;
  /** What the model takes besides text ("image", "pdf", …); attached files of these kinds reach it directly. */
  inputs?: string[];
  /** The model cannot call tools: it runs as a plain chat, with no sandbox, so it is no subagent model. */
  chatOnly?: boolean;
}

/** A chat owned by the app. */
export interface SessionInfo {
  id: string;
  /** Engines this chat has used, in order ("codex" in chats from before 0.11). Empty until the first message. */
  engines: string[];
  /** The engine holding the chat's latest turns. */
  engine?: string | null;
  /** Model ("<engine>:<model id>") and effort of the last message sent; reopening the chat picks them again. */
  model?: string | null;
  effort?: string | null;
  title: string;
  createdAt: number | null;
  updatedAt: number | null;
  busy: boolean;
  /** Set once the chat has run: where its work lives. */
  place?: Place | null;
}

export type PartStatus = "pending" | "running" | "completed" | "error";

export type Part =
  | { id: string; kind: "text"; text: string }
  | { id: string; kind: "reasoning"; text: string }
  | {
      id: string;
      kind: "tool";
      tool: string;
      title: string;
      status: PartStatus;
      input?: string | null;
      output?: string | null;
      diff?: string | null;
      exitCode?: number | null;
    }
  | { id: string; kind: "diff"; status: string; files: { path: string; change: string; diff: string }[] }
  | { id: string; kind: "notice"; notice?: "files" | "retry" | "compacted"; text: string }
  | { id: string; kind: "usage"; input: number | null; output: number | null; reasoning: number | null; cost: number }
  /** Files sent with a user message, now in the chat's sandbox at [path]. */
  | { id: string; kind: "attachments"; items: SentAttachment[] }
  /** Files the agent handed over at the end of the turn (docs/adr/outputs.md). */
  | { id: string; kind: "outputs"; files: OutputFile[] }
  /** Questions the agent put to the user (docs/adr/questions.md). */
  | { id: string; kind: "question"; status: "asking" | "answered" | "dismissed"; questions: Question[] };

/** One of the agent's multiple-choice questions; [answer] once replied (labels, or the user's own text). */
export interface Question {
  header: string;
  question: string;
  options: { label: string; description: string | null }[];
  multiple: boolean;
  /** The user may type an answer of their own. */
  custom: boolean;
  answer?: string[] | null;
}

export interface QuestionRequest {
  requestId: string;
  sessionId: string;
  questions: Question[];
}

/** Where handed-over files are kept. */
export type Shelf = "device" | "s3";

export interface OutputFile {
  name: string;
  size: number;
  shelf: Shelf;
  chatId: string;
  /** Set when it could not be kept: "output_no_space", "output_failed". */
  error?: string | null;
}

/** A kept file (`outputs.list`). */
export interface OutputItem {
  chatId: string;
  chatTitle?: string | null;
  name: string;
  size: number;
  modifiedAt: number;
}

/** The connected bucket, without its secret. */
export interface S3Info {
  endpoint: string;
  bucket: string;
  accessKeyId: string;
  region: string;
  prefix: string;
}

export interface StorageInfo {
  destination: Shelf;
  s3: S3Info | null;
}

export interface SentAttachment {
  name: string;
  path: string;
  size: number;
  mime: string;
  /** Also given to the model directly (an image or document it takes). */
  inline?: boolean;
}

/** A file picked for the next message, kept by the host until it is sent (`attachments.add`). */
export interface Attachment {
  id: string;
  name: string;
  size: number;
  mime: string;
}

/** Why a picked file was refused; `attach_no_space` comes with the sizes. */
export interface AttachError {
  name: string | null;
  error: string;
  where?: "device" | "sandbox";
  needed?: number;
  free?: number;
}

/** An installed skill (`skills.list`). */
export interface SkillInfo {
  name: string;
  description: string;
  files: number;
  bytes: number;
}

/** An installed plugin (`plugins.list`): what it holds, read from its manifest. */
export interface PluginInfo {
  name: string;
  version?: string | null;
  description: string;
  /** Which manifest it came with: "agent-plugins", "codex", "claude" or "cursor". */
  format: string;
  files: number;
  bytes: number;
  skills: { name: string; description: string }[];
  /** remote: reached by URL (used); otherwise started as a command (not used). */
  mcpServers: { name: string; remote: boolean; url?: string | null; command?: string | null }[];
  hooks: { event: string; command: string }[];
  agents: { name: string; description: string }[];
  commands: number;
  apps: number;
}

export interface Message {
  id: string;
  role: "user" | "assistant";
  createdAt: number | null;
  model?: string | null;
  error?: string | null;
  parts: Part[];
  /** Locally added before the engine echoes it back. */
  pending?: boolean;
  /** Set on stored messages: which engine session (segment) they belong to. */
  engine?: string;
  segment?: string;
  segmentModel?: string | null;
}

export interface TodoItem {
  text: string;
  status: "pending" | "in_progress" | "completed" | "cancelled" | string;
}

export interface ApprovalRequest {
  requestId: string;
  sessionId: string;
  kind: string;
  title: string | null;
  detail: string | null;
}

export interface Accounts {
  /** `keys`: the providers whose API key is stored ("opencode" is OpenCode Zen). */
  opencode: { keys: string[]; state: BackendStatus } | null;
  /** A key puts chats in Tensorlake sandboxes; without it they run in on-device jails. */
  tensorlake: { keySet: boolean };
  /** A key lets web search and fetch fall back to TinyFish when the usual service runs out. */
  tinyfish: { keySet: boolean };
}

/** Where a chat runs, fixed on its first message. */
export type Place = "cloud" | "phone";

/** An optional engine feature shown in the options sheet, worded by `opt.<id>` in i18n. */
export interface EngineOption {
  id: string;
  engine: Engine;
  /** On until the user turns it off. */
  default?: boolean;
  /** An option this one does nothing without. */
  requires?: string | null;
}

export type Approval = "full_auto" | "risky" | "always";

export interface Settings {
  approval: Approval;
  lastModel: string | null;
  lastEffort: string | null;
  language: "system" | "ja" | "en";
  disableSeccomp: boolean;
  /** Install newer engine releases found when the app opens. */
  autoUpdate?: boolean;
  /** Given to opencode after the app's own instructions; "" = none. */
  systemPrompt?: string;
  /** "<engine>:<provider>/<model>" subagents run on; null = the chat's model (docs/adr/subagent-model.md). */
  subagentModel?: string | null;
  subagentEffort?: string | null;
}

export interface ToolUpdateInfo {
  current: string;
  bundled: boolean;
  latest?: string | null;
  updateAvailable?: boolean;
  error?: string;
}

export interface AppInfo {
  version: string;
  workspace: string;
  android: string;
  device: string;
  abi: string | null;
}

/** What a chat's message is waiting for before its engine gets it (`chat.stage`). */
export type Stage = "sandbox" | "upload" | "install" | "engine";

/** One entry of a chat sandbox's working folder (`files.list`). */
export interface FileEntry {
  name: string;
  dir: boolean;
  size: number | null;
  modifiedAt: number | null;
}

export interface FileListing {
  /** Absolute path in the sandbox, at or under `AppInfo.workspace`. */
  path: string;
  entries: FileEntry[];
  /** The chat has no sandbox: it never ran, or Tensorlake deleted it. */
  missing?: boolean;
}

/** Where `files.export` / `files.zip` put the file: Download/PoorHarness, or the share sheet. */
/** A git repository in the bucket (`git.list`). */
export interface RepoItem {
  name: string;
  /** The default branch, if set. */
  head: string | null;
  branches: string[];
  size: number;
  modifiedAt: number;
  /** Its address for git-remote-s3 ("s3://<bucket>/<folder>/git/<name>"). */
  url: string;
}

export interface ExportResult {
  where: "downloads" | "share";
  name: string;
}

export interface LogSource {
  source: string;
  lines: string[];
}

export interface Listed<T> {
  items: T[];
  errors: Partial<Record<Engine, string>>;
  /** Engines still being started for the first time to learn their models. */
  pending?: Engine[];
}
