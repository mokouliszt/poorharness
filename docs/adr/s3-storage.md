# An S3-compatible bucket, one folder per use

Status: accepted (2026-10, 0.9.0, revised in 0.10.0 and 0.11.0)

## Context

Files agents hand over ([outputs](outputs.md)) are kept on the phone by default.
Users may want them in their own storage instead, reachable from other devices,
and later features (backups, sharing a skill library) will want the same
storage. Providers differ (Backblaze B2, Cloudflare R2, MinIO, AWS S3, Wasabi),
but all speak the S3 API.

## Decision

- One bucket, connected in Settings: endpoint, bucket, access key ID, secret
  key, and optionally the region (read from an `s3.<region>.…` or
  `s3.dualstack.<region>.…` endpoint, else
  `us-east-1`) and a folder in the bucket for everything this app writes.
  The whole set is kept in the Keystore-encrypted secret store. Saving writes,
  reads back and deletes `.poorharness-check` under that folder first, so a
  wrong key, bucket or permission fails there and not at the end of a turn.
- `S3Storage` speaks the S3 API itself (Signature V4, path-style URLs, signed
  payload hashes): PutObject, GetObject, DeleteObject, ListObjectsV2 and
  DeleteObjects. No SDK: these five calls are all the app needs.
- Layout: `<folder>/<use>/…`. Each use gets a fixed name (`S3Storage.OUTPUTS`
  = `outputs`, with `outputs/<chat id>/<file>`; `S3Storage.GIT` = `git`, with
  `git/<repo>/…`, [git-repos](git-repos.md)), reads and deletes only below it
  (`S3Storage.space`), and a new use adds its own name instead of sharing.
- Handed-over files go to the phone or to the bucket, as picked in Settings
  ("Persistent storage").
  Disconnecting the bucket sends new files to the phone again; what is in the
  bucket stays there.

## Consequences

- Any S3-compatible service works without app changes; the region field covers
  those whose endpoint does not name it (R2 takes `auto`).
- The key needs list, read, write and delete on the bucket (or its folder);
  B2's application keys can be limited to one bucket.
- Verified against Backblaze B2 (`s3.us-east-005.backblazeb2.com`), including
  keys with spaces, `+`, parentheses and Japanese characters (`S3StorageTest`,
  `OutputsLiveTest`).
