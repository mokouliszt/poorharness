import { useState } from "react";
import { ExternalLink, LoaderCircle } from "lucide-react";
import { call } from "@/lib/bridge";
import { refreshAccounts, refreshModels, setKeySheet, setKeyStage, setProviderKey, setProviderKeySheet, setTinyfishSheet, toast, useStore } from "@/lib/store";
import { describeError, useT } from "@/lib/i18n";
import { cn } from "@/lib/cn";
import { Sheet } from "./ui/Sheet";
import { Button } from "./ui/Button";

// Where people get keys. Kept in the UI so it owns every link it shows.
export const ZEN_KEYS_URL = "https://opencode.ai/auth";
export const TENSORLAKE_KEYS_URL = "https://cloud.tensorlake.ai/";
export const TINYFISH_KEYS_URL = "https://agent.tinyfish.ai/api-keys";
export const NVIDIA_KEYS_URL = "https://build.nvidia.com/";
export const ORCAROUTER_KEYS_URL = "https://www.orcarouter.ai/console";
export const VENICE_KEYS_URL = "https://venice.ai/settings/api";
export const INFRON_KEYS_URL = "https://infron.ai/dashboard/apiKeys";

/** A password field with a link to where the key comes from. [onSave] throws to keep the sheet open. */
export function KeySheet(props: {
  open: boolean;
  title: string;
  label: string;
  getUrl: string;
  getLabel: string;
  note?: string;
  onClose: () => void;
  onSave: (key: string) => Promise<void>;
}) {
  const t = useT();
  const [value, setValue] = useState("");
  const [saving, setSaving] = useState(false);
  // Saving can take a while (opencode may have to start); the button says what it waits for.
  const stage = useStore((s) => s.keyStage);
  return (
    <Sheet open={props.open} onClose={props.onClose} title={props.title}>
      <div className="space-y-3 px-5 pb-2">
        <input
          type="password"
          autoComplete="off"
          spellCheck={false}
          value={value}
          disabled={saving}
          onChange={(e) => setValue(e.target.value)}
          placeholder={props.label}
          aria-label={props.label}
          className="h-12 w-full rounded-xl border border-line bg-void px-4 font-mono text-sm text-paper placeholder:text-mute focus:border-mute focus:outline-none"
        />
        {props.note && <p className="text-xs text-mute">{props.note}</p>}
        <button
          className="flex items-center gap-1.5 text-sm text-mute underline underline-offset-4"
          onClick={() => void call("app.openUrl", { url: props.getUrl })}
        >
          {props.getLabel}
          <ExternalLink size={13} />
        </button>
        <Button
          variant="primary"
          disabled={!value.trim() || saving}
          aria-busy={saving}
          // Disabled while saving, but not dimmed, so the stage stays readable.
          className={cn("w-full", saving && "disabled:bg-paper disabled:text-void")}
          onClick={async () => {
            setSaving(true);
            try {
              await props.onSave(value.trim());
              setValue("");
              props.onClose();
            } catch (e) {
              toast(describeError(t, (e as Error).message), "error");
            } finally {
              setSaving(false);
            }
          }}
        >
          {saving ? (
            <span className="flex min-w-0 items-center gap-2">
              <LoaderCircle size={16} className="shrink-0 animate-spin" />
              <span className="truncate">{t(stage ? `key.${stage}` : "key.saving")}</span>
            </span>
          ) : (
            t("common.save")
          )}
        </Button>
      </div>
    </Sheet>
  );
}

/** The Tensorlake key (optional: without it chats run on the device), opened from Settings. */
export function TensorlakeKeySheet() {
  const t = useT();
  const open = useStore((s) => s.keySheet);
  return (
    <KeySheet
      open={open}
      title={t("tl.sheetTitle")}
      label={t("tl.key")}
      getUrl={TENSORLAKE_KEYS_URL}
      getLabel={t("tl.get")}
      onClose={() => setKeySheet(false)}
      onSave={async (key) => {
        // The host checks the key with Tensorlake before keeping it.
        setKeyStage("checking");
        try {
          await call("tensorlake.set", { key });
          setKeyStage("models");
          await refreshAccounts();
          await refreshModels();
        } finally {
          setKeyStage(null);
        }
        toast(t("tl.saved"));
      }}
    />
  );
}

/** The TinyFish key (optional: the backup for web search and fetch), opened from Settings. */
export function TinyFishKeySheet() {
  const t = useT();
  const open = useStore((s) => s.tinyfishSheet);
  return (
    <KeySheet
      open={open}
      title={t("tf.sheetTitle")}
      label={t("tl.key")}
      getUrl={TINYFISH_KEYS_URL}
      getLabel={t("tl.get")}
      onClose={() => setTinyfishSheet(false)}
      onSave={async (key) => {
        // The host checks the key with TinyFish before keeping it.
        setKeyStage("checking");
        try {
          await call("tinyfish.set", { key });
          await refreshAccounts();
        } finally {
          setKeyStage(null);
        }
        toast(t("tl.saved"));
      }}
    />
  );
}

const PROVIDERS = {
  opencode: { title: "zen.title", get: "zen.get", url: ZEN_KEYS_URL },
  nvidia: { title: "nvidia.title", get: "nvidia.get", url: NVIDIA_KEYS_URL },
  orcarouter: { title: "orcarouter.title", get: "orcarouter.get", url: ORCAROUTER_KEYS_URL },
  infron: { title: "infron.title", get: "infron.get", url: INFRON_KEYS_URL },
  venice: { title: "venice.title", get: "venice.get", url: VENICE_KEYS_URL },
} as const;

/** An opencode provider key (Zen, NVIDIA NIM, OrcaRouter, Infron or Venice), opened from Settings or the model picker. */
export function ProviderKeySheet() {
  const t = useT();
  const provider = useStore((s) => s.providerKeySheet);
  const k = PROVIDERS[provider ?? "opencode"];
  return (
    <KeySheet
      open={!!provider}
      title={t(k.title)}
      label={t("tl.key")}
      getUrl={k.url}
      getLabel={t(k.get)}
      onClose={() => setProviderKeySheet(null)}
      onSave={async (key) => {
        if (!provider) return;
        await setProviderKey(provider, key);
        toast(t("zen.saved"));
      }}
    />
  );
}
