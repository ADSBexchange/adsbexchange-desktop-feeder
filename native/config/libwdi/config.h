/* libwdi config.h for the desktop feeder's rtlsdr-driver helper (mingw-w64 clang, x64 only).
   Also used by libwdi's embedder, which runs on the Linux build host. */

/* WinUSB only, and only the Windows 8+ inbox driver: native/patches/libwdi-inbox-winusb.patch
   drops the WDK co-installers that WDK_DIR would otherwise embed. WDK_DIR still has to be
   defined: it is what enables WinUSB support in libwdi. */
#define WINUSB_INBOX 1
#define WDK_DIR "unused-inbox-winusb"
#define WDF_VER 1011
#define COINSTALLER_DIR "wdf"
#define X64_DIR "x64"

/* 64 bit only: the embedder picks installer_x64.exe up from the current directory. */
#define OPT_M64 1

/* Message logging */
#define ENABLE_LOGGING 1
#define INCLUDE_DEBUG_LOGGING 1

/* Windows 7 or later headers */
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
