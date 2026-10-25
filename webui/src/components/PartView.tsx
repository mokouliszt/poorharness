import { useState } from "react";
import { ChevronRight, CircleAlert, FileDiff, LoaderCircle } from "lucide-react";
import type { Part } from "@/lib/types";
import { useT, type TKey } from "@/lib/i18n";
import { compactNumber } from "@/lib/format";
import { cn } from "@/lib/cn";
import { Markdown } from "./Markdown";
import { OutputCards } from "./OutputCards";

export function PartView({ part, live }: { part: Part; live: boolean }) {
  switch (part.kind) {
    case "text":
      return part.text ? <Markdown text={part.text} /> : null;
    case "reasoning":
      return part.text ? <Reasoning text={part.text} live={live} /> : null;
    case "tool":
      return <ToolRow part={part} />;
    case "diff":
      return <DiffBlock files={part.files} />;
    case "notice":
      return <Notice part={part} />;
    case "usage":
      return <Usage part={part} />;
    case "attachments":
      return null; // shown with the user's message
    case "outputs":
      return <OutputCards files={part.files} />;
    case "question":
      return <QuestionCard part={part} />;
  }
}

/** What the agent asked (docs/adr/questions.md) and what came back. */
function QuestionCard({ part }: { part: Extract<Part, { kind: "question" }> }) {
  const t = useT();
  return (
    <div className="space-y-3 rounded-xl border border-line bg-surface/60 px-3 py-2.5">
      {part.questions.map((q, i) => (
        <div key={i}>
          {q.header && <div className="text-xs text-mute">{q.header}</div>}
          <div className="phrase break-words text-sm text-ink">{q.question}</div>
          {part.status === "answered" && q.answer && q.answer.length > 0 && (
            <div className="mt-0.5 break-words text-sm font-medium text-paper">{q.answer.join(t("question.sep"))}</div>
          )}
        </div>
      ))}
      {part.status === "asking" && (
        <div className="flex items-center gap-1.5 text-xs text-mute">
          <LoaderCircle size={12} className="animate-spin" />
          {t("question.waiting")}
        </div>
      )}
      {part.status === "dismissed" && <div className="text-xs text-mute">{t("question.dismissed")}</div>}
    </div>
  );
}

function Reasoning({ text, live }: { text: string; live: boolean }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button
        className="flex items-center gap-1.5 text-sm text-mute"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
      >
        <ChevronRight size={14} className={cn("transition-transform", open && "rotate-90")} />
        <span className={cn(live && "animate-pulse")}>{t("chat.reasoning")}</span>
      </button>
      {open && (
        <div className="mt-2 border-l border-line pl-3 text-sm text-mute">
          <Markdown text={text} />
        </div>
      )}
    </div>
  );
}

const TOOL_VERBS: Record<string, TKey> = {
  bash: "tool.bash",
  edit: "tool.edit",
  write: "tool.write",
  read: "tool.read",
  grep: "tool.grep",
  glob: "tool.glob",
  list: "tool.list",
  webfetch: "tool.webfetch",
  websearch: "tool.websearch",
  todowrite: "tool.todowrite",
  task: "tool.task",
  ls: "tool.ls",
  multiedit: "tool.multiedit",
  apply_patch: "tool.apply_patch",
};

