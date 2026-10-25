# Git repositories for agents, kept in the bucket

Status: accepted (2026-10, 0.11.0)

## Context

Agents work in sandboxes that are paused, deleted with their chat, or expire
(Tensorlake's free plan keeps files 7 days). Code that should outlive a chat
needs a git remote, and users without a git server of their own still have the
S3-compatible bucket they connected for [outputs](outputs.md). Options weighed:

- A git server (Gitea and the like): one more service to run, the opposite of
  this app's no-server design.
- A git remote helper over S3 inside the sandbox: the agent would hold the
  bucket's key, and with it everything else in the bucket.
- The bucket as storage, the app as the only party that touches it: git runs
  in the sandbox against a plain local remote; the app moves git bundles
  between the bucket and the sandbox.

For the layout, [git-remote-s3](https://github.com/awslabs/git-remote-s3)
(awslabs) already defines one: `<repo>/HEAD` holds the default branch as a ref
name, and each ref has one bundle, `<repo>/<ref>/<sha>.bundle`, made with
`git bundle create <file> <ref>` (the ref's whole history). It locks a ref with
a conditional write (`If-None-Match: *` on `<repo>/<ref>/LOCK#.lock`, 60 s).
Backblaze B2 does not take conditional writes (the connection is closed on such
a PUT, checked October 2026), so git-remote-s3 can clone from B2 but not push.

## Decision

- Only while a bucket is connected: opencode gets the `repo` tool (from the
  app's plugin) and a line in its instructions; the tool is denied otherwise.
  Its actions: `list`, `open <name>`, `create <name>`. In "Ask every time" mode
  opening and creating ask first. Connecting or disconnecting the bucket
  restarts opencode when idle.
- The tool goes from opencode's plugin to the app over the loopback bridge
  (`/repo`), naming the session's sandbox as the plugin's map has it (a
  subagent's session is traced to its top session first); the app finds the
  chat and does the work there ([GitRepos]):
  - `open`: the bundles go to the sandbox and are fetched into a bare repository
    at `/tmp/git/<name>.git`, with `receive.denyNonFastForwards` and
    `receive.denyDeletes`, a reflog, and a git identity of "PoorHarness" if the
    sandbox has none. A branch the sandbox already has ahead of or apart from
    the bucket is kept (unsaved commits of an earlier turn).
  - `create`: an empty bare repository there, and `HEAD` in the bucket so it is
    listed at once.
- When a turn ends (and when a background subagent finishes after it), while
  the sandbox is still held, every branch and tag of the sandbox's repositories
  that differs from the bucket is compared with the stored one in the sandbox:
  new or a fast-forward, it is bundled and stored (the old bundle deleted); the
  bucket ahead, nothing; forked, the stored branch stays and the sandbox's
  commits are stored as `<branch>-<chat id>`. A ref git-remote-s3 has locked is
  waited for (up to the lock's 60 s). Nothing is ever deleted from the bucket
  by a save. Which chats opened a repository is kept on the phone, so a restart
  between opening and saving still saves.
- The layout is git-remote-s3's, under the bucket's `git` folder
  ([s3-storage](s3-storage.md)), so `git clone s3://<bucket>/<folder>/git/<name>`
  works from a computer with git-remote-s3. Settings → Git lists the
  repositories, copies that address, saves the default branch's bundle to
  Download/PoorHarness (`git clone -b <branch> <file>` makes a working copy),
  and deletes one or all.

## Consequences

- The bucket's key never reaches a sandbox or a model.
- Every save of a branch uploads its whole history (full bundles, as
  git-remote-s3 does). Fine for agent-sized repositories; large ones cost
  upload time at the end of a turn.
- Within the app, saves never overwrite. A computer pushing with git-remote-s3
  to storage that takes conditional writes (AWS S3) is waited for; on B2 a
  computer can only clone and fetch.
- An agent that rewrites history must use a new branch.
- Verified with git 2.43 on the build host (`GitReposTest`: create, push, save,
  a second chat moving main on, refused force push and deletion, a fork saved
  to `main-<chat>`, reopening with unsaved commits), in a Tensorlake sandbox
  against Backblaze B2 with a clone through git-remote-s3 0.4.2 (`GitReposLiveTest`),
  and the tool in opencode 1.19.0 with tensorlake-opencode 0.3.1 (list and open
  called by DeepSeek V4.1 Flash; the ask path in "Ask every time").
