# Releasing

How a change gets from a branch to a signed installer on the
[Releases](https://github.com/ADSBexchange/adsbexchange-desktop-feeder/releases) page. Building,
signing and publishing need a maintainer with signing rights. Everything else is open to any
contributor.

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
  `appVersion` and is on `master`, and builds again. CI does not sign or publish.

## Tools

All commands below are PowerShell 7 (`pwsh`), from the repository's root on Windows 10/11 x64.

| Tool | Install | For |
| --- | --- | --- |
| PowerShell 7 | `winget install -e --id Microsoft.PowerShell` | The repository's scripts |
| Git | `winget install -e --id Git.Git` | |
| GitHub CLI | `winget install -e --id GitHub.cli`, then `gh auth login` | Pull requests, tags, releases |
| Temurin JDK 17, latest update | `winget install -e --id EclipseAdoptium.Temurin.17.JDK` | The build. The installer bundles the JDK the build ran on |
| Docker Desktop | `winget install -e --id Docker.DockerDesktop` | Building readsb.exe and rtlsdr-driver.exe |
| Artifact Signing client tools, Azure CLI | Maintainers' signing guide | Signing (maintainers only) |

Point `JAVA_HOME` at the JDK 17 to build with, in the same window as the build:

```powershell
Get-ChildItem 'C:\Program Files\Eclipse Adoptium'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.<update>-hotspot'
& "$env:JAVA_HOME\bin\java" -version
```

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

## 3. Build and sign (maintainer with signing rights)

From a clean checkout of `master`:

```powershell
git switch master
git pull --ff-only
git status --short                    # nothing listed
az account show --query user.name     # signed in; otherwise az login
pwsh .\tools\build-native.ps1
pwsh .\tools\spike-test.ps1
.\gradlew.bat --no-daemon :app:test :app:buildMsi -Psign
```

`-Psign` signs the launcher, readsb.exe and rtlsdr-driver.exe, builds the MSI, signs it last and
verifies every signature. The MSI is `Releases\ADS-B-Exchange-Feeder.msi`.

Check it before going further:

```powershell
Get-AuthenticodeSignature .\Releases\ADS-B-Exchange-Feeder.msi |
  Format-List Status, SignerCertificate, TimeStamperCertificate
Start-Process msiexec -ArgumentList '/i', "$PWD\Releases\ADS-B-Exchange-Feeder.msi" -Wait
```

- The signature is `Valid`, the signer is JETNET, L.L.C., and there is a timestamp.
- Settings > About shows the new version and the `master` commit, without `-dirty`.
- With a stick plugged in, it feeds, and MLAT connects.

## 4. Tag

Tag the commit the MSI was built from:

```powershell
git tag -a vX.Y.Z -m "ADS-B Exchange Desktop Feeder X.Y.Z (beta)" (git rev-parse HEAD)
git push origin vX.Y.Z
gh run list --workflow release.yml --limit 1
gh run watch <run id>
```

## 5. Release notes

Start from the previous release's notes and update them:

```powershell
gh release view --json body --jq .body > notes.md
git log --format='- %s' <previous tag>..vX.Y.Z
```

- Add a "What's new in X.Y.Z" section at the top.
- Update the file name, the SHA-256 and anything in Install, Privacy and Good to know that changed.
- State plainly any change to what the app sends, or to a privacy setting or its default, and what
  happens to people who update.

## 6. Publish

Create the release as a draft:

```powershell
$hash = (Get-FileHash .\Releases\ADS-B-Exchange-Feeder.msi -Algorithm SHA256).Hash.ToLower()
[IO.File]::WriteAllText("$PWD\Releases\ADS-B-Exchange-Feeder.msi.sha256", "$hash  ADS-B-Exchange-Feeder.msi`n")
gh release create vX.Y.Z --draft --verify-tag --title "ADS-B Exchange Desktop Feeder X.Y.Z (beta)" `
  --notes-file notes.md .\Releases\ADS-B-Exchange-Feeder.msi .\Releases\ADS-B-Exchange-Feeder.msi.sha256
```

Check the draft on GitHub: the title, both files, and the SHA-256 in the notes matches the
`.sha256` file. Then publish it. It's not a pre-release, and it becomes the latest:

```powershell
gh release edit vX.Y.Z --draft=false --latest
```

Afterwards, delete branches that were merged for the release.
