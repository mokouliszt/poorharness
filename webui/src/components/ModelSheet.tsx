import { useEffect, useMemo, useState } from "react";
import { Check, ChevronRight, CornerDownRight, LoaderCircle, Search } from "lucide-react";
import { Sheet } from "./ui/Sheet";
import {
  chooseModel,
  chooseSubagentModel,
  setModelSheet,
  setProviderKeySheet,
  setSubagentSheet,
  takePickerOpenGroup,
  useStore,
  type ProviderKey,
} from "@/lib/store";
import { describeError, useT, type TKey } from "@/lib/i18n";
import { compactNumber } from "@/lib/format";
import { cn } from "@/lib/cn";
import type { ModelInfo } from "@/lib/types";

const EMPTY_KEYS: string[] = [];
/** opencode providers that show nothing until their key is added, in picker order. */
const KEYED = [
  { id: "nvidia", label: "models.nvidia" },
  { id: "orcarouter", label: "orcarouter.title" },
  { id: "infron", label: "infron.title" },
  { id: "venice", label: "venice.title" },
] as const satisfies readonly { id: ProviderKey; label: TKey }[];

/**
 * Every model opencode reports, grouped by provider. Nothing is hardcoded: the
 * list is whatever opencode's catalog says. Any model can be picked inside a chat.
 *
 * [target] "subagent" picks the model subagents run on (docs/adr/subagent-model.md),
 * from the same list, with a first row that keeps them on the chat's model.
 */
