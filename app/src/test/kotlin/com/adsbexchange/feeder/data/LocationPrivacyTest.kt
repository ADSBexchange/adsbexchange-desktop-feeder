package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The receiver position is usually someone's home: it must not reach the log or diagnostics. */
class LocationPrivacyTest {
    private val home = StationLocation(
        lat = 43.123456, lon = -76.654321, altitudeM = 120.0, accuracyM = 5f,
        timeMs = 0, source = StationLocation.Source.GPS,
    )
    private val settings = FeederSettings(uuid = "00000000-0000-4000-8000-000000000000", name = "", location = home)

    @Test
    fun readsbCommandLineHasNoCoordinates() {
        val args = settings.readsbArgs(FeederPaths(File("files")), gpsdPort = 40000, feedTo = FeederSettings.ADSBX_FEED)
        val line = args.joinToString(" ")
        assertFalse(line, "--lat" in args || "--lon" in args)
        assertFalse(line, "43.12" in line || "76.65" in line)
        // The position still reaches readsb, over gpsd.
        assertTrue(line, "127.0.0.1,40000,gpsd_in" in args)
    }

    /** The feeder ID opens the stats page, so it's read from a file rather than logged with the command line. */
    @Test
    fun readsbCommandLineHasNoFeederId() {
        val paths = FeederPaths(File("files"))
        val args = settings.readsbArgs(paths, gpsdPort = 40000, feedTo = FeederSettings.ADSBX_FEED)
        val line = args.joinToString(" ")
        assertFalse(line, settings.uuid in line || "uuid=" in line)
        assertEquals("feeder-id", args[args.indexOf("--uuid-file") + 1])
        assertTrue(line, "feed1.adsbexchange.com,30004,beast_reduce_plus_out" in args)
        // Timed as ADS-B Exchange's feeder kit.
        assertEquals("0.5", args[args.indexOf("--net-beast-reduce-interval") + 1])
        assertEquals("0.2", args[args.indexOf("--net-ro-interval-beast-reduce") + 1])
        // Not feeding: no ID needed.
        assertFalse("--uuid-file" in settings.readsbArgs(paths, gpsdPort = 40000, feedTo = null))
    }

    /** The command line is logged; absolute paths would name the Windows account, often a person's name. */
    @Test
    fun readsbCommandLineHasNoProfilePaths() {
        val paths = FeederPaths(File(System.getProperty("java.io.tmpdir"), "Some Person\\AppData\\Local\\Feeder"))
        val args = settings.readsbArgs(paths, gpsdPort = 40000, feedTo = FeederSettings.ADSBX_FEED)
        val line = args.joinToString(" ")
        assertFalse(line, "Some Person" in line || paths.base.absolutePath in line)
        assertEquals("json", args[args.indexOf("--write-json") + 1])
    }

    @Test
    fun redactsReadsbPositionMessages() {
        val cases = mapOf(
            "readsb --lat 43.12346 --lon -76.65432 --quiet" to "readsb --lat [hidden] --lon [hidden] --quiet",
            "Using lat: 43.123456, lon: -76.654321 (location accuracy: exact)" to
                "Using lat: [hidden], lon: [hidden] (location accuracy: exact)",
            "[2026-09-30 10:24:29] Autodetected receiver location: 43.12346, -76.65432 at 120m AMSL" to
                "[2026-09-30 10:24:29] Autodetected receiver location: [hidden] at 120m AMSL",
            "gpsdebug: parsed lat,lon:   43.123456, -76.654321 (alt: 120 m)" to "gpsdebug: parsed lat,lon:   [hidden] (alt: 120 m)",
            "setLatLon: lat: 43.123456 lon: -76.654321" to "setLatLon: lat: [hidden] lon: [hidden]",
            """{"lat":43.123456,"lon":-76.654321}""" to """{"lat":[hidden],"lon":[hidden]}""",
        )
        for ((line, expected) in cases) assertEquals(expected, redactLocation(line))
    }

    @Test
    fun leavesOtherLinesAlone() {
        for (line in listOf(
            "BeastReduce TCP output: Connection established: 127.0.0.1 port 30100",
            "readsb version: 3.16.17 android-poc",
            "[feeder] mlat: handshake complete: compression zlib2, split sync disabled",
            "--net-connector feed1.adsbexchange.com,30004,beast_reduce_plus_out,uuid=0000",
        )) assertEquals(line, redactLocation(line))
    }
}
