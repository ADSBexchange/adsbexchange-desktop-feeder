package com.adsbexchange.feeder.data

import com.adsbexchange.feeder.data.Milestones.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MilestonesTest {
    private val day = 86_400_000L

    @Test
    fun progressTowardsTheNextMilestone() {
        val h = History(messages = 550_000, uniqueAircraft = 40, feedingMs = 8 * day)
        val p = Milestones.progress(h).associateBy { it.kind }
        assertEquals(100_000L, p.getValue(Kind.MESSAGES).reached!!.target)
        assertEquals(1_000_000L, p.getValue(Kind.MESSAGES).next!!.target)
        assertEquals(0.5f, p.getValue(Kind.MESSAGES).fraction, 1e-6f)
        assertNull(p.getValue(Kind.AIRCRAFT).reached)
        assertEquals(0.4f, p.getValue(Kind.AIRCRAFT).fraction, 1e-6f)
        assertEquals(7 * day, p.getValue(Kind.FEEDING).reached!!.target)
        assertEquals(4, Milestones.reachedCount(h)) // 10k, 100k messages; 1 and 7 days
    }

    @Test
    fun theTopOfASeriesIsAllReached() {
        val p = Milestones.progress(History(messages = 2_000_000_000)).first { it.kind == Kind.MESSAGES }
        assertNull(p.next)
        assertEquals(1f, p.fraction)
    }
}