export function ModelSheet({ target = "chat" }: { target?: "chat" | "subagent" }) {
  const t = useT();
  const forSubagents = target === "subagent";
  const open = useStore((s) => (forSubagents ? s.subagentSheet : s.modelSheet));
  const models = useStore((s) => s.models);
  const errors = useStore((s) => s.modelErrors);
  const pending = useStore((s) => s.modelsPending);
  const selected = useStore((s) => (forSubagents ? s.settings?.subagentModel ?? null : s.model));
  const effort = useStore((s) => (forSubagents ? s.settings?.subagentEffort ?? null : s.effort));
  const close = () => (forSubagents ? setSubagentSheet(false) : setModelSheet(false));
  const choose = (id: string | null, e: string | null) => (forSubagents ? void chooseSubagentModel(id, e) : chooseModel(id!, e));
  const keys = useStore((s) => s.accounts?.opencode?.keys ?? EMPTY_KEYS);
  const [query, setQuery] = useState("");

  const groups = useMemo(() => {
    const q = query.trim().toLowerCase();
    const freeWord = t("models.freeBadge").toLowerCase();
    const match = (m: ModelInfo) =>
      !q ||
      m.name.toLowerCase().includes(q) ||
      m.id.toLowerCase().includes(q) ||
      m.providerName.toLowerCase().includes(q) ||
      // "free" (or 無料) finds the free models even when their ids do not say so.
      (m.free && (freeWord.includes(q) || "free".includes(q)));
    // Subagents use tools; a model that cannot call them is no subagent model.
    const list = models.filter(match).filter((m) => !(forSubagents && m.chatOnly));
    const out: { key: string; label: string; items: ModelInfo[]; addKey?: ProviderKey }[] = [];
    // OpenCode's own models (Zen): the free ones first, then any a Zen key adds.
    const zen = list.filter((m) => m.backend === "opencode" && m.provider === "opencode");
    const ordered = [...zen.filter((m) => m.free), ...zen.filter((m) => !m.free)];
    // Without a Zen key the group still offers one, as the other providers do.
    if (ordered.length) out.push({ key: "p:opencode", label: t("models.opencode"), items: ordered, addKey: keys.includes("opencode") ? undefined : "opencode" });
    const byProvider = new Map<string, { label: string; items: ModelInfo[] }>();
    for (const m of list.filter((m) => m.backend === "opencode" && m.provider !== "opencode")) {
      const g = byProvider.get(m.provider) ?? { label: m.provider === "nvidia" ? t("models.nvidia") : m.providerName, items: [] };
      g.items.push(m);
      byProvider.set(m.provider, g);
    }
    for (const [id, g] of byProvider) out.push({ key: `p:${id}`, label: g.label, items: g.items });
    // These list their models only once a key is added, so offer the key even with none known yet.
    for (const k of KEYED) {
      if (!keys.includes(k.id) && !q && !byProvider.has(k.id)) out.push({ key: `p:${k.id}`, label: t(k.label), items: [], addKey: k.id });
    }
    // Adding a key is the chat picker's job; subagents pick from what is there.
    return forSubagents ? out.filter((g) => g.items.length > 0) : out;
  }, [models, query, keys, t, forSubagents]);

  const current = models.find((m) => m.id === selected);
  const mixed = new Set(groups.filter((g) => g.items.some((m) => m.free) && g.items.some((m) => !m.free)).map((g) => g.key));

  // Only the group holding the current model starts open (or, with none picked, the
  // first with models), plus a provider whose key was just added. A search opens
  // every group; either way each can still be closed.
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [started, setStarted] = useState(false);
  const home = () => {
    const g = groups.find((x) => x.items.some((m) => m.id === selected)) ?? groups.find((x) => x.items.length > 0);
    return [g?.key, takePickerOpenGroup()].filter((k): k is string => !!k);
  };
  useEffect(() => {
    if (!open) return setStarted(false);
    // Wait for models when the picker opens before any arrived.
    if (started || !groups.some((g) => g.items.length > 0)) return;
    setExpanded(new Set(home()));
    setStarted(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, groups]);
  // While searching, every group is open unless the person closed it in this search.
  const searching = !!query.trim();
  const [closedInSearch, setClosedInSearch] = useState<Set<string>>(new Set());
  useEffect(() => setClosedInSearch(new Set()), [searching]);
  const flip = (set: Set<string>, key: string) => {
    const next = new Set(set);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    return next;
  };
  const toggle = (key: string) =>
    searching ? setClosedInSearch((prev) => flip(prev, key)) : setExpanded((prev) => flip(prev, key));

  return (
    <Sheet open={open} onClose={close} title={t(forSubagents ? "subagentModel.title" : "models.title")}>
      <div className="px-4 pb-2">
        <label className="flex h-10 items-center gap-2 rounded-full bg-raised px-3">
          <Search size={15} className="text-mute" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("models.search")}
            className="min-w-0 flex-1 bg-transparent text-base text-paper placeholder:text-mute focus:outline-none"
          />
        </label>
      </div>

      <div className="scroll-quiet min-h-0 flex-1 overflow-y-auto px-2 pb-2">
        {Object.entries(errors).map(([engine, message]) => (
          <p key={engine} className="px-3 py-1 text-xs text-danger">
            {t("models.loadFailed", { engine, message: describeError(t, message) })}
          </p>
        ))}
        {pending.map((engine) => (
          <p key={engine} className="flex items-center gap-2 px-3 py-2 text-sm text-mute">
            <LoaderCircle size={14} className="animate-spin" />
            {t("models.loadingShort")}
          </p>
        ))}
        {forSubagents && !searching && (
          <button
            onClick={() => {
              choose(null, null);
              close();
            }}
            className={cn(
              "flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left",
              selected === null ? "bg-paper/10" : "active:bg-raised",
            )}
          >
            <CornerDownRight size={16} className="shrink-0 text-mute" />
            <span className="min-w-0 flex-1 truncate text-base text-paper">{t("subagentModel.same")}</span>
            {selected === null && <Check size={18} className="shrink-0 text-paper" />}
          </button>
        )}
        {groups.length === 0 && pending.length === 0 && <p className="px-3 py-6 text-center text-sm text-mute">{t("models.none")}</p>}
        {groups.map((g) => {
          // A group with nothing to list yet (no key) is a single row.
          const empty = g.items.length === 0;
          const isOpen = !empty && (searching ? !closedInSearch.has(g.key) : expanded.has(g.key));
          return (
          <section key={g.key} className="pt-1">
            <div className="flex items-center justify-between pr-3">
              {empty ? (
                <span className="flex min-h-10 min-w-0 flex-1 items-center px-3">
                  <span className="truncate text-xs font-medium uppercase tracking-wider text-mute">{g.label}</span>
                </span>
              ) : (
                <button
                  onClick={() => toggle(g.key)}
                  aria-expanded={isOpen}
                  className="flex min-h-10 min-w-0 flex-1 items-center gap-1.5 rounded-lg px-3 text-left active:bg-raised"
                >
                  <ChevronRight size={14} className={cn("shrink-0 text-mute transition-transform", isOpen && "rotate-90")} />
                  <span className="truncate text-xs font-medium uppercase tracking-wider text-mute">{g.label}</span>
                  <span className="shrink-0 text-xs text-mute">{g.items.length}</span>
                  {!isOpen && g.items.some((m) => m.id === selected) && <Check size={14} className="ml-auto shrink-0 text-paper" />}
                </button>
              )}
              {g.addKey && !forSubagents && (
                <button className="text-xs font-medium text-paper underline underline-offset-4" onClick={() => setProviderKeySheet(g.addKey!)}>
                  {t("models.addKey")}
                </button>
              )}
            </div>
            {isOpen && g.items.map((m) => (
              <button
                key={m.id}
                disabled={!m.available}
                onClick={() => {
                  const keep = effort && m.efforts.includes(effort) ? effort : m.defaultEffort;
                  choose(m.id, keep);
                  if (!m.efforts.length) close();
                }}
                className={cn(
                  "flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left disabled:opacity-40",
                  m.id === selected ? "bg-paper/10" : "active:bg-raised",
                )}
              >
                <div className="min-w-0 flex-1">
                  <div className="flex min-w-0 items-center gap-2">
                    <span className="truncate text-base text-paper">{m.name}</span>
                    {/* Marked only where free and paid models share a group (a Zen key adds paid ones). */}
                    {m.chatOnly && <span className="shrink-0 rounded-full border border-line px-1.5 text-[11px] leading-4 text-mute">{t("models.chatOnly")}</span>}
                    {m.free && mixed.has(g.key) && <span className="shrink-0 rounded-full border border-line px-1.5 text-[11px] leading-4 text-mute">{t("models.freeBadge")}</span>}
                  </div>
                  <div className="truncate text-xs text-mute">
                    {m.description ?? m.id.slice(m.id.indexOf(":") + 1)}
                    {m.contextWindow ? ` · ${t("models.context", { n: compactNumber(m.contextWindow) })}` : ""}
                  </div>
                </div>
                {m.id === selected && <Check size={18} className="shrink-0 text-paper" />}
              </button>
            ))}
          </section>
          );
        })}
      </div>

      {current && current.efforts.length > 0 && (
        <div className="border-t border-line px-4 pt-3">
          <div className="mb-2 text-xs font-medium uppercase tracking-wider text-mute">{t("models.effort")}</div>
          <div className="scroll-quiet flex gap-2 overflow-x-auto pb-1">
            {current.efforts.map((e) => (
              <button
                key={e}
                onClick={() => {
                  choose(current.id, e);
                  close();
                }}
                className={cn(
                  "h-9 shrink-0 rounded-full px-4 font-mono text-sm",
                  e === effort ? "bg-paper text-void" : "border border-line text-ink",
                )}
              >
                {e}
                {e === current.defaultEffort && e !== effort ? " ·" : ""}
              </button>
            ))}
          </div>
        </div>
      )}
    </Sheet>
  );
}
