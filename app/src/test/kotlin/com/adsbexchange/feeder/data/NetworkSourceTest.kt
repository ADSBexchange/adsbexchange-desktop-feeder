package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Settings > Receiver's network source: what's saved, what readsb is told, and how its connection is followed. */
class NetworkSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val paths by lazy { FeederPaths(tmp.newFolder("app")) }
    private val files = FeederPaths(File("files"))

    @Test
    fun theUsbStickIsTheDefault() {
        val s = FeederSettings.load(paths)
        assertEquals(ReceiverSource.USB, s.source)
        assertNull(s.networkSource())
        val args = s.readsbArgs(files, gpsdPort = 1, feedTo = null)
        assertTrue(args.toString(), args.containsAll(listOf("--device-type", "rtlsdr")))
    }

    @Test
    fun theSourceIsSavedAndRead() {
        FeederSettings.update(paths) { it.copy(source = ReceiverSource.NETWORK, networkHost = "raspberrypi.local", networkPort = 30105) }
        val s = FeederSettings.load(paths)
        assertEquals(ReceiverSource.NETWORK, s.source)
        assertEquals("raspberrypi.local,30105", s.networkSource())
        // Back to the stick: the address is kept for next time, and isn't used.
        FeederSettings.update(paths) { it.copy(source = ReceiverSource.USB) }
        val back = FeederSettings.load(paths)
        assertEquals("raspberrypi.local", back.networkHost)
        assertNull(back.networkSource())
    }

    @Test
    fun readsbReadsTheOtherReceiversBeastOutputInsteadOfAStick() {
        val s = FeederSettings(uuid = "u", name = "", location = null, source = ReceiverSource.NETWORK,
            networkHost = "192.168.1.50", networkPort = 30005, gain = "49.6", biasTee = true)
        val args = s.readsbArgs(files, gpsdPort = 1, feedTo = FeederSettings.ADSBX_FEED, network = s.networkSource())
        assertTrue(args.toString(), args.containsAll(listOf("--device-type", "none")))
        assertTrue(args.toString(), "192.168.1.50,30005,beast_in" in args)
        assertFalse("no stick", "rtlsdr" in args)
        // The stick's settings don't apply: no gain, and no voltage on a socket that isn't there.
        assertFalse(args.toString(), "--gain" in args || "--enable-biastee" in args)
        // Fed, and read by MLAT, as with a stick.
        assertTrue(args.toString(), "feed1.adsbexchange.com,30004,beast_reduce_plus_out,feed2.adsbexchange.com,64004" in args)
        assertTrue(args.toString(), "--net-bo-port" in args)
    }

    /** It goes into readsb's comma-separated --net-connector: nothing that could add a field of its own. */
    @Test
    fun onlyAPlainHostNameOrAddressIsUsed() {
        listOf("192.168.1.50", "raspberrypi.local", "pi", "piaware-1.lan").forEach {
            assertTrue(it, ReceiverSource.validHost(it))
        }
        listOf("", "1.2.3.4,30004,beast_reduce_plus_out", "pi local", "-pi", "pi.", "http://pi", "pi:30005", "pi;x").forEach {
            assertFalse(it, ReceiverSource.validHost(it))
        }
        assertFalse(ReceiverSource.validPort(0))
        assertFalse(ReceiverSource.validPort(65536))
        // A settings.json edited by hand to something unusable means the stick, not a readsb that can't start.
        val bad = FeederSettings(uuid = "u", name = "", location = null, source = ReceiverSource.NETWORK,
            networkHost = "pi,30004,beast_out", networkPort = 30005)
        assertNull(bad.networkSource())
        assertNull(bad.copy(networkHost = "pi", networkPort = 70000).networkSource())
    }

    @Test
    fun theConnectionToTheOtherReceiverIsFollowedInTheLog() {
        val log = tmp.newFile("readsb.log")
        val watcher = FeedLogWatcher(log, "192.168.1.50,30005")
        fun says(line: String): FeedState {
            log.appendText("$line\n")
            return watcher.poll()
        }
        // The lines as readsb.exe writes them.
        assertEquals(FeedState.DISCONNECTED,
            says("Beast TCP input: Connection to 192.168.1.50 port 30005 failed (1): 107 (connection refused)"))
        assertEquals(ConnectFailure.REFUSED, watcher.failure)
        assertEquals(FeedState.DISCONNECTED,
            says("Beast TCP input: Connection to 192.168.1.50 port 30005 failed 10 times, suppressing most error messages until connection succeeds"))
        assertEquals("the reason given before still holds", ConnectFailure.REFUSED, watcher.failure)
        assertEquals(FeedState.DISCONNECTED, says("Beast TCP input: Connection to 192.168.1.50 port 30005 timed out."))
        assertEquals(ConnectFailure.NO_ANSWER, watcher.failure)
        assertEquals(FeedState.CONNECTED, says("Beast TCP input: Connection established: 192.168.1.50 port 30005"))
        assertNull(watcher.failure)
        assertEquals("another port's lines don't count", FeedState.CONNECTED,
            says("Beast TCP input: Connection to 192.168.1.50 port 300 failed (1): 107 (connection refused)"))
        assertEquals(FeedState.DISCONNECTED,
            says("Beast TCP input: Remote server disconnected: 192.168.1.50 port 30005 (fd 7, SendQ 0, RecvQ 0)"))
        assertNull("dropped, not refused", watcher.failure)

        val named = FeedLogWatcher(log, "raspberrypi.local,30005", startAt = log.length())
        log.appendText("Beast TCP input: Name resolution for raspberrypi.local failed: No such host is known. \n")
        assertEquals(FeedState.DISCONNECTED, named.poll())
        assertEquals(ConnectFailure.UNKNOWN_HOST, named.failure)
    }

    @Test
    fun anAddressIsShownAsPeopleWriteIt() {
        assertEquals("192.168.1.50:30005", ReceiverSource.shown("192.168.1.50,30005"))
    }
}
