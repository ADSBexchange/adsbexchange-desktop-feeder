package com.adsbexchange.feeder.data

/**
 * Reads a number typed the way people write them: "51,05011" is 51.05011 (a decimal comma, as many
 * keyboards type), "1,234" metres is 1234, "1.234,5" is 1234.5, and spaces and the field's own unit
 * are ignored. Null if it still isn't a number, so a typo is refused rather than saved as something
 * else. As the Linux feeder image's `parseNumber` (2026-10-22).
 */
object TypedNumber {
    /** A latitude or longitude. A coordinate never needs thousands, so one comma is a decimal comma. */
    fun coordinate(typed: String): Double? = parse(typed, quantity = false, units = listOf("°"))

    /** An altitude in metres. Feet aren't accepted, so "3450 ft" isn't saved as 3450 m. */
    fun metres(typed: String): Double? = parse(typed, quantity = true, units = listOf("metres", "meters", "m"))

    /**
     * An altitude, in metres, typed in [unit] unless it says otherwise: "3862 ft", "3862'" and
     * "3862 feet" are feet and "1177 m" is metres whichever unit is chosen. A trailing ' is feet;
     * one between digits ("1'177") is still a thousands separator.
     */
    fun altitude(typed: String, unit: AltitudeUnit): Double? {
        FEET_MARK.find(typed)?.let { mark ->
            return parse(typed.substring(0, mark.range.first), quantity = true, units = emptyList())?.let(AltitudeUnit.FEET::toMetres)
        }
        if (unit == AltitudeUnit.METRES || METRE_MARK.containsMatchIn(typed)) return metres(typed)
        return parse(typed, quantity = true, units = emptyList())?.let(unit::toMetres)
    }

    private val FEET_MARK = Regex("(?i)\\s*(ft|feet|foot|'|′)\\s*$")
    private val METRE_MARK = Regex("(?i)\\d\\s*(m|metres|meters)\\s*$")

    /** A gain in dB. */
    fun decibels(typed: String): Double? = parse(typed, quantity = true, units = listOf("db"))

    private val SPACES = Regex("[\\s  '’]")
    private val THOUSANDS = Regex("^[-+]?\\d{1,3}(,\\d{3})+$")
    private val PLAIN = Regex("^[-+]?\\d+(\\.\\d+)?$")

    private fun parse(typed: String, quantity: Boolean, units: List<String>): Double? {
        var s = typed.replace(SPACES, "")
        units.firstOrNull { s.endsWith(it, ignoreCase = true) }?.let { s = s.dropLast(it.length) }
        val comma = s.lastIndexOf(','); val dot = s.lastIndexOf('.')
        s = when {
            // Both: whichever comes last is the decimal mark.
            comma >= 0 && dot >= 0 -> if (comma > dot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            comma >= 0 -> {
                val commas = s.count { it == ',' }
                if (commas > 1 && !THOUSANDS.matches(s)) return null // "1,234,5" is a typo, not a number
                val thousands = commas > 1 || (quantity && Regex(",\\d{3}$").containsMatchIn(s))
                if (thousands) s.replace(",", "") else s.replace(',', '.')
            }
            quantity && s.count { it == '.' } > 1 -> s.replace(".", "") // 1.234.567
            else -> s
        }
        return if (PLAIN.matches(s)) s.toDouble() else null
    }
}
