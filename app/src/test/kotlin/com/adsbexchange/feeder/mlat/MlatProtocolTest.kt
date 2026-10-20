package com.adsbexchange.feeder.mlat

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Deflater
import java.util.zip.Inflater

class MlatProtocolTest {
    private val config = MlatClient.Config(
        uuid = "9ba38053-7674-485a-a39b-e2a7134b73ee", user = "william34 london",
        lat = 43.089, lon = -76.103, altM = 150.0, privacy = true, inputPort = 30005,
    )

    @Test fun handshakeHasUpstreamFieldsInSortedOrder() {
        val json = handshakeJson(config, 12_000_000)
        val o = JSONObject(json)
        assertEquals(3, o.getInt("version"))
        assertEquals("0.4.2", o.getString("client_version"))
        assertEquals("dump1090", o.getString("clock_type"))
        assertEquals(12_000_000, o.getLong("clock_frequency"))
        assertTrue(o.isNull("clock_epoch"))
        assertEquals("william34 london", o.getString("user"))
        assertEquals(config.uuid, o.getString("uuid"))
        assertEquals(43.089, o.getDouble("lat"), 1e-9)
        assertEquals(150.0, o.getDouble("alt"), 1e-9)
        assertTrue(o.getBoolean("privacy"))
        assertFalse(o.getBoolean("udp_transport"))
        assertFalse(o.getBoolean("return_results"))
        assertTrue(o.getBoolean("selective_traffic") && o.getBoolean("heartbeat") && o.getBoolean("return_stats"))
        assertEquals(listOf("none", "zlib", "zlib2"), (0 until 3).map { o.getJSONArray("compress").getString(it) })
        // Same layout as json.dumps(sort_keys=True): keys in order, ", " and ": " separators.
        assertTrue(json.startsWith("{\"alt\": 150.0, \"client_version\": \"0.4.2\", \"clock_epoch\": null, "))
        assertTrue(json.endsWith("\"user\": \"william34 london\", \"uuid\": \"${config.uuid}\", \"version\": 3}"))
    }

    @Test fun jsonStringsAreAsciiEscaped() {
        assertEquals("\"a\\\"b\\\\c\\u00e9\"", MlatClient.str("a\"b\\cé"))
    }

    @Test fun zlibFramingRoundTrips() {
        val lines = (1..500).map { "{\"sync\":{\"et\":$it,\"em\":\"8d40621d58c382d690c8ac2863a7\",\"ot\":${it + 5},\"om\":\"8d40621d58c386435cc412692ad6\"}}" }
        val def = Deflater(1)
        val packets = zlibPackets(def, lines) + zlibPackets(def, listOf("{\"heartbeat\":{}}"))
        for (p in packets) {
            val len = ((p[0].toInt() and 0xff) shl 8) or (p[1].toInt() and 0xff)
            assertEquals(p.size - 2, len)
            // The sync-flush tail is stripped on the wire, as upstream.
            assertFalse(p.copyOfRange(p.size - 4, p.size).contentEquals(byteArrayOf(0, 0, -1, -1)))
        }
        val wire = packets.fold(ByteArray(0)) { acc, b -> acc + b }
        val (decoded, consumed) = zlibUnpack(Inflater(), wire)
        assertEquals(wire.size, consumed)
        assertEquals(lines + "{\"heartbeat\":{}}", decoded)
    }

    @Test fun zlibUnpackWaitsForCompletePackets() {
        val wire = zlibPackets(Deflater(1), listOf("{\"start_sending\":[\"a1b2c3\"]}")).single()
        val inf = Inflater()
        val (none, used) = zlibUnpack(inf, wire.copyOf(wire.size - 1))
        assertTrue(none.isEmpty())
        assertEquals(0, used)
        assertArrayEquals(arrayOf("{\"start_sending\":[\"a1b2c3\"]}"), zlibUnpack(inf, wire).first.toTypedArray())
    }

    /** A few KB from the (plain TCP) server must not inflate into megabytes in memory. */
    @Test fun zlibUnpackRefusesAZipBomb() {
        val bomb = zlibPackets(Deflater(9), listOf("a".repeat(2 * MAX_SERVER_MESSAGE))).single()
        assertTrue("small on the wire: ${bomb.size} bytes", bomb.size < 16 * 1024)
        val thrown = runCatching { zlibUnpack(Inflater(), bomb) }.exceptionOrNull()
        assertTrue("$thrown", thrown is java.io.IOException)
    }

    @Test fun rateReportRounding() {
        assertEquals("0.33", MlatClient.round2(1.0 / 3))
        assertEquals("2.0", MlatClient.round2(2.0))
        assertEquals("0.5", MlatClient.round2(0.5))
    }
}
