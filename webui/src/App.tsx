import { useEffect } from "react";
import { setBackHandler } from "@/lib/bridge";
import { currentScreen, init, pop, useStore } from "@/lib/store";
import { resolveLang } from "@/lib/i18n";
import { Setup } from "@/screens/Setup";
import { Home } from "@/screens/Home";
import { Chat } from "@/screens/Chat";
import { Files } from "@/screens/Files";
import { SettingsScreen } from "@/screens/Settings";
import { Diagnostics } from "@/screens/Diagnostics";
import { Licenses } from "@/screens/Licenses";
import { Outputs } from "@/screens/Outputs";
import { Repos } from "@/screens/Repos";
import { Toasts } from "@/components/Toasts";
import { ApprovalSheet } from "@/components/ApprovalSheet";
import { QuestionSheet } from "@/components/QuestionSheet";
import { ProviderKeySheet, TensorlakeKeySheet, TinyFishKeySheet } from "@/components/KeySheet";
import { S3Sheet } from "@/components/S3Sheet";

export function App() {
  const screen = useStore(currentScreen);
  const phase = useStore((s) => s.runtime?.phase);
  const lang = useStore((s) => resolveLang(s.settings?.language));

  useEffect(() => {
    void init();
    setBackHandler(pop);
    return () => setBackHandler(null);
  }, []);
  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);

  // Only unpacking Ubuntu (first run) or a broken install takes over the screen;
  // chats and settings work while the engines are stopped.
  const blocked = phase === "installing" || phase === "failed";
  const ready = !!phase && !blocked;
  let body;
  if (!phase) body = null;
  else if (blocked && screen.name !== "diagnostics") body = <Setup />;
  else if (screen.name === "chat") body = <Chat key={screen.sessionId} sessionId={screen.sessionId} />;
  else if (screen.name === "files") body = <Files key={`${screen.sessionId}:${screen.path ?? ""}`} sessionId={screen.sessionId} path={screen.path} />;
  else if (screen.name === "settings") body = <SettingsScreen />;
  else if (screen.name === "diagnostics") body = <Diagnostics />;
  else if (screen.name === "licenses") body = <Licenses />;
  else if (screen.name === "outputs") body = <Outputs key={screen.shelf} shelf={screen.shelf} />;
  else if (screen.name === "repos") body = <Repos />;
  else body = <Home />;

  return (
    <div className="mx-auto h-full max-w-3xl">
      {body}
      {/* Approvals for chats not on screen still need an answer. */}
      {ready && screen.name !== "chat" && <ApprovalSheet />}
      {ready && screen.name !== "chat" && <QuestionSheet />}
      <TensorlakeKeySheet />
      <ProviderKeySheet />
      <TinyFishKeySheet />
      <S3Sheet />
      <Toasts />
    </div>
  );
}
