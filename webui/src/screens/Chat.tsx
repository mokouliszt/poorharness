import { Fragment, useLayoutEffect, useRef, useState } from "react";
import { CircleAlert, Copy, FolderOpen, ListChecks, Settings as Gear, Trash2 } from "lucide-react";
import { abort, deleteSession, pop, push, selectedEngine, send, toast, useStore } from "@/lib/store";
import { call } from "@/lib/bridge";
import { describeError, resolveLang, useT, type TKey } from "@/lib/i18n";
import { laterLabel } from "@/lib/format";
import { cn } from "@/lib/cn";
import type { Message, TodoItem } from "@/lib/types";
import { TopBar } from "@/components/TopBar";
import { Composer } from "@/components/Composer";
import { ModelSheet } from "@/components/ModelSheet";
import { ApprovalSheet } from "@/components/ApprovalSheet";
import { QuestionSheet } from "@/components/QuestionSheet";
import { PartView } from "@/components/PartView";
import { AttachmentChip } from "@/components/AttachmentChip";
import { Sheet } from "@/components/ui/Sheet";
import { Button } from "@/components/ui/Button";
import { Mark } from "@/components/Mark";
import { OptionsButton, OptionsSheet } from "@/components/OptionsSheet";

const EMPTY: Message[] = [];

const STAGES: Record<string, TKey> = {
  sandbox: "stage.sandbox",
  upload: "stage.upload",
  outputs: "stage.outputs",
  repos: "stage.repos",
};

export function Chat({ sessionId }: { sessionId: string }) {
  const t = useT();
  const session = useStore((s) => s.sessions.find((x) => x.id === sessionId));
  const messages = useStore((s) => s.messages[sessionId] ?? EMPTY);
  const busy = useStore((s) => !!s.busy[sessionId]);
  const error = useStore((s) => s.errors[sessionId]);
  const todos = useStore((s) => s.todos[sessionId]);
  const detail = useStore((s) => s.statusDetail[sessionId]);
  const retryAt = useStore((s) => s.retryAt[sessionId]);
  const lang = useStore((s) => resolveLang(s.settings?.language));
  const engine = useStore(selectedEngine);
  const backend = useStore((s) => (engine ? s.runtime?.backends[engine] : undefined));
  const starting = backend?.phase === "starting";
  const stage = useStore((s) => s.stage[sessionId]);
  const progress = useStore((s) => s.stageProgress[sessionId]);
  const background = useStore((s) => s.background[sessionId] ?? 0);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const scroller = useRef<HTMLDivElement>(null);
  const stick = useRef(true);


  // Follow new output while the reader is at the bottom; leave them alone if they scrolled up.
  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [messages, busy]);

  const last = messages[messages.length - 1];
  const waiting = busy && (!last || last.role === "user" || last.parts.length === 0);

  return (
    <div className="flex h-full flex-col">
      <TopBar
        title={session?.title || "…"}
        right={
          <>
            <button
              onClick={() => push({ name: "files", sessionId })}
              aria-label={t("chat.files")}
              className="flex h-10 w-10 items-center justify-center rounded-full text-mute active:bg-surface"
            >
              <FolderOpen size={19} />
            </button>
            <OptionsButton />
            <button
              onClick={() => push({ name: "settings" })}
              aria-label={t("settings.title")}
              className="flex h-10 w-10 items-center justify-center rounded-full text-mute active:bg-surface"
            >
              <Gear size={19} />
            </button>
            <button
              onClick={() => setConfirmDelete(true)}
              aria-label={t("chat.delete")}
              className="flex h-10 w-10 items-center justify-center rounded-full text-mute active:bg-surface"
            >
              <Trash2 size={18} />
            </button>
          </>
        }
      />

      <div
        ref={scroller}
        onScroll={(e) => {
          const el = e.currentTarget;
          stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
        }}
        className="min-h-0 flex-1 overflow-y-auto px-4 pb-4"
      >
        <div className="mx-auto max-w-2xl space-y-5 pt-2">
          {messages.map((m, i) => {
            const prev = messages[i - 1];
            // Where the chat moved to another engine (or model of record), say so.
            const switched = !!(m.segment && prev?.segment && m.segment !== prev.segment);
            return (
              <Fragment key={m.id}>
                {switched && <SegmentDivider model={m.segmentModel} />}
                <MessageView m={m} live={busy && i === messages.length - 1} sessionId={sessionId} />
              </Fragment>
            );
          })}
          {(waiting || (busy && (starting || !!stage))) && (
            <div className="flex items-center gap-3 text-sm text-mute">
              <Mark size={22} busy />
              <span className="min-w-0 break-words">
                {stage && STAGES[stage]
                  ? t(STAGES[stage]) + (stage === "upload" && progress != null ? ` ${Math.round(progress * 100)}%` : "")
                  : starting
                    ? backend?.detail === "plugin"
                      ? t("chat.pluginSetup")
                      : t("chat.engineStarting", { engine: "opencode" })
                    : t("chat.working")}
                {!starting && !stage && detail ? ` · ${detail}` : ""}
                {/* A long wait (such as a daily limit) says when it ends; short ones are not worth a time. */}
                {!starting && !stage && retryAt && retryAt - Date.now() > 60_000 ? ` · ${t("chat.retryAt", { time: laterLabel(retryAt, lang) })}` : ""}
              </span>
            </div>
          )}
          {!busy && background > 0 && (
            <div className="flex items-center gap-3 text-sm text-mute">
              <Mark size={22} busy />
              <span>{t("chat.background", { n: background })}</span>
            </div>
          )}
          {error && (
            <div className="flex gap-2 rounded-xl border border-danger/40 px-3 py-2 text-sm text-danger">
              <CircleAlert size={16} className="mt-0.5 shrink-0" />
              <span className="break-words">{describeError(t, error)}</span>
            </div>
          )}
        </div>
      </div>

      {todos && todos.length > 0 && <Plan items={todos} />}

      <Composer
        busy={busy}
        draftKey={sessionId}
        attachKey={sessionId}
        sessionId={sessionId}
        onStop={() => void abort(sessionId)}
        onSend={async (text, files) => {
          stick.current = true;
          try {
            await send(sessionId, text, files);
            return true;
          } catch (e) {
            toast(t("error.send", { message: describeError(t, (e as Error).message) }), "error");
            return false;
          }
        }}
      />
      <ModelSheet />
      <OptionsSheet />
      <ModelSheet target="subagent" />
      <ModelSheet target="title" />
      <ApprovalSheet sessionId={sessionId} />
      <QuestionSheet sessionId={sessionId} />

      <Sheet open={confirmDelete} onClose={() => setConfirmDelete(false)} title={t("chat.deleteConfirm")}>
        <div className="grid gap-2 px-5 pb-2">
          <Button
            variant="danger"
            onClick={async () => {
              setConfirmDelete(false);
              try {
                await deleteSession(sessionId);
                pop();
              } catch (e) {
                toast(describeError(t, (e as Error).message), "error");
              }
            }}
          >
            {t("common.delete")}
          </Button>
          <Button variant="ghost" onClick={() => setConfirmDelete(false)}>
            {t("common.cancel")}
          </Button>
        </div>
      </Sheet>
    </div>
  );
}

