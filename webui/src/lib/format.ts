import type { Lang } from "./i18n";

export function compactNumber(n: number | null | undefined): string {
  if (n == null) return "–";
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(n >= 10_000_000 ? 0 : 1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(n >= 10_000 ? 0 : 1)}k`;
  return String(n);
}

export function timeLabel(ts: number | null, lang: Lang): string {
  if (!ts) return "";
  const d = new Date(ts);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  const locale = lang === "ja" ? "ja-JP" : "en-US";
  if (sameDay) return d.toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" });
  return d.toLocaleDateString(locale, { month: "short", day: "numeric" });
}

/** A moment later on: the time if it is today, otherwise the date and time. */
export function laterLabel(ts: number, lang: Lang): string {
  const d = new Date(ts);
  const locale = lang === "ja" ? "ja-JP" : "en-US";
  const time = d.toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" });
  if (d.toDateString() === new Date().toDateString()) return time;
  return `${d.toLocaleDateString(locale, { month: "short", day: "numeric" })} ${time}`;
}

export type DayBucket = "today" | "yesterday" | "earlier";

export function dayBucket(ts: number | null): DayBucket {
  if (!ts) return "earlier";
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  if (ts >= start.getTime()) return "today";
  if (ts >= start.getTime() - 86_400_000) return "yesterday";
  return "earlier";
}

/** File sizes as people read them: 512 B, 3.4 KB, 12 MB. */
export function fileSize(n: number | null | undefined): string {
  if (n == null) return "";
  if (n < 1024) return `${n} B`;
  const units = ["KB", "MB", "GB"];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v >= 10 ? Math.round(v) : v.toFixed(1)} ${units[i]}`;
}
