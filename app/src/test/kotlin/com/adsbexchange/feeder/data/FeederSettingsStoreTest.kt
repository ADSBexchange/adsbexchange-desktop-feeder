package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/** settings.json and the feeder ID's backup in readsb\feeder-id (from the phone feeder, b55b130). */
class FeederSettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val paths by lazy { FeederPaths(tmp.root) }

    @Test
    fun firstRunCreatesADesktopUuidAndKeepsIt() {
        val first = FeederSettings.load(paths)
        assertTrue(first.uuid.endsWith(FeederSettings.DESKTOP_UUID_TAG))
        assertEquals(first.uuid, FeederSettings.load(paths).uuid)
        assertEquals(first.uuid, paths.uuidFile.readText())
    }

    @Test
    fun emptySettingsFileKeepsTheFeederId() {
        val uuid = FeederSettings.load(paths).uuid
        paths.settingsFile.writeText("") // what a power cut mid-write could leave behind
        assertEquals(uuid, FeederSettings.load(paths).uuid)
    }

    /** A settings.json that can't be understood is kept as a copy before it's replaced. */
    @Test
    fun damagedSettingsAreCopiedAside() {
        val uuid = FeederSettings.update(paths) { it.copy(name = "Attic") }.uuid
        paths.settingsFile.writeText("{\"name\": \"Attic\", \"loc")
        val s = FeederSettings.load(paths)
        assertEquals(uuid, s.uuid)
        val copies = tmp.root.listFiles { f -> f.name.startsWith("settings.json.unreadable-") }!!.toList()
        assertEquals(1, copies.size)
        assertEquals("{\"name\": \"Attic\", \"loc", copies[0].readText())
    }

    /** Another program holding settings.json for a moment: read again shortly, not replaced by defaults. */
    @Test
    fun aBrieflyLockedSettingsFileIsReadOnceItsFree() {
        FeederSettings.update(paths) { it.copy(name = "Attic", gain = "42.1") }
        val raf = RandomAccessFile(paths.settingsFile, "rw")
        val lock = raf.channel.lock()
        thread { Thread.sleep(300); lock.release(); raf.close() }
        val s = FeederSettings.load(paths)
        assertEquals("Attic", s.name)
        assertEquals("42.1", s.gain)
    }

    /** Still unreadable after the retries: defaults for now, keeping the feeder ID, and nothing written over it. */
    @Test
    fun aSettingsFileThatStaysLockedIsntWrittenOver() {
        val saved = FeederSettings.update(paths) { it.copy(name = "Attic", gain = "42.1") }
        val text = paths.settingsFile.readText()
        RandomAccessFile(paths.settingsFile, "rw").use { raf ->
            raf.channel.lock().use {
                val s = FeederSettings.load(paths)
                assertEquals("the feeder ID from readsb's copy", saved.uuid, s.uuid)
                assertEquals("", s.name)
                val changed = FeederSettings.update(paths) { it.copy(userStopped = true) }
                assertTrue(changed.userStopped)
            }
        }
        assertEquals("not written over", text, paths.settingsFile.readText())
        assertEquals("Attic", FeederSettings.load(paths).name)
    }

    @Test
    fun missingSettingsFileKeepsTheFeederId() {
        val uuid = FeederSettings.update(paths) { it.copy(name = "Attic") }.uuid
        paths.settingsFile.delete()
        val s = FeederSettings.load(paths)
        assertEquals(uuid, s.uuid)
        assertEquals("", s.name)
    }

    @Test
    fun blankUuidIsRecoveredAndSaved() {
        val uuid = FeederSettings.update(paths) { it.copy(name = "Attic") }.uuid
        paths.settingsFile.writeText(paths.settingsFile.readText().replace(uuid, ""))
        val s = FeederSettings.load(paths)
        assertEquals(uuid, s.uuid)
        assertEquals("Attic", s.name)
        assertTrue(paths.settingsFile.readText().contains(uuid))
    }

    @Test
    fun unreadableFeederIdGivesANewUuid() {
        paths.root.mkdirs()
        paths.uuidFile.writeText("not a uuid")
        val s = FeederSettings.load(paths)
        assertNotEquals("not a uuid", s.uuid)
        assertTrue(s.uuid.endsWith(FeederSettings.DESKTOP_UUID_TAG))
        assertEquals(s.uuid, paths.uuidFile.readText())
    }

    @Test
    fun updatesPersistWithoutLeftoverFiles() {
        FeederSettings.update(paths) { it.copy(userStopped = true, gain = "42.1") }
        val s = FeederSettings.load(paths)
        assertTrue(s.userStopped)
        assertEquals("42.1", s.gain)
        assertEquals(emptyList<String>(), tmp.root.walk().filter { it.name.endsWith(".tmp") }.map { it.name }.toList())
    }

    /** The bias tee puts DC on the antenna socket: off unless asked for, and only for a real stick. */
    @Test
    fun biasTeeIsOffByDefaultAndOnlyWhenAsked() {
        val s = FeederSettings.load(paths)
        assertEquals(false, s.biasTee)
        assertFalse("--enable-biastee" in s.readsbArgs(paths, gpsdPort = 40000, feedTo = null))
        val on = FeederSettings.update(paths) { it.copy(biasTee = true) }
        assertTrue(FeederSettings.load(paths).biasTee)
        assertTrue("--enable-biastee" in on.readsbArgs(paths, gpsdPort = 40000, feedTo = null))
        assertFalse("not with a recording", "--enable-biastee" in
            on.readsbArgs(paths, gpsdPort = 40000, feedTo = null, inputFile = File("capture.uc8")))
    }

    /** Ticked to start with, and for 0.5.20's never-ticked box; unticking it is kept. */
    @Test
    fun antennaBoxStartsTickedAndKeepsAnUntick() {
        assertTrue(FeederSettings.load(paths).antennaConfirmed)
        val uuid = FeederSettings.load(paths).uuid
        paths.settingsFile.writeText("{\"uuid\": \"$uuid\", \"antennaConfirmed\": false}")
        assertTrue("0.5.20's box is ignored", FeederSettings.load(paths).antennaConfirmed)
        FeederSettings.update(paths) { it.copy(antennaConfirmed = false) }
        assertFalse(FeederSettings.load(paths).antennaConfirmed)
    }

    @Test
    fun radarAirportsDefaultsOnAndPersistsOff() {
        assertTrue(FeederSettings.load(paths).radarAirports)
        FeederSettings.update(paths) { it.copy(radarAirports = false) }
        assertEquals(false, FeederSettings.load(paths).radarAirports)
    }

    /** Every writer's change survives, as with the window and the engine writing at once. */
    @Test
    fun concurrentUpdatesAreNotLost() {
        FeederSettings.load(paths)
        val threads = List(4) {
            thread {
                repeat(50) { FeederSettings.update(paths) { s -> s.copy(antennaAltitudeM = (s.antennaAltitudeM ?: 0.0) + 1) } }
            }
        }
        threads.forEach(Thread::join)
        assertEquals(200.0, FeederSettings.load(paths).antennaAltitudeM!!, 0.0)
    }
}
