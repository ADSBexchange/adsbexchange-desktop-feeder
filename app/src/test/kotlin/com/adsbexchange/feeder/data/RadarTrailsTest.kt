package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarTrailsTest {
    private fun plane(hex: String, lat: Double?, lon: Double? = 0.0, seenPos: Double? = 0.5) = Aircraft(
        hex = hex, callsign = null, altitudeFt = 10_000, onGround = false, groundSpeedKt = null, trackDeg = null,
        lat = lat, lon = lon, distanceNm = null, rssi = null, seenSecondsAgo = 0.0, seenPosSecondsAgo = seenPos,
    )

    @Test
    fun keepsAPointEveryFewSeconds() {
        val trails = RadarTrails()
        var out: Map<String, List<RadarTrails.Point>> = emptyMap()
        // Moving a little every second for 30 s: a point every 5 s.
        for (s in 0..30) out = trails.update(listOf(plane("a", 45.0 + s * 0.001)), s * 1000L)
        assertEquals(7, out.getValue("a").size)
    }

    @Test
    fun skipsStaleAndMissingPositionsAndStillAircraft() {
        val trails = RadarTrails()
        trails.update(listOf(plane("a", 45.0)), 0)
        trails.update(listOf(plane("a", 45.0)), 10_000) // hasn't moved
        trails.update(listOf(plane("a", 45.1, seenPos = 30.0)), 20_000) // an old position
        val out = trails.update(listOf(plane("a", null), plane("b", 46.0, seenPos = null)), 30_000)
        assertEquals(1, out.getValue("a").size)
        assertTrue("no position age, no trail", "b" !in out)
    }

    @Test
    fun dropsOldPointsAndForgetsAircraftGoneQuiet() {
        val trails = RadarTrails()
        trails.update(listOf(plane("a", 45.0), plane("b", 40.0)), 0)
        trails.update(listOf(plane("a", 45.1)), RadarTrails.KEEP_MS)
        val out = trails.update(listOf(plane("a", 45.2)), RadarTrails.KEEP_MS + 10_000)
        assertEquals(listOf(45.1, 45.2), out.getValue("a").map { it.lat })
        assertTrue("b" !in out)
    }
}
