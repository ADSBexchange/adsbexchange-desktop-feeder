package com.adsbexchange.feeder.service

import com.adsbexchange.feeder.mlat.MlatClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** The MLAT sync line the engine logs every 10 minutes. */
class MlatSyncLogTest {
    @Test
    fun reportsTheServersViewAndWhatWasSent() {
        val s = MlatClient.Stats(state = MlatClient.State.CONNECTED, peerCount = 7, outlierPercent = 1.53,
            badSyncTimeout = 0, requested = 6)
        assertEquals("mlat: synced with 7 nearby feeders, sync outliers 1.5%, bad-sync timeout 0; " +
            "last 10 min: 925 sync pairs and 463 MLAT messages sent, 6 aircraft requested",
            FeederEngine.mlatSyncLine(s, 925, 463, 10))
    }

    @Test
    fun beforeTheServerReportsSync() {
        val s = MlatClient.Stats(state = MlatClient.State.CONNECTED, peerCount = null)
        assertEquals("mlat: the server hasn't reported sync yet; last 10 min: 0 sync pairs and 0 MLAT messages sent, " +
            "0 aircraft requested", FeederEngine.mlatSyncLine(s, 0, 0, 10))
        assertEquals("mlat: synced with 1 nearby feeder; last 10 min: 0 sync pairs and 0 MLAT messages sent, 0 aircraft requested",
            FeederEngine.mlatSyncLine(s.copy(peerCount = 1), 0, 0, 10))
    }
}
