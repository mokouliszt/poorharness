import { useCallback, useEffect, useRef, useState } from "react";
import { Copy, Download, LoaderCircle, Trash2 } from "lucide-react";
import { call } from "@/lib/bridge";
import { onBackClose, toast, useStore } from "@/lib/store";
import { describeError, resolveLang, useT } from "@/lib/i18n";
import { fileSize, timeLabel } from "@/lib/format";
import type { ExportResult, RepoItem } from "@/lib/types";
import { TopBar } from "@/components/TopBar";
import { Sheet } from "@/components/ui/Sheet";
import { Button } from "@/components/ui/Button";

type Confirm = { kind: "one"; repo: RepoItem } | { kind: "all" };

/**
 * The git repositories agents keep in the bucket (docs/adr/git-repos.md): their
 * address for git-remote-s3, the default branch's bundle to save, and deleting
 * one or all of them.
 */
export function Repos() {
  const t = useT();
  const lang = useStore((s) => resolveLang(s.settings?.language));
  const [items, setItems] = useState<RepoItem[] | null>(null);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  const [working, setWorking] = useState(false);
  const [saving, setSaving] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const res = await call<{ items: RepoItem[]; total: number }>("git.list");
      setItems(res.items);
      setTotal(res.total);
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => (confirm && !working ? onBackClose(() => setConfirm(null)) : undefined), [confirm, working]);
  const shown = useRef<Confirm | null>(null);
  if (confirm) shown.current = confirm;

  async function save(repo: RepoItem) {
    setSaving(repo.name);
    try {
      const res = await call<ExportResult>("git.export", { name: repo.name, action: "save" });
      if (res.where === "downloads") toast(t("files.saved"));
    } catch (e) {
      toast(describeError(t, (e as Error).message), "error");
    } finally {
      setSaving(null);
    }
  }

  async function remove() {
    if (!confirm) return;
    setWorking(true);
    try {
      if (confirm.kind === "all") await call("git.deleteAll");
      else await call("git.delete", { name: confirm.repo.name });
      setConfirm(null);
      await load();
    } catch (e) {
      toast(describeError(t, (e as Error).message), "error");
    } finally {
      setWorking(false);
    }
  }

  const iconButton = "flex h-12 w-11 shrink-0 items-center justify-center rounded-full text-mute active:bg-surface disabled:opacity-40";
  return (
    <div className="flex h-full flex-col">
      <TopBar title={t("repos.title")} />
      <div className="scroll-quiet min-h-0 flex-1 overflow-y-auto px-4 pb-10">
        {error ? (
          <p className="px-1 pt-6 text-sm text-mute">{describeError(t, error)}</p>
        ) : items === null ? (
          <p className="flex items-center gap-2 px-1 pt-6 text-sm text-mute">
            <LoaderCircle size={16} className="animate-spin" />
          </p>
        ) : items.length === 0 ? (
          <p className="px-1 pt-6 text-sm text-mute">{t("repos.empty")}</p>
        ) : (
          <>
            <div className="flex items-center gap-3 px-1 pt-4">
              <span className="flex-1 text-sm text-mute">{t("repos.total", { count: String(items.length), size: fileSize(total) })}</span>
              <Button size="sm" variant="outline" onClick={() => setConfirm({ kind: "all" })}>
                {t("outputs.deleteAll")}
              </Button>
            </div>
            <div className="mt-4 divide-y divide-line overflow-hidden rounded-slip border border-line">
              {items.map((r) => (
                <div key={r.name} className="flex min-h-[64px] items-center pr-1">
                  <div className="min-w-0 flex-1 py-2 pl-4">
                    <div className="truncate font-mono text-sm text-paper">{r.name}</div>
                    <div className="truncate text-xs text-mute">
                      {[r.branches.length ? r.branches.join(", ") : null, r.size ? fileSize(r.size) : null, r.modifiedAt ? timeLabel(r.modifiedAt, lang) : null]
                        .filter(Boolean)
                        .join(" · ")}
                    </div>
                  </div>
                  <button
                    className={iconButton}
                    onClick={() => void call("app.copy", { text: r.url }).then(() => toast(t("repos.copied")))}
                    aria-label={t("repos.copyUrl", { name: r.name })}
                  >
                    <Copy size={16} />
                  </button>
                  <button
                    className={iconButton}
                    disabled={!r.branches.length || saving !== null}
                    onClick={() => void save(r)}
                    aria-label={t("repos.save", { name: r.name })}
                  >
                    {saving === r.name ? <LoaderCircle size={16} className="animate-spin" /> : <Download size={16} />}
                  </button>
                  <button className={iconButton} onClick={() => setConfirm({ kind: "one", repo: r })} aria-label={t("outputs.delete", { name: r.name })}>
                    <Trash2 size={17} />
                  </button>
                </div>
              ))}
            </div>
          </>
        )}
      </div>
      <Sheet
        open={!!confirm}
        onClose={() => !working && setConfirm(null)}
        title={
          shown.current?.kind === "all"
            ? t("repos.deleteAllTitle", { count: String(items?.length ?? 0) })
            : t("outputs.deleteTitle", { name: shown.current?.kind === "one" ? shown.current.repo.name : "" })
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
