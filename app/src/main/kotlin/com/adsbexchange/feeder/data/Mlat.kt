package com.adsbexchange.feeder.data

/**
 * Whether MLAT can run, and if not, what it's waiting for. Checked by the service (to start or
 * stop the client) and by the UI (to tell the user what to do).
 *
 * MLAT times the same aircraft transmission at several feeders, so a receiver position that's
 * off by more than about 15 m spoils it (the ADSBx feeder setup says as much).
 */
enum class MlatReadiness {
    READY,
    OFF,
    /** Feeding to ADS-B Exchange is off, so there's no MLAT either. */
    NOT_FEEDING,
    NO_LOCATION,
    /** Position not accurate enough; see [MLAT_ACCURACY_M]. (Typed positions count as accurate.) */
    IMPRECISE,
    NO_ALTITUDE,
    /** The user hasn't confirmed the position entered is the antenna's. */
    NOT_CONFIRMED,
}

/** Required location accuracy for MLAT, from the ADSBx feeder setup ("15m/45ft"). */
const val MLAT_ACCURACY_M = 15f

fun FeederSettings.mlatReadiness(): MlatReadiness {
    val loc = location
    return when {
        !mlatEnabled -> MlatReadiness.OFF
        !feedAdsbx -> MlatReadiness.NOT_FEEDING
        loc == null -> MlatReadiness.NO_LOCATION
        loc.source != StationLocation.Source.MANUAL && (loc.accuracyM ?: Float.MAX_VALUE) > MLAT_ACCURACY_M ->
            MlatReadiness.IMPRECISE
        mlatAltitudeM()?.let { it in -420.0..5100.0 } != true -> MlatReadiness.NO_ALTITUDE
        !antennaConfirmed -> MlatReadiness.NOT_CONFIRMED
        else -> MlatReadiness.READY
    }
}

/** Antenna height for MLAT (above the WGS84 ellipsoid): the antenna height entered, else the position's. */
fun FeederSettings.mlatAltitudeM(): Double? = antennaAltitudeM ?: location?.altitudeM

/**
 * The MLAT "user" field. Upstream requires one, so a blank name becomes an anonymous but stable
 * id, e.g. "desktopfeeder-9ba38053".
 */
fun FeederSettings.mlatUser(): String = name.ifBlank { "desktopfeeder-${uuid.take(8)}" }
