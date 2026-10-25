import { useEffect, useState } from "react";
import { LoaderCircle } from "lucide-react";
import { clearS3, saveS3, setDestination, setS3Sheet, toast, useStore } from "@/lib/store";
import { describeError, useT, type TKey } from "@/lib/i18n";
import { cn } from "@/lib/cn";
import { Sheet } from "./ui/Sheet";
import { Button } from "./ui/Button";

type Fields = { endpoint: string; bucket: string; accessKeyId: string; secret: string; region: string; prefix: string };
const EMPTY: Fields = { endpoint: "", bucket: "", accessKeyId: "", secret: "", region: "", prefix: "" };

/**
 * The S3-compatible bucket (docs/adr/s3-storage.md). Saving writes, reads back and
 * deletes a test object first, so a wrong key or bucket is caught here.
 */
export function S3Sheet() {
  const t = useT();
  const open = useStore((s) => s.s3Sheet);
  const current = useStore((s) => s.storage?.s3 ?? null);
  const [f, setF] = useState<Fields>(EMPTY);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (open) setF(current ? { ...EMPTY, ...current, secret: "" } : EMPTY);
  }, [open, current]);

  const ready = f.endpoint.trim() && f.bucket.trim() && f.accessKeyId.trim() && (f.secret.trim() || current?.accessKeyId === f.accessKeyId.trim());

  function field(key: keyof Fields, label: TKey, opts: { secret?: boolean; placeholder?: string; mono?: boolean } = {}) {
    return (
      <label className="block">
        <span className="mb-1 block text-xs text-mute">{t(label)}</span>
        <input
          type={opts.secret ? "password" : "text"}
          inputMode={key === "endpoint" ? "url" : undefined}
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
          value={f[key]}
          disabled={saving}
          placeholder={opts.placeholder}
          onChange={(e) => setF({ ...f, [key]: e.target.value })}
          className={cn(
            "h-11 w-full rounded-xl border border-line bg-void px-3 text-sm text-paper placeholder:font-sans placeholder:text-mute/70 focus:border-mute focus:outline-none",
            opts.mono !== false && "font-mono",
          )}
        />
      </label>
    );
  }

  return (
    <Sheet open={open} onClose={() => !saving && setS3Sheet(false)} title={t("s3.title")}>
      <div className="scroll-quiet min-h-0 space-y-3 overflow-y-auto px-5 pb-2">
        {field("endpoint", "s3.endpoint", { placeholder: "https://s3.us-east-1.amazonaws.com" })}
        {field("bucket", "s3.bucket")}
        {field("accessKeyId", "s3.keyId")}
        {field("secret", "s3.secret", { secret: true, placeholder: current ? t("s3.secretKeep") : undefined })}
        <div className="grid grid-cols-2 gap-3">
          {field("region", "s3.region")}
          {field("prefix", "s3.prefix")}
        </div>
        <Button
          variant="primary"
          disabled={!ready || saving}
          aria-busy={saving}
          className={cn("w-full", saving && "disabled:bg-paper disabled:text-void")}
          onClick={async () => {
            setSaving(true);
            try {
              const first = !current;
              await saveS3({ ...f, endpoint: f.endpoint.trim(), bucket: f.bucket.trim(), accessKeyId: f.accessKeyId.trim() });
              // Connecting is choosing it; changing a connected bucket leaves the choice as it was.
              if (first) await setDestination("s3");
              toast(t("s3.connected", { bucket: f.bucket.trim() }));
              setS3Sheet(false);
            } catch (e) {
              const msg = (e as Error).message;
              toast(msg.startsWith("s3_failed:") ? msg.slice("s3_failed:".length).trim() : describeError(t, msg), "error");
            } finally {
              setSaving(false);
            }
          }}
        >
          {saving ? (
            <span className="flex min-w-0 items-center gap-2">
              <LoaderCircle size={16} className="shrink-0 animate-spin" />
              <span className="truncate">{t("s3.checking")}</span>
            </span>
          ) : (
            t("s3.save")
          )}
        </Button>
        {current ? (
          <Button
            variant="ghost"
            className="w-full text-mute"
            disabled={saving}
            onClick={() => {
              void clearS3()
                .then(() => setS3Sheet(false))
                .catch((e: Error) => toast(describeError(t, e.message), "error"));
            }}
          >
            {t("s3.disconnect")}
          </Button>
        ) : (
          <Button variant="ghost" className="w-full" disabled={saving} onClick={() => setS3Sheet(false)}>
            {t("common.cancel")}
          </Button>
        )}
      </div>
    </Sheet>
  );
}
