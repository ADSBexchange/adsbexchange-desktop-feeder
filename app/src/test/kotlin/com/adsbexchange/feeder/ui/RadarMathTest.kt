package com.adsbexchange.feeder.ui

import com.adsbexchange.feeder.data.Airports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarMathTest {
    @Test
    fun autoRangeFitsTheFurthestAircraftWithRoom() {
        assertEquals(RadarMath.DEFAULT_NM, RadarMath.autoRange(null, null))
        assertEquals(10, RadarMath.autoRange(5.0, null))
        assertEquals("9.5 nm with 10% room needs more than 10", 25, RadarMath.autoRange(9.5, null))
        assertEquals(100, RadarMath.autoRange(60.0, null))
        assertEquals("beyond the largest range", 300, RadarMath.autoRange(400.0, null))
    }

    @Test
    fun autoRangeGrowsAtOnceButShrinksOnlyWithRoomToSpare() {
        assertEquals("grows", 150, RadarMath.autoRange(120.0, 100))
        assertEquals("48 nm would fit 50, but only just: stays at 100", 100, RadarMath.autoRange(40.0, 100))
        assertEquals("well inside 50: shrinks", 50, RadarMath.autoRange(30.0, 100))
        assertEquals("nothing heard: keeps the current range", 150, RadarMath.autoRange(null, 150))
    }

    @Test
    fun ringsAreTidyStepsAndAtMostFive() {
        for (range in RadarMath.RANGES_NM) {
            val step = RadarMath.ringStepNm(range)
            assertTrue("$range nm: ${range / step} rings", range / step <= 5)
            assertTrue("$range nm: at least 2 rings", range / step >= 2)
        }
        assertEquals(10.0, RadarMath.ringStepNm(50), 0.0)
        assertEquals(50.0, RadarMath.ringStepNm(200), 0.0)
    }

    @Test
    fun distanceAndBearing() {
        // One degree of latitude north is 60 nm.
        val (dn, bn) = RadarMath.distanceBearing(45.0, -75.0, 46.0, -75.0)
        assertEquals(60.0, dn, 0.1)
        assertEquals(0.0, bn, 0.01)
        // East along the equator.
        val (de, be) = RadarMath.distanceBearing(0.0, 0.0, 0.0, 1.0)
        assertEquals(60.04, de, 0.1)
        assertEquals(90.0, be, 0.01)
        // South-west.
        val (_, bsw) = RadarMath.distanceBearing(0.0, 0.0, -0.5, -0.5)
        assertEquals(225.0, bsw, 0.1)
        assertEquals(0.0, RadarMath.distanceBearing(10.0, 10.0, 10.0, 10.0).first, 1e-9)
    }

    @Test
    fun altitudeColoursFollowTheMapScale() {
        assertEquals("orange on the ground", 20f, RadarMath.altitudeHue(0), 0f)
        assertEquals(140f, RadarMath.altitudeHue(11_000), 0f)
        assertEquals("between stops", 220f, RadarMath.altitudeHue(25_500), 0.5f)
        assertEquals(300f, RadarMath.altitudeHue(40_000), 0f)
        assertEquals("tops out at red", 360f, RadarMath.altitudeHue(60_000), 0f)
        assertEquals(20f, RadarMath.altitudeHue(-500), 0f)
        for (h in 0..360 step 10) assertTrue(RadarMath.lightness(h.toFloat()) in 0.4f..0.7f)
    }

    @Test
    fun airportsSmallOnlyCloseInLargerAndNearerFirst() {
        // Around a receiver at 43N 76W: 1' of latitude is 1 nm.
        val a = Airports.parse(sequenceOf(
            "SMALL	S	43.1	-76.0",    // 6 nm
            "MED1	M	43.5	-76.0",     // 30 nm
            "LARGE	L	44.0	-76.0",    // 60 nm
            "MED2	M	43.2	-76.0",     // 12 nm
            "FAR	L	47.0	-76.0",      // 240 nm
        ))
        fun codes(range: Int) = RadarMath.airportsToShow(a, 43.0, -76.0, range).map { it.airport.code }
        assertEquals(listOf("MED2", "MED1", "SMALL"), codes(50))
        assertEquals("no small fields beyond 50 nm", listOf("LARGE", "MED2", "MED1"), codes(100))
        assertEquals(listOf("SMALL"), codes(10))
        val med2 = RadarMath.airportsToShow(a, 43.0, -76.0, 25).first { it.airport.code == "MED2" }
        assertEquals(12.0, med2.distanceNm, 0.1)
        assertEquals("due north", 0.0, med2.bearingDeg, 0.1)
    }

    @Test
    fun airportsAreCapped() {
        val many = Airports.parse((1..100).asSequence().map { "S$it	S	${43.0 + it * 0.001}	-76.0" })
        val shown = RadarMath.airportsToShow(many, 43.0, -76.0, 25)
        assertEquals(RadarMath.MAX_AIRPORTS, shown.size)
        assertEquals("the nearest kept", "S1", shown.first().airport.code)
    }

    @Test
    fun aroundSyracuseFromTheBundledList() {
        val shown = RadarMath.airportsToShow(Airports.bundled, 43.0, -76.0, 50).map { it.airport.code }
        assertTrue(shown.toString(), listOf("KSYR", "KITH", "KBGM", "KRME").all { it in shown })
        assertEquals("the large airport first", "KSYR", shown.first())
    }
}
