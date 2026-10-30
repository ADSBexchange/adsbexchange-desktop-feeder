package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Altitudes typed in feet or metres, and the one-time check of one saved when Settings took metres only. */
class AltitudeUnitTest {
    @get:Rule val tmp = TemporaryFolder()
    private val paths by lazy { FeederPaths(tmp.root) }

    @Test
    fun theUnitChosenAppliesUnlessTheTextSaysOtherwise() {
        assertEquals(1177.1376, TypedNumber.altitude("3862", AltitudeUnit.FEET)!!, 1e-6)
        assertEquals(1177.0, TypedNumber.altitude("1177", AltitudeUnit.METRES)!!, 0.0)
        assertEquals("feet, though metres is chosen", 1177.1376, TypedNumber.altitude("3,862 ft", AltitudeUnit.METRES)!!, 1e-6)
        assertEquals(1177.1376, TypedNumber.altitude("3862'", AltitudeUnit.METRES)!!, 1e-6)
        assertEquals(1177.1376, TypedNumber.altitude("3862 feet", AltitudeUnit.METRES)!!, 1e-6)
        assertEquals("metres, though feet is chosen", 1177.0, TypedNumber.altitude("1177 m", AltitudeUnit.FEET)!!, 0.0)
        assertEquals("a thousands separator, not feet", 1177.0, TypedNumber.altitude("1'177", AltitudeUnit.METRES)!!, 0.0)
        assertEquals(-3.048, TypedNumber.altitude("-10 ft", AltitudeUnit.METRES)!!, 1e-9)
        assertNull(TypedNumber.altitude("high", AltitudeUnit.FEET))
        assertNull(TypedNumber.altitude("ft", AltitudeUnit.FEET))
    }

    @Test
    fun feetWhereTheRegionUsesThem() {
        assertEquals(AltitudeUnit.FEET, AltitudeUnit.forRegion("US"))
        assertEquals(AltitudeUnit.METRES, AltitudeUnit.forRegion("GB"))
        assertEquals(AltitudeUnit.METRES, AltitudeUnit.forRegion("DE"))
        assertEquals(AltitudeUnit.METRES, AltitudeUnit.forRegion(""))
    }

    @Test
    fun bothUnitsAreShown() {
        assertEquals("1,177 m (3,862 ft)", AltitudeUnit.describe(1177.1376))
        assertEquals("12.5 m (41 ft)", AltitudeUnit.describe(12.5))
        assertEquals("3862", AltitudeUnit.FEET.fieldText(1177.14))
        assertEquals("1177.2", AltitudeUnit.METRES.fieldText(1177.2))
    }

    @Test
    fun anAltitudeFromBefore0521IsCheckedOnceWhereFeetAreUsed() {
        val old = FeederSettings(uuid = "u", name = "", location = null, antennaAltitudeM = 1177.0)
        assertTrue(old.altitudeNeedsCheck(region = AltitudeUnit.FEET))
        assertFalse("metres were right there", old.altitudeNeedsCheck(region = AltitudeUnit.METRES))
        assertFalse("confirmed or saved with a unit", old.copy(altitudeUnit = AltitudeUnit.FEET).altitudeNeedsCheck(region = AltitudeUnit.FEET))
        assertFalse("no altitude", old.copy(antennaAltitudeM = null).altitudeNeedsCheck(region = AltitudeUnit.FEET))
        assertEquals(AltitudeUnit.FEET, old.shownAltitudeUnit(region = AltitudeUnit.FEET))
        assertEquals(AltitudeUnit.METRES, old.copy(altitudeUnit = AltitudeUnit.METRES).shownAltitudeUnit(region = AltitudeUnit.FEET))
    }

    @Test
    fun theUnitIsSaved() {
        assertNull(FeederSettings.load(paths).altitudeUnit)
        FeederSettings.update(paths) { it.copy(antennaAltitudeM = 1177.14, altitudeUnit = AltitudeUnit.FEET) }
        assertEquals(AltitudeUnit.FEET, FeederSettings.load(paths).altitudeUnit)
    }

    @Test
    fun feetTypedAsMetresIsOutOfRangeForMlatAboveAbout5100Ft() {
        val denver = FeederSettings(uuid = "u", name = "", location = StationLocation.manual(39.74, -104.99, null),
            antennaAltitudeM = 5280.0)
        assertEquals(MlatReadiness.NO_ALTITUDE, denver.mlatReadiness())
        assertEquals(MlatReadiness.READY, denver.copy(antennaAltitudeM = AltitudeUnit.FEET.toMetres(5280.0)).mlatReadiness())
    }
}
