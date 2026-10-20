package com.adsbexchange.feeder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI
import java.util.Locale

// The phone feeder's building blocks (ui/Components.kt and ui/FeedWidgets.kt there), so the two
// apps' screens look and read the same.

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(color, CircleShape))
}

/** A dot and a line of text: the state of something, coloured by how it's going. */
@Composable
fun StatusLine(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Something the user should fix, in the error colour. */
@Composable
fun WarningRow(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        StatusDot(MaterialTheme.colorScheme.error, Modifier.padding(top = 5.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A label on the left and its value on the right. A long value wraps within 60% of the width, so
 * the label always keeps the rest. Unbounded, a value such as a feeder name plus "(location hidden
 * on the MLAT map)" took the whole row and squeezed the label to one letter per line (seen on the phone).
 */
@Composable
fun DetailRow(label: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val valueMax = maxWidth * 0.6f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = valueMax))
        }
    }
}

/**
 * A minimal area chart: [values] oldest to newest across the full width, scaled to the data's own
 * maximum. Flat, no axes; the caption beside it carries the numbers.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    capacity: Int = values.size,
) {
    val baseline = MaterialTheme.colorScheme.outline
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        drawLine(baseline, start = Offset(0f, size.height), end = Offset(size.width, size.height), strokeWidth = 1f)
        if (values.size < 2) return@Canvas
        val max = values.max().coerceAtLeast(1f)
        val slots = capacity.coerceAtLeast(values.size) - 1
        val step = size.width / slots
        // Right-align so the newest sample is always at the right edge.
        val x0 = size.width - step * (values.size - 1)
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = x0 + i * step
            val y = size.height - (v / max) * (size.height - 4f)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(x0, size.height)
            close()
        }
        drawPath(area, color.copy(alpha = 0.18f))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

object Format {
    fun bytes(b: Long): String = when {
        b < 1_000 -> "$b B"
        b < 1_000_000 -> "%.1f kB".format(Locale.US, b / 1e3)
        b < 1_000_000_000 -> "%.1f MB".format(Locale.US, b / 1e6)
        else -> "%.2f GB".format(Locale.US, b / 1e9)
    }

    /** Memory as Task Manager shows it: megabytes of 1,048,576 bytes. */
    fun memory(b: Long): String = "%.0f MB".format(Locale.US, b / 1_048_576.0)

    fun rate(bytesPerSec: Double): String = when {
        bytesPerSec < 1_000 -> "%.0f B/s".format(Locale.US, bytesPerSec)
        else -> "%.1f kB/s".format(Locale.US, bytesPerSec / 1e3)
    }

    fun count(n: Long): String = "%,d".format(Locale.US, n)

    /** For tiles: "9,876", "12.3 k", "4.56 M", "1.20 B". */
    fun compact(n: Long): String = when {
        n < 10_000 -> count(n)
        n < 1_000_000 -> "%.1f k".format(Locale.US, n / 1e3)
        n < 1_000_000_000 -> "%.2f M".format(Locale.US, n / 1e6)
        else -> "%.2f B".format(Locale.US, n / 1e9)
    }

    /** "45 s", "12 min", "2 h 13 min", "3 d 4 h". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            s < 86_400 -> "${s / 3600} h ${s % 3600 / 60} min"
            else -> "${s / 86_400} d ${s % 86_400 / 3600} h"
        }
    }

    /** "1 drop", "3 drops". */
    fun plural(n: Int, one: String, other: String) = "$n ${if (n == 1) one else other}"
}

object Links {
    /** From the ADSBx feeder setup: MLAT sync status (by feeder name) and the MLAT map. */
    const val MLAT_SYNC = "https://map.adsbexchange.com/sync/"
    const val MLAT_MAP = "https://map.adsbexchange.com/mlat-map/"
    /** Linked from adsbexchange.com's Feeder Hub as "View leaderboard". */
    const val LEADERBOARD = "https://globe.adsbexchange.com/leaderboard/"
    /** Support: the invite to ADS-B Exchange's Discord server, the one on adsbexchange.com (never expires). */
    const val DISCORD = "https://discord.gg/ad8SSMpWvH"
    /** An aircraft on the globe, by its Mode S address (as the phone's Aircraft tab). */
    fun aircraft(hex: String) = "https://globe.adsbexchange.com/?icao=$hex"
}

/** Opens a web page in the default browser. */
fun openUrl(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

/** Opens a folder in Explorer. */
fun openFolder(dir: java.io.File) {
    runCatching { dir.mkdirs(); Desktop.getDesktop().open(dir) }
}
