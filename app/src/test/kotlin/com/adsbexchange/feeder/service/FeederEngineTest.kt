package com.adsbexchange.feeder.service

import com.adsbexchange.feeder.data.FeedState
import com.adsbexchange.feeder.data.FeederPaths
import com.adsbexchange.feeder.data.FeederSettings
import com.adsbexchange.feeder.data.FeederStatus
import com.adsbexchange.feeder.data.History
import com.adsbexchange.feeder.data.HistorySamples
import com.adsbexchange.feeder.data.FeederStatus.Reason
import com.adsbexchange.feeder.data.FeederStatus.State
import com.adsbexchange.feeder.data.StationLocation
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * The engine end to end with the real readsb.exe on the synthetic capture from
 * tools/spike-test.ps1 (AdsbSim): feed to a local sink, position over the gpsd server, clean stop.
 * Skipped unless native/build/readsb.exe and tools/testdata/spike.iq exist. Nothing leaves 127.0.0.1.
 */
class FeederEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val exe = System.getProperty("feeder.readsb")?.let(::File)
    private val testData = System.getProperty("feeder.testdata")?.let(::File)
    private val iq get() = File(testData, "spike.iq")
    private val expected get() = JSONObject(File(testData, "expected.json").readText())
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private lateinit var paths: FeederPaths
    private lateinit var engine: FeederEngine
    private lateinit var sink: ServerSocket
    private val received = ByteArrayOutputStream()

    @Before
    fun setUp() {
        assumeTrue("readsb.exe not built (tools/build-native.ps1)", exe?.isFile == true)
        assumeTrue("no capture (tools/spike-test.ps1 makes it)", iq.isFile)
        paths = FeederPaths(tmp.newFolder("app"))
        engine = FeederEngine(paths, findReadsb = { exe })
        sink = ServerSocket(0, 1, loopback)
        thread(isDaemon = true) {
            runCatching {
                sink.accept().use { s -> s.getInputStream().copyTo(received) }
            }
        }
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
        if (::sink.isInitialized) sink.close()
    }

    private fun location(): StationLocation {
        val rx = expected.getJSONObject("receiver")
        return StationLocation(rx.getDouble("lat"), rx.getDouble("lon"), null, null, 0, StationLocation.Source.MANUAL)
    }

    private fun awaitStatus(timeoutMs: Long, what: String, test: (FeederStatus) -> Boolean): FeederStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = engine.status.value
            if (test(s)) return s
            Thread.sleep(200)
        }
        throw AssertionError("timed out waiting for $what; status ${engine.status.value}")
    }

    @Test
    fun decodesFeedsAndKeepsThePositionPrivate() {
        val rx = location()
        // As with the window open: history samples aircraft every 10 s (once a minute otherwise).
        engine.uiVisible = true
        // A stand-in for ADS-B Exchange's stats endpoint (StatsUploader).
        val statsBodies = CopyOnWriteArrayList<String>()
        val stats = HttpServer.create(InetSocketAddress(loopback, 0), 0).apply {
            createContext("/api/receive/") { ex ->
                statsBodies += GZIPInputStream(ex.requestBody).use { String(it.readBytes(), Charsets.UTF_8) }
                ex.sendResponseHeaders(200, -1)
                ex.close()
            }
            start()
        }
        engine.start(FeederEngine.TestRun(iq, rx, feedTo = "127.0.0.1,${sink.localPort}",
            statsUrl = "http://127.0.0.1:${stats.address.port}/api/receive/", statsIntervalMs = 1_000))
        awaitStatus(20_000, "feed connected") { it.state == State.RUNNING && it.feedState == FeedState.CONNECTED }

        // readsb got the position from the gpsd server (it's on no command line).
        val receiverJson = File(paths.jsonDir, "receiver.json")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !(receiverJson.isFile && JSONObject(receiverJson.readText()).has("lat"))) {
            Thread.sleep(200)
        }
        val r = JSONObject(receiverJson.readText())
        assertEquals(rx.lat, r.getDouble("lat"), 1e-4)
        assertEquals(rx.lon, r.getDouble("lon"), 1e-4)

        // Any other program connecting to the gpsd port gets nothing.
        val log = paths.logFile.readText()
        val gpsdPort = Regex("""127\.0\.0\.1,(\d+),gpsd_in""").find(log)!!.groupValues[1].toInt()
        Socket(loopback, gpsdPort).use { s ->
            s.soTimeout = 3_000
            val got = try { s.getInputStream().read() } catch (_: SocketTimeoutException) { -2 }
            assertTrue("gpsd answered a stranger ($got)", got == -1 || got == -2)
        }
        assertTrue("coordinates in the log", !log.contains("43.0000") && !log.contains("-76.0000") && !log.contains("--lat"))

        // At the end of the capture readsb exits; a test run isn't restarted.
        val end = awaitStatus(45_000, "end of capture") { it.state == State.STOPPED }
        assertEquals(Reason.READSB_EXITED, end.reason)
        val hexes = JSONObject(paths.aircraftJson.readText()).getJSONArray("aircraft").let { a ->
            (0 until a.length()).map { a.getJSONObject(it).getString("hex") }.toSet()
        }
        val want = expected.getJSONArray("aircraft").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("hex") } }
        assertTrue("decoded $hexes, expected $want", hexes.containsAll(want))
        Thread.sleep(500)
        assertTrue("feed reached the sink", received.size() > 1000)
        // readsb sent the feeder ID it read from --uuid-file, and the ID isn't in the log.
        val feederId = FeederSettings.load(paths).uuid
        assertTrue("feeder ID sent on the feed", String(received.toByteArray(), Charsets.ISO_8859_1).contains(feederId))
        assertFalse("feeder ID in the log", paths.logFile.readText().contains(feederId))
        assertTrue("history saved", paths.historyFile.isFile)
        // The range plot came from the simulated aircraft's positions (readsb's r_dst/r_dir).
        val range = History.read(paths)!!.rangeNm
        assertTrue("range plot recorded: $range", range.count { it > 0 } >= 3)
        // readsb's own furthest position (stats.json: Max range, Furthest aircraft), though it only
        // learnt the position over gpsd after starting (readsb-windows.patch, item 4).
        val maxDistanceM = HistorySamples.stats(paths)?.maxDistanceM
        assertTrue("readsb's max_distance: $maxDistanceM", (maxDistanceM ?: 0.0) > 1852.0)

        // Stats went out with this feeder's UUID and the aircraft, but (no MLAT in this run) without
        // the distances from the receiver.
        stats.stop(0)
        assertTrue("stats uploads: ${statsBodies.size}", statsBodies.size >= 3)
        val uuid = FeederSettings.load(paths).uuid
        val withAircraft = statsBodies.map(::JSONObject).filter { it.getJSONArray("aircraft").length() > 0 }
        assertTrue("stats with aircraft", withAircraft.isNotEmpty())
        withAircraft.forEach { assertEquals(uuid, it.getString("uuid")) }
        assertTrue("receiver distances in stats", statsBodies.none { "r_dst" in it || "r_dir" in it })
        assertTrue("readsb wrote distances, so they were taken out", paths.aircraftJson.readText().contains("\"r_dst\""))
    }

    /**
     * MLAT end to end, against tools/mlat-sim/FakeMlatServer.java on 127.0.0.1: the client checks
     * that its Beast input is this engine's readsb (the listener's end of the connection belongs to
     * readsb's process; from the phone feeder, 37a3b8e), then forwards what it hears.
     */
    @Test
    fun mlatForwardsOnlyOurReadsb() {
        val javaExe = File(System.getProperty("java.home"), "bin/java.exe").path
        val fakeMlat = File(testData!!.parentFile, "mlat-sim/FakeMlatServer.java")
        val summary = File(tmp.root, "mlat-summary.json")
        val mlatPort = ServerSocket(0, 1, loopback).use { it.localPort }
        val hexes = expected.getJSONArray("aircraft").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("hex") } }
        val server = ProcessBuilder(listOf(javaExe, fakeMlat.path, "$mlatPort", summary.path, "90") + hexes)
            .redirectErrorStream(true).redirectOutput(File(tmp.root, "fake-mlat.log")).start()
        try {
            Thread.sleep(3_000) // single-file java compiles before it listens
            // MLAT's sync quality logged every 5 s instead of every 10 minutes.
            engine.shutdown()
            engine = FeederEngine(paths, findReadsb = { exe }, mlatLogEveryMs = 5_000)
            engine.start(FeederEngine.TestRun(iq, location(), feedTo = "127.0.0.1,${sink.localPort}",
                mlatServer = "127.0.0.1" to mlatPort))
            awaitStatus(60_000, "end of capture") { it.state == State.STOPPED }
            assertTrue("stand-in finished", server.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
            val s = JSONObject(summary.readText())
            assertTrue("MLAT input connected: $s", s.getBoolean("input_connected"))
            assertTrue("MLAT forwarded readsb's aircraft: $s", s.getJSONArray("seen").length() > 0)
            val log = paths.logFile.readText()
            assertFalse("our readsb was refused", log.contains("isn't this app's readsb"))
            // The stand-in reports 7 nearby feeders; the periodic line records it.
            assertTrue("MLAT sync logged", log.lines().any { "mlat: synced with 7 nearby feeders" in it && "sync pairs" in it })
        } finally {
            server.destroyForcibly()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testStatsMustBeLocal() {
        FeederEngine.TestRun(iq, location(), statsUrl = "https://adsbexchange.com/api/receive/")
    }

    @Test
    fun stopsReadsbCleanly() {
        engine.start(FeederEngine.TestRun(iq, location()))
        awaitStatus(15_000, "running") { it.state == State.RUNNING }
        Thread.sleep(2_000)
        engine.stop()
        val s = engine.status.value
        assertEquals(State.STOPPED, s.state)
        assertEquals(Reason.USER_STOPPED, s.reason)
        assertEquals("readsb's exit code after closing its stdin", 0, s.exitCode)
        assertTrue(paths.logFile.readText().contains("Caught SIGTERM"))
    }
}
