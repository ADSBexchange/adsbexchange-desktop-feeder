package com.adsbexchange.feeder.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The unit the antenna altitude is typed in. Settings stores metres either way. Feet to start with
 * where heights are given in feet (Windows' region set to the US, Liberia or Myanmar), metres
 * everywhere else.
 */
enum class AltitudeUnit(val symbol: String, private val metresPerUnit: Double) {
    METRES("m", 1.0),
    FEET("ft", 0.3048);

    fun toMetres(value: Double): Double = value * metresPerUnit
    fun fromMetres(metres: Double): Double = metres / metresPerUnit

    /** The altitude for the Settings field in this unit: no grouping, at most one decimal. */
    fun fieldText(metres: Double): String {
        val v = (fromMetres(metres) * 10).roundToLong() / 10.0
        return if (v == v.roundToLong().toDouble()) v.roundToLong().toString() else v.toString()
    }

    companion object {
        private val FEET_REGIONS = setOf("US", "LR", "MM")

        fun forRegion(country: String = Locale.getDefault().country): AltitudeUnit =
            if (country.uppercase(Locale.ROOT) in FEET_REGIONS) FEET else METRES

        fun ofSymbol(symbol: String?): AltitudeUnit? = entries.firstOrNull { it.symbol == symbol }

        /** "1,177 m (3,862 ft)": both, so a value typed in the wrong unit stands out. */
        fun describe(metres: Double): String = "${grouped(metres)} m (${grouped(FEET.fromMetres(metres))} ft)"

        private fun grouped(v: Double): String =
            if (abs(v) < 100) "%.1f".format(Locale.US, v).removeSuffix(".0") else "%,.0f".format(Locale.US, v)
    }
}
