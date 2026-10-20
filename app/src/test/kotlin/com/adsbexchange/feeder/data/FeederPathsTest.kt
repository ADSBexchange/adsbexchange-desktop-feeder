package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FeederPathsTest {
    private val env = mapOf("LOCALAPPDATA" to "C:\\Users\\someone\\AppData\\Local", "FEEDER_HOME" to "D:\\dev\\feeder-home")

    @Test
    fun developmentRunsCanMoveTheDataFolder() {
        val paths = FeederPaths.default(env::get) { null }
        assertEquals(File("D:\\dev\\feeder-home"), paths.base)
    }

    /** Security review O8: the installed app always uses the usual folder. */
    @Test
    fun theInstalledAppIgnoresFeederHome() {
        val installed = mapOf("jpackage.app-path" to "C:\\Program Files\\ADS-B Exchange Feeder\\ADS-B Exchange Feeder.exe")
        val paths = FeederPaths.default(env::get, installed::get)
        assertEquals(File("C:\\Users\\someone\\AppData\\Local", FeederPaths.APP_DIR), paths.base)
    }
}
