# One Activity, one WebView, one bridge

Status: accepted (2026-10)

## Context

Every screen is web UI (`webui/`). The host only needs to serve it and answer
its calls.

## Decision

The host is Kotlin with no UI toolkit beyond one WebView. The UI is served from
`https://appassets.androidplatform.net/assets/webui/` by `WebViewAssetLoader`
(no `file://`). JavaScript talks to Kotlin through a single
`PoorDSHNative.request(json)` method; replies and events come back via
`window.__poordsh`. Navigation away from the app host opens Custom Tabs.

## Consequences

Every host feature is a bridge method or event in
[`docs/bridge-protocol.md`](../bridge-protocol.md), and the UI can be developed
in a browser ([ui-runs-without-the-phone](ui-runs-without-the-phone.md)).
