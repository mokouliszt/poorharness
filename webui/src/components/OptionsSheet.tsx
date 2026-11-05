import { useEffect, useState } from "react";
import { ChevronRight, SlidersHorizontal } from "lucide-react";
import {
  hasModalKey,
  loadModalUsage,
  setChatGpu,
  setOption,
  setOptionsSheet,
  setSubagentSheet,
  setTitleSheet,
  toast,
  useStore,
} from "@/lib/store";
import { describeError, useT, type TKey } from "@/lib/i18n";
import { usd } from "@/lib/format";
import { Sheet } from "./ui/Sheet";
import { Switch } from "./ui/Switch";
import { cn } from "@/lib/cn";

/**
 * opencode's switchable features. The host owns the list, their defaults and how
 * each one is applied (docs/adr/engine-options.md); this only shows them and which
 * are on. Under subagents: the model they run on (docs/adr/subagent-model.md). Last,
 * the model chat titles are written with (docs/adr/title-model.md). Opened in a chat
 * that works (or will start) in Modal, it first offers that chat a GPU.
 */
export function OptionsSheet({ sessionId }: { sessionId?: string }) {
  const t = useT();
  const open = useStore((s) => s.optionsSheet);
  const items = useStore((s) => s.options);
  const enabled = useStore((s) => s.engineOptions);
  const running = useStore((s) => {
    const phase = s.runtime?.backends.opencode?.phase;
    return phase === "ready" || phase === "starting";
  });
  const subagentModel = useStore((s) => {
    const id = s.settings?.subagentModel;
    if (!id) return null;
    const name = s.models.find((m) => m.id === id)?.name ?? id.slice(id.indexOf("/") + 1);
    const effort = s.settings?.subagentEffort;
    return effort ? `${name} · ${effort}` : name;
  });
  const titleModel = useStore((s) => {
    const id = s.settings?.titleModel;
    if (!id) return null;
    return s.models.find((m) => m.id === id)?.name ?? id.slice(id.indexOf("/") + 1);
  });
  const [saving, setSaving] = useState<string | null>(null);

  return (
    <Sheet open={open} onClose={() => setOptionsSheet(false)} title={t("options.title")}>
      <div className="scroll-quiet min-h-0 flex-1 overflow-y-auto px-2 pb-2">
        {sessionId && <ChatGpu sessionId={sessionId} open={open} />}
        {items.length === 0 ? (
          <p className="px-3 py-6 text-center text-sm text-mute">{t("options.none")}</p>
        ) : (
          items.map((o) => {
            const on = enabled.includes(o.id);
            const idle = !!o.requires && !enabled.includes(o.requires);
            return (
              <div key={o.id}>
                <label
                  className={cn(
                    "flex items-center gap-3 rounded-xl px-3 py-3",
                    idle ? "pointer-events-none opacity-50" : "cursor-pointer active:bg-raised",
                  )}
                >
                  <div className="min-w-0 flex-1">
                    <div className="text-base text-paper">{t(`opt.${o.id}` as TKey)}</div>
                    <div className="phrase text-xs text-mute">{t(`opt.${o.id}.desc` as TKey)}</div>
                  </div>
                  <Switch
                    checked={on && !idle}
                    disabled={saving === o.id || idle}
                    label={t(`opt.${o.id}` as TKey)}
                    onCheckedChange={async (v) => {
                      setSaving(o.id);
                      try {
                        await setOption(o.id, v);
                      } catch (e) {
                        toast(describeError(t, (e as Error).message), "error");
                      } finally {
                        setSaving(null);
                      }
                    }}
                  />
                </label>
                {o.id === "subagents" && on && (
                  <button
                    onClick={() => setSubagentSheet(true)}
                    className="flex w-full items-center gap-3 rounded-xl py-2.5 pl-6 pr-3 text-left active:bg-raised"
                  >
                    <div className="min-w-0 flex-1">
                      <div className="text-sm text-paper">{t("subagentModel.row")}</div>
                      <div className="truncate text-xs text-mute">{subagentModel ?? t("subagentModel.same")}</div>
                    </div>
                    <ChevronRight size={16} className="shrink-0 text-mute" />
                  </button>
                )}
              </div>
            );
          })
        )}
        {items.length > 0 && (
          <button
            onClick={() => setTitleSheet(true)}
            className="flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left active:bg-raised"
          >
            <div className="min-w-0 flex-1">
              <div className="text-base text-paper">{t("titleModel.title")}</div>
              <div className="truncate text-xs text-mute">{titleModel ?? t("subagentModel.same")}</div>
            </div>
            <ChevronRight size={16} className="shrink-0 text-mute" />
          </button>
        )}
        {items.length > 0 && running && <p className="px-3 pt-1 text-xs text-mute">{t("options.applyLater")}</p>}
      </div>
    </Sheet>
  );
}

/**
 * A GPU for this chat's Modal sandbox (docs/adr/modal-first.md). Shown while the
 * chat is in Modal, or has not started and a Modal token is set.
 */
function ChatGpu({ sessionId, open }: { sessionId: string; open: boolean }) {
  const t = useT();
  const session = useStore((s) => s.sessions.find((x) => x.id === sessionId));
  // In Modal, or not started yet with a token set (it will start there unless Modal is near its budget).
  const modal = useStore((s) => (session?.place ? session.place === "modal" : !session?.engines.length && hasModalKey(s)));
  const gpu = useStore((s) => s.settings?.modalGpu ?? "T4");
  const price = useStore((s) => s.modalUsage?.gpus.find((g) => g.id === gpu)?.usdPerHour);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    if (open && modal) void loadModalUsage();
  }, [open, modal]);
  if (!session || !modal) return null;
  // This chat's own setting, set apart from the engine-wide options below.
  return (
    <div className="mb-1 border-b border-line pb-1">
      <label className="flex cursor-pointer items-center gap-3 rounded-xl px-3 py-3 active:bg-raised">
        <div className="min-w-0 flex-1">
          <div className="text-base text-paper">{t("gpu.title", { gpu })}</div>
          <div className="phrase text-xs text-mute">
            {price != null ? t("gpu.desc", { price: usd(price) }) : t("gpu.descNoPrice")}
          </div>
        </div>
        <Switch
          checked={!!session.gpu}
          disabled={saving}
          label={t("gpu.title", { gpu })}
          onCheckedChange={async (v) => {
            setSaving(true);
            try {
              await setChatGpu(sessionId, v);
            } catch (e) {
              toast(describeError(t, (e as Error).message), "error");
            } finally {
              setSaving(false);
            }
          }}
        />
      </label>
    </div>
  );
}

/** The header button that opens [OptionsSheet]. */
export function OptionsButton() {
  const t = useT();
  return (
    <button
      onClick={() => setOptionsSheet(true)}
      aria-label={t("options.open")}
      className="flex h-10 w-10 items-center justify-center rounded-full text-mute active:bg-surface"
    >
      <SlidersHorizontal size={18} />
    </button>
  );
}
