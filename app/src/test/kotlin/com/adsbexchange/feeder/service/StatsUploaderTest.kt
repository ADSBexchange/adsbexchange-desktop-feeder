package com.adsbexchange.feeder.service

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/** The stats uploads: the same body and headers as adsbexchange-stats' json-status, to a local server only. */
class StatsUploaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val uuid = "0f1e2d3c-4b5a-4987-8a6b-5c4dad5bde5c"

    /** As readsb writes it (json_out.c), with one aircraft placed and one not. */
    private val aircraftJson = """
        { "now" : 1759500000.1,
          "messages" : 123456,
          "aircraft" : [
        {"hex":"a1b2c3","type":"adsb_icao","flight":"TEST1   ","alt_baro":35000,"lat":43.123456,"lon":-76.123456,"nic":8,"rc":186,"seen_pos":0.4,"r_dst":12.345,"r_dir":45.6,"messages":100,"seen":0.1,"rssi":-20.5},
        {"hex":"d4e5f6","type":"adsb_icao","alt_baro":12000,"messages":12,"seen":2.0,"rssi":-30.2}
          ]
        }
    """.trimIndent()

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun addsWhatTheScriptAdds() {
        val o = JSONObject(StatsUploader.payloadJson(aircraftJson, uuid, includeRange = true)!!)
        assertEquals(uuid, o.getString("uuid"))
        assertEquals("", o.getString("v"))
        // jq's floor: the mean of -20.5 and -30.2 is -25.35.
        assertEquals(-26, o.getInt("rssi"))
        assertEquals(-31, o.getInt("rssi-min"))
        assertEquals(-21, o.getInt("rssi-max"))
        assertEquals(123456, o.getInt("messages"))
        assertEquals(2, o.getJSONArray("aircraft").length())
        assertEquals(12.345, o.getJSONArray("aircraft").getJSONObject(0).getDouble("r_dst"), 1e-9)
    }

    @Test
    fun receiverDistancesOnlyWithMlat() {
        val text = StatsUploader.payloadJson(aircraftJson, uuid, includeRange = false)!!
        assertFalse(text.contains("r_dst"))
        assertFalse(text.contains("r_dir"))
        // Everything else is as readsb wrote it, aircraft positions included.
        val first = JSONObject(text).getJSONArray("aircraft").getJSONObject(0)
        assertEquals(43.123456, first.getDouble("lat"), 1e-9)
        assertEquals(-20.5, first.getDouble("rssi"), 1e-9)
        assertTrue(text.startsWith(aircraftJson.substringBefore("\"r_dst\"").dropLast(1)))
    }

    /** If readsb ever writes them differently, nothing is sent rather than the distances leaking. */
    @Test
    fun unexpectedDistanceFormatIsNotSent() {
        val changed = aircraftJson.replace("\"r_dst\":12.345", "\"r_dst\": 12.345")
        assertNull(StatsUploader.payloadJson(changed, uuid, includeRange = false))
        assertNotNull(StatsUploader.payloadJson(changed, uuid, includeRange = true))
    }

    @Test
    fun noAircraft() {
        val o = JSONObject(StatsUploader.payloadJson("""{ "now" : 1.0, "messages" : 0, "aircraft" : [ ] }""", uuid, false)!!)
        assertEquals(0, o.getInt("rssi"))
        assertEquals(0, o.getInt("rssi-min"))
        assertEquals(0, o.getInt("rssi-max"))
        assertNull(StatsUploader.payloadJson("""{ "now" : 1.0 }""", uuid, false))
    }

    private class Upload(val headers: Map<String, String?>, val body: String)

    /** A stand-in for ADS-B Exchange's /api/receive/ on 127.0.0.1, answering with [status]. */
    private fun startServer(uploads: MutableList<Upload>, status: () -> Int): URI {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 0)
        s.createContext("/api/receive/") { ex ->
            val body = GZIPInputStream(ex.requestBody).use { String(it.readBytes(), Charsets.UTF_8) }
            uploads += Upload(
                listOf("adsbx-uuid", "Content_Encoding", "Content-Type").associateWith { ex.requestHeaders.getFirst(it) },
                body,
            )
            ex.sendResponseHeaders(status(), -1)
            ex.close()
        }
        s.start()
        server = s
        return URI("http://127.0.0.1:${s.address.port}/api/receive/")
    }

    private fun await(what: String, timeoutMs: Long = 10_000, test: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (test()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun writeAircraft(): File = File(tmp.root, "aircraft.json").apply { writeText(aircraftJson) }

    @Test
    fun uploadsAsTheScriptDoesAndRecovers() {
        val uploads = CopyOnWriteArrayList<Upload>()
        val status = AtomicInteger(200)
        val url = startServer(uploads) { status.get() }
        val file = writeAircraft()
        val log = CopyOnWriteArrayList<String>()
        val u = StatsUploader(StatsUploader.Config(uuid, file, includeRange = false, url = url, intervalMs = 200)) { log += it }
        u.start()
        try {
            await("two uploads") { uploads.size >= 2 }
            val first = uploads.first()
            assertEquals(uuid, first.headers["adsbx-uuid"])
            assertEquals("gzip", first.headers["Content_Encoding"])
            assertEquals("application/x-www-form-urlencoded", first.headers["Content-Type"])
            assertEquals(uuid, JSONObject(first.body).getString("uuid"))
            assertFalse(first.body.contains("r_dst"))
            await("counted") { u.stats.uploads >= 2 && u.stats.lastOkMs != null }
            assertTrue(u.stats.bytesSent > 0)

            status.set(503)
            await("failure seen") { u.stats.error == "HTTP 503" }
            status.set(200)
            file.writeText(aircraftJson) // still fresh
            await("recovered") { u.stats.error == null }
            assertEquals("one line per run of failures", 1, log.count { it.startsWith("stats: not sent") })
            assertTrue(log.any { it == "stats: sending again" })
        } finally {
            u.stop()
        }
        val after = uploads.size
        Thread.sleep(600)
        assertEquals("nothing after stop()", after, uploads.size)
    }

    /**
     * ADS-B Exchange's end closes connections every 5 minutes or so; an upload sent just then gets
     * no reply at all. The uploader sends it again at once on a fresh connection, so nothing is
     * lost and no failure is reported. (From the phone feeder, 267f988.)
     */
    @Test
    fun uploadThatGetsNoReplyIsSentAgainAtOnce() {
        val requests = AtomicInteger()
        val socket = java.net.ServerSocket(0, 10, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val serverThread = Thread {
            while (!socket.isClosed) {
                val s = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    s.use { conn ->
                        val input = conn.getInputStream().buffered()
                        while (true) {
                            // Request line and headers, then the body by Content-Length.
                            val head = StringBuilder()
                            while (!head.endsWith("\r\n\r\n")) {
                                val b = input.read()
                                if (b < 0) return@use
                                head.append(b.toChar())
                            }
                            val length = Regex("(?i)content-length:\\s*(\\d+)").find(head)!!.groupValues[1].toInt()
                            input.readNBytes(length)
                            // The first upload: hang up without a word, as the real server sometimes does.
                            if (requests.incrementAndGet() == 1) return@use
                            conn.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            conn.getOutputStream().flush()
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val file = writeAircraft()
        val log = CopyOnWriteArrayList<String>()
        val url = URI("http://127.0.0.1:${socket.localPort}/api/receive/")
        val u = StatsUploader(StatsUploader.Config(uuid, file, includeRange = false, url = url, intervalMs = 300)) { log += it }
        u.start()
        try {
            await("an upload accepted") { u.stats.uploads >= 1 }
            assertTrue("the dropped upload was sent again", requests.get() >= 2)
            assertNull(u.stats.error)
            assertEquals("no failure reported", 0, log.count { it.startsWith("stats: not sent") })
        } finally {
            u.stop()
            socket.close()
            serverThread.join(1_000)
        }
    }

    /** readsb not writing (stuck, or still starting): nothing is sent. */
    @Test
    fun staleAircraftListIsNotSent() {
        val uploads = CopyOnWriteArrayList<Upload>()
        val url = startServer(uploads) { 200 }
        val file = writeAircraft().apply { setLastModified(System.currentTimeMillis() - 60_000) }
        val u = StatsUploader(StatsUploader.Config(uuid, file, includeRange = false, url = url, intervalMs = 100)) { }
        u.start()
        try {
            Thread.sleep(800)
            assertEquals(0, uploads.size)
            assertNull(u.stats.error)
        } finally {
            u.stop()
        }
    }
}
