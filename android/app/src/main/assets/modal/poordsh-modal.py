# PoorHarness's bridge to Modal (docs/adr/modal-first.md).
#
# One JSON request per line on stdin, one reply per line on stdout, matched by
# "id"; requests run in threads, so several can be in flight. It runs in the
# app's Ubuntu with the official Modal SDK from its own venv. The token comes in
# the "init" request, never through the environment, so other processes of the
# app's user cannot read it from /proc.
#
# Replies carry "error" and "kind" when a call fails: "usage" (spending limit,
# credits, quota), "auth", "notfound", "exists" (a running sandbox has the name),
# "invalid", "outdated" (this SDK is too old for Modal) or "other".
import base64
import json
import os
import re
import sys
import threading
import traceback

OUT = threading.Lock()
S = {"sandboxes": {}}
MAX_READ = 32 << 20
USAGE_WORDS = ("billing", "credit", "spend", "budget", "quota", "limit", "payment", "balance", "exhausted")


def send(obj):
    line = json.dumps(obj)
    with OUT:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


def m():
    return S["modal"]


def client():
    return S["client"]


def app():
    if "app" not in S:
        S["app"] = m().App.lookup(S["app_name"], create_if_missing=True, client=client())
    return S["app"]


def base_image():
    # Modal's default Debian image with the tools PoorHarness's own scripts use.
    return m().Image.debian_slim().apt_install(*S["packages"])


def sandbox(sid):
    sb = S["sandboxes"].get(sid)
    if sb is None:
        sb = m().Sandbox.from_id(sid, client=client())
        S["sandboxes"][sid] = sb
    return sb


def op_init(r):
    import modal

    S["modal"] = modal
    S["client"] = modal.Client.from_credentials(r["tokenId"], r["tokenSecret"])
    S["app_name"] = r["app"]
    S["runner"] = r["runner"]
    S["packages"] = r.get("packages") or []
    return {"ok": True, "version": modal.__version__}


def op_check(r):
    ws = m().Workspace.from_context(client=client())
    ws.hydrate()
    return {"workspace": ws.name}


def op_usage(r):
    ws = m().Workspace.from_context(client=client())
    s = ws.billing.summary()
    rates = ws.billing.rates()
    return {
        "start": s.start.isoformat(),
        "end": s.end.isoformat(),
        "meteredUsd": str(s.metered_cost),
        "billedUsd": str(s.billed_cost),
        "rates": {k: str(v) for k, v in rates.items() if not k.startswith("endpoints_")},
    }


def op_find(r):
    try:
        sb = m().Sandbox.from_name(S["app_name"], r["name"], client=client())
    except m().exception.NotFoundError:
        return {"sandbox": None}
    if sb.poll() is not None:
        return {"sandbox": None}
    S["sandboxes"][sb.object_id] = sb
    return {"sandbox": sb.object_id, "tags": sb.get_tags()}


def op_start(r):
    image = m().Image.from_id(r["image"], client=client()) if r.get("image") else base_image()
    kwargs = dict(
        app=app(),
        image=image,
        name=r["name"],
        tags=r.get("tags") or {},
        timeout=r["timeout"],
        idle_timeout=r["idleTimeout"],
        workdir=r["workdir"],
        cpu=tuple(r["cpu"]),
        memory=tuple(r["memory"]),
        client=client(),
    )
    if r.get("gpu"):
        kwargs["gpu"] = r["gpu"]
    sb = m().Sandbox.create(**kwargs)
    S["sandboxes"][sb.object_id] = sb
    return {"sandbox": sb.object_id}


BOUND = re.compile(r"(CPU|memory) request must be ([<>])= (\d+) (mCPU|MiB)")


def op_bounds(r):
    # The smallest and largest CPU and memory a sandbox may ask for. Modal says
    # them only when refusing a request, so ask for values far out of range:
    # "CPU request must be >= 125 mCPU (got 0)", "... <= 64000 mCPU", "memory
    # request must be >= 128 MiB", "... <= 344064 MiB" (November 2026).
    out = {}
    for kw in (dict(cpu=0.001), dict(cpu=10000.0), dict(memory=1), dict(memory=100_000_000)):
        try:
            sb = m().Sandbox.create("true", app=app(), image=base_image(), timeout=10, client=client(), **kw)
            sb.terminate()
        except Exception as e:
            for what, side, n, _ in BOUND.findall(str(e)):
                key = ("cpu" if what == "CPU" else "mem") + ("Min" if side == ">" else "Max")
                out[key] = int(n)
    # mCPU to cores; MiB as they are.
    for k in ("cpuMin", "cpuMax"):
        if k in out:
            out[k] = out[k] / 1000
    return out


def op_list(r):
    out = []
    for sb in m().Sandbox.list(app_id=app().app_id, tags=r.get("tags") or None, client=client()):
        out.append({"sandbox": sb.object_id, "tags": sb.get_tags()})
    return {"sandboxes": out}


