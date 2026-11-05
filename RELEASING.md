# Releasing

How a change gets from a branch to a signed installer on the
[Releases](https://github.com/ADSBexchange/adsbexchange-desktop-feeder/releases) page. Merging the
release, pushing its tag and publishing need a maintainer; approving the signing needs a second
one. Everything else is open to any contributor.

## How releases work

- `dev` is the default branch; pull requests go there. `master` holds releases. A pull request into
  `master` needs one approval and is merged with a merge commit.
- The version is `val appVersion` in `app/build.gradle.kts`, and nowhere else.
- A release is an annotated tag `vX.Y.Z` on `master` plus a GitHub release with the signed
  `ADS-B-Exchange-Feeder.msi` and its `ADS-B-Exchange-Feeder.msi.sha256` attached. The installer has
  the same name in every release, so `releases/latest/download/ADS-B-Exchange-Feeder.msi` is always
  the newest one.
- The app's update check reads the latest release twice a day. It skips drafts and pre-releases, so
  a release reaches installed copies when it's published, not before.
- CI builds and tests every pull request. On a `v*` tag, `release.yml` checks that the tag matches
  `appVersion` and is on `master`, builds and tests, and waits for a maintainer other than the one
  who pushed the tag to approve the `release` environment. It then signs the installer through
  Azure Artifact Signing with a credential that exists only for jobs in that environment, attests
  the installer's provenance, and creates a draft release with the installer and its `.sha256`.
- Publishing is final. A published release's files and tag are locked and the tag name can't be
  used again, so anything wrong with a published release is put right by a new version. The title
  and notes stay editable.

## Tools

CI builds and signs releases. A Windows 10/11 x64 PC is for changing and testing the app, and for
checking an installer before it's published. All commands below are PowerShell 7 (`pwsh`), from the
repository's root.

| Tool | Install | For |
| --- | --- | --- |
| PowerShell 7 | `winget install -e --id Microsoft.PowerShell` | The repository's scripts |
| Git | `winget install -e --id Git.Git` | |
| GitHub CLI | `winget install -e --id GitHub.cli`, then `gh auth login` | Pull requests, tags, releases, attestations |
| Temurin JDK 17, latest update | `winget install -e --id EclipseAdoptium.Temurin.17.JDK` | Building and testing locally |
| Docker Desktop | `winget install -e --id Docker.DockerDesktop` | Building readsb.exe and rtlsdr-driver.exe locally |

Point `JAVA_HOME` at the JDK 17 to build with, in the same window as the build:

```powershell
Get-ChildItem 'C:\Program Files\Eclipse Adoptium'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.<update>-hotspot'
& "$env:JAVA_HOME\bin\java" -version
```

Maintainers with signing rights can still sign a local build (`:app:buildMsi -Psign`, see the
maintainers' signing guide); releases don't use it.

## 1. Get a change into dev

```powershell
git switch dev
git pull --ff-only
git switch -c <short-description>
# ... change, commit ...
.\gradlew.bat :app:test
git push -u origin HEAD
gh pr create --base dev --fill
gh pr checks --watch
```

The pull request's description needs the template's "Release note" section: one or two sentences
for people who feed, or "none". `--fill` takes the description from the commit message, so either
give the commit message that section or add it on GitHub. The release's notes are made from it.

Once checks pass (and someone has looked at it), merge it:

```powershell
gh pr merge --squash --delete-branch
```

## 2. Prepare the release

1. Bump `appVersion` in `app/build.gradle.kts` (in its own pull request into `dev`, or in the last
   change's).
2. Open the release pull request and wait for its checks:

   ```powershell
   gh pr create --base master --head dev --title "Release X.Y.Z" --body "Release X.Y.Z."
   gh pr checks <number> --watch
   ```

3. Another maintainer approves it. Then merge it with a merge commit:

   ```powershell
   gh pr merge <number> --merge
   ```

## 3. Tag

From a clean checkout of `master` at the release pull request's merge commit:

```powershell
git switch master
git pull --ff-only
git status --short                    # nothing listed
git log -1 --format='%H %s'           # the release pull request's merge commit
git tag -a vX.Y.Z -m "ADS-B Exchange Desktop Feeder X.Y.Z (beta)" (git rev-parse HEAD)
git push origin vX.Y.Z
gh run list --workflow release.yml --limit 1
gh run watch <run id>
```

The run builds and tests, then waits at "Signed installer" for the approval in step 4. Don't move
or delete a pushed tag. If the run fails for a reason outside the repository (a runner or a service),
re-run it: `gh run rerun <run id> --failed`. If the code or a workflow has to change, that's a new
version.

## 4. Approve the signing (a second maintainer)

The run's "Tag" summary, from the "Tag and version" job, shows the tagged commit, whether it is the
head of `master`, who pushed the tag and the pull request that was merged. Approve the `release`
environment (Review deployments, on the run's page) when:

- the commit is the head of `master` and the release pull request's merge commit;
- the build and test jobs passed;
- you are not the one who pushed the tag.

The run then signs the installer, attests it and creates the draft release.

## 5. Check the draft

The run's "Signed installer" summary shows the signer, the SHA-256 and the bundled Java runtime.
Check the draft and its installer on a PC:

```powershell
gh release view vX.Y.Z --json isDraft,assets --jq '{isDraft, assets: [.assets[].name]}'
gh release download vX.Y.Z --pattern 'ADS-B-Exchange-Feeder.msi*' --dir .\Releases --clobber
Get-Content .\Releases\ADS-B-Exchange-Feeder.msi.sha256
Get-FileHash .\Releases\ADS-B-Exchange-Feeder.msi
Get-AuthenticodeSignature .\Releases\ADS-B-Exchange-Feeder.msi |
  Format-List Status, SignerCertificate, TimeStamperCertificate
gh attestation verify .\Releases\ADS-B-Exchange-Feeder.msi --repo ADSBexchange/adsbexchange-desktop-feeder `
  --signer-workflow ADSBexchange/adsbexchange-desktop-feeder/.github/workflows/release.yml
Start-Process msiexec -ArgumentList '/i', "$PWD\Releases\ADS-B-Exchange-Feeder.msi" -Wait
```

- The hash matches the `.sha256` file, the signature is `Valid`, the signer is JETNET, L.L.C.,
  there is a timestamp, and the attestation verifies against this repository's release workflow.
- Settings > About shows the new version and the `master` commit.
- With a stick plugged in, it feeds, and MLAT connects.

## 6. Release notes

The draft's notes are the previous release's, with:

- the version in the title;
- one "What's new in X.Y.Z" section, made from the "Release note" sections of the pull requests
  merged since the previous release, in place of the previous release's, and a link to the earlier
  releases' notes;
- the SHA-256 in "Verify the download" set to the new installer's.

A reminder block on top lists what to check: pull requests without a release note, any that ticks
"Changes what the app sends, or a privacy setting or its default", and commits that didn't come from
a pull request. The same notes are in the run's "Draft release" summary. Without a reminder block,
read the notes and go on to step 7. With one, edit them:

```powershell
gh release view vX.Y.Z --json body --jq .body > notes.md
# ... edit notes.md ...
gh release edit vX.Y.Z --notes-file notes.md
```

- Deal with each item in the reminder block, then delete it.
- Update anything in Install, Privacy and Good to know that changed.
- State plainly any change to what the app sends, or to a privacy setting or its default, and what
  happens to people who update.

## 7. Publish

Check the draft on GitHub once more: the title, both files, and the SHA-256 in the notes matches the
`.sha256` file. Then publish it. It's not a pre-release, and it becomes the latest:

```powershell
gh release edit vX.Y.Z --draft=false --latest
gh release verify vX.Y.Z
```

Afterwards, delete branches that were merged for the release.
