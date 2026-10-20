package com.adsbexchange.feeder.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.TimeZone

class HistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val utc = TimeZone.getTimeZone("UTC")
    private val day0 = 20_000 * HistoryRecorder.DAY_MS // some UTC midnight
    private fun fresh() = HistoryRecorder(HistoryStore.Loaded(History(), HashSet(), null, HashSet()), utc)
    private fun pos(nm: Double, bearing: Double) = HistorySamples.Position(nm, bearing)

    @Test
    fun addsReadsbTotalsAcrossReadingsAndSessions() {
        val r = fresh()
        r.onStats(session = 1, messagesTotal = 100, bytesOut = 1_000, maxDistanceM = null, loopbackAtStats = 0, contributing = true, nowMs = day0)
        r.onStats(session = 1, messagesTotal = 250, bytesOut = 2_500, maxDistanceM = null, loopbackAtStats = 0, contributing = true, nowMs = day0)
        // readsb restarted: its totals start from zero again.
        r.onStats(session = 2, messagesTotal = 40, bytesOut = 400, maxDistanceM = null, loopbackAtStats = 0, contributing = true, nowMs = day0)
        val h = r.snapshot(day0)
        assertEquals(290, h.messages)
        assertEquals(2_900, h.bytesSent)
    }

    @Test
    fun countsNothingWhileNotConnected() {
        val r = fresh()
        r.onStats(1, 100, 1_000, null, 0, contributing = false, nowMs = day0)
        r.onStats(1, 150, 1_500, null, 0, contributing = true, nowMs = day0)
        assertEquals("only what came after reconnecting", 50, r.snapshot(day0).messages)
    }

    @Test
    fun takesOutTheMlatClientsLoopbackData() {
        val r = fresh()
        val client = Any()
        r.onMlat(client, syncs = 10, mlat = 2, bytes = 300, inputBytes = 1_000, contributing = true, nowMs = day0)
        r.onStats(1, 0, bytesOut = 3_000, maxDistanceM = null, loopbackAtStats = r.loopbackBytes, contributing = true, nowMs = day0)
        val h = r.snapshot(day0)
        assertEquals("feed 3000 - 1000 loopback, plus 300 MLAT", 2_300, h.bytesSent)
        assertEquals(10, h.mlatSyncs)
        assertEquals(1_000, r.loopbackBytes)
    }

    @Test
    fun aNewMlatClientStartsItsCountersAgain() {
        val r = fresh()
        r.onMlat(Any(), 10, 1, 100, 0, true, day0)
        r.onMlat(Any(), 3, 0, 20, 0, true, day0)
        assertEquals(13, r.snapshot(day0).mlatSyncs)
    }

    @Test
    fun feedingTimeIsCappedAcrossGapsAndTracksTheLongestRun() {
        val r = fresh()
        r.onFeedTick(true, connectedSinceMs = day0, nowMs = day0)
        r.onFeedTick(true, day0, day0 + 2_000)
        r.onFeedTick(true, day0, day0 + 602_000) // 10 minutes later: suspended, not feeding
        r.onFeedTick(false, null, day0 + 604_000)
        r.onFeedTick(true, day0 + 606_000, day0 + 606_000)
        val h = r.snapshot(day0)
        assertEquals(2_000 + HistoryRecorder.MAX_TICK_GAP_MS, h.feedingMs)
        assertEquals(602_000, h.longestRunMs)
        assertEquals(day0, h.firstFedMs)
    }

    @Test
    fun uniqueAircraftForLifeAndPerDay() {
        val r = fresh()
        r.onAircraft(listOf(1, 2, 3), listOf(pos(50.0, 10.0)), recordRange = true, contributing = true, nowMs = day0)
        r.onAircraft(listOf(2, 3, 4), listOf(pos(900.0, 10.0)), recordRange = true, contributing = true, nowMs = day0 + 1_000) // 900 nm: bogus
        r.onAircraft(listOf(1, 5), listOf(pos(80.0, 10.0)), recordRange = true, contributing = true, nowMs = day0 + HistoryRecorder.DAY_MS)
        val h = r.snapshot(day0)
        assertEquals(5, h.uniqueAircraft)
        assertEquals(listOf(4, 2), h.days.map { it.aircraft })
        assertEquals(80.0, h.furthestNm!!, 0.0)
    }

    @Test
    fun savesAndContinuesAfterARestart() {
        val paths = FeederPaths(tmp.root)
        val store = HistoryStore(paths)
        val r1 = HistoryRecorder(store.load(), utc)
        r1.onAircraft(listOf(0xa1b2c3, 0xc0ffee), listOf(pos(30.0, 200.0)), true, true, day0)
        r1.onStats(1, 500, 5_000, 120_000.0, 0, true, day0)
        r1.save(store, day0)
        r1.onAircraft(listOf(0xa1b2c3, 0x7c1234), emptyList(), true, true, day0)
        r1.save(store, day0)
        assertEquals("each address is written once", 12, paths.historyAircraftFile.length())

        val r2 = HistoryRecorder(store.load(), utc)
        r2.onAircraft(listOf(0xc0ffee, 0x123456), emptyList(), true, true, day0 + 5_000)
        r2.onStats(7, 100, 1_000, null, 0, true, day0 + 5_000)
        val h = r2.snapshot(day0)
        assertEquals(4, h.uniqueAircraft)
        assertEquals("today's count survives the restart", 4, h.days.single().aircraft)
        assertEquals(600, h.messages)
        assertEquals(120_000 / 1852.0, h.furthestNm!!, 1e-9)
        assertEquals(h, History.fromJson(h.toJson()))
    }

    @Test
    fun keepsAtMostMaxDays() {
        val r = fresh()
        repeat(History.MAX_DAYS + 10) { d -> r.onStats(1, (d + 1).toLong(), 0, null, 0, true, day0 + d * HistoryRecorder.DAY_MS) }
        val days = r.snapshot(day0).days
        assertEquals(History.MAX_DAYS, days.size)
        assertEquals(20_000 + 10, days.first().day)
    }

    @Test
    fun parsesReadsbFiles() {
        val stats = HistorySamples.parseStats(JSONObject(
            """{"total":{"start":1790826456.0,"messages_valid":1222,"max_distance":0,"remote":{"bytes_out":45998}}}"""
        ))!!
        assertEquals(1790826456L, stats.session)
        assertEquals(1222, stats.messages)
        assertEquals(45_998, stats.bytesOut)

        val a = HistorySamples.parseAircraft(JSONObject(
            """{"aircraft":[
                {"hex":"a1b2c3","r_dst":42.5,"r_dir":271.0,"seen_pos":3.1},
                {"hex":"~2e0045","r_dst":99.0,"r_dir":10.0,"seen_pos":1.0},
                {"hex":"7c1234"},
                {"hex":"c0ffee","r_dst":120.0,"r_dir":90.0,"seen_pos":300.0}]}"""
        ))
        assertEquals(listOf(0xa1b2c3, 0x7c1234, 0xc0ffee), a.addresses)
        assertEquals("non-ICAO and stale positions don't count", 42.5, a.furthestNm!!, 0.0)
        assertNull(HistorySamples.parseStats(JSONObject("{}")))
        assertTrue(HistorySamples.parseAircraft(JSONObject("{}")).addresses.isEmpty())
    }

    @Test
    fun rangePlotKeepsTheFurthestInEachDirection() {
        val r = fresh()
        r.onAircraft(listOf(1, 2), listOf(pos(40.0, 5.0), pos(60.0, 95.0)), recordRange = true, contributing = true, nowMs = day0)
        r.onAircraft(listOf(1, 3), listOf(pos(70.0, 9.9), pos(20.0, 96.0), pos(30.0, 359.9)), true, true, day0 + 1)
        r.onAircraft(listOf(4), listOf(pos(500.0, 180.0)), true, true, day0 + 2) // implausible: ignored
        val range = r.snapshot(day0).rangeNm
        assertEquals(History.RANGE_BEARINGS, range.size)
        assertEquals(70.0, range[0], 0.0)  // 0-10 degrees
        assertEquals(60.0, range[9], 0.0)  // 90-100 degrees
        assertEquals(30.0, range[35], 0.0) // 350-360 degrees
        assertEquals(0.0, range[18], 0.0)
        assertEquals(day0, r.snapshot(day0).rangeSinceMs)
    }

    @Test
    fun noRangePlotInMobileModeButTheRecordStillCounts() {
        val r = fresh()
        r.onAircraft(listOf(1), listOf(pos(80.0, 45.0)), recordRange = false, contributing = true, nowMs = day0)
        val h = r.snapshot(day0)
        assertTrue(h.rangeNm.all { it == 0.0 })
        assertEquals(80.0, h.furthestNm!!, 0.0)
    }

    /** A power cut mid-append leaves part of a record; it's cut off so later records line up. */
    @Test
    fun aPartialAircraftRecordIsDroppedOnLoad() {
        val paths = FeederPaths(tmp.root)
        val store = HistoryStore(paths)
        val r = HistoryRecorder(store.load(), utc)
        r.onAircraft(listOf(1, 2, 3), emptyList(), false, true, day0)
        r.save(store, day0)
        paths.historyAircraftFile.appendBytes(byteArrayOf(0, 0)) // half of a fourth record
        val logged = ArrayList<String>()
        val loaded = HistoryStore(paths) { logged += it }.load()
        assertEquals(setOf(1, 2, 3), loaded.aircraft)
        assertEquals(12L, paths.historyAircraftFile.length())
        assertEquals(1, logged.size)
        // Appending after that lines up again.
        val r2 = HistoryRecorder(loaded, utc)
        r2.onAircraft(listOf(4), emptyList(), false, true, day0 + 1)
        r2.save(store, day0 + 1)
        assertEquals(setOf(1, 2, 3, 4), HistoryStore(paths).load().aircraft)
    }

    /** history.json there but unreadable: load says so, rather than handing back zeros to be saved over it. */
    @Test
    fun anUnreadableHistoryIsntMistakenForNone() {
        val paths = FeederPaths(tmp.root)
        val store = HistoryStore(paths)
        val r = HistoryRecorder(store.load(), utc)
        r.onStats(session = 1, messagesTotal = 100, bytesOut = 1_000, maxDistanceM = null, loopbackAtStats = 0, contributing = true, nowMs = day0)
        r.save(store, day0)
        RandomAccessFile(paths.historyFile, "rw").use { raf ->
            raf.channel.lock().use {
                assertThrows(IOException::class.java) { HistoryStore(paths).load() }
            }
        }
        assertEquals(100, HistoryStore(paths).load().history.messages)
    }

    @Test
    fun aDamagedHistoryIsCopiedAsideAndStartsAfresh() {
        val paths = FeederPaths(tmp.root)
        paths.historyFile.writeText("{\"messages\": 10")
        val h = HistoryStore(paths).load().history
        assertEquals(0, h.messages)
        assertEquals(1, tmp.root.listFiles { f -> f.name.startsWith("history.json.unreadable-") }!!.size)
    }

    /** A failed history.json write doesn't append the same aircraft again at the next save. */
    @Test
    fun aircraftArentAppendedTwice() {
        val paths = FeederPaths(tmp.root)
        val store = HistoryStore(paths)
        val r = HistoryRecorder(store.load(), utc)
        r.onAircraft(listOf(7, 8), emptyList(), false, true, day0)
        // A folder with something in it where history.json goes: its write fails.
        File(paths.historyFile, "x").apply { parentFile.mkdirs(); writeText("x") }
        assertThrows(Exception::class.java) { r.save(store, day0) }
        paths.historyFile.deleteRecursively()
        r.onStats(session = 1, messagesTotal = 1, bytesOut = 1, maxDistanceM = null, loopbackAtStats = 0, contributing = true, nowMs = day0)
        r.save(store, day0 + 1)
        assertEquals("two records, not four", 8L, paths.historyAircraftFile.length())
    }

    @Test
    fun resettingTheRangePlotStartsItAgainAndSurvivesASave() {
        val paths = FeederPaths(tmp.root)
        val store = HistoryStore(paths)
        val r = HistoryRecorder(store.load(), utc)
        r.onAircraft(listOf(1), listOf(pos(80.0, 45.0)), true, true, day0)
        r.resetRange(day0 + 10)
        r.onAircraft(listOf(1), listOf(pos(20.0, 200.0)), true, true, day0 + 20)
        r.save(store, day0 + 20)
        val h = HistoryRecorder(store.load(), utc).snapshot(day0 + 30)
        assertEquals(0.0, h.rangeNm[4], 0.0)
        assertEquals(20.0, h.rangeNm[20], 0.0)
        assertEquals(day0 + 10, h.rangeSinceMs)
        assertEquals("the record isn't reset", 80.0, h.furthestNm!!, 0.0)
    }
}