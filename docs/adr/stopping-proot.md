# Stopping PRoot means SIGQUIT

Status: accepted (2026-10)

## Context

PRoot ignores SIGTERM (and most other signals); it handles SIGQUIT (and fatal
signals) by sending SIGKILL to every process it traces and exiting. On
Android, `Process.destroy()` and `destroyForcibly()` both send SIGTERM (libcore
`UNIXProcess_md.c`), so before 0.1.2 a "stopped" opencode server and its PRoot
kept running in the background, and each restart left another pair behind.
SIGKILL on PRoot alone is no better: its tracees are detached and keep running.

## Decision

`GuestProcess.stop()` sends SIGQUIT to the PRoot pid (read from
`Process.toString()`, which prints `pid=`), waits 2 s, and only then SIGKILLs
every process whose `TracerPid` is PRoot, then PRoot itself.

## Consequences

Verified on Linux with PRoot 5.1: SIGTERM leaves the guest running, SIGQUIT
leaves nothing. Guests are never stopped with `Process.destroy()` alone.
