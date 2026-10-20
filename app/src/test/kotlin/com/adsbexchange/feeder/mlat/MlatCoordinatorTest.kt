package com.adsbexchange.feeder.mlat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MlatCoordinatorTest {
    private class FakeServer : MlatCoordinator.Server {
        val syncs = ArrayList<Pair<ModeSMessage, ModeSMessage>>()
        val mlat = ArrayList<ModeSMessage>()
        val seen = ArrayList<Collection<Int>>()
        var clockResets = 0
        override fun sendMlat(m: ModeSMessage) { mlat += m }
        override fun sendSync(even: ModeSMessage, odd: ModeSMessage) { syncs += even to odd }
        override val splitSync = false
        override fun sendSplitSync(m: ModeSMessage) = Unit
        override fun sendSeen(addresses: Collection<Int>) { seen += addresses.toList() }
        override fun sendLost(addresses: Collection<Int>) = Unit
        override fun sendRateReport(report: Map<Int, Double>) = Unit
        override fun sendInputConnected() = Unit
        override fun sendInputDisconnected() = Unit
        override fun sendClockJump() = Unit
        override fun sendClockReset(reason: String, frequency: Long?, mode: String?) { clockResets++ }
    }

    private val server = FakeServer()
    private val reader = BeastReader()
    private val coordinator = MlatCoordinator(server, reader, 12_000_000).also { it.serverConnected() }

    private fun msg(bytes: ByteArray, ts: Long) = ModeSMessage(ts, 0x40, bytes)

    @Test fun sendsSyncPairsOnlyForRequestedAircraftAfterTenMessages() {
        val icao = 0xA1B2C3
        coordinator.inputConnected()
        assertEquals(1, server.clockResets)
        var ts = 12_000_000L
        var now = 100.0
        repeat(14) { i ->
            coordinator.received(msg(TestFrames.df17Position(icao, 35000, odd = i % 2 == 1), ts), now)
            ts += 6_000_000; now += 0.5
        }
        assertTrue("not requested yet: no sync", server.syncs.isEmpty())

        coordinator.serverStartSending(listOf(icao))
        repeat(4) { i ->
            coordinator.received(msg(TestFrames.df17Position(icao, 35000, odd = i % 2 == 1), ts), now)
            ts += 6_000_000; now += 0.5
        }
        assertEquals(4, server.syncs.size)
        val (even, odd) = server.syncs.last()
        assertTrue(even.evenCpr && odd.oddCpr)
        assertEquals(4L, coordinator.syncsSent)
    }

    @Test fun forwardsModeSRepliesForRequestedNonAdsbAircraft() {
        val icao = 0x0D15EA
        coordinator.serverStartSending(listOf(icao))
        var ts = 12_000_000L
        repeat(12) { coordinator.received(msg(TestFrames.df11(icao), ts), 100.0 + it); ts += 12_000_000 }
        // The first DF11 creates the aircraft; forwarding starts at the 10th message.
        assertEquals(3, server.mlat.size)
        assertTrue(server.mlat.all { it.df == 11 && it.address == icao })
    }

    @Test fun reportsSeenAircraftToServer() {
        val icao = 0xABCDEF
        coordinator.received(msg(TestFrames.df11(icao), 12_000_000), 1.0)
        coordinator.received(msg(TestFrames.df11(icao), 24_000_000), 2.0)
        // Updates run every ~4.5-5.5 s and reports every 4 updates.
        var t = 3.0
        while (server.seen.isEmpty() && t < 120) {
            coordinator.received(msg(TestFrames.df11(icao), (t * 12_000_000).toLong()), t)
            coordinator.heartbeat(t)
            t += 0.5
        }
        assertEquals(listOf(icao), server.seen.flatten())
    }
}
