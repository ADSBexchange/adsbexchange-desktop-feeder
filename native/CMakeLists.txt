cmake_minimum_required(VERSION 3.22)
project(desktopfeeder_native C RC)

# Builds two Windows x64 programs, each statically linked with no DLL dependencies beyond Windows:
# - readsb.exe: readsb + librtlsdr + libusb (WinUSB backend) + zstd + zlib + wepoll;
# - rtlsdr-driver.exe: finds RTL-SDR sticks and installs WinUSB for them (libwdi).
# Run through tools/build-native.ps1 (cross-compiles in a pinned llvm-mingw container).

include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/deps.cmake)

set(CMAKE_C_STANDARD 11)
# Third-party code is compiled with warnings off; we don't maintain it.
set(THIRD_PARTY_FLAGS -w -O2)

# Exploit mitigations for everything, since readsb parses untrusted input (anyone can transmit on
# 1090 MHz) and rtlsdr-driver runs as administrator: stack canaries (as readsb's own Linux build)
# and Control Flow Guard. ASLR and DEP are on by default with this toolchain.
add_compile_options(-fstack-protector-strong -mguard=cf)
add_link_options(-mguard=cf)
link_libraries(ssp)
set(COMPAT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/compat)

# --- libusb (Windows backend: WinUSB, plus UsbDk if installed) -------------------------
add_library(usb STATIC
    ${libusb_SOURCE_DIR}/libusb/core.c
    ${libusb_SOURCE_DIR}/libusb/descriptor.c
    ${libusb_SOURCE_DIR}/libusb/hotplug.c
    ${libusb_SOURCE_DIR}/libusb/io.c
    ${libusb_SOURCE_DIR}/libusb/sync.c
    ${libusb_SOURCE_DIR}/libusb/strerror.c
    ${libusb_SOURCE_DIR}/libusb/os/events_windows.c
    ${libusb_SOURCE_DIR}/libusb/os/threads_windows.c
    ${libusb_SOURCE_DIR}/libusb/os/windows_common.c
    ${libusb_SOURCE_DIR}/libusb/os/windows_usbdk.c
    ${libusb_SOURCE_DIR}/libusb/os/windows_winusb.c)
target_include_directories(usb
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/config/libusb ${libusb_SOURCE_DIR}/libusb/os
    PUBLIC ${libusb_SOURCE_DIR}/libusb)
target_compile_options(usb PRIVATE ${THIRD_PARTY_FLAGS})

