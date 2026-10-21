<#
.SYNOPSIS
    End-to-end test of the Windows readsb.exe without a dongle and without ADS-B Exchange.

.DESCRIPTION
    1. Generates a synthetic 1090 MHz capture (tools/adsb-sim/AdsbSim.java, from the phone feeder).
    2. Starts two local stand-ins on 127.0.0.1: a feed sink in place of feed1.adsbexchange.com,
       and a gpsd server that hands readsb the receiver position (never on the command line).
    3. Runs readsb.exe on the capture at real-time speed, with the app's intended options.
    4. While it runs: reads Beast data from its output port and checks receiver.json.
    5. Afterwards: compares the decoded aircraft with what the simulator put in, checks the
       feed reached the sink with the feeder UUID, and that the log has no coordinates.
    6. Runs again and closes readsb's stdin: it must shut down cleanly (the desktop app's stop).

    Test data never reaches the real ADS-B Exchange: every connection is to 127.0.0.1.

.PARAMETER Seconds
    Length of the synthetic capture (default 20).
#>
#Requires -Version 7.0
param([int]$Seconds = 20)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$exe = Join-Path $root 'native\build\readsb.exe'
$out = Join-Path $root 'tools\testdata'
if (-not (Test-Path $exe)) { throw "No $exe; run tools\build-native.ps1 first" }
New-Item -ItemType Directory -Force $out | Out-Null

$script:failures = 0
function Step([string]$m) { Write-Host "`n== $m" -ForegroundColor Cyan }
function Check([bool]$ok, [string]$m) {
    if ($ok) { Write-Host "  PASS  $m" -ForegroundColor Green }
    else { Write-Host "  FAIL  $m" -ForegroundColor Red; $script:failures++ }
}
function FreePort {
    $l = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop(); $p
}

# --- 1. synthetic capture ---------------------------------------------------------------
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$iq = Join-Path $out 'spike.iq'
$expectedFile = Join-Path $out 'expected.json'
$simSrc = Join-Path $root 'tools\adsb-sim\AdsbSim.java'
if (-not (Test-Path $iq) -or -not (Test-Path $expectedFile) -or
    (Get-Item $simSrc).LastWriteTime -gt (Get-Item $iq).LastWriteTime -or
    ((Get-Content $expectedFile -Raw | ConvertFrom-Json).seconds -ne $Seconds)) {
    Step 'Generating synthetic capture'
    & $java $simSrc $iq $expectedFile $Seconds 2>&1 | Where-Object { $_ -notmatch 'JAVA_TOOL_OPTIONS' }
    if ($LASTEXITCODE) { throw 'AdsbSim failed' }
}
$expected = Get-Content $expectedFile -Raw | ConvertFrom-Json
$rx = $expected.receiver

# --- 2. local stand-ins -----------------------------------------------------------------
# Feed sink: accepts readsb's feed connection and saves everything it sends.
$sinkFile = Join-Path $out 'sink.bin'
$sink = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$sink.Start()
$sinkPort = $sink.LocalEndpoint.Port
$sinkAccept = $sink.AcceptTcpClientAsync()

# gpsd stand-in: VERSION on connect, then a TPV report, as the phone's GpsdServer does.
$gpsd = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$gpsd.Start()
$gpsdPort = $gpsd.LocalEndpoint.Port
$gpsdAccept = $gpsd.AcceptTcpClientAsync()
$gpsdLines = '{"class":"VERSION","release":"3.25","rev":"adsbx-desktop-spike","proto_major":3,"proto_minor":14}' + "`n" +
    [string]::Format([Globalization.CultureInfo]::InvariantCulture,
        '{{"class":"TPV","mode":3,"lat":{0:F7},"lon":{1:F7},"alt":100.0}}', $rx.lat, $rx.lon) + "`n"

# A desktop-tagged test UUID (desktop feeders end in ad5bde5c).
$uuid = ([guid]::NewGuid().ToString()).Substring(0, 28) + 'ad5bde5c'
$beastPort = FreePort

