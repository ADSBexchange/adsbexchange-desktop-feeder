package com.adsbexchange.feeder.mlat

import com.adsbexchange.feeder.mlat.TestFrames.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeSTest {
    @Test fun publishedIdentificationFrame() {
        // "The 1090 MHz Riddle" example: KLM1023, ICAO 4840D6, TC 4 (not a position).
        val m = ModeSMessage(1000, 0, hex("8D4840D6202CC371C32CE0576098"))
        assertEquals(17, m.df)
        assertTrue(m.valid)
        assertEquals(0x4840D6, m.address)
        assertFalse(m.evenCpr || m.oddCpr)
        assertEquals("8d4840d6202cc371c32ce0576098", m.hex())
    }

    @Test fun publishedPositionPair() {
        // "The 1090 MHz Riddle": ICAO 40621D at 38,000 ft, even then odd CPR, TC 11 (NUCp 7).
        val even = ModeSMessage(1, 0, hex("8D40621D58C382D690C8AC2863A7"))
        val odd = ModeSMessage(2, 0, hex("8D40621D58C386435CC412692AD6"))
        for (m in listOf(even, odd)) {
            assertTrue(m.valid)
            assertEquals(0x40621D, m.address)
            assertEquals(38000, m.altitude)
            assertEquals(7, m.nuc)
        }
        assertTrue(even.evenCpr && !even.oddCpr)
        assertTrue(odd.oddCpr && !odd.evenCpr)
    }

    @Test fun corruptedDf17IsInvalid() {
        val bytes = hex("8D40621D58C382D690C8AC2863A7")
        bytes[5] = (bytes[5].toInt() xor 0x01).toByte()
        assertFalse(ModeSMessage(1, 0, bytes).valid)
    }

    @Test fun positionWithoutCprDataIsInvalid() {
        val m = ModeSMessage(1, 0, TestFrames.df17Position(0xabcdef, 5000, odd = false, lat17 = 0, lon17 = 0x10))
        assertFalse(m.valid)
    }

    @Test fun addressParityAndAltitude() {
        // DF4 with 25 ft coding: N = (5000 + 1000) / 25 = 240 -> Q-bit layout in AC13.
        val n = 240
        // Inverse of decodeAc13's Q-bit unpacking: N bits 10-5 -> 12-7, bit 4 -> 5, bits 3-0 stay.
        val ac13 = ((n and 0x7e0) shl 2) or ((n and 0x10) shl 1) or 0x10 or (n and 0xf)
        val m = ModeSMessage(1, 0, TestFrames.df4(0xC0FFEE, ac13))
        assertEquals(4, m.df)
        assertTrue(m.valid)
        assertEquals(0xC0FFEE, m.address)
        assertEquals(5000, m.altitude)
    }

    @Test fun df11AllCall() {
        val m = ModeSMessage(1, 0, TestFrames.df11(0x0D15EA))
        assertEquals(11, m.df)
        assertTrue(m.valid)
        assertEquals(0x0D15EA, m.address)
    }

    @Test fun altitudeEdgeCases() {
        assertNull(ModeSMessage.decodeAc13(0))
        assertNull(ModeSMessage.decodeAc13(0x0040)) // metric
        assertEquals(-1000, ModeSMessage.decodeAc13(0x0010)) // Q bit, N = 0
    }
}
