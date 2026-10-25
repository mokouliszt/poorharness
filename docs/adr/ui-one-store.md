# One store, plain functions

Status: accepted (2026-10)

## Context

Host events, bridge replies and user input all change the same state.

## Decision

State lives in a single zustand store (`webui/src/lib/store.ts`); actions are
exported functions, not hooks, so event handlers and components call the same
code. Screens read with selectors.

## Consequences

Host events are handled in one place, outside React components.
