---
name: release
description: Take changes from a branch to dev, or cut a signed release of the desktop feeder (version bump, dev to master, signed MSI, tag, GitHub release). Use when someone asks to ship, merge, release, tag or publish a version.
---

# Ship a change or cut a release

RELEASING.md in the repository root is the procedure. Read it first and follow its steps and
commands; this skill adds how to drive it in a session. The person you're working with may not have
done a release before, so tell them what each step does before running it.

## Running commands

Run the repository's commands in PowerShell 7: `pwsh -NoProfile -Command "..."` from the repository
root (never `powershell.exe`, which is 5.1). Builds take minutes; run them in the background and
report when they finish.

## What you may do without asking

Read-only commands: `git status/log/diff/fetch`, `gh pr view/checks/list`, `gh run list/view/watch`,
`gh release view/list`, `Get-AuthenticodeSignature`, `java -version`, `az account show`, and running
the build and tests.

## Ask first, every time

Each of these is visible to others or reaches users. Show the exact command, say what it does, and
wait for a yes:

- pushing a branch, opening or merging a pull request;
- pushing a tag;
- creating the GitHub release (always `--draft`), and separately, publishing it.

Never bypass the branch rules, even when the account is an admin: no direct pushes to `dev` or
`master`, no force-push, no approving or merging the release pull request on the author's own
approval. Never delete or move an existing tag or release.

## Steps

### 0. Where things stand

Find out before proposing anything:

```powershell
git fetch origin --tags
git status -sb
git log --oneline origin/master..origin/dev
gh release list --limit 3
Select-String '^val appVersion' app/build.gradle.kts
gh pr list --state open
```

Tell the person: the last release, what is on `dev` and not released, the version in
`appVersion`, and open pull requests. If they only want a change into `dev`, do RELEASING.md step 1
and stop.

### 1. Preflight (release only)

Check each tool in RELEASING.md's Tools table and stop on the first one missing, with its install
command:

- `gh auth status` works.
- `docker version` reaches the engine (Docker Desktop is running).
- `JAVA_HOME` points to Temurin 17, and it is the latest update on
  https://adoptium.net/temurin/releases/?version=17. If a newer one exists, say so: the installer
  bundles this JDK.
- `az account show` succeeds. If signing tools or sign-in are missing, the person needs the
  maintainers' signing setup before step 3; everything up to then can still go ahead.

### 2. Version and release pull request

- If `appVersion` still equals the latest release, propose the next version (patch for fixes,
  minor for features) and bump it in a pull request into `dev`.
- Open the `dev` to `master` pull request per RELEASING.md step 2. Its approval comes from another
  maintainer; tell the person who needs to approve, then wait.

### 3. Build, sign, verify

Follow RELEASING.md step 3 on a clean `master`. Before building, confirm `git log -1` is the
release pull request's merge commit. After building, report each check in step 3 and ask the person
to do the hands-on ones (install, About screen, feeding, MLAT). Don't continue until they confirm.

### 4. Tag

RELEASING.md step 4. Tag `HEAD` of the clean `master` checkout that was built. Watch `release.yml`
and stop if it fails.

### 5. Release notes

Draft `notes.md` per RELEASING.md step 5 from the previous release's notes and the commits since its
tag (read the full commit messages, not just subjects). Write for people who feed ADS-B Exchange, not
developers. List any change to what is sent, to privacy settings or their defaults, or to what
happens on update, and ask the person to confirm those lines. Put the notes in front of them before
continuing.

### 6. Draft, check, publish

RELEASING.md step 6. After creating the draft, compare the SHA-256 in the notes, in the `.sha256`
file, and of the uploaded asset (`gh release view vX.Y.Z --json assets`). Publishing is a separate
yes.

Finish with the release URL and
`https://github.com/ADSBexchange/adsbexchange-desktop-feeder/releases/latest/download/ADS-B-Exchange-Feeder.msi`.
