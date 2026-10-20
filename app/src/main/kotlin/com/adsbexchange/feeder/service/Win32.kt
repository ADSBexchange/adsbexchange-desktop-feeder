package com.adsbexchange.feeder.service

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.LinkedBlockingQueue

/** The few Windows API calls the feeder needs, through JNA. */
internal object Win32 {
    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun SetThreadExecutionState(flags: Int): Int
        fun CreateJobObjectW(attributes: Pointer?, name: WString?): Pointer?
        fun SetInformationJobObject(job: Pointer, infoClass: Int, info: Pointer, length: Int): Boolean
        fun OpenProcess(access: Int, inheritHandle: Boolean, pid: Int): Pointer?
        fun AssignProcessToJobObject(job: Pointer, process: Pointer): Boolean
        fun CloseHandle(handle: Pointer): Boolean
        fun K32GetProcessMemoryInfo(process: Pointer, counters: Pointer, size: Int): Boolean
    }

    @Suppress("FunctionName")
    private interface IpHlpApi : Library {
        fun GetExtendedTcpTable(table: Pointer?, size: IntByReference, order: Boolean, af: Int, tableClass: Int, reserved: Int): Int
    }

    private val kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }
    private val iphlpapi by lazy { Native.load("iphlpapi", IpHlpApi::class.java) }

    // ---- keeping the PC awake ------------------------------------------------------------

    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001

    /**
     * Tells Windows not to sleep while [on] (the screen may still turn off). The request belongs to
     * the calling thread and lasts until that thread clears it or ends, so call it from one
     * long-lived thread; see [KeepAwake].
     */
    fun keepSystemAwake(on: Boolean): Boolean =
        kernel32.SetThreadExecutionState(if (on) ES_CONTINUOUS or ES_SYSTEM_REQUIRED else ES_CONTINUOUS) != 0

    // ---- job object: readsb ends with the app ---------------------------------------------

    private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
    private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
    /** sizeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION) on x64; LimitFlags is at offset 16. */
    private const val EXTENDED_LIMIT_INFO_SIZE = 144
    private const val PROCESS_SET_QUOTA = 0x0100
    private const val PROCESS_TERMINATE = 0x0001

    /**
     * A job whose processes Windows kills when the app's last handle to it closes, i.e. when the
     * app exits for any reason, crashes included. Never closed: it lives as long as the app.
     */
    private val killOnCloseJob: Pointer? by lazy {
        runCatching {
            val job = kernel32.CreateJobObjectW(null, null) ?: return@runCatching null
            val info = Memory(EXTENDED_LIMIT_INFO_SIZE.toLong()).apply { clear() }
            info.setInt(16, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
            if (kernel32.SetInformationJobObject(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, EXTENDED_LIMIT_INFO_SIZE)) job
            else { kernel32.CloseHandle(job); null }
        }.getOrNull()
    }

    /** Puts [pid] in the kill-on-close job. False if Windows refused (the stdin pipe still stops readsb). */
    fun endWithThisProcess(pid: Long): Boolean = runCatching {
        val job = killOnCloseJob ?: return false
        val process = kernel32.OpenProcess(PROCESS_SET_QUOTA or PROCESS_TERMINATE, false, pid.toInt()) ?: return false
        try { kernel32.AssignProcessToJobObject(job, process) } finally { kernel32.CloseHandle(process) }
    }.getOrDefault(false)

    // ---- memory in use, as Task Manager shows it ------------------------------------------

    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    private const val PROCESS_VM_READ = 0x0010
    /** sizeof(PROCESS_MEMORY_COUNTERS_EX2) on x64 (Windows 10 1809 and later); cb is at offset 0. */
    private const val MEMORY_COUNTERS_SIZE = 96
    private const val WORKING_SET_OFFSET = 16L
    private const val PRIVATE_WORKING_SET_OFFSET = 80L

    /**
     * The memory [pid] holds in RAM that no other process shares: Task Manager's "Memory" column
     * (the private working set). Null if it can't be read.
     */
    fun privateWorkingSet(pid: Long): Long? = runCatching {
        val process = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION or PROCESS_VM_READ, false, pid.toInt())
            ?: return null
        try {
            val counters = Memory(MEMORY_COUNTERS_SIZE.toLong()).apply { clear(); setInt(0, MEMORY_COUNTERS_SIZE) }
            if (!kernel32.K32GetProcessMemoryInfo(process, counters, MEMORY_COUNTERS_SIZE)) return null
            // Older Windows fills only the basic counters: fall back to the whole working set.
            counters.getLong(PRIVATE_WORKING_SET_OFFSET).takeIf { it > 0 } ?: counters.getLong(WORKING_SET_OFFSET)
        } finally {
            kernel32.CloseHandle(process)
        }
    }.getOrNull()

    // ---- who owns a loopback TCP connection -----------------------------------------------

    private const val AF_INET = 2
    private const val TCP_TABLE_OWNER_PID_ALL = 5
    private const val NO_ERROR = 0
    private const val ERROR_INSUFFICIENT_BUFFER = 122
    /** MIB_TCPROW_OWNER_PID: state, local addr, local port, remote addr, remote port, owning pid. */
    private const val ROW_SIZE = 24
    private const val LOOPBACK = 0x0100007F // 127.0.0.1 as stored in the table (network byte order, read little-endian)

    /**
     * The process that owns the client end of the IPv4 loopback connection from local port
     * [clientPort] to [serverPort], or null if there's no such connection.
     */
    fun loopbackClientPid(clientPort: Int, serverPort: Int): Long? {
        val size = IntByReference(0)
        var buf: Memory? = null
        repeat(3) { // the table can grow between the size query and the read
            val rc = iphlpapi.GetExtendedTcpTable(buf, size, false, AF_INET, TCP_TABLE_OWNER_PID_ALL, 0)
            val b = buf
            if (rc == NO_ERROR && b != null) return findClient(b, clientPort, serverPort)
            if (rc != ERROR_INSUFFICIENT_BUFFER && rc != NO_ERROR) return null
            val grown = Memory(size.value.toLong() + 16 * ROW_SIZE)
            size.value = grown.size().toInt()
            buf = grown
        }
        return null
    }

    private fun findClient(table: Memory, clientPort: Int, serverPort: Int): Long? {
        val rows = table.getInt(0)
        for (i in 0 until rows) {
            val row = 4L + i * ROW_SIZE
            val localAddr = table.getInt(row + 4)
            val localPort = port(table.getInt(row + 8))
            val remoteAddr = table.getInt(row + 12)
            val remotePort = port(table.getInt(row + 16))
            if (localPort == clientPort && remotePort == serverPort && localAddr == LOOPBACK && remoteAddr == LOOPBACK) {
                return table.getInt(row + 20).toLong() and 0xFFFFFFFFL
            }
        }
        return null
    }

    /** Ports are stored in network byte order in the low 16 bits. */
    private fun port(raw: Int): Int = ((raw and 0xFF) shl 8) or ((raw shr 8) and 0xFF)
}

/**
 * Holds Windows' "don't sleep" request on a thread of its own, because the request is per thread
 * and the feeder's own threads come and go.
 */
internal class KeepAwake {
    private val requests = LinkedBlockingQueue<Boolean>()

    init {
        Thread({
            var applied = false
            while (true) {
                val want = requests.take()
                if (want != applied) {
                    runCatching { Win32.keepSystemAwake(want) }
                    applied = want
                }
            }
        }, "keep-awake").apply { isDaemon = true; start() }
    }

    fun set(on: Boolean) {
        requests.put(on)
    }
}
