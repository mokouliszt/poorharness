import { useState } from "react";
import { Check, Square, SquareCheck } from "lucide-react";
import { Sheet } from "./ui/Sheet";
import { Button } from "./ui/Button";
import { answerQuestion, useStore } from "@/lib/store";
import { useT } from "@/lib/i18n";
import { cn } from "@/lib/cn";
import type { QuestionRequest } from "@/lib/types";

/**
 * The agent's questions (opencode's question tool, docs/adr/questions.md) for
 * [sessionId], or for any chat when omitted: one question at a time, answered
 * with its options or the user's own words, all sent together at the end.
 */
export function QuestionSheet({ sessionId }: { sessionId?: string }) {
  const request = useStore((s) => s.questions.find((q) => !sessionId || q.sessionId === sessionId));
  if (!request) return null;
  return <Questions key={request.requestId} request={request} />;
}

function Questions({ request }: { request: QuestionRequest }) {
  const t = useT();
  const qs = request.questions;
  const [step, setStep] = useState(0);
  const [picks, setPicks] = useState<string[][]>(() => qs.map(() => []));
  const [own, setOwn] = useState<string[]>(() => qs.map(() => ""));
  const [ownOn, setOwnOn] = useState<boolean[]>(() => qs.map(() => false));
  const q = qs[step];
  if (!q) return null;
  const answerOf = (i: number) => [...picks[i], ...(ownOn[i] && own[i].trim() ? [own[i].trim()] : [])];
  const last = step === qs.length - 1;
  const ready = answerOf(step).length > 0;

  const put = <T,>(list: T[], value: T) => list.map((v, i) => (i === step ? value : v));
  const pick = (label: string) => {
    if (q.multiple) {
      setPicks(put(picks, picks[step].includes(label) ? picks[step].filter((l) => l !== label) : [...picks[step], label]));
    } else {
      setPicks(put(picks, [label]));
      setOwnOn(put(ownOn, false));
    }
  };
  const typeOwn = (text: string) => {
    setOwn(put(own, text));
    setOwnOn(put(ownOn, true));
    if (!q.multiple) setPicks(put(picks, []));
  };
  const next = () => {
    if (!last) setStep(step + 1);
    else void answerQuestion(request.requestId, qs.map((_, i) => answerOf(i)));
  };

  const title = qs.length > 1 ? `${q.header || t("question.title")} · ${step + 1}/${qs.length}` : q.header || t("question.title");
  return (
    <Sheet open onClose={() => {}} dismissable={false} title={title}>
      <div className="scroll-quiet min-h-0 flex-1 overflow-y-auto px-5">
        <p className="phrase whitespace-pre-wrap break-words text-base text-ink">{q.question}</p>
        {q.multiple && <p className="mt-1 text-xs text-mute">{t("question.multiple")}</p>}
        <div className="mt-3 space-y-1">
          {q.options.map((o) => {
            const on = picks[step].includes(o.label);
            return (
              <button
                key={o.label}
                onClick={() => pick(o.label)}
                aria-pressed={on}
                className={cn("flex min-h-12 w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left", on ? "bg-paper/10" : "active:bg-raised")}
              >
                {q.multiple &&
                  (on ? <SquareCheck size={18} className="shrink-0 text-paper" /> : <Square size={18} className="shrink-0 text-mute" />)}
                <div className="min-w-0 flex-1">
                  <div className="break-words text-base text-paper">{o.label}</div>
                  {o.description && <div className="break-words text-xs text-mute">{o.description}</div>}
                </div>
                {!q.multiple && on && <Check size={18} className="shrink-0 text-paper" />}
              </button>
            );
          })}
        </div>
        {q.custom && (
          <input
            value={own[step]}
            onChange={(e) => typeOwn(e.target.value)}
            onFocus={() => {
              if (!own[step].trim()) return;
              setOwnOn(put(ownOn, true));
              if (!q.multiple) setPicks(put(picks, []));
            }}
            onKeyDown={(e) => e.key === "Enter" && ready && next()}
            placeholder={t("question.own")}
            aria-label={t("question.own")}
            className={cn(
              "mt-2 h-12 w-full rounded-xl border bg-void px-4 text-base text-paper placeholder:text-mute/70 focus:outline-none",
              ownOn[step] && own[step].trim() ? "border-mute" : "border-line focus:border-mute",
            )}
          />
        )}
      </div>
      <div className="grid gap-2 px-5 pt-4">
        <div className="flex gap-2">
          {step > 0 && (
            <Button className="flex-1" onClick={() => setStep(step - 1)}>
              {t("question.back")}
            </Button>
          )}
          <Button variant="primary" className="flex-1" disabled={!ready} onClick={next}>
            {last ? t("question.send") : t("question.next")}
          </Button>
        </div>
        <Button variant="ghost" onClick={() => void answerQuestion(request.requestId, null)}>
          {t("question.dismiss")}
        </Button>
      </div>
    </Sheet>
  );
}
