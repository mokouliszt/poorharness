import { File as FileIcon, FileText, Image as ImageIcon, LoaderCircle, X } from "lucide-react";
import { fileSize } from "@/lib/format";
import { useT } from "@/lib/i18n";
import { cn } from "@/lib/cn";

function Icon({ mime }: { mime: string }) {
  if (mime.startsWith("image/")) return <ImageIcon size={14} className="shrink-0 text-mute" />;
  if (mime.startsWith("text/") || mime === "application/pdf" || mime === "application/json") {
    return <FileText size={14} className="shrink-0 text-mute" />;
  }
  return <FileIcon size={14} className="shrink-0 text-mute" />;
}

/** One attached file: in the composer (removable) or with a sent message (opens it in Files). */
export function AttachmentChip(props: {
  name: string;
  size?: number;
  mime: string;
  onRemove?: () => void;
  onOpen?: () => void;
  className?: string;
}) {
  const t = useT();
  const cls = cn(
    "inline-flex max-w-full items-center gap-1.5 rounded-full border border-line bg-raised py-1 pl-2.5 text-xs text-ink",
    props.onRemove ? "pr-1" : "pr-2.5",
    props.onOpen && "active:bg-surface",
    props.className,
  );
  if (props.onOpen) {
    return (
      <button onClick={props.onOpen} aria-label={t("attach.open", { name: props.name })} className={cls}>
        <Icon mime={props.mime} />
        <span className="min-w-0 truncate">{props.name}</span>
        {props.size != null && <span className="shrink-0 text-mute">{fileSize(props.size)}</span>}
      </button>
    );
  }
  return (
    <span className={cls}>
      <Icon mime={props.mime} />
      <span className="min-w-0 truncate">{props.name}</span>
      {props.size != null && <span className="shrink-0 text-mute">{fileSize(props.size)}</span>}
      {props.onRemove && (
        <button
          onClick={props.onRemove}
          aria-label={t("attach.remove", { name: props.name })}
          className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-mute active:bg-surface"
        >
          <X size={13} />
        </button>
      )}
    </span>
  );
}

/** Shown while picked files are still being copied in. */
export function AttachingChip() {
  const t = useT();
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-line bg-raised px-2.5 py-1 text-xs text-mute">
      <LoaderCircle size={13} className="animate-spin" />
      {t("attach.adding")}
    </span>
  );
}
