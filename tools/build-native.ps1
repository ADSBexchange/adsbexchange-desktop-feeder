<#
.SYNOPSIS
    Builds readsb.exe and rtlsdr-driver.exe for Windows x64 by cross-compiling in a pinned
    llvm-mingw container.

.DESCRIPTION
    Nothing is installed on this machine: the compiler, CMake and Ninja come from the
    mstorsjo/llvm-mingw image, pinned by digest. Third-party sources are fetched at pinned
    commits by native/cmake/deps.cmake into native/third_party (git-ignored).

    Output: native/build/readsb.exe and native/build/rtlsdr-driver.exe (statically linked;
    they need only Windows system DLLs).

.PARAMETER Clean
    Delete native/build first (third-party sources are kept).
#>
param([switch]$Clean)

$ErrorActionPreference = 'Stop'

# llvm-mingw 2026-10-06 build (tag 20261006): clang 23.1.3, CMake 3.28.3, Ninja 1.11.1.
$Image = 'mstorsjo/llvm-mingw@sha256:3f535cae24312a5bb25968ef670ea070e422da01f3f642bac550bca781bac29e'

$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Build = Join-Path $Repo 'native\build'

if ($Clean -and (Test-Path $Build)) {
    Remove-Item -Recurse -Force $Build
}

# The build itself is tools/build-native.sh, which CI runs too.
docker run --rm -v "${Repo}:/src" $Image sh /src/tools/build-native.sh
if ($LASTEXITCODE -ne 0) {
    throw "Native build failed (exit $LASTEXITCODE)"
}

Write-Host "Built $Build\readsb.exe and $Build\rtlsdr-driver.exe"
