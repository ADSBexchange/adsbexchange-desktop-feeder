package com.adsbexchange.feeder.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** One local calendar day of contribution. [day] is the local epoch day (days since 1970-01-01). */
data class DayStats(
    val day: Int,
    val messages: Long = 0,
    val bytesSent: Long = 0,
    val feedingMs: Long = 0,
    /** Different aircraft heard that day. */
    val aircraft: Int = 0,
)

/**
 * Lifetime contribution to ADS-B Exchange, counted on this PC by [HistoryRecorder] in the
 * feeder service and shown on the History tab. Only time spent connected to the feed counts.
 */
data class History(
    val firstFedMs: Long? = null,
    /** Messages readsb decoded while feeding. */
    val messages: Long = 0,
    /** Feed plus MLAT data sent to ADS-B Exchange. */
    val bytesSent: Long = 0,
    val feedingMs: Long = 0,
    val uniqueAircraft: Int = 0,
    val mlatSyncs: Long = 0,
    val mlatMessages: Long = 0,
    val longestRunMs: Long = 0,
    val furthestNm: Double? = null,
    val furthestMs: Long? = null,
    /** Oldest first, at most [MAX_DAYS]. */
    val days: List<DayStats> = emptyList(),
    /**
     * Furthest position heard in each direction, nm: [RANGE_BEARINGS] sectors clockwise from north,
     * 0 where nothing has been heard. Recorded since [rangeSinceMs] (the first position, or a reset).
     */
    val rangeNm: List<Double> = List(RANGE_BEARINGS) { 0.0 },
    val rangeSinceMs: Long? = null,
    val updatedMs: Long = 0,
) {
    val busiestDay: DayStats? get() = days.filter { it.messages > 0 }.maxByOrNull { it.messages }

    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("firstFedMs", firstFedMs ?: JSONObject.NULL)
        .put("messages", messages).put("bytesSent", bytesSent).put("feedingMs", feedingMs)
        .put("uniqueAircraft", uniqueAircraft)
        .put("mlatSyncs", mlatSyncs).put("mlatMessages", mlatMessages)
        .put("longestRunMs", longestRunMs)
        .put("furthestNm", furthestNm ?: JSONObject.NULL).put("furthestMs", furthestMs ?: JSONObject.NULL)
        .put("updatedMs", updatedMs)
        .put("rangeNm", JSONArray(rangeNm)).put("rangeSinceMs", rangeSinceMs ?: JSONObject.NULL)
        .put("days", JSONArray().apply {
            days.forEach { put(JSONArray(listOf(it.day, it.messages, it.bytesSent, it.feedingMs, it.aircraft))) }
        })

    companion object {
        const val MAX_DAYS = 400
        const val RANGE_BEARINGS = 36

        fun fromJson(o: JSONObject): History = History(
            firstFedMs = o.optDoubleOrNull("firstFedMs")?.toLong(),
            messages = o.optLong("messages"), bytesSent = o.optLong("bytesSent"), feedingMs = o.optLong("feedingMs"),
            uniqueAircraft = o.optInt("uniqueAircraft"),
            mlatSyncs = o.optLong("mlatSyncs"), mlatMessages = o.optLong("mlatMessages"),
            longestRunMs = o.optLong("longestRunMs"),
            furthestNm = o.optDoubleOrNull("furthestNm"), furthestMs = o.optDoubleOrNull("furthestMs")?.toLong(),
            updatedMs = o.optLong("updatedMs"),
            rangeNm = o.optJSONArray("rangeNm")?.takeIf { it.length() == RANGE_BEARINGS }
                ?.let { a -> List(RANGE_BEARINGS) { a.optDouble(it, 0.0) } } ?: List(RANGE_BEARINGS) { 0.0 },
            rangeSinceMs = o.optDoubleOrNull("rangeSinceMs")?.toLong(),
            days = o.optJSONArray("days")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONArray(i)?.let { d ->
                        DayStats(d.optInt(0), d.optLong(1), d.optLong(2), d.optLong(3), d.optInt(4))
                    }
                }
            }.orEmpty(),
        )

        /** What the History tab shows; null before this PC has ever fed. */
        fun read(paths: FeederPaths): History? =
            runCatching { fromJson(JSONObject(paths.historyFile.readText())) }.getOrNull()
    }
}

/** What [HistoryRecorder] samples from readsb's JSON files. */
object HistorySamples {
    /** stats.json "total": [session] is readsb's start time, which identifies the session. */
    class Stats(val session: Long, val messages: Long, val bytesOut: Long, val maxDistanceM: Double?)

    /** A position heard in the last minute: distance and bearing from the receiver. */
    class Position(val nm: Double, val bearingDeg: Double)

    class Aircraft(val addresses: List<Int>, val positions: List<Position>) {
        val furthestNm: Double? get() = positions.maxOfOrNull { it.nm }
    }

    fun stats(paths: FeederPaths): Stats? = runCatching {
        parseStats(JSONObject(paths.statsJson.readText()))
    }.getOrNull()

    fun aircraft(paths: FeederPaths): Aircraft? = runCatching {
        parseAircraft(JSONObject(paths.aircraftJson.readText()))
    }.getOrNull()

