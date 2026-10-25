# Attached files go to the sandbox, and to the model when it can take them

Status: accepted (2026-10, 0.4.0; revised in 0.11.0)

## Context

A message can carry files of any type and size. Models differ in what they
take directly (images, sometimes PDFs) and providers cap how big an inline file
may be. The agent works in the chat's sandbox, so that is where a file is useful
whatever the model can read.

## Decision

- Picking a file (`attachments.add`, the system picker) copies it into
  `files/attachments` at once, after checking it fits: on the phone's storage,
  and for a Tensorlake chat in its sandbox (`df` there, waking a paused one; a
  chat with no sandbox yet assumes a new one's free space). A file that does
  not fit is refused right there, with the sizes.
- Sending puts every file in `/tmp/workspace/attachments/` of the chat's
  sandbox (a rename into a device jail; a streamed upload to Tensorlake, with
  progress, after one more space check), under a unique name. A text note,
  hidden like the hand-off context, lists each path; the UI turns it back into
  chips, so the engine's history stays the record.
- A file also goes to the model directly when the model says it takes that kind
  ("inputs" from the engine's catalog) and it fits common provider limits
  (`Attachments.inlineFor`): PNG, JPEG, GIF and WebP images (up to 5 MB as a
  data URL, larger ones scaled down to 2048 px) and, for models that take them,
  PDFs up to 10 MB. Everything else is only uploaded and the model learns where it is.
- Unsent files are dropped on the next start; a send that fails keeps them
  (and does not upload them twice).

## Consequences

There is no size limit of the app's own. Formats a provider would refuse
(HEIC, audio, video, archives) never fail a turn; the agent can still open
them in the sandbox. The limits are fixed numbers in one place, chosen low
enough for every common provider; models.dev does not publish them.
