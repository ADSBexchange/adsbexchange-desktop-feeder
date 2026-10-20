package com.adsbexchange.feeder.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Desktop resources" card's numbers, with a fake clock and fake process counters. */
class ResourceMonitorTest {
    private val app = ProcessHandle.current().pid()
    private val readsbPid = 4242L
    private val sec = 1_000_000_000L

    private var now = 0L
    private val cpu = mutableMapOf<Long, Long>()
    private val mem = mutableMapOf<Long, Long>()
    private val monitor = ResourceMonitor(cpus = 4, cpuNanos = { cpu[it] }, memory = { mem[it] }, nowNanos = { now })

    @Test
    fun cpuIsAShareOfTheWholePc() {
        // 1 s of CPU over 10 s on a 4-core PC: 2.5% of the PC, as Task Manager shows it.
        assertEquals(2.5, ResourceMonitor.cpuPercent(1 * sec, 10 * sec, 4), 1e-9)
        assertEquals(0.0, ResourceMonitor.cpuPercent(1 * sec, 0, 4), 1e-9)
    }

    @Test
    fun appAndReadsbTogether() {
        cpu[app] = 10 * sec; cpu[readsbPid] = 5 * sec
        mem[app] = 100L shl 20; mem[readsbPid] = 11L shl 20
        val first = monitor.sample(readsbPid, null)
        assertNull("no CPU figure from one sample", first.cpuPercent)
        assertEquals(111L shl 20, first.memoryBytes)

        now += 10 * sec
        cpu[app] = cpu[app]!! + sec / 10 // 0.1 s
        cpu[readsbPid] = cpu[readsbPid]!! + sec / 2 // 0.5 s
        // 0.6 s over 10 s on 4 cores
        assertEquals(1.5, monitor.sample(readsbPid, null).cpuPercent!!, 1e-9)
    }

    @Test
    fun aRestartedReadsbStartsAfresh() {
        cpu[app] = 0; cpu[readsbPid] = 0
        monitor.sample(readsbPid, null)
        now += 10 * sec
        cpu[app] = sec / 10
        cpu[5151L] = 3 * sec // a new readsb with 3 s already used: not counted this time
        assertEquals(0.25, monitor.sample(5151L, null).cpuPercent!!, 1e-9)
    }

    @Test
    fun sentRateOverAboutAMinute() {
        // Not known until 20 s of samples.
        var u = monitor.sample(readsbPid, 0)
        assertNull(u.sentBytesPerSec)
        now += 10 * sec
        assertNull(monitor.sample(readsbPid, 4_000).sentBytesPerSec)
        now += 10 * sec
        u = monitor.sample(readsbPid, 8_000)
        assertEquals(400.0, u.sentBytesPerSec!!, 1e-9)
        assertEquals(400.0 * 86_400 * 30.44, u.sentBytesPerMonth!!, 1.0)

        // Older samples drop out of the minute's window.
        repeat(10) { now += 10 * sec; u = monitor.sample(readsbPid, 8_000L + (it + 1) * 2_000) }
        assertEquals(200.0, u.sentBytesPerSec!!, 1e-9)

        // A new readsb run starts its counters again; not feeding shows nothing.
        now += 10 * sec
        assertNull(monitor.sample(readsbPid, 100).sentBytesPerSec)
        assertNull(monitor.sample(readsbPid, null).sentBytesPerSec)
    }

    @Test
    fun noReadsbJustTheApp() {
        mem[app] = 90L shl 20
        val u = monitor.sample(null, null)
        assertEquals(90L shl 20, u.memoryBytes)
        assertTrue(u.sentBytesPerSec == null)
    }
}
