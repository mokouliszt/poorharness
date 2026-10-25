# Copy

Status: accepted (2026-10)

## Context

The app speaks English and Japanese on a small screen.

## Decision

All strings are in `webui/src/lib/i18n.ts` (English and Japanese, same keys; a
missing Japanese key is a type error). Labels name the action; no explanatory
sentences where a control can speak for itself.

## Consequences

A new string is written in both languages in the same change.
