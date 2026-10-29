package com.adsbexchange.feeder.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsbexchange.feeder.BuildInfo
import com.adsbexchange.feeder.data.FeederPaths
import com.adsbexchange.feeder.data.FeederSettings
import com.adsbexchange.feeder.data.FeederStatus
import com.adsbexchange.feeder.data.History
import com.adsbexchange.feeder.data.RadarTrails
import com.adsbexchange.feeder.data.ReceiverStatus
import com.adsbexchange.feeder.data.ReceiverStatusReader
import com.adsbexchange.feeder.resources.Res
import com.adsbexchange.feeder.resources.icon
import com.adsbexchange.feeder.service.FeederEngine
import com.adsbexchange.feeder.service.ResourceMonitor
import com.adsbexchange.feeder.service.StatsUploader
import com.adsbexchange.feeder.service.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

private enum class Page(val title: String) {
    STATUS("Status"), RADAR("Radar"), AIRCRAFT("Aircraft"), HISTORY("History"), SETTINGS("Settings")
}

/** The window's content. Only composed while the window is shown, so its polling stops when hidden. */
@Composable
fun FeederApp(engine: FeederEngine, paths: FeederPaths, updates: UpdateChecker? = null) {
    val status by engine.status.collectAsState()
    var receiver by remember { mutableStateOf<ReceiverStatus?>(null) }
    var feed by remember { mutableStateOf(FeedStats()) }
    var history by remember { mutableStateOf<History?>(null) }
    var page by remember { mutableStateOf(Page.STATUS) }
    var usage by remember { mutableStateOf<ResourceMonitor.Usage?>(null) }
    // The radar's trails. FeederApp is only composed while the window is shown, so they start afresh
    // each time it opens rather than jumping across the gap (the phone clears them after 15 s hidden).
    val trails = remember { RadarTrails() }
    var trailMap by remember { mutableStateOf(emptyMap<String, List<RadarTrails.Point>>()) }
    var radarRangeNm by remember { mutableStateOf(FeederSettings.load(paths).radarRangeNm) }
    var radarAirports by remember { mutableStateOf(FeederSettings.load(paths).radarAirports) }

    // readsb rewrites aircraft.json every second; follow it while the window is open.
    LaunchedEffect(Unit) {
        val reader = ReceiverStatusReader(paths)
        val tracker = FeedStatsTracker()
        val monitor = ResourceMonitor()
        var seconds = 0
        while (true) {
            val st = engine.status.value
            val r = withContext(Dispatchers.IO) { reader.read(st.feedTarget) }
            receiver = r
            if (st.state != FeederStatus.State.RUNNING) trails.clear()
            trailMap = trails.update(r.aircraft, System.currentTimeMillis())
            val f = tracker.update(st, r)
            feed = f
            // The Desktop resources card: every 5 s is plenty, and costs next to nothing.
            if (seconds++ % 5 == 0) {
                val sent = if (st.state == FeederStatus.State.RUNNING && st.feeding) {
                    f.bytesSent?.let { it + (st.mlat?.bytesSent ?: 0) + (st.stats?.bytesSent ?: 0) }
                } else null
                usage = withContext(Dispatchers.IO) { monitor.sample(engine.readsbPid, sent) }
            }
            delay(1_000)
        }
    }
    // The engine saves history.json every 10 s while the window is open. The radar draws its range plot.
    // Re-read at once after the History tab changes something (refresh), as well as every 5 s.
    var historyRefresh by remember { mutableStateOf(0) }
    LaunchedEffect(page, historyRefresh) {
        if (historyRefresh > 0) delay(500) // the engine applies a reset on its own thread
        while (page == Page.HISTORY || page == Page.RADAR) {
            history = withContext(Dispatchers.IO) { History.read(paths) }
            delay(5_000)
        }
    }

    FeederTheme {
        // The Surface sets the default text colour; outside one, Text is black.
        Surface(Modifier.fillMaxSize(), color = Brand.Black, contentColor = Brand.Text) {
            Column(Modifier.fillMaxSize()) {
                BrandHeader()
                // A newer version, on every tab, until downloaded or put off ("Not now").
                val update = updates?.state?.collectAsState()?.value?.available
                var dismissed by remember { mutableStateOf(FeederSettings.load(paths).dismissedUpdate) }
                if (update != null && update.version != dismissed) {
                    UpdateBanner(update, onLater = {
                        dismissed = update.version
                        FeederSettings.update(paths) { it.copy(dismissedUpdate = update.version) }
                    })
                }
                PrimaryTabRow(selectedTabIndex = page.ordinal, containerColor = Brand.Black) {
                    Page.entries.forEach { p ->
                        Tab(selected = page == p, onClick = { page = p }, text = { Text(p.title) })
                    }
                }
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    when (page) {
                        Page.STATUS -> StatusScreen(status, receiver, feed, paths,
                            onStart = { engine.start() }, onStop = { engine.stop() },
                            onSetUpDriver = { engine.setUpDriver() },
                            onOpenSettings = { page = Page.SETTINGS },
                            onShowAircraft = { page = Page.AIRCRAFT }, usage = usage)
                        Page.RADAR -> RadarScreen(
                            receiver, running = status.state == FeederStatus.State.RUNNING, trails = trailMap,
                            coverage = history?.rangeNm, rangeNm = radarRangeNm,
                            onSetRange = { nm ->
                                radarRangeNm = nm
                                // Only the radar reads it: the engine reloads settings, but nothing restarts.
                                FeederSettings.update(paths) { it.copy(radarRangeNm = nm) }
                            },
                            showAirports = radarAirports,
                            onSetAirports = { on ->
                                radarAirports = on
                                FeederSettings.update(paths) { it.copy(radarAirports = on) }
                            },
                        )
                        Page.AIRCRAFT -> AircraftScreen(receiver)
                        Page.HISTORY -> HistoryScreen(
                            history,
                            statsPageUrl = remember(page) { StatsUploader.statsPageUrl(FeederSettings.load(paths).uuid) },
                            onResetRange = {
                                engine.resetRange()
                                historyRefresh++
                            },
                        )
                        Page.SETTINGS -> SettingsScreen(paths, updates)
                    }
                }
            }
        }
    }
}

/** The site's header: logo mark, "ADS-B Exchange", and the product name in green beneath. */
@Composable
internal fun BrandHeader() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Image(painterResource(Res.drawable.icon), contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("ADS-B Exchange", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Brand.Text)
            Text("Desktop Feeder", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Brand.Green)
        }
        if (BuildInfo.beta) {
            Spacer(Modifier.width(20.dp))
            // An outlined tag beside the name, in the product's green, as tall as it, where it's seen
            // at once: "Beta" until the full release.
            Text("BETA", fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 2.sp, color = Brand.Green,
                modifier = Modifier.border(1.5.dp, Brand.Green, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}
