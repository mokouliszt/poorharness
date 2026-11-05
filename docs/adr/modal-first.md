# Modal first, then Upstash Box, Tensorlake and the device

Status: accepted (2026-11, 0.20.0)

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
- a name can be used again only after `terminate(wait=True)`.

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
workspace `/tmp/workspace` as everywhere. A CPU sandbox asks for 0.125 of a
core and 256 MiB and may use up to 2 cores and 4 GiB; Modal ends it after 6
hours, or 5 minutes without a command. Commands run through
`sandbox/runner.sh`, as in a box.

**Between uses, a snapshot.** Modal sandboxes do not pause. When a chat lets
go of its sandbox, the app snapshots its filesystem at once and stops it 60 s
later; the next use starts a sandbox from the snapshot, with every file. A new
snapshot replaces the chat's last one; they expire 30 days after they were
made and are deleted with the chat. A chat whose snapshot is gone starts empty,
and the conversation is handed over as for an expired Tensorlake sandbox. While
a chat holds its sandbox, the app runs an empty command every 50 s, so a long
think or an open question does not hit the idle stop; once the phone is gone,
the idle stop ends the sandbox. When the app starts, sandboxes of this phone's
chats that a killed app left running are snapshotted and stopped; those of
chats this phone does not have are left alone.

**GPU.** Off unless a chat turns it on (its options sheet); the GPU type is
chosen in Settings from Modal's rates, T4 by default. A GPU sandbox asks for
0.5 of a core and 2 GiB, may use 4 cores and 16 GiB, and Modal ends it after 2
hours or 2 minutes without a command. Turning the GPU on or off takes a snapshot
and starts the sandbox again. A GPU is not started without half an hour of it
left under 95% of the budget (`modal_gpu_budget`). A held GPU sandbox that no
tool has used for 10 minutes is snapshotted and stopped, and the next tool call
starts it again.

**The budget.** This month's cost is the larger of Modal's billing summary
(read at most every 5 minutes, and on each chat's first message) and the app's
own count: each sandbox's running time at what it asked for, at Modal's rates
(`files/modal-state.json`). Under 80% of the budget new chats start in Modal;
from 95% a `modal` chat moves on at its next message, while its files can still
be copied out. Any refusal that reads as usage (`ResourceExhaustedError`, or
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
- The app's count covers what sandboxes asked for, not what they used beyond
  it, and Modal's summary lags: a heavy GPU turn can pass 95% before the app
  sees it. Modal's own spend limit is what really caps the bill.
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
