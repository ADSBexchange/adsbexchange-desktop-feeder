package com.adsbexchange.feeder.data

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Where the app and readsb keep their files: `%LOCALAPPDATA%\ADS-B Exchange Feeder` (per user,
 * not roaming: the history and readsb's output belong to this PC).
 */
class FeederPaths(val base: File) {
    val root = File(base, "readsb")
    val jsonDir = File(root, "json")
    val logFile = File(root, "readsb.log")
    val aircraftJson = File(jsonDir, "aircraft.json")
    val statsJson = File(jsonDir, "stats.json")
    val receiverJson = File(jsonDir, "receiver.json")
    /** The feeder ID for readsb's --uuid-file, so it's on no command line (readsb.log shows those). */
    val uuidFile = File(root, "feeder-id")
    val settingsFile = File(base, "settings.json")
    /** Written by the feeder engine; see [HistoryStore]. */
    val historyFile = File(base, "history.json")
    val historyAircraftFile = File(base, "history_aircraft.bin")
    /** Held while the app runs, so a second copy doesn't start a second feeder. */
    val lockFile = File(base, "app.lock")

    companion object {
        const val APP_DIR = "ADS-B Exchange Feeder"

        /**
         * FEEDER_HOME overrides the folder, for development runs that mustn't touch real settings.
         * The installed app ignores it: its launcher sets `jpackage.app-path`.
         */
        fun default(
            env: (String) -> String? = System::getenv,
            property: (String) -> String? = System::getProperty,
        ): FeederPaths {
            if (property("jpackage.app-path") == null) {
                env("FEEDER_HOME")?.takeIf { it.isNotBlank() }?.let { return FeederPaths(File(it)) }
            }
            val local = env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: File(property("user.home"), "AppData\\Local").path
            return FeederPaths(File(local, APP_DIR))
        }
    }
}

/** Where the receiver is. On a PC it is always entered by hand (PCs rarely have GPS). */
data class StationLocation(
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val accuracyM: Float?,
    val timeMs: Long,
    val source: Source,
) {
    enum class Source { GPS, NETWORK, MANUAL }

    fun toJson(): JSONObject = JSONObject()
        .put("lat", lat).put("lon", lon)
        .put("alt", altitudeM ?: JSONObject.NULL)
        .put("acc", accuracyM?.toDouble() ?: JSONObject.NULL)
        .put("time", timeMs).put("source", source.name)

    companion object {
        fun fromJson(o: JSONObject?): StationLocation? = o?.let {
            StationLocation(
                lat = it.getDouble("lat"),
                lon = it.getDouble("lon"),
                altitudeM = it.optDoubleOrNull("alt"),
                accuracyM = it.optDoubleOrNull("acc")?.toFloat(),
                timeMs = it.optLong("time"),
                source = runCatching { Source.valueOf(it.optString("source")) }.getOrDefault(Source.MANUAL),
            )
        }

        /** A typed-in position; null if the numbers aren't a valid latitude/longitude. */
        fun manual(lat: Double, lon: Double, altitudeM: Double?): StationLocation? =
            if (lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                StationLocation(lat, lon, altitudeM, null, System.currentTimeMillis(), Source.MANUAL)
            } else null
    }
}

/** User settings, as a JSON file in [FeederPaths.base]. */
data class FeederSettings(
    val uuid: String,
    val name: String,
    val location: StationLocation?,
    val gain: String = "auto",
    /**
     * Power an amplifier (LNA) at the antenna through the cable: readsb's --enable-biastee, about
     * 4.5 V on the antenna socket. Off by default; readsb reads it only when it starts.
     */
    val biasTee: Boolean = false,
    val feedAdsbx: Boolean = true,
    /** Start feeding when the app starts. */
    val autoStart: Boolean = true,
    /** Tray alerts when feeding stops working or the stick is unplugged (see [FeedWatchdog]). */
    val feedAlerts: Boolean = true,
    /** Send ADS-B Exchange the stats behind this feeder's stats page; see [com.adsbexchange.feeder.service.StatsUploader]. */
    val statsUpload: Boolean = true,
    /** Keep feeding while a laptop runs on its battery. Off: the engine pauses until it's plugged in. */
    val feedOnBattery: Boolean = false,
    /** Keep sending stats on a metered connection (1-10 GB a month). Off: they pause, the feed goes on. */
    val statsOnMetered: Boolean = false,
    /** Look for a newer version on GitHub now and then (UpdateChecker). */
    val checkForUpdates: Boolean = true,
    /** The new version the user said "Not now" to: its notice stays hidden until a newer one. */
    val dismissedUpdate: String? = null,
    /** Start the app (in the tray) when the user signs in to Windows; see [com.adsbexchange.feeder.service.Autostart]. */
    val startWithWindows: Boolean = true,
    /** Set when the user clicks Stop, so the next app start doesn't restart what they stopped. */
    val userStopped: Boolean = false,
    val onboardingDone: Boolean = false,
    /** Take part in ADS-B Exchange MLAT. Needed for the feeder name to be used. See [mlatReadiness]. */
    val mlatEnabled: Boolean = true,
    /**
     * mlat-client --privacy: the public MLAT map shows no location for this feeder. Without it, the map
     * shows one rounded to about 1 km. The name is listed either way.
     */
    val mlatPrivacy: Boolean = true,
    /** The user confirmed the position they entered is within about 15 m of the antenna. */
    val antennaConfirmed: Boolean = false,
    /** Antenna height in metres above the WGS84 ellipsoid (roughly: above sea level). */
    val antennaAltitudeM: Double? = null,
    /** The radar's range in nm, or 0 to fit the aircraft heard (see [com.adsbexchange.feeder.ui.RadarMath.autoRange]). */
    val radarRangeNm: Int = 0,
    /** Show nearby airports on the radar ([Airports]). */
    val radarAirports: Boolean = true,
) {
    /**
     * readsb's command line.
     *
     * @param gpsdPort port of the in-app [com.adsbexchange.feeder.service.GpsdServer], the only
     *   way the receiver position reaches readsb.
     * @param beastPort loopback port for readsb's Beast output, which the MLAT client reads.
     * @param feedTo where to send the feed, as "host,port" (see [ADSBX_FEED]), or null not to feed.
     * @param inputFile tests only: read UC8 I/Q samples from this file at real-time speed instead
     *   of the SDR.
     */
    fun readsbArgs(
        paths: FeederPaths, gpsdPort: Int, feedTo: String?, beastPort: Int = DEFAULT_BEAST_PORT, inputFile: File? = null,
    ): List<String> = buildList {
        if (inputFile != null) {
            add("--device-type"); add("ifile")
            add("--ifile"); add(inputFile.absolutePath)
            add("--iformat"); add("UC8")
            add("--throttle")
        } else {
            add("--device-type"); add("rtlsdr")
            add("--gain"); add(gain.ifBlank { "auto" })
            // Powers an amplifier at the antenna through the cable, on sticks whose bias tee is
            // switched in software (the RTL-SDR Blog V3 and V4, say). Off unless asked for: DC on
            // the cable can harm an antenna or splitter that shorts it.
            if (biasTee) add("--enable-biastee")
        }
        // No --lat/--lon: readsb (and our start line) print the whole command line to the log, which
        // the app shows and which would go out with diagnostics. The position arrives over gpsd instead.
        // Without --quiet readsb prints every decoded message to stdout (i.e. our log file).
        add("--quiet")
        add("--db-file"); add("none")
        // Paths relative to readsb's working folder ([FeederPaths.root]; see ReadsbProcess.start):
        // absolute ones put the user's profile folder in the log, and the Windows account name is
        // often a person's name.
        add("--write-json"); add(paths.jsonDir.relativeTo(paths.root).path)
        add("--write-json-every"); add("1")
        add("--json-location-accuracy"); add("2")
        add("--net")
        // Only expose ports on loopback.
        add("--net-bind-address"); add("127.0.0.1")
        add("--net-bo-port"); add("$beastPort")
        add("--net-ro-port"); add("0")
        add("--net-ri-port"); add("0")
        add("--net-bi-port"); add("0")
        add("--net-sbs-port"); add("0")
        add("--net-connector"); add("127.0.0.1,$gpsdPort,gpsd_in")
        if (feedTo != null) {
            // The feeder ID comes from a file the engine writes ([FeederPaths.uuidFile]), not uuid= on
            // the connector: with stats on, the ID opens the feeder's stats page, and this command
            // line goes to the log.
            add("--uuid-file"); add(paths.uuidFile.relativeTo(paths.root).path)
            // primary-host,port,protocol[,failover-host,port]: readsb moves to feed2 while feed1 can't
            // be reached, as ADS-B Exchange's own feeder image does.
            val endpoints = feedEndpoints(feedTo)
            add("--net-connector"); add((listOf(endpoints.first(), "beast_reduce_plus_out") + endpoints.drop(1)).joinToString(","))
            // As ADS-B Exchange's feeder kit sends it (configure.sh: REDUCE_INTERVAL 0.5,
            // --net-ro-interval 0.2): each aircraft at most twice a second, sent in batches every
            // 0.2 s, so ADS-B Exchange gets the same data from this app as from its own feeders.
            // readsb's defaults (0.25 s, flushed within 50 ms) send twice the updates. Both apply to
            // beast-reduce outputs only, i.e. this feed: the MLAT client reads the full Beast output.
            // (As the phone feeder, 4bfe8f5.)
            add("--net-beast-reduce-interval"); add("0.5")
            add("--net-ro-interval-beast-reduce"); add("0.2")
        }
    }

    private fun toJson(): JSONObject = JSONObject()
        .put("uuid", uuid).put("name", name)
        .put("location", location?.toJson() ?: JSONObject.NULL)
        .put("gain", gain).put("biasTee", biasTee).put("feedAdsbx", feedAdsbx)
        .put("autoStart", autoStart).put("startWithWindows", startWithWindows).put("userStopped", userStopped)
        .put("feedAlerts", feedAlerts).put("statsUpload", statsUpload)
        .put("feedOnBattery", feedOnBattery).put("statsOnMetered", statsOnMetered)
        .put("checkForUpdates", checkForUpdates).put("dismissedUpdate", dismissedUpdate ?: JSONObject.NULL)
        .put("onboardingDone", onboardingDone)
        .put("mlatEnabled", mlatEnabled).put("mlatPrivacy", mlatPrivacy)
        .put("antennaConfirmed", antennaConfirmed)
        .put("antennaAltitudeM", antennaAltitudeM ?: JSONObject.NULL)
        .put("radarRangeNm", radarRangeNm)
        .put("radarAirports", radarAirports)

    companion object {
        const val ADSBX_HOST = "feed1.adsbexchange.com"
        /** readsb connector target for the ADS-B Exchange feed. */
        const val ADSBX_FEED = "$ADSBX_HOST,30004"
        /**
         * Where readsb switches to while [ADSBX_FEED] can't be reached, as ADS-B Exchange's own
         * feeder image does (`feed1.adsbexchange.com,30004,beast_reduce_out,feed2.adsbexchange.com,64004`).
         */
        const val ADSBX_FEED_FAILOVER = "feed2.adsbexchange.com,64004"

        /** The feed's "host,port" endpoints: [feedTo], then its failover if it has one. A test sink has none. */
        fun feedEndpoints(feedTo: String): List<String> =
            if (feedTo == ADSBX_FEED) listOf(ADSBX_FEED, ADSBX_FEED_FAILOVER) else listOf(feedTo)
        const val MAX_NAME_LENGTH = 40

        /** The gain settings an RTL-SDR's R820T tuner has, in dB, as readsb's --gain help lists them. */
        val RTL_SDR_GAINS = listOf(
            "0.0", "0.9", "1.4", "2.7", "3.7", "7.7", "8.7", "12.5", "14.4", "15.7", "16.6", "19.7", "20.7", "22.9",
            "25.4", "28.0", "29.7", "32.8", "33.8", "36.4", "37.2", "38.6", "40.2", "42.1", "43.4", "43.9", "44.5",
            "48.0", "49.6",
        )
        /** The usual Beast output port; another one is used if something else on the PC has it. */
        const val DEFAULT_BEAST_PORT = 30005

        /**
         * Ends every feeder UUID this app creates ("ADSB desk", in hex), so ADS-B Exchange can count
         * desktop feeders on the feed and in its stats, e.g. `uuid LIKE '%ad5bde5c'`. The phone app
         * uses ad5bce11. It has to be hex: readsb on ADS-B Exchange's end stops reading a UUID at the
         * first non-hex character. It goes at the end because feeder pages show the first 8
         * characters, which stay random.
         */
        const val DESKTOP_UUID_TAG = "ad5bde5c"

        /** A random (version 4) UUID whose last 8 hex digits are [DESKTOP_UUID_TAG]. */
        fun newFeederUuid(): String = UUID.randomUUID().toString().dropLast(DESKTOP_UUID_TAG.length) + DESKTOP_UUID_TAG

        private val lock = Any()

        private val UUID_FORMAT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /**
         * First run, or a missing or damaged settings.json: start from defaults, but keep the
         * feeder ID if readsb's copy of it survives ([FeederPaths.uuidFile]), so the feeder keeps its
         * identity (and its stats on ADS-B Exchange) rather than appearing as a new one. (As the
         * phone feeder, b55b130.) A damaged file is copied aside first, so nothing is lost for good.
         *
         * A settings.json that is there but can't be read at all, even after about a second of
         * retries (another program holding it), isn't replaced: this returns
         * defaults for now, with the feeder ID from readsb's copy or blank, and [update] doesn't
         * save over it. The next read that works gets everything back.
         */
        fun load(paths: FeederPaths): FeederSettings = synchronized(lock) { loadLocked(paths).settings }

        /** [load]'s result; [writable] is false while settings.json can't be read, so it isn't written over. */
        private class Loaded(val settings: FeederSettings, val writable: Boolean)

        private fun loadLocked(paths: FeederPaths): Loaded {
            val text = try {
                readTextIfExists(paths.settingsFile)
            } catch (e: IOException) {
                appendAppLog(paths, "settings: settings.json can't be read ($e); using defaults for now, and not saving over it")
                val uuid = recoveredUuid(paths) ?: ""
                return Loaded(FeederSettings(uuid = uuid, name = "", location = null), writable = false)
            }
            val s = text?.let(::parse)
            if (s != null && s.uuid.isNotBlank()) return Loaded(s, writable = true)
            if (text != null && s == null) {
                val copy = keepUnreadableCopy(paths.settingsFile)
                appendAppLog(paths, "settings: settings.json couldn't be understood; " +
                    (copy?.let { "kept a copy as ${it.name}" } ?: "couldn't keep a copy") + "; starting from defaults")
            }
            val uuid = recoveredUuid(paths) ?: newFeederUuid()
            val result = s?.copy(uuid = uuid) ?: FeederSettings(uuid = uuid, name = "", location = null)
            write(paths, result)
            return Loaded(result, writable = true)
        }

        private fun recoveredUuid(paths: FeederPaths): String? =
            runCatching { paths.uuidFile.readText().trim() }.getOrNull()?.takeIf(UUID_FORMAT::matches)

        private fun parse(text: String): FeederSettings? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            return FeederSettings(
                uuid = o.optString("uuid"),
                name = o.optString("name"),
                location = StationLocation.fromJson(o.optJSONObject("location")),
                gain = o.optString("gain", "auto").ifBlank { "auto" },
                biasTee = o.optBoolean("biasTee", false),
                feedAdsbx = o.optBoolean("feedAdsbx", true),
                autoStart = o.optBoolean("autoStart", true),
                feedAlerts = o.optBoolean("feedAlerts", true),
                statsUpload = o.optBoolean("statsUpload", true),
                feedOnBattery = o.optBoolean("feedOnBattery", false),
                statsOnMetered = o.optBoolean("statsOnMetered", false),
                checkForUpdates = o.optBoolean("checkForUpdates", true),
                dismissedUpdate = o.optString("dismissedUpdate").takeIf { it.isNotBlank() && !o.isNull("dismissedUpdate") },
                startWithWindows = o.optBoolean("startWithWindows", true),
                userStopped = o.optBoolean("userStopped", false),
                onboardingDone = o.optBoolean("onboardingDone", false),
                mlatEnabled = o.optBoolean("mlatEnabled", true),
                mlatPrivacy = o.optBoolean("mlatPrivacy", true),
                antennaConfirmed = o.optBoolean("antennaConfirmed", false),
                antennaAltitudeM = o.optDoubleOrNull("antennaAltitudeM"),
                radarRangeNm = o.optInt("radarRangeNm", 0),
                radarAirports = o.optBoolean("radarAirports", true),
            )
        }

        /**
         * Read-modify-write, so concurrent writers each apply their own change. Not saved while
         * settings.json can't be read (see [load]), so a change then can't write defaults over it.
         */
        fun update(paths: FeederPaths, change: (FeederSettings) -> FeederSettings): FeederSettings = synchronized(lock) {
            val loaded = loadLocked(paths)
            change(loaded.settings).also {
                if (loaded.writable) write(paths, it)
                else appendAppLog(paths, "settings: change not saved, because settings.json can't be read")
            }
        }

        /** Also keeps readsb's copy of the feeder ID current, which doubles as its backup. */
        private fun write(paths: FeederPaths, s: FeederSettings) {
            paths.base.mkdirs()
            writeAtomically(paths.settingsFile, s.toJson().toString(2))
            if (runCatching { paths.uuidFile.readText().trim() }.getOrNull() != s.uuid) {
                paths.root.mkdirs()
                writeAtomically(paths.uuidFile, s.uuid)
            }
        }

        /**
         * As the ADSBx feeder kits do (configure.sh): letters, digits, spaces, '_' and '-' are kept,
         * anything else becomes '_'. Blank is allowed: the name is optional.
         */
        fun sanitizeName(raw: String): String =
            raw.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").take(MAX_NAME_LENGTH)
    }
}

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null
