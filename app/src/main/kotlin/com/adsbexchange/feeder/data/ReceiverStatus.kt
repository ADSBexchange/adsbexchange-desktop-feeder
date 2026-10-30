package com.adsbexchange.feeder.data

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** A snapshot of what readsb has written to its JSON directory and log. */
data class ReceiverStatus(
    /** readsb rewrote aircraft.json within the last few seconds. */
    val live: Boolean,
    val messagesPerSecond: Double?,
    val aircraft: List<Aircraft>,
    val signalDbfs: Double?,
    val noiseDbfs: Double?,
    val samplesDropped: Long?,
    val maxRangeNm: Double?,
    /** Connection state of readsb's feed connector, parsed from its log. */
    val feedState: FeedState,
    /** This run's totals: messages decoded, bytes readsb sent out, aircraft (tracks) seen. */
    val messagesTotal: Long?,
    val bytesSent: Long?,
    val aircraftSeen: Long?,
    /** stats.json timestamp (s); readsb refreshes it about every 10 s. */
    val statsNow: Double?,
    /** Where readsb currently thinks the receiver is (from --lat/--lon or the gpsd feed). */
    val receiverLat: Double?,
    val receiverLon: Double?,
    /** The last error-looking line readsb logged, for showing why it stopped. */
    val lastError: String?,
    val logTail: List<String>,
) {
    val withPosition get() = aircraft.count { it.lat != null }
}

data class Aircraft(
    val hex: String,
    val callsign: String?,
    /** Feet, or null when unknown. */
    val altitudeFt: Int?,
    val onGround: Boolean,
    val groundSpeedKt: Double?,
    val trackDeg: Double?,
    val lat: Double?,
    val lon: Double?,
    val distanceNm: Double?,
    val rssi: Double?,
    val seenSecondsAgo: Double?,
    /** How old the position is, s. readsb keeps reporting a position for a while after the last one. */
    val seenPosSecondsAgo: Double? = null,
)

enum class FeedState { UNKNOWN, CONNECTED, DISCONNECTED }

/** Polls readsb's output files. Keeps the previous sample to turn message counts into a rate. */
class ReceiverStatusReader(private val paths: FeederPaths) {
    private var lastNow: Double? = null
    private var lastMessages: Long? = null

    /** @param feedTarget the feed connector as "host,port"; its connect/disconnect log lines give [ReceiverStatus.feedState]. */
    fun read(feedTarget: String? = null): ReceiverStatus {
        val aircraftJson = paths.aircraftJson.readJson()
        val statsRoot = paths.statsJson.readJson()
        val stats = statsRoot?.optJSONObject("last1min")
        val total = statsRoot?.optJSONObject("total")
        val local = stats?.optJSONObject("local")
        val receiver = paths.receiverJson.readJson()
        val logLines = readTail(paths.logFile, LOG_TAIL_BYTES)

        val now = aircraftJson?.optDoubleOrNull("now")
        val messages = aircraftJson?.takeIf { it.has("messages") }?.optLong("messages")
        val live = now != null && System.currentTimeMillis() / 1000.0 - now < STALE_AFTER_S

        var rate: Double? = null
        val prevNow = lastNow
        val prevMessages = lastMessages
        if (now != null && messages != null && prevNow != null && prevMessages != null && now > prevNow) {
            rate = (messages - prevMessages).coerceAtLeast(0) / (now - prevNow)
        }
        if (now != null && now != prevNow) {
            lastNow = now
            lastMessages = messages
        }

        val rxLat = receiver?.optDoubleOrNull("lat")
        val rxLon = receiver?.optDoubleOrNull("lon")
        val list = aircraftJson?.optJSONArray("aircraft")
        val aircraft = (0 until (list?.length() ?: 0)).map { parseAircraft(list!!.getJSONObject(it), rxLat, rxLon) }
            .sortedWith(compareBy<Aircraft, Double?>(nullsLast()) { it.distanceNm })

        return ReceiverStatus(
            live = live,
            messagesPerSecond = if (live) rate else null,
            aircraft = if (live) aircraft else emptyList(),
            signalDbfs = local?.optDoubleOrNull("signal"),
            noiseDbfs = local?.optDoubleOrNull("noise"),
            samplesDropped = local?.takeIf { it.has("samples_dropped") }?.optLong("samples_dropped"),
            maxRangeNm = stats?.optDoubleOrNull("max_distance")?.let { it / 1852.0 },
            feedState = feedTarget?.let { feedStateFromLog(logLines, it) } ?: FeedState.UNKNOWN,
            messagesTotal = messages,
            bytesSent = total?.optJSONObject("remote")?.takeIf { it.has("bytes_out") }?.optLong("bytes_out"),
            aircraftSeen = total?.optJSONObject("tracks")?.takeIf { it.has("all") }?.optLong("all"),
            statsNow = statsRoot?.optDoubleOrNull("now"),
            receiverLat = rxLat,
            receiverLon = rxLon,
            lastError = lastErrorFromLog(logLines)?.let(::redactLocation),
            logTail = logLines.takeLast(LOG_TAIL_LINES).map(::redactLocation),
        )
    }