# --- zstd (readsb hard-depends on it for trace/API compression) -------------------------
file(GLOB ZSTD_SOURCES
    ${zstd_SOURCE_DIR}/lib/common/*.c
    ${zstd_SOURCE_DIR}/lib/compress/*.c
    ${zstd_SOURCE_DIR}/lib/decompress/*.c)
add_library(zstd STATIC ${ZSTD_SOURCES})
target_include_directories(zstd PUBLIC ${zstd_SOURCE_DIR}/lib PRIVATE ${zstd_SOURCE_DIR}/lib/common)
target_compile_definitions(zstd PRIVATE ZSTD_DISABLE_ASM ZSTD_MULTITHREAD=0)
target_compile_options(zstd PRIVATE ${THIRD_PARTY_FLAGS})

# --- wepoll (epoll API on top of Windows' AFD poll, for sockets) -------------------------
add_library(wepoll STATIC ${wepoll_SOURCE_DIR}/wepoll.c)
target_include_directories(wepoll PUBLIC ${wepoll_SOURCE_DIR})
target_compile_options(wepoll PRIVATE ${THIRD_PARTY_FLAGS})
target_link_libraries(wepoll PUBLIC ws2_32)

# --- patched copies --------------------------------------------------------------------
# patched_copy(<out dir> <source dir> <patch>): a copy of a fetched source tree with one of
# native/patches applied by "git apply". The patch must apply cleanly: after a dependency bump,
# configure fails here until the patch is updated. Redone when the patch or the revision changes.
function(patched_copy out src patch)
    file(SHA256 ${patch} hash)
    string(SUBSTRING "${hash}" 0 16 hash)
    file(GLOB fetched LIST_DIRECTORIES false RELATIVE ${src} ${src}/.fetched-*)
    set(stamp ${out}/.patched-${hash}${fetched})
    if(NOT EXISTS ${stamp})
        file(REMOVE_RECURSE ${out})
        file(COPY ${src}/ DESTINATION ${out} PATTERN ".git" EXCLUDE)
        # The ceiling stops git from finding this repository above the build directory, where
        # "git apply" would resolve paths from the repository root and skip the patch.
        execute_process(
            COMMAND ${CMAKE_COMMAND} -E env GIT_CEILING_DIRECTORIES=${CMAKE_BINARY_DIR}
                ${GIT_EXECUTABLE} apply --whitespace=nowarn ${patch}
            WORKING_DIRECTORY ${out}
            RESULT_VARIABLE rc)
        if(NOT rc EQUAL 0)
            message(FATAL_ERROR "${patch} no longer applies to ${src}; update it")
        endif()
        file(TOUCH ${stamp})
    endif()
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${patch})
endfunction()

# --- zlib (readsb gzips some JSON output) -----------------------------------------------
# v1.3.2 plus upstream's gzwrite fix, which isn't in a release yet (see the patch's header).
set(Z ${CMAKE_BINARY_DIR}/zlib-src)
patched_copy(${Z} ${zlib_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/patches/zlib-gzwrite-overflow.patch)
add_library(z STATIC
    ${Z}/adler32.c ${Z}/compress.c ${Z}/crc32.c ${Z}/deflate.c ${Z}/gzclose.c ${Z}/gzlib.c
    ${Z}/gzread.c ${Z}/gzwrite.c ${Z}/infback.c ${Z}/inffast.c ${Z}/inflate.c ${Z}/inftrees.c
    ${Z}/trees.c ${Z}/uncompr.c ${Z}/zutil.c)
target_include_directories(z PUBLIC ${Z})
target_compile_options(z PRIVATE ${THIRD_PARTY_FLAGS})

# --- librtlsdr (RTL-SDR Blog fork) -----------------------------------------------------
# With WinUSB's RAW_IO turned on for the sample stream, for MLAT (see the patch's header).
set(RTL ${CMAKE_BINARY_DIR}/rtlsdr-src)
patched_copy(${RTL} ${rtlsdr_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/patches/rtlsdr-winusb-raw-io.patch)
add_library(rtlsdr STATIC
    ${RTL}/src/librtlsdr.c
    ${RTL}/src/tuner_e4k.c
    ${RTL}/src/tuner_fc0012.c
    ${RTL}/src/tuner_fc0013.c
    ${RTL}/src/tuner_fc2580.c
    ${RTL}/src/tuner_r82xx.c)
target_include_directories(rtlsdr PUBLIC ${RTL}/include)
target_compile_definitions(rtlsdr PUBLIC rtlsdr_STATIC)
target_compile_options(rtlsdr PRIVATE ${THIRD_PARTY_FLAGS})
target_link_libraries(rtlsdr PUBLIC usb)

# --- readsb ----------------------------------------------------------------------------
set(R ${CMAKE_BINARY_DIR}/readsb-src)
patched_copy(${R} ${readsb_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/patches/readsb-windows.patch)

file(READ ${readsb_SOURCE_DIR}/version READSB_VERSION)
string(STRIP "${READSB_VERSION}" READSB_VERSION)

# The POSIX shims readsb is compiled against (without the force-include, which would
# redirect the shims' own calls to themselves).
add_library(wincompat STATIC ${COMPAT_DIR}/win_compat.c)
target_compile_definitions(wincompat PRIVATE _WIN32_WINNT=0x0A00)
target_link_libraries(wincompat PUBLIC wepoll ws2_32)

add_executable(readsb
    ${R}/readsb.c
    ${R}/argp.c ${R}/anet.c ${R}/interactive.c ${R}/mode_ac.c ${R}/mode_s.c ${R}/comm_b.c
    ${R}/json_out.c ${R}/net_io.c ${R}/crc.c ${R}/demod_2400.c
    ${R}/uat2esnt/uat2esnt.c ${R}/uat2esnt/uat_decode.c
    ${R}/stats.c ${R}/cpr.c ${R}/icao_filter.c ${R}/track.c ${R}/util.c ${R}/fasthash.c
    ${R}/convert.c ${R}/sdr_ifile.c ${R}/sdr_beast.c ${R}/sdr.c ${R}/ais_charset.c
    ${R}/globe_index.c ${R}/geomag.c ${R}/receiver.c ${R}/aircraft.c ${R}/api.c
    ${R}/threadpool.c ${R}/sdr_rtlsdr.c)
target_include_directories(readsb PRIVATE ${R} ${COMPAT_DIR} ${COMPAT_DIR}/include)
target_compile_definitions(readsb PRIVATE
    _GNU_SOURCE _DEFAULT_SOURCE _POSIX_THREAD_SAFE_FUNCTIONS
    _WIN32_WINNT=0x0A00
    NO_EVENT_FD
    ENABLE_RTLSDR DISABLE_INTERACTIVE
    # --enable-biastee: power an amplifier through the antenna cable (Settings; off by default).
    ENABLE_RTLSDR_BIASTEE
    MODES_READSB_VERSION="${READSB_VERSION} desktopfeeder"
    READSB_SHORT_VERSION="${READSB_VERSION}"
    READSB_SHORT_COMMIT="windows")
target_compile_options(readsb PRIVATE ${THIRD_PARTY_FLAGS} -include ${COMPAT_DIR}/win_compat.h)
target_link_libraries(readsb PRIVATE wincompat rtlsdr zstd z wepoll ws2_32 pthread -static)

# --- rtlsdr-driver: WinUSB installer for RTL-SDR sticks (libwdi) ------------------------
# libwdi embeds its inf/cat templates and a small installer exe into the library at build time,
# with an "embedder" program that runs on the build host. The patch switches its WinUSB support
# to the driver built into Windows 8+ (no WDK co-installers to embed).
set(W ${CMAKE_BINARY_DIR}/libwdi-src)
patched_copy(${W} ${libwdi_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/patches/libwdi-inbox-winusb.patch)
set(WDI_CONFIG ${CMAKE_CURRENT_SOURCE_DIR}/config/libwdi)
set(WDI_GEN ${CMAKE_BINARY_DIR}/libwdi-gen)
file(MAKE_DIRECTORY ${WDI_GEN})

# The installer libwdi runs (elevated) to install a prepared driver package.
add_executable(installer_x64 ${W}/libwdi/installer.c)
target_include_directories(installer_x64 PRIVATE ${WDI_CONFIG} ${W} ${W}/libwdi)
target_compile_options(installer_x64 PRIVATE ${THIRD_PARTY_FLAGS})
# It runs elevated: static imports (newdev, setupapi) come from System32 only, never its own folder.
target_link_libraries(installer_x64 PRIVATE setupapi newdev ole32 -static -Wl,--dependent-load-flag=0x800)
# The embedder looks for installer_x64.exe in the directory it runs in.
set_target_properties(installer_x64 PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${W}/libwdi)

add_custom_command(
    OUTPUT ${WDI_GEN}/embedded.h
    COMMAND gcc -w -I${WDI_CONFIG} -I${W} ${W}/libwdi/embedder.c -o ${WDI_GEN}/embedder
    COMMAND ${CMAKE_COMMAND} -E rm -f ${WDI_GEN}/embedded.h
    COMMAND ${WDI_GEN}/embedder ${WDI_GEN}/embedded.h
    WORKING_DIRECTORY ${W}/libwdi
    DEPENDS installer_x64 ${W}/libwdi/embedder.c ${W}/libwdi/embedder_files.h
        ${W}/libwdi/winusb.inf.in ${W}/libwdi/winusb.cat.in ${WDI_CONFIG}/config.h
    COMMENT "Embedding libwdi resources (host embedder)"
    VERBATIM)

add_library(wdi STATIC
    ${WDI_GEN}/embedded.h
    ${W}/libwdi/logging.c ${W}/libwdi/tokenizer.c ${W}/libwdi/vid_data.c ${W}/libwdi/pki.c
    ${W}/libwdi/libwdi_dlg.c ${W}/libwdi/libwdi.c)
target_include_directories(wdi PRIVATE ${WDI_GEN} ${WDI_CONFIG} ${W} PUBLIC ${W}/libwdi)
target_compile_options(wdi PRIVATE ${THIRD_PARTY_FLAGS})
target_link_libraries(wdi PUBLIC setupapi ole32 ntdll shell32 newdev)

add_executable(rtlsdr-driver
    ${CMAKE_CURRENT_SOURCE_DIR}/rtlsdr-driver/rtlsdr_driver.c
    ${CMAKE_CURRENT_SOURCE_DIR}/rtlsdr-driver/rtlsdr-driver.rc)
target_compile_options(rtlsdr-driver PRIVATE -Wall -Wextra)
# Elevated too: System32-only static imports (LOAD_LIBRARY_SEARCH_SYSTEM32).
target_link_libraries(rtlsdr-driver PRIVATE wdi -static -Wl,--dependent-load-flag=0x800)