function StartReadsb([string]$jsonDir) {
    New-Item -ItemType Directory -Force $jsonDir | Out-Null
    Get-ChildItem $jsonDir | Remove-Item -Force
    $psi = [Diagnostics.ProcessStartInfo]::new($exe)
    foreach ($a in @(
            '--device-type', 'ifile', '--ifile', $iq, '--iformat', 'UC8', '--throttle',
            '--quiet', '--db-file', 'none',
            '--write-json', $jsonDir, '--write-json-every', '1', '--json-location-accuracy', '2',
            '--net', '--net-bind-address', '127.0.0.1',
            '--net-bo-port', "$beastPort", '--net-ro-port', '0', '--net-ri-port', '0', '--net-bi-port', '0', '--net-sbs-port', '0',
            '--net-connector', "127.0.0.1,$gpsdPort,gpsd_in",
            '--net-connector', "127.0.0.1,$sinkPort,beast_reduce_plus_out,uuid=$uuid")) {
        $psi.ArgumentList.Add($a)
    }
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.Environment['READSB_EXIT_ON_STDIN_EOF'] = '1'
    $p = [Diagnostics.Process]::Start($psi)
    [pscustomobject]@{ Process = $p; Stdout = $p.StandardOutput.ReadToEndAsync(); Stderr = $p.StandardError.ReadToEndAsync() }
}

# --- 3/4. decode run --------------------------------------------------------------------
Step "Running readsb.exe on the capture ($Seconds s at real-time speed)"
$jsonDir = Join-Path $out 'json'
$run = StartReadsb $jsonDir
$started = Get-Date

# Serve gpsd once readsb connects.
if ($gpsdAccept.Wait(10000)) {
    $g = $gpsdAccept.Result
    $gs = $g.GetStream()
    $bytes = [Text.Encoding]::ASCII.GetBytes($gpsdLines)
    $gs.Write($bytes, 0, $bytes.Length)
    $gs.Flush()
}
Check $gpsdAccept.IsCompleted 'readsb connected to the gpsd stand-in'
Check ($sinkAccept.Wait(10000)) 'readsb connected to the feed sink'
$sinkFs = [IO.File]::Create($sinkFile)
$sinkCopy = if ($sinkAccept.IsCompleted) { $sinkAccept.Result.GetStream().CopyToAsync($sinkFs) } else { $null }

Start-Sleep -Seconds 5
# Beast output port, as the MLAT client will read it.
$beastBytes = 0
try {
    $c = [Net.Sockets.TcpClient]::new('127.0.0.1', $beastPort)
    $c.ReceiveTimeout = 3000
    $buf = New-Object byte[] 65536
    $until = (Get-Date).AddSeconds(2)
    $s = $c.GetStream()
    while ((Get-Date) -lt $until) {
        if ($s.DataAvailable) { $beastBytes += $s.Read($buf, 0, $buf.Length) } else { Start-Sleep -Milliseconds 50 }
    }
    $c.Close()
} catch { }
Check ($beastBytes -gt 0) "Beast output on 127.0.0.1:$beastPort ($beastBytes bytes in 2 s)"

$receiverJson = Join-Path $jsonDir 'receiver.json'
$receiver = if (Test-Path $receiverJson) { Get-Content $receiverJson -Raw | ConvertFrom-Json } else { $null }
Check ($receiver -and [math]::Abs($receiver.lat - $rx.lat) -lt 0.0001 -and [math]::Abs($receiver.lon - $rx.lon) -lt 0.0001) `
    "readsb got the receiver position over gpsd ($($receiver.lat), $($receiver.lon))"

$exited = $run.Process.WaitForExit(($Seconds + 30) * 1000)
$took = ((Get-Date) - $started).TotalSeconds
if (-not $exited) { $run.Process.Kill() }
$log = $run.Stderr.Result + $run.Stdout.Result
Set-Content -Encoding utf8 (Join-Path $out 'readsb.log') $log
# Upstream readsb treats the end of an input file as an unexpected SDR stop: "Abnormal exit", code 1.
Check ($exited -and $log -match 'Abnormal exit') "readsb exited at the end of the capture (code $(if ($exited) { $run.Process.ExitCode }))"
# --throttle paces the file at real time; that needs working absolute-time sleeps on Windows.
Check ($took -ge $Seconds - 1 -and $took -le $Seconds + 10) ("capture played at real-time speed ({0:N1} s for {1} s)" -f $took, $Seconds)

# The sink sees a reset when readsb's process ends; that's fine.
if ($sinkCopy) { try { $null = $sinkCopy.Wait(5000) } catch { } }
$sinkFs.Close()
$sink.Stop(); $gpsd.Stop()

# --- 5. results -------------------------------------------------------------------------
Step 'Feed'
$stats = Get-Content (Join-Path $jsonDir 'stats.json') -Raw | ConvertFrom-Json
$sent = [long]$stats.total.remote.bytes_out
$received = (Get-Item $sinkFile).Length
Check ($sent -gt 0 -and $received -gt 0) "feed sent by readsb ($sent bytes counted, incl. Beast port) and received by the sink ($received bytes)"
$onWire = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($sinkFile)).Contains($uuid)
Check $onWire "feed carries the desktop-tagged feeder UUID ($uuid)"

Step 'Log'
$coordLine = [regex]::Match($log, '(?im)^.*(--lat|--lon|\blat\b\W{0,3}-?\d+\.\d|location:\s*-?\d+\.\d|-76\.0).*$').Value
Check (-not $coordLine) "readsb log has no receiver coordinates $(if ($coordLine) { "($coordLine)" })"
$errLines = @($log -split "`n" | Where-Object { $_ -match '(?i)error|fatal|failed' })
Check ($errLines.Count -eq 0) "readsb log has no errors $(if ($errLines) { "(" + ($errLines -join ' | ') + ")" })"

