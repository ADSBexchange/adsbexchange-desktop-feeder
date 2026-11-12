package com.adsbexchange.feeder.data

/**
 * RTL-SDR tuner chips, by the name librtlsdr prints when readsb opens a stick ("Found Fitipower
 * FC0012 tuner"; an R820T2 or R860 shows as "Rafael Micro R820T"). readsb flags the FC0012, FC0013
 * and FC2580 as bad for 1090 MHz and carries on (sdr_rtlsdr.c); the app says what that means (issue #30).
 */
object Tuners {
    private val FOUND = Regex("""Found (.+) tuner""")

    /** The tuner named in [log] (readsb's output since it started), or null if it hasn't said yet. */
    fun found(log: String): String? = FOUND.findAll(log).lastOrNull()?.groupValues?.get(1)?.trim()

    /** Its range ends below 1090 MHz, so it can't tune there at all ("no valid PLL combination"). */
    fun cannotReceive(tuner: String): Boolean = tuner.contains("FC0012") || tuner.contains("2580")

    /** It tunes to 1090 MHz, at the very edge of its range, and hears little there. */
    fun poor(tuner: String): Boolean = tuner.contains("FC0013")

    /** What to use instead, for the messages about the two above. */
    const val ADVICE = "A stick made for ADS-B, such as ADS-B Exchange's, or any RTL-SDR with an R820T, R820T2, " +
        "R860 or R828D tuner (the RTL-SDR Blog V3 and V4 among them), receives 1090 MHz properly."
}
