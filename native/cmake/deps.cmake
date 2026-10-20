# Clones pinned third-party sources into native/third_party on first configure.
# Same mechanism as the phone feeder's app/src/main/cpp/cmake/deps.cmake.

find_package(Git REQUIRED)

set(THIRD_PARTY_DIR "${CMAKE_CURRENT_LIST_DIR}/../third_party")
file(MAKE_DIRECTORY "${THIRD_PARTY_DIR}")
file(LOCK "${THIRD_PARTY_DIR}/.lock" GUARD FILE TIMEOUT 900)

function(_git dir)
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" -c core.autocrlf=false -c advice.detachedHead=false ${ARGN}
        WORKING_DIRECTORY "${dir}"
        RESULT_VARIABLE rc
        OUTPUT_QUIET)
    if(NOT rc EQUAL 0)
        message(FATAL_ERROR "git ${ARGN} failed in ${dir} (exit ${rc})")
    endif()
endfunction()

# fetch_dep(<name> <git url> <commit sha>) -> sets <name>_SOURCE_DIR
function(fetch_dep name url rev)
    set(dir "${THIRD_PARTY_DIR}/${name}")
    set(stamp "${dir}/.fetched-${rev}")
    if(NOT EXISTS "${stamp}")
        message(STATUS "Fetching ${name} @ ${rev}")
        file(REMOVE_RECURSE "${dir}")
        file(MAKE_DIRECTORY "${dir}")
        _git("${dir}" init -q)
        _git("${dir}" fetch -q --depth 1 "${url}" "${rev}")
        _git("${dir}" checkout -q FETCH_HEAD)
        file(TOUCH "${stamp}")
    endif()
    set(${name}_SOURCE_DIR "${dir}" PARENT_SCOPE)
endfunction()

# Pinned revisions (bump deliberately; readsb is patched by native/patches/readsb-windows.patch).
# libusb, rtlsdr, zstd and readsb match the phone feeder's pins.
fetch_dep(libusb  https://github.com/libusb/libusb.git          87a55632db62c9bdc58cd31d3ccfa673f1bb017f) # v1.0.30
fetch_dep(rtlsdr  https://github.com/rtlsdrblog/rtl-sdr-blog.git aed0ea19f3a273370a13c9009b96313c75d54c7b) # master 2026-03 (RTL-SDR Blog V4 support)
fetch_dep(zstd    https://github.com/facebook/zstd.git          f8745da6ff1ad1e7bab384bd1f9d742439278e99) # v1.5.7
fetch_dep(zlib    https://github.com/madler/zlib.git            da607da739fa6047df13e66a2af6b8bec7c2a498) # v1.3.2
fetch_dep(wepoll  https://github.com/piscisaureus/wepoll.git    0598a791bf9cbbf480793d778930fc635b044980) # v1.5.8 (dist: single wepoll.c/.h)
fetch_dep(readsb  https://github.com/wiedehopf/readsb.git       094720939c01943de82b14df6f42f67fff1cd514) # dev 2026-09-27, v3.16.17
fetch_dep(libwdi  https://github.com/pbatard/libwdi.git         30df0c0e051b0132c4b9ebed8c054bc8eb3aaaec) # master 2025-07-17 (v1.5.1 + installer fixes 947f457, 66d282e), for rtlsdr-driver

file(LOCK "${THIRD_PARTY_DIR}/.lock" RELEASE)
