package com.adsbexchange.feeder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsbexchange.feeder.BuildInfo
import com.adsbexchange.feeder.data.FeedState
import com.adsbexchange.feeder.data.FeedWatchdog
import com.adsbexchange.feeder.data.FeederPaths
import com.adsbexchange.feeder.data.FeederSettings
import com.adsbexchange.feeder.data.FeederStatus
import com.adsbexchange.feeder.data.FeederStatus.DriverSetup
import com.adsbexchange.feeder.data.FeederStatus.Reason
import com.adsbexchange.feeder.data.FeederStatus.State
import com.adsbexchange.feeder.data.ReceiverStatus
import com.adsbexchange.feeder.data.RestartBudget
import com.adsbexchange.feeder.data.StationLocation
import com.adsbexchange.feeder.service.Autostart
import com.adsbexchange.feeder.service.ResourceMonitor
import com.adsbexchange.feeder.service.StatsUploader
import com.adsbexchange.feeder.service.UpdateChecker
import java.util.Locale

// ---- Status --------------------------------------------------------------------------------

@Composable
fun StatusScreen(
    status: FeederStatus,
    receiver: ReceiverStatus?,
    feed: FeedStats,
    paths: FeederPaths,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSetUpDriver: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowAircraft: () -> Unit,
    usage: ResourceMonitor.Usage? = null,
) {
    // Re-read with each status change, and after "The position is the antenna's" below.
    var settingsVersion by remember { mutableStateOf(0) }
    val settings = remember(status, settingsVersion) { FeederSettings.load(paths) }
    val (headline, detail, color) = describe(status)
    val needsDriver = status.state == State.STARTING && status.reason == Reason.NEEDS_DRIVER
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color)
                Spacer(Modifier.width(10.dp))
                Text(headline, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            if (detail != null) Text(detail, color = Brand.TextSecondary)
            if (needsDriver) driverSetupText(status.driverSetup)?.let { Text(it, color = Brand.Warn) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needsDriver) {
                    val running = status.driverSetup == DriverSetup.RUNNING
                    Button(onClick = onSetUpDriver, enabled = !running) {
                        Text(if (running) "Setting up the driver…" else "Set up the driver")
                    }
                }
                if (status.state == State.STOPPED) Button(onClick = onStart) { Text("Start feeding") }
                else OutlinedButton(onClick = onStop) { Text("Stop") }
            }
        }
        // A tray notification is easy to miss, so a problem stays here while it lasts.
        status.feedProblem?.let { p ->
            SectionCard(null) {
                WarningRow(when (p) {
                    FeedWatchdog.Problem.FEED_DOWN -> "This PC hasn't been able to send to ADS-B Exchange for 15 minutes or " +
                        "more. Check its internet connection; the feeder keeps retrying."
                    FeedWatchdog.Problem.NO_AIRCRAFT -> "No aircraft heard for 2 hours or more. Check that the antenna is " +
                        "connected and the RTL-SDR is firmly plugged in."
                })
            }
        }
        if (settings.location == null) {
            SectionCard("Set your receiver's position") {
                Text("readsb needs it to place aircraft, and MLAT needs it accurate to 15 m.", color = Brand.TextSecondary)
                TextButton(onClick = onOpenSettings) { Text("Open Settings") }
            }
        }
        if (status.state == State.RUNNING && receiver != null) {
            LiveFeedCard(
                status, receiver, feed, settings,
                onShowAircraft = onShowAircraft,
                onOpenSettings = onOpenSettings,
                onConfirmAntenna = {
                    FeederSettings.update(paths) { it.copy(antennaConfirmed = true) }
                    settingsVersion++
                },
            )
        }
        val lastError = receiver?.lastError
        if (lastError != null && status.state != State.RUNNING) {
            SectionCard("Last message from readsb") {
                Text(lastError, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Brand.TextSecondary)
            }
        }
        if (usage != null) ResourcesCard(usage, feeding = status.state == State.RUNNING && status.feeding)
        TextButton(onClick = { openFolder(paths.root) }) { Text("Open the log folder") }
    }
}

/**
 * What the feeder costs this PC (see [ResourceMonitor]). People hesitate to leave something
 * running in the background; the numbers show how little it uses, the same way Task Manager would.
 */
