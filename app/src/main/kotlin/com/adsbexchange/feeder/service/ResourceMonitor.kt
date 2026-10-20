package com.adsbexchange.feeder.service

/**
 * What the feeder costs this PC, for the "Desktop resources" card on Status: the app and readsb
 * together, measured as Task Manager measures them (CPU as a share of the whole PC, memory as the
 * private working set), plus what goes out to ADS-B Exchange. People hesitate to leave something
 * running in the background; this shows how little it uses.
 *
 * The window samples it every few seconds while it's shown, so it costs nothing in the background.
 */
class ResourceMonitor(
    private val cpus: Int = Runtime.getRuntime().availableProcessors(),
    private val cpuNanos: (pid: Long) -> Long? = ::processCpuNanos,
    private val memory: (pid: Long) -> Long? = Win32::privateWorkingSet,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    data class Usage(
        /** Share of the whole PC (all cores), as on Task Manager's Processes tab; null until two samples. */
        val cpuPercent: Double?,
        /** App plus readsb, private working set. */
        val memoryBytes: Long?,
        /** Feed, MLAT and stats sent to ADS-B Exchange, over about the last minute; null until known. */
        val sentBytesPerSec: Double?,
    ) {
        val sentBytesPerMonth: Double? get() = sentBytesPerSec?.let { it * SECONDS_PER_MONTH }
    }

    private var lastCpu = emptyMap<Long, Long>()
    private var lastAt: Long? = null
    /** (time, bytes sent so far this run), over the last [RATE_WINDOW_NS]. */
    private val sent = ArrayDeque<Pair<Long, Long>>()

    /**
     * @param readsbPid this run's readsb, if it's running.
     * @param sentBytes everything sent to ADS-B Exchange this run so far, or null when not feeding.
     */
    fun sample(readsbPid: Long?, sentBytes: Long?): Usage {
        val now = nowNanos()
        val pids = listOfNotNull(ProcessHandle.current().pid(), readsbPid)
        val cpu = pids.mapNotNull { pid -> cpuNanos(pid)?.let { pid to it } }.toMap()
        val before = lastAt
        // Only processes seen last time count, so a restarted readsb starts afresh.
        val cpuPercent = before?.let {
            val used = cpu.entries.sumOf { (pid, t) -> lastCpu[pid]?.let { (t - it).coerceAtLeast(0) } ?: 0L }
            cpuPercent(used, now - it, cpus)
        }
        lastCpu = cpu
        lastAt = now

        val memoryBytes = pids.mapNotNull(memory).takeIf { it.isNotEmpty() }?.sum()

        if (sentBytes == null) {
            sent.clear()
        } else {
            // The counters start again with each readsb run.
            if (sent.isNotEmpty() && sentBytes < sent.last().second) sent.clear()
            sent.addLast(now to sentBytes)
            while (sent.size > 2 && now - sent.first().first > RATE_WINDOW_NS) sent.removeFirst()
        }
        val span = if (sent.size >= 2) sent.last().first - sent.first().first else 0L
        val rate = if (span >= MIN_RATE_SPAN_NS) (sent.last().second - sent.first().second) * 1e9 / span else null
        return Usage(cpuPercent, memoryBytes, rate)
    }

    companion object {
        const val SECONDS_PER_MONTH = 30.44 * 86_400
        /** readsb's byte count only moves every 10 s (stats.json), so the rate is over about a minute. */
        private const val RATE_WINDOW_NS = 60_000_000_000L
        private const val MIN_RATE_SPAN_NS = 20_000_000_000L

        /** CPU time as a share of all the PC's cores over [wallNanos], in percent. */
        fun cpuPercent(cpuNanos: Long, wallNanos: Long, cpus: Int): Double =
            if (wallNanos <= 0 || cpus <= 0) 0.0 else 100.0 * cpuNanos / wallNanos / cpus

        private fun processCpuNanos(pid: Long): Long? =
            ProcessHandle.of(pid).flatMap { it.info().totalCpuDuration() }.map { it.toNanos() }.orElse(null)
    }
}
