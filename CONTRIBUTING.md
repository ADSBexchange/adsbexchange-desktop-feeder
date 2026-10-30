# Contributing

Thanks for helping with the ADS-B Exchange Desktop Feeder. By taking part you agree to the
[code of conduct](CODE_OF_CONDUCT.md).

## Where to go

- **Help with the app:** ADS-B Exchange's Discord server (Settings > Help in the app).
- **A security problem:** report it privately, as [SECURITY.md](SECURITY.md) describes, not in an issue.
- **A bug or an idea:** open an issue with one of the templates.

Logs: `readsb.log` includes your feeder's ID, and other files in the log folder include your
receiver's location. Don't attach them to an issue; a maintainer will ask for what's needed privately.

## Pull requests

- Branch from `dev` and open the pull request into `dev`. `master` holds releases.
- For anything larger than a fix, open an issue first so the approach can be agreed before the work.
- Keep a pull request to one change, and match the style of the code around it.
- CI builds the native programs and the installer, runs the tests, and runs the security scan and
  CodeQL. For contributors outside the ADS-B Exchange organisation, a maintainer approves the run
  first.

Before opening one, build and test locally as the README's [Building](README.md#building) section
describes:

```powershell
tools\build-native.ps1
tools\spike-test.ps1
.\gradlew.bat :app:test
```

- **Dependencies are locked.** A dependency change updates `gradle/libs.versions.toml`, the
  lockfiles and `gradle/verification-metadata.xml` together (the README has the command). A change to
  a C library updates its pinned commit in `native/cmake/deps.cmake`.
- **Tests never reach ADS-B Exchange.** Tests that need a server use stand-ins on 127.0.0.1.
- **Location stays private.** Nothing may log or send the receiver's position beyond what the
  README's Privacy section describes.

## Releases

Maintainers build, sign and publish releases from `master`, as [RELEASING.md](RELEASING.md) describes.

## Licence

The app is GPL-3.0-or-later. Contributions are accepted under the same licence.
