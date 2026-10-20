package com.adsbexchange.feeder.mlat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeastReaderTest {
    private var clockMs = 1_000_000L
    private val reader = BeastReader { clockMs }

    private fun feed(bytes: ByteArray): Pair<Int, List<ReaderEvent>> {
        val out = ArrayList<ReaderEvent>()
        return reader.feed(bytes, bytes.size, out) to out
    }

    @Test fun parsesFrameAndMovesTimestampToFrameStart() {
        val msg = TestFrames.df17Position(0xA1B2C3, 35000, odd = false)
        val (consumed, events) = feed(TestFrames.beast(10_000_000, 0x40, msg))
        assertTrue(consumed > 0)
        val m = (events.single() as ReaderEvent.Message).message
        assertEquals(10_000_000L - 768, m.timestamp)
        assertEquals(0xA1B2C3, m.address)
    }

    @Test fun unescapesDoubled1A() {
        // 0x1A inside the timestamp and the message are doubled on the wire.
        val msg = TestFrames.df17Position(0x1A1A1A, 12000, odd = true)
        val ts = 0x1A00001A1A1AL
        val (_, events) = feed(TestFrames.beast(ts, 0x1a, msg))
        val m = (events.single() as ReaderEvent.Message).message
        assertEquals(ts - 768, m.timestamp)
        assertEquals(0x1A1A1A, m.address)
    }

    @Test fun keepsIncompleteFrameForNextFeed() {
        val frame = TestFrames.beast(10_000_000, 0x40, TestFrames.df17Position(0xA1B2C3, 35000, odd = false))
        val (consumed, events) = feed(frame.copyOf(frame.size - 3))
        assertEquals(0, consumed)
        assertTrue(events.isEmpty())
    }

    @Test fun filtersModeSRepliesUntilServerWantsThem() {
        val icao = 0xC0FFEE
        val df11 = TestFrames.df11(icao)
        var (_, events) = feed(TestFrames.beast(12_000_000, 0x40, df11))
        assertTrue("DF11 filtered while not requested", events.isEmpty())
        assertEquals(setOf(icao), reader.takeSeen())

        reader.interestedMlat += icao
        clockMs += 1000
        events = feed(TestFrames.beast(24_000_000, 0x40, df11)).second
        assertEquals(11, (events.single() as ReaderEvent.Message).message.df)
    }

    @Test fun reportsTimestampJumps() {
        val msg = TestFrames.df17Position(0xA1B2C3, 35000, odd = false)
        feed(TestFrames.beast(12_000_000, 0x40, msg))
        // Wall clock moves 1 s but the timestamp jumps 100 s, twice (upstream's OUTLIER_LIMIT is 1).
        clockMs += 1000
        val first = feed(TestFrames.beast(12_000_000 + 1_200_000_000L, 0x40, msg)).second
        clockMs += 1000
        val second = feed(TestFrames.beast(12_000_000 + 2_400_000_000L, 0x40, msg)).second
        assertTrue("messages dropped while jumping", first.none { it is ReaderEvent.Message })
        assertTrue(second.any { it is ReaderEvent.TimestampJump })
    }
}
