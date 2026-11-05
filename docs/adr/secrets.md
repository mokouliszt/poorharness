# Secrets

Status: accepted (2026-10, revised in 0.2.0, 0.3.0, 0.11.0, 0.13.0 and 0.19.0)

## Context

The app holds API keys and the key of the user's S3-compatible bucket, and
starts programs in a guest where every process can read every other process's
command line.

## Decision

Keys typed in the UI (Modal, Upstash Box, Tensorlake, TinyFish, the bucket) are encrypted with an Android
Keystore key and written with `commit()`, so they survive the app being killed.
Backup is disabled. Secrets reach the guest through a 0600 file that the guest
deletes before exec, never through argv. OpenCode Zen, NVIDIA NIM, OrcaRouter,
Infron and Venice keys stay in opencode's own store. The bucket's key never leaves the
app: sandboxes get files and git bundles from it, not the key
([git-repos](git-repos.md)). The same goes for the TinyFish key: the plugin asks
the app to call TinyFish ([web-in-sandbox](web-in-sandbox.md)), and for the
Upstash Box key: opencode's tools for a box go through the app's bridge, and
boxes are made with no environment of the app's ([box-first](box-first.md)).
The Modal token likewise: the app's helper gets it over a pipe, and sandboxes
are started with no environment of the app's ([modal-first](modal-first.md)).

Until 0.11 a ChatGPT sign-in was kept on the phone and copied into sandboxes
for Codex ([opencode-only](opencode-only.md)); it is no longer read.

## Consequences

A device jail is weaker than a Tensorlake sandbox: its programs run as the
app's Android user and can read the environment of the app's other processes
([phone-sandboxes](phone-sandboxes.md)).
