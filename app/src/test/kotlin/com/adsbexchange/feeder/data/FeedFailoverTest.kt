package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The ADS-B Exchange feed falls back to feed2 as the official feeder image does; other targets have no failover. */
class FeedFailoverTest {
    @get:Rule val tmp = TemporaryFolder()
    private val settings = FeederSettings(uuid = "u", name = "", location = null)
    private val paths get() = FeederPaths(File("files"))

    @Test
    fun theAdsbxFeedHasFeed2AsItsFailover() {
        val args = settings.readsbArgs(paths, gpsdPort = 1, feedTo = FeederSettings.ADSBX_FEED)
        assertTrue(args.toString(),
            "feed1.adsbexchange.com,30004,beast_reduce_plus_out,feed2.adsbexchange.com,64004" in args)
    }

    @Test
    fun aLocalTestSinkHasNoFailover() {
        val args = settings.readsbArgs(paths, gpsdPort = 1, feedTo = "127.0.0.1,30100")
        assertTrue(args.toString(), "127.0.0.1,30100,beast_reduce_plus_out" in args)
    }

    @Test
    fun theFeedStateFollowsWhicheverServerItIsOn() {
        val log = tmp.newFile("readsb.log")
        val watcher = FeedLogWatcher(log, FeederSettings.ADSBX_FEED)
        fun says(line: String): FeedState {
            log.appendText("$line\n")
            return watcher.poll()
        }
        assertEquals(FeedState.CONNECTED,
            says("BeastReduce TCP output: Connection established: feed1.adsbexchange.com (1.2.3.4) port 30004 (sent UUID)"))
        assertEquals(FeedState.DISCONNECTED,
            says("BeastReduce TCP output: Remote server disconnected: feed1.adsbexchange.com (1.2.3.4) port 30004 (fd 7, SendQ 0, RecvQ 0)"))
        assertEquals(FeedState.DISCONNECTED,
            says("BeastReduce TCP output: Connection to feed1.adsbexchange.com (1.2.3.4) port 30004 timed out."))
        assertEquals("on feed2", FeedState.CONNECTED,
            says("BeastReduce TCP output: Connection established: feed2.adsbexchange.com (5.6.7.8) port 64004 (sent UUID)"))
        assertEquals("another connector's lines don't count", FeedState.CONNECTED,
            says("BeastReduce TCP output: Connection to 127.0.0.1 port 30100 failed: refused"))
    }
}
