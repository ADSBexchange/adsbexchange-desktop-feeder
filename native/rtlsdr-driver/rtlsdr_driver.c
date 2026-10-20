/*
 * rtlsdr-driver: finds RTL-SDR sticks and installs the WinUSB driver librtlsdr needs, so users
 * never have to run Zadig. Part of the ADS-B Exchange Desktop Feeder (GPL-3.0-or-later).
 *
 * Windows binds RTL2832U sticks to a TV-tuner (DVB-T) driver, or to none. librtlsdr talks to the
 * stick through libusb, which needs WinUSB on the stick's interface 0. This uses libwdi (the
 * library behind Zadig) to generate a driver package for that interface, sign its catalog with a
 * one-off self-signed certificate (whose private key libwdi deletes straight after signing), and
 * install it.
 *
 *   rtlsdr-driver list [--all]
 *       One JSON object per RTL-SDR stick on stdout (--all: every USB device, for diagnostics).
 *       Needs no admin rights.
 *   rtlsdr-driver install
 *       Installs WinUSB for every stick that doesn't have it. Needs admin rights: the app starts
 *       it through UAC ("runas"). Messages go to driver-install.log beside this program (there's
 *       no console to read when elevated), which the app copies into its own log afterwards.
 *
 * Elevated, this only writes beside itself: the log, and libwdi's driver package (including the
 * installer libwdi runs elevated) in driver-staging\, deleted afterwards. Installed per machine,
 * that's under Program Files, where only administrators can write, so nothing running as the user
 * can swap those files between libwdi writing and running them, or point the log elsewhere.
 * (Until 0.5.16 they went to the user's %TEMP% and a log path the app passed.)
 *   rtlsdr-driver prepare DIR
 *       Tests only: writes the driver package for a stand-in stick to DIR, unsigned.
 *
 * Exit codes: 0 done (or nothing to do), 1 failed, 2 no RTL-SDR found, 3 not running as admin,
 * 4 bad arguments.
 */
#include <windows.h>
#include <shlobj.h>
#include <stdio.h>
#include <string.h>

#include "libwdi.h"

enum { EXIT_OK = 0, EXIT_FAILED = 1, EXIT_NO_DEVICE = 2, EXIT_NOT_ADMIN = 3, EXIT_USAGE = 4 };

