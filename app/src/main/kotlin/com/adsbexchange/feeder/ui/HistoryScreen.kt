package com.adsbexchange.feeder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsbexchange.feeder.data.DayStats
import com.adsbexchange.feeder.data.History
import com.adsbexchange.feeder.data.HistoryRecorder
import com.adsbexchange.feeder.data.Milestones
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lifetime contribution to ADS-B Exchange, from the engine's [History] (history.json). The phone
 * feeder's History tab (as of 42c1b0f: milestones and the range plot), worded for a PC. Unlike the
 * phone, it keeps the Leaderboard link: the Leaderboard works in a desktop browser.
 *
 * @param statsPageUrl the feeder's stats page on ADS-B Exchange, while stats are sent; else null.
 */
@Composable
fun HistoryScreen(
    history: History?,
    statsPageUrl: String?,
    onResetRange: () -> Unit,
) {
    val h = history?.takeIf { it.firstFedMs != null }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (h == null) {
            SectionCard("Your contribution") {
                Text("Your history starts the first time this PC feeds ADS-B Exchange. Everything it contributes " +
                    "from then on adds up here.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Totals(h)
            MilestonesCard(h)
            LastDays(h)
            RangeCard(h.rangeNm, h.rangeSinceMs, onReset = onResetRange)
            Records(h)
            if (h.mlatSyncs > 0 || h.mlatMessages > 0) {
                SectionCard("MLAT") {
                    DetailRow("Sync pairs sent", Format.count(h.mlatSyncs))
                    DetailRow("MLAT messages sent", Format.count(h.mlatMessages))
                    Text("Sync pairs and MLAT messages help ADS-B Exchange locate aircraft that don't broadcast " +
                        "their own position.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SectionCard(null) {
            Text("Counted on this PC while it's connected to ADS-B Exchange. It's kept when the app is updated " +
                "or uninstalled. ADS-B Exchange's own figures are on its Leaderboard" +
                (if (statsPageUrl != null) " and your stats page." else "."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { openUrl(Links.LEADERBOARD) }) { Text("View the Leaderboard") }
                statsPageUrl?.let { url -> TextButton(onClick = { openUrl(url) }) { Text("My stats page") } }
            }
        }
    }
}

@Composable
private fun Totals(h: History) {
    SectionCard("Your contribution") {
        Text("Feeding ADS-B Exchange since ${dateOf(h.firstFedMs!!)}", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Format.compact(h.messages), "messages received", Modifier.weight(1f))
            StatTile(Format.compact(h.uniqueAircraft.toLong()), "different aircraft", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Format.duration(h.feedingMs), "time feeding", Modifier.weight(1f))
            StatTile(Format.bytes(h.bytesSent), "data sent", Modifier.weight(1f))
        }
    }
}

/** Each series' latest milestone and progress to the next. */
@Composable
private fun MilestonesCard(h: History) {
    SectionCard("Milestones") {
        Milestones.progress(h).forEach { p ->
            val reached = p.reached
            val next = p.next
            Text(
                when {
                    next == null -> "All reached: ${milestoneLabel(reached!!)}"
                    reached != null -> "Reached ${milestoneLabel(reached)}"
                    else -> "First up: ${milestoneLabel(next)}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (next != null) {
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                if (reached != null) {
                    Text("Next: ${milestoneLabel(next)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun milestoneLabel(m: Milestones.Milestone): String = when (m.kind) {
    Milestones.Kind.MESSAGES -> "${roundNumber(m.target)} messages"
    Milestones.Kind.AIRCRAFT -> "${roundNumber(m.target)} different aircraft"
    Milestones.Kind.FEEDING -> (m.target / HistoryRecorder.DAY_MS).toInt().let { "$it ${if (it == 1) "day" else "days"} feeding" }
}

/** "10,000", "1 million", "1 billion": milestones are round numbers. */
private fun roundNumber(n: Long): String = when {
    n >= 1_000_000_000 && n % 1_000_000_000 == 0L -> "${n / 1_000_000_000} billion"
    n >= 1_000_000 && n % 1_000_000 == 0L -> "${n / 1_000_000} million"
    else -> Format.count(n)
}

@Composable
private fun RangeCard(rangeNm: List<Double>, sinceMs: Long?, onReset: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val any = rangeNm.any { it > 0 }
    SectionCard("Reception range") {
        RangePlot(rangeNm)
        Text(
            if (any && sinceMs != null) "The furthest aircraft heard in each direction since ${dateOf(sinceMs)}. " +
                "Short spokes show where buildings or terrain block the antenna."
            else "Builds up as aircraft are heard.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (any) TextButton(onClick = { confirm = true }) { Text("Reset") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reset the range plot?") },
            text = { Text("It starts again from now, for example after you've moved the antenna. The rest of your history stays.") },
            confirmButton = { TextButton(onClick = { confirm = false; onReset() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Polar plot of [rangeNm], north up: one spoke per sector, joined into a shape, inside range rings.
 * No map underneath, so it doesn't show where the receiver is.
 */
@Composable
private fun RangePlot(rangeNm: List<Double>) {
    val fill = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 11.sp)
    // At most 480 dp across: the window is wide, and a bigger plot says nothing more.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(1f).padding(8.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2 - 14.dp.toPx()
            val max = rangeNm.maxOrNull()?.takeIf { it > 0 } ?: 100.0
            val step = RING_STEPS_NM.firstOrNull { max / it <= 4 } ?: RING_STEPS_NM.last()
            val outer = ceil(max / step).coerceAtLeast(1.0) * step
            fun point(bearingDeg: Double, nm: Double): Offset {
                val r = (nm / outer * radius).toFloat()
                val a = Math.toRadians(bearingDeg)
                return Offset(center.x + r * sin(a).toFloat(), center.y - r * cos(a).toFloat())
            }
            var ringNm = step
            while (ringNm <= outer + 1e-9) {
                val r = (ringNm / outer * radius).toFloat()
                drawCircle(ring, r, center, style = Stroke(1.dp.toPx()))
                drawText(measurer, "%.0f nm".format(Locale.US, ringNm), point(45.0, ringNm) + Offset(4f, -14f), labelStyle)
                ringNm += step
            }
            listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, bearing) ->
                val layout = measurer.measure(label, labelStyle)
                val p = point(bearing, outer * 1.09)
                drawText(layout, topLeft = p - Offset(layout.size.width / 2f, layout.size.height / 2f))
            }
            if (rangeNm.none { it > 0 }) return@Canvas
            val sector = 360.0 / rangeNm.size
            val shape = Path()
            rangeNm.forEachIndexed { i, nm ->
                val p = point((i + 0.5) * sector, nm)
                if (i == 0) shape.moveTo(p.x, p.y) else shape.lineTo(p.x, p.y)
            }
            shape.close()
            drawPath(shape, fill.copy(alpha = 0.25f))
            drawPath(shape, fill, style = Stroke(2.dp.toPx()))
        }
    }
}

private val RING_STEPS_NM = listOf(10.0, 25.0, 50.0, 100.0, 150.0, 200.0)

@Composable
private fun LastDays(h: History) {
    val today = HistoryRecorder.epochDay(System.currentTimeMillis())
    val byDay = h.days.associateBy { it.day }
    val last = (today - 29..today).map { byDay[it] ?: DayStats(it) }
    SectionCard("Last 30 days") {
        DayBars(last.map { it.messages.toFloat() })
        val t = last.last()
        Text("Today: ${Format.count(t.messages)} messages, ${Format.count(t.aircraft.toLong())} aircraft",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Records(h: History) {
    SectionCard("Records") {
        DetailRow("Longest unbroken run", Format.duration(h.longestRunMs))
        val far = h.furthestNm
        val farMs = h.furthestMs
        if (far != null && farMs != null) {
            DetailRow("Furthest aircraft", "%.0f nm · %s".format(Locale.US, far, dateOf(farMs)))
        }
        h.busiestDay?.let { d ->
            DetailRow("Busiest day", "${Format.compact(d.messages)} messages · ${dateOfDay(d.day)}")
        }
    }
}

/** One bar per value, oldest on the left; empty days show as a gap on the baseline. */
@Composable
private fun DayBars(values: List<Float>) {
    val bar = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val slot = size.width / values.size
        values.forEachIndexed { i, v ->
            if (v <= 0f) return@forEachIndexed
            val height = (v / max) * (size.height - 2f)
            drawRect(bar, Offset(i * slot + slot * 0.15f, size.height - height), Size(slot * 0.7f, height))
        }
    }
}

private fun dateOf(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

/** A local epoch day as a date: midnight UTC of that day, formatted in UTC. */
private fun dateOfDay(day: Int): String = DateFormat.getDateInstance(DateFormat.MEDIUM)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(Date(day * HistoryRecorder.DAY_MS))
