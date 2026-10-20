<#
.SYNOPSIS
    The security scans to run before each release.

.DESCRIPTION
    Runs each scanner in a throwaway container with the repository mounted read-only. Nothing is
    built or installed: dependencies are read from the Gradle lockfiles, the C libraries from the
    commits pinned in native/cmake/deps.cmake.

      Secrets          gitleaks, over every commit and over the working tree
      Dependencies     osv-scanner on the lockfiles; OSV's API for the pinned C library commits
      Static analysis  semgrep (Kotlin, Java, C, secrets), on this project's code only
      SBOM             syft (CycloneDX), plus the C libraries and the bundled Java runtime, which
                       no scanner can identify

    Writes security/sbom.cdx.json and security/scan-summary.md (both committed) and the raw scanner output to build/security-scan. Exits 1 if there is anything to
    act on: a secret, a known vulnerability in a library that ships in the MSI, or an open
    static-analysis finding. Vulnerabilities in build tools (the Kotlin and Compose Gradle
    plugins) are listed but don't fail the scan, since they never reach a user's PC.

    Needs Docker. semgrep downloads its rules from semgrep.dev and runs with telemetry off; the
    two OSV lookups send package names and versions (or commit hashes) to osv.dev.

.PARAMETER JavaHome
    The JDK whose runtime jpackage bundles into the MSI (default: JAVA_HOME).

.EXAMPLE
    pwsh -File tools\security-scan.ps1
#>
[CmdletBinding()]
param([string]$JavaHome = $env:JAVA_HOME)
if (-not $JavaHome) { throw 'Set JAVA_HOME (or -JavaHome) to the JDK 17 the MSI is built with.' }

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$raw = Join-Path $root 'build\security-scan'
$committed = Join-Path $root 'security'
New-Item -ItemType Directory -Force $raw, $committed | Out-Null

# Pinned by digest (the phone repo's). To update one: docker pull <name>:latest, then copy its RepoDigest here.
$images = [ordered]@{
    gitleaks = @{ ref = 'zricethezav/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f'; version = '8.30.1' }
    osv      = @{ ref = 'ghcr.io/google/osv-scanner@sha256:afd838850ac1a0fcc15ff4a041dc9ba11123c3f0d2666217a5f0fcf9222b55fa'; version = '2.6.0' }
    semgrep  = @{ ref = 'semgrep/semgrep@sha256:acaac22ffc7b7cc5926de0751b223bce0b2491c33d18422fa72f632c78d81198'; version = '1.177.0' }
    syft     = @{ ref = 'anchore/syft@sha256:500e2d872ac019436926e8322b4fc1f39441d94d21f6f4046c6ff29b30e8cb02'; version = '1.52.0' }
}
$mount = @('-v', "${root}:/repo:ro", '-v', "${raw}:/out")
$blocking = [System.Collections.Generic.List[string]]::new()
# Not ours, or not source: upstream clones, build output, the MSI, test captures.
$notOurs = 'native/third_party', 'native/build', 'build', 'app/build', '.gradle', '.kotlin', 'Releases', 'tools/testdata'

function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Run([string[]] $dockerArgs) {
    & docker run --rm @dockerArgs 2>&1 | ForEach-Object { Write-Verbose "$_" }
    $LASTEXITCODE
}
function ReadJson($name) {
    $f = Join-Path $raw $name
    if (Test-Path $f) { Get-Content $f -Raw | ConvertFrom-Json } else { $null }
}

