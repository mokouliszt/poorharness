# Old WebViews

Status: accepted (2026-10)

## Context

The app supports Android 8, and some phones run an old Android System WebView.

## Decision

The build target is es2017 and `webui/src/polyfills.ts` covers built-ins that
some Android System WebView versions lack. Assets use relative URLs
(`base: "./"`).

## Consequences

A newer built-in is added to the polyfills before the UI relies on it.
