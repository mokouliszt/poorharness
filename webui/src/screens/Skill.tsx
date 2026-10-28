import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  ChevronRight,
  ChevronsDownUp,
  ChevronsUpDown,
  Copy,
  File as FileIcon,
  FileCode,
  FileImage,
  FileJson,
  FileText,
  Folder,
  FolderOpen,
  LoaderCircle,
  Trash2,
  WrapText,
} from "lucide-react";
import { call } from "@/lib/bridge";
import { deleteSkill, onBackClose, pop, push, toast } from "@/lib/store";
import { describeError, useT } from "@/lib/i18n";
import { fileSize } from "@/lib/format";
import { cn } from "@/lib/cn";
import type { SkillContent, SkillFiles } from "@/lib/types";
import { TopBar } from "@/components/TopBar";
import { Markdown } from "@/components/Markdown";
import { Sheet } from "@/components/ui/Sheet";
import { Button } from "@/components/ui/Button";

/** A folder of the tree, built from the flat list the host sends. */
interface Dir {
  name: string;
  path: string;
  dirs: Dir[];
  files: { name: string; path: string; size: number }[];
  count: number;
}

function buildTree(files: SkillFiles["files"]): Dir {
  const root: Dir = { name: "", path: "", dirs: [], files: [], count: 0 };
  for (const f of files) {
    const parts = f.path.split("/");
    let dir = root;
    dir.count++;
    for (let i = 0; i < parts.length - 1; i++) {
      const path = parts.slice(0, i + 1).join("/");
      let next = dir.dirs.find((d) => d.path === path);
      if (!next) {
        next = { name: parts[i], path, dirs: [], files: [], count: 0 };
        dir.dirs.push(next);
      }
      next.count++;
      dir = next;
    }
    dir.files.push({ name: parts[parts.length - 1], path: f.path, size: f.size });
  }
  const sort = (d: Dir) => {
    d.dirs.sort((a, b) => a.name.localeCompare(b.name));
    d.files.sort((a, b) => a.name.localeCompare(b.name));
    d.dirs.forEach(sort);
  };
  sort(root);
  return root;
}

function allDirs(d: Dir): string[] {
  return d.dirs.flatMap((x) => [x.path, ...allDirs(x)]);
}

/** Up to this many files the tree opens whole; a bigger one opens at its top level. */
const OPEN_ALL_UP_TO = 40;

const CODE = /\.(py|js|mjs|cjs|ts|tsx|jsx|sh|bash|rb|go|rs|java|kt|c|h|cpp|css|scss|html|sql|ps1|bat)$/i;
const DATA = /\.(json|ya?ml|toml|xml|csv|tsv|ini|cfg)$/i;
const IMAGE = /\.(png|jpe?g|gif|webp|svg|ico)$/i;

function iconFor(name: string) {
  if (IMAGE.test(name)) return FileImage;
  if (DATA.test(name)) return FileJson;
  if (CODE.test(name)) return FileCode;
  if (/\.(md|markdown|txt|rst)$/i.test(name)) return FileText;
  return FileIcon;
}

/**
 * An installed skill as it sits on the phone: every file and folder, to check
 * that nothing was lost in the archive. A file opens in [SkillFile]; the skill
 * is deleted from here.
 */