# --- secrets ----------------------------------------------------------------------------------
Step 'Secrets (gitleaks)'
$gitleaksArgs = @('--config', '/repo/.gitleaks.toml', '--gitleaks-ignore-path', '/repo/.gitleaksignore', '--redact', '--no-banner', '--report-format', 'json')
Run (@('--network', 'none') + $mount + @($images.gitleaks.ref, 'git', '/repo') + $gitleaksArgs + @('--report-path', '/out/gitleaks-history.json')) | Out-Null
Run (@('--network', 'none') + $mount + @($images.gitleaks.ref, 'dir', '/repo') + $gitleaksArgs + @('--report-path', '/out/gitleaks-worktree.json')) | Out-Null
$commits = [int](git -C $root rev-list --all --count)
$leaks = @(ReadJson 'gitleaks-history.json') + @(ReadJson 'gitleaks-worktree.json') | Where-Object { $_ }
Write-Host "  $commits commits and the working tree: $($leaks.Count) finding(s)"
foreach ($l in $leaks) { $blocking.Add("Secret: $($l.RuleID) in $($l.File -replace '^/repo/','') line $($l.StartLine)") }

# --- dependencies -----------------------------------------------------------------------------
Step 'Dependencies (osv-scanner, from the lockfiles)'
Run ($mount + @($images.osv.ref, 'scan', 'source', '-L', '/repo/app/gradle.lockfile', '-L', '/repo/buildscript-gradle.lockfile',
        '--format', 'json', '--output', '/out/osv.json')) | Out-Null
$osv = ReadJson 'osv.json'
if (-not $osv) { throw 'osv-scanner produced no report' }

# Every locked package ("group:name:version") and whether it ships: the lockfiles record which
# Gradle configurations use it, and only runtimeClasspath goes into the MSI.
$locked = @{}
foreach ($lockfile in 'app\gradle.lockfile', 'buildscript-gradle.lockfile') {
    foreach ($line in Get-Content (Join-Path $root $lockfile)) {
        if ($line -match '^([^#=][^=]*:[^:=]+)=(.+)$') {
            $key, $configurations = $Matches[1], ($Matches[2] -split ',')
            $ships = [bool]($configurations -match '^runtimeClasspath$')
            $locked[$key] = $locked[$key] -or $ships
        }
    }
}
$shippedPackages = @{}
foreach ($k in $locked.Keys) { if ($locked[$k]) { $shippedPackages[($k -replace ':[^:]+$', '')] = $true } }
$packageCount = $locked.Count
$vulnRows = @{}
foreach ($res in $osv.results) {
    foreach ($p in $res.packages) {
        $key = "$($p.package.name) $($p.package.version)"
        if ($vulnRows.ContainsKey($key)) { continue }
        $vulnRows[$key] = [pscustomobject]@{
            Package = $key
            Shipped = [bool]$shippedPackages[$p.package.name]
            Ids     = ($p.vulnerabilities | ForEach-Object { $_.id }) -join ', '
            Summary = ($p.vulnerabilities | ForEach-Object { $_.summary } | Where-Object { $_ } | Select-Object -Unique) -join '; '
            MaxCvss = (($p.groups | ForEach-Object { [double]($_.max_severity -as [double]) }) | Measure-Object -Maximum).Maximum
        }
    }
}
$vulns = $vulnRows.Values | Sort-Object @{ e = 'Shipped'; Descending = $true }, @{ e = 'MaxCvss'; Descending = $true }
$shippedVulns = @($vulns | Where-Object Shipped)
Write-Host "  $packageCount packages: $($shippedVulns.Count) vulnerable in the MSI, $(@($vulns).Count - $shippedVulns.Count) in build tools only"
foreach ($v in $shippedVulns) { $blocking.Add("Vulnerable library in the MSI: $($v.Package) ($($v.Ids))") }

