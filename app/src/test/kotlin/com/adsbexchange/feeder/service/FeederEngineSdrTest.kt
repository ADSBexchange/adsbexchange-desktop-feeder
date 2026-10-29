package com.adsbexchange.feeder.service

import com.adsbexchange.feeder.data.FeederPaths
import com.adsbexchange.feeder.data.FeederSettings
import com.adsbexchange.feeder.data.FeederStatus
import com.adsbexchange.feeder.data.FeederStatus.DriverSetup
import com.adsbexchange.feeder.data.FeederStatus.Reason
import com.adsbexchange.feeder.data.FeederStatus.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The engine waits for a usable stick before it starts readsb, and the driver setup gets it going.
 * Stand-ins for the stick check, the driver install and readsb: no hardware, no admin rights.
 */
class FeederEngineSdrTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var paths: FeederPaths
    private lateinit var engine: FeederEngine
    @Volatile private var scan = SdrScan(emptyList())
    @Volatile private var installOutcome = SdrDriver.Outcome.INSTALLED
    private val readsbLookups = AtomicInteger()
    /** A stand-in for the PC's power: on mains unless a test unplugs it, so the tests don't depend on this PC's. */
    private val onBattery = AtomicBoolean(false)
    private val power = object : PowerConditions {
        override fun onBattery() = this@FeederEngineSdrTest.onBattery.get()
        override fun metered() = false
    }
    private val installs = AtomicInteger()

    private val tvTuner = SdrStick("0bda", "2838", "Generic RTL2832U OEM", "Bulk-In, Interface (Interface 0)", "RTL2832UBDA", winusb = false)

    @Before
    fun setUp() {
        paths = FeederPaths(tmp.newFolder("app"))
        // Belt and braces: nothing here may reach ADS-B Exchange.
        FeederSettings.update(paths) { it.copy(feedAdsbx = false) }
        // Most tests click the button themselves; driverSetupStartsByItself checks the automatic setup.
        engine = newEngine(autoSetUp = false)
    }

    private fun newEngine(autoSetUp: Boolean) = FeederEngine(
        paths,
        // readsb "missing": the engine reaching this point is what's being tested.
        findReadsb = { readsbLookups.incrementAndGet(); null },
        sdrProbe = { scan },
        installDriver = {
            installs.incrementAndGet()
            if (installOutcome == SdrDriver.Outcome.INSTALLED) scan = SdrScan(listOf(tvTuner.copy(driver = "WinUSB", winusb = true)))
            installOutcome
        },
        autoSetUpDriver = autoSetUp,
        conditions = power,
    )

    private fun restartEngine(auto: Boolean) {
        engine.shutdown()
        engine = newEngine(auto)
    }

    @After
    fun tearDown() = engine.shutdown()

    private fun awaitStatus(what: String, test: (FeederStatus) -> Boolean): FeederStatus {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            engine.status.value.let { if (test(it)) return it }
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what; status ${engine.status.value}")
    }

    /**
     * An unexpected exception on the engine thread used to end its task silently, leaving feeding
     * at "Starting" for ever. Now it's logged, shown, and the run tried
     * again; here the stick check throws at the start and again in the scheduled retry.
     */
    @Test
    fun anUnexpectedErrorIsLoggedAndTheRunTriedAgain() {
        engine.shutdown()
        val probes = AtomicInteger()
        engine = FeederEngine(
            paths,
            findReadsb = { readsbLookups.incrementAndGet(); null },
            sdrProbe = { if (probes.incrementAndGet() <= 2) throw IllegalStateException("stick check blew up") else scan },
            autoSetUpDriver = false,
            restartDelayMs = { _, _ -> 1_000L },
            conditions = power,
        )
        engine.start()
        awaitStatus("the error shown") { it.state == State.STARTING && it.reason == Reason.ENGINE_ERROR }
        awaitStatus("running again: waiting for a stick") { it.state == State.STARTING && it.reason == Reason.NO_SDR }
        assertEquals(3, probes.get())
        val log = paths.logFile.readLines()
        assertEquals(log.joinToString("\n"), 2, log.count { "engine: error while" in it && "IllegalStateException: stick check blew up" in it })
        assertTrue(log.any { "engine: error while starting again" in it })
        assertTrue(log.any { "engine: starting again in 1 s" in it })
    }

    @Test
    fun noStickNoReadsb() {
        engine.start()
        awaitStatus("no SDR") { it.state == State.STARTING && it.reason == Reason.NO_SDR }
        Thread.sleep(4_000) // past the next check
        assertEquals("readsb must not start without a stick", 0, readsbLookups.get())
        assertEquals("logged once, not every check", 1,
            paths.logFile.readLines().count { "no RTL-SDR plugged in" in it })
    }

    @Test
    fun stickPluggedInLaterStartsReadsb() {
        engine.start()
        awaitStatus("no SDR") { it.reason == Reason.NO_SDR }
        scan = SdrScan(listOf(tvTuner.copy(driver = "WinUSB", winusb = true)))
        awaitStatus("readsb start attempted") { it.state == State.STOPPED && it.reason == Reason.START_FAILED }
        assertEquals(1, readsbLookups.get())
    }

    @Test
    fun tvTunerDriverWaitsForSetupThenStarts() {
        scan = SdrScan(listOf(tvTuner))
        engine.start()
        val waiting = awaitStatus("needs driver") { it.reason == Reason.NEEDS_DRIVER }
        assertEquals("Generic RTL2832U OEM", waiting.stickName)
        assertEquals("RTL2832UBDA", waiting.stickDriver)
        assertEquals(0, readsbLookups.get())

        engine.setUpDriver()
        // Starts straight after the install, without waiting for the next check.
        awaitStatus("readsb start attempted") { it.reason == Reason.START_FAILED }
        assertEquals(1, installs.get())
        assertEquals(1, readsbLookups.get())
        assertEquals(DriverSetup.IDLE, engine.status.value.driverSetup)
    }

    /** Plug and play: a stick on the TV-tuner driver gets WinUSB without a click, then feeds. */
    @Test
    fun driverSetupStartsByItself() {
        restartEngine(auto = true)
        scan = SdrScan(listOf(tvTuner))
        engine.start()
        awaitStatus("readsb start attempted") { it.reason == Reason.START_FAILED }
        assertEquals(1, installs.get())
        assertEquals(1, readsbLookups.get())
    }

    /** A "No" at the permission prompt isn't asked again on every check. */
    @Test
    fun automaticSetupIsTriedOnce() {
        restartEngine(auto = true)
        scan = SdrScan(listOf(tvTuner))
        installOutcome = SdrDriver.Outcome.CANCELLED
        engine.start()
        awaitStatus("cancelled") { it.driverSetup == DriverSetup.CANCELLED }
        Thread.sleep(4_000) // past the next check
        assertEquals(1, installs.get())
        assertEquals(Reason.NEEDS_DRIVER, engine.status.value.reason)
    }

    @Test
    fun declinedPermissionPromptIsReported() {
        scan = SdrScan(listOf(tvTuner))
        installOutcome = SdrDriver.Outcome.CANCELLED
        engine.start()
        awaitStatus("needs driver") { it.reason == Reason.NEEDS_DRIVER }
        engine.setUpDriver()
        val s = awaitStatus("cancelled") { it.driverSetup == DriverSetup.CANCELLED }
        assertEquals(Reason.NEEDS_DRIVER, s.reason)
        assertEquals(0, readsbLookups.get())
        assertTrue(File(paths.root, "readsb.log").readText().contains("driver setup: cancelled"))
    }

    /**
     * The stick is pulled out while feeding: readsb exits (a stand-in here: Windows' where.exe,
     * which rejects readsb's arguments and exits at once), the engine finds no stick and says so,
     * then says "feeding again" once it's back and readsb runs.
     */
    @Test
    fun unpluggingWhileFeedingAlertsAndRecovers() {
        engine.shutdown()
        val fakeReadsb = File(System.getenv("SystemRoot"), "System32\\where.exe")
        val events = CopyOnWriteArrayList<FeederEngine.Event>()
        engine = FeederEngine(paths, findReadsb = { fakeReadsb }, sdrProbe = { scan }, installDriver = { SdrDriver.Outcome.FAILED },
            conditions = power)
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { engine.events.collect { events += it } }
        Thread.sleep(300) // subscribed before anything can be emitted
        try {
            scan = SdrScan(listOf(tvTuner.copy(driver = "WinUSB", winusb = true)))
            engine.start()
            awaitStatus("running") { it.state == State.RUNNING }
            scan = SdrScan(emptyList()) // pulled out; readsb's exit is noticed on the next tick
            awaitStatus("waiting for the stick") { it.reason == Reason.NO_SDR }
            assertEquals(listOf<FeederEngine.Event>(FeederEngine.Event.StickUnplugged), events.toList())
            assertTrue(paths.logFile.readText().contains("the RTL-SDR was unplugged"))

            scan = SdrScan(listOf(tvTuner.copy(driver = "WinUSB", winusb = true))) // back in
            awaitStatus("running again") { it.state == State.RUNNING }
            // Emitted with the status change, collected on another thread a moment later.
            val deadline = System.currentTimeMillis() + 2_000
            while (events.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(FeederEngine.Event.FeedRecovered, events.getOrNull(1))
        } finally {
            scope.cancel()
        }
    }

    /**
     * readsb keeps stopping with the stick still plugged in (a failing stick or cable, or another
     * program holding it): after 5 exits the engine alerts once and keeps restarting it. Once a run
     * stays up (8 s here, so a slow start on a busy PC isn't mistaken for one; 10 minutes in the app)
     * it's over, with one "feeding again".
     *
     * Stand-ins for readsb: where.exe, which rejects readsb's arguments and exits at once, restarted
     * 0.1 s after each exit; then cmd.exe, which ignores them and waits on the stdin the engine
     * keeps open, i.e. stays up.
     */
    @Test
    fun crashLoopAlertsOnceAndKeepsRetrying() {
        engine.shutdown()
        val system32 = File(System.getenv("SystemRoot"), "System32")
        val healthy = AtomicBoolean(false)
        val events = CopyOnWriteArrayList<FeederEngine.Event>()
        engine = FeederEngine(
            paths, sdrProbe = { scan }, installDriver = { SdrDriver.Outcome.FAILED },
            findReadsb = { readsbLookups.incrementAndGet(); File(system32, if (healthy.get()) "cmd.exe" else "where.exe") },
            restartDelayMs = { _, _ -> 100 }, crashLoopClearMs = 8_000, conditions = power,
        )
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { engine.events.collect { events += it } }
        Thread.sleep(300)
        fun count(e: FeederEngine.Event) = events.count { it == e }
        try {
            scan = SdrScan(listOf(tvTuner.copy(driver = "WinUSB", winusb = true)))
            engine.start()
            var deadline = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < deadline && !engine.status.value.sdrFailing) Thread.sleep(100)
            assertTrue("flagged: ${engine.status.value}", engine.status.value.sdrFailing)
            // From the log, since the next restart may already be under way: 5 starts, then the flag.
            val log = paths.logFile.readLines()
            val flaggedAt = log.indexOfFirst { "still retrying" in it }
            assertTrue("flag logged", flaggedAt >= 0)
            assertEquals("flagged on the 5th exit", 5, log.take(flaggedAt).count { "starting readsb" in it })

            // It keeps retrying, and doesn't alert again.
            // (Waited for rather than slept: starting the stand-in can be slow on a busy machine.)
            val before = readsbLookups.get()
            val restartsBy = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < restartsBy && readsbLookups.get() < before + 2) Thread.sleep(100)
            assertTrue("restarts carry on: $before -> ${readsbLookups.get()}", readsbLookups.get() >= before + 2)
            assertTrue("still flagged", engine.status.value.sdrFailing)
            assertEquals("one alert per episode", 1, count(FeederEngine.Event.SdrFailing))

            // The stick works again: a run that stays up ends it.
            healthy.set(true)
            deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && engine.status.value.sdrFailing) Thread.sleep(100)
            assertFalse("cleared: ${engine.status.value}", engine.status.value.sdrFailing)
            assertEquals(State.RUNNING, engine.status.value.state)
            deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline && count(FeederEngine.Event.FeedRecovered) == 0) Thread.sleep(20)
            assertEquals("one 'feeding again'", 1, count(FeederEngine.Event.FeedRecovered))
            assertEquals(1, count(FeederEngine.Event.SdrFailing))
        } finally {
            scope.cancel()
        }
    }

    /**
     * Saving a new gain in Settings restarts readsb at once with it (readsb reads the gain only at
     * start), and that doesn't count towards a crash loop. Stand-in readsb: cmd.exe, which stays up.
     */
    @Test
    fun newGainRestartsReadsb() {
        engine.shutdown()
        val cmd = File(System.getenv("SystemRoot"), "System32\\cmd.exe")
        engine = FeederEngine(paths, findReadsb = { readsbLookups.incrementAndGet(); cmd }, sdrProbe = { scan },
            installDriver = { SdrDriver.Outcome.FAILED }, conditions = power)
        scan = SdrScan(listOf(tvTuner.copy(driver = "WinUSB", winusb = true)))
        engine.start()
        awaitStatus("running") { it.state == State.RUNNING }
        assertEquals(1, readsbLookups.get())

        Thread.sleep(1_100) // a settings file newer than the one the engine read
        FeederSettings.update(paths) { it.copy(gain = "38.6") }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && readsbLookups.get() < 2) Thread.sleep(50)
        awaitStatus("running again") { it.state == State.RUNNING }
        assertEquals("restarted once", 2, readsbLookups.get())
        val log = paths.logFile.readLines()
        assertTrue(log.any { "gain changed to 38.6; restarting readsb" in it })
        assertTrue("started with the new gain", log.last { "starting readsb" in it }.contains("--gain 38.6"))
        assertFalse(engine.status.value.sdrFailing)
        Thread.sleep(3_000)
        assertEquals("and only once", 2, readsbLookups.get())
    }

    /**
     * A laptop on battery: feeding pauses (readsb isn't started, or is stopped within a tick) and
     * starts again by itself on mains. Settings > Keep feeding on battery overrides it.
     */
    @Test
    fun pausesOnBatteryAndResumesOnMains() {
        engine.shutdown()
        val cmd = File(System.getenv("SystemRoot"), "System32\\cmd.exe")
        engine = FeederEngine(paths, findReadsb = { readsbLookups.incrementAndGet(); cmd }, sdrProbe = { scan },
            installDriver = { SdrDriver.Outcome.FAILED }, conditions = power, powerPollMs = 200)
        scan = SdrScan(listOf(tvTuner.copy(driver = "WinUSB", winusb = true)))

        onBattery.set(true)
        engine.start()
        awaitStatus("paused on battery") { it.state == State.STARTING && it.reason == Reason.ON_BATTERY }
        Thread.sleep(600)
        assertEquals("readsb not started on battery", 0, readsbLookups.get())

        onBattery.set(false)
        awaitStatus("running on mains") { it.state == State.RUNNING }
        assertEquals(1, readsbLookups.get())

        // Unplugged while feeding: paused within a tick, and readsb stopped.
        onBattery.set(true)
        awaitStatus("paused again") { it.reason == Reason.ON_BATTERY }
        assertEquals(null, engine.readsbPid)
        val log = paths.logFile.readLines()
        assertEquals("said once per pause", 2, log.count { "on battery: feeding paused" in it })
        assertTrue(log.any { "on mains power again; feeding" in it })

        // The setting keeps it going on battery.
        Thread.sleep(1_100) // a settings file newer than the one the engine read
        FeederSettings.update(paths) { it.copy(feedOnBattery = true) }
        awaitStatus("running on battery") { it.state == State.RUNNING }
    }

    @Test
    fun statsPauseOnlyOnAMeteredConnection() {
        val on = FeederSettings(uuid = "00000000-0000-4000-8000-000000000000", name = "", location = null)
        assertTrue(FeederEngine.statsPausedMetered(metered = true, settings = on, feeding = true))
        assertFalse("not metered", FeederEngine.statsPausedMetered(false, on, true))
        assertFalse("the user said send anyway", FeederEngine.statsPausedMetered(true, on.copy(statsOnMetered = true), true))
        assertFalse("not feeding", FeederEngine.statsPausedMetered(true, on, false))
    }

    @Test
    fun stopWhileWaitingStopsChecking() {
        engine.start()
        awaitStatus("no SDR") { it.reason == Reason.NO_SDR }
        engine.stop()
        assertEquals(State.STOPPED, engine.status.value.state)
        scan = SdrScan(listOf(tvTuner.copy(driver = "WinUSB", winusb = true)))
        Thread.sleep(4_000)
        assertEquals(0, readsbLookups.get())
        assertFalse(engine.status.value.state == State.RUNNING)
    }
}
