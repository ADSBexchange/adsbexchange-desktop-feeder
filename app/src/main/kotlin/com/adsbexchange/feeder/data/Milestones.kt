package com.adsbexchange.feeder.data

/** Milestones on the History tab: round numbers in three series, from [History]. */
object Milestones {
    enum class Kind { MESSAGES, AIRCRAFT, FEEDING }

    data class Milestone(val kind: Kind, val target: Long)

    /** A series' progress: the highest milestone reached, the next one, and how far along it is (0..1). */
    data class Progress(val kind: Kind, val reached: Milestone?, val next: Milestone?, val fraction: Float)

    private const val DAY_MS = 86_400_000L

    private val TARGETS = mapOf(
        Kind.MESSAGES to listOf(10_000L, 100_000L, 1_000_000L, 10_000_000L, 100_000_000L, 1_000_000_000L),
        Kind.AIRCRAFT to listOf(100L, 1_000L, 10_000L, 50_000L, 100_000L),
        Kind.FEEDING to listOf(1L, 7L, 30L, 100L, 365L).map { it * DAY_MS },
    )

    fun value(h: History, kind: Kind): Long = when (kind) {
        Kind.MESSAGES -> h.messages
        Kind.AIRCRAFT -> h.uniqueAircraft.toLong()
        Kind.FEEDING -> h.feedingMs
    }

    /** How many milestones have been reached across all series. */
    fun reachedCount(h: History): Int = TARGETS.entries.sumOf { (kind, ts) -> ts.count { value(h, kind) >= it } }

    fun progress(h: History): List<Progress> = Kind.entries.map { kind ->
        val v = value(h, kind)
        val targets = TARGETS.getValue(kind)
        val reached = targets.lastOrNull { v >= it }
        val next = targets.firstOrNull { v < it }
        val from = reached ?: 0L
        val fraction = if (next == null) 1f else ((v - from).toFloat() / (next - from)).coerceIn(0f, 1f)
        Progress(kind, reached?.let { Milestone(kind, it) }, next?.let { Milestone(kind, it) }, fraction)
    }
}
