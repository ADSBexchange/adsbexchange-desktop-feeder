package com.adsbexchange.feeder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsbexchange.feeder.BuildInfo
import com.adsbexchange.feeder.service.UpdateChecker
import java.text.DateFormat
import java.util.Date

/**
 * A newer version, across the top of the window on every tab until the user downloads it or says
 * "Not now" (which hides it until a newer one still).
 */
@Composable
fun UpdateBanner(update: UpdateChecker.Update, onLater: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Brand.GreenDeep).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Version ${update.version} is available", color = Brand.Green, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
            Text("You have ${BuildInfo.version}. Download the installer and run it; your settings and history are kept.",
                style = MaterialTheme.typography.bodySmall, color = Brand.Text)
        }
        Button(onClick = { openUrl(update.url) },
            colors = ButtonDefaults.buttonColors(containerColor = Brand.Green, contentColor = Brand.Black)) { Text("Download") }
        TextButton(onClick = onLater) { Text("Not now") }
    }
}

/** Settings > About: the switch, "Check now", and how the last check went. */
@Composable
fun UpdateSettings(checker: UpdateChecker, enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    val state by checker.state.collectAsState()
    Toggle("Check for new versions", enabled, onEnabledChange)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { checker.checkNow() }, enabled = !state.checking) { Text("Check now") }
        val checked = state.checkedMs?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
        Text(
            when {
                state.checking -> "Checking…"
                state.available != null -> "Version ${state.available!!.version} is available."
                state.error != null -> "Couldn't check: ${state.error}"
                state.noReleases -> "No releases published yet (checked $checked)."
                checked != null -> "Up to date (checked $checked)."
                else -> "Looks once a day or so, on GitHub; it only tells GitHub this PC's address and the app's version."
            },
            style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary,
        )
    }
}
