import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Check, ChevronRight, LoaderCircle, Plus } from "lucide-react";
import { call } from "@/lib/bridge";
import {
  addPlugin,
  addSkill,
  applyUpdate,
  checkUpdates,
  loadPlugins,
  loadSkills,
  hasProviderKey,
  hasTensorlakeKey,
  loadVersions,
  push,
  refreshAccounts,
  setProviderKey,
  setProviderKeySheet,
  setKeySheet,
  setTinyfishSheet,
  hasTinyfishKey,
  onBackClose,
  loadStorage,
  setDestination,
  setS3Sheet,
  toast,
  updateSettings,
  useStore,
  type ProviderKey,
} from "@/lib/store";
import { describeError, useT } from "@/lib/i18n";
import { cn } from "@/lib/cn";
import { fileSize } from "@/lib/format";
import { count, partsSummary } from "@/screens/Installed";
import type { Approval, Engine } from "@/lib/types";
import { TopBar } from "@/components/TopBar";
import { Button } from "@/components/ui/Button";
import { Sheet } from "@/components/ui/Sheet";
import { Switch } from "@/components/ui/Switch";

/**
 * Engines with a chat working right now. Settings stay reachable during a chat;
 * only the few actions that would cut a running turn short are disabled.
 */
function useBusyEngines(): Set<Engine> {
  const sessions = useStore((s) => s.sessions);
  const busy = useStore((s) => s.busy);
  return useMemo(() => {
    const out = new Set<Engine>();
    if (sessions.some((x) => busy[x.id])) out.add("opencode");
    return out;
  }, [sessions, busy]);
}

export function SettingsScreen() {
  const t = useT();
  const settings = useStore((s) => s.settings);
  const info = useStore((s) => s.info);
  const busyEngines = useBusyEngines();
  const anyBusy = busyEngines.size > 0;
  const anyRunning = useStore((s) => Object.values(s.runtime?.backends ?? {}).some((b) => b?.phase === "ready" || b?.phase === "starting"));
  useEffect(() => {
    void refreshAccounts();
    void loadVersions();
  }, []);

  return (
    <div className="flex h-full flex-col">
      <TopBar title={t("settings.title")} />
      <div className="scroll-quiet min-h-0 flex-1 overflow-y-auto px-4 pb-10">
        {anyBusy && <p className="px-1 pt-3 text-sm text-mute">{t("settings.busyNote")}</p>}
        <Section title={t("settings.accounts")}>
          <TensorlakeRow />
          <ProviderKeyRow provider="opencode" />
          <ProviderKeyRow provider="nvidia" />
          <ProviderKeyRow provider="orcarouter" />
          <ProviderKeyRow provider="infron" />
          <ProviderKeyRow provider="venice" />
          <TinyFishRow />
        </Section>

        <Section title={t("systemPrompt.title")}>
          <SystemPromptRow />
        </Section>

        <Section title={t("skills.title")}>
          <Skills />
        </Section>

        <Section title={t("plugins.title")}>
          <Plugins />
        </Section>

        <OutputsSettings />

        <Section title={t("approvalMode.title")}>
          {(["full_auto", "risky", "always"] as Approval[]).map((a) => (
            <Row key={a} onClick={() => void updateSettings({ approval: a })}>
              <span className="flex-1">{t(`approvalMode.${a}`)}</span>
              {settings?.approval === a && <Check size={18} className="text-paper" />}
            </Row>
          ))}
        </Section>

        <Section title={t("settings.engines")}>
          <Versions busy={busyEngines} />
        </Section>

        <Section title={t("settings.maintenance")}>
          <Row disabled={anyBusy || !anyRunning} onClick={() => void call("engines.stop")}>
            <span className="flex-1">{t("runtime.stopEngines")}</span>
          </Row>
          <Row onClick={() => push({ name: "diagnostics" })}>
            <span className="flex-1">{t("runtime.diagnostics")}</span>
            <ChevronRight size={16} className="text-mute" />
          </Row>
          <Reinstall disabled={anyBusy} />
        </Section>

        <Section title={t("settings.language")}>
          {(["system", "ja", "en"] as const).map((l) => (
            <Row key={l} onClick={() => void updateSettings({ language: l })}>
              <span className="flex-1">{l === "system" ? t("lang.system") : l === "ja" ? "日本語" : "English"}</span>
              {settings?.language === l && <Check size={18} className="text-paper" />}
            </Row>
          ))}
        </Section>

        <Section title={t("settings.about")}>
          <div className="px-4 py-3 text-sm text-mute">
            <div className="text-ink">{t("about.version", { v: info?.version ?? "" })}</div>
            <p className="mt-1">{t("about.body")}</p>
          </div>
          <Row onClick={() => push({ name: "licenses" })}>
            <span className="flex-1">{t("about.licenses")}</span>
            <ChevronRight size={16} className="text-mute" />
          </Row>
        </Section>
      </div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="pt-6">
      <h2 className="px-1 pb-2 text-xs font-medium uppercase tracking-wider text-mute">{title}</h2>
      <div className="divide-y divide-line overflow-hidden rounded-slip border border-line">{children}</div>
    </section>
  );
}

