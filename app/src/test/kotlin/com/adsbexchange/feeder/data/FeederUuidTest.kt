package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class FeederUuidTest {
    private val uuids = List(10_000) { FeederSettings.newFeederUuid() }

    @Test
    fun endsWithTheDesktopTag() {
        for (u in uuids) assertTrue(u, u.endsWith("ad5bde5c"))
    }

    @Test
    fun isAStandardVersion4Uuid() {
        for (u in uuids) {
            assertTrue(u, Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(u))
            assertEquals(u, UUID.fromString(u).toString())
        }
    }

    /** readsb on ADS-B Exchange's end reads 32 hex digits and stops at anything else. */
    @Test
    fun survivesReadsbsUuidParsing() {
        for (u in uuids) assertTrue(u, Regex("[0-9a-f]{32}").matches(u.replace("-", "")))
    }

    /** Feeder pages show the first 8 characters, so they must still tell feeders apart. */
    @Test
    fun staysUnique() {
        assertEquals(uuids.size, uuids.toSet().size)
        assertTrue(uuids.map { it.take(8) }.toSet().size > uuids.size * 0.99)
    }
}
