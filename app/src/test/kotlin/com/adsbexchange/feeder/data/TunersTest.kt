package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stick's tuner from readsb's log, and which tuners can't receive 1090 MHz (issue #30). */
class TunersTest {
    @Test
    fun theTunerIsReadFromTheLog() {
        // As readsb.exe logged it in the report.
        val log = """
            rtlsdr: using device #0: Generic RTL2832U (Realtek, RTL2832U, SN 00000001)
            Found Fitipower FC0012 tuner
            rtlsdr: BAD TUNER, 1090 reception will be TERRIBLE, use another SDR
            [FC0012] no valid PLL combination found for 1090000000 Hz!
        """.trimIndent()
        assertEquals("Fitipower FC0012", Tuners.found(log))
        assertEquals("Rafael Micro R820T", Tuners.found("Found Rafael Micro R820T tuner\r\n"))
        assertNull("not said yet", Tuners.found("rtlsdr: using device #0"))
    }

    @Test
    fun onlyTunersThatCantReach1090AreRefused() {
        assertTrue(Tuners.cannotReceive("Fitipower FC0012"))
        assertTrue(Tuners.cannotReceive("FCI 2580"))
        assertFalse("barely, but it does", Tuners.cannotReceive("Fitipower FC0013"))
        assertTrue(Tuners.poor("Fitipower FC0013"))
        listOf("Rafael Micro R820T", "Rafael Micro R828D", "Elonics E4000").forEach {
            assertFalse(it, Tuners.cannotReceive(it) || Tuners.poor(it))
        }
    }
}