function Row({ children, onClick, disabled }: { children: ReactNode; onClick?: () => void; disabled?: boolean }) {
  const cls = "flex min-h-[52px] w-full items-center gap-3 px-4 py-2 text-left text-base";
  return onClick ? (
    <button className={cn(cls, "active:bg-surface disabled:opacity-50")} disabled={disabled} onClick={onClick}>
      {children}
    </button>
  ) : (
    <div className={cls}>{children}</div>
  );
}

/** One system prompt for both engines (docs/adr/system-prompt.md); it applies from each chat's next message. */
function SystemPromptRow() {
  const t = useT();
  const prompt = useStore((s) => s.settings?.systemPrompt ?? "");
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState("");
  const [saving, setSaving] = useState(false);
  useEffect(() => (open && !saving ? onBackClose(() => setOpen(false)) : undefined), [open, saving]);
  const unchanged = draft.trim() === prompt;

  async function save(text: string) {
    setSaving(true);
    try {
      await updateSettings({ systemPrompt: text });
      setOpen(false);
    } catch (e) {
      toast(describeError(t, (e as Error).message), "error");
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <Row
        onClick={() => {
          setDraft(prompt);
          setOpen(true);
        }}
      >
        {prompt ? (
          <>
            <span className="phrase min-w-0 flex-1 truncate">{prompt.split("\n").find((l) => l.trim()) ?? prompt}</span>
            <ChevronRight size={16} className="shrink-0 text-mute" />
          </>
        ) : (
          <>
            <Plus size={18} className="text-mute" />
            <span className="flex-1">{t("systemPrompt.add")}</span>
          </>
        )}
      </Row>
      <Sheet open={open} onClose={() => !saving && setOpen(false)} title={t("systemPrompt.title")}>
        <div className="scroll-quiet min-h-0 space-y-3 overflow-y-auto px-5 pb-2">
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            maxLength={SYSTEM_PROMPT_MAX}
            disabled={saving}
            aria-label={t("systemPrompt.title")}
            spellCheck={false}
            className="h-44 w-full resize-none rounded-xl border border-line bg-void px-3 py-2.5 text-sm leading-relaxed text-paper focus:border-mute focus:outline-none"
          />
          <Button variant="primary" className="w-full" disabled={saving || unchanged} onClick={() => void save(draft.trim())}>
            {saving ? <LoaderCircle size={16} className="animate-spin" /> : t("common.save")}
          </Button>
          {prompt ? (
            <Button variant="ghost" className="w-full text-mute" disabled={saving} onClick={() => void save("")}>
              {t("common.delete")}
            </Button>
          ) : (
            <Button variant="ghost" className="w-full" disabled={saving} onClick={() => setOpen(false)}>
              {t("common.cancel")}
            </Button>
          )}
        </div>
      </Sheet>
    </>
  );
}

/** Matches SystemPrompt.MAX_CHARS on the host. */
const SYSTEM_PROMPT_MAX = 20_000;

/**
 * Skills the user added (.skill or .zip, as skill-creator packs them); every chat's
 * sandbox gets them. Each opens to its files, where it is also deleted.
 */
function Skills() {
  const t = useT();
  const skills = useStore((s) => s.skills);
  const [adding, setAdding] = useState(false);
  useEffect(() => {
    void loadSkills().catch(() => undefined);
  }, []);

  async function add() {
    setAdding(true);
    try {
      const name = await addSkill();
      if (name) {
        toast(t("skills.added", { name }));
        // Straight to what went in.
        push({ name: "skill", skill: name });
      }
    } catch (e) {
      const message = (e as Error).message;
      toast(
        message.startsWith("skill_invalid:")
          ? t("skills.invalid", { message: message.slice("skill_invalid:".length).trim() })
          : describeError(t, message),
        "error",
      );
    } finally {
      setAdding(false);
    }
  }

  return (
    <>
      {(skills ?? []).map((sk) => (
        <Row key={sk.name} onClick={() => push({ name: "skill", skill: sk.name })}>
          <div className="min-w-0 flex-1">
            <div className="flex items-baseline gap-2">
              <span className="truncate font-mono text-sm">{sk.name}</span>
              <span className="shrink-0 font-mono text-xs text-mute">
                {count(t, "files", sk.files)} · {fileSize(sk.bytes)}
              </span>
            </div>
            {sk.description && <div className="phrase line-clamp-2 text-xs text-mute">{sk.description}</div>}
          </div>
          <ChevronRight size={16} className="shrink-0 text-mute" />
        </Row>
      ))}
      <Row disabled={adding} onClick={() => void add()}>
        <Plus size={18} className="text-mute" />
        <span className="flex-1">{adding ? t("skills.adding") : t("skills.add")}</span>
        <span className="font-mono text-xs text-mute">{t("skills.formats")}</span>
      </Row>
    </>
  );
}

/**
 * Plugin archives; each engine uses the parts it can (docs/adr/plugins.md). Each
 * opens to what it holds and its files, where it is also deleted.
 */
function Plugins() {
  const t = useT();
  const plugins = useStore((s) => s.plugins);
  const [adding, setAdding] = useState(false);
  useEffect(() => {
    void loadPlugins().catch(() => undefined);
  }, []);

  async function add() {
    setAdding(true);
    try {
      const name = await addPlugin();
      if (name) {
        toast(t("plugins.added", { name }));
        // Straight to what went in.
        push({ name: "plugin", plugin: name });
      }
    } catch (e) {
      toast(pluginError(t, (e as Error).message), "error");
    } finally {
      setAdding(false);
    }
  }

  return (
    <>
      {(plugins ?? []).map((pl) => (
        <Row key={pl.name} onClick={() => push({ name: "plugin", plugin: pl.name })}>
          <div className="min-w-0 flex-1">
            <div className="flex items-baseline gap-2">
              <span className="truncate font-mono text-sm">{pl.name}</span>
              <span className="shrink-0 font-mono text-xs text-mute">
                {[pl.version, count(t, "files", pl.files), fileSize(pl.bytes)].filter(Boolean).join(" · ")}
              </span>
            </div>
            <div className="truncate text-xs text-mute">{partsSummary(t, pl) || pl.description}</div>
          </div>
          <ChevronRight size={16} className="shrink-0 text-mute" />
        </Row>
      ))}
      <Row disabled={adding} onClick={() => void add()}>
        <Plus size={18} className="text-mute" />
        <span className="flex-1">{adding ? t("plugins.adding") : t("plugins.add")}</span>
        <span className="font-mono text-xs text-mute">{t("plugins.formats")}</span>
      </Row>
    </>
  );
}

function pluginError(t: ReturnType<typeof useT>, message: string): string {
  const m = /^plugin_invalid:([a-z_]+): ?(.*)$/s.exec(message);
  if (!m) return describeError(t, message);
  const known: Record<string, Parameters<typeof t>[0]> = {
    is_skill: "plugins.isSkill",
    is_marketplace: "plugins.isMarketplace",
    no_manifest: "plugins.noManifest",
  };
  return known[m[1]] ? t(known[m[1]]) : t("plugins.invalid", { message: m[2] });
}

/** Where handed-over files go, the bucket, and what is kept (docs/adr/outputs.md). */
function OutputsSettings() {
  const t = useT();
  const storage = useStore((s) => s.storage);
  useEffect(() => {
    void loadStorage().catch(() => undefined);
  }, []);
  const s3 = storage?.s3 ?? null;
  const pick = (to: "device" | "s3") => void setDestination(to).catch((e: Error) => toast(describeError(t, e.message), "error"));
  let host = "";
  try {
    host = s3 ? new URL(s3.endpoint).host : "";
  } catch {
    host = s3?.endpoint ?? "";
  }
  return (
    <>
      <Section title={t("outputs.section")}>
        <Row onClick={() => pick("device")}>
          <span className="flex-1">{t("outputs.device")}</span>
          {storage?.destination === "device" && <Check size={18} className="text-paper" />}
        </Row>
        <div className="flex min-h-[52px] items-center">
          <button
            className="flex min-h-[52px] min-w-0 flex-1 items-center gap-3 px-4 py-2 text-left text-base active:bg-surface"
            onClick={() => (s3 ? pick("s3") : setS3Sheet(true))}
          >
            <div className="min-w-0 flex-1">
              <div>{t("outputs.s3")}</div>
              <div className="truncate text-xs text-mute">{s3 ? `${s3.bucket} · ${host}` : t("outputs.s3NotConnected")}</div>
            </div>
            {storage?.destination === "s3" && <Check size={18} className="shrink-0 text-paper" />}
          </button>
          <Button size="sm" className="mr-3 shrink-0" onClick={() => setS3Sheet(true)}>
            {s3 ? t("outputs.edit") : t("outputs.connect")}
          </Button>
        </div>
      </Section>
      <Section title={t("outputs.kept")}>
        <Row onClick={() => push({ name: "outputs", shelf: "device" })}>
          <span className="flex-1">{t("outputs.browseDevice")}</span>
          <ChevronRight size={16} className="text-mute" />
        </Row>
        {s3 && (
          <Row onClick={() => push({ name: "outputs", shelf: "s3" })}>
            <span className="flex-1">{t("outputs.browseS3")}</span>
            <ChevronRight size={16} className="text-mute" />
          </Row>
        )}
      </Section>
      {s3 && (
        <Section title="Git">
          <Row onClick={() => push({ name: "repos" })}>
            <span className="flex-1">{t("repos.row")}</span>
            <ChevronRight size={16} className="text-mute" />
          </Row>
        </Section>
      )}
    </>
  );
}

const PROVIDER_ROWS = {
  opencode: { title: "zen.title", none: "zen.free", set: "zen.set" },
  nvidia: { title: "nvidia.title", none: "nvidia.none", set: "nvidia.set" },
  orcarouter: { title: "orcarouter.title", none: "orcarouter.none", set: "orcarouter.set" },
  infron: { title: "infron.title", none: "infron.none", set: "infron.set" },
  venice: { title: "venice.title", none: "venice.none", set: "venice.set" },
} as const;

/** An API key kept in opencode's own store: OpenCode Zen ("opencode"), NVIDIA NIM, OrcaRouter, Infron or Venice. */
function ProviderKeyRow({ provider }: { provider: ProviderKey }) {
  const t = useT();
  const k = PROVIDER_ROWS[provider];
  const has = useStore((s) => hasProviderKey(s, provider));
  return (
    <Row>
      <div className="min-w-0 flex-1">
        <div>{t(k.title)}</div>
        <div className="text-xs text-mute">{has ? t(k.set) : t(k.none)}</div>
      </div>
      {has ? (
        <Button size="sm" onClick={() => void setProviderKey(provider, null).catch((e: Error) => toast(describeError(t, e.message), "error"))}>
          {t("zen.remove")}
        </Button>
      ) : (
        <Button size="sm" onClick={() => setProviderKeySheet(provider)}>
          {t("zen.add")}
        </Button>
      )}
    </Row>
  );
}

function TinyFishRow() {
  const t = useT();
  const has = useStore(hasTinyfishKey);
  return (
    <Row>
      <div className="min-w-0 flex-1">
        <div>TinyFish</div>
        <div className="text-xs text-mute">{has ? t("tf.set") : t("tf.none")}</div>
      </div>
      {has ? (
        <Button
          size="sm"
          onClick={() =>
            void call("tinyfish.set", { key: null })
              .then(refreshAccounts)
              .catch((e: Error) => toast(describeError(t, e.message), "error"))
          }
        >
          {t("zen.remove")}
        </Button>
      ) : (
        <Button size="sm" onClick={() => setTinyfishSheet(true)}>
          {t("zen.add")}
        </Button>
      )}
    </Row>
  );
}

function TensorlakeRow() {
  const t = useT();
  const keySet = useStore(hasTensorlakeKey);
  return (
    <Row>
      <div className="min-w-0 flex-1">
        <div>Tensorlake</div>
        <div className="text-xs text-mute">{keySet ? t("tl.keySet") : t("tl.required")}</div>
      </div>
      <Button size="sm" onClick={() => setKeySheet(true)}>
        {keySet ? t("tl.change") : t("zen.add")}
      </Button>
    </Row>
  );
}

function Versions({ busy }: { busy: Set<Engine> }) {
  const t = useT();
  const settings = useStore((s) => s.settings);
  const updates = useStore((s) => s.updates);
  const progress = useStore((s) => s.updateProgress);
  const backends = useStore((s) => s.runtime?.backends);
  const [checking, setChecking] = useState(false);
  const engines: Engine[] = ["opencode"];
  return (
    <>
      {engines.map((e) => {
        const u = updates[e];
        const b = backends?.[e];
        const pct = progress[e];
        const state =
          b?.phase === "ready" ? t("runtime.running") : b?.phase === "starting" ? t("runtime.startingState") : b?.phase === "failed" ? t("runtime.failedState") : t("runtime.stopped");
        return (
          <Row key={e}>
            <div className="min-w-0 flex-1">
              <div>opencode</div>
              <div className="truncate font-mono text-xs text-mute">
                {u?.current ?? (b?.phase === "ready" ? b.detail : "")}
                {/* The version this app ships with, unless updated here. */}
                {u?.bundled ? ` (${t("runtime.default")})` : ""} · {state}
                {u?.updateAvailable === false ? ` · ${t("runtime.current")}` : ""}
              </div>
            </div>
            {pct != null ? (
              <span className="font-mono text-sm text-mute">{t("runtime.updating", { pct: Math.round(pct * 100) })}</span>
            ) : (
              // A newer release and the way back to this app's own version can both apply.
              <div className="flex shrink-0 flex-col items-end gap-2 py-1">
                {u?.updateAvailable && u.latest ? (
                  <Button size="sm" variant="primary" onClick={() => void applyUpdate(e).catch((err) => toast(describeError(t, String(err.message)), "error"))}>
                    {t("runtime.update", { v: u.latest })}
                  </Button>
                ) : u?.error ? (
                  <span className="text-xs text-mute">{u.error}</span>
                ) : null}
                {u && !u.bundled && (
                  <Button size="sm" disabled={busy.has(e)} onClick={() => void call("updates.reset", { tool: e }).then(checkUpdates)}>
                    {t("runtime.useDefault")}
                  </Button>
                )}
              </div>
            )}
          </Row>
        );
      })}
      <Row
        onClick={async () => {
          setChecking(true);
          try {
            await checkUpdates();
          } catch (err) {
            toast((err as Error).message, "error");
          } finally {
            setChecking(false);
          }
        }}
      >
        <span className="flex-1">{checking ? t("runtime.checking") : t("runtime.check")}</span>
      </Row>
      {/* The whole row toggles, as in the options sheet. */}
      <label className="flex min-h-[52px] w-full cursor-pointer items-center gap-3 px-4 py-2 text-left text-base active:bg-surface">
        <span className="flex-1">{t("runtime.autoUpdate")}</span>
        <Switch
          label={t("runtime.autoUpdate")}
          checked={!!settings?.autoUpdate}
          onCheckedChange={(v) => void updateSettings({ autoUpdate: v }).catch((err: Error) => toast(describeError(t, err.message), "error"))}
        />
      </label>
    </>
  );
}

function Reinstall({ disabled }: { disabled: boolean }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Row disabled={disabled} onClick={() => setOpen(true)}>
        <span className="flex-1 text-danger">{t("runtime.reinstall")}</span>
      </Row>
      <Sheet open={open} onClose={() => setOpen(false)} title={t("runtime.reinstall")}>
        <div className="space-y-3 px-5 pb-2">
          <p className="text-sm text-mute">{t("runtime.reinstallConfirm")}</p>
          <Button
            variant="danger"
            className="w-full"
            onClick={() => {
              setOpen(false);
              void call("runtime.reinstall").catch((e: Error) => toast(e.message, "error"));
            }}
          >
            {t("runtime.reinstall")}
          </Button>
          <Button variant="ghost" className="w-full" onClick={() => setOpen(false)}>
            {t("common.cancel")}
          </Button>
        </div>
      </Sheet>
    </>
  );
}
