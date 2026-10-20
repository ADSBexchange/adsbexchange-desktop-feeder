package com.adsbexchange.feeder.data

/**
 * Spots a crash loop. When readsb stops on its own the engine starts it again, so a failing stick
 * or cable, or another program holding the stick, would otherwise cycle unnoticed while the window
 * only says "Restarting". Too many unplanned exits in a short time and the engine flags it
 * ([FeederStatus.sdrFailing]) and alerts once, but keeps retrying: on a PC most of these fix
 * themselves (the other program closes, the USB recovers), and retrying costs next to nothing.
 *
 * The phone feeder's RestartBudget (b55b130), kept in memory here since the engine runs in one
 * process for the app's whole life. Unlike the phone, which gives up to save its battery, the
 * desktop doesn't.
 *
 * Not counted: exits because the stick was unplugged, which the engine waits out instead.
 */
class RestartBudget(private val maxExits: Int = MAX_EXITS, private val windowMs: Long = WINDOW_MS) {
    private val exits = ArrayDeque<Long>()

    /** Counts an unplanned exit; false means this makes [maxExits] exits within [windowMs]: give up. */
    fun allow(nowMs: Long): Boolean {
        exits.addLast(nowMs)
        while (exits.isNotEmpty() && nowMs - exits.first() >= windowMs) exits.removeFirst()
        return exits.size < maxExits
    }

    /** A deliberate start (Start, or the app starting) begins with a fresh budget. */
    fun reset() = exits.clear()

    companion object {
        const val MAX_EXITS = 5
        const val WINDOW_MS = 15 * 60_000L
    }
}