@Composable
private fun ResourcesCard(usage: ResourceMonitor.Usage, feeding: Boolean) {
    SectionCard("Desktop resources") {
        DetailRow("CPU", usage.cpuPercent?.let {
            if (it < 0.1) "under 0.1% of this PC" else "%.1f%% of this PC".format(Locale.US, it)
        } ?: "Measuring…")
        DetailRow("Memory", usage.memoryBytes?.let { Format.memory(it) } ?: "–")
        if (feeding) {
            DetailRow("Sent to ADS-B Exchange", usage.sentBytesPerSec?.let { rate ->
                "${Format.rate(rate)} · about ${Format.bytes(usage.sentBytesPerMonth!!.toLong())} a month"
            } ?: "Measuring…")
        }
        Text("The feeder and its decoder (readsb) together, measured as Task Manager does, with CPU as a share of " +
            "the whole PC. The figures include this window.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class Headline(val text: String, val detail: String?, val color: Color)

private fun describe(s: FeederStatus): Headline = when {
    // A crash loop: shown through its restarts (including the brief runs between them) until a run
    // stays up or the feed connects. readsb's last line is in the card below.
    s.sdrFailing && s.state != State.STOPPED && s.feedState != FeedState.CONNECTED -> Headline(
        "The RTL-SDR keeps failing",
        "The receiver stopped ${RestartBudget.MAX_EXITS} times in ${RestartBudget.WINDOW_MS / 60_000} minutes. Close " +
            "any other program that uses the stick (such as SDR#), and check its cable or try another USB port. " +
            "The feeder keeps trying every minute and starts feeding by itself once the stick works.",
        Brand.Red)
    else -> describeState(s)
}

private fun describeState(s: FeederStatus): Headline = when (s.state) {
    State.RUNNING -> when {
        !s.feeding -> Headline("Receiving (not feeding)", "Feeding ADS-B Exchange is off in Settings.", Brand.Warn)
        s.feedState == FeedState.CONNECTED -> Headline("Feeding ADS-B Exchange", null, Brand.Green)
        else -> Headline("Connecting to ADS-B Exchange…", null, Brand.Warn)
    }
    State.STARTING -> when (s.reason) {
        Reason.NO_SDR -> Headline("Plug in your RTL-SDR",
            "No RTL-SDR stick found. Feeding starts by itself when you plug one in.", Brand.Warn)
        Reason.NEEDS_DRIVER -> Headline("Set up your RTL-SDR",
            "Windows is using ${s.stickDriver?.let { "the \"$it\" driver" } ?: "no driver"} for your " +
                "${s.stickName ?: "RTL-SDR"}. The feeder needs Windows' WinUSB driver on it instead. " +
                "Windows will ask for your permission once. Other programs that use the stick as a TV tuner won't work with it afterwards.",
            Brand.Warn)
        Reason.ON_BATTERY -> Headline("Paused: on battery",
            "Feeding pauses while this PC runs on its battery, and starts again by itself when it's plugged in. " +
                "To keep feeding on battery, turn on Keep feeding on battery in Settings.", Brand.Warn)
        Reason.READSB_EXITED -> Headline("Restarting the receiver…", "readsb stopped (code ${s.exitCode}). Starting it again shortly.", Brand.Warn)
        Reason.ENGINE_ERROR -> Headline("Restarting after an error…",
            "Something unexpected stopped feeding. It starts again by itself shortly; the details are in the log.", Brand.Warn)
        else -> Headline("Starting…", null, Brand.Warn)
    }
    State.STOPPED -> when (s.reason) {
        Reason.START_FAILED -> Headline("Can't start the receiver", "readsb.exe is missing or couldn't run.", Brand.Red)
        Reason.ENGINE_ERROR -> Headline("Stopped after an error",
            "Something unexpected stopped the feeder. Start tries again; the details are in the log.", Brand.Red)
        Reason.USER_STOPPED -> Headline("Stopped", "Not feeding until you start it again.", Brand.TextSecondary)
        else -> Headline("Stopped", null, Brand.TextSecondary)
    }
}

private fun driverSetupText(d: DriverSetup): String? = when (d) {
    DriverSetup.CANCELLED -> "The driver wasn't set up: Windows needs your permission to install it."
    DriverSetup.FAILED -> "Setting up the driver failed. The log folder has the details."
    else -> null
}

// ---- Aircraft ------------------------------------------------------------------------------

@Composable
fun AircraftScreen(receiver: ReceiverStatus?) {
    val list = receiver?.aircraft.orEmpty()
    if (list.isEmpty()) {
        Text("No aircraft yet.", color = Brand.TextSecondary)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text("Click an aircraft to see it on the ADS-B Exchange map.", color = Brand.TextSecondary,
                fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            AircraftRow("Callsign", "Hex", "Altitude", "Speed", "Distance", header = true)
        }
        items(list, key = { it.hex }) { a ->
            AircraftRow(
                a.callsign ?: "–", a.hex,
                if (a.onGround) "ground" else a.altitudeFt?.let { "$it ft" } ?: "–",
                a.groundSpeedKt?.let { "%.0f kt".format(Locale.US, it) } ?: "–",
                a.distanceNm?.let { "%.1f nm".format(Locale.US, it) } ?: "–",
                // As on the phone: the whole row opens the aircraft on the globe.
                onClick = { openUrl(Links.aircraft(a.hex)) },
            )
        }
    }
}

@Composable
private fun AircraftRow(vararg cells: String, header: Boolean = false, onClick: (() -> Unit)? = null) {
    val link = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
    Row(Modifier.fillMaxWidth().then(link).padding(vertical = 2.dp)) {
        cells.forEachIndexed { i, text ->
            Text(text, Modifier.weight(1f),
                // The callsign in the link colour, so the rows read as links.
                color = when {
                    header -> Brand.TextSecondary
                    i == 0 && onClick != null -> Brand.Green
                    else -> Brand.Text
                },
                fontWeight = if (header) FontWeight.Medium else FontWeight.Normal)
        }
    }
}

// ---- Settings ------------------------------------------------------------------------------

@Composable
fun SettingsScreen(paths: FeederPaths, updates: UpdateChecker? = null) {
    val saved = remember { FeederSettings.load(paths) }
    var lat by remember { mutableStateOf(saved.location?.lat?.toString() ?: "") }
    var lon by remember { mutableStateOf(saved.location?.lon?.toString() ?: "") }
    var alt by remember { mutableStateOf((saved.antennaAltitudeM ?: saved.location?.altitudeM)?.toString() ?: "") }
    var confirmed by remember { mutableStateOf(saved.antennaConfirmed) }
    var name by remember { mutableStateOf(saved.name) }
    var feed by remember { mutableStateOf(saved.feedAdsbx) }
    var mlat by remember { mutableStateOf(saved.mlatEnabled) }
    var privacy by remember { mutableStateOf(saved.mlatPrivacy) }
    var startWithWindows by remember { mutableStateOf(saved.startWithWindows) }
    var feedAlerts by remember { mutableStateOf(saved.feedAlerts) }
    var statsUpload by remember { mutableStateOf(saved.statsUpload) }
    var feedOnBattery by remember { mutableStateOf(saved.feedOnBattery) }
    var statsOnMetered by remember { mutableStateOf(saved.statsOnMetered) }
    var gain by remember { mutableStateOf(saved.gain) }
    var biasTee by remember { mutableStateOf(saved.biasTee) }
    var message by remember { mutableStateOf<String?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    if (showLicenses) return LicensesScreen(onBack = { showLicenses = false })

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Receiver position") {
            Text("Where the antenna is. It stays on this PC, except that MLAT sends it to ADS-B Exchange (whose " +
                "public MLAT map shows it rounded to about 1 km, or not at all while MLAT privacy is on).",
                color = Brand.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lat, { lat = it }, Modifier.weight(1f), label = { Text("Latitude") }, singleLine = true)
                OutlinedTextField(lon, { lon = it }, Modifier.weight(1f), label = { Text("Longitude") }, singleLine = true)
                OutlinedTextField(alt, { alt = it }, Modifier.weight(1f), label = { Text("Antenna altitude (m)") }, singleLine = true)
            }
            Text("To find them: right-click the antenna's spot in an online map; most show the latitude and " +
                "longitude. Altitude is the ground's height above sea level plus the antenna's height above the ground.",
                color = Brand.TextSecondary, fontSize = 13.sp)
            TextButton(onClick = { openUrl("https://www.openstreetmap.org/") }) { Text("Open a map in the browser") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(confirmed, { confirmed = it })
                Text("This position is within 15 m of the antenna (needed for MLAT)")
            }
        }
        SectionCard("Receiver gain") {
            GainSelect(gain) { gain = it }
            Text("Auto suits most setups: readsb adjusts the gain as conditions change. A lower setting can help " +
                "near an airport, where strong signals swamp the stick; a higher one can pick up more distant " +
                "aircraft. Saving a new gain restarts the receiver, which takes a few seconds.",
                color = Brand.TextSecondary, fontSize = 13.sp)
            Toggle("Power an amplifier through the antenna cable (bias tee)", biasTee) { biasTee = it }
            Text("Only for an amplifier (LNA) at the antenna that takes its power through the cable, with a stick " +
                "that can supply it (the RTL-SDR Blog V3 or V4, or a Nooelec SMArTee, say). Without it, such an " +
                "amplifier passes little or no signal. Leave it off otherwise: it puts about 4.5 V on the antenna " +
                "socket, which can damage an antenna or splitter that shorts it. The ADS-B Exchange stick doesn't " +
                "need it. Saving a change restarts the receiver.",
                color = Brand.TextSecondary, fontSize = 13.sp)
        }
        SectionCard("Feeding") {
            Toggle("Feed ADS-B Exchange", feed) { feed = it }
            Toggle("Take part in MLAT", mlat) { mlat = it }
            Toggle("MLAT privacy (hide my location on the public MLAT map)", privacy) { privacy = it }
            Text("Still takes part in MLAT. Off, ADS-B Exchange's MLAT map shows your feeder at an approximate " +
                "location (rounded to about 1 km). On, it shows no location, though your feeder name can still appear " +
                "in its lists.", color = Brand.TextSecondary, fontSize = 13.sp)
            Toggle("Start with Windows (in the notification area)", startWithWindows) { startWithWindows = it }
            Toggle("Alert me if feeding stops", feedAlerts) { feedAlerts = it }
            Text("If this PC can't reach ADS-B Exchange for 15 minutes, hears no aircraft for 2 hours, or the " +
                "RTL-SDR is unplugged.", color = Brand.TextSecondary, fontSize = 13.sp)
            Toggle("Send stats for my feeder's stats page (recommended)", statsUpload) { statsUpload = it }
            Text("Every 5 seconds, the aircraft this PC hears go to ADS-B Exchange, as with its Raspberry Pi feeders' " +
                "stats package. That's roughly 1-10 GB a month, depending on how many aircraft are in range.",
                color = Brand.TextSecondary, fontSize = 13.sp)
            TextButton(onClick = { openUrl(StatsUploader.statsPageUrl(saved.uuid)) }) { Text("Open my stats page") }
            Toggle("Send stats on metered connections", statsOnMetered) { statsOnMetered = it }
            Text("Windows treats phone hotspots, and connections you've set as metered, this way. Off, the stats " +
                "pause on them and feeding carries on.", color = Brand.TextSecondary, fontSize = 13.sp)
            Toggle("Keep feeding on battery", feedOnBattery) { feedOnBattery = it }
            Text("For laptops. Off, feeding pauses while the PC runs on its battery, and starts again by itself " +
                "when it's plugged in.", color = Brand.TextSecondary, fontSize = 13.sp)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Feeder name (optional)") })
            Text("Shown on ADS-B Exchange's Leaderboard and MLAT maps, through MLAT. Leave it blank to stay " +
                "anonymous (MLAT then uses desktopfeeder-${saved.uuid.take(8)}).",
                color = Brand.TextSecondary, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val la = lat.trim().replace(',', '.').toDoubleOrNull()
                val lo = lon.trim().replace(',', '.').toDoubleOrNull()
                val al = alt.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                val blank = lat.isBlank() && lon.isBlank()
                val location = if (blank) null else la?.let { a -> lo?.let { o -> StationLocation.manual(a, o, al) } }
                message = when {
                    !blank && location == null -> "Latitude must be -90 to 90 and longitude -180 to 180."
                    alt.isNotBlank() && al == null -> "Altitude must be a number of metres."
                    else -> {
                        val before = FeederSettings.load(paths)
                        FeederSettings.update(paths) {
                            it.copy(
                                location = location, antennaAltitudeM = al, antennaConfirmed = confirmed,
                                name = FeederSettings.sanitizeName(name), feedAdsbx = feed,
                                mlatEnabled = mlat, mlatPrivacy = privacy, startWithWindows = startWithWindows,
                                feedAlerts = feedAlerts, statsUpload = statsUpload, gain = gain, biasTee = biasTee,
                                feedOnBattery = feedOnBattery, statsOnMetered = statsOnMetered,
                            )
                        }
                        Autostart.apply(startWithWindows)
                        when {
                            gain != before.gain && biasTee != before.biasTee -> "Saved. The receiver is restarting with the new gain and bias tee."
                            gain != before.gain -> "Saved. The receiver is restarting with the new gain."
                            biasTee != before.biasTee -> "Saved. The receiver is restarting with the bias tee ${if (biasTee) "on" else "off"}."
                            else -> "Saved. Feeding on/off applies the next time feeding starts."
                        }
                    }
                }
            }) { Text("Save") }
            message?.let { Text(it, color = Brand.TextSecondary) }
        }
        HelpSection(paths)
        AboutSection(saved.uuid, onShowLicenses = { showLicenses = true }) {
            if (updates != null) {
                var check by remember { mutableStateOf(saved.checkForUpdates) }
                UpdateSettings(updates, check) { on ->
                    check = on
                    FeederSettings.update(paths) { it.copy(checkForUpdates = on) }
                }
            }
        }
    }
}

