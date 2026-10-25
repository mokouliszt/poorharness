import { Sheet } from "./ui/Sheet";
import { Button } from "./ui/Button";
import { replyApproval, useStore } from "@/lib/store";
import { useT } from "@/lib/i18n";

/** Shows the oldest pending approval for [sessionId] (or any session when omitted). */
export function ApprovalSheet({ sessionId }: { sessionId?: string }) {
  const t = useT();
  const request = useStore((s) => s.approvals.find((a) => !sessionId || a.sessionId === sessionId));
  if (!request) return null;
  const title =
    request.kind === "bash"
      ? t("approval.bash")
      : request.kind === "edit"
        ? t("approval.edit")
        : request.kind === "tool"
          ? t("approval.tool")
          : t("approval.other");
  return (
    <Sheet open onClose={() => {}} dismissable={false} title={title}>
      <div className="space-y-3 px-5 pb-2">
        {request.title && request.title !== request.kind && (
          <pre className="max-h-56 overflow-auto whitespace-pre-wrap break-words rounded-xl bg-void p-3 font-mono text-xs text-paper">
            {request.title}
          </pre>
        )}
        {request.detail && <p className="whitespace-pre-wrap break-words text-sm text-mute">{request.detail}</p>}
        <div className="grid gap-2 pt-1">
          <Button variant="primary" onClick={() => void replyApproval(request.requestId, "once")}>
            {t("approval.once")}
          </Button>
          <Button onClick={() => void replyApproval(request.requestId, "session")}>{t("approval.session")}</Button>
          <Button variant="ghost" onClick={() => void replyApproval(request.requestId, "deny")}>
            {t("approval.deny")}
          </Button>
        </div>
      </div>
    </Sheet>
  );
}