/* librtlsdr's known_devices table (rtl-sdr-blog librtlsdr.c), which is what it will open. */
static const struct { unsigned short vid, pid; const char *name; } known_devices[] = {
	{ 0x0bda, 0x2832, "Generic RTL2832U" },
	{ 0x0bda, 0x2838, "Generic RTL2832U OEM" },
	{ 0x0413, 0x6680, "DigitalNow Quad DVB-T PCI-E card" },
	{ 0x0413, 0x6f0f, "Leadtek WinFast DTV Dongle mini D" },
	{ 0x0458, 0x707f, "Genius TVGo DVB-T03 USB dongle (Ver. B)" },
	{ 0x0ccd, 0x00a9, "Terratec Cinergy T Stick Black (rev 1)" },
	{ 0x0ccd, 0x00b3, "Terratec NOXON DAB/DAB+ USB dongle (rev 1)" },
	{ 0x0ccd, 0x00b4, "Terratec Deutschlandradio DAB Stick" },
	{ 0x0ccd, 0x00b5, "Terratec NOXON DAB Stick - Radio Energy" },
	{ 0x0ccd, 0x00b7, "Terratec Media Broadcast DAB Stick" },
	{ 0x0ccd, 0x00b8, "Terratec BR DAB Stick" },
	{ 0x0ccd, 0x00b9, "Terratec WDR DAB Stick" },
	{ 0x0ccd, 0x00c0, "Terratec MuellerVerlag DAB Stick" },
	{ 0x0ccd, 0x00c6, "Terratec Fraunhofer DAB Stick" },
	{ 0x0ccd, 0x00d3, "Terratec Cinergy T Stick RC (Rev.3)" },
	{ 0x0ccd, 0x00d7, "Terratec T Stick PLUS" },
	{ 0x0ccd, 0x00e0, "Terratec NOXON DAB/DAB+ USB dongle (rev 2)" },
	{ 0x1554, 0x5020, "PixelView PV-DT235U(RN)" },
	{ 0x15f4, 0x0131, "Astrometa DVB-T/DVB-T2" },
	{ 0x15f4, 0x0133, "HanfTek DAB+FM+DVB-T" },
	{ 0x185b, 0x0620, "Compro Videomate U620F" },
	{ 0x185b, 0x0650, "Compro Videomate U650F" },
	{ 0x185b, 0x0680, "Compro Videomate U680F" },
	{ 0x1b80, 0xd393, "GIGABYTE GT-U7300" },
	{ 0x1b80, 0xd394, "DIKOM USB-DVBT HD" },
	{ 0x1b80, 0xd395, "Peak 102569AGPK" },
	{ 0x1b80, 0xd397, "KWorld KW-UB450-T USB DVB-T Pico TV" },
	{ 0x1b80, 0xd398, "Zaapa ZT-MINDVBZP" },
	{ 0x1b80, 0xd39d, "SVEON STV20 DVB-T USB & FM" },
	{ 0x1b80, 0xd3a4, "Twintech UT-40" },
	{ 0x1b80, 0xd3a8, "ASUS U3100MINI_PLUS_V2" },
	{ 0x1b80, 0xd3af, "SVEON STV27 DVB-T USB & FM" },
	{ 0x1b80, 0xd3b0, "SVEON STV21 DVB-T USB & FM" },
	{ 0x1d19, 0x1101, "Dexatek DK DVB-T Dongle (Logilink VG0002A)" },
	{ 0x1d19, 0x1102, "Dexatek DK DVB-T Dongle (MSI DigiVox mini II V3.0)" },
	{ 0x1d19, 0x1103, "Dexatek Technology Ltd. DK 5217 DVB-T Dongle" },
	{ 0x1d19, 0x1104, "MSI DigiVox Micro HD" },
	{ 0x1f4d, 0xa803, "Sweex DVB-T USB" },
	{ 0x1f4d, 0xb803, "GTek T803" },
	{ 0x1f4d, 0xc803, "Lifeview LV5TDeluxe" },
	{ 0x1f4d, 0xd286, "MyGica TD312" },
	{ 0x1f4d, 0xd803, "PROlectrix DV107669" },
};

static const char *rtlsdr_name(const struct wdi_device_info *d)
{
	for (size_t i = 0; i < sizeof(known_devices) / sizeof(known_devices[0]); i++) {
		if (known_devices[i].vid == d->vid && known_devices[i].pid == d->pid)
			return known_devices[i].name;
	}
	return NULL;
}

/* librtlsdr claims interface 0. On composite sticks interface 1 (the IR receiver) is left alone. */
static int is_rtlsdr_interface(const struct wdi_device_info *d)
{
	return rtlsdr_name(d) != NULL && (!d->is_composite || d->mi == 0);
}

static int has_winusb(const struct wdi_device_info *d)
{
	return d->driver != NULL && _stricmp(d->driver, "WinUSB") == 0;
}

static void print_json_string(FILE *f, const char *s)
{
	fputc('"', f);
	for (; s != NULL && *s; s++) {
		unsigned char c = (unsigned char)*s;
		if (c == '"' || c == '\\')
			fprintf(f, "\\%c", c);
		else if (c < 0x20)
			fprintf(f, "\\u%04x", c);
		else
			fputc(c, f);
	}
	fputc('"', f);
}

static void print_device(FILE *f, const struct wdi_device_info *d)
{
	fprintf(f, "{\"vid\":\"%04x\",\"pid\":\"%04x\",\"composite\":%s,\"mi\":%d,\"name\":",
		d->vid, d->pid, d->is_composite ? "true" : "false", d->is_composite ? d->mi : -1);
	print_json_string(f, rtlsdr_name(d));
	fputs(",\"desc\":", f);
	print_json_string(f, d->desc);
	fputs(",\"driver\":", f);
	if (d->driver)
		print_json_string(f, d->driver);
	else
		fputs("null", f);
	fprintf(f, ",\"winusb\":%s}\n", has_winusb(d) ? "true" : "false");
}

