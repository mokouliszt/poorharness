package dev.mokouliszt.poordsh.phone

/**
 * The agent that runs inside a chat's on-device jail and does the work opencode's
 * sandbox tools ask for. It is the device counterpart of a Tensorlake sandbox's
 * process/file API: one JSON object per line on stdin, one JSON reply per line on
 * stdout, matched by `id`. [PhoneSandboxManager] owns the pipe; nothing of the
 * phone outside the jail is reachable from here.
 *
 * Child processes write to temp files, never to the agent's stdout, so a
 * backgrounded command cannot block the control stream or corrupt a reply, and
 * `cmd &` keeps running in the jail after its launching command returns.
 */
object PhoneAgent {
    const val SOURCE = """
import sys, os, json, base64, subprocess, signal, tempfile, threading

MAX_OUT = 1 << 20        # cap each of stdout/stderr returned from a command
MAX_READ = 32 << 20      # refuse to read a file larger than this
OUT = threading.Lock()

def send(obj):
    line = json.dumps(obj)
    with OUT:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()

def run(req):
    cwd = req.get("cwd") or "/tmp/workspace"
    try:
        os.makedirs(cwd, exist_ok=True)
    except OSError:
        cwd = "/"
    timeout = req.get("timeout") or 120
    of = tempfile.NamedTemporaryFile(prefix="run-", suffix=".out", delete=False)
    ef = tempfile.NamedTemporaryFile(prefix="run-", suffix=".err", delete=False)
    try:
        p = subprocess.Popen(["sh", "-c", req["command"]], cwd=cwd,
                             stdin=subprocess.DEVNULL, stdout=of, stderr=ef,
                             start_new_session=True)
        timed_out = False
        try:
            p.wait(timeout=timeout)
            code = p.returncode
        except subprocess.TimeoutExpired:
            timed_out = True
            try:
                os.killpg(p.pid, signal.SIGKILL)
            except OSError:
                pass
            try:
                p.wait(timeout=5)
            except Exception:
                pass
            code = 124
        of.flush(); ef.flush()
        out = tail(of.name)
        err = tail(ef.name)
        if timed_out:
            err += "\n[timed out after %ss]" % timeout
        return {"exitCode": code, "stdout": out, "stderr": err}
    finally:
        of.close(); ef.close()
        for n in (of.name, ef.name):
            try:
                os.unlink(n)
            except OSError:
                pass

def tail(name):
    with open(name, "rb") as f:
        data = f.read(MAX_OUT + 1)
    if len(data) > MAX_OUT:
        return data[:MAX_OUT].decode("utf-8", "replace") + "\n[output truncated]"
    return data.decode("utf-8", "replace")

def read(req):
    path = req["path"]
    size = os.path.getsize(path)
    if size > MAX_READ:
        return {"ok": False, "error": "%s is %d bytes, too large to read" % (path, size)}
    with open(path, "rb") as f:
        data = f.read()
    return {"ok": True, "dataB64": base64.b64encode(data).decode()}

def write(req):
    path = req["path"]
    data = base64.b64decode(req["dataB64"])
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    return {"ok": True, "bytes": len(data)}

def listdir(req):
    path = req["path"]
    entries = []
    for name in sorted(os.listdir(path)):
        full = os.path.join(path, name)
        try:
            is_dir = os.path.isdir(full)
            size = os.path.getsize(full)
        except OSError:
            is_dir, size = False, 0
        entries.append({"name": name, "is_dir": is_dir, "size": size})
    return {"entries": entries}

OPS = {"run": run, "read": read, "write": write, "list": listdir}

def handle(line):
    rid = None
    try:
        req = json.loads(line)
        rid = req.get("id")
        op = OPS.get(req.get("op"))
        res = op(req) if op else {"error": "unknown op"}
    except Exception as e:
        res = {"error": str(e)}
    res["id"] = rid
    send(res)

def main():
    send({"id": 0, "ready": True})
    for line in sys.stdin:
        line = line.strip()
        if line:
            threading.Thread(target=handle, args=(line,), daemon=True).start()

main()
"""
}
