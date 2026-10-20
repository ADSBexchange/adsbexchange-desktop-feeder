package com.adsbexchange.feeder

import java.util.Properties

/** The app's version, the git commit it was built from, and where its source is (written by the build: generateBuildInfo). */
object BuildInfo {
    private val props = Properties().apply {
        BuildInfo::class.java.getResourceAsStream("/build-info.properties")?.use { load(it) }
    }
    val version: String = props.getProperty("version") ?: "dev"
    val commit: String = props.getProperty("commit") ?: "unknown"
    val sourceUrl: String = props.getProperty("source") ?: "https://github.com/ADSBexchange/adsbexchange-desktop-feeder"
    /** The GitHub repository ("owner/name") whose releases the update check reads. */
    val releasesRepo: String = props.getProperty("releases") ?: "ADSBexchange/adsbexchange-desktop-feeder"
    /** A beta build: the app says so (app/build.gradle.kts, betaRelease). Development runs count as beta. */
    val beta: Boolean = props.getProperty("beta")?.toBooleanStrictOrNull() ?: true
    /** "ADS-B Exchange Feeder", with " (beta)" while [beta]: the window title and tray tooltip. */
    val appTitle: String get() = "ADS-B Exchange Feeder" + if (beta) " (beta)" else ""

    /**
     * The source of exactly this build, as the GPL asks: the commit's tree when the build came from
     * a clean commit, otherwise the repository.
     */
    val sourceForThisBuild: String
        get() = if (commit.matches(Regex("[0-9a-f]{7,40}"))) "$sourceUrl/tree/$commit" else sourceUrl
}