    private fun parseAircraft(o: JSONObject, rxLat: Double?, rxLon: Double?): Aircraft {
        val lat = o.optDoubleOrNull("lat")
        val lon = o.optDoubleOrNull("lon")
        val altRaw = o.opt("alt_baro")
        val distance = if (lat != null && lon != null && rxLat != null && rxLon != null) {
            greatCircleM(rxLat, rxLon, lat, lon) / 1852.0
        } else null
        return Aircraft(
            hex = o.optString("hex"),
            callsign = o.optString("flight").trim().ifBlank { null },
            altitudeFt = (altRaw as? Number)?.toInt(),
            onGround = altRaw == "ground",
            groundSpeedKt = o.optDoubleOrNull("gs"),
            trackDeg = o.optDoubleOrNull("track"),
            lat = lat,
            lon = lon,
            distanceNm = distance,
            rssi = o.optDoubleOrNull("rssi"),
            seenSecondsAgo = o.optDoubleOrNull("seen"),
            seenPosSecondsAgo = o.optDoubleOrNull("seen_pos"),
        )
    }

    /** The last connect/disconnect line readsb logged for the feed connector wins. */
    private fun feedStateFromLog(lines: List<String>, target: String): FeedState {
        val t = FeedTarget(target)
        for (line in lines.asReversed()) t.classify(line)?.let { return it }
        return FeedState.UNKNOWN
    }

    private fun lastErrorFromLog(lines: List<String>): String? =
        lines.lastOrNull { l -> ERROR_MARKERS.any { l.contains(it, ignoreCase = true) } }
            ?.substringAfter("] ")?.trim()

    private fun File.readJson(): JSONObject? = runCatching { JSONObject(readText()) }.getOrNull()

    private companion object {
        const val STALE_AFTER_S = 10.0
        const val LOG_TAIL_LINES = 200
        const val LOG_TAIL_BYTES = 64 * 1024L
        val ERROR_MARKERS = listOf("FATAL", "error", "failed")
    }
}

/**
 * A feed connector ("host,port", plus its failover: [FeederSettings.feedEndpoints]) and how to
 * recognise its connect/disconnect lines in readsb's log. Connected to either server is connected.
 */
private class FeedTarget(target: String) {
    private val endpoints = FeederSettings.feedEndpoints(target).map { it.substringBefore(",") to " port ${it.substringAfter(",")}" }

    fun classify(line: String): FeedState? {
        if (endpoints.none { (host, port) -> line.contains(host) && line.contains(port) }) return null
        if (line.contains("Connection established")) return FeedState.CONNECTED
        if (line.contains("disconnected") || line.contains("failed") || line.contains("refused") ||
            line.contains("timed out")) return FeedState.DISCONNECTED
        return null
    }
}