/**
 * As the phone's Settings > Help: support is ADS-B Exchange's Discord server. Unlike the phone's
 * diagnostics, the log folder holds the location (json/gpsd.json, outline.json, aircraft.json's
 * distances), so the text asks for readsb.log alone, and privately: it has the feeder ID.
 */
@Composable
private fun HelpSection(paths: FeederPaths) {
    SectionCard("Help") {
        Text("Having trouble? Ask on ADS-B Exchange's Discord server. If you're asked for the log, send " +
            "readsb.log privately rather than in a channel: it includes this feeder's ID. Don't share the whole " +
            "log folder, as other files in it include the receiver's location.",
            style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
        Text("Using an amplifier powered through the antenna cable, and seeing few or no aircraft? Turn on " +
            "\"Power an amplifier through the antenna cable\" under Receiver gain.",
            style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openUrl(Links.DISCORD) }) { Text("Get help on Discord") }
            OutlinedButton(onClick = { openFolder(paths.root) }) { Text("Open the log folder") }
        }
    }
}

/**
 * The receiver gain as a list rather than typed in: "auto", or one of the tuner's own steps. A value
 * set some other way (by hand in settings.json) stays in the list, so opening Settings never changes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GainSelect(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf("auto") + FeederSettings.RTL_SDR_GAINS +
        listOf(value).filter { it != "auto" && it !in FeederSettings.RTL_SDR_GAINS }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            gainLabel(value), {}, readOnly = true, singleLine = true, label = { Text("Gain") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.width(300.dp).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { g ->
                DropdownMenuItem(text = { Text(gainLabel(g)) }, onClick = { onChange(g); open = false })
            }
        }
    }
}

private fun gainLabel(g: String): String = when (g) {
    "auto" -> "Auto (recommended)"
    FeederSettings.RTL_SDR_GAINS.last() -> "$g dB (maximum)"
    else -> "$g dB"
}

/** As the phone's Settings > About: version, what's shared, and the GPL's notices and source. */
@Composable
private fun AboutSection(uuid: String, onShowLicenses: () -> Unit, updates: @Composable () -> Unit = {}) {
    SectionCard("About") {
        Text("Version ${BuildInfo.version}${if (BuildInfo.beta) " beta" else ""} (${BuildInfo.commit})",
            style = MaterialTheme.typography.bodyMedium)
        if (BuildInfo.beta) {
            Text("This is a beta: it works, and it's being tested before a full release. If something doesn't, " +
                "tell us on Discord (Help, above).", style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
        }
        Text("Feeder ID: $uuid", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Brand.TextSecondary)
        Text("What ADS-B Exchange receives", style = MaterialTheme.typography.titleSmall)
        Text("ADS-B Exchange receives the aircraft messages this PC hears, with a feeder ID that marks this as a " +
            "desktop feeder. With MLAT on, it also receives the position you entered, the antenna altitude and your " +
            "feeder name, which MLAT needs to work out aircraft positions. ADS-B Exchange's public MLAT map lists " +
            "feeders by name; with privacy off, it also shows your feeder at an approximate location, rounded to about " +
            "1 km, never the precise one. Privacy is on to start with. With stats on, it receives the list of aircraft this PC hears every 5 seconds, " +
            "for your stats page; each aircraft's distance and direction from the receiver are only included while " +
            "MLAT is on, since they reveal where the receiver is. The position isn't sent to anyone other than " +
            "ADS-B Exchange.",
            style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
        // The GPL's "appropriate legal notices": copyright, no warranty, the licence, and the source.
        Text(OSS_COMPONENTS.first().copyright, style = MaterialTheme.typography.bodySmall)
        Text("This app is free software: you can redistribute it and/or modify it under the terms of the GNU " +
            "General Public License, version 3 or (at your option) any later version. It comes with ABSOLUTELY NO " +
            "WARRANTY. It's built on readsb, librtlsdr, libusb, zstd, zlib, wepoll, libwdi and a port of " +
            "mlat-client; see Open-source licences.",
            style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openUrl(BuildInfo.sourceForThisBuild) }) { Text("Source code") }
            OutlinedButton(onClick = onShowLicenses) { Text("Open-source licences") }
        }
        updates()
    }
}

@Composable
internal fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(value, onChange)
        Text(label)
    }
}
