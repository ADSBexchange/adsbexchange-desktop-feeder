package com.adsbexchange.feeder.mlat

import kotlin.random.Random

/**
 * Tracks aircraft and decides what to send to the MLAT server: a Kotlin port of mlat-client's
 * coordinator.py. The rules (message thresholds, timings, which DFs are sent when) are kept as
 * upstream; the server relies on them.
 */
internal class MlatCoordinator(
    private val server: Server,
    private val receiver: BeastReader,
    private val frequency: Long,
) {
    /** What the coordinator sends. Implemented by the server connection. */
    interface Server {
        fun sendMlat(m: ModeSMessage)
        fun sendSync(even: ModeSMessage, odd: ModeSMessage)
        /** Non-null when the server asked for split sync instead of pairs. */
        val splitSync: Boolean
        fun sendSplitSync(m: ModeSMessage)
        fun sendSeen(addresses: Collection<Int>)
        fun sendLost(addresses: Collection<Int>)
        fun sendRateReport(report: Map<Int, Double>)
        fun sendInputConnected()
        fun sendInputDisconnected()
        fun sendClockJump()
        fun sendClockReset(reason: String, frequency: Long?, mode: String?)
    }

    private class Aircraft(val icao: Int) {
        var messages = 0
        var lastMessageTime = 0.0
        var lastEvenTime = 0.0
        var lastOddTime = 0.0
        var adsbGood = false
        var evenMessage: ModeSMessage? = null
        var oddMessage: ModeSMessage? = null
        var requested = true
        var rateMeasurementStart = 0.0
        var recentAdsbPositions = 0
    }

    private val aircraft = HashMap<Int, Aircraft>()
    private val requestedTraffic = HashSet<Int>()
    private var reported = HashSet<Int>()
    private var nextReport: Double? = null
    private var nextAircraftUpdate = 0.0
    private var lastAircraftUpdate = 0.0

    // Counters for the UI.
    var syncsSent = 0L; private set
    var mlatSent = 0L; private set
    val requestedCount get() = requestedTraffic.size
    fun trackedCounts(): Pair<Int, Int> {
        var adsb = 0
        var modes = 0
        for (ac in aircraft.values) {
            if (ac.messages < 2) continue
            if (ac.adsbGood) adsb++ else modes++
        }
        return adsb to modes
    }

    fun heartbeat(now: Double) {
        if (now >= nextAircraftUpdate) {
            nextAircraftUpdate = now + UPDATE_INTERVAL + Random.nextDouble()
            updateAircraft(now)
            val nr = nextReport
            if (nr != null) {
                // Reporting piggybacks on updates, as upstream.
                nextReport = nr + 1.0
                if (nextReport!! >= REPORT_INTERVAL) {
                    nextReport = 0.0
                    sendAircraftReport()
                    sendRateReport(now)
                }
            }
        }
    }

    private fun updateAircraft(now: Double) {
        for (icao in receiver.takeSeen()) {
            val ac = aircraft.getOrPut(icao) {
                Aircraft(icao).apply {
                    requested = icao in requestedTraffic
                    rateMeasurementStart = now
                }
            }
            if (ac.lastMessageTime <= lastAircraftUpdate) {
                // Seen by the receiver but every message was filtered: count it anyway.
                ac.messages++
                ac.lastMessageTime = now
            }
            ac.adsbGood = now - ac.lastEvenTime < POSITION_EXPIRY && now - ac.lastOddTime < POSITION_EXPIRY
        }
        aircraft.values.removeAll { now - it.lastMessageTime > EXPIRY_AGE }
        lastAircraftUpdate = now
    }

    private fun sendAircraftReport() {
        val all = aircraft.values.filter { it.messages > 1 }.mapTo(HashSet()) { it.icao }
        val seen = all - reported
        val lost = reported - all
        if (seen.isNotEmpty()) server.sendSeen(seen)
        if (lost.isNotEmpty()) server.sendLost(lost)
        reported = all
    }

    private fun sendRateReport(now: Double) {
        val report = HashMap<Int, Double>()
        for (ac in aircraft.values) {
            val interval = now - ac.rateMeasurementStart
            if (interval > 0 && ac.recentAdsbPositions > 0) {
                report[ac.icao] = ac.recentAdsbPositions / interval
                ac.rateMeasurementStart = now
                ac.recentAdsbPositions = 0
            }
        }
        if (report.isNotEmpty()) server.sendRateReport(report)
    }

    // --- server events ---------------------------------------------------------------------

    fun serverConnected() {
        requestedTraffic.clear()
        aircraft.clear()
        reported = HashSet()
        nextReport = Random.nextDouble() * REPORT_INTERVAL
    }

    fun serverDisconnected() {
        nextReport = null
    }

    fun serverStartSending(icaos: Collection<Int>) {
        for (icao in icaos) aircraft[icao]?.requested = true
        requestedTraffic += icaos
        updateReceiverFilter()
    }

    fun serverStopSending(icaos: Collection<Int>) {
        for (icao in icaos) aircraft[icao]?.requested = false
        requestedTraffic -= icaos.toSet()
        updateReceiverFilter()
    }

    private fun updateReceiverFilter() {
        receiver.interestedMlat.clear()
        for (icao in requestedTraffic) {
            val ac = aircraft[icao]
            // Requested, and no recent ADS-B position from it: we want its Mode S replies.
            if (ac == null || !ac.adsbGood) receiver.interestedMlat += icao
        }
    }

    // --- receiver events -------------------------------------------------------------------

    fun inputConnected() {
        server.sendInputConnected()
        // Upstream synthesises a decoder mode-change event on connect, which becomes a clock reset.
        server.sendClockReset("Decoder mode changed to BEAST", frequency, "BEAST")
    }

    fun inputDisconnected() {
        server.sendInputDisconnected()
        aircraft.clear()
        server.sendLost(reported)
        reported.clear()
    }

    fun timestampJump() = server.sendClockJump()

    fun received(m: ModeSMessage, now: Double) {
        when (m.df) {
            0, 4, 5, 16, 20, 21 -> receivedMisc(m, now)
            11 -> receivedDf11(m, now)
            17 -> receivedDf17(m, now)
        }
    }

    private fun receivedMisc(m: ModeSMessage, now: Double) {
        val ac = aircraft[m.address] ?: return // not a known ICAO
        ac.messages++
        ac.lastMessageTime = now
        if (ac.messages < 10 || !ac.requested || ac.adsbGood) return
        server.sendMlat(m); mlatSent++
    }

    private fun receivedDf11(m: ModeSMessage, now: Double) {
        val ac = aircraft[m.address]
        if (ac == null) {
            aircraft[m.address] = newAircraft(m.address, now)
            return
        }
        ac.messages++
        ac.lastMessageTime = now
        if (ac.messages < 10 || !ac.requested || ac.adsbGood) return
        server.sendMlat(m); mlatSent++
    }

    private fun receivedDf17(m: ModeSMessage, now: Double) {
        val ac = aircraft[m.address]
        if (ac == null) {
            aircraft[m.address] = newAircraft(m.address, now)
            return
        }
        ac.messages++
        ac.lastMessageTime = now
        if (ac.messages < 10) return
        if (!m.evenCpr && !m.oddCpr) return // not a position
        if (!m.valid) return
        if (m.evenCpr) ac.evenMessage = m else ac.oddMessage = m
        val even = ac.evenMessage ?: return
        val odd = ac.oddMessage ?: return
        if (kotlin.math.abs(even.timestamp - odd.timestamp) > 5 * frequency) return
        if (m.altitude == null) return
        if (m.nuc < 6) return

        ac.recentAdsbPositions++
        if (m.evenCpr) ac.lastEvenTime = now else ac.lastOddTime = now
        ac.adsbGood = now - ac.lastEvenTime < POSITION_EXPIRY && now - ac.lastOddTime < POSITION_EXPIRY
        if (!ac.requested) return

        if (server.splitSync) server.sendSplitSync(m) else server.sendSync(even, odd)
        syncsSent++
    }

    private fun newAircraft(icao: Int, now: Double) = Aircraft(icao).apply {
        requested = icao in requestedTraffic
        messages = 1
        lastMessageTime = now
        rateMeasurementStart = now
    }

    private companion object {
        const val UPDATE_INTERVAL = 4.5
        const val REPORT_INTERVAL = 4.0 // in multiples of UPDATE_INTERVAL
        const val POSITION_EXPIRY = 30.0
        const val EXPIRY_AGE = 120.0
    }
}