function ToolRow({ part }: { part: Extract<Part, { kind: "tool" }> }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const verb = TOOL_VERBS[part.tool] ? t(TOOL_VERBS[part.tool]) : part.tool;
  const running = part.status === "running" || part.status === "pending";
  const failed = part.status === "error" || (part.exitCode != null && part.exitCode !== 0);
  // Some tools (the Tensorlake plugin's bash, for one) report an empty title.
  const title = part.title || part.input || "";
  const hasBody = !!(part.output || part.diff || (part.input && part.input !== title));
  return (
    <div className="rounded-xl border border-line bg-surface/60">
      <button
        className="flex w-full items-center gap-2 px-3 py-2 text-left"
        onClick={() => hasBody && setOpen(!open)}
        aria-expanded={hasBody ? open : undefined}
      >
        {running ? (
          <LoaderCircle size={14} className="shrink-0 animate-spin text-mute" />
        ) : failed ? (
          <CircleAlert size={14} className="shrink-0 text-danger" />
        ) : (
          <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-mute" />
        )}
        <span className="shrink-0 text-sm text-mute">{verb}</span>
        <span className="min-w-0 flex-1 truncate font-mono text-xs text-ink">{title}</span>
        {part.exitCode != null && part.exitCode !== 0 && (
          <span className="shrink-0 font-mono text-xs text-danger">{t("chat.exit", { code: part.exitCode })}</span>
        )}
        {hasBody && <ChevronRight size={14} className={cn("shrink-0 text-mute transition-transform", open && "rotate-90")} />}
      </button>
      {open && (
        <div className="space-y-2 border-t border-line px-3 py-2">
          {part.input && part.input !== title && <Pre label={t("chat.input")} text={part.input} />}
          {part.diff && <DiffText text={part.diff} />}
          {part.output && <Pre label={t("chat.output")} text={part.output} />}
        </div>
      )}
    </div>
  );
}

function Pre({ label, text }: { label: string; text: string }) {
  return (
    <div>
      <div className="mb-1 text-xs text-mute">{label}</div>
      <pre className="max-h-72 overflow-auto whitespace-pre-wrap break-words font-mono text-xs leading-5 text-ink">{text}</pre>
    </div>
  );
}

function DiffText({ text }: { text: string }) {
  return (
    <pre className="max-h-80 overflow-auto font-mono text-xs leading-5">
      {text.split("\n").map((line, i) => (
        <div
          key={i}
          className={cn(
            "whitespace-pre",
            line.startsWith("+") && !line.startsWith("+++") && "bg-paper/10 text-paper",
            line.startsWith("-") && !line.startsWith("---") && "text-mute line-through decoration-mute/40",
            line.startsWith("@@") && "text-mute",
          )}
        >
          {line || " "}
        </div>
      ))}
    </pre>
  );
}

function DiffBlock({ files }: { files: { path: string; change: string; diff: string }[] }) {
  const [open, setOpen] = useState<string | null>(null);
  return (
    <div className="divide-y divide-line rounded-xl border border-line bg-surface/60">
      {files.map((f) => (
        <div key={f.path}>
          <button
            className="flex w-full items-center gap-2 px-3 py-2 text-left"
            onClick={() => setOpen(open === f.path ? null : f.path)}
            aria-expanded={open === f.path}
          >
            <FileDiff size={14} className="shrink-0 text-mute" />
            <span className="min-w-0 flex-1 truncate font-mono text-xs">{f.path}</span>
            <span className="shrink-0 text-xs text-mute">{f.change}</span>
          </button>
          {open === f.path && f.diff && (
            <div className="border-t border-line px-3 py-2">
              <DiffText text={f.diff} />
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

function Notice({ part }: { part: Extract<Part, { kind: "notice" }> }) {
  const t = useT();
  const text =
    part.notice === "files"
      ? t("chat.filesChanged", { files: part.text })
      : part.notice === "retry"
        ? `${t("chat.retrying")}${part.text ? ` · ${part.text}` : ""}`
        : part.notice === "compacted"
          ? t("chat.compacted")
          : part.text;
  return <div className="truncate text-xs text-mute">{text}</div>;
}

function Usage({ part }: { part: Extract<Part, { kind: "usage" }> }) {
  const t = useT();
  if (!part.input && !part.output) return null;
  return (
    <div className="text-right font-mono text-[11px] text-mute/70">
      {t("chat.tokens", { input: compactNumber(part.input), output: compactNumber(part.output) })}
    </div>
  );
}
