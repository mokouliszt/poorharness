# Modal first, then Upstash Box, Tensorlake and the device

Status: accepted (2026-11, 0.20.0; sandbox sizes revised in 0.20.1 and 0.20.2)

## Context

Modal's Starter plan gives $30 of credits a month (November 2026) and bills a
sandbox for as long as it runs, per CPU core, GiB of memory and GPU, each at the
larger of what was asked for and what was used. Users can set a spend limit in
Modal's own settings. Checked in November 2026 with a test token and the Python
SDK 1.6.3:

- the API is gRPC, and Modal refuses clients it considers too old; its SDKs are
  the only documented way in;
- a sandbox starts in 0.1–0.5 s (7 s the first time an image is built); a
  command takes about 40 ms and a filesystem call about 20 ms; it runs as root,
  Debian 12 on x86_64 with Python 3.11; a T4 sandbox has `nvidia-smi`;
- `snapshot_filesystem()` took 0.5 s for a near-empty sandbox and 3 s for
  96 MB in 2000 files; a sandbox made from the snapshot starts in 0.04 s with
  the whole of `/`;
- `idle_timeout` stops a sandbox 30 s (as set) after its last command ended; a
  background process does not count as activity, a running command does;
- `Workspace.billing.rates()` lists prices as `cpu_hour_cost_sandbox`,
  `mem_gib_hour_cost_sandbox` and `gpu_hour_cost_<gpu>` (T4 $0.59 an hour up to
  B300 $7.10); a GPU's name for a sandbox is `<gpu>` upper-cased with `_` as
  `-` (`a100_80gb` → `A100-80GB`), except `rtx6000` → `RTX-PRO-6000`; a wrong
  name fails at once without cost;
- `Workspace.billing.summary()` gives the month's metered cost on Starter,
  some minutes behind;
- a name can be used again only after `terminate(wait=True)`;
- a sandbox may ask for 0.125 to 64 CPU cores and 128 to 344064 MiB; Modal says
  so only when refusing a request (`CPU request must be >= 125 mCPU`, `<= 64000
  mCPU`, `memory request must be >= 128 MiB`, `<= 344064 MiB`), and does not
  check limits at all (one of 10000 cores was taken). The default limits are
  the request plus 16 cores and no memory cap;
- inside, a sandbox asking for 0.125 cores and allowed 64 sees `nproc` 1 but 64
  CPUs (`os.cpu_count()`; 43 on a T4 host), and 8 busy loops got 7.9 cores; its
  cgroup v1 counters are readable: `cpuacct/cpuacct.usage` (CPU ns since it
  started) and `memory/memory.usage_in_bytes` (memory in use);
- in a sandbox reserving 0.125 cores and allowed Modal's most, this app's own
  `assembleRelease` (Gradle at `-Xmx3g`, R8) took 119 s, 664 CPU-seconds
  (5.6 cores on average) and peaked at 5.1 GiB the first time, with downloads;
  58 s, 390 CPU-seconds and 4.8 GiB rebuilt clean. .NET 10 WinForms and WPF
  template builds for Windows (`EnableWindowsTargeting`) took 5–9 s and stayed
  under 250 MiB; a self-contained single-file WPF publish, 8 s, 22 CPU-seconds
  and 461 MiB.

How Modal refuses work once a spend limit or the credits are used up is not
documented and was not observed: the test account's credits were not spent on
purpose.

## Decision

A chat's place ([Place]) is chosen on its first message, in this order:

1. `modal`: a Modal token is set, Modal has not refused work for usage this
   month, and this month's cost is under 80% of the budget set in Settings
   (default $30, the Starter credits);
2. `box` ([box-first](box-first.md));
3. `cloud`: a Tensorlake key is set ([chat-sandboxes](chat-sandboxes.md));
4. `phone`: a device jail ([phone-sandboxes](phone-sandboxes.md)).

**Reaching Modal** (`modal/ModalAgent.kt`). The first token installs the
`modal` package from PyPI into a venv in the app's Ubuntu
(`/root/.poordsh/modal/venv`; the image carries `python3-venv`), and the app runs
`assets/modal/poordsh-modal.py` there, one JSON request per line like the jail
agent. pip upgrades the SDK once a week, and at once when Modal says it is too
old; a venv a new Ubuntu image cannot run is installed again. The token reaches
the helper over its pipe, never through an environment, and never reaches
opencode, a sandbox or a model ([secrets](secrets.md)). The helper stops 3
minutes after its last call unless sandboxes are up. A gRPC client of the app's
own would have to follow Modal's protocol and version checks by hand; the SDK is
what Modal keeps working.

