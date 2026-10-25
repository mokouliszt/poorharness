# PoorHarness: opencode's webfetch and websearch, run inside the chat's sandbox
# (docs/adr/web-in-sandbox.md). poordsh-sandboxes.js sends this file to the
# sandbox with base64-encoded arguments on the command line and reads one line
# back: MARK + JSON. Python 3 standard library only; no key is ever passed here.
#
#   fetch  {kind, url, format: text|markdown|html, timeout}
#   search {kind, query, numResults, type, livecrawl, contextMaxCharacters}
#
# Answers {"ok": true, ...} or {"ok": false, "error": "...", "limit": bool}. "limit"
# marks a usage limit (HTTP 402/429, or a limit message from the search service);
# only that sends the plugin on to its TinyFish fallback.
import base64
import html.parser
import json
import re
import signal
import sys
import urllib.error
import urllib.parse
import urllib.request

MARK = "\x01POORDSH-WEB\x01"
MAX_BYTES = 5 * 1024 * 1024
BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
ACCEPT = {
    "markdown": "text/markdown;q=1.0, text/x-markdown;q=0.9, text/plain;q=0.8, text/html;q=0.7, */*;q=0.1",
    "text": "text/plain;q=1.0, text/markdown;q=0.9, text/html;q=0.8, */*;q=0.1",
    "html": "text/html;q=1.0, application/xhtml+xml;q=0.9, text/plain;q=0.8, text/markdown;q=0.7, */*;q=0.1",
}
EXA = "https://mcp.exa.ai/mcp"
LIMIT = re.compile(r"rate.?limit|quota|usage limit|too many requests|limit exceeded|exceeded", re.I)

SKIP = {"script", "style", "noscript", "template", "svg", "head", "iframe", "object", "embed", "canvas", "video", "audio"}
BLOCK = {"p", "div", "section", "article", "main", "header", "footer", "nav", "aside", "form", "figure", "figcaption", "details", "summary", "dl", "dt", "dd", "address", "fieldset"}


class Converter(html.parser.HTMLParser):
    """HTML to Markdown (markup=True) or plain text (markup=False)."""

    def __init__(self, base, markup):
        super().__init__(convert_charrefs=True)
        self.base, self.markup = base, markup
        self.out, self.skip, self.pre, self.lists, self.links = [], 0, 0, [], []

    def emit(self, text):
        self.out.append(text)

    def handle_starttag(self, tag, attrs):
        if tag in SKIP:
            self.skip += 1
            return
        if self.skip:
            return
        attrs = dict(attrs)
        if re.fullmatch(r"h[1-6]", tag):
            self.emit("\n\n" + ("#" * int(tag[1]) + " " if self.markup else ""))
        elif tag in BLOCK:
            self.emit("\n\n" if tag == "p" else "\n")
        elif tag in ("ul", "ol"):
            self.emit("" if self.lists else "\n")
            self.lists.append([tag, 0])
        elif tag == "li":
            depth = max(len(self.lists) - 1, 0)
            if self.lists and self.lists[-1][0] == "ol":
                self.lists[-1][1] += 1
                bullet = f"{self.lists[-1][1]}. "
            else:
                bullet = "- "
            self.emit("\n" + "  " * depth + bullet)
        elif tag == "br":
            self.emit("\n")
        elif tag == "hr":
            self.emit("\n\n" + ("---" if self.markup else "") + "\n\n")
        elif tag == "pre":
            self.pre += 1
            self.emit("\n\n" + ("```\n" if self.markup else ""))
        elif tag == "blockquote":
            self.emit("\n\n" + ("> " if self.markup else ""))
        elif tag == "tr":
            self.emit("\n" + ("| " if self.markup else ""))
        elif tag in ("td", "th"):
            pass
        elif self.markup and tag == "a":
            href = attrs.get("href") or ""
            if href and not href.startswith(("#", "javascript:")):
                self.links.append(urllib.parse.urljoin(self.base, href))
                self.emit("[")
            else:
                self.links.append(None)
        elif self.markup and tag in ("strong", "b"):
            self.emit("**")
        elif self.markup and tag in ("em", "i"):
            self.emit("*")
        elif self.markup and tag == "code" and not self.pre:
            self.emit("`")
        elif tag == "img" and attrs.get("alt"):
            self.emit(attrs["alt"])

    def handle_endtag(self, tag):
        if tag in SKIP:
            self.skip = max(self.skip - 1, 0)
            return
        if self.skip:
            return
        if re.fullmatch(r"h[1-6]", tag) or tag == "p" or tag == "blockquote":
            self.emit("\n\n")
        elif tag in BLOCK:
            self.emit("\n")
        elif tag in ("ul", "ol"):
            if self.lists:
                self.lists.pop()
            self.emit("\n")
        elif tag == "pre":
            self.pre = max(self.pre - 1, 0)
            self.emit(("\n```" if self.markup else "") + "\n\n")
        elif tag in ("td", "th"):
            self.emit(" | " if self.markup else "\t")
        elif self.markup and tag == "a" and self.links:
            href = self.links.pop()
            if href:
                self.emit(f"]({href})")
        elif self.markup and tag in ("strong", "b"):
            self.emit("**")
        elif self.markup and tag in ("em", "i"):
            self.emit("*")
        elif self.markup and tag == "code" and not self.pre:
            self.emit("`")

    def handle_data(self, data):
        if self.skip:
            return
        self.emit(data if self.pre else re.sub(r"\s+", " ", data))

    def result(self):
        text = "".join(self.out)
        text = re.sub(r"\[\s*\]\([^)]*\)", "", text)  # a link around an image only
        text = re.sub(r"[ \t]+\n", "\n", text)
        text = re.sub(r"\n{3,}", "\n\n", text)
        return text.strip()


