# ADS-B Exchange Desktop Feeder

[![CI](https://github.com/ADSBexchange/adsbexchange-desktop-feeder/actions/workflows/ci.yml/badge.svg?branch=dev)](https://github.com/ADSBexchange/adsbexchange-desktop-feeder/actions/workflows/ci.yml?query=branch%3Adev)
[![Latest release](https://img.shields.io/github/v/release/ADSBexchange/adsbexchange-desktop-feeder)](https://github.com/ADSBexchange/adsbexchange-desktop-feeder/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/ADSBexchange/adsbexchange-desktop-feeder/total)](https://github.com/ADSBexchange/adsbexchange-desktop-feeder/releases)
[![License](https://img.shields.io/github/license/ADSBexchange/adsbexchange-desktop-feeder)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Windows%2010%20%7C%2011%20x64-0078D6)

A Windows app that turns a PC and an RTL-SDR USB stick into an [ADS-B Exchange](https://www.adsbexchange.com)
feeder. Install it, plug in the stick, and it starts feeding.

**Beta.** It works, and it's being tested before a full release. For help, ask on ADS-B Exchange's
Discord server (Settings > Help in the app).

## What it does

- **Feeds ADS-B Exchange** with what the stick hears, decoded by [readsb](https://github.com/wiedehopf/readsb).
- **Takes part in MLAT**, which works out the positions of aircraft that don't broadcast them, once
  you've entered the antenna's position.
- **Sends the stats behind your feeder's stats page** on ADS-B Exchange.
- **Sets up the stick's driver itself.** No Zadig step: the first time a stick is plugged in,
  Windows asks for permission to install its driver, and feeding starts.
- **Shows what you're receiving:** a live feed and MLAT status, a radar of the aircraft around you
  with nearby airports, the aircraft list, and your lifetime history, milestones and reception range.
- **Runs quietly in the background**, from the notification area. It starts with Windows (Settings
  can turn that off), uses about 0.5% of the CPU, and on a laptop pauses on battery and holds back
  its stats on metered connections (both can be changed in Settings).

Windows 10 and 11, 64-bit.

## Install

1. Download the installer (`ADS-B-Exchange-Feeder.msi`) from this repository's
   [latest release](../../releases/latest) and run it. It's signed by JETNET, L.L.C. While the
   publisher is new, SmartScreen may still warn: choose **More info**, check the publisher is
   JETNET, L.L.C., then choose **Run anyway**. The app installs for everyone on the PC, so
   Windows asks for administrator permission. It asks again the first time a new stick is plugged
   in (to install its driver) and when you install an update; on a PC where you're not an
   administrator, an administrator needs to approve these.
2. Leave **Open ADS-B Exchange Feeder** ticked on the last page and click **Finish**.
3. Plug in the RTL-SDR stick. The first time, Windows asks for permission to install its driver:
   choose **Yes**. Feeding starts by itself.
4. Optional, for MLAT: in **Settings**, enter the antenna's position and altitude.

**Which stick?** Any RTL2832U-based RTL-SDR works. One built for aircraft, with a 1090 MHz filter
and amplifier inside (such as ADS-B Exchange's), receives far more than a general-purpose stick on
the same antenna. If you use an amplifier at the antenna that's powered through the cable, turn on
**Power an amplifier through the antenna cable (bias tee)** under Settings > Receiver gain.

Uninstalling (Settings > Apps) removes the app and its shortcuts. Your settings and history stay
in `%LOCALAPPDATA%\ADS-B Exchange Feeder`.

## Privacy

- **The receiver's position stays on the PC**, except that MLAT sends it to ADS-B Exchange, as
  every ADS-B Exchange feeder does. ADS-B Exchange's public MLAT map lists feeders by name, and with
  MLAT privacy off shows an approximate location (rounded to about 1 km), never the precise one.
  MLAT privacy is on to start with.
- **It never appears on readsb's command line or in the log.** readsb gets it over a local gpsd
  connection that only readsb itself may open: any other program that connects is dropped first.
- **The stats** list the aircraft this PC hears, every 5 seconds. Each aircraft's distance and
  direction from the receiver are only included while MLAT is on.
- **The feeder ID** marks the feed as coming from this app. Share `readsb.log` only privately,
  since it contains the ID.
- The app checks GitHub for a newer release twice a day (Settings > About can turn that off).
  Apart from that, the feed, MLAT and the stats, it makes no connections of its own.

## How it works

```
 Tray icon + window (Compose Desktop)          one JVM process, one copy per Windows user
        │ watches FeederEngine.status
        ▼
 FeederEngine (one thread) ── stats uploads ──► https://adsbexchange.com/api/receive/ (every 5 s)
   │                       ── MLAT client ──► ADS-B Exchange MLAT (feed1/feed2)
   │  gpsd server 127.0.0.1:<port> ──TPV──┐        ▲ Beast 127.0.0.1:30005
   │  stdin pipe + kill-on-close job      ▼        │
   ├──────────────────────────────► readsb.exe ────┘──► feed1.adsbexchange.com:30004
   │                                   │ writes json/, readsb.log
   └── rtlsdr-driver.exe list (every 3 s until a stick is ready) / install (elevated, on request)
```

| Piece | Where |
|---|---|
| Entry point: tray, window, single-instance lock, `--background` | `app/src/main/kotlin/.../Main.kt` |
| Runs readsb, gpsd, MLAT, stats and history; starts readsb once a stick with WinUSB is plugged in, restarts it after it exits, recovers from unexpected errors, and keeps the PC awake while feeding | `service/FeederEngine.kt` |
| readsb as a child process: stdin pipe for a clean stop, a kill-on-close job so it can't outlive the app | `service/ReadsbProcess.kt` |
| The MLAT client (a Kotlin port of mlat-client) | `mlat/` |
| Stats uploads, as ADS-B Exchange's stats package sends them | `service/StatsUploader.kt` |
| Finding sticks and installing WinUSB | `service/SdrDriver.kt`, `native/rtlsdr-driver/` |
| Windows calls through JNA: connection owners, job object, keep awake, power and metered status | `service/Win32.kt`, `service/PowerConditions.kt` |
| Checking GitHub for a newer release | `service/UpdateChecker.kt` |
| Settings, paths and lifetime history | `data/` |
| Screens | `ui/` |

Settings and paths: `%LOCALAPPDATA%\ADS-B Exchange Feeder`. The feeder ID is a random UUID ending
in `ad5bde5c`, which marks desktop feeders.

### The stick's driver (no Zadig)

Windows binds RTL2832U sticks to a TV-tuner driver, or to none. librtlsdr needs Windows' own WinUSB
driver on the stick's interface 0, which users normally install by hand with Zadig.
`rtlsdr-driver.exe` (`native/rtlsdr-driver/`) does it for them, using
[libwdi](https://github.com/pbatard/libwdi), the library behind Zadig:

- `rtlsdr-driver list` reports RTL-SDR sticks (librtlsdr's table of USB IDs) and their current
  driver, as JSON, without admin rights.
- `rtlsdr-driver install` runs elevated, through Windows' permission prompt, when a stick without
  WinUSB is plugged in.

**What the install changes on the PC** (your IT people may want to know, since it touches the
machine's certificate stores):

- libwdi writes a driver package beside `rtlsdr-driver.exe`, in Program Files, where only
  administrators can write: an INF for `USB\VID_xxxx&PID_xxxx&MI_00` that uses the WinUSB driver
  built into Windows, plus a catalog file. The helper deletes it afterwards.
- To sign the catalog, libwdi creates a self-signed certificate named after the device
  (`CN=USB\VID_0BDA&PID_2838&MI_00 (libwdi autogenerated)`). It adds the certificate to the
  machine's Trusted Root and Trusted Publisher stores, signs the catalog, and deletes the
  certificate's private key, so it can't sign anything else.
- Windows then installs the package for that interface only. Other interfaces and devices are left
  alone. Programs that used the stick as a TV tuner won't work with it afterwards, as with Zadig.

`native/patches/libwdi-inbox-winusb.patch` drops libwdi's Windows 7 co-installers and uses the INF
form Microsoft documents for Windows 8 and later.

### readsb on Windows

readsb is written for Linux: it uses epoll, eventfd, POSIX signals, `fcntl`, `mmap` and BSD
sockets as plain ints. The Windows build keeps upstream readsb nearly unchanged, with shims for the
rest:

| Piece | Where |
|---|---|
| Pinned sources: readsb, librtlsdr, libusb, zstd, zlib, wepoll, libwdi | `native/cmake/deps.cmake` |
| Build: readsb and its dependencies, statically linked into one `readsb.exe` | `native/CMakeLists.txt` |
| POSIX layer, force-included into every readsb file | `native/compat/win_compat.h`, `win_compat.c` |
| Stand-ins for `<sys/socket.h>`, `<sys/epoll.h>`, `<termios.h>`, … | `native/compat/include/` |
| The few source changes the shims can't make (each explained in its header) | `native/patches/readsb-windows.patch` |
| librtlsdr with WinUSB's RAW_IO on for the sample stream (see MLAT timing below) | `native/patches/rtlsdr-winusb-raw-io.patch` |
| zlib with upstream's unreleased gzwrite overflow fix | `native/patches/zlib-gzwrite-overflow.patch` |

How the shims work:

- **File descriptors.** The shims give out ints in separate ranges (C runtime fds, wepoll handles,
  sockets) and route each `read`, `write` and `close` by range.
- **epoll** is [wepoll](https://github.com/piscisaureus/wepoll). Without eventfd, readsb takes the
  same route as its macOS build (`NO_EVENT_FD`).
- **errno.** Winsock errors are translated to errno values.
- **Windows differences that would fail quietly:** `rename()` replaces an existing target as in
  POSIX; files open in binary mode; invalid fds return `EBADF` instead of aborting; `SO_REUSEADDR`
  is dropped, since on Windows it would let a second process take a listening port; absolute-time
  sleeps use a high-resolution waitable timer.
- **Stopping.** readsb shuts down cleanly when its stdin closes, which is how the app stops it.
- **Not supported:** serial Beast devices, globe-history symlinks and the interactive console mode.
  The feeder needs none of them.

### MLAT timing on Windows

MLAT compares when the same message reached different receivers, using readsb's timestamps, which
count the stick's samples. Losing samples between the stick and readsb shifts the count, and the
MLAT server sees a clock jump. Without WinUSB's RAW_IO policy, Windows passes queued USB reads on
one at a time, and in the gap between two the RTL2832U's small FIFO can overflow. librtlsdr doesn't
ask for RAW_IO, so `native/patches/rtlsdr-winusb-raw-io.patch` turns it on (readsb's log says
"WinUSB RAW_IO on for the sample stream"). With it, a desktop feeder's bad-sync score stays at 0
over long runs.

To check a feeder: the 10-minute `mlat:` lines in `readsb.log` give the MLAT server's figures
(nearby feeders in sync, outliers, and a bad-sync timeout, where anything above 0 means the server
isn't using it for positions).

## Building

Needs JDK 17, Docker Desktop and PowerShell 7 (`tools\spike-test.ps1` uses an API Windows
PowerShell 5.1 lacks). Nothing else is installed: the C compiler is the
`mstorsjo/llvm-mingw` image, pinned by digest.

```powershell
$env:JAVA_HOME = '<path to a JDK 17>'
tools\build-native.ps1          # -> native\build\readsb.exe and rtlsdr-driver.exe
.\gradlew.bat :app:test         # the tests
.\gradlew.bat :app:run          # the app (uses your real settings folder)
.\gradlew.bat :app:buildMsi     # the installer, into Releases\
```

- **Without a stick or reaching ADS-B Exchange:** point `FEEDER_HOME` at a scratch folder whose
  `settings.json` has `"feedAdsbx": false`.
- **The screens with live data and no stick:** make a capture with
  `java tools/adsb-sim/AdsbSim.java tools/testdata/preview.iq tools/testdata/preview.json 120`, then
  run `.\gradlew.bat :app:runPreview`. The feed and MLAT go to stand-ins on 127.0.0.1.
- **The engine tests** need `tools\testdata\spike.iq`, which `tools\spike-test.ps1` creates; they're
  skipped without it. `tools\spike-test.ps1` also checks readsb end to end against local stand-ins.
  Tests never reach ADS-B Exchange: test runs accept only 127.0.0.1 targets.
- **Dependencies are locked:** `app/gradle.lockfile` and `buildscript-gradle.lockfile` hold every
  version, and `gradle/verification-metadata.xml` every artifact's SHA-256. To change one, edit
  `gradle/libs.versions.toml` and build with `--write-locks --write-verification-metadata sha256`.
- **The installer** is WiX 3 (`installer/Product.wxs`), which the build downloads and checks
  against a pinned SHA-256. It installs per machine to `C:\Program Files\ADS-B Exchange Feeder`.
- **Release builds are signed** (maintainers): `.\gradlew.bat :app:buildMsi -Psign` signs the
  launcher, `readsb.exe` and `rtlsdr-driver.exe`, then the MSI, through Azure Artifact Signing, and
  verifies each signature. It needs the Artifact Signing client tools, an `az login` with signing
  rights, and the signing account's `metadata.json` (default `C:\ArtifactSigning\metadata.json`).

## Security

- Every source is pinned: C libraries by commit, JVM libraries by version and SHA-256, the build
  image by digest, WiX by SHA-256.
- `tools/security-scan.ps1` runs gitleaks, osv-scanner, semgrep and syft in containers, and writes
  `security/scan-summary.md` and a CycloneDX SBOM (`security/sbom.cdx.json`).
- To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Licensing

GPL-3.0-or-later ([LICENSE](LICENSE)): readsb and the MLAT client are GPL-3, and librtlsdr is
GPL-2.0-or-later. `readsb.exe` also contains wepoll (BSD-2-Clause), zlib (zlib licence), libusb
(LGPL-2.1+) and zstd (BSD). `rtlsdr-driver.exe` contains libwdi (LGPL-3.0+), modified as described
above. The installer also contains the Java libraries (Compose Multiplatform, Kotlin, AndroidX and
others: Apache-2.0), JNA (used under Apache-2.0), org.json (public domain), Skiko with Skia
(BSD-3-Clause) and ICU data (Unicode), a Java runtime (Eclipse Temurin 17, GPL-2.0 with the
Classpath Exception), DM Sans (SIL OFL) and airport data from [OurAirports](https://ourairports.com/data/)
(public domain).

In the app, Settings > About shows the copyright, the no-warranty notice and a **Source code**
button that opens this repository at the exact commit the build came from. **Open-source
licences** lists every component with its full licence text.
