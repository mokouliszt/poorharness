import { cn } from "@/lib/cn";

/** The app mark. With `busy`, a sonar ring pulses behind it. */
export function Mark({ size = 28, busy = false, className }: { size?: number; busy?: boolean; className?: string }) {
  return (
    <span className={cn("relative inline-flex shrink-0", className)} style={{ width: size, height: size }} aria-hidden>
      {busy && <span className="absolute inset-0 animate-sonar rounded-full border border-paper/60" />}
      <img src="./logo-mark.png" alt="" width={size} height={size} className="relative" draggable={false} />
    </span>
  );
}
