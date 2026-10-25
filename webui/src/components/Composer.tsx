import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { ArrowUp, ChevronDown, LoaderCircle, Paperclip, Square } from "lucide-react";
import { addAttachments, clearAttachments, removeAttachment, restoreAttachments, setModelSheet, takeDraft, toast, useStore } from "@/lib/store";
import { fileSize } from "@/lib/format";
import { describeError, useT } from "@/lib/i18n";
import { cn } from "@/lib/cn";
import type { AttachError, Attachment } from "@/lib/types";
import { AttachingChip, AttachmentChip } from "./AttachmentChip";

const NO_FILES: Attachment[] = [];

/**
 * Message box with the model chip and attached files. [onSend] returns false to
 * keep the text and files (for example when sending failed), true to clear them.
 * [draftKey] picks up text handed back by the store (a first message that could
 * not be sent). Files are kept per [attachKey]; [sessionId] tells the host which
 * chat they are for, so it can check they fit there.
 */
export function Composer(props: {
  busy?: boolean;
  disabled?: boolean;
  draftKey?: string;
  attachKey: string;
  sessionId?: string;
  onSend: (text: string, files: Attachment[]) => Promise<boolean>;
  onStop?: () => void;
}) {
  const t = useT();
  const files = useStore((s) => s.attachments[props.attachKey] ?? NO_FILES);
  const attaching = useStore((s) => !!s.attaching[props.attachKey]);
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const ref = useRef<HTMLTextAreaElement>(null);
  const model = useStore((s) => s.models.find((m) => m.id === s.model));
  const effort = useStore((s) => s.effort);
  // Before an engine has reported its models once, say so instead of "Pick a model".
  const loading = useStore((s) => !model && s.modelsPending.length > 0);
  const hasDraft = useStore((s) => props.draftKey !== undefined && s.drafts[props.draftKey] !== undefined);

  useEffect(() => {
    if (!hasDraft || props.draftKey === undefined) return;
    const draft = takeDraft(props.draftKey);
    if (draft !== undefined) setText((cur) => cur || draft);
  }, [hasDraft, props.draftKey]);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 180)}px`;
  }, [text]);

  const canSend = (text.trim().length > 0 || files.length > 0) && !attaching && !sending && !props.disabled && !!model;

  async function submit() {
    if (!canSend) return;
    const value = text.trim();
    // Cleared at once: sending can wait a while for an engine to start.
    setText("");
    setSending(true);
    // The files leave the box with the text; they come back if the send fails.
    const sent = files;
    clearAttachments(props.attachKey);
    try {
      if (!(await props.onSend(value, sent))) {
        setText((cur) => cur || value);
        restoreAttachments(props.attachKey, sent);
      }
    } finally {
      setSending(false);
    }
  }

  async function attach() {
    try {
      const refused = await addAttachments(props.attachKey, props.sessionId);
      for (const r of refused) toast(refusal(r), "error");
    } catch (e) {
      toast(describeError(t, (e as Error).message), "error");
    }
  }

  function refusal(r: AttachError): string {
    const name = r.name ?? t("attach.file");
    if (r.error === "attach_no_space" && r.needed != null && r.free != null) {
      return t(r.where === "device" ? "attach.noSpaceDevice" : "attach.noSpaceSandbox", {
        name,
        needed: fileSize(r.needed),
        free: fileSize(r.free),
      });
    }
    return t("attach.failed", { name, message: describeError(t, r.error) });
  }

  return (
    <div className="px-3 pb-3 pt-2">
      <div className="rounded-slip border border-line bg-surface focus-within:border-mute">
        {(files.length > 0 || attaching) && (
          <div className="scroll-quiet flex gap-1.5 overflow-x-auto px-3 pt-3">
            {files.map((f) => (
              <AttachmentChip
                key={f.id}
                name={f.name}
                size={f.size}
                mime={f.mime}
                className="max-w-[240px] shrink-0"
                onRemove={() => removeAttachment(props.attachKey, f.id)}
              />
            ))}
            {attaching && <AttachingChip />}
          </div>
        )}
        <textarea
          ref={ref}
          value={text}
          rows={1}
          onChange={(e) => setText(e.target.value)}
          placeholder={t("composer.message")}
          aria-label={t("composer.message")}
          className="block max-h-[180px] w-full resize-none bg-transparent px-4 pt-3 text-base text-paper placeholder:text-mute focus:outline-none"
        />
        <div className="flex items-center gap-1 px-2 pb-2 pt-1">
          <button
            onClick={() => void attach()}
            disabled={attaching}
            aria-label={t("attach.add")}
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-mute active:bg-raised disabled:opacity-50"
          >
            <Paperclip size={18} />
          </button>
          <button
            onClick={() => setModelSheet(true)}
            className="flex min-w-0 items-center gap-1 rounded-full px-2.5 py-1.5 text-sm text-mute active:bg-raised"
          >
            {loading && <LoaderCircle size={13} className="shrink-0 animate-spin" />}
            <span className="truncate text-ink">{model ? model.name : loading ? t("models.loadingShort") : t("chat.noModel")}</span>
            {effort && <span className="shrink-0 rounded-full border border-line px-1.5 font-mono text-[11px] leading-4">{effort}</span>}
            <ChevronDown size={14} className="shrink-0" />
          </button>
          <div className="flex-1" />
          {props.busy && props.onStop ? (
            <button
              onClick={props.onStop}
              aria-label={t("composer.stop")}
              className="flex h-9 w-9 items-center justify-center rounded-full border border-line text-paper active:bg-raised"
            >
              <Square size={13} fill="currentColor" />
            </button>
          ) : (
            <button
              onClick={submit}
              disabled={!canSend}
              aria-label={t("composer.send")}
              className={cn(
                "flex h-9 w-9 items-center justify-center rounded-full transition-colors",
                canSend ? "bg-paper text-void" : "bg-raised text-mute",
              )}
            >
              <ArrowUp size={18} strokeWidth={2.4} />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
