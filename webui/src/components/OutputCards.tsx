import { useState } from "react";
import { Cloud, Download, FileText, LoaderCircle, Share2 } from "lucide-react";
import { call } from "@/lib/bridge";
import { toast } from "@/lib/store";
import { describeError, useT } from "@/lib/i18n";
import { fileSize } from "@/lib/format";
import { cn } from "@/lib/cn";
import type { ExportResult, OutputFile } from "@/lib/types";

/**
 * Files the agent handed over at the end of its turn (docs/adr/outputs.md). A tap
 * saves one to Download/PoorHarness; the share button offers it to another app.
 */
export function OutputCards({ files }: { files: OutputFile[] }) {
  return (
    <div className="grid gap-2">
      {files.map((f) => (
        <OutputCard key={`${f.shelf}:${f.name}`} file={f} />
      ))}
    </div>
  );
}

function OutputCard({ file }: { file: OutputFile }) {
  const t = useT();
  const [busy, setBusy] = useState<"save" | "share" | null>(null);
  const failed = !!file.error;

  async function take(action: "save" | "share") {
    setBusy(action);
    try {
      const res = await call<ExportResult>("outputs.export", { shelf: file.shelf, chatId: file.chatId, name: file.name, action });
      if (res.where === "downloads") toast(t("files.saved"));
    } catch (e) {
      toast(describeError(t, (e as Error).message), "error");
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className={cn("flex items-stretch rounded-slip border border-line bg-surface", failed && "opacity-60")}>
      <button
        className={cn(
          "flex min-h-[56px] min-w-0 flex-1 items-center gap-3 px-3 py-2 text-left active:bg-raised disabled:active:bg-transparent",
          failed ? "rounded-slip" : "rounded-l-slip",
        )}
        disabled={failed || busy !== null}
        onClick={() => void take("save")}
        aria-label={t("outputs.save", { name: file.name })}
      >
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-raised text-ink">
          {busy === "save" ? <LoaderCircle size={17} className="animate-spin" /> : <FileText size={17} />}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm text-paper">{file.name}</span>
          <span className="flex items-center gap-1.5 text-xs text-mute">
            {failed ? (
              describeError(t, file.error)
            ) : (
              <>
                {fileSize(file.size)}
                {file.shelf === "s3" && (
                  <>
                    <span aria-hidden>·</span>
                    <Cloud size={12} aria-hidden />
                    {t("outputs.inBucket")}
                  </>
                )}
              </>
            )}
          </span>
        </span>
        {!failed && <Download size={16} className="shrink-0 text-mute" aria-hidden />}
      </button>
      {!failed && (
        <button
          className="flex w-12 shrink-0 items-center justify-center self-stretch rounded-r-slip border-l border-line text-mute active:bg-raised"
          disabled={busy !== null}
          onClick={() => void take("share")}
          aria-label={t("outputs.share", { name: file.name })}
        >
          {busy === "share" ? <LoaderCircle size={16} className="animate-spin" /> : <Share2 size={16} />}
        </button>
      )}
    </div>
  );
}
