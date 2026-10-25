# Tensorlake on the free plan by default

Status: accepted (2026-10, revised in 0.2.0)

## Context

PoorHarness should work with free accounts only. Tensorlake's free plan allows one
running sandbox of 1 vCPU, 1 GB of memory and 10 GB of disk.

## Decision

Sandboxes are sized for the free plan: 1 vCPU / 1 GB / 10 GB (the plugin's
default, 2 vCPU / 4 GB, is rejected there). The `tensorlake-opencode` plugin is
installed by opencode on the device on its first start (its npm tree is
~200 MB with native modules, too big to bundle); the UI says so while it
downloads.

## Consequences

Heavy builds are slow in a sandbox. On the free plan one chat works at a time
([chat-sandboxes](chat-sandboxes.md)).
