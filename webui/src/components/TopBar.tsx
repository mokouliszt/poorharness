import type { ReactNode } from "react";
import { ArrowLeft } from "lucide-react";
import { pop } from "@/lib/store";
import { useT } from "@/lib/i18n";

export function TopBar({ title, back = true, right }: { title?: ReactNode; back?: boolean; right?: ReactNode }) {
  const t = useT();
  return (
    <header className="flex h-14 shrink-0 items-center gap-1 px-2">
      {back && (
        <button onClick={() => pop()} aria-label={t("common.back")} className="flex h-10 w-10 items-center justify-center rounded-full active:bg-surface">
          <ArrowLeft size={20} />
        </button>
      )}
      <div className="min-w-0 flex-1 truncate px-1 text-base font-medium text-paper">{title}</div>
      {right}
    </header>
  );
}
