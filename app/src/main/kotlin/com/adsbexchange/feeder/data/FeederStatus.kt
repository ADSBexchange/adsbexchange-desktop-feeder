package com.adsbexchange.feeder.data

import org.json.JSONObject

/**
 * What the feeder engine is doing, for the UI and the tray. Kept in memory: on the desktop the
 * engine, the tray and the window are one process, and readsb is a child process whose exit the
 * engine sees directly.
 */
data class FeederStatus(
    val state: State,
    val reason: Reason = Reason.NONE,
    /**
     * Whether this run sends data to ADS-B Exchange. Can differ from the saved setting: the setting
     * only applies at the next start, and test runs feed a local sink instead.
     */
    val feeding: Boolean = false,
    /** readsb feed connector target as "host,port" (feed1.adsbexchange.com,30004 or a test sink). */
    val feedTarget: String? = null,
    val feedState: FeedState = FeedState.UNKNOWN,
    /** When the current feed connection was established, if it's up. */
    val connectedSinceMs: Long? = null,
    /** Times the feed connection has dropped during this run. */
    val reconnects: Int = 0,
    /** When readsb started for this run. */
    val startedMs: Long? = null,
    /** readsb's exit code, once this run's readsb has ended. */
    val exitCode: Int? = null,
    /** A "feeding stopped" problem while it lasts (see FeedWatchdog), for the Status screen. */
    val feedProblem: FeedWatchdog.Problem? = null,
    /**
     * readsb keeps stopping ([RestartBudget.MAX_EXITS] times in a short time) and the engine keeps
     * restarting it, until a run stays up; see [RestartBudget].
     */
    val sdrFailing: Boolean = false,
    /** Loopback port of readsb's Beast output in this run. */
    val beastPort: Int? = null,
    /** The RTL-SDR stick found, by librtlsdr's name ("Generic RTL2832U OEM"); null if none or unknown. */
    val stickName: String? = null,
    /** The driver Windows has bound to the stick when it isn't WinUSB (shown with [Reason.NEEDS_DRIVER]). */
    val stickDriver: String? = null,
    val driverSetup: DriverSetup = DriverSetup.IDLE,
    val mlat: MlatStatus? = null,
    /** The stats uploads while they're on (see StatsUploader); null when they aren't. */
    val stats: StatsStatus? = null,
    /** Stats uploads are paused because the connection is metered (Settings > Send stats on metered connections). */
    val statsPausedMetered: Boolean = false,
    val updatedMs: Long = System.currentTimeMillis(),
) {
    enum class State { STOPPED, STARTING, RUNNING }

    enum class Reason {
        NONE,
        USER_STOPPED,
        /** No RTL-SDR plugged in (or, without the driver helper, none readsb could open). */
        NO_SDR,
        /** An RTL-SDR is plugged in, but Windows has another driver on it; see [driverSetup]. */
        NEEDS_DRIVER,
        /** readsb.exe is missing or couldn't be started. */
        START_FAILED,
        /** readsb stopped on its own; the engine restarts it after a pause (see also [sdrFailing]). */
        READSB_EXITED,
        /** Paused: the laptop is on its battery (Settings > Keep feeding on battery is off). Resumes on mains. */
        ON_BATTERY,
        /** Something unexpected went wrong in the engine; the run was ended and starts again after a pause. */
        ENGINE_ERROR,
    }

    /** Installing WinUSB for the stick (rtlsdr-driver.exe, behind Windows' permission prompt). */
    enum class DriverSetup {
        IDLE,
        RUNNING,
        /** The user said no at the permission prompt. */
        CANCELLED,
        FAILED,
    }
}

/** The stats uploads for the UI (com.adsbexchange.feeder.service.StatsUploader.Stats). */
data class StatsStatus(
    val uploads: Long,
    val bytesSent: Long,
    val lastOkMs: Long?,
    /** Why the latest upload failed; null once one succeeds. */
    val error: String?,
)

/** MLAT client state for the UI, published by the service inside [FeederStatus]. */
data class MlatStatus(
    val readiness: MlatReadiness,
    /** [com.adsbexchange.feeder.mlat.MlatClient.State] name while the client runs. */
    val state: String? = null,
    val server: String? = null,
    val connectedSinceMs: Long? = null,
    val peerCount: Int? = null,
    val outlierPercent: Double? = null,
    val badSyncTimeout: Int? = null,
    val requested: Int = 0,
    val adsbAircraft: Int = 0,
    val modesAircraft: Int = 0,
    val syncsSent: Long = 0,
    val mlatSent: Long = 0,
    val bytesSent: Long = 0,
    /**
     * This readsb session's Beast data read by the MLAT client on loopback, as of readsb's current
     * stats.json. readsb counts it in its bytes_out, so it's subtracted to get what the feed sent.
     */
    val loopbackBytes: Long = 0,
    /** Server message of the day, or why it refused the connection. */
    val message: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("readiness", readiness.name).put("state", state ?: JSONObject.NULL)
        .put("server", server ?: JSONObject.NULL).put("connectedSince", connectedSinceMs ?: JSONObject.NULL)
        .put("peerCount", peerCount ?: JSONObject.NULL).put("outlierPercent", outlierPercent ?: JSONObject.NULL)
        .put("badSyncTimeout", badSyncTimeout ?: JSONObject.NULL)
        .put("requested", requested).put("adsbAircraft", adsbAircraft).put("modesAircraft", modesAircraft)
        .put("syncsSent", syncsSent).put("mlatSent", mlatSent).put("bytesSent", bytesSent)
        .put("loopbackBytes", loopbackBytes).put("message", message ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject?): MlatStatus? = o?.let {
            MlatStatus(
                readiness = runCatching { MlatReadiness.valueOf(it.optString("readiness")) }.getOrDefault(MlatReadiness.OFF),
                state = it.optStringOrNull("state"),
                server = it.optStringOrNull("server"),
                connectedSinceMs = it.optDoubleOrNull("connectedSince")?.toLong(),
                peerCount = it.optDoubleOrNull("peerCount")?.toInt(),
                outlierPercent = it.optDoubleOrNull("outlierPercent"),
                badSyncTimeout = it.optDoubleOrNull("badSyncTimeout")?.toInt(),
                requested = it.optInt("requested"),
                adsbAircraft = it.optInt("adsbAircraft"),
                modesAircraft = it.optInt("modesAircraft"),
                syncsSent = it.optLong("syncsSent"),
                mlatSent = it.optLong("mlatSent"),
                bytesSent = it.optLong("bytesSent"),
                loopbackBytes = it.optLong("loopbackBytes"),
                message = it.optStringOrNull("message"),
            )
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