Step 'C libraries (OSV, by pinned commit)'
$native = foreach ($line in Get-Content (Join-Path $root 'native\cmake\deps.cmake')) {
    if ($line -match '^fetch_dep\((\S+)\s+(\S+)\s+([0-9a-f]{40})\)\s*#\s*(.*)$') {
        [pscustomobject]@{
            Name = $Matches[1]; Url = $Matches[2]; Commit = $Matches[3]; Note = $Matches[4].Trim()
            Exe = $(if ($Matches[1] -eq 'libwdi') { 'rtlsdr-driver.exe' } else { 'readsb.exe' })
        }
    }
}
# Advisories our own patches fix: a "Fixes:" line in the header of native/patches/<library>-*.patch.
# OSV matches by commit, so it still reports them against the pinned upstream commit.
$fixedBy = @{}
foreach ($p in Get-ChildItem (Join-Path $root 'native\patches') -Filter '*.patch') {
    $lib = ($p.BaseName -split '-')[0]
    foreach ($l in Get-Content $p.FullName) {
        if ($l -like 'diff --git*') { break }
        if ($l -match '^Fixes:\s*(.+)$') {
            foreach ($id in ($Matches[1] -split '[,\s]+' | Where-Object { $_ })) { $fixedBy["$lib|$id"] = $p.Name }
        }
    }
}
foreach ($n in $native) {
    $r = Invoke-RestMethod -Method Post -Uri 'https://api.osv.dev/v1/query' -ContentType 'application/json' `
        -Body (@{ commit = $n.Commit } | ConvertTo-Json) -TimeoutSec 60
    $ids = @($r.vulns | Where-Object { $_ } | ForEach-Object { $_.id })
    $open = @($ids | Where-Object { -not $fixedBy["$($n.Name)|$_"] })
    $shown = $ids | ForEach-Object { if ($fixedBy["$($n.Name)|$_"]) { "$_ (fixed by native/patches/$($fixedBy["$($n.Name)|$_"]))" } else { $_ } }
    $n | Add-Member Advisories ($shown -join ', ')
    $n | Add-Member OpenAdvisories $open.Count
    Write-Host ("  {0,-7} {1}  {2}" -f $n.Name, $n.Commit.Substring(0, 10), $(if ($ids) { $shown -join ', ' } else { 'no known advisories' }))
    foreach ($id in $open) { $blocking.Add("Vulnerable C library: $($n.Name) @ $($n.Commit.Substring(0, 10)) ($id)") }
}

Step 'Java runtime bundled into the MSI'
$jre = @{}
$releaseFile = Join-Path $JavaHome 'release'
if (Test-Path $releaseFile) {
    foreach ($line in Get-Content $releaseFile) { if ($line -match '^([A-Z_]+)="?([^"]*)"?$') { $jre[$Matches[1]] = $Matches[2] } }
}
$jreName = if ($jre.IMPLEMENTOR_VERSION) { $jre.IMPLEMENTOR_VERSION } else { "Java $($jre.JAVA_VERSION)" }
Write-Host "  $jreName ($($jre.IMPLEMENTOR), released $($jre.JAVA_VERSION_DATE)) from $JavaHome"

# --- static analysis --------------------------------------------------------------------------
Step 'Static analysis (semgrep)'
# semgrep's image insists on its code being mounted at /src.
$semgrepExcludes = $notOurs | ForEach-Object { '--exclude', $_ }
Run (@('-v', "${root}:/src:ro", '-v', "${raw}:/out", '-w', '/src', $images.semgrep.ref, 'semgrep', 'scan', '--metrics=off',
        '--config', 'p/default', '--config', 'p/kotlin', '--config', 'p/java', '--config', 'p/c', '--config', 'p/secrets') +
    $semgrepExcludes + @('--json', '--output', '/out/semgrep.json')) | Out-Null
$semgrep = ReadJson 'semgrep.json'
if (-not $semgrep) { throw 'semgrep produced no report' }
$findings = @($semgrep.results)
$suppressed = (Select-String -Path (git -C $root ls-files | ForEach-Object { Join-Path $root $_ }) -Pattern 'nosemgrep' -SimpleMatch |
    Where-Object { $_.Path -notmatch '(security-scan\.ps1|\.md)$' }).Count
$partial = @($semgrep.errors | ForEach-Object { $_.path } | Where-Object { $_ } | Sort-Object -Unique)
Write-Host "  $($semgrep.paths.scanned.Count) files: $($findings.Count) open finding(s), $suppressed reviewed and annotated in the code"
foreach ($f in $findings) { $blocking.Add("Static analysis: $($f.check_id -replace '.*\.','') at $($f.path):$($f.start.line)") }

# --- SBOM -------------------------------------------------------------------------------------
Step 'Software Bill of Materials (syft + the C libraries + the Java runtime)'
$syftExcludes = $notOurs | ForEach-Object { '--exclude', "./$_/**" }
Run (@('--network', 'none') + $mount + @($images.syft.ref, 'scan', 'dir:/repo') + $syftExcludes +
    @('-o', 'cyclonedx-json=/out/sbom-syft.cdx.json')) | Out-Null
$sbom = ReadJson 'sbom-syft.cdx.json'
if (-not $sbom) { throw 'syft produced no SBOM' }
# syft reads app/gradle.lockfile but not buildscript-gradle.lockfile (the build's own plugins), so
# add what it missed. Mark each package "required" if it ships in the MSI and "excluded" if only
# the build uses it (CycloneDX: excluded = not part of what is delivered).
$seen = @{}
foreach ($c in @($sbom.components)) {
    if ($c.purl -match '^pkg:maven/([^/]+)/([^@]+)@([^?]+)') {
        $key = "$($Matches[1]):$($Matches[2]):$($Matches[3])"
        $seen[$key] = $true
        if ($locked.ContainsKey($key)) { $c | Add-Member -Force scope $(if ($locked[$key]) { 'required' } else { 'excluded' }) }
    }
}
$missing = foreach ($key in $locked.Keys | Where-Object { -not $seen[$_] } | Sort-Object) {
    $g, $n, $v = $key -split ':'
    [ordered]@{
        'bom-ref' = "pkg:maven/$g/$n@$v"; type = 'library'; group = $g; name = $n; version = $v
        purl = "pkg:maven/$g/$n@$v"; scope = $(if ($locked[$key]) { 'required' } else { 'excluded' })
    }
}
$extra = @($native | ForEach-Object {
        $gh = [regex]::Match($_.Url, 'github\.com/([^/]+)/([^/.]+)')
        [ordered]@{
            'bom-ref'          = "native:$($_.Name)"
            type               = 'library'
            scope              = 'required'
            name               = $gh.Groups[2].Value
            version            = $_.Commit
            description        = "C library compiled into $($_.Exe) ($($_.Note))"
            purl               = "pkg:github/$($gh.Groups[1].Value)/$($gh.Groups[2].Value)@$($_.Commit)"
            externalReferences = @(@{ type = 'vcs'; url = $_.Url })
        }
    })
if ($jre.JAVA_RUNTIME_VERSION) {
    $extra += [ordered]@{
        'bom-ref'   = 'runtime:java'
        type        = 'framework'
        scope       = 'required'
        name        = $jreName
        version     = $jre.JAVA_RUNTIME_VERSION
        description = "Java runtime bundled into the MSI by jpackage ($($jre.IMPLEMENTOR), $($jre.JAVA_VERSION_DATE))"
    }
}
$sbom.components = @($sbom.components) + @($missing) + $extra
$sbom | ConvertTo-Json -Depth 30 | Set-Content -Encoding utf8 (Join-Path $committed 'sbom.cdx.json')
$inMsi = @($sbom.components | Where-Object { $_.scope -eq 'required' }).Count
Write-Host "  $(@($sbom.components).Count) components ($inMsi in the MSI) -> security/sbom.cdx.json"

# --- summary ----------------------------------------------------------------------------------
$head = git -C $root rev-parse --short HEAD
$dirty = if (git -C $root status --porcelain -- . ':!security') { ' plus uncommitted changes' } else { '' }
$md = [System.Text.StringBuilder]::new()
function Line($s = '') { [void]$md.AppendLine($s) }
Line '# Security scan summary'
Line
Line "Generated by ``tools/security-scan.ps1`` on $(Get-Date -Format 'yyyy-MM-dd HH:mm zzz') at commit ``$head``$dirty."
Line 'Every scanner ran in a container with the repository mounted read-only; nothing was built or installed.'
Line
Line '| Check | Tool | Result |'
Line '|---|---|---|'
Line "| Secret scanning across full history | gitleaks $($images.gitleaks.version) | $commits commits and the working tree: **$($leaks.Count) findings** |"
Line "| Dependency vulnerability scan from the lockfile | osv-scanner $($images.osv.version) | $packageCount packages: **$($shippedVulns.Count) vulnerable in the MSI**, $(@($vulns).Count - $shippedVulns.Count) in build tools only |"
Line "| Dependency vulnerability scan, C libraries | OSV API, by commit | $(@($native | Where-Object OpenAdvisories).Count) of $(@($native).Count) with open advisories; $(@($native | Where-Object { $_.Advisories -and -not $_.OpenAdvisories }).Count) with advisories fixed by our patches (see note) |"
Line "| Bundled Java runtime | release file | $jreName, released $($jre.JAVA_VERSION_DATE): check it's the latest quarterly update before each release |"
Line "| Static application security testing | semgrep $($images.semgrep.version) | $($semgrep.paths.scanned.Count) files: **$($findings.Count) open findings**, $suppressed reviewed and annotated in the code |"
Line "| Software Bill of Materials | syft $($images.syft.version) | ``security/sbom.cdx.json``, $(@($sbom.components).Count) components, $inMsi of them in the MSI (CycloneDX) |"
Line '| Configuration and container scanning | - | Not applicable: nothing is containerised |'
Line
if (@($vulns).Count) {
    Line '## Known vulnerabilities'
    Line
    Line '| Package | Where | Advisories | Max CVSS | Summary |'
    Line '|---|---|---|---|---|'
    foreach ($v in $vulns) { Line "| $($v.Package) | $(if ($v.Shipped) { '**MSI**' } else { 'build tools' }) | $($v.Ids) | $($v.MaxCvss) | $($v.Summary) |" }
    Line
    Line '"Build tools" means the package is only used by the build (the Kotlin and Compose Gradle'
    Line 'plugins and compilers), per the configurations recorded in the lockfiles. It never reaches'
    Line 'a user''s PC, but it does run on the build machine.'
    Line
}
Line '## C libraries'
Line
Line '| Library | In | Pinned commit | Upstream | Known advisories |'
Line '|---|---|---|---|---|'
foreach ($n in $native) { Line "| $($n.Name) ($($n.Note)) | $($n.Exe) | ``$($n.Commit)`` | $($n.Url) | $(if ($n.Advisories) { $n.Advisories } else { 'none' }) |" }
Line
Line 'OSV answers "no known advisories" both for a clean commit and for a repository it does not'
Line 'track, so for readsb, rtl-sdr-blog, wepoll and libwdi in particular this is weak evidence.'
Line
if ($findings.Count) {
    Line '## Open static-analysis findings'
    Line
    foreach ($f in $findings) { Line "- ``$($f.path):$($f.start.line)`` $($f.check_id): $(($f.extra.message -replace '\s+', ' ').Trim())" }
    Line
}
if ($partial.Count) {
    Line '## Files semgrep could only partly parse'
    Line
    foreach ($p in $partial) { Line "- ``$p``" }
    Line
}
Line '## Scanner images'
Line
foreach ($k in $images.Keys) { Line "- $k $($images[$k].version): ``$($images[$k].ref)``" }
$md.ToString() | Set-Content -Encoding utf8 (Join-Path $committed 'scan-summary.md')

Step 'Result'
if ($blocking.Count) {
    $blocking | ForEach-Object { Write-Host "  FAIL $_" -ForegroundColor Red }
    Write-Host "`nSECURITY SCAN: $($blocking.Count) item(s) to act on. See security/scan-summary.md" -ForegroundColor Red
    exit 1
}
Write-Host '  Nothing to act on. See security/scan-summary.md' -ForegroundColor Green
exit 0
