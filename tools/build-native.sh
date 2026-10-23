#!/bin/sh
# Builds native/build/readsb.exe and native/build/rtlsdr-driver.exe. Runs inside the pinned
# mstorsjo/llvm-mingw container with the repository mounted at /src: tools/build-native.ps1 starts
# it on Windows, and the CI workflow on Linux.
set -e
git config --global --add safe.directory '*'
cmake -S /src/native -B /src/native/build -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE=/src/native/cmake/mingw-x64.cmake \
    -DCMAKE_BUILD_TYPE=Release
cmake --build /src/native/build
x86_64-w64-mingw32-strip /src/native/build/readsb.exe /src/native/build/rtlsdr-driver.exe
