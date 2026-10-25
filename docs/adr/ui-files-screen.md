# Files are taken out from the Files screen

Status: accepted (2026-10, 0.2.0; revised in 0.3.0)

## Context

Work stays in the chat's sandbox ([chat-sandboxes](chat-sandboxes.md)).

## Decision

The folder button in a chat opens its workspace; a file can be saved to
`Download/PoorHarness` or shared, and the whole workspace can be taken as a zip.
The screen says that Tensorlake may delete files after 7 days (or, for a chat
on the device, that they stay until the chat is deleted), and tells an expired
sandbox from one that never ran.

## Consequences

Agents never write to shared storage; the user decides what leaves the
sandbox.
