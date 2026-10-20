package com.adsbexchange.feeder.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class SdrDriverTest {
    @get:Rule val tmp = TemporaryFolder()

    private val exe = System.getProperty("feeder.rtlsdrDriver")?.let(::File)

    @Test
    fun parsesTheHelpersList() {
        val scan = SdrDriver.parse(
            """
            {"vid":"0bda","pid":"2838","composite":true,"mi":0,"name":"Generic RTL2832U OEM","desc":"Bulk-In, Interface (Interface 0)","driver":"RTL2832UBDA","winusb":false}
            {"vid":"0bda","pid":"2832","composite":false,"mi":-1,"name":"Generic RTL2832U","desc":"RTL2832U","driver":null,"winusb":false}
            not json
            """.trimIndent()
        )
        assertEquals(2, scan.sticks.size)
        assertEquals("Generic RTL2832U OEM", scan.sticks[0].name)
        assertEquals("RTL2832UBDA", scan.sticks[0].driver)
        assertEquals(null, scan.sticks[1].driver)
        assertTrue(scan.needsDriver)
        assertFalse(scan.ready)
    }

    @Test
    fun oneStickWithWinusbIsEnough() {
        val scan = SdrDriver.parse(
            """{"vid":"0bda","pid":"2838","composite":true,"mi":0,"name":"Generic RTL2832U OEM","desc":"x","driver":"WinUSB","winusb":true}"""
        )
        assertTrue(scan.ready)
        assertFalse(scan.needsDriver)
        assertFalse(SdrDriver.parse("").needsDriver)
    }

    @Test
    fun runWithTimeoutReturnsTheOutput() {
        val (code, out) = SdrDriver.runWithTimeout(listOf("cmd.exe", "/c", "echo hello"), 10_000)!!
        assertEquals(0, code)
        assertEquals("hello", out.trim())
    }

    /** A helper that hangs while holding its output open is killed at the timeout. */
    @Test
    fun runWithTimeoutGivesUpOnAHungHelper() {
        val began = System.nanoTime()
        // ping keeps writing for about 30 s.
        val result = SdrDriver.runWithTimeout(listOf("ping", "-n", "30", "127.0.0.1"), 1_000)
        val tookMs = (System.nanoTime() - began) / 1_000_000
        assertEquals(null, result)
        assertTrue("gave up after $tookMs ms", tookMs < 10_000)
    }

    @Test
    fun theInstalledAppIgnoresDevelopmentOverrides() {
        val dev = mapOf("feeder.rtlsdrDriver" to "C:\\dev\\rtlsdr-driver.exe")
        assertEquals("C:\\dev\\rtlsdr-driver.exe", developmentOverride("feeder.rtlsdrDriver") { dev[it] })
        val installed = dev + ("jpackage.app-path" to "C:\\Program Files\\ADS-B Exchange Feeder\\ADS-B Exchange Feeder.exe")
        assertEquals(null, developmentOverride("feeder.rtlsdrDriver") { installed[it] })
        assertEquals(null, developmentOverride("feeder.readsb") { installed[it] })
    }

    /** The real helper lists this PC's sticks (none, usually) without admin rights. */
    @Test
    fun helperListsSticks() {
        assumeTrue("rtlsdr-driver.exe not built (tools/build-native.ps1)", exe?.isFile == true)
        assertNotNull(SdrDriver.scan(exe!!))
    }

    /**
     * The driver package the helper would install, for a stand-in RTL2832U: Windows 8+ inbox
     * WinUSB for interface 0 only, no co-installers (see native/patches/libwdi-inbox-winusb.patch).
     */
    @Test
    fun driverPackageIsInboxWinusbForInterface0() {
        assumeTrue("rtlsdr-driver.exe not built (tools/build-native.ps1)", exe?.isFile == true)
        val dir = tmp.newFolder("pkg")
        val p = ProcessBuilder(exe!!.absolutePath, "prepare", dir.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        assumeTrue("running as admin: prepare refuses (it would sign)", p.exitValue() != 4)
        assertEquals(out, 0, p.exitValue())

        assertEquals(setOf("rtlsdr_0bda_2838.inf", "installer_x64.exe"), dir.list()!!.toSet())
        val bytes = File(dir, "rtlsdr_0bda_2838.inf").readBytes()
        assertTrue("UTF-16LE BOM", bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
        val inf = String(bytes, Charsets.UTF_16LE)
        assertTrue(inf, "DeviceID   = \"VID_0BDA&PID_2838&MI_00\"" in inf)
        assertTrue(inf, Regex("""Include\s*=\s*winusb\.inf\s+Needs\s*=\s*WINUSB\.NT\s""").containsMatchIn(inf))
        assertTrue(inf, "WINUSB.NT.Services" in inf)
        assertFalse(inf, "CoInstaller" in inf)
        assertFalse(inf, "KmdfService" in inf)
    }
}