export function Skill({ name }: { name: string }) {
  const t = useT();
  const [data, setData] = useState<SkillFiles | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [closed, setClosed] = useState<Set<string>>(new Set());
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    call<SkillFiles>("skills.files", { name }).then((d) => {
      if (d.files.length > OPEN_ALL_UP_TO) setClosed(new Set(allDirs(buildTree(d.files)).filter((p) => p.includes("/"))));
      setData(d);
    }, (e: Error) => setError(e.message));
  }, [name]);
  useEffect(() => (confirming && !deleting ? onBackClose(() => setConfirming(false)) : undefined), [confirming, deleting]);

  const tree = useMemo(() => (data ? buildTree(data.files) : null), [data]);
  const dirs = useMemo(() => (tree ? allDirs(tree) : []), [tree]);
  // Anything closed: the button opens everything; otherwise it closes everything.
  const anyClosed = closed.size > 0;
  const toggle = (path: string) =>
    setClosed((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });

  let body: ReactNode;
  if (error) body = <Center>{describeError(t, error)}</Center>;
  else if (!data || !tree) {
    body = (
      <Center>
        <LoaderCircle size={18} className="animate-spin" />
      </Center>
    );
  } else {
    body = (
      <div className="scroll-quiet min-h-0 flex-1 overflow-y-auto px-2 pb-6">
        <div className="px-3 pb-4">
          {data.description && <p className="phrase text-sm text-mute">{data.description}</p>}
          <div className="mt-3 flex flex-wrap gap-2">
            <Stat>{count(t, "files", data.files.length)}</Stat>
            {dirs.length > 0 && <Stat>{count(t, "folders", dirs.length)}</Stat>}
            <Stat>{fileSize(data.bytes)}</Stat>
          </div>
        </div>
        <div>
          <Branch dir={tree} depth={0} closed={closed} toggle={toggle} open={(path) => push({ name: "skillFile", skill: name, path })} />
        </div>
        <div className="px-1 pt-6">
          <button
            onClick={() => setConfirming(true)}
            className="flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left text-danger active:bg-surface"
          >
            <Trash2 size={17} className="shrink-0" />
            <span className="text-base">{t("skill.delete")}</span>
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <TopBar
        title={<span className="font-mono">{name}</span>}
        right={
          dirs.length > 0 && (
            <IconButton
              label={t(anyClosed ? "skill.expandAll" : "skill.collapseAll")}
              onClick={() => setClosed(anyClosed ? new Set() : new Set(dirs))}
            >
              {anyClosed ? <ChevronsUpDown size={18} /> : <ChevronsDownUp size={18} />}
            </IconButton>
          )
        }
      />
      {body}
      <Sheet open={confirming} onClose={() => !deleting && setConfirming(false)} title={t("skills.removeTitle", { name })}>
        <div className="space-y-3 px-5 pb-2">
          <Button
            variant="danger"
            className="w-full"
            disabled={deleting}
            onClick={() => {
              setDeleting(true);
              deleteSkill(name)
                .then(() => {
                  setConfirming(false);
                  pop();
                })
                .catch((e: Error) => toast(describeError(t, e.message), "error"))
                .finally(() => setDeleting(false));
            }}
          >
            {t("common.delete")}
          </Button>
          <Button variant="ghost" className="w-full" disabled={deleting} onClick={() => setConfirming(false)}>
            {t("common.cancel")}
          </Button>
        </div>
      </Sheet>
    </div>
  );
}

