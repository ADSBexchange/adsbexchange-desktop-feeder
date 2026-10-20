package com.adsbexchange.feeder.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The licences screen has to cover everything in the MSI (unit tests run in app/). As the phone's. */
class LicensesTest {
    @Test
    fun everyLicenseFileIsBundled() {
        for (c in OSS_COMPONENTS) for (f in c.licenseFiles) {
            val text = licenseText(f)
            assertTrue("${c.name}: $f missing or empty", text != null && text.length > 900)
        }
    }

    @Test
    fun everyPinnedCLibraryIsListed() {
        val urls = Regex("""^fetch_dep\(\S+\s+(\S+)\s""", RegexOption.MULTILINE)
            .findAll(File("../native/cmake/deps.cmake").readText())
            .map { it.groupValues[1].removeSuffix(".git") }.toList()
        assertTrue("no fetch_dep lines found", urls.size >= 7)
        for (url in urls) assertTrue("$url is not on the licences screen", OSS_COMPONENTS.any { it.url == url })
    }

    @Test
    fun theAppComesFirstWithItsSource() {
        val app = OSS_COMPONENTS.first()
        assertTrue(app.license.startsWith("GPL-3.0") && app.url.startsWith("https://"))
    }

    /** Only public-domain code may come without a licence text. */
    @Test
    fun onlyPublicDomainHasNoText() {
        for (c in OSS_COMPONENTS.filter { it.licenseFiles.isEmpty() }) {
            assertTrue("${c.name} has no licence text", c.license.contains("Public domain", ignoreCase = true))
        }
    }
}
