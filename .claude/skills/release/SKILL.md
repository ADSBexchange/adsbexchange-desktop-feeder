---
name: release
description: Take changes from a branch to dev, or cut a signed release of the desktop feeder (version bump, dev to master, tag, approval, draft check, notes, publish). Use when someone asks to ship, merge, release, tag or publish a version.
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
`gh release view/list/download/verify`, `gh attestation verify`, `Get-AuthenticodeSignature`,
`Get-FileHash`, `java -version`, and running the build and tests.

## Ask first, every time

Each of these is visible to others or reaches users. Show the exact command, say what it does, and
wait for a yes:

- pushing a branch, opening or merging a pull request;
- pushing a tag;
- editing the draft release's notes, and separately, publishing it.

Never bypass the branch rules, even when the account is an admin: no direct pushes to `dev` or
`master`, no force-push, no approving or merging the release pull request on the author's own
approval. Never approve the `release` environment: that is a second maintainer's decision, made on
the run's page. Never delete or move an existing tag or release.

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
gh run list --workflow release.yml --limit 1
```

Tell the person: the last release, what is on `dev` and not released, the version in
`appVersion`, open pull requests, and whether a release run is waiting for approval or has left a
draft. If they only want a change into `dev`, do RELEASING.md step 1 and stop.

### 1. Preflight (release only)

- `gh auth status` works, and `gh --version` is current (`winget upgrade -e --id GitHub.cli`);
  `gh attestation verify` and `gh release verify` are recent commands.
- The PC can run the installer for the hands-on check in step 4.

### 2. Version and release pull request

- If `appVersion` still equals the latest release, propose the next version (patch for fixes,
  minor for features) and bump it in a pull request into `dev`.
- Open the `dev` to `master` pull request per RELEASING.md step 2. Its approval comes from another
  maintainer; tell the person who needs to approve, then wait.

### 3. Tag and approval

RELEASING.md steps 3 and 4. Before tagging, confirm `git log -1` on the clean `master` checkout is
the release pull request's merge commit. Pushing the tag is a yes. Watch the run: it stops at
"Signed installer" until a maintainer other than the person who pushed the tag approves the
`release` environment. Tell the person who can approve and what RELEASING.md step 4 asks them to
check, then wait. Stop if any job fails.

### 4. Check the draft

RELEASING.md step 5. Run the command checks and report each one: the hash, the signature, the
attestation. Ask the person to do the hands-on ones (install, About screen, feeding, MLAT). Don't
continue until they confirm.

### 5. Release notes

RELEASING.md step 6. The draft's "What's new" comes from the pull requests' release notes. Work
through its reminder block, if it has one: for a pull request or commit with no note, read the full
pull request or commit message and write one. Write for people who feed ADS-B Exchange, not
developers. List any change to what is sent, to privacy settings or their defaults, or to what
happens on update, and ask the person to confirm those lines. Put the notes in front of them;
updating the draft is a yes.

### 6. Publish

RELEASING.md step 7. Compare the SHA-256 in the notes, in the `.sha256` file, and of the uploaded
asset (`gh release view vX.Y.Z --json assets`) once more. Publishing is a separate yes and is
final: the files and the tag are locked once published. Afterwards run `gh release verify vX.Y.Z`.

Finish with the release URL and
`https://github.com/ADSBexchange/adsbexchange-desktop-feeder/releases/latest/download/ADS-B-Exchange-Feeder.msi`.