def op_running(r):
    return {"running": sandbox(r["sandbox"]).poll() is None}


def op_touch(r):
    # A command, so Modal's idle stop counts from now; it reads the sandbox's
    # CPU time so far (ns) and memory in use (bytes), which the app bills itself
    # with. Either may be missing.
    p = sandbox(r["sandbox"]).exec(
        "sh", "-c", "cat /sys/fs/cgroup/cpuacct/cpuacct.usage /sys/fs/cgroup/memory/memory.usage_in_bytes 2>/dev/null", timeout=30
    )
    lines = p.stdout.read().split()
    p.wait()
    nums = [int(x) for x in lines if x.isdigit()]
    out = {"ok": True}
    if len(nums) == 2:
        out["cpuNs"], out["memBytes"] = nums
    return out


def op_run(r):
    timeout = int(r.get("timeout") or 120)
    p = sandbox(r["sandbox"]).exec(
        "sh", "-c", S["runner"], "poordsh-runner", "run", r.get("cwd") or "/", str(timeout), r["command"],
        timeout=timeout + 30,
    )
    out = p.stdout.read()
    err = p.stderr.read()
    return {"exitCode": p.wait(), "stdout": out, "stderr": err}


def op_read(r):
    fs = sandbox(r["sandbox"]).filesystem
    try:
        if r.get("to"):
            fs.copy_to_local(r["path"], r["to"])
            return {"ok": True}
        data = fs.read_bytes(r["path"])
    except m().exception.SandboxFilesystemNotFoundError:
        return {"missing": True}
    if len(data) > MAX_READ:
        return {"error": "%s is %d bytes, too large to read" % (r["path"], len(data)), "kind": "invalid"}
    return {"ok": True, "dataB64": base64.b64encode(data).decode()}


def op_write(r):
    fs = sandbox(r["sandbox"]).filesystem
    if r.get("from"):
        fs.copy_from_local(r["from"], r["path"])
    else:
        fs.write_bytes(base64.b64decode(r.get("dataB64") or ""), r["path"])
    return {"ok": True}


def op_ls(r):
    try:
        files = sandbox(r["sandbox"]).filesystem.list_files(r["path"])
    except m().exception.SandboxFilesystemNotFoundError:
        return {"entries": []}
    return {
        "entries": [
            {"name": f.name, "is_dir": f.is_dir(), "size": f.size, "modifiedAt": int(f.modified_time * 1000)}
            for f in files
        ]
    }


def op_snapshot(r):
    img = sandbox(r["sandbox"]).snapshot_filesystem(timeout=r.get("timeout") or 300, ttl=r.get("ttl"))
    return {"image": img.object_id}


def op_terminate(r):
    sb = S["sandboxes"].pop(r["sandbox"], None) or m().Sandbox.from_id(r["sandbox"], client=client())
    # Waits until it has stopped, so its name is free for the next start.
    sb.terminate(wait=True)
    return {"ok": True}


def op_delete_image(r):
    import modal.experimental

    try:
        modal.experimental.image_delete(r["image"], client=client())
    except m().exception.NotFoundError:
        pass
    return {"ok": True}


OPS = {
    "init": op_init,
    "check": op_check,
    "touch": op_touch,
    "bounds": op_bounds,
    "usage": op_usage,
    "find": op_find,
    "start": op_start,
    "list": op_list,
    "running": op_running,
    "run": op_run,
    "read": op_read,
    "write": op_write,
    "ls": op_ls,
    "snapshot": op_snapshot,
    "terminate": op_terminate,
    "deleteImage": op_delete_image,
}


def kind_of(e):
    exc = S.get("modal") and S["modal"].exception
    text = str(e).lower()
    if exc is not None:
        if isinstance(e, exc.AuthError):
            return "auth"
        if isinstance(e, exc.VersionError):
            return "outdated"
        if isinstance(e, (exc.NotFoundError, exc.SandboxTerminatedError)):
            return "notfound"
        if isinstance(e, exc.AlreadyExistsError):
            return "exists"
        if isinstance(e, exc.ResourceExhaustedError) or any(w in text for w in USAGE_WORDS):
            return "usage"
        if isinstance(e, exc.InvalidError):
            return "invalid"
    if "unsupported" in text and "client" in text:
        return "outdated"
    return "other"


def handle(line):
    rid = None
    try:
        req = json.loads(line)
        rid = req.get("id")
        op = OPS.get(req.get("op"))
        res = op(req) if op else {"error": "unknown op", "kind": "invalid"}
    except Exception as e:
        res = {"error": "%s: %s" % (type(e).__name__, e), "kind": kind_of(e)}
        if os.environ.get("POORDSH_DEBUG"):
            traceback.print_exc(file=sys.stderr)
    res["id"] = rid
    send(res)


def main():
    send({"id": 0, "ready": True})
    for line in sys.stdin:
        line = line.strip()
        if line:
            threading.Thread(target=handle, args=(line,), daemon=True).start()


main()
