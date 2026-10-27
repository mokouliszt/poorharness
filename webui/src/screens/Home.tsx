import { useState } from "react";
import { Settings as Gear, Trash2 } from "lucide-react";
import { call } from "@/lib/bridge";
import { deleteSession, HOME_DRAFT, openSession, push, startChat, toast, useStore } from "@/lib/store";
import { describeError, resolveLang, useT, type TKey } from "@/lib/i18n";
import { dayBucket, timeLabel, type DayBucket } from "@/lib/format";
import { Composer } from "@/components/Composer";
import { ModelSheet } from "@/components/ModelSheet";
import { Mark } from "@/components/Mark";
import { Sheet } from "@/components/ui/Sheet";
import { Button } from "@/components/ui/Button";
import { OptionsButton, OptionsSheet } from "@/components/OptionsSheet";
import type { BackendStatus, Engine, SessionInfo } from "@/lib/types";

const BUCKETS: { key: DayBucket; label: TKey }[] = [
  { key: "today", label: "home.today" },
  { key: "yesterday", label: "home.yesterday" },
  { key: "earlier", label: "home.earlier" },
];


export function Home() {
  const t = useT();
  const sessions = useStore((s) => s.sessions);
  const busy = useStore((s) => s.busy);
  const backends = useStore((s) => s.runtime?.backends);
  const lang = useStore((s) => resolveLang(s.settings?.language));
  const anyBusy = Object.values(busy).some(Boolean);
  const [pendingDelete, setPendingDelete] = useState<SessionInfo | null>(null);
  // Engines start on demand; only a failed start is worth a banner (a missing key has its own).
  const down = (Object.entries(backends ?? {}) as [Engine, BackendStatus][]).filter(
    ([, b]) => b.phase === "failed" && b.detail !== "tensorlake_key_missing",
  );

  return (
    <div className="flex h-full flex-col">
      <header className="flex h-14 shrink-0 items-center gap-3 px-4">
        <Mark size={30} busy={anyBusy} />
        <span className="flex-1 text-base font-semibold tracking-tight text-paper">PoorHarness</span>
        <OptionsButton />
        <button
          onClick={() => push({ name: "settings" })}
          aria-label={t("settings.title")}
          className="flex h-10 w-10 items-center justify-center rounded-full active:bg-surface"
        >
          <Gear size={20} />
        </button>
      </header>

      {down.map(([engine, b]) => (
        <div key={engine} className="mx-4 mb-2 flex items-center justify-between gap-3 rounded-xl border border-line px-3 py-2 text-sm">
          <span className="min-w-0 text-mute">
            {t("home.engineDown", { engine })}
            {b.detail === "tensorlake_plugin_unavailable" ? ` · ${describeError(t, b.detail)}` : ""}
          </span>
          <button
            className="shrink-0 font-medium text-paper"
            onClick={() => void call("engines.start", { target: engine }).catch((e: Error) => toast(describeError(t, e.message), "error"))}
          >
            {t("home.restart")}
          </button>
        </div>
      ))}

      <main className="scroll-quiet min-h-0 flex-1 overflow-y-auto px-2">
        {sessions.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-4 pb-16 opacity-80">
            <Mark size={64} />
            <p className="text-sm text-mute">{t("home.empty")}</p>
          </div>
        ) : (
          BUCKETS.map(({ key, label }) => {
            const items = sessions.filter((s) => dayBucket(s.updatedAt) === key);
            if (!items.length) return null;
            return (
              <section key={key} className="pb-2">
                <h2 className="px-3 pb-1 pt-4 text-xs font-medium uppercase tracking-wider text-mute">{t(label)}</h2>
                {items.map((s) => (
                  <SessionRow key={s.id} s={s} busy={!!busy[s.id]} lang={lang} onDelete={() => setPendingDelete(s)} />
                ))}
              </section>
            );
          })
        )}
      </main>

      <Composer
        attachKey={HOME_DRAFT}
        onSend={async (text, files) => {
          try {
            await startChat(text, files);
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

      <Sheet open={!!pendingDelete} onClose={() => setPendingDelete(null)} title={t("chat.deleteConfirm")}>
        <div className="grid gap-2 px-5 pb-2">
          {pendingDelete?.title && <p className="truncate pb-1 text-sm text-mute">{pendingDelete.title}</p>}
          <Button
            variant="danger"
            onClick={async () => {
              const target = pendingDelete;
              setPendingDelete(null);
              if (!target) return;
              try {
                await deleteSession(target.id);
              } catch (e) {
                toast(describeError(t, (e as Error).message), "error");
              }
            }}
          >
            {t("common.delete")}
          </Button>
          <Button variant="ghost" onClick={() => setPendingDelete(null)}>
            {t("common.cancel")}
          </Button>
        </div>
      </Sheet>
    </div>
  );
}

function SessionRow(props: { s: SessionInfo; busy: boolean; lang: "en" | "ja"; onDelete: () => void }) {
  const { s, busy, lang } = props;
  const t = useT();
  return (
    <div className="flex items-center rounded-xl active:bg-surface">
      <button onClick={() => void openSession(s.id)} className="flex min-w-0 flex-1 items-center gap-3 py-3 pl-3 text-left">
        <div className="min-w-0 flex-1">
          <div className="truncate text-base text-paper">{s.title || "…"}</div>
          <div className="mt-0.5 flex items-center gap-2 text-xs text-mute">
            <span className="shrink-0">{timeLabel(s.updatedAt, lang)}</span>
          </div>
        </div>
        {busy && <span className="h-2 w-2 shrink-0 animate-pulse rounded-full bg-paper" />}
      </button>
      <button
        onClick={props.onDelete}
        aria-label={`${t("chat.delete")}: ${s.title}`}
        className="ml-1 mr-1 flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-mute active:bg-raised active:text-paper"
      >
        <Trash2 size={18} />
      </button>
    </div>
  );
}
