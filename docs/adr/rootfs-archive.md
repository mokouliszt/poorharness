# Archive format of the image

Status: accepted (2026-10)

## Context

The image is unpacked on the phone by the app's own extractor, and the same
inputs should give the same archive.

## Decision

tar with hard links dereferenced, numeric owners, sorted entries and a fixed
mtime, compressed with xz. `rootfs.json` carries the sha256 the app checks
while unpacking and the bundled engine versions.

## Consequences

The on-device extractor (`TarExtractor.kt`) only handles directories, regular
files and symlinks.
