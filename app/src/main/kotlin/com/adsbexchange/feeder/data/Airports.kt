package com.adsbexchange.feeder.data

import kotlin.math.abs
import kotlin.math.cos

/**
 * Airports for the radar: OurAirports' large and medium airports, and the small ones with an ICAO
 * code or scheduled service, bundled in resources/airports/airports.tsv (made by
 * tools/airports/MakeAirports.java from a pinned snapshot). Nothing is fetched at run time.
 */
class Airports(val all: List<Airport>) {
    enum class Size { LARGE, MEDIUM, SMALL }

    class Airport(val code: String, val size: Size, val lat: Double, val lon: Double)

    /**
     * The airports roughly within [nm] of a point: a latitude/longitude box, so it can include a few
     * just outside; the caller measures the real distance.
     */
    fun near(lat: Double, lon: Double, nm: Double): List<Airport> {
        val dLat = nm / 60.0
        // Longitude degrees shrink towards the poles; past about 89° take every longitude.
        val c = cos(Math.toRadians(lat))
        val dLon = if (c < 0.02) 360.0 else nm / (60.0 * c)
        return all.filter { a ->
            abs(a.lat - lat) <= dLat && (dLon >= 180.0 || abs(((a.lon - lon + 540.0) % 360.0) - 180.0) <= dLon)
        }
    }

    companion object {
        /** The bundled list, read on first use (about 19,500 airports, half a megabyte). */
        val bundled: Airports by lazy {
            val stream = Airports::class.java.getResourceAsStream("/airports/airports.tsv")
                ?: error("airports.tsv is missing from the app's resources")
            stream.bufferedReader().useLines { parse(it) }
        }

        /** The tab-separated list: code, size (L, M or S), latitude, longitude; # starts a comment. */
        fun parse(lines: Sequence<String>): Airports = Airports(lines.mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val f = line.split('\t')
            if (f.size != 4) return@mapNotNull null
            val size = when (f[1]) {
                "L" -> Size.LARGE
                "M" -> Size.MEDIUM
                "S" -> Size.SMALL
                else -> return@mapNotNull null
            }
            val lat = f[2].toDoubleOrNull() ?: return@mapNotNull null
            val lon = f[3].toDoubleOrNull() ?: return@mapNotNull null
            Airport(f[0], size, lat, lon)
        }.toList())
    }
}
