package com.adsbexchange.feeder.ui

import com.adsbexchange.feeder.data.FeedState
import com.adsbexchange.feeder.data.FeederStatus
import com.adsbexchange.feeder.data.MlatReadiness
import com.adsbexchange.feeder.data.MlatStatus
import com.adsbexchange.feeder.data.ReceiverStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedStatsTrackerTest {
    private val started = System.currentTimeMillis() - 60_000

    private fun status(loopback: Long, state: FeederStatus.State = FeederStatus.State.RUNNING, startedMs: Long = started) =
        FeederStatus(state, feeding = true, feedState = FeedState.CONNECTED, startedMs = startedMs,
            mlat = MlatStatus(MlatReadiness.READY, loopbackBytes = loopback))

    private fun receiver(statsNow: Double, bytesOut: Long, rate: Double = 120.0) = ReceiverStatus(
        live = true, messagesPerSecond = rate, aircraft = emptyList(), signalDbfs = null, noiseDbfs = null,
        samplesDropped = null, maxRangeNm = null, feedState = FeedState.CONNECTED, messagesTotal = 1000,
        bytesSent = bytesOut, aircraftSeen = 10, statsNow = statsNow, receiverLat = null, receiverLon = null,
        lastError = null, logTail = emptyList(),
    )

    /** readsb's bytes_out counts our MLAT client's loopback reads too; they never reach ADS-B Exchange. */
    @Test
    fun sentLeavesOutTheMlatLoopback() {
        val t = FeedStatsTracker()
        val f = t.update(status(loopback = 4_000), receiver(statsNow = 100.0, bytesOut = 10_000))
        assertEquals(6_000L, f.bytesSent)
    }

    @Test
    fun uploadRateIsMeasuredBetweenStatsRefreshes() {
        val t = FeedStatsTracker()
        assertNull(t.update(status(0), receiver(100.0, 10_000)).uploadBytesPerSec)
        // Same stats.json (readsb refreshes it every 10 s): no new reading.
        assertNull(t.update(status(0), receiver(100.0, 10_000)).uploadBytesPerSec)
        val f = t.update(status(1_000), receiver(110.0, 31_000))
        assertEquals(2_000.0, f.uploadBytesPerSec!!, 0.001) // (31,000 - 1,000 - 10,000) / 10 s
    }

    @Test
    fun aNewRunStartsAfresh() {
        val t = FeedStatsTracker()
        t.update(status(0), receiver(100.0, 10_000))
        t.update(status(0), receiver(110.0, 20_000))
        val f = t.update(status(0, startedMs = started + 1), receiver(5.0, 500))
        assertNull(f.uploadBytesPerSec)
        assertTrue(f.rateHistory.size <= 1)
    }

    @Test
    fun nothingWhileStopped() {
        val f = FeedStatsTracker().update(status(0, state = FeederStatus.State.STOPPED), receiver(100.0, 10_000))
        assertNull(f.bytesSent)
        assertTrue(f.rateHistory.isEmpty())
    }
}
