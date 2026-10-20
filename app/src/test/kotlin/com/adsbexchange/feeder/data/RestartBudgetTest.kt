package com.adsbexchange.feeder.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartBudgetTest {
    private val min = 60_000L

    @Test
    fun fifthExitWithinFifteenMinutesGivesUp() {
        val b = RestartBudget()
        repeat(4) { assertTrue("exit ${it + 1}", b.allow(it * min)) }
        assertFalse(b.allow(14 * min))
    }

    @Test
    fun exitsOlderThanTheWindowDontCount() {
        val b = RestartBudget()
        repeat(4) { b.allow(it * min) } // minutes 0-3
        // Minute 16: the exits at 0 and 1 have aged out, so this is the third in the window (2, 3, 16).
        assertTrue(b.allow(16 * min))
        assertTrue(b.allow(16 * min + 10_000))
        // The fifth within 15 minutes: 2, 3, 16, 16:10, 16:20.
        assertFalse(b.allow(16 * min + 20_000))
    }

    @Test
    fun aDeliberateStartBeginsAfresh() {
        val b = RestartBudget()
        repeat(4) { b.allow(it * min) }
        b.reset()
        repeat(4) { assertTrue(b.allow((5 + it) * min)) }
    }
}
