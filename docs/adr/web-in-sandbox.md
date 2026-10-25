# Web access from the chat's sandbox, with TinyFish for usage limits

Status: accepted (2026-10, 0.13.0)

## Context

opencode's `webfetch` and `websearch` ran in opencode, on the phone, so every
page and search left from the phone's network and IP. `websearch` uses the free
Exa service, which has a usage limit.

## Decision

PoorHarness's plugin registers `webfetch` and `websearch` last, with opencode's
own arguments, so they replace the built-in ones. Each request runs in the
chat's sandbox (a Tensorlake sandbox or a device jail): the plugin sends
`assets/opencode/poordsh-web.py`, with its arguments in base64, through
tensorlake-opencode's `bash` tool, so the sandbox is found and routed exactly as
for the model's own commands. The script needs only python3's standard library:
it fetches (HTML to Markdown or text, images as attachments) and calls Exa's MCP
endpoint like opencode does.

Only a usage limit sends the request on to TinyFish: HTTP 402 or 429, or a limit
or quota message from the search. Any other error goes to the model as it is. The
fallback goes through the app's loopback bridge (`/web`), which calls TinyFish's
Search and Fetch APIs. The key is a Settings entry; it stays in the app, never
reaches a sandbox or the model, and is read at each call, so changing it needs no
restart. Without a key a limit is reported to the model like any error.

Approvals are unchanged: both tools ask with opencode's `webfetch` and
`websearch` permissions. `codesearch` still runs on the phone.

## Consequences

Pages are fetched from the sandbox's address, not the phone's. The sandbox must
have python3 (the device image does; checked for Tensorlake sandboxes in
2026-10); without it the tools answer with the error, and nothing is fetched from
the phone. If the plugin cannot build the tools, opencode's own stay in place.
Binary files other than images are refused with a hint to use `curl` in the
sandbox. If Exa or TinyFish change their APIs, `poordsh-web.py`
and `TinyFish.kt` are the only places to change
([maintenance](../maintenance.md)).
