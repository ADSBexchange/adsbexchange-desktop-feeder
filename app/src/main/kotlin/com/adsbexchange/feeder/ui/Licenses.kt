package com.adsbexchange.feeder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adsbexchange.feeder.BuildInfo
import java.io.File

/**
 * An open-source component of the app. Its licence texts are bundled in resources/licenses/, as the
 * GPL and the other licences require them to be shipped with the app.
 */
internal data class OssComponent(
    val name: String,
    /** What it does in this app. */
    val use: String,
    val license: String,
    val copyright: String,
    val url: String,
    /** Under resources/licenses/; "java:" names a file in the Java runtime's own legal/ folder. */
    val licenseFiles: List<String>,
)

/**
 * Everything in the MSI: the app, the C libraries from native/cmake/deps.cmake, the Java libraries
 * in runtimeClasspath (all Apache-2.0 apart from JNA's choice and org.json), the Java runtime, and
 * the font. As the phone feeder's licences screen, with the desktop's components.
 */
internal val OSS_COMPONENTS = listOf(
    // The app itself, first. Placeholder copyright holder: for Legal to confirm before release (as on the phone).
    OssComponent(
        "ADS-B Exchange Desktop Feeder", "This app", "GPL-3.0-or-later",
        "Copyright © 2026 ADS-B Exchange", BuildInfo.sourceUrl, listOf("GPL-3.0.txt"),
    ),
    OssComponent(
        "readsb", "Decodes the aircraft messages (readsb.exe)", "GPL-3.0-or-later",
        "Copyright © 2020 Matthias Wirth, © 2019 Michael Wolf, and others; includes dump1090 code " +
            "© 2012 Salvatore Sanfilippo (BSD)",
        "https://github.com/wiedehopf/readsb", listOf("readsb-NOTICE.txt", "GPL-3.0.txt"),
    ),
    OssComponent(
        "mlat-client", "MLAT (a Kotlin port)", "GPL-3.0-or-later",
        "Copyright © 2015 Oliver Jowett",
        "https://github.com/adsbexchange/mlat-client", listOf("GPL-3.0.txt"),
    ),
    OssComponent(
        "librtlsdr (RTL-SDR Blog)", "Talks to the RTL-SDR stick", "GPL-2.0-or-later",
        "Copyright © 2012-2014 Steve Markgraf, © 2012 Dimitri Stolnikov, and others",
        "https://github.com/rtlsdrblog/rtl-sdr-blog", listOf("GPL-2.0.txt"),
    ),
    OssComponent(
        "libusb", "USB access for librtlsdr", "LGPL-2.1-or-later", // gitleaks:allow (licence details, not a key)
        "Copyright © 2001 Johannes Erdfelt, © 2007-2009 Daniel Drake, © 2010-2012 Peter Stuge, " +
            "© 2008-2016 Nathan Hjelm, and others",
        "https://github.com/libusb/libusb", listOf("LGPL-2.1.txt"),
    ),
    OssComponent(
        "Zstandard", "Compression in readsb", "BSD-3-Clause",
        "Copyright © Meta Platforms, Inc. and affiliates",
        "https://github.com/facebook/zstd", listOf("zstd-BSD.txt"),
    ),
    OssComponent(
        "zlib", "Compression in readsb", "Zlib",
        "Copyright © 1995-2026 Jean-loup Gailly and Mark Adler",
        "https://github.com/madler/zlib", listOf("zlib.txt"),
    ),
    OssComponent(
        "wepoll", "Network event handling for readsb on Windows", "BSD-2-Clause",
        "Copyright © 2012-2020 Bert Belder",
        "https://github.com/piscisaureus/wepoll", listOf("wepoll-BSD.txt"),
    ),
    OssComponent(
        "libwdi", "Installs the stick's WinUSB driver (rtlsdr-driver.exe; modified for Windows' built-in WinUSB)",
        "LGPL-3.0-or-later",
        "Copyright © 2010-2023 Pete Batard, and others",
        "https://github.com/pbatard/libwdi", listOf("LGPL-3.0.txt", "GPL-3.0.txt"),
    ),
    OssComponent(
        "Compose Multiplatform, Kotlin, kotlinx, AndroidX, JetBrains Runtime API, JSpecify",
        "The app's window, language and runtime libraries", "Apache-2.0",
        "Copyright © JetBrains s.r.o. and Kotlin contributors; © The Android Open Source Project; the JSpecify authors",
        "https://www.jetbrains.com/compose-multiplatform/", listOf("Apache-2.0.txt"),
    ),
    OssComponent(
        "Skiko, with Skia and ICU data", "Draws the window", "Apache-2.0; Skia BSD-3-Clause; ICU Unicode-3.0",
        "Copyright © JetBrains s.r.o.; © 2011 Google Inc.; © Unicode, Inc.",
        "https://github.com/JetBrains/skiko", listOf("Apache-2.0.txt", "Skia-BSD.txt", "ICU-Unicode.txt"),
    ),
    OssComponent(
        "JNA", "Windows calls (driver setup, connection checks, keeping the PC awake)",
        "Apache-2.0 (or LGPL-2.1-or-later)",
        "Copyright © 2007 Timothy Wall, and the JNA contributors",
        "https://github.com/java-native-access/jna", listOf("Apache-2.0.txt"),
    ),
    OssComponent(
        "JSON-java (org.json)", "Reading and writing settings and status", "Public domain",
        "Sean Leary and contributors (public domain)",
        "https://github.com/stleary/JSON-java", emptyList(),
    ),
    OssComponent(
        "Java runtime (Eclipse Temurin 17, OpenJDK)", "Runs the app", "GPL-2.0 with the Classpath Exception",
        "Copyright © Oracle and/or its affiliates, and others",
        "https://adoptium.net", listOf("java:java.base/LICENSE", "java:java.base/ASSEMBLY_EXCEPTION"),
    ),
    OssComponent(
        "DM Sans", "The font (as adsbexchange.com)", "OFL-1.1",
        "Copyright © 2014 The DM Sans Project Authors",
        "https://github.com/googlefonts/dm-fonts", listOf("DM-Sans-OFL.txt"),
    ),
    OssComponent(
        "OurAirports data", "The airports on the radar", "Public domain (the Unlicense)",
        "OurAirports contributors", "https://ourairports.com/data/", listOf("OurAirports-Unlicense.txt"),
    ),
)

