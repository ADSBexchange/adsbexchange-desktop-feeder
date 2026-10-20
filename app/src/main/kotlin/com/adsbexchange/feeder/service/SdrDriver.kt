package com.adsbexchange.feeder.service

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** An RTL-SDR stick's interface 0, as rtlsdr-driver.exe reports it. */
data class SdrStick(
    val vid: String,
    val pid: String,
    /** librtlsdr's name for this USB ID, e.g. "Generic RTL2832U OEM". */
    val name: String,
    /** What Windows calls it, e.g. "Bulk-In, Interface (Interface 0)". */
    val description: String?,
    /** The Windows driver (service) bound to it now, e.g. "WinUSB", or null for none. */
    val driver: String?,
    val winusb: Boolean,
)

/** The RTL-SDR sticks plugged in. readsb can use a stick only once it has WinUSB. */
data class SdrScan(val sticks: List<SdrStick>) {
    val ready: Boolean get() = sticks.any { it.winusb }
    val needsDriver: Boolean get() = sticks.isNotEmpty() && !ready
}

/** Finds RTL-SDR sticks; [HelperProbe] in the app, a stand-in in tests. */
fun interface SdrProbe {
    /** Null when the check itself failed: then the engine just tries readsb. */
    fun scan(): SdrScan?
}

/**
 * rtlsdr-driver.exe (native/rtlsdr-driver): finds RTL-SDR sticks and installs WinUSB for them
 * with libwdi, so users never need Zadig.
 */
object SdrDriver {
    enum class Outcome { INSTALLED, CANCELLED, NO_DEVICE, FAILED }

    /** rtlsdr-driver.exe: from Gradle's run task (not in the installed app), or next to the packaged app's resources. */
    fun findExecutable(): File? {
        developmentOverride("feeder.rtlsdrDriver")?.let { File(it) }?.takeIf { it.isFile }?.let { return it }
        System.getProperty("compose.application.resources.dir")?.let { File(it, "rtlsdr-driver.exe") }
            ?.takeIf { it.isFile }?.let { return it }
        return null
    }

    /** `rtlsdr-driver list`: no admin rights needed, takes well under a second. */
    fun scan(exe: File): SdrScan? = runCatching {
        val (code, out) = runWithTimeout(listOf(exe.absolutePath, "list"), SCAN_TIMEOUT_MS) ?: return null
        when (code) {
            0, 2 -> parse(out) // 2: no RTL-SDR found
            else -> null
        }
    }.getOrNull()

    /**
     * Runs [command] and returns its exit code and output, or null if it doesn't finish within
     * [timeoutMs] (it's killed then). The output is read on a thread of its own: reading it first
     * and then waiting would wait for the program to end, so a hung helper would block the engine
     * for good, the timeout never reached.
     */
    internal fun runWithTimeout(command: List<String>, timeoutMs: Long): Pair<Int, String>? {
        val p = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.outputStream.close()
        var out = ""
        val reader = thread(name = "helper-output", isDaemon = true) {
            out = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")
        }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return null
        }
        reader.join(2_000)
        return p.exitValue() to out
    }

    /** `list` normally takes well under a second. */
    private const val SCAN_TIMEOUT_MS = 15_000L

    internal fun parse(listOutput: String): SdrScan = SdrScan(
        listOutput.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                SdrStick(
                    vid = o.getString("vid"), pid = o.getString("pid"), name = o.optString("name"),
                    description = o.optString("desc").takeIf { !o.isNull("desc") && it.isNotBlank() },
                    driver = o.optString("driver").takeIf { !o.isNull("driver") && it.isNotBlank() },
                    winusb = o.optBoolean("winusb"),
                )
            }.getOrNull()
        }.toList()
    )

    /**
     * `rtlsdr-driver install`, elevated: Windows shows its permission (UAC) prompt. Blocks until
     * the helper finishes or the user says no. The helper's messages are appended to [log].
     *
     * Elevated, the helper writes its messages only beside itself (`driver-install.log`, in Program
     * Files once installed), never to a path from here: an administrator's write to a file the user
     * controls could be pointed at a system file. This copies them over once it has finished.
     */
    fun installElevated(exe: File, log: File): Outcome {
        log.parentFile?.mkdirs()
        val helperLog = File(exe.parentFile, HELPER_LOG)
        val startedMs = System.currentTimeMillis()
        try {
            return runElevated(exe)
        } finally {
            // Only this run's: a log from an earlier run stays where it is if the user said no.
            runCatching {
                if (helperLog.isFile && helperLog.lastModified() >= startedMs - 2_000) log.appendText(helperLog.readText())
            }
        }
    }

    private fun runElevated(exe: File): Outcome {
        val info = ShellAPI.SHELLEXECUTEINFO().apply {
            cbSize = size()
            fMask = SEE_MASK_NOCLOSEPROCESS or SEE_MASK_NOASYNC
            lpVerb = "runas"
            lpFile = exe.absolutePath
            lpParameters = "install"
            nShow = WinUser.SW_HIDE
        }
        if (!Shell32.INSTANCE.ShellExecuteEx(info)) {
            return if (Kernel32.INSTANCE.GetLastError() == ERROR_CANCELLED) Outcome.CANCELLED else Outcome.FAILED
        }
        val process = info.hProcess ?: return Outcome.FAILED
        try {
            Kernel32.INSTANCE.WaitForSingleObject(process, INSTALL_TIMEOUT_MS)
            val code = IntByReference()
            if (!Kernel32.INSTANCE.GetExitCodeProcess(process, code) || code.value == WinBase.STILL_ACTIVE) {
                return Outcome.FAILED
            }
            return when (code.value) {
                0 -> Outcome.INSTALLED
                2 -> Outcome.NO_DEVICE
                else -> Outcome.FAILED
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(process)
        }
    }

    /** Where the elevated helper writes its messages: beside itself (rtlsdr_driver.c). */
    const val HELPER_LOG = "driver-install.log"
    private const val SEE_MASK_NOCLOSEPROCESS = 0x00000040
    private const val SEE_MASK_NOASYNC = 0x00000100
    private const val ERROR_CANCELLED = 1223
    /** Driver installs normally take seconds; Windows can take a while the first time. */
    private const val INSTALL_TIMEOUT_MS = 5 * 60 * 1000

    /** The usual probe: the helper if it's there; without it (development builds), no check. */
    class HelperProbe(private val exe: File?) : SdrProbe {
        override fun scan(): SdrScan? = exe?.let(::scan)
    }
}