def convert(body, base, markup):
    conv = Converter(base, markup)
    conv.feed(body)
    conv.close()
    text = conv.result()
    if not text and body.strip():  # unbalanced markup swallowed everything
        text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", body)).strip()
    return text


def charset_of(content_type, raw):
    m = re.search(r"charset=([\w.-]+)", content_type or "", re.I) or re.search(rb"<meta[^>]+charset=[\"']?([\w.-]+)", raw[:4096], re.I)
    if m:
        name = m.group(1)
        return name.decode("ascii", "ignore") if isinstance(name, bytes) else name
    return "utf-8"


def open_url(url, headers, timeout):
    req = urllib.request.Request(url, headers=headers)
    return urllib.request.urlopen(req, timeout=timeout)


def fetch(a):
    url = str(a.get("url") or "")
    if not re.match(r"https?://", url):
        return {"ok": False, "error": "URL must start with http:// or https://"}
    if url.startswith("http://") and not a.get("keepHttp"):  # keepHttp: tests against a local server only
        url = "https://" + url[len("http://"):]
    fmt = a.get("format") if a.get("format") in ACCEPT else "markdown"
    timeout = min(max(float(a.get("timeout") or 30), 1), 100)
    headers = {"User-Agent": BROWSER, "Accept": ACCEPT[fmt], "Accept-Language": "en-US,en;q=0.9"}
    try:
        resp = open_url(url, headers, timeout)
    except urllib.error.HTTPError as e:
        if e.code != 403 or e.headers.get("cf-mitigated") != "challenge":
            raise
        # A bot challenge: an honest client name sometimes gets through.
        resp = open_url(url, {**headers, "User-Agent": "PoorHarness"}, timeout)
    with resp:
        status = getattr(resp, "status", 200)
        if status >= 400:
            return {"ok": False, "error": f"Request failed with status code: {status}"}
        raw = resp.read(MAX_BYTES + 1)
        ctype = (resp.headers.get("content-type") or "").split(";")[0].strip().lower()
        final = resp.geturl()
    if len(raw) > MAX_BYTES:
        return {"ok": False, "error": "Response too large (exceeds 5MB limit)"}
    if ctype.startswith("image/") and "svg" not in ctype:
        return {"ok": True, "kind": "image", "mime": ctype, "b64": base64.b64encode(raw).decode("ascii"), "url": final}
    textual = ctype.startswith("text/") or any(k in ctype for k in ("json", "xml", "javascript", "yaml", "x-www-form"))
    try:
        text = raw.decode(charset_of(resp.headers.get("content-type"), raw), "replace" if textual else "strict")
    except (UnicodeDecodeError, LookupError):
        text = None
    if text is None or (not textual and ctype and "\x00" in text):
        return {"ok": False, "error": f"Unsupported content type: {ctype or 'unknown'}. Download it with bash (curl -o) and read the file instead."}
    if "html" in ctype and fmt != "html":
        text = convert(text, final, fmt == "markdown")
    return {"ok": True, "kind": "text", "text": text, "url": final, "contentType": ctype}


def search(a):
    query = str(a.get("query") or "").strip()
    if not query:
        return {"ok": False, "error": "query is required"}
    arguments = {
        "query": query,
        "type": a.get("type") or "auto",
        "numResults": a.get("numResults") or 8,
        "livecrawl": a.get("livecrawl") or "fallback",
    }
    if a.get("contextMaxCharacters"):
        arguments["contextMaxCharacters"] = a["contextMaxCharacters"]
    rpc = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "web_search_exa", "arguments": arguments}}
    headers = {
        "User-Agent": BROWSER,
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    req = urllib.request.Request(a.get("endpoint") or EXA, data=json.dumps(rpc).encode(), headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=25) as resp:
        raw = resp.read(MAX_BYTES).decode("utf-8", "replace")
    message = None
    for line in raw.splitlines():
        if line.startswith("data:"):
            try:
                message = json.loads(line[5:].strip())
            except ValueError:
                continue
            if "result" in message or "error" in message:
                break
    if message is None:
        try:
            message = json.loads(raw)
        except ValueError:
            return {"ok": False, "error": "Search answered in an unknown format"}
    if "error" in message:
        why = str((message["error"] or {}).get("message", message["error"]))
        return {"ok": False, "error": "Search failed: " + why, "limit": bool(LIMIT.search(why))}
    result = message.get("result") or {}
    text = "\n".join(c.get("text", "") for c in result.get("content", []) if c.get("type") == "text").strip()
    if result.get("isError"):
        return {"ok": False, "error": "Search failed: " + (text[:300] or "unknown error"), "limit": bool(LIMIT.search(text))}
    if not text:
        return {"ok": False, "error": "No search results found."}
    return {"ok": True, "kind": "text", "text": text}


def describe(e):
    if isinstance(e, urllib.error.HTTPError):
        return f"Request failed with status code: {e.code} {e.reason}"
    if isinstance(e, urllib.error.URLError):
        return f"Request failed: {e.reason}"
    return f"{type(e).__name__}: {e}"


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except AttributeError:
        pass
    try:
        args = json.loads(base64.b64decode(sys.argv[1]))

        def on_alarm(*_):
            raise TimeoutError("timed out")

        signal.signal(signal.SIGALRM, on_alarm)
        signal.alarm(int(min(max(float(args.get("timeout") or 30), 1), 100)) + 5)
        out = fetch(args) if args.get("kind") == "fetch" else search(args)
    except Exception as e:  # reported to the plugin, which decides what to do next
        limit = isinstance(e, urllib.error.HTTPError) and e.code in (402, 429)
        out = {"ok": False, "error": describe(e), "limit": limit}
    sys.stdout.write("\n" + MARK + json.dumps(out, ensure_ascii=False) + "\n")


main()
