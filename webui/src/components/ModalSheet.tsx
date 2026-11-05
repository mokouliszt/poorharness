import { useEffect, useState } from "react";
import { Check, LoaderCircle } from "lucide-react";
import { call } from "@/lib/bridge";
import { loadModalUsage, refreshAccounts, setModalSheet, toast, updateSettings, useStore } from "@/lib/store";
import { describeError, useT } from "@/lib/i18n";
import { usd } from "@/lib/format";
import { cn } from "@/lib/cn";
import { Sheet } from "./ui/Sheet";
import { Button } from "./ui/Button";

/**
 * Modal, once a token is set (docs/adr/modal-first.md): the month's budget the app
 * keeps chats under, and the GPU chats get when they turn it on. The GPUs and
 * their prices are Modal's own list, read when the sheet opens.
 */
export function ModalSheet() {
  const t = useT();
  const open = useStore((s) => s.modalSheet);
  const settings = useStore((s) => s.settings);
  const usage = useStore((s) => s.modalUsage);
  const [budget, setBudget] = useState("");
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!open) return;
    setBudget(String(settings?.modalBudgetUsd ?? 30));
    setLoading(true);
    void loadModalUsage(true).finally(() => setLoading(false));
    // Only on opening: a later settings change must not overwrite what is being typed.
  }, [open]);

  const value = Number(budget);
  const valid = budget.trim() !== "" && Number.isFinite(value) && value > 0;
  const changed = valid && value !== settings?.modalBudgetUsd;
  const selected = settings?.modalGpu ?? "T4";
  // The chosen GPU stays listed even if Modal's list could not be read.
  const gpus = usage?.gpus.length ? usage.gpus : [{ id: selected, usdPerHour: NaN }];

  return (
    <Sheet open={open} onClose={() => !saving && setModalSheet(false)} title="Modal">
      <div className="scroll-quiet min-h-0 space-y-4 overflow-y-auto px-5 pb-2">
        <p className="text-sm text-mute">
          {usage ? t("modal.usage", { spent: usd(usage.spentUsd), budget: usd(usage.budgetUsd) }) : loading ? t("modal.reading") : t("modal.set")}
        </p>

        <div>
          <label className="block">
            <span className="mb-1 block text-xs text-mute">{t("modal.budget")}</span>
            <div className="flex gap-2">
              <input
                type="text"
                inputMode="decimal"
                autoComplete="off"
                value={budget}
                disabled={saving}
                onChange={(e) => setBudget(e.target.value.replace(/[^0-9.]/g, ""))}
                className="h-11 min-w-0 flex-1 rounded-xl border border-line bg-void px-3 font-mono text-sm text-paper focus:border-mute focus:outline-none"
              />
              <Button
                variant="primary"
                disabled={!changed || saving}
                onClick={async () => {
                  setSaving(true);
                  try {
                    await updateSettings({ modalBudgetUsd: value });
                    void loadModalUsage();
                    toast(t("modal.budgetSaved"));
                  } catch (e) {
                    toast(describeError(t, (e as Error).message), "error");
                  } finally {
                    setSaving(false);
                  }
                }}
              >
                {t("common.save")}
              </Button>
            </div>
          </label>
          <p className="mt-1.5 text-xs text-mute">{t("modal.budgetNote")}</p>
        </div>

        <div>
          <div className="mb-1 flex items-center gap-2 text-xs text-mute">
            <span>{t("modal.gpu")}</span>
            {loading && <LoaderCircle size={12} className="animate-spin" />}
          </div>
          <div className="divide-y divide-line overflow-hidden rounded-xl border border-line">
            {gpus.map((g) => (
              <button
                key={g.id}
                disabled={saving}
                onClick={() => void updateSettings({ modalGpu: g.id }).catch((e: Error) => toast(describeError(t, e.message), "error"))}
                className="flex min-h-[44px] w-full items-center gap-3 px-3 text-left text-sm active:bg-surface"
              >
                <span className="flex-1 font-mono text-paper">{g.id}</span>
                {Number.isFinite(g.usdPerHour) && <span className="text-mute">{t("modal.perHour", { price: usd(g.usdPerHour) })}</span>}
                <Check size={16} className={cn("shrink-0", g.id === selected ? "text-paper" : "invisible")} />
              </button>
            ))}
          </div>
          <p className="mt-1.5 text-xs text-mute">{t("modal.gpuNote")}</p>
        </div>

        <Button
          variant="ghost"
          className="w-full text-mute"
          disabled={saving}
          onClick={() =>
            void call("modal.set", { key: null })
              .then(refreshAccounts)
              .then(() => setModalSheet(false))
              .catch((e: Error) => toast(describeError(t, e.message), "error"))
          }
        >
          {t("modal.remove")}
        </Button>
      </div>
    </Sheet>
  );
}
