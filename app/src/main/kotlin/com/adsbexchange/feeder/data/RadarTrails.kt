package com.adsbexchange.feeder.data

/**
 * Where each aircraft has been over the last few minutes, for the radar's trails. The app adds
 * every aircraft.json it reads (once a second while it's on screen); a point is kept every
 * [everyMs] at most, so a trail is a few dozen points.
 */
class RadarTrails(private val keepMs: Long = KEEP_MS, private val everyMs: Long = EVERY_MS) {
    data class Point(val lat: Double, val lon: Double, val timeMs: Long)

    private val trails = HashMap<String, ArrayDeque<Point>>()

    /** Adds the positions in [aircraft] and returns every trail, oldest point first. */
    fun update(aircraft: List<Aircraft>, nowMs: Long): Map<String, List<Point>> {
        for (a in aircraft) {
            val lat = a.lat ?: continue
            val lon = a.lon ?: continue
            val age = a.seenPosSecondsAgo ?: continue
            if (age > FRESH_S) continue
            val t = nowMs - (age * 1000).toLong()
            val trail = trails.getOrPut(a.hex) { ArrayDeque() }
            val last = trail.lastOrNull()
            if (last == null || (t - last.timeMs >= everyMs && (last.lat != lat || last.lon != lon))) {
                trail.addLast(Point(lat, lon, t))
            }
        }
        val iterator = trails.values.iterator()
        while (iterator.hasNext()) {
            val trail = iterator.next()
            while (trail.isNotEmpty() && nowMs - trail.first().timeMs > keepMs) trail.removeFirst()
            if (trail.isEmpty()) iterator.remove()
        }
        return trails.mapValues { it.value.toList() }
    }

    fun clear() = trails.clear()

    companion object {
        const val KEEP_MS = 3 * 60_000L
        const val EVERY_MS = 5_000L
        /** Positions older than this aren't added (readsb reports the last one for a while). */
        const val FRESH_S = 15.0
    }
}
