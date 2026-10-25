import { memo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { call } from "@/lib/bridge";

/** Agent markdown. Links open in the system browser; raw HTML is never rendered. */
export const Markdown = memo(function Markdown({ text }: { text: string }) {
  return (
    <div className="prose-agent">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: ({ href, children }) => (
            <a
              href={href}
              onClick={(e) => {
                e.preventDefault();
                if (href && /^https?:/i.test(href)) void call("app.openUrl", { url: href });
              }}
            >
              {children}
            </a>
          ),
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
});
