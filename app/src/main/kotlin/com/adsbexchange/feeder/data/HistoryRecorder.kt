package com.adsbexchange.feeder.data

import java.util.TimeZone

/**
 * Adds up what this PC contributes to ADS-B Exchange, for [History]. Driven by the feeder
 * service's tick; counts only while connected to the feed ([contributing]).
 *
 * readsb's counters (stats.json "total") and the MLAT client's run from zero for each readsb
 * session or client, so this keeps the last value seen and adds the difference. A counter that
 * goes down means a new session or client.
 */
class HistoryRecorder(
    loaded: HistoryStore.Loaded,
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    private var firstFedMs = loaded.history.firstFedMs
    private var messages = loaded.history.messages
    private var bytesSent = loaded.history.bytesSent
    private var feedingMs = loaded.history.feedingMs
    private var mlatSyncs = loaded.history.mlatSyncs
    private var mlatMessages = loaded.history.mlatMessages
    private var longestRunMs = loaded.history.longestRunMs
    private var furthestNm = loaded.history.furthestNm
    private var furthestMs = loaded.history.furthestMs
    private val days = loaded.history.days.associateByTo(sortedMapOf()) { it.day }
    private val rangeNm = loaded.history.rangeNm.toDoubleArray()
    private var rangeSinceMs = loaded.history.rangeSinceMs

    private val aircraft = loaded.aircraft
    private val newAircraft = ArrayList<Int>()
    private var todayDay = loaded.todayDay
    private val todayAircraft = loaded.todayAircraft

    /** Whether there is anything new to save. */
    var dirty = false
        private set

    // Per readsb session and per MLAT client: not saved, since both start from zero again.
    private var statsSession: Long? = null
    private var lastMessages = 0L
    private var lastFeedBytes = 0L
    private var mlatClient: Any? = null
    private var lastSyncs = 0L
    private var lastMlat = 0L
    private var lastMlatBytes = 0L
    private var lastMlatInput = 0L
    private var lastFeedTickMs: Long? = null

    /**
     * Beast data the MLAT client read from readsb on loopback this session. readsb counts it in
     * its bytes_out, but it never leaves the PC, so it's subtracted from the feed's bytes.
     */
    var loopbackBytes = 0L
        private set

    /** Every tick. [connectedSinceMs] is when the current feed connection came up. */
    fun onFeedTick(contributing: Boolean, connectedSinceMs: Long?, nowMs: Long) {
        if (!contributing) {
            lastFeedTickMs = null
            return
        }
        if (firstFedMs == null) firstFedMs = nowMs
        lastFeedTickMs?.let { last ->
            // Capped, so a gap (e.g. the PC asleep) isn't counted as feeding.
            val dt = (nowMs - last).coerceIn(0, MAX_TICK_GAP_MS)
            feedingMs += dt
            day(nowMs) { it.copy(feedingMs = it.feedingMs + dt) }
        }
        lastFeedTickMs = nowMs
        connectedSinceMs?.let { longestRunMs = maxOf(longestRunMs, nowMs - it) }
        dirty = true
    }

    /**
     * From readsb's stats.json: the session's start time and its running totals. [loopbackAtStats]
     * is [loopbackBytes] as of when readsb wrote that stats.json (it does so every 10 s), so the two
     * counters describe the same moment.
     */
    fun onStats(
        session: Long, messagesTotal: Long, bytesOut: Long, maxDistanceM: Double?, loopbackAtStats: Long,
        contributing: Boolean, nowMs: Long,
    ) {
        if (session != statsSession) {
            statsSession = session
            lastMessages = 0
            lastFeedBytes = 0
        }
        val dMessages = (messagesTotal - lastMessages).coerceAtLeast(0)
        lastMessages = maxOf(lastMessages, messagesTotal)
        // Within a tick of each other, not exactly the same moment: never go back.
        val feedBytes = (bytesOut - loopbackAtStats).coerceAtLeast(0)
        val dBytes = (feedBytes - lastFeedBytes).coerceAtLeast(0)
        lastFeedBytes = maxOf(lastFeedBytes, feedBytes)
        if (!contributing) return
        messages += dMessages
        bytesSent += dBytes
        day(nowMs) { it.copy(messages = it.messages + dMessages, bytesSent = it.bytesSent + dBytes) }
        maxDistanceM?.takeIf { it > 0 }?.let { furthest(it / 1852.0, nowMs) }
        dirty = true
    }

    /** From the MLAT client's counters; [client] identifies the client instance. */
    fun onMlat(client: Any, syncs: Long, mlat: Long, bytes: Long, inputBytes: Long, contributing: Boolean, nowMs: Long) {
        if (client !== mlatClient) {
            mlatClient = client
            lastSyncs = 0; lastMlat = 0; lastMlatBytes = 0; lastMlatInput = 0
        }
        fun delta(now: Long, last: Long) = (now - last).coerceAtLeast(0)
        val dSyncs = delta(syncs, lastSyncs)
        val dMlat = delta(mlat, lastMlat)
        val dBytes = delta(bytes, lastMlatBytes)
        loopbackBytes += delta(inputBytes, lastMlatInput)
        lastSyncs = maxOf(lastSyncs, syncs); lastMlat = maxOf(lastMlat, mlat)
        lastMlatBytes = maxOf(lastMlatBytes, bytes); lastMlatInput = maxOf(lastMlatInput, inputBytes)
        if (!contributing) return
        mlatSyncs += dSyncs
        mlatMessages += dMlat
        bytesSent += dBytes
        day(nowMs) { it.copy(bytesSent = it.bytesSent + dBytes) }
        dirty = true
    }

    /**
     * From a snapshot of readsb's aircraft.json: the addresses heard and the recent positions.
     * [recordRange] is false on the phone in mobile mode, where a range plot around a moving receiver
     * means nothing; the desktop always records it.
     */
    fun onAircraft(
        addresses: Collection<Int>, positions: Collection<HistorySamples.Position>, recordRange: Boolean,
        contributing: Boolean, nowMs: Long,
    ) {
        if (!contributing) return
        val today = epochDay(nowMs)
        if (todayDay != today) {
            todayDay = today
            todayAircraft.clear()
        }
        var newToday = 0
        for (a in addresses) {
            if (aircraft.add(a)) newAircraft += a
            if (todayAircraft.add(a)) newToday++
        }
        if (newToday > 0) day(nowMs) { it.copy(aircraft = it.aircraft + newToday) }
        for (p in positions) {
            if (p.nm > MAX_PLAUSIBLE_NM || p.nm <= 0) continue
            furthest(p.nm, nowMs)
            if (!recordRange) continue
            val sector = Math.floorMod((p.bearingDeg / SECTOR_DEG).toInt(), History.RANGE_BEARINGS)
            if (p.nm > rangeNm[sector]) {
                rangeNm[sector] = p.nm
                if (rangeSinceMs == null) rangeSinceMs = nowMs
            }
        }
        dirty = true
    }

    /** Starts the range plot again, e.g. after the antenna has moved. */
    fun resetRange(nowMs: Long) {
        rangeNm.fill(0.0)
        rangeSinceMs = nowMs
        dirty = true
    }

    /** When the range plot was last started (see [resetRange]), or null if it never has been. */
    val rangeStartedMs: Long? get() = rangeSinceMs

    /** The current totals; [days] trimmed to [History.MAX_DAYS]. */
    fun snapshot(nowMs: Long): History {
        while (days.size > History.MAX_DAYS) days.remove(days.firstKey())
        return History(
            firstFedMs = firstFedMs, messages = messages, bytesSent = bytesSent, feedingMs = feedingMs,
            uniqueAircraft = aircraft.size, mlatSyncs = mlatSyncs, mlatMessages = mlatMessages,
            longestRunMs = longestRunMs, furthestNm = furthestNm, furthestMs = furthestMs,
            days = days.values.toList(), rangeNm = rangeNm.toList(), rangeSinceMs = rangeSinceMs, updatedMs = nowMs,
        )
    }

    /**
     * Writes everything new to [store]: the new aircraft first, then the totals that count them.
     * Once the aircraft are on disk they're forgotten here, so a failed history.json write doesn't
     * append them a second time at the next save.
     */
    fun save(store: HistoryStore, nowMs: Long) {
        if (!dirty) return
        store.appendAircraft(newAircraft)
        newAircraft.clear()
        store.writeHistory(snapshot(nowMs), todayDay, todayAircraft)
        dirty = false
    }

    fun epochDay(ms: Long): Int = epochDay(ms, zone)

    private fun day(nowMs: Long, change: (DayStats) -> DayStats) {
        val d = epochDay(nowMs)
        days[d] = change(days[d] ?: DayStats(d))
    }

    private fun furthest(nm: Double, nowMs: Long) {
        // Beyond 400 nm is a decoding error, not reception.
        if (nm > MAX_PLAUSIBLE_NM) return
        if (nm > (furthestNm ?: 0.0)) {
            furthestNm = nm
            furthestMs = nowMs
        }
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val MAX_TICK_GAP_MS = 60_000L
        const val MAX_PLAUSIBLE_NM = 400.0
        private const val SECTOR_DEG = 360.0 / History.RANGE_BEARINGS

        /** Days since 1970-01-01 in [zone]: the key of [DayStats]. */
        fun epochDay(ms: Long, zone: TimeZone = TimeZone.getDefault()): Int =
            Math.floorDiv(ms + zone.getOffset(ms), DAY_MS).toInt()
    }
}
