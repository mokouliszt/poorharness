import { useStore } from "@/lib/store";
import { cn } from "@/lib/cn";

export function Toasts() {
  const toasts = useStore((s) => s.toasts);
  // Below the 56 px top bar, so a toast never covers its buttons.
  return (
    <div className="pointer-events-none fixed inset-x-0 top-16 z-[60] flex flex-col items-center gap-2 px-4" aria-live="polite">
      {toasts.map((t) => (
        <div
          key={t.id}
          className={cn(
            "max-w-md animate-fade rounded-full px-4 py-2 text-sm shadow-lg",
            t.tone === "error" ? "bg-danger text-paper" : "bg-paper text-void",
          )}
        >
          {t.text}
        </div>
      ))}
    </div>
  );
}
