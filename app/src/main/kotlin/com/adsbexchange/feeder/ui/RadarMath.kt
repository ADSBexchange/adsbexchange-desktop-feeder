package com.adsbexchange.feeder.ui

import com.adsbexchange.feeder.data.Airports
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The radar's geometry and colours, kept apart from the drawing so they can be tested. */
object RadarMath {
    /** Ranges the radar shows, nm; Auto picks one of these. */
    val RANGES_NM = listOf(10, 25, 50, 100, 150, 200, 250, 300)
    /** The fixed choices offered next to Auto. */
    val CHOICES_NM = listOf(25, 50, 100, 200)
    /** Auto's range before anything with a position has been heard. */
    const val DEFAULT_NM = 50
    /** Positions older than this aren't shown; between [STALE_POSITION_S] and this they're dimmed. */
    const val MAX_POSITION_AGE_S = 60.0
    const val STALE_POSITION_S = 15.0

    /** Auto leaves this much room beyond the furthest aircraft. */
    private const val MARGIN = 1.1
    /** Auto only zooms in once everything fits well inside the smaller range, so it doesn't flicker. */
    private const val SHRINK_BELOW = 0.8

    /**
     * The range that fits [furthestNm] (the furthest aircraft with a position, or null for none).
     * It grows straight away, but shrinks only when the aircraft fit well inside the smaller range:
     * [current] is the range shown now, if any.
     */
    fun autoRange(furthestNm: Double?, current: Int?): Int {
        if (furthestNm == null) return current ?: DEFAULT_NM
        val needed = furthestNm * MARGIN
        val target = RANGES_NM.firstOrNull { it >= needed } ?: RANGES_NM.last()
        if (current == null || target >= current) return target
        return if (needed <= target * SHRINK_BELOW) target else current
    }

    /** Spacing of the range rings: the smallest step that gives at most 5 rings. */
    fun ringStepNm(rangeNm: Int): Double = RING_STEPS_NM.firstOrNull { rangeNm / it <= 5 } ?: RING_STEPS_NM.last()

    private val RING_STEPS_NM = listOf(2.5, 5.0, 10.0, 25.0, 50.0, 100.0)

    /** Great-circle distance (nm) and initial bearing (degrees clockwise from true north, 0–360). */
    fun distanceBearing(lat0: Double, lon0: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val p0 = Math.toRadians(lat0)
        val p1 = Math.toRadians(lat)
        val dp = p1 - p0
        val dl = Math.toRadians(lon - lon0)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p0) * cos(p1) * sin(dl / 2) * sin(dl / 2)
        val distance = 2 * EARTH_RADIUS_NM * asin(sqrt(a.coerceIn(0.0, 1.0)))
        val y = sin(dl) * cos(p1)
        val x = cos(p0) * sin(p1) - sin(p0) * cos(p1) * cos(dl)
        val bearing = (Math.toDegrees(atan2(y, x)) + 360) % 360
        return distance to bearing
    }

    private const val EARTH_RADIUS_NM = 3440.065

    /** Small airports only at this range or closer; further out they'd crowd the screen. */
    const val SMALL_AIRPORTS_MAX_NM = 50
    /** At most this many airports at once: the larger, then the nearer, first. */
    const val MAX_AIRPORTS = 30

    /** An airport placed relative to the receiver. */
    class PlacedAirport(val airport: Airports.Airport, val distanceNm: Double, val bearingDeg: Double)

    /**
     * The airports to draw within [rangeNm] of the receiver: small ones only at [SMALL_AIRPORTS_MAX_NM]
     * or closer, then the larger and nearer first, at most [MAX_AIRPORTS].
     */
    fun airportsToShow(airports: Airports, rxLat: Double, rxLon: Double, rangeNm: Int): List<PlacedAirport> {
        val small = rangeNm <= SMALL_AIRPORTS_MAX_NM
        return airports.near(rxLat, rxLon, rangeNm.toDouble())
            .filter { small || it.size != Airports.Size.SMALL }
            .map { a ->
                val (d, b) = distanceBearing(rxLat, rxLon, a.lat, a.lon)
                PlacedAirport(a, d, b)
            }
            .filter { it.distanceNm <= rangeNm }
            .sortedWith(compareBy<PlacedAirport> { it.airport.size.ordinal }.thenBy { it.distanceNm })
            .take(MAX_AIRPORTS)
    }

    /**
     * Hue (0–360) for a barometric altitude, on the scale the ADS-B Exchange map uses (after tar1090's
     * altitude colours): orange near the ground, through yellow and green, to blue, magenta and red
     * at the top.
     */
    fun altitudeHue(altitudeFt: Int): Float {
        val stops = HUE_STOPS
        if (altitudeFt <= stops.first().first) return stops.first().second
        for (i in 1 until stops.size) {
            val (alt, hue) = stops[i]
            if (altitudeFt <= alt) {
                val (alt0, hue0) = stops[i - 1]
                return hue0 + (hue - hue0) * (altitudeFt - alt0) / (alt - alt0)
            }
        }
        return stops.last().second
    }

    /** Lightness (0–1) for a hue, evening out how bright the colours look against the dark radar. */
    fun lightness(hue: Float): Float {
        val stops = LIGHTNESS_STOPS
        for (i in 1 until stops.size) {
            val (h, l) = stops[i]
            if (hue <= h) {
                val (h0, l0) = stops[i - 1]
                return (l0 + (l - l0) * (hue - h0) / (h - h0)) / 100f
            }
        }
        return stops.last().second / 100f
    }

    /** Altitude (ft) to hue: tar1090's stops. */
    private val HUE_STOPS = listOf(
        0 to 20f, 2_000 to 32.5f, 4_000 to 43f, 6_000 to 54f, 8_000 to 72f, 9_000 to 85f,
        11_000 to 140f, 40_000 to 300f, 51_000 to 360f,
    )

    /** Hue to lightness (%), lifted a little from tar1090's for the black background. */
    private val LIGHTNESS_STOPS = listOf(
        0f to 58f, 20f to 56f, 40f to 57f, 60f to 50f, 80f to 48f, 140f to 48f, 180f to 47f,
        200f to 62f, 240f to 64f, 280f to 64f, 300f to 52f, 320f to 55f, 360f to 58f,
    )

    /** The altitudes the legend marks. */
    val LEGEND_FT = listOf(0, 10_000, 20_000, 30_000, 40_000)
    const val LEGEND_TOP_FT = 45_000
}
