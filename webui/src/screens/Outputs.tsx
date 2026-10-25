import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { LoaderCircle, Trash2 } from "lucide-react";
import { call } from "@/lib/bridge";
import { onBackClose, toast, useStore } from "@/lib/store";
import { describeError, resolveLang, useT } from "@/lib/i18n";
import { fileSize, timeLabel } from "@/lib/format";
import type { ExportResult, OutputItem, Shelf } from "@/lib/types";
import { TopBar } from "@/components/TopBar";
import { Sheet } from "@/components/ui/Sheet";
import { Button } from "@/components/ui/Button";

type Confirm = { kind: "one"; item: OutputItem } | { kind: "all" };

/**
 * The files kept on one shelf (the phone or the bucket), by chat, to take out again
 * or delete one by one or all at once (docs/adr/outputs.md).
 */
export function Outputs({ shelf }: { shelf: Shelf }) {
  const t = useT();
  const lang = useStore((s) => resolveLang(s.settings?.language));
  const [items, setItems] = useState<OutputItem[] | null>(null);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  const [working, setWorking] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      const res = await call<{ items: OutputItem[]; total: number }>("outputs.list", { shelf });
      setItems(res.items);
      setTotal(res.total);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [shelf]);

  useEffect(() => {
    void load();
  }, [load]);
  // Back closes the confirmation, not the screen; kept while it slides away so its title stays.
  useEffect(() => (confirm && !working ? onBackClose(() => setConfirm(null)) : undefined), [confirm, working]);
  const shown = useRef<Confirm | null>(null);
  if (confirm) shown.current = confirm;

  const groups = useMemo(() => {
    const out = new Map<string, OutputItem[]>();
    for (const it of items ?? []) out.set(it.chatId, [...(out.get(it.chatId) ?? []), it]);
    return [...out.entries()];
  }, [items]);

  async function save(item: OutputItem) {
    try {
      const res = await call<ExportResult>("outputs.export", { shelf, chatId: item.chatId, name: item.name, action: "save" });
      if (res.where === "downloads") toast(t("files.saved"));
    } catch (e) {
      toast(describeError(t, (e as Error).message), "error");
    }
  }

  async function remove() {
    if (!confirm) return;
    setWorking(true);
    try {
      if (confirm.kind === "all") await call("outputs.deleteAll", { shelf });
      else await call("outputs.delete", { shelf, chatId: confirm.item.chatId, name: confirm.item.name });
      setConfirm(null);
      await load();
    } catch (e) {
      toast(describeError(t, (e as Error).message), "error");
    } finally {
      setWorking(false);
    }
  }

  const title = t("outputs.keptOn", { where: t(shelf === "s3" ? "outputs.s3" : "outputs.device") });
  return (
    <div className="flex h-full flex-col">
      <TopBar title={title} />
      <div className="scroll-quiet min-h-0 flex-1 overflow-y-auto px-4 pb-10">
        {error ? (
          <p className="px-1 pt-6 text-sm text-mute">{describeError(t, error)}</p>
        ) : items === null ? (
          <p className="flex items-center gap-2 px-1 pt-6 text-sm text-mute">
            <LoaderCircle size={16} className="animate-spin" />
          </p>
        ) : items.length === 0 ? (
          <p className="px-1 pt-6 text-sm text-mute">{t("outputs.empty")}</p>
        ) : (
          <>
            <div className="flex items-center gap-3 px-1 pt-4">
              <span className="flex-1 text-sm text-mute">{t("outputs.total", { count: String(items.length), size: fileSize(total) })}</span>
              <Button size="sm" variant="outline" onClick={() => setConfirm({ kind: "all" })}>
                {t("outputs.deleteAll")}
              </Button>
            </div>
            {groups.map(([chatId, list]) => (
              <section key={chatId} className="pt-5">
                <h2 className="truncate px-1 pb-2 text-xs font-medium text-mute">{list[0].chatTitle || t("outputs.deletedChat")}</h2>
                <div className="divide-y divide-line overflow-hidden rounded-slip border border-line">
                  {list.map((it) => (
                    <div key={it.name} className="flex min-h-[56px] items-center">
                      <button className="min-w-0 flex-1 px-4 py-2 text-left active:bg-surface" onClick={() => void save(it)} aria-label={t("outputs.save", { name: it.name })}>
                        <div className="truncate text-sm text-paper">{it.name}</div>
                        <div className="text-xs text-mute">
                          {fileSize(it.size)} · {timeLabel(it.modifiedAt, lang)}
                        </div>
                      </button>
                      <button
                        className="mr-1 flex h-12 w-12 shrink-0 items-center justify-center rounded-full text-mute active:bg-surface"
                        onClick={() => setConfirm({ kind: "one", item: it })}
                        aria-label={t("outputs.delete", { name: it.name })}
                      >
                        <Trash2 size={17} />
                      </button>
                    </div>
                  ))}
                </div>
              </section>
            ))}
          </>
        )}
      </div>
      <Sheet
        open={!!confirm}
        onClose={() => !working && setConfirm(null)}
        title={
          shown.current?.kind === "all"
            ? t("outputs.deleteAllTitle", { count: String(items?.length ?? 0) })
            : t("outputs.deleteTitle", { name: shown.current?.kind === "one" ? shown.current.item.name : "" })
        }
      >
        <div className="space-y-3 px-5 pb-2">
          <Button variant="danger" className="w-full" disabled={working} onClick={() => void remove()}>
            {working ? <LoaderCircle size={16} className="animate-spin" /> : t("common.delete")}
          </Button>
          <Button variant="ghost" className="w-full" disabled={working} onClick={() => setConfirm(null)}>
            {t("common.cancel")}
          </Button>
        </div>
      </Sheet>
    </div>
  );
}
