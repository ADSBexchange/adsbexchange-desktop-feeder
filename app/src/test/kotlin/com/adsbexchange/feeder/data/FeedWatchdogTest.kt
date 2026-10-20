package com.adsbexchange.feeder.data

import com.adsbexchange.feeder.data.FeedWatchdog.Action
import com.adsbexchange.feeder.data.FeedWatchdog.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's FeedWatchdogTest, ticking every 30 s as a running engine does: the desktop watchdog
 * treats a longer gap between ticks as the PC having slept (see [sleepIsNotSilence]).
 */
class FeedWatchdogTest {
    private val min = 60_000L
    private val step = 30_000L

    /** Ticks from [fromMs] (exclusive) to [toMs] (inclusive) every 30 s; returns the non-null actions. */
    private fun FeedWatchdog.run(
        fromMs: Long, toMs: Long, feeding: Boolean = true, connected: Boolean, messages: (Long) -> Long?,
    ): List<Pair<Long, Action>> {
        val out = ArrayList<Pair<Long, Action>>()
        var t = fromMs + step
        while (t <= toMs) {
            tick(true, feeding, connected, messages(t), t)?.let { out += t to it }
            t += step
        }
        return out
    }

    /** Messages that keep arriving: a new count every tick. */
    private val busy: (Long) -> Long = { it / 1000 }

    @Test
    fun alertsOnceWhenTheFeedHasBeenDownFor15MinutesAndClearsWhenItsBack() {
        val w = FeedWatchdog()
        assertNull(w.tick(running = true, feeding = true, feedConnected = false, messagesTotal = 0, nowMs = 0))
        assertTrue(w.run(0, 14 * min, connected = false, messages = busy).isEmpty())
        assertEquals(listOf(15 * min to Action.Alert(Problem.FEED_DOWN)), w.run(14 * min, 20 * min, connected = false, messages = busy))
        assertEquals(Action.Clear, w.tick(true, true, true, 2_000, 20 * min + step))
        assertTrue(w.run(20 * min + step, 22 * min, connected = true, messages = busy).isEmpty())
    }

    @Test
    fun aShortWifiBlipIsNotAnAlert() {
        val w = FeedWatchdog()
        w.tick(true, true, false, 0, 0)
        w.run(0, 10 * min, connected = true, messages = busy)
        assertTrue("the clock restarted at the new drop", w.run(10 * min, 24 * min, connected = false, messages = busy).isEmpty())
        // The drop began at the first disconnected tick, 10 min 30 s.
        assertEquals(listOf(25 * min + step to Action.Alert(Problem.FEED_DOWN)), w.run(24 * min, 26 * min, connected = false, messages = busy))
    }

    @Test
    fun alertsAfterTwoHoursWithNoMessages() {
        val w = FeedWatchdog()
        w.tick(true, true, true, 500, 0)
        assertTrue(w.run(0, 119 * min, connected = true) { 500 }.isEmpty())
        assertEquals(listOf(120 * min to Action.Alert(Problem.NO_AIRCRAFT)), w.run(119 * min, 120 * min, connected = true) { 500 })
        assertEquals("messages again", Action.Clear, w.tick(true, true, true, 501, 120 * min + step))
    }

    @Test
    fun notFeedingADSBExchangeIsNotAFeedProblem() {
        val w = FeedWatchdog()
        w.tick(true, feeding = false, feedConnected = false, messagesTotal = 0, nowMs = 0)
        assertTrue(w.run(0, 60 * min, feeding = false, connected = false, messages = busy).isEmpty())
    }

    @Test
    fun stoppingClearsAnAlert() {
        val w = FeedWatchdog()
        w.tick(true, true, false, 0, 0)
        w.run(0, 15 * min, connected = false, messages = busy)
        assertEquals(Action.Clear, w.tick(running = false, feeding = false, feedConnected = false, messagesTotal = null, nowMs = 16 * min))
        assertNull(w.clear())
    }

    /**
     * Desktop only: the PC slept for 5 hours with the feeder running. Waking, the message count
     * hasn't moved and the feed is reconnecting, but neither has lasted the threshold while awake.
     */
    @Test
    fun sleepIsNotSilence() {
        val w = FeedWatchdog()
        w.tick(true, true, true, 500, 0)
        w.run(0, 10 * min, connected = true) { 500 }
        val wake = 10 * min + 5 * 60 * min
        assertNull("no alert on the first tick after sleep", w.tick(true, true, false, 500, wake))
        assertTrue("timing starts again at wake-up", w.run(wake, wake + 14 * min, connected = false) { 500 }.isEmpty())
        assertEquals(listOf(wake + 15 * min to Action.Alert(Problem.FEED_DOWN)),
            w.run(wake + 14 * min, wake + 15 * min, connected = false) { 500 })
    }
}
