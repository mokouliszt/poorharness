# Skills live in one place, read where they are used

Status: accepted (2026-10, 0.4.0; sync shared with plugins in 0.8.0; opencode only in 0.11.0)

## Context

Users want to add Agent Skills (a folder with `SKILL.md` and its scripts and
references) and have the agent use them. A skill is packed as a `.skill` or
`.zip` file by Anthropic's skill-creator. opencode runs on the phone and its
tools in the chat's sandbox. The paths it hands the model must work where the
model's commands run, in the sandbox, whose home folder differs between
Tensorlake (`/home/tl-user`) and a device jail (`/root`).

## Decision

- The store is `files/skills/<name>/` on the phone (`SkillStore`). Settings
  adds a skill from a picked `.skill` or `.zip` and removes one. An archive is
  accepted when skill-creator's checks pass (`SkillPackage`, after
  `scripts/quick_validate.py` and `package_skill.py`): one `SKILL.md` with
  frontmatter keys from its list, a kebab-case `name` of at most 64
  characters (also the folder name), a `description` without angle brackets
  of at most 1024; caches, `node_modules` and a top-level `evals/` are dropped
  as the packager drops them. Unsafe paths and oversized archives are refused.
- One path, [`SkillSync.ROOT`](../../android/app/src/main/java/dev/mokouliszt/poordsh/skills/SkillSync.kt)
  = `/tmp/poordsh/skills`, means the same everywhere:
  - opencode's PRoot binds the store there, and its config lists that path
    in `skills.paths`. Its permission config allows the `skill` tool only for
    the user's skills (opencode's built-in one edits opencode's own config),
    and the plugin offers the tool only when there are skills.
  - Before every turn the chat's sandbox gets a copy at the same path
    (`SkillSync`, through `FolderSync`, which plugins share since 0.8.0): a
    manifest of skill digests makes an unchanged sandbox cost
    one small read (and nothing when this process synced it already); a
    changed skill is replaced whole; only skills PoorHarness put there are
    removed. A failed sync does not stop the turn.
- Adding or removing a skill restarts opencode when it is next idle (its
  skill list and permissions are read at start).

## Consequences

opencode finds skills through a mechanism it documents, and a skill's scripts
run where its paths point, in every place a chat can be. opencode's built-in
skill is not offered. A skill an agent writes into `/tmp/poordsh/skills` itself
stays in that chat's sandbox only. Updating opencode means checking that
`skills.paths` and the `skill` permission still behave as described ([`docs/maintenance.md`](../maintenance.md)).
