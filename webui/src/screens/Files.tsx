import { useCallback, useEffect, useState, type ReactNode } from "react";
import { ChevronRight, File as FileIcon, Folder, FolderArchive, LoaderCircle, RefreshCw } from "lucide-react";
import { call } from "@/lib/bridge";
import { push, toast, useStore } from "@/lib/store";
import { describeError, resolveLang, useT } from "@/lib/i18n";
import { fileSize, timeLabel } from "@/lib/format";
import type { ExportResult, FileEntry, FileListing } from "@/lib/types";
import { TopBar } from "@/components/TopBar";
import { Sheet } from "@/components/ui/Sheet";
import { Button } from "@/components/ui/Button";

type Target = { kind: "file"; path: string; entry: FileEntry } | { kind: "zip" };

/**
 * A folder of the chat's sandbox. Work stays in the sandbox; this is where it is
 * taken out, one file or everything as a zip, to Downloads or the share sheet.
 * Each subfolder is its own screen, so back goes up a level.
 */
export function Files({ sessionId, path }: { sessionId: string; path?: string }) {
  const t = useT();
  const root = useStore((s) => s.info?.workspace ?? "/tmp/workspace");
  const lang = useStore((s) => resolveLang(s.settings?.language));
  // A chat that has run had a sandbox; one that never ran has nothing yet.
  const ran = useStore((s) => (s.sessions.find((x) => x.id === sessionId)?.engines.length ?? 0) > 0);
  // Cloud sandboxes may expire after 7 days; a device jail keeps its files until the chat goes.
  const onDevice = useStore((s) => s.sessions.find((x) => x.id === sessionId)?.place === "phone");
  const keepNote = onDevice ? t("files.onDevice") : t("files.retention");
  const dir = path ?? root;
  const [listing, setListing] = useState<FileListing | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [target, setTarget] = useState<Target | null>(null);
  const [working, setWorking] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setListing(await call<FileListing>("files.list", { sessionId, path: dir }));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [sessionId, dir]);

  useEffect(() => {
    void load();
  }, [load]);

  async function deliver(action: "save" | "share") {
    if (!target) return;
    setWorking(true);
    try {
      const res =
        target.kind === "zip"
          ? await call<ExportResult>("files.zip", { sessionId, action })
          : await call<ExportResult>("files.export", { sessionId, path: target.path, action });
      if (res.where === "downloads") toast(t("files.saved"));
      setTarget(null);
    } catch (e) {
      toast(describeError(t, (e as Error).message), "error");
    } finally {
      setWorking(false);
    }
  }

  const entries = listing?.entries ?? [];
  const crumbs = dir === root ? [] : dir.slice(root.length + 1).split("/");
  let body: ReactNode;
  if (loading && !listing) {
    body = (
      <Center>
        <LoaderCircle size={18} className="animate-spin" />
        {t("files.opening")}
      </Center>
    );
  } else if (error) {
    body = (
      <Center>
        <p className="max-w-xs text-center">{describeError(t, error)}</p>
        <Button size="sm" onClick={() => void load()}>
          {t("home.restart")}
        </Button>
      </Center>
    );
  } else if (listing?.missing) {
    body = ran && !onDevice ? (
      <Center>
        <p>{t("files.gone")}</p>
        <p className="text-xs">{keepNote}</p>
      </Center>
    ) : (
      <Center>{t("files.empty")}</Center>
    );
  } else if (entries.length === 0) {
    body = <Center>{t("files.empty")}</Center>;
  } else {
    const rows = entries.map((e) => {
      const full = `${dir}/${e.name}`;
      return (
        <button
          key={e.name}
          onClick={() => (e.dir ? push({ name: "files", sessionId, path: full }) : setTarget({ kind: "file", path: full, entry: e }))}
          className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left active:bg-surface"
        >
          {e.dir ? <Folder size={18} className="shrink-0 text-ink" /> : <FileIcon size={18} className="shrink-0 text-mute" />}
          <div className="min-w-0 flex-1">
            <div className="truncate text-base text-paper">{e.name}</div>
            {!e.dir && (
              <div className="truncate text-xs text-mute">
                {[fileSize(e.size), timeLabel(e.modifiedAt, lang)].filter(Boolean).join(" · ")}
              </div>
            )}
          </div>
          {e.dir && <ChevronRight size={16} className="shrink-0 text-mute" />}
        </button>
      );
    });
    body = (
      <>
        {rows}
        <p className="phrase px-3 pb-4 pt-3 text-xs text-mute">{keepNote}</p>
      </>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <TopBar
        title={t("files.title")}
        right={
          // Nothing to refresh or pack once the sandbox is gone.
          listing?.missing ? undefined : (
            <>
              <IconButton label={t("files.refresh")} disabled={loading} onClick={() => void load()}>
                <RefreshCw size={18} className={loading && listing ? "animate-spin" : undefined} />
              </IconButton>
              <IconButton label={t("files.all")} disabled={dir === root && entries.length === 0} onClick={() => setTarget({ kind: "zip" })}>
                <FolderArchive size={19} />
              </IconButton>
            </>
          )
        }
      />
      <nav className="scroll-quiet flex items-center gap-1 overflow-x-auto px-4 pb-2 font-mono text-xs text-mute" aria-label={t("files.title")}>
        <span className={crumbs.length ? undefined : "text-ink"}>{t("files.root")}</span>
        {crumbs.map((c, i) => (
          <span key={i} className="flex shrink-0 items-center gap-1">
            <ChevronRight size={12} />
            <span className={i === crumbs.length - 1 ? "text-ink" : undefined}>{c}</span>
          </span>
        ))}
      </nav>
      <main className="scroll-quiet min-h-0 flex-1 overflow-y-auto px-2">{body}</main>

      <Sheet
        open={!!target}
        onClose={() => !working && setTarget(null)}
        title={target?.kind === "file" ? target.entry.name : t("files.all")}
      >
        <div className="grid gap-2 px-5 pb-2">
          {target?.kind === "file" && (
            <p className="pb-1 text-sm text-mute">
              {[fileSize(target.entry.size), timeLabel(target.entry.modifiedAt, lang)].filter(Boolean).join(" · ")}
            </p>
          )}
          {working ? (
            // The host cannot be interrupted mid-copy; the sheet closes when it is done.
            <p className="flex items-center gap-2 py-3 text-sm text-mute">
              <LoaderCircle size={14} className="animate-spin" />
              {target?.kind === "zip" ? t("files.packing") : t("files.opening")}
            </p>
          ) : (
            <>
              <Button variant="primary" onClick={() => void deliver("save")}>
                {t("files.save")}
              </Button>
              <Button onClick={() => void deliver("share")}>{t("files.share")}</Button>
              <Button variant="ghost" onClick={() => setTarget(null)}>
                {t("common.cancel")}
              </Button>
            </>
          )}
        </div>
      </Sheet>
    </div>
  );
}

function Center({ children }: { children: ReactNode }) {
  return <div className="phrase flex h-full flex-col items-center justify-center gap-3 px-6 pb-10 text-center text-sm text-mute">{children}</div>;
}

function IconButton(props: { label: string; disabled?: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      onClick={props.onClick}
      disabled={props.disabled}
      aria-label={props.label}
      className="flex h-10 w-10 items-center justify-center rounded-full text-mute active:bg-surface disabled:opacity-40"
    >
      {props.children}
    </button>
  );
}
