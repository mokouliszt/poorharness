# Upstash Box first, then Tensorlake, then the device

Status: accepted (2026-11, 0.19.0)

## Context

Tensorlake's free plan runs one sandbox at a time (1 vCPU, 1 GB) and may delete
a suspended one after 7 days ([tensorlake-free-plan](tensorlake-free-plan.md)),
so a second chat waits for the first. Upstash Box's free plan, checked in
November 2026 with a test key:

- up to 10 boxes at once, paused ones included (the 11th gets `409 Box limit
  reached. Maximum 10 boxes allowed in the Free plan…`);
- each box 2 vCPU, 4 GB and 10 GB of disk (cgroup `cpu.max` 200000/100000),
  Debian 12 on arm64 with python3, git, curl and Node, running as `boxuser`
  with password-less sudo;
- 5 active CPU hours a month, counted as CPU actually used (100% of 2 cores for
  an hour is 2 hours), so a box waiting for a model costs next to nothing;
- a box pauses by itself after an hour idle; paused, it keeps its whole
  filesystem (`/`, not only `/workspace`), ends its processes, and any API call
  wakes it in under a second;
- boxes are kept until deleted; no expiry is documented.

Upstash documents only its SDKs. The HTTP calls PoorHarness needs (`@upstash/box`
0.7.10 as the reference) and what they did when tried are in `upstash/BoxApi.kt`;
the ones that are easy to get wrong:

- auth is the `X-Box-Api-Key` header; every route but `GET /v2/box/{name}`
  takes the box's id, not its name;
- `POST /v2/box/{id}/exec` takes an argv, has no environment or working-folder
  fields that a command can rely on, and the gateway closes the call after
  300 s (`exec-stream` lasts longer while output flows);
- `GET /v2/box/{id}/files/list` of a missing folder answers `{"files": null}`;
- a snapshot (`POST /v2/box/{id}/snapshots`) took 47 s even for a nearly empty
  box; a box made from it (`POST /v2/box/from-snapshot`) was ready in about 3 s
  with the whole filesystem;
- `GET /v2/box/billing?month=YYYY-MM` gives each box's `cpu_ns` for the month,
  deleted boxes included.

How Box refuses work once the CPU hours are spent is not documented and was not
observed: using up the test account's allowance on purpose was turned down.

## Decision

A chat's place ([Place]) is chosen on its first message, in this order:

1. `box`: an Upstash Box key is set, Box has not refused work for usage this
   month, and the month's CPU is under 95% of the free allowance
   (`BoxSandboxes.FREE_CPU_HOURS`, read from `billing` at most every 5 minutes;
   an unreadable month counts as available);
2. `cloud`: a Tensorlake key is set ([chat-sandboxes](chat-sandboxes.md));
3. `phone`: a device jail ([phone-sandboxes](phone-sandboxes.md)).

**One box per chat** (`upstash/BoxSandboxes.kt`): named `poordsh-<chat id>`,
labelled `poordsh`, created with Upstash's defaults for size and image (nothing
pinned), workspace `/tmp/workspace` as everywhere. It is deleted with the chat.
Boxes without the label or the name prefix are never touched.

**Nothing left running.** A process left in the background keeps using CPU
after its command returns (a `while :` loop ran at 100% until the box was
paused, checked November 2026), and Box pauses an idle box only after an hour.
So:

- the app pauses a box 60 s after its last use, like a Tensorlake sandbox;
  pausing ends every process and keeps the files, and the next call wakes it;
- when the app starts, it pauses its boxes that are awake and unused for 3
  minutes (left by a killed app; one another phone is using stays awake);
- every command records its session in the box, and a watchdog there (started
  by `box-runner.sh` whenever the app checks the box) kills those sessions,
  with whatever they left in the background, once the app has not been heard
  from for 10 minutes, as when the phone dies or goes offline. While a chat
  holds its box, the app tells the box it is there every 2 minutes, so a long
  turn keeps its background processes;
- each command still has its own timeout, and the notification's Stop pauses
  every box at once.

**Ten boxes.** When Box refuses a new box for the box limit, the least recently
used idle box of this app is archived: snapshotted, deleted, and its snapshot
recorded (`files/box-state.json`). That chat's box is made again from the
snapshot on its next use, and the snapshot deleted. The chat being made room for
shows "Making room in Upstash Box" (stage `room`). If every box is busy, or none
is this app's, a new chat starts in the next place and an existing one fails
with `box_full`.

**Running commands.** opencode's tools for a `box:<chat>` sandbox go through the
loopback bridge, like a jail's, and the app calls Box (a box checked when the
turn started is used without asking Box again); the Box key never reaches
opencode, a sandbox or a model ([secrets](secrets.md)). The app runs each
command through `box-runner.sh` (resources, next to `BoxRunner`), so replies
match the jail agent's: output into files (so `cmd &` returns), 1 MiB per
stream, exit 124 past the timeout. A command allowed longer than 270 s runs
apart and is waited for over calls of at most 240 s, under the gateway's 300 s.

**When Box refuses for usage.** Any 402 or 429, or a 400–403 whose message
speaks of usage, CPU, quota, credit or limits (other than the box limit), counts
as a spent allowance: new chats go to the next place until the UTC month
changes. A `box` chat that is refused when its message starts moves for good to
the next place (Tensorlake, else the device; stage `move`): its workspace is
copied over with the file API alone (no command runs; at most 2000 files and
300 MB, dependency folders left out), and the box is then deleted. If the copy
cannot be made, the chat goes on in an empty sandbox with the conversation
handed over as for an expired sandbox, and the box stays until the chat is
deleted. A tool call refused mid-turn tells the model to have the user send the
message again.

## Consequences

- Up to ten chats work at once on free accounts, with more CPU and memory than
  Tensorlake's free sandbox. A Tensorlake key becomes a fallback; without either
  key nothing changes.
- Processes end when a box pauses, unlike a Tensorlake suspend: a dev server
  started in one turn is gone a minute after the turn ends.
- Archiving takes about a minute and, past ten chats, happens whenever an old
  chat comes back. Snapshots count as storage, which the free plan does not
  price (November 2026).
- The usage refusal is recognized by guesswork; a misread refusal moves a chat
  out of Box early, never the other way round. When a real one is seen,
  `BoxApi.classify` and this file should say what it looks like.
- Files moved out of Box lose their executable bits, and anything outside
  `/tmp/workspace` stays behind.
- Boxes run in AWS us-east-1 only, so pages fetched by the agent come from there
  ([web-in-sandbox](web-in-sandbox.md)).
- tensorlake-opencode is still installed: its tools are the ones routed here
  ([opencode-tools-fail-closed](opencode-tools-fail-closed.md)).
- When Upstash changes its API, `BoxApi.kt` is the only place that knows it;
  `BoxLiveTest` (with `UPSTASH_BOX_API_KEY` set) re-checks it
  ([maintenance](../maintenance.md)).
