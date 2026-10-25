import { useEffect, useState } from "react";
import { call } from "@/lib/bridge";
import { toast, updateSettings, useStore } from "@/lib/store";
import { useT } from "@/lib/i18n";
import { cn } from "@/lib/cn";
import type { LogSource } from "@/lib/types";
import { TopBar } from "@/components/TopBar";
import { Button } from "@/components/ui/Button";
import { Switch } from "@/components/ui/Switch";

export function Diagnostics() {
  const t = useT();
  const info = useStore((s) => s.info);
  const settings = useStore((s) => s.settings);
  const [logs, setLogs] = useState<LogSource[]>([]);
  const [source, setSource] = useState("app");

  useEffect(() => {
    let alive = true;
    const load = () => call<LogSource[]>("runtime.logs").then((l) => alive && setLogs(l)).catch(() => {});
    void load();
    const timer = setInterval(load, 2000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, []);

  const lines = logs.find((l) => l.source === source)?.lines ?? [];
  const all = () =>
    [`PoorHarness ${info?.version} · Android ${info?.android} · ${info?.device} · ${info?.abi}`]
      .concat(logs.flatMap((l) => [`--- ${l.source}`, ...l.lines]))
      .join("\n");

  return (
    <div className="flex h-full flex-col">
      <TopBar
        title={t("diag.title")}
        right={
          <Button
            size="sm"
            variant="ghost"
            onClick={() => {
              void call("app.copy", { text: all() });
              toast(t("chat.copied"));
            }}
          >
            {t("diag.copy")}
          </Button>
        }
      />
      <div className="px-4 pb-2 text-xs text-mute">
        {t("diag.device")}: {info?.device} · Android {info?.android} · {info?.abi}
      </div>
      <div className="scroll-quiet flex gap-2 overflow-x-auto px-4 pb-2">
        {logs.map((l) => (
          <button
            key={l.source}
            onClick={() => setSource(l.source)}
            className={cn("h-8 shrink-0 rounded-full px-3 font-mono text-xs", source === l.source ? "bg-paper text-void" : "border border-line")}
          >
            {l.source} {l.lines.length}
          </button>
        ))}
      </div>
      <pre className="mx-4 min-h-0 flex-1 overflow-auto rounded-xl border border-line bg-surface p-3 font-mono text-[11px] leading-4 text-ink">
        {lines.join("\n") || "—"}
      </pre>
      <div className="flex items-center gap-3 px-4 py-4">
        <div className="min-w-0 flex-1">
          <div className="text-sm">{t("diag.seccomp")}</div>
          <div className="text-xs text-mute">{t("diag.seccompBody")}</div>
        </div>
        <Switch
          label={t("diag.seccomp")}
          checked={!!settings?.disableSeccomp}
          onCheckedChange={(v) => void updateSettings({ disableSeccomp: v })}
        />
      </div>
    </div>
  );
}