Step 'Decoded aircraft'
$aircraft = (Get-Content (Join-Path $jsonDir 'aircraft.json') -Raw | ConvertFrom-Json).aircraft
$rows = foreach ($e in $expected.aircraft) {
    $a = $aircraft | Where-Object { $_.hex -eq $e.hex } | Select-Object -First 1
    $checks = [ordered]@{
        found    = [bool]$a
        flight   = $a -and ($a.flight -as [string]).Trim() -eq $e.flight
        alt_baro = $a -and $a.alt_baro -eq $e.alt_baro
        position = $a -and $null -ne $a.lat -and [math]::Abs($a.lat - $e.lat) -lt 0.001 -and [math]::Abs($a.lon - $e.lon) -lt 0.001
        gs       = $a -and $null -ne $a.gs -and [math]::Abs($a.gs - $e.gs) -lt 1
        track    = $a -and $null -ne $a.track -and [math]::Abs($a.track - $e.track) -lt 1
        rate     = $a -and $a.baro_rate -eq $e.baro_rate
    }
    $ok = -not ($checks.Values -contains $false)
    if (-not $ok) { $script:failures++ }
    [pscustomobject]@{
        hex = $e.hex; result = $(if ($ok) { 'PASS' } else { 'FAIL: ' + (($checks.GetEnumerator() | Where-Object { -not $_.Value } | ForEach-Object Key) -join ',') })
        flight = $a.flight; alt = $a.alt_baro; lat = $a.lat; lon = $a.lon; gs = $a.gs; track = $a.track; rate = $a.baro_rate
    }
}
$rows | Format-Table -AutoSize | Out-String | Write-Host
$mo = $aircraft | Where-Object { $_.hex -eq $expected.modes_only.hex } | Select-Object -First 1
Check ($mo -and $mo.alt_baro -eq $expected.modes_only.alt_baro) "Mode S-only aircraft decoded ($($expected.modes_only.hex) at $($mo.alt_baro) ft)"
Check ($stats.total.messages_valid -gt 0) "stats.json counts $($stats.total.messages_valid) valid messages"

# --- 6. clean stop ----------------------------------------------------------------------
Step 'Stop by closing stdin'
# No feed or gpsd this time: readsb just retries those connections in the background.
$sink = $null
$run = StartReadsb (Join-Path $out 'json-stop')
Start-Sleep -Seconds 3
$run.Process.StandardInput.Close()
$exited = $run.Process.WaitForExit(10000)
if (-not $exited) { $run.Process.Kill() }
$log2 = $run.Stderr.Result + $run.Stdout.Result
Check ($exited -and $run.Process.ExitCode -eq 0) "readsb exited cleanly when stdin closed (code $(if ($exited) { $run.Process.ExitCode } else { 'none: killed' }))"
Check ($log2 -match 'Caught SIGTERM') 'readsb logged a SIGTERM-style shutdown'

Write-Host ''
if ($script:failures) { Write-Host "$script:failures check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host 'All checks passed' -ForegroundColor Green
