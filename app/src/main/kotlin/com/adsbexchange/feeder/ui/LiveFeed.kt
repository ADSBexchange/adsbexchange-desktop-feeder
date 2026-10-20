package com.adsbexchange.feeder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsbexchange.feeder.data.FeedState
import com.adsbexchange.feeder.data.FeederSettings
import com.adsbexchange.feeder.data.FeederStatus
import com.adsbexchange.feeder.data.FeederStatus.State
import com.adsbexchange.feeder.data.MLAT_ACCURACY_M
import com.adsbexchange.feeder.data.MlatReadiness
import com.adsbexchange.feeder.data.ReceiverStatus
import com.adsbexchange.feeder.data.mlatUser
import com.adsbexchange.feeder.service.StatsUploader
import java.util.Locale

/** The feed card's derived numbers: what reached ADS-B Exchange, how fast, and recent message rates. */
data class FeedStats(
    /** readsb's bytes_out minus the Beast data our own MLAT client read on loopback. */
    val bytesSent: Long? = null,
    val uploadBytesPerSec: Double? = null,
    /** Messages per second, one sample a second, oldest first; up to [FeedStatsTracker.HISTORY_SECONDS]. */
    val rateHistory: List<Float> = emptyList(),
)

/**
 * Turns readsb's counters into the feed card's numbers, as the phone's MainViewModel does. Fed one
 * [ReceiverStatus] a second while the window is open; starts over with each readsb run.
 */
class FeedStatsTracker {
    private var runStartedMs: Long? = null
    private val rateHistory = ArrayDeque<Float>()
    private var lastStatsNow: Double? = null
    private var lastBytesSent: Long? = null
    private var uploadBytesPerSec: Double? = null
    private var lastSampleMs = 0L

    fun update(status: FeederStatus, r: ReceiverStatus): FeedStats {
        if (status.startedMs != runStartedMs) {
            runStartedMs = status.startedMs
            rateHistory.clear()
            lastStatsNow = null
            lastBytesSent = null
            uploadBytesPerSec = null
        }
        val running = status.state == State.RUNNING
        if (running) {
            val nowMs = System.currentTimeMillis()
            // Skip the first seconds of a run: the first rate readings cover a tiny interval and spike.
            val warmedUp = nowMs - (status.startedMs ?: 0L) > WARMUP_MS
            if (warmedUp && nowMs - lastSampleMs >= 900) {
                lastSampleMs = nowMs
                rateHistory.addLast((r.messagesPerSecond ?: 0.0).toFloat())
            }
            while (rateHistory.size > HISTORY_SECONDS) rateHistory.removeFirst()
        }
        // readsb refreshes its byte counter about every 10 s; derive a rate between refreshes.
        // Its bytes_out includes the Beast data our own MLAT client reads on loopback; take it out.
        val now = r.statsNow
        val bytes = r.bytesSent?.let { (it - (status.mlat?.loopbackBytes ?: 0)).coerceAtLeast(0) }
        if (now != null && bytes != null && now != lastStatsNow) {
            val prevNow = lastStatsNow
            val prevBytes = lastBytesSent
            if (prevNow != null && prevBytes != null && now > prevNow) {
                uploadBytesPerSec = (bytes - prevBytes).coerceAtLeast(0) / (now - prevNow)
            }
            lastStatsNow = now
            lastBytesSent = bytes
        }
        return FeedStats(
            bytesSent = bytes.takeIf { running },
            uploadBytesPerSec = uploadBytesPerSec.takeIf { running },
            rateHistory = if (running) rateHistory.toList() else emptyList(),
        )
    }

    companion object {
        const val HISTORY_SECONDS = 300
        private const val WARMUP_MS = 5_000L
    }
}

/**
 * Proof that this PC is feeding: the feed connection and how long it has been up, what readsb has
 * sent, what it's decoding, and MLAT, updated live. All counted on the PC itself.
 */
