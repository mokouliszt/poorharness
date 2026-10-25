# The engine updates without an app update

Status: accepted (2026-10, revised in 0.2.0, 0.4.1, 0.7.0 and 0.11.0)

## Context

opencode releases often. Fixes and new features should not wait for a
PoorHarness release.

## Decision

opencode can be updated in the app from the npm registry, where the project
publishes its official builds (`android/.../update/BinaryUpdater.kt`).
Downloads are checked against the registry's sha512 integrity, go to the phone,
and stay on the major version the app was built with (the Tensorlake plugin
targets 1.x). "Use default" undoes an update; it stays next to "Update to …"
when a newer release follows one already installed here. Between an update
installed here and the version a later app release ships with, the newer wins,
so an app update never leaves opencode on an older build.

opencode is checked each time the app comes up (`EngineUpdates`, from the
activity's `onStart`; a return within ten minutes of the last check does not
check again), so Settings shows a newer release without a tap. With "Auto
update" on (off by default), what is newer is installed right after the
check, and at once when the switch is turned on, and swapped in once opencode
is idle, the same steps as "Update to …". The result goes to the UI as
`updates.changed`, and `updates.last` returns it to a UI that was not attached
yet.

## Consequences

A new version can change its protocol; with automatic updates it arrives
without anyone choosing it, which is why the switch is off by default.
"Revert to default" with the switch on lasts until the next launch finds the
newer release again.
[`docs/maintenance.md`](../maintenance.md) lists what to re-check when the
bundled version moves.