    internal fun parseStats(o: JSONObject): Stats? {
        val total = o.optJSONObject("total") ?: return null
        return Stats(
            session = total.optDouble("start").takeIf { it.isFinite() }?.toLong() ?: return null,
            messages = total.optLong("messages_valid"),
            bytesOut = total.optJSONObject("remote")?.optLong("bytes_out") ?: 0,
            maxDistanceM = total.optDoubleOrNull("max_distance"),
        )
    }

    /**
     * ICAO addresses only: readsb marks others (TIS-B track numbers, MLAT-only) with a leading "~".
     * Positions count only if seen in the last minute.
     */
    internal fun parseAircraft(o: JSONObject): Aircraft {
        val list = o.optJSONArray("aircraft") ?: JSONArray()
        val addresses = ArrayList<Int>(list.length())
        val positions = ArrayList<Position>()
        for (i in 0 until list.length()) {
            val a = list.optJSONObject(i) ?: continue
            val hex = a.optString("hex")
            if (hex.length != 6 || hex.startsWith("~")) continue
            addresses += hex.toIntOrNull(16) ?: continue
            val dst = a.optDoubleOrNull("r_dst")
            val dir = a.optDoubleOrNull("r_dir")
            val seenPos = a.optDoubleOrNull("seen_pos")
            if (dst != null && dir != null && seenPos != null && seenPos <= 60) positions += Position(dst, dir)
        }
        return Aircraft(addresses, positions)
    }
}

/**
 * History on disk, written by the feeder service only:
 * - `history.json`: the totals, records and daily figures, plus today's aircraft (so the day's
 *   count survives a restart). Rewritten in full; it stays small.
 * - `history_aircraft.bin`: every aircraft address ever heard, 4 bytes each, append-only. A busy
 *   site can hear tens of thousands of aircraft a year, so this is never rewritten.
 */
class HistoryStore(private val paths: FeederPaths, private val log: (String) -> Unit = {}) {
    class Loaded(val history: History, val aircraft: MutableSet<Int>, val todayDay: Int?, val todayAircraft: MutableSet<Int>)

    /**
     * The saved history; a fresh one if there's none. Throws an IOException if the files are there
     * but can't be read, even after about a second of retries (another program holding them): the
     * caller then leaves them alone rather than saving zeros over a lifetime's totals.
     * A history.json that can't be understood is copied aside, and history starts afresh.
     */
    fun load(): Loaded {
        val text = readTextIfExists(paths.historyFile)
        val json = text?.let { t ->
            runCatching { JSONObject(t) }.getOrNull() ?: run {
                val copy = keepUnreadableCopy(paths.historyFile)
                log("history: history.json couldn't be understood; " +
                    (copy?.let { "kept a copy as ${it.name}" } ?: "couldn't keep a copy") + "; starting afresh")
                null
            }
        }
        val history = json?.let { History.fromJson(it) } ?: History()
        val today = json?.optJSONObject("today")
        val todayAircraft = HashSet<Int>()
        today?.optJSONArray("aircraft")?.let { a -> for (i in 0 until a.length()) todayAircraft += a.optInt(i) }
        return Loaded(history, loadAircraft(), today?.optInt("day"), todayAircraft)
    }

    /**
     * history_aircraft.bin: 4 bytes per aircraft. A power cut in the middle of an append can leave
     * part of a record at the end, which would shift every record appended after it,
     * so it's cut off here, before anything more is appended.
     */
    private fun loadAircraft(): MutableSet<Int> {
        val file = paths.historyAircraftFile
        val bytes = readIfExists(file) { it.readBytes() } ?: return HashSet()
        val whole = bytes.size / 4 * 4
        if (whole != bytes.size) {
            RandomAccessFile(file, "rw").use { it.setLength(whole.toLong()) }
            log("history: dropped ${bytes.size - whole} bytes of a partial record at the end of ${file.name}")
        }
        val aircraft = HashSet<Int>(whole / 4 * 2)
        DataInputStream(bytes.inputStream(0, whole)).use { input -> repeat(whole / 4) { aircraft += input.readInt() } }
        return aircraft
    }

    /** Appends [aircraft] to history_aircraft.bin and waits until they're on disk. */
    fun appendAircraft(aircraft: Collection<Int>) {
        if (aircraft.isEmpty()) return
        FileOutputStream(paths.historyAircraftFile, true).use { file ->
            val out = DataOutputStream(file.buffered())
            aircraft.forEach(out::writeInt)
            out.flush()
            file.fd.sync()
        }
    }

    fun writeHistory(history: History, todayDay: Int?, todayAircraft: Collection<Int>) {
        val json = history.toJson()
        if (todayDay != null) json.put("today", JSONObject().put("day", todayDay).put("aircraft", JSONArray(todayAircraft.toList())))
        writeAtomically(paths.historyFile, json.toString())
    }

    /** Appends [newAircraft] first, so the totals in history.json never count aircraft that aren't on disk. */
    fun save(history: History, todayDay: Int?, todayAircraft: Collection<Int>, newAircraft: Collection<Int>) {
        appendAircraft(newAircraft)
        writeHistory(history, todayDay, todayAircraft)
    }
}