@Composable
fun LiveFeedCard(
    status: FeederStatus,
    r: ReceiverStatus,
    feed: FeedStats,
    settings: FeederSettings,
    onShowAircraft: () -> Unit,
    onOpenSettings: () -> Unit,
    onConfirmAntenna: () -> Unit,
) {
    SectionCard(if (status.feeding) "Live feed" else "Reception") {
        val now = System.currentTimeMillis()
        val (color, text) = when {
            !status.feeding -> Brand.Warn to "Not feeding ADS-B Exchange"
            status.feedState == FeedState.CONNECTED -> Brand.Green to buildString {
                append("Connected to ADS-B Exchange for ").append(Format.duration(now - (status.connectedSinceMs ?: now)))
                if (status.reconnects > 0) append(" · ").append(Format.plural(status.reconnects, "drop", "drops"))
            }
            status.feedState == FeedState.DISCONNECTED -> Brand.Red to "Not connected to ADS-B Exchange; retrying"
            else -> Brand.Warn to "Connecting to ADS-B Exchange"
        }
        StatusLine(color, text)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status.feeding) {
                StatTile(feed.uploadBytesPerSec?.let { Format.rate(it) } ?: "–", "sent now", Modifier.weight(1f))
            }
            StatTile(r.messagesPerSecond?.let { "%.0f".format(Locale.US, it) } ?: "–", "msg/s", Modifier.weight(1f))
            StatTile("${r.aircraft.size}", "aircraft", Modifier.weight(1f))
        }

        Sparkline(feed.rateHistory, capacity = FeedStatsTracker.HISTORY_SECONDS)
        Text("Messages per second, last 5 minutes (peak %.0f)".format(Locale.US, feed.rateHistory.maxOrNull() ?: 0f),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (status.feeding) DetailRow("Sent to ADS-B Exchange", feed.bytesSent?.let { Format.bytes(it) } ?: "–")
        DetailRow("Messages decoded", r.messagesTotal?.let { Format.count(it) } ?: "–")
        DetailRow("Aircraft seen this session", r.aircraftSeen?.let { Format.count(it) } ?: "–")
        DetailRow("Aircraft with a position now", "${r.withPosition}")
        DetailRow("Max range (last minute)", r.maxRangeNm?.let { "%.0f nm".format(Locale.US, it) } ?: "–")
        if (r.signalDbfs != null && r.noiseDbfs != null) {
            DetailRow("Signal / noise", "%.1f / %.1f dBFS".format(Locale.US, r.signalDbfs, r.noiseDbfs))
        }
        status.startedMs?.let { DetailRow("Receiver running for", Format.duration(now - it)) }
        if (r.receiverLat != null && r.receiverLon != null) {
            // About 1 km: enough to see readsb has the position. This screen is the one people
            // screenshot; Settings shows it exactly. (As the phone feeder, 478bade.)
            DetailRow("Receiver position (rounded)", "%.2f, %.2f".format(Locale.US, r.receiverLat, r.receiverLon))
        } else {
            WarningRow("No receiver position yet. Aircraft distances and range need one; set it in Settings.")
        }
        r.samplesDropped?.takeIf { it > 0 }?.let {
            WarningRow("$it samples dropped in the last minute. The PC or its USB connection can't keep up.")
        }
        if (status.feeding) MlatBlock(status, settings, onOpenSettings, onConfirmAntenna)
        if (status.feeding) StatsBlock(status, settings)
        if (status.feeding) {
            Text("Counted by the receiver on this PC. ADS-B Exchange knows this feed by its feeder ID, starting " +
                "${settings.uuid.take(8)} (full ID in Settings).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onShowAircraft) { Text("See aircraft") }
    }
}

/** What MLAT is waiting for, or null when there's nothing to say. */
fun mlatReadinessText(r: MlatReadiness, accuracyM: Float?): String? = when (r) {
    MlatReadiness.READY, MlatReadiness.OFF -> null
    MlatReadiness.NOT_FEEDING -> "MLAT needs feeding to ADS-B Exchange turned on."
    MlatReadiness.NO_LOCATION -> "MLAT is waiting for the receiver position. Enter it in Settings."
    // Typed-in positions count as exact, so this only shows for one that came from elsewhere.
    MlatReadiness.IMPRECISE -> "MLAT needs a position accurate to %.0f m; this one is ±%s m. Enter it in Settings."
        .format(Locale.US, MLAT_ACCURACY_M, accuracyM?.let { "%.0f".format(Locale.US, it) } ?: "?")
    MlatReadiness.NO_ALTITUDE -> "MLAT needs the antenna altitude. Enter it in Settings."
    MlatReadiness.NOT_CONFIRMED -> "MLAT is waiting for you to confirm the position entered is the antenna's."
}

/** Stats part of the Live feed card: whether the uploads behind the feeder's stats page are getting through. */
@Composable
private fun StatsBlock(status: FeederStatus, settings: FeederSettings) {
    val s = status.stats
    if (s == null) {
        if (!settings.statsUpload) DetailRow("Stats page", "Off")
        else if (status.statsPausedMetered) DetailRow("Stats page", "Paused on a metered connection")
        return
    }
    HorizontalDivider()
    Text("Stats", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    val now = System.currentTimeMillis()
    val (color, text) = when {
        s.error != null -> Brand.Red to "Stats not getting through (${s.error}); retrying"
        s.lastOkMs != null -> Brand.Green to "Stats sent to ADS-B Exchange ${Format.duration(now - s.lastOkMs)} ago"
        else -> Brand.Warn to "Starting to send stats…"
    }
    StatusLine(color, text)
    if (s.uploads > 0) DetailRow("Stats sent this session", Format.bytes(s.bytesSent))
    TextButton(onClick = { openUrl(StatsUploader.statsPageUrl(settings.uuid)) }) { Text("My stats page") }
}

/** MLAT part of the Live feed card: state, sync with nearby feeders, and what it's sending. */
@Composable
private fun MlatBlock(
    status: FeederStatus,
    settings: FeederSettings,
    onOpenSettings: () -> Unit,
    onConfirmAntenna: () -> Unit,
) {
    val m = status.mlat ?: return
    if (m.readiness == MlatReadiness.OFF) {
        DetailRow("MLAT", "Off")
        return
    }
    HorizontalDivider()
    Text("MLAT", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    val now = System.currentTimeMillis()
    val (color, text) = when {
        m.readiness != MlatReadiness.READY ->
            Brand.Warn to (mlatReadinessText(m.readiness, settings.location?.accuracyM) ?: "")
        m.state == "CONNECTED" -> Brand.Green to buildString {
            append("MLAT connected for ").append(Format.duration(now - (m.connectedSinceMs ?: now)))
            m.peerCount?.let { append(" · synced with ").append(Format.plural(it, "nearby feeder", "nearby feeders")) }
        }
        m.state == "DENIED" -> Brand.Red to "The MLAT server refused the connection: ${m.message ?: ""}"
        m.state == "DISCONNECTED" -> Brand.Red to "Not connected to the MLAT server; retrying"
        else -> Brand.Warn to "Connecting to the MLAT server…"
    }
    StatusLine(color, text)
    when (m.readiness) {
        MlatReadiness.NOT_CONFIRMED -> FilledTonalButton(onClick = onConfirmAntenna) {
            Text("The position is the antenna's")
        }
        MlatReadiness.NO_LOCATION, MlatReadiness.NO_ALTITUDE, MlatReadiness.IMPRECISE ->
            FilledTonalButton(onClick = onOpenSettings) { Text("Open Settings") }
        else -> Unit
    }
    if (m.readiness == MlatReadiness.READY && m.state == "CONNECTED") {
        DetailRow("Aircraft MLAT asked this feeder for", "${m.requested}")
        DetailRow("Sync pairs sent", Format.count(m.syncsSent))
        DetailRow("MLAT messages sent", Format.count(m.mlatSent))
        m.outlierPercent?.let { DetailRow("Sync outliers", "%.1f%%".format(Locale.US, it)) }
        DetailRow("Sent to the MLAT server", Format.bytes(m.bytesSent))
        DetailRow("Shown on the Leaderboard and MLAT maps as",
            if (settings.mlatPrivacy) "${settings.mlatUser()} (location hidden on the MLAT map)" else settings.mlatUser())
        Row {
            TextButton(onClick = { openUrl(Links.MLAT_SYNC) }) { Text("MLAT sync status") }
            if (!settings.mlatPrivacy) TextButton(onClick = { openUrl(Links.MLAT_MAP) }) { Text("MLAT map") }
        }
    }
}
