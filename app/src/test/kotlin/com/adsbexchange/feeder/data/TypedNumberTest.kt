package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypedNumberTest {
    @Test
    fun coordinatesTakeADecimalCommaOrPoint() {
        assertEquals(51.05011, TypedNumber.coordinate("51,05011")!!, 1e-9)
        assertEquals(-76.654321, TypedNumber.coordinate(" -76.654321 ")!!, 1e-9)
        assertEquals("a coordinate's comma is never thousands", 43.123, TypedNumber.coordinate("43,123")!!, 1e-9)
        assertEquals(43.1, TypedNumber.coordinate("43.1°")!!, 1e-9)
        assertNull(TypedNumber.coordinate("43.1.2"))
        assertNull(TypedNumber.coordinate("north"))
        assertNull(TypedNumber.coordinate(""))
    }

    @Test
    fun metresTakeThousandsSeparatorsAndDecimalCommas() {
        assertEquals(1234.0, TypedNumber.metres("1,234")!!, 0.0)
        assertEquals(1234.0, TypedNumber.metres("1 234 m")!!, 0.0)
        assertEquals(120.5, TypedNumber.metres("120,5")!!, 1e-9)
        assertEquals(1234.5, TypedNumber.metres("1.234,5")!!, 1e-9)
        assertEquals(1234.5, TypedNumber.metres("1,234.5")!!, 1e-9)
        assertEquals(-12.0, TypedNumber.metres("-12 metres")!!, 0.0)
        assertNull("feet aren't metres", TypedNumber.metres("3450 ft"))
        assertNull("a typo, not a number", TypedNumber.metres("1,234,5"))
    }

    @Test
    fun gainTakesADecimalComma() {
        assertEquals(49.6, TypedNumber.decibels("49,6")!!, 1e-9)
        assertEquals(40.2, TypedNumber.decibels("40.2 dB")!!, 1e-9)
        assertNull(TypedNumber.decibels("auto"))
    }
}
