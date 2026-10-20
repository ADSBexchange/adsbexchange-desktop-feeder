package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirportsTest {
    @Test
    fun theBundledListLoads() {
        val all = Airports.bundled.all
        assertTrue("${all.size} airports", all.size in 15_000..30_000)
        val syr = all.single { it.code == "KSYR" }
        assertEquals(Airports.Size.LARGE, syr.size)
        assertEquals(43.1112, syr.lat, 0.01)
        assertEquals(-76.1063, syr.lon, 0.01)
        assertTrue("every size is there", Airports.Size.entries.all { s -> all.any { it.size == s } })
        assertTrue("codes are short and tab-free", all.all { it.code.length in 2..8 && '\t' !in it.code })
        assertTrue("coordinates in range", all.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 })
    }

    @Test
    fun theBundledListNamesItsPinnedSource() {
        val header = Airports::class.java.getResourceAsStream("/airports/airports.tsv")!!
            .bufferedReader().useLines { lines -> lines.takeWhile { it.startsWith("#") }.joinToString("\n") }
        assertTrue(header, header.contains("ourairports-data b529623d2a0b09a9be78d37fc7a7a5e1e16e3451"))
        assertTrue(header, header.contains("SHA-256 0499dbd88de2fc735ac5c09f59796ccc3af544e17ffda807f793c0c6fe6c3d75"))
    }

    @Test
    fun parseSkipsCommentsAndBadLines() {
        val a = Airports.parse(sequenceOf("# comment", "", "KSYR\tL\t43.1\t-76.1", "BAD\tX\t1\t2", "SHORT\tL\t1", "KITH\tM\t42.5\t-76.5"))
        assertEquals(listOf("KSYR", "KITH"), a.all.map { it.code })
    }

    @Test
    fun nearIsABoxAroundThePoint() {
        val a = Airports.parse(sequenceOf(
            "NEAR\tS\t43.2\t-76.1", "FAR\tL\t45.0\t-76.0", "EAST\tM\t43.0\t-75.0",
            "DATELINE\tM\t0.0\t179.9", "OTHERSIDE\tM\t0.0\t-179.9",
        ))
        assertEquals(listOf("NEAR"), a.near(43.0, -76.0, 25.0).map { it.code })
        assertEquals("1° of longitude at 43N is about 44 nm", listOf("NEAR", "EAST"), a.near(43.0, -76.0, 50.0).map { it.code })
        assertEquals("across the date line", listOf("DATELINE", "OTHERSIDE"), a.near(0.0, 180.0, 25.0).map { it.code })
    }
}