function Branch({
  dir,
  depth,
  closed,
  toggle,
  open,
}: {
  dir: Dir;
  depth: number;
  closed: Set<string>;
  toggle: (path: string) => void;
  open: (path: string) => void;
}) {
  const t = useT();
  // Folders first, as in any file browser; at the top, SKILL.md leads (the agent reads it first).
  const lead = depth === 0 ? dir.files.filter((f) => f.name === "SKILL.md") : [];
  const rest = dir.files.filter((f) => !lead.includes(f));
  const fileRow = (f: Dir["files"][number]) => {
    const Icon = iconFor(f.name);
    return (
          <button
            key={f.path}
            onClick={() => open(f.path)}
            className="flex w-full items-center gap-2.5 rounded-lg px-2 py-2 text-left active:bg-surface"
          >
            {/* Where a folder has its chevron, so names line up. */}
            <span className="w-4 shrink-0" aria-hidden />
            <Icon size={17} className="shrink-0 text-mute" />
            <span className={cn("min-w-0 flex-1 truncate font-mono text-sm", f.name === "SKILL.md" ? "text-paper" : "text-ink")}>{f.name}</span>
            <span className="shrink-0 font-mono text-xs text-mute">{fileSize(f.size)}</span>
          </button>
    );
  };
  return (
    <div className={cn(depth > 0 && "ml-[21px] border-l border-line pl-1")}>
      {lead.map(fileRow)}
      {dir.dirs.map((d) => {
        const isOpen = !closed.has(d.path);
        return (
          <div key={d.path}>
            <button
              aria-expanded={isOpen}
              onClick={() => toggle(d.path)}
              className="flex w-full items-center gap-2.5 rounded-lg px-2 py-2 text-left active:bg-surface"
            >
              <span className="flex w-4 shrink-0 justify-center">
                <ChevronRight size={14} className={cn("text-mute transition-transform", isOpen && "rotate-90")} />
              </span>
              {isOpen ? <FolderOpen size={17} className="shrink-0 text-ink" /> : <Folder size={17} className="shrink-0 text-ink" />}
              <span className="min-w-0 flex-1 truncate font-mono text-sm text-paper">{d.name}</span>
              <span className="shrink-0 text-xs text-mute">{count(t, "files", d.count)}</span>
            </button>
            {isOpen && <Branch dir={d} depth={depth + 1} closed={closed} toggle={toggle} open={open} />}
          </div>
        );
      })}
      {rest.map(fileRow)}
    </div>
  );
}

/**
 * One file of an installed skill, read-only: text with line numbers (Markdown can
 * also be shown rendered), an image as itself, anything else by its size.
 */
