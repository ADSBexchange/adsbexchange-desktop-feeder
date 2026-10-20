package com.adsbexchange.feeder.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileInputStream
import kotlin.concurrent.thread

class FilesTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * Windows won't replace a file another program has open, as an antivirus scanner or the search
     * indexer briefly does after each write. The save waits for it rather than failing (seen as a
     * lost settings save in FeederSettingsStoreTest).
     */
    @Test
    fun replacesAFileThatIsBrieflyOpenElsewhere() {
        val f = tmp.newFile("settings.json").apply { writeText("old") }
        val reader = FileInputStream(f) // Java opens files without sharing delete: the replace is refused
        thread { Thread.sleep(150); reader.close() }
        writeAtomically(f, "new")
        assertEquals("new", f.readText())
    }
}