static struct wdi_device_info *usb_devices(int *err)
{
	struct wdi_device_info *list = NULL;
	struct wdi_options_create_list opts = { .list_all = TRUE, .list_hubs = FALSE, .trim_whitespaces = TRUE };
	*err = wdi_create_list(&list, &opts);
	if (*err == WDI_ERROR_NO_DEVICE) {
		*err = WDI_SUCCESS;
		return NULL;
	}
	return list;
}

/* all: every USB device, for diagnostics (exit code still says whether an RTL-SDR was found) */
static int cmd_list(int all)
{
	int err;
	struct wdi_device_info *list = usb_devices(&err);
	if (err != WDI_SUCCESS) {
		fprintf(stderr, "listing USB devices failed: %s\n", wdi_strerror(err));
		return EXIT_FAILED;
	}
	int found = 0;
	for (struct wdi_device_info *d = list; d != NULL; d = d->next) {
		int rtl = is_rtlsdr_interface(d);
		if (rtl || all)
			print_device(stdout, d);
		found += rtl;
	}
	wdi_destroy_list(list);
	return found ? EXIT_OK : EXIT_NO_DEVICE;
}

/*
 * Tests only: writes the driver package for a stand-in RTL2832U (0bda:2838, interface 0) to dir,
 * so the generated inf can be checked without a stick. Without admin rights libwdi writes the inf
 * and the installer but doesn't create, sign or install anything.
 */
static int cmd_prepare(const char *dir)
{
	struct wdi_device_info d = {
		.vid = 0x0bda, .pid = 0x2838, .is_composite = TRUE, .mi = 0,
		.desc = "Bulk-In, Interface (Interface 0)",
	};
	if (IsUserAnAdmin()) {
		fprintf(stderr, "prepare is for tests: run it without admin rights, so nothing gets signed\n");
		return EXIT_USAGE;
	}
	struct wdi_options_prepare_driver prep = { .driver_type = WDI_WINUSB };
	int r = wdi_prepare_driver(&d, dir, "rtlsdr_0bda_2838.inf", &prep);
	if (r != WDI_SUCCESS) {
		fprintf(stderr, "prepare failed: %s\n", wdi_strerror(r));
		return EXIT_FAILED;
	}
	return EXIT_OK;
}

/* The folder this program is in, without a trailing backslash. */
static int own_folder(char *out, size_t size)
{
	DWORD n = GetModuleFileNameA(NULL, out, (DWORD)size);
	if (n == 0 || n >= size)
		return 0;
	char *slash = strrchr(out, '\\');
	if (slash == NULL)
		return 0;
	*slash = '\0';
	return 1;
}

/*
 * Deletes path and everything in it. A link (junction or symbolic link) is removed itself, never
 * followed, so this can't be steered into deleting anything outside path.
 */
static void delete_tree(const char *path)
{
	DWORD attrs = GetFileAttributesA(path);
	if (attrs == INVALID_FILE_ATTRIBUTES)
		return;
	if ((attrs & FILE_ATTRIBUTE_DIRECTORY) && !(attrs & FILE_ATTRIBUTE_REPARSE_POINT)) {
		char pattern[MAX_PATH];
		if (snprintf(pattern, sizeof(pattern), "%s\\*", path) < (int)sizeof(pattern)) {
			WIN32_FIND_DATAA f;
			HANDLE h = FindFirstFileA(pattern, &f);
			if (h != INVALID_HANDLE_VALUE) {
				do {
					if (strcmp(f.cFileName, ".") == 0 || strcmp(f.cFileName, "..") == 0)
						continue;
					char child[MAX_PATH];
					if (snprintf(child, sizeof(child), "%s\\%s", path, f.cFileName) < (int)sizeof(child))
						delete_tree(child);
				} while (FindNextFileA(h, &f));
				FindClose(h);
			}
		}
	}
	if (attrs & FILE_ATTRIBUTE_DIRECTORY) {
		RemoveDirectoryA(path);
	} else {
		SetFileAttributesA(path, FILE_ATTRIBUTE_NORMAL);
		DeleteFileA(path);
	}
}