/** Reads one of [OssComponent.licenseFiles]. */
internal fun licenseText(file: String): String? = runCatching {
    if (file.startsWith("java:")) {
        File(System.getProperty("java.home"), "legal/" + file.removePrefix("java:")).readText()
    } else {
        OssComponent::class.java.getResourceAsStream("/licenses/$file")?.bufferedReader()?.use { it.readText() }
    }
}.getOrNull()

/** The list of [OSS_COMPONENTS]; clicking one shows its licence text. In place of Settings, with a way back. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    var selected by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val current = selected
            Text(if (current == null) "Open-source licences" else OSS_COMPONENTS[current].name,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (current != null) selected = null else onBack() }) { Text("Back") }
        }
        val index = selected
        if (index == null) ComponentList { selected = it } else LicenseView(OSS_COMPONENTS[index])
    }
}

@Composable
private fun ComponentList(onSelect: (Int) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This app is built from these open-source components. Select one to read its licence.",
            style = MaterialTheme.typography.bodyMedium)
        OSS_COMPONENTS.forEachIndexed { i, c ->
            SectionCard(null, Modifier.clickable { onSelect(i) }) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${c.use} · ${c.license}", style = MaterialTheme.typography.bodyMedium)
                Text(c.copyright, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LicenseView(c: OssComponent) {
    val text = remember(c) { c.licenseFiles.mapNotNull { licenseText(it) }.joinToString("\n\n\n") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${c.license} · ${c.copyright}", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { openUrl(c.url) }) { Text("Project page") }
        if (text.isNotEmpty()) Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
    }
}