**One sandbox per chat** (`modal/ModalSandboxes.kt`), in the Modal App
`poorharness`, named `poordsh-<chat id>` and tagged `poordsh=1`, `chat` and
`gpu`; Modal's Debian image with git, curl, zip, unzip, procps and ripgrep;
workspace `/tmp/workspace` as everywhere. Every sandbox reserves 2 cores and
8 GiB (`ModalSandboxes.RESERVE`) and may use up to the most Modal allows (64
cores, 336 GiB in November 2026). The reservation is what Modal guarantees when
its machine is busy: 8 GiB holds an Android build like the one above with room
for a larger Gradle heap, and 2 cores keeps its clean rebuild to a few minutes
(it got 6.7 cores when the machine was free); .NET builds need far less. The
app reads Modal's bounds from its refusals once a week (`bounds` in the
helper), and again when Modal refuses a start for them, so a change at Modal
needs no new release (the reservation is kept within them); without an answer
it uses the values above. A CPU sandbox is ended by Modal after 6 hours, or 5
minutes without a command. Commands run through `sandbox/runner.sh`, as in a
box.

**Between uses, a snapshot.** Modal sandboxes do not pause. When a chat lets
go of its sandbox, the app snapshots its filesystem at once and stops it 60 s
later; the next use starts a sandbox from the snapshot, with every file. A new
snapshot replaces the chat's last one; they expire 30 days after they were
made and are deleted with the chat. A chat whose snapshot is gone starts empty,
and the conversation is handed over as for an expired Tensorlake sandbox. While
a chat holds its sandbox, the app runs a command every 50 s, so a long think or
an open question does not hit the idle stop (the same command reads its CPU time
and memory for the budget, below); once the phone is gone,
the idle stop ends the sandbox. When the app starts, sandboxes of this phone's
chats that a killed app left running are snapshotted and stopped; those of
chats this phone does not have are left alone.

**GPU.** Off unless a chat turns it on (its options sheet); the GPU type is
chosen in Settings from Modal's rates, T4 by default. A GPU sandbox has the
same CPU and memory bounds, and Modal ends it after 2 hours or 2 minutes
without a command. Turning the GPU on or off takes a snapshot
and starts the sandbox again. A GPU is not started without half an hour of it
left under 95% of the budget (`modal_gpu_budget`). A held GPU sandbox that no
tool has used for 10 minutes is snapshotted and stopped, and the next tool call
starts it again.

**The budget.** This month's cost is the larger of Modal's billing summary
(read at most every 5 minutes, and on each chat's first message) and the app's
own count (`files/modal-state.json`), which bills as Modal does: between two
readings of a sandbox, the CPU it used and the larger of its memory then and
now, each at least the request, plus its GPU, at Modal's rates; time not read
yet counts at the request. Under 80% of the budget new chats start in Modal;
from 95% a `modal` chat moves on at its next message, while its files can still
be copied out. At 100% a held sandbox is snapshotted and stopped at its next
reading, even mid-turn, and nothing starts in Modal for the month except to copy
a chat's files out. Any refusal that reads as usage (`ResourceExhaustedError`, or
words about billing, credits, spend, quotas or limits) counts as the month
spent: chats move on, and a tool call refused mid-turn tells the model to have
the user send the message again. Changing the budget in Settings clears that
mark. Modal's own spend limit stays the hard stop.

**Moving out** (`ChatService.moveTo`, shared with Box). The workspace is zipped
in the sandbox (dependency folders left out), copied to the phone, and unpacked
into the next place (stage `move`); the Modal sandbox and its snapshot then go.
If the copy fails, the chat goes on empty with the conversation handed over.
The place changes through `ChatStore.movePlace`: in 0.19.0 a Box chat that
moved out kept `box` as its place, because `setPlace` only sets a place that is
not set yet.

## Consequences

- Chats start in a second and keep everything under `/` between uses; a GPU is
  one switch away. Processes end when a chat lets go, as with Box.
- The first token takes a minute or two to install Modal's SDK (tens of MB),
  and a Python process runs on the phone while Modal sandboxes are up.
- A held sandbox costs its reservation, about $0.48 an hour (2 × $0.1419 +
  8 × $0.024), while the model thinks too: a $30 budget is about 60 hours of
  chats in Modal. Above it, a tool that starts a worker per CPU
  (`os.cpu_count()` is 64) can run 64 cores, about $9 an hour, and memory is
  capped only by Modal's largest sandbox. `nproc` reports the reservation (2),
  so `make -j$(nproc)` and the like stay small.
- The app reads usage every 50 s and memory only at those moments, so a burst
  between readings is caught by CPU time but not by memory, and up to 50 s of
  running past the budget is possible; Modal's summary lags by minutes. Modal's
  own spend limit is what really caps the bill.
- Modal's bounds come from the wording of its refusals; if that changes, the
  app falls back to the last values read, then to those above.
- The usage refusal is recognized by guesswork; a misread one moves a chat out
  early. When a real one is seen, `poordsh-modal.py` (`kind`) and this file
  should say what it looks like.
- Snapshots are images in the user's Modal workspace until they expire or the
  chat is deleted.
- Modal's `_experimental_persistent` sandboxes could one day replace snapshots;
  they are not used while experimental.
- When Modal changes its SDK, `poordsh-modal.py` is the only place that calls
  it; `ModalSandboxesTest` covers the rest with a stand-in
  ([maintenance](../maintenance.md)).
