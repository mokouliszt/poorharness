import { useState } from "react";
import { call } from "@/lib/bridge";
import { push, useStore } from "@/lib/store";
import { useT } from "@/lib/i18n";
import { Mark } from "@/components/Mark";
import { Button } from "@/components/ui/Button";

/** First-run unpacking, or a runtime that could not be prepared. */
export function Setup() {
  const t = useT();
  const runtime = useStore((s) => s.runtime);
  const [retrying, setRetrying] = useState(false);
  const phase = runtime?.phase ?? "idle";
  const failed = phase === "failed";
  const pct = Math.round((runtime?.progress ?? 0) * 100);

  return (
    <div className="flex h-full flex-col items-center justify-center px-8 text-center">
      <Mark size={96} busy={!failed} />
      <h1 className="mt-8 text-xl font-semibold text-paper">{failed ? t("setup.failed") : t("setup.title")}</h1>

      {phase === "installing" && (
        <div className="mt-6 w-full max-w-xs">
          <div className="flex justify-between text-sm text-mute">
            <span>{t("setup.unpack")}</span>
            <span className="font-mono">{pct}%</span>
          </div>
          <div className="mt-2 h-1 overflow-hidden rounded-full bg-raised">
            <div className="h-full rounded-full bg-paper transition-[width] duration-300" style={{ width: `${pct}%` }} />
          </div>
          <p className="mt-6 text-sm text-mute">{t("setup.first")}</p>
        </div>
      )}

      {failed && (
        <>
          <p className="mt-3 max-w-sm break-words text-sm text-mute">{runtime?.error}</p>
          <div className="mt-8 flex gap-3">
            <Button
              variant="primary"
              disabled={retrying}
              onClick={async () => {
                setRetrying(true);
                try {
                  await call("runtime.start");
                } finally {
                  setRetrying(false);
                }
              }}
            >
              {t("setup.retry")}
            </Button>
            <Button onClick={() => push({ name: "diagnostics" })}>{t("setup.log")}</Button>
          </div>
        </>
      )}
    </div>
  );
}