/**
 * Follows readsb.log for the feed connector's state, reading only the bytes appended since the
 * last call (the engine polls every 2 s; re-reading the whole log each time would be wasted work).
 *
 * @param startAt where to start reading: the log's length when this readsb run started, so lines
 *   from earlier runs (the log is kept across runs) don't count.
 */
class FeedLogWatcher(private val file: File, target: String, startAt: Long = 0L) {
    private val feed = FeedTarget(target)
    private var offset = startAt
    var state = FeedState.UNKNOWN; private set

    fun poll(): FeedState {
        val len = file.length()
        if (len < offset) offset = 0 // the log was truncated (rotation at 512 kB)
        if (len > offset) runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val bytes = ByteArray((len - offset).coerceAtMost(1L shl 20).toInt())
                raf.readFully(bytes)
                // Only whole lines; a partial last line is read again next time.
                val end = bytes.lastIndexOf('\n'.code.toByte())
                if (end >= 0) {
                    offset += end + 1
                    String(bytes, 0, end, Charsets.UTF_8).lineSequence().forEach { l -> feed.classify(l)?.let { state = it } }
                }
            }
        }
        return state
    }
}

/**
 * Replaces coordinates in a log line with "[hidden]". The log is shown on screen and goes out with
 * diagnostics, and the receiver position is usually someone's home. The app doesn't put
 * coordinates in the log; this catches readsb messages that print them ("lat: …", "location: …").
 */
internal fun redactLocation(line: String): String =
    LOCATION_REGEXES.fold(line) { l, re -> re.replace(l, "$1[hidden]") }

private val LOCATION_REGEXES = listOf(
    // A pair after a label: "lat,lon: 43.1,-76.2", "location: 43.12345, -76.54321"
    Regex("""(?i)(\b(?:lat\s*,\s*lon|lat\s*/\s*lon|location|position|pos)\b["']?\s*[:=]?\s*\(?)-?\d{1,3}\.\d+\s*,\s*-?\d{1,3}\.\d+"""),
    // A single labelled value: "--lat 43.1", "lat: 43.1", "\"lon\":-76.2", "latitude=43.1"
    Regex("""(?i)((?:--)?\b(?:lat|lon|latitude|longitude)\b["']?\s*[:=]?\s*)-?\d{1,3}\.\d+"""),
)

/** The last [maxBytes] of [file] as lines (the first, partial line dropped). */
internal fun readTail(file: File, maxBytes: Long): List<String> = runCatching {
    RandomAccessFile(file, "r").use { raf ->
        val len = raf.length()
        val start = (len - maxBytes).coerceAtLeast(0)
        raf.seek(start)
        val bytes = ByteArray((len - start).toInt())
        raf.readFully(bytes)
        val lines = String(bytes, Charsets.UTF_8).split('\n')
        (if (start > 0) lines.drop(1) else lines).dropLastWhile { it.isEmpty() }
    }
}.getOrDefault(emptyList())

/** Haversine distance in metres on a sphere of the mean Earth radius (within 0.5% of the ellipsoid). */
internal fun greatCircleM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
    return 2 * 6_371_008.8 * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
}

/** For the tray: from stats.json, which readsb refreshes about every 10 s. */
data class QuickStats(val messagesPerSecond: Double?, val aircraft: Int?)

fun readQuickStats(paths: FeederPaths): QuickStats = runCatching {
    val o = JSONObject(paths.statsJson.readText())
    val perMinute = o.optJSONObject("last1min")?.optDoubleOrNull("messages_valid")
    val withPos = o.optDoubleOrNull("aircraft_with_pos")
    val withoutPos = o.optDoubleOrNull("aircraft_without_pos")
    QuickStats(
        messagesPerSecond = perMinute?.div(60.0),
        aircraft = if (withPos != null && withoutPos != null) (withPos + withoutPos).toInt() else null,
    )
}.getOrDefault(QuickStats(null, null))
