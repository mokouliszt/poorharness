# Licensing

Status: accepted (2026-10)

## Context

The APK bundles third-party programs under GPL and LGPL as well as permissive
licenses.

## Decision

Our code is MIT. Third-party components ship unmodified under their own
licenses: PRoot (GPL-2.0-or-later) as a separate executable whose exact source
and build recipe are in `runtime/proot`; talloc (LGPL-3.0-or-later), linked
statically, rebuildable with the same recipe; Ubuntu packages with their
copyright files kept in the image. `THIRD_PARTY_NOTICES.md` lists everything
the APK contains and is shown in the app.

## Consequences

Anything added to the APK or the image is added to `THIRD_PARTY_NOTICES.md` in
the same change. Code from AGPL projects is not used
([proot-on-android](proot-on-android.md)).
