# Agents hand files over through one flat outputs folder

Status: accepted (2026-10, 0.9.0; revised in 0.11.0, opencode only)

## Context

Work stays in the chat's sandbox, and the Files screen takes any file out. That
leaves finding the result to the user. Claude's apps instead give the agent a
folder whose contents reach the person, shown under the reply as files to save.
Agents also tend to scatter results over nested folders, which are awkward to
take out on a phone.

## Decision

- `/tmp/workspace/outputs` in every chat's sandbox (Tensorlake or a device jail)
  is the outputs folder. The app makes it before a chat's first turn in each
  sandbox. opencode is told what it is for through `instructions` in the app's
  config (a file on the phone, `/root/.config/poordsh/instructions.md`, holding
  `Outputs.INSTRUCTIONS` and the user's [system prompt](system-prompt.md)):
  files go straight in it, several files together go as one `.zip`, and the
  reply names them without linking.
- When a turn ends, while it still holds the sandbox, the app lists the folder
  and hands over every top-level file that is new or changed since the chat last
  handed it over (size and modification time; hidden files and names ending in
  `~` stay). The chat keeps, per name, the signature last handed over.
- The folder takes files only, enforced in three places, most reliable last:
  1. the instructions;
  2. opencode's plugin refuses `write`/`edit` into a subfolder and `mkdir` of one
     (a tool error the model sees, so it zips instead);
  3. at collection, a folder is never handed over as a folder: it goes as one
     `<folder>.zip` (zipped in the sandbox with python3, or on the phone for a
     jail), unless a file already has that name. A change anywhere inside it
     hands it over again.
- Where a file is kept ([outputs shelves](s3-storage.md)): on the phone in the
  Ubuntu home, `files/runtime/home/outputs/<chat id>/<name>` (`/root/outputs` in
  Ubuntu), or, when the user picks it in Settings, in their S3-compatible bucket
  under `outputs/<chat id>/<name>`. A file the bucket cannot take is kept on the
  phone instead, so nothing handed over is lost; a file that does not fit on the
  phone (200 MB stay free) is shown as not kept. A later file of the same name
  replaces the kept one.
- The files show under the turn's last answer as one `outputs` part (kept in
  the chat beside the engine's copy, so the next copy does not drop it, and left
  out of what is handed to the other engine on a switch). A tap saves one to
  Download/PoorHarness; the share button offers it to another app. The card says
  when a file is in the bucket.
- Settings lists what each shelf keeps, by chat, with the total size; each file
  can be saved or deleted, or all of them at once. Deleting a chat leaves its
  kept files, listed under "Deleted chat".

## Consequences

- The model decides what to hand over; what it leaves elsewhere stays reachable
  from the Files screen.
- Handing over a large file makes the end of a turn take longer; the chat says
  so while it happens.
- A folder created by other means (`cp -r`, a script) is still handed over as a
  zip. A file still being written when the turn ends (a background job) goes in
  whatever state it is in, and again at a later turn once it changed.
- Verified with opencode 1.19.0 (Infron DeepSeek V4.1 Flash in a Tensorlake
  sandbox: the plugin refused a nested `mkdir`, the model zipped the CSVs);
  collection from a Tensorlake sandbox into a B2 bucket by `OutputsLiveTest`.
