# Ubuntu 24.04 base, packages installed with apt under qemu

Status: accepted (2026-10)

## Context

The guest needs an ordinary Debian-style userland whose package database
matches what is on disk, built on an x86_64 host.

## Decision

ubuntu-base arm64 is unpacked and `apt-get install` runs inside it through
`proot -q qemu-aarch64-static`, so maintainer scripts really run and dpkg's
database matches the disk. Docs and man pages are excluded; copyright files are
kept. `APT::Sandbox::User "root"` stays in the image because apt cannot drop to
`_apt` under PRoot's fake root on the device either. `POORDSH_REUSE_APT=1`
reuses the slow apt stage between runs.

## Consequences

The first image build takes about 15 minutes.