export function SkillFile({ skill, path }: { skill: string; path: string }) {
  const t = useT();
  const [content, setContent] = useState<SkillContent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [wrap, setWrap] = useState(false);
  const [rendered, setRendered] = useState(false);
  const [dims, setDims] = useState<{ w: number; h: number } | null>(null);
  const fileName = path.slice(path.lastIndexOf("/") + 1);
  const isMarkdown = /\.(md|markdown)$/i.test(fileName);

  useEffect(() => {
    call<SkillContent>("skills.read", { name: skill, path }).then(setContent, (e: Error) => setError(e.message));
  }, [skill, path]);

  const text = content?.kind === "text" ? content.text : null;
  const lines = useMemo(() => (text == null ? [] : text.replace(/\n$/, "").split("\n")), [text]);

  let body: ReactNode;
  if (error) body = <Center>{describeError(t, error)}</Center>;
  else if (!content) {
    body = (
      <Center>
        <LoaderCircle size={18} className="animate-spin" />
      </Center>
    );
  } else if (content.kind === "image") {
    body = (
      <div className="flex min-h-0 flex-1 items-center justify-center overflow-auto p-4">
        <img
          src={`data:${content.mime};base64,${content.data}`}
          alt={fileName}
          onLoad={(e) => setDims({ w: e.currentTarget.naturalWidth, h: e.currentTarget.naturalHeight })}
          // An icon a few pixels wide is scaled up, without blurring, so it can be seen.
          style={dims && dims.w < 96 ? { width: 96, imageRendering: "pixelated" } : undefined}
          className="max-h-full max-w-full rounded-lg bg-surface object-contain"
        />
      </div>
    );
  } else if (content.kind === "binary") {
    body = (
      <Center>
        <FileIcon size={28} />
        <p>{t("skill.noPreview")}</p>
      </Center>
    );
  } else if (isMarkdown && rendered) {
    // The frontmatter (a skill's name and description) is metadata: shown as itself, above the prose.
    const front = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(content.text);
    body = (
      <div className="scroll-quiet min-h-0 flex-1 overflow-y-auto px-5 pb-8">
        {front && (
          <pre className="mb-4 whitespace-pre-wrap rounded-xl border border-line px-3 py-2.5 font-mono text-xs leading-5 text-mute [overflow-wrap:anywhere]">
            {front[1]}
          </pre>
        )}
        <Markdown text={front ? content.text.slice(front[0].length) : content.text} />
      </div>
    );
  } else {
    body = (
      <div className="scroll-quiet min-h-0 flex-1 overflow-auto pb-8">
        {wrap ? (
          <div className="grid grid-cols-[auto_1fr] font-mono text-[13px] leading-5">
            {lines.map((line, i) => (
              <Line key={i} n={i + 1}>
                {line}
              </Line>
            ))}
          </div>
        ) : (
          // Two blocks, not one element per line: a 256 KB file stays light in the WebView.
          <div className="flex w-max min-w-full font-mono text-[13px] leading-5">
            <pre className="sticky left-0 select-none border-r border-line bg-void pl-4 pr-3 text-right text-mute/60">
              {lines.map((_, i) => i + 1).join("\n")}
            </pre>
            <pre className="pl-3 pr-4 text-ink">{text}</pre>
          </div>
        )}
        {content.truncated && <p className="px-4 pt-3 text-xs text-mute">{t("skill.truncated", { size: fileSize(content.size) })}</p>}
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <TopBar
        title={<span className="font-mono">{fileName}</span>}
        right={
          content?.kind === "text" && (
            <>
              {!rendered && (
                <IconButton label={t("skill.wrap")} pressed={wrap} onClick={() => setWrap((w) => !w)}>
                  <WrapText size={18} />
                </IconButton>
              )}
              <IconButton
                label={t("common.copy")}
                onClick={() => void call("app.copy", { text: content.text }).then(() => toast(t("skill.copied")))}
              >
                <Copy size={17} />
              </IconButton>
            </>
          )
        }
      />
      <div className="flex shrink-0 items-center gap-3 px-4 pb-3">
        <span className="min-w-0 flex-1 truncate font-mono text-xs text-mute">
          {skill}/{path}
          {content ? ` · ${fileSize(content.size)}` : ""}
          {dims ? ` · ${dims.w} × ${dims.h}` : ""}
        </span>
        {isMarkdown && content?.kind === "text" && (
          <div className="flex shrink-0 rounded-full bg-surface p-0.5 text-xs" role="group">
            <Segment on={!rendered} onClick={() => setRendered(false)}>
              {t("skill.source")}
            </Segment>
            <Segment on={rendered} onClick={() => setRendered(true)}>
              {t("skill.preview")}
            </Segment>
          </div>
        )}
      </div>
      {body}
    </div>
  );
}

function Line({ n, children }: { n: number; children: string }) {
  return (
    <>
      <span className="select-none border-r border-line pl-4 pr-3 text-right text-mute/60">{n}</span>
      <span className="whitespace-pre-wrap pl-3 pr-4 text-ink [overflow-wrap:anywhere]">{children || " "}</span>
    </>
  );
}

function Segment({ on, onClick, children }: { on: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      onClick={onClick}
      aria-pressed={on}
      className={cn("h-7 rounded-full px-3 transition-colors", on ? "bg-paper text-void" : "text-mute")}
    >
      {children}
    </button>
  );
}

/** "1 file", "3 files" (Japanese has one form). */
export function count(t: ReturnType<typeof useT>, what: "files" | "folders", n: number): string {
  if (what === "files") return n === 1 ? t("skill.oneFile") : t("skill.files", { n });
  return n === 1 ? t("skill.oneFolder") : t("skill.folders", { n });
}

function Stat({ children }: { children: ReactNode }) {
  return <span className="rounded-full border border-line px-2.5 py-0.5 font-mono text-xs text-ink">{children}</span>;
}

function Center({ children }: { children: ReactNode }) {
  return <div className="flex flex-1 flex-col items-center justify-center gap-3 px-6 text-center text-sm text-mute">{children}</div>;
}

function IconButton(props: { label: string; pressed?: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      onClick={props.onClick}
      aria-label={props.label}
      aria-pressed={props.pressed}
      className={cn(
        "flex h-10 w-10 items-center justify-center rounded-full active:bg-surface",
        props.pressed ? "bg-surface text-paper" : "text-mute",
      )}
    >
      {props.children}
    </button>
  );
}