static int cmd_install(const char *staging)
{
	if (!IsUserAnAdmin()) {
		fprintf(stderr, "install needs administrator rights\n");
		return EXIT_NOT_ADMIN;
	}
	int err;
	struct wdi_device_info *list = usb_devices(&err);
	if (err != WDI_SUCCESS) {
		fprintf(stderr, "listing USB devices failed: %s\n", wdi_strerror(err));
		return EXIT_FAILED;
	}

	/* A fresh, empty staging folder: whatever an earlier, interrupted run left goes first. */
	char base[MAX_PATH];
	if (strlen(staging) >= sizeof(base) - 16) {
		fprintf(stderr, "staging folder path too long\n");
		wdi_destroy_list(list);
		return EXIT_FAILED;
	}
	strcpy(base, staging);
	delete_tree(base);

	int found = 0, failed = 0, index = 0;
	for (struct wdi_device_info *d = list; d != NULL; d = d->next) {
		if (!is_rtlsdr_interface(d))
			continue;
		found++;
		fputs("device: ", stderr);
		print_device(stderr, d);
		if (has_winusb(d)) {
			fprintf(stderr, "already has WinUSB\n");
			continue;
		}
		char dir[MAX_PATH], inf[32];
		snprintf(dir, sizeof(dir), "%s\\%d", base, index++);
		snprintf(inf, sizeof(inf), "rtlsdr_%04x_%04x.inf", d->vid, d->pid);
		struct wdi_options_prepare_driver prep = { .driver_type = WDI_WINUSB };
		int r = wdi_prepare_driver(d, dir, inf, &prep);
		if (r == WDI_SUCCESS)
			r = wdi_install_driver(d, dir, inf, NULL);
		if (r == WDI_SUCCESS) {
			fprintf(stderr, "WinUSB installed\n");
		} else {
			fprintf(stderr, "WinUSB install failed: %s\n", wdi_strerror(r));
			failed++;
		}
	}
	wdi_destroy_list(list);
	/* The driver is in Windows' driver store now; the package isn't needed. */
	delete_tree(base);
	if (!found) {
		fprintf(stderr, "no RTL-SDR found\n");
		return EXIT_NO_DEVICE;
	}
	return failed ? EXIT_FAILED : EXIT_OK;
}

int main(int argc, char **argv)
{
	/* This runs elevated, and libwdi loads mssign32, crypt32 and others with plain LoadLibrary,
	 * which looks in the program's own folder first. Take DLLs from System32 only. (The
	 * program's static imports get the same through the linker's dependent-load flag.) */
	SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_SYSTEM32);

	if (argc >= 2 && strcmp(argv[1], "list") == 0) {
		int all = argc == 3 && strcmp(argv[2], "--all") == 0;
		if (argc != 2 && !all)
			return EXIT_USAGE;
		wdi_set_log_level(WDI_LOG_LEVEL_NONE);
		return cmd_list(all);
	}
	if (argc == 3 && strcmp(argv[1], "prepare") == 0) {
		wdi_set_log_level(WDI_LOG_LEVEL_WARNING);
		return cmd_prepare(argv[2]);
	}
	if (argc == 2 && strcmp(argv[1], "install") == 0) {
		/* Log and staging beside this program only; see the comment at the top. */
		char folder[MAX_PATH], log[MAX_PATH], staging[MAX_PATH];
		if (!own_folder(folder, sizeof(folder))
				|| snprintf(log, sizeof(log), "%s\\driver-install.log", folder) >= (int)sizeof(log)
				|| snprintf(staging, sizeof(staging), "%s\\driver-staging", folder) >= (int)sizeof(staging))
			return EXIT_FAILED;
		/* A link in place of the log would redirect an administrator's write: remove it first. */
		delete_tree(log);
		if (freopen(log, "w", stderr) == NULL)
			return EXIT_FAILED;
		setvbuf(stderr, NULL, _IONBF, 0);
		wdi_set_log_level(WDI_LOG_LEVEL_INFO);
		return cmd_install(staging);
	}
	fprintf(stderr, "usage: rtlsdr-driver list [--all] | install | prepare DIR\n");
	return EXIT_USAGE;
}
