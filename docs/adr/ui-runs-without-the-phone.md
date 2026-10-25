# The UI runs without the phone

Status: accepted (2026-10)

## Context

Design work and screenshots should not need a device or a running engine.

## Decision

Outside the app, `webui/src/lib/mock.ts` stands in for the Kotlin host, so
`npm run dev` shows every screen with canned data (`?setup` for first run,
`?nokey` for no Tensorlake key; the list
is at the top of the file).

## Consequences

Every bridge method the UI calls needs an answer in the mock.
