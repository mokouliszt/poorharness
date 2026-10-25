import { useEffect, useState } from "react";
import { call } from "@/lib/bridge";
import { useT } from "@/lib/i18n";
import { TopBar } from "@/components/TopBar";
import { Markdown } from "@/components/Markdown";

export function Licenses() {
  const t = useT();
  const [text, setText] = useState<string | null>(null);
  useEffect(() => {
    call<string>("app.notices").then(setText).catch((e) => setText(String(e.message)));
  }, []);
  return (
    <div className="flex h-full flex-col">
      <TopBar title={t("about.licenses")} />
      <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-10 text-sm">{text != null && <Markdown text={text} />}</div>
    </div>
  );
}