/** Where a chat went on in a new session (a fresh sandbox, or a chat from Codex before 0.11). */
function SegmentDivider({ model }: { model?: string | null }) {
  const label = model ?? "";
  return (
    <div className="flex items-center gap-3 py-1 text-xs text-mute" role="separator">
      <span className="h-px flex-1 bg-line" />
      <span>{label}</span>
      <span className="h-px flex-1 bg-line" />
    </div>
  );
}

function MessageView({ m, live, sessionId }: { m: Message; live: boolean; sessionId: string }) {
  const t = useT();
  if (m.role === "user") {
    const text = m.parts.map((p) => (p.kind === "text" ? p.text : "")).join("\n").trim();
    const files = m.parts.flatMap((p) => (p.kind === "attachments" ? p.items : []));
    return (
      <div className={cn("flex flex-col items-end gap-1.5", m.pending && "opacity-70")}>
        {files.length > 0 && (
          <div className="flex max-w-[85%] flex-wrap justify-end gap-1.5">
            {files.map((f, i) => (
              <AttachmentChip
                key={`${f.path || f.name}-${i}`}
                name={f.name}
                size={f.size}
                mime={f.mime}
                onOpen={f.path ? () => push({ name: "files", sessionId, path: f.path.slice(0, f.path.lastIndexOf("/")) }) : undefined}
              />
            ))}
          </div>
        )}
        {text && (
          <div className="max-w-[85%] whitespace-pre-wrap break-words rounded-slip rounded-br-md bg-paper px-4 py-2.5 text-base text-void">
            {text}
          </div>
        )}
      </div>
    );
  }
  const text = m.parts.filter((p) => p.kind === "text").map((p) => (p as { text: string }).text).join("\n\n");
  return (
    <div className="space-y-3">
      {m.parts.map((p) => (
        <PartView key={p.id} part={p} live={live} />
      ))}
      {m.error && (
        <div className="flex gap-2 text-sm text-danger">
          <CircleAlert size={16} className="mt-0.5 shrink-0" />
          <span className="break-words">{describeError(t, m.error)}</span>
        </div>
      )}
      {!live && text && (
        <button
          className="flex items-center gap-1.5 text-xs text-mute active:text-ink"
          onClick={() => {
            void call("app.copy", { text });
            toast(t("chat.copied"));
          }}
        >
          <Copy size={13} />
          {t("common.copy")}
        </button>
      )}
    </div>
  );
}

function Plan({ items }: { items: TodoItem[] }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const done = items.filter((i) => i.status === "completed").length;
  const current = items.find((i) => i.status === "in_progress") ?? items.find((i) => i.status === "pending");
  return (
    <div className="mx-3 rounded-xl border border-line bg-surface">
      <button className="flex w-full items-center gap-2 px-3 py-2 text-left" onClick={() => setOpen(!open)} aria-expanded={open}>
        <ListChecks size={15} className="shrink-0 text-mute" />
        <span className="shrink-0 text-xs text-mute">
          {t("chat.plan")} {done}/{items.length}
        </span>
        {!open && current && <span className="min-w-0 flex-1 truncate text-sm text-ink">{current.text}</span>}
      </button>
      {open && (
        <ol className="max-h-48 space-y-1.5 overflow-y-auto px-3 pb-3">
          {items.map((i, n) => (
            <li key={n} className="flex gap-2 text-sm">
              <span
                className={cn(
                  "mt-1.5 h-2 w-2 shrink-0 rounded-full border",
                  i.status === "completed" && "border-paper bg-paper",
                  i.status === "in_progress" && "animate-pulse border-paper",
                  i.status !== "completed" && i.status !== "in_progress" && "border-mute",
                )}
              />
              <span className={cn(i.status === "completed" ? "text-mute line-through" : "text-ink")}>{i.text}</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
