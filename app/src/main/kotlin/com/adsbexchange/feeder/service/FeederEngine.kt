package com.adsbexchange.feeder.service

import com.adsbexchange.feeder.data.FeedLogWatcher
import com.adsbexchange.feeder.data.FeedState
import com.adsbexchange.feeder.data.FeedWatchdog
import com.adsbexchange.feeder.data.FeederPaths
import com.adsbexchange.feeder.data.FeederSettings
import com.adsbexchange.feeder.data.FeederStatus
import com.adsbexchange.feeder.data.FeederStatus.DriverSetup
import com.adsbexchange.feeder.data.FeederStatus.Reason
import com.adsbexchange.feeder.data.FeederStatus.State
import com.adsbexchange.feeder.data.HistoryRecorder
import com.adsbexchange.feeder.data.HistorySamples
import com.adsbexchange.feeder.data.HistoryStore
import com.adsbexchange.feeder.data.MlatReadiness
import com.adsbexchange.feeder.data.MlatStatus
import com.adsbexchange.feeder.data.StationLocation
import com.adsbexchange.feeder.data.RestartBudget
import com.adsbexchange.feeder.data.StatsStatus
import com.adsbexchange.feeder.data.appendAppLog
import com.adsbexchange.feeder.data.describeError
import com.adsbexchange.feeder.data.mlatAltitudeM
import com.adsbexchange.feeder.data.mlatReadiness
import com.adsbexchange.feeder.data.mlatUser
import com.adsbexchange.feeder.data.writeAtomically
import com.adsbexchange.feeder.mlat.MlatClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs the feeder: readsb.exe, the gpsd server that gives it the receiver position, the MLAT
 * client, the stats uploads, and the lifetime history. The desktop counterpart of the phone's FeederService.
 *
 * Everything runs on one engine thread, so the state below needs no locking. The UI and tray
 * watch [status].
 *
 * readsb only starts once an RTL-SDR stick with the WinUSB driver is plugged in ([sdrProbe]).
 * Until then the engine checks every few seconds and says what's missing (Reason.NO_SDR or
 * NEEDS_DRIVER; [setUpDriver] installs WinUSB, by itself the first time: see [autoSetUpDriver]).
 * Starting readsb without a stick would connect to ADS-B Exchange only to drop the connection a
 * second later, over and over.
 *
 * readsb exiting on its own (stick unplugged, crash) isn't the end: the engine starts it again
 * after a pause, until the user clicks Stop. Test runs ([TestRun]) aren't restarted.
 */
class FeederEngine(
    private val paths: FeederPaths,
    private val findReadsb: () -> File? = ReadsbProcess::findExecutable,
    private val sdrProbe: SdrProbe = SdrDriver.HelperProbe(SdrDriver.findExecutable()),
    private val installDriver: () -> SdrDriver.Outcome = {
        SdrDriver.findExecutable()?.let { SdrDriver.installElevated(it, File(paths.root, DRIVER_LOG)) }
            ?: SdrDriver.Outcome.FAILED
    },
    /**
     * Plug and play: when a stick without WinUSB turns up, start the driver setup straight away
     * (Windows shows its permission prompt) instead of waiting for a click. Once per app run, so a
     * "No" at the prompt isn't asked again; the button stays for that.
     */
    private val autoSetUpDriver: Boolean = true,
    /** How long to wait before starting readsb again after it stopped on its own (shortened in tests). */
    private val restartDelayMs: (failedStarts: Int, noSdr: Boolean) -> Long = ::defaultRestartDelayMs,
    /** How long a run must stay up to end a crash loop (shortened in tests); see [clearCrashLoopIfSettled]. */
    private val crashLoopClearMs: Long = CRASH_LOOP_CLEAR_MS,
    /** How often to log MLAT's sync quality while it's connected (shortened in tests); see [logMlatSync]. */
    private val mlatLogEveryMs: Long = MLAT_LOG_EVERY_MS,
    /** On battery, or on a metered connection: a laptop pauses (see [waitForMains] and [updateStats]). */
    private val conditions: PowerConditions = PowerConditions.Windows,
    /** How often to look for mains power again while paused on battery (shortened in tests). */
    private val powerPollMs: Long = POWER_POLL_MS,
) {
    /**
     * Tests only: read a UC8 I/Q capture instead of the SDR, feed a local sink instead of ADS-B
     * Exchange, and use the capture's receiver position. Only 127.0.0.1 targets are accepted, so a
     * test can never reach the real ADS-B Exchange.
     */
    data class TestRun(
        val inputFile: File,
        val location: StationLocation,
        val feedTo: String? = null,
        val mlatServer: Pair<String, Int>? = null,
        val altitudeM: Double = 100.0,
        /** Where to send stats uploads (see [StatsUploader]), as "http://127.0.0.1:port/..."; none if null. */
        val statsUrl: String? = null,
        val statsIntervalMs: Long = StatsUploader.INTERVAL_MS,
    ) {
        init {
            require(feedTo == null || feedTo.startsWith("127.0.0.1,")) { "test feed must be local" }
            require(mlatServer == null || mlatServer.first == "127.0.0.1") { "test MLAT server must be local" }
            require(statsUrl == null || statsUrl.startsWith("http://127.0.0.1:")) { "test stats server must be local" }
        }
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "feeder-engine").apply { isDaemon = true }
    }
    private val _status = MutableStateFlow(FeederStatus(State.STOPPED))
    val status: StateFlow<FeederStatus> = _status.asStateFlow()

    /** Set by the UI: screen-only work (fresher history samples, MLAT counters) only while it's shown. */
    @Volatile var uiVisible = false

    private val keepAwake = KeepAwake()
    /** Read by the gpsd and MLAT threads too (their checks that a connection is our readsb). */
    @Volatile private var readsb: ReadsbProcess? = null

    /** This run's readsb process, for the window's "Desktop resources" card; null when it isn't running. */
    val readsbPid: Long? get() = readsb?.pid
    private var test: TestRun? = null
    private var gpsd: GpsdServer? = null
    private var feedWatcher: FeedLogWatcher? = null
    private var logStart = 0L
    private var tickTask: ScheduledFuture<*>? = null
    private var restartTask: ScheduledFuture<*>? = null
    private var failedStarts = 0
    /** Spots a crash loop: too many unplanned exits in a short time; see [RestartBudget]. */
    private val restartBudget = RestartBudget()
    /** In a crash loop: readsb keeps stopping, and the engine keeps restarting it. On [FeederStatus.sdrFailing]. */
    private var crashLoop = false
    /** The crash loop was announced ([Event.SdrFailing]), so its end is too. */
    private var crashLoopAlerted = false
    /** What the last stick check found missing, so the log says it once rather than every check. */
    private var waitingFor: Reason? = null
    /** See [autoSetUpDriver]: the automatic driver setup is tried once per app run. */
    private var autoDriverSetupTried = false
    private var ticks = 0
    private var settings: FeederSettings? = null
    private var settingsMtime = 0L
    private var lastPushedLocation: StationLocation? = null
    /** The gain and bias tee this readsb run was started with; see [tick]. */
    private var runGain: String? = null
    private var runBiasTee = false
    /** When MLAT's sync quality was last logged, and its counters then; see [logMlatSync]. */
    private var mlatLoggedAtMs = 0L
    private var mlatLoggedSyncs = 0L
    private var mlatLoggedMlat = 0L
    private var mlatClient: MlatClient? = null
    private var mlatConfig: MlatClient.Config? = null
    private var statsUploader: StatsUploader? = null
    private var statsConfig: StatsUploader.Config? = null
    private var history: HistoryRecorder? = null
    private var historyStore: HistoryStore? = null
    private var lastAircraftSampleMtime = 0L
    private var statsMtime = 0L
    /** [HistoryRecorder.loopbackBytes] as of the current stats.json; see [recordHistory]. */
    private var loopbackAtStats = 0L
    private val watchdog = FeedWatchdog()
    /** readsb's message count from the latest stats sample, for [watchdog] (sampled with the history). */
    private var latestMessages: Long? = null
    /** A "stick unplugged" alert went out, so say so when feeding starts again. */
    private var unpluggedAlerted = false
    /** Whether the connection is metered, as of [meteredCheckedMs]; see [updateStats]. */
    private var metered = false
    private var meteredCheckedMs = 0L

    /** Things worth a tray notification. The engine stays UI-agnostic; Main.kt shows them. */
    sealed interface Event {
        /** Feeding has stopped working; see [FeedWatchdog]. Also on [FeederStatus.feedProblem] while it lasts. */
        data class FeedProblem(val problem: FeedWatchdog.Problem) : Event
        /** Working again after a [FeedProblem], [StickUnplugged] or [SdrFailing]. */
        data object FeedRecovered : Event
        /** The stick went away while feeding. */
        data object StickUnplugged : Event
        /** readsb keeps stopping (the engine keeps restarting it); see [RestartBudget]. Also on [FeederStatus.sdrFailing]. */
        data object SdrFailing : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** Starts the History tab's range plot again (e.g. after moving the antenna). Running or not. */
    fun resetRange() {
        executor.execute(guarded("the range reset", OnError.LOG) {
            val now = System.currentTimeMillis()
            val h = history
            if (h != null) {
                h.resetRange(now)
                saveHistory()
            } else {
                // Not running: change the saved history directly (not if it can't be read: load throws).
                runCatching {
                    val store = HistoryStore(paths, ::log)
                    HistoryRecorder(store.load()).apply { resetRange(now); save(store, now) }
                }.onFailure { log("history: range not reset: $it") }
            }
            log("range plot reset")
        })
    }

    /** Starts feeding (clears a previous Stop). No-op if already running. */
    fun start(testRun: TestRun? = null) {
        executor.execute(guarded("starting") {
            restartTask?.cancel(false)
            restartTask = null
            failedStarts = 0
            clearCrashLoop(announce = false)
            if (testRun == null) FeederSettings.update(paths) { it.copy(userStopped = false) }
            startRun(testRun)
        })
    }

    /** Stops feeding and remembers that the user did, so the next app start doesn't feed. Waits until stopped. */
    fun stop() {
        runCatching {
            executor.submit(guarded("stopping", OnError.STOP) { stopRun(Reason.USER_STOPPED, rememberStop = true) })
                .get(10, TimeUnit.SECONDS)
        }
    }

    /** At app exit: stops readsb without changing the user's start/stop choice. Waits until stopped. */
    fun shutdown() {
        runCatching {
            executor.submit(guarded("shutting down", OnError.STOP) { stopRun(Reason.NONE, rememberStop = false) })
                .get(10, TimeUnit.SECONDS)
        }
        executor.shutdownNow()
    }

    /** What [guarded] does after logging an exception. */
    private enum class OnError {
        /** Nothing more: the task wasn't part of the run (a range reset, say). */
        LOG,
        /** End the run, and stay stopped (Stop, or the app quitting). */
        STOP,
        /** End the run, and start again after a pause, as after a readsb crash. */
        RESTART,
    }

    /**
     * [block], for the engine thread, with any exception caught. Otherwise an exception would end
     * the task silently: a scheduled task that throws is never run again (the 2 s [tick] among
     * them), so readsb would run unwatched, or feeding would sit at "Starting" for ever, with
     * nothing in the log. It's logged, with where it was thrown, and the
     * run ended and restarted as [onError] says; see [recover].
     */
    private fun guarded(task: String, onError: OnError = OnError.RESTART, block: () -> Unit) = Runnable {
        try {
            block()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt() // shutdown()
        } catch (e: Throwable) {
            try {
                recover(task, e, onError)
            } catch (again: Throwable) {
                log("engine: recovering from that failed too: ${describeError(again)}")
            }
        }
    }

    /** After an exception in [task]: logs it, then ends the run and starts again later, or stays stopped. */
    private fun recover(task: String, e: Throwable, onError: OnError) {
        log("engine: error while $task: ${describeError(e)}")
        if (onError == OnError.LOG) return
        val wasTest = test != null
        restartTask?.cancel(false)
        restartTask = null
        readsb?.let { p -> runCatching { p.stop() }.onFailure { log("engine: stopping readsb failed: ${describeError(it)}") } }
        teardown()
        val stayStopped = onError == OnError.STOP || wasTest ||
            runCatching { FeederSettings.load(paths).userStopped }.getOrDefault(false)
        if (stayStopped) {
            return publish {
                it.copy(state = State.STOPPED, reason = if (onError == OnError.STOP) Reason.USER_STOPPED else Reason.ENGINE_ERROR,
                    feedState = FeedState.UNKNOWN, connectedSinceMs = null)
            }
        }
        val delayMs = restartDelayMs(failedStarts, false)
        failedStarts++
        log("engine: starting again in ${delayMs / 1000} s")
        publish {
            it.copy(state = State.STARTING, reason = Reason.ENGINE_ERROR, feeding = false, feedState = FeedState.UNKNOWN,
                connectedSinceMs = null)
        }
        scheduleStart(delayMs)
    }

    /** Starts a run after [delayMs], unless the user has clicked Stop by then. */
    private fun scheduleStart(delayMs: Long) {
        restartTask = executor.schedule(guarded("starting again") {
            restartTask = null
            if (!FeederSettings.load(paths).userStopped) startRun(null)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Installs WinUSB for the plugged-in stick. Windows asks the user for permission first, which
     * can take any amount of time, so this runs on a thread of its own; once the driver is in,
     * feeding starts by itself.
     */
    fun setUpDriver() {
        val before = _status.getAndUpdate { if (it.driverSetup == DriverSetup.RUNNING) it else it.copy(driverSetup = DriverSetup.RUNNING) }
        if (before.driverSetup == DriverSetup.RUNNING) return
        thread(name = "driver-setup", isDaemon = true) {
            log("setting up the WinUSB driver (Windows asks for permission)")
            val driverLog = File(paths.root, DRIVER_LOG)
            val logFrom = driverLog.length()
            val outcome = runCatching { installDriver() }.getOrDefault(SdrDriver.Outcome.FAILED)
            // The helper ran elevated with no console; copy what it said into our log.
            runCatching {
                driverLog.readText().drop(logFrom.toInt()).lineSequence().filter { it.isNotBlank() }
                    .forEach { log("driver: $it") }
            }
            log("driver setup: ${outcome.name.lowercase()}")
            publish {
                it.copy(driverSetup = when (outcome) {
                    SdrDriver.Outcome.INSTALLED, SdrDriver.Outcome.NO_DEVICE -> DriverSetup.IDLE
                    SdrDriver.Outcome.CANCELLED -> DriverSetup.CANCELLED
                    SdrDriver.Outcome.FAILED -> DriverSetup.FAILED
                })
            }
            // Don't wait for the next check to start feeding.
            executor.execute(guarded("starting after the driver setup") {
                if (readsb == null && restartTask != null) {
                    restartTask?.cancel(false)
                    restartTask = null
                    startRun(null)
                }
            })
        }
    }

    // ---- engine thread only from here --------------------------------------------------------

    private fun startRun(testRun: TestRun?) {
        if (readsb != null) return
        test = testRun
        val s = FeederSettings.load(paths).also { settings = it; settingsMtime = paths.settingsFile.lastModified() }
        runGain = s.gain
        runBiasTee = s.biasTee
        var stick: SdrStick? = null
        if (testRun == null) {
            if (!s.feedOnBattery && conditions.onBattery()) return waitForMains()
            if (waitingFor == Reason.ON_BATTERY) {
                log("on mains power again; feeding")
                waitingFor = null
            }
            val scan = sdrProbe.scan()
            if (scan != null && !scan.ready) return waitForStick(scan)
            stick = scan?.sticks?.firstOrNull { it.winusb }
            waitingFor = null
        }
        val exe = findReadsb()
        if (exe == null) {
            log("readsb.exe not found")
            return publish { FeederStatus(State.STOPPED, Reason.START_FAILED) }
        }
        publish { FeederStatus(State.STARTING, driverSetup = it.driverSetup, sdrFailing = crashLoop) }
        paths.jsonDir.mkdirs()
        // Don't let the UI show the previous run's aircraft while this one starts.
        listOf(paths.aircraftJson, paths.statsJson, paths.receiverJson).forEach { it.delete() }

        // Only our readsb may read the position: see GpsdServer and Win32.loopbackClientPid.
        val server = GpsdServer { peerPort, serverPort ->
            val pid = readsb?.pid
            pid != null && Win32.loopbackClientPid(peerPort, serverPort) == pid
        }
        gpsd = server
        val location = testRun?.location ?: s.location
        location?.let(server::update)
        lastPushedLocation = location

        // History that's there but can't be read isn't recorded this run, so it isn't saved over.
        val store = HistoryStore(paths, ::log)
        try {
            history = HistoryRecorder(store.load())
            historyStore = store
        } catch (e: IOException) {
            log("history: the saved history can't be read ($e); not recording it this run, so it isn't saved over")
        }
        val feedTo = when {
            testRun != null -> testRun.feedTo
            s.feedAdsbx -> FeederSettings.ADSBX_FEED
            else -> null
        }
        // Blank only while settings.json can't be read and readsb's copy is gone too (FeederSettings.load):
        // never feed under a made-up identity. An error, so the run is tried again shortly (guarded).
        if (feedTo != null && s.uuid.isBlank()) throw IOException("no feeder ID: settings.json can't be read")
        val beastPort = pickBeastPort()
        // readsb reads the feeder ID from here (--uuid-file); see FeederSettings.readsbArgs.
        if (feedTo != null) writeAtomically(paths.uuidFile, s.uuid)
        val args = s.readsbArgs(paths, server.port, feedTo, beastPort, testRun?.inputFile)
        logStart = paths.logFile.length()
        log("starting readsb: ${args.joinToString(" ")}")
        val process = try {
            ReadsbProcess.start(exe, args, paths.logFile, paths.root)
        } catch (e: IOException) {
            log("readsb.exe failed to start: $e")
            server.close()
            gpsd = null
            return publish { FeederStatus(State.STOPPED, Reason.START_FAILED) }
        }
        readsb = process
        // Accept gpsd connections only now: the access check needs readsb's pid. (The socket is
        // already listening, so readsb's first connection waits in the backlog.)
        server.start()
        feedWatcher = feedTo?.let { FeedLogWatcher(paths.logFile, it, logStart) }
        keepAwake.set(true)
        ticks = 0
        publish {
            FeederStatus(
                State.RUNNING, feeding = feedTo != null, feedTarget = feedTo,
                startedMs = System.currentTimeMillis(), beastPort = beastPort, stickName = stick?.name,
                sdrFailing = crashLoop,
            )
        }
        if (unpluggedAlerted) {
            unpluggedAlerted = false
            _events.tryEmit(Event.FeedRecovered)
        }
        tickTask = executor.scheduleWithFixedDelay(guarded("checking on readsb") { tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
    }

    /** No usable stick yet: say why, and look again shortly. readsb isn't started. */
    private fun waitForStick(scan: SdrScan) {
        val reason = if (scan.needsDriver) Reason.NEEDS_DRIVER else Reason.NO_SDR
        val first = scan.sticks.firstOrNull()
        if (reason != waitingFor) {
            waitingFor = reason
            log(if (first == null) "no RTL-SDR plugged in; waiting"
                else "${first.name} (${first.vid}:${first.pid}) has driver ${first.driver ?: "none"}, not WinUSB; waiting for driver setup")
        }
        publish {
            it.copy(
                state = State.STARTING, reason = reason, stickName = first?.name, stickDriver = first?.driver,
                feeding = false, feedState = FeedState.UNKNOWN, connectedSinceMs = null,
            )
        }
        scheduleStart(SDR_POLL_S * 1000)
        if (reason == Reason.NEEDS_DRIVER && autoSetUpDriver && !autoDriverSetupTried) {
            autoDriverSetupTried = true
            setUpDriver()
        }
    }

    /**
     * Paused on battery: a laptop shouldn't run its battery down feeding (Settings > Keep feeding on
     * battery turns this off). readsb isn't running, so the PC may sleep; look again shortly.
     */
    private fun waitForMains() {
        if (waitingFor != Reason.ON_BATTERY) {
            waitingFor = Reason.ON_BATTERY
            log("on battery: feeding paused until the PC is plugged in")
        }
        publish {
            it.copy(
                state = State.STARTING, reason = Reason.ON_BATTERY, feeding = false, feedState = FeedState.UNKNOWN,
                connectedSinceMs = null,
            )
        }
        scheduleStart(powerPollMs)
    }

    private fun tick() {
        val p = readsb ?: return
        if (!p.isAlive) return onReadsbExit(p.exitCode ?: -1)
        val visible = uiVisible

        val feedState = feedWatcher?.poll() ?: FeedState.UNKNOWN
        val st = _status.value
        if (st.feeding && feedState != st.feedState) {
            val dropped = st.feedState == FeedState.CONNECTED && feedState != FeedState.CONNECTED
            publish {
                it.copy(
                    feedState = feedState,
                    connectedSinceMs = if (feedState == FeedState.CONNECTED) System.currentTimeMillis() else null,
                    reconnects = it.reconnects + if (dropped) 1 else 0,
                )
            }
            if (feedState == FeedState.CONNECTED) failedStarts = 0
        }

        // Settings change only through the app, which writes the file; re-read when it does.
        val mtime = paths.settingsFile.lastModified()
        if (mtime != settingsMtime) {
            settingsMtime = mtime
            settings = FeederSettings.load(paths)
        }
        val s = settings!!
        // Unplugged from the mains: pause until it's plugged in again (not for test runs).
        if (test == null && !s.feedOnBattery && conditions.onBattery()) {
            readsb?.stop()
            teardown()
            return waitForMains()
        }
        // readsb reads the gain and the bias tee only when it starts, so a change (Settings) means
        // starting it again.
        if (test == null && s.gain != runGain) return restartReadsb("gain changed to ${s.gain}")
        if (test == null && s.biasTee != runBiasTee) {
            return restartReadsb("bias tee turned ${if (s.biasTee) "on" else "off"}")
        }
        if (test == null) {
            s.location?.let { saved ->
                if (saved != lastPushedLocation) {
                    gpsd?.update(saved)
                    lastPushedLocation = saved
                }
            }
        }
        recordHistory(visible)
        watchFeed(s)
        updateMlat(s, visible)
        updateStats(s, visible)
        clearCrashLoopIfSettled()
        ticks++
    }

    private fun onReadsbExit(code: Int) {
        val noSdr = logSinceStart().contains("no supported devices found")
        log("readsb exited (code $code)${if (noSdr) ": no RTL-SDR found" else ""}")
        val wasTest = test != null
        teardown()
        val reason = if (noSdr) Reason.NO_SDR else Reason.READSB_EXITED
        if (wasTest) {
            return publish { it.copy(state = State.STOPPED, reason = reason, exitCode = code, feedState = FeedState.UNKNOWN, connectedSinceMs = null) }
        }
        // Was the stick pulled out? Then say so now, and wait for it rather than restarting readsb.
        val scan = sdrProbe.scan()
        if (scan != null && scan.sticks.isEmpty()) {
            log("the RTL-SDR was unplugged")
            // Its own alert supersedes a crash loop; plugging it back in is a fresh start.
            clearCrashLoop(announce = false)
            if (settings?.feedAlerts != false) {
                unpluggedAlerted = true
                _events.tryEmit(Event.StickUnplugged)
            }
            return waitForStick(scan)
        }
        // A crash loop (a failing stick or cable, or another program using the stick): stop and say
        // so, rather than restarting for ever. Start, or the next app start, tries again.
        // A crash loop (a failing stick, cable or hub, or another program holding the stick): say so
        // once, and keep retrying, since most of these fix themselves (the other program closes,
        // the USB recovers). It's cleared once a run stays up; see clearCrashLoopIfSettled.
        if (!restartBudget.allow(System.currentTimeMillis()) && !crashLoop) {
            crashLoop = true
            log("readsb exited ${RestartBudget.MAX_EXITS} times in ${RestartBudget.WINDOW_MS / 60_000} minutes; " +
                "still retrying")
            if (settings?.feedAlerts != false) {
                crashLoopAlerted = true
                _events.tryEmit(Event.SdrFailing)
            }
        }
        // Waiting for a stick to be plugged in: check often. A crash: back off.
        val delayMs = restartDelayMs(failedStarts, noSdr)
        failedStarts++
        publish {
            it.copy(state = State.STARTING, reason = reason, exitCode = code, feedState = FeedState.UNKNOWN,
                connectedSinceMs = null, sdrFailing = crashLoop)
        }
        scheduleStart(delayMs)
    }

    /**
     * Ends a crash loop ([crashLoop]): when a run has stayed up [crashLoopClearMs], quietly when the
     * user stops or starts feeding or the stick is unplugged. "Feeding again" only follows an
     * announced loop.
     */
    private fun clearCrashLoop(announce: Boolean) {
        restartBudget.reset()
        if (!crashLoop) return
        crashLoop = false
        if (announce && crashLoopAlerted) _events.tryEmit(Event.FeedRecovered)
        crashLoopAlerted = false
        publish { it.copy(sdrFailing = false) }
    }

    /**
     * A run that stays up [crashLoopClearMs] ends a crash loop. Uptime rather than the feed
     * connecting, so it also works with feeding off, and a stick that fails again within minutes
     * doesn't send a "feeding again" between every pair of "keeps failing" alerts.
     */
    private fun clearCrashLoopIfSettled() {
        if (!crashLoop) return
        val started = _status.value.startedMs ?: return
        if (System.currentTimeMillis() - started < crashLoopClearMs) return
        log("readsb has run ${crashLoopClearMs / 60_000} minutes; it's working again")
        clearCrashLoop(announce = true)
    }

    /** Stops readsb and starts it again at once, for a setting it only reads at start. Not a crash. */
    private fun restartReadsb(why: String) {
        log("$why; restarting readsb")
        readsb?.stop()
        teardown()
        startRun(null)
    }

    private fun stopRun(reason: Reason, rememberStop: Boolean) {
        restartTask?.cancel(false)
        restartTask = null
        waitingFor = null
        if (rememberStop) clearCrashLoop(announce = false)
        if (rememberStop) FeederSettings.update(paths) { it.copy(userStopped = true) }
        val code = readsb?.let { p ->
            log("stopping readsb")
            p.stop()
        }
        teardown()
        publish { it.copy(state = State.STOPPED, reason = reason, exitCode = code ?: it.exitCode, feedState = FeedState.UNKNOWN, connectedSinceMs = null) }
    }

    /**
     * Ends everything that belongs to one readsb run. readsb itself must already have exited. Each
     * step is on its own, so one that fails (logged) doesn't leave the rest running.
     */
    private fun teardown() {
        fun step(what: String, block: () -> Unit) {
            runCatching(block).onFailure { log("engine: $what failed: ${describeError(it)}") }
        }
        step("stopping the checks") { tickTask?.cancel(false) }
        tickTask = null
        saveHistory()
        history = null
        historyStore = null
        step("stopping MLAT") { mlatClient?.stop() }
        mlatClient = null
        mlatConfig = null
        step("stopping the stats uploads") { statsUploader?.stop() }
        statsUploader = null
        statsConfig = null
        if (_status.value.stats != null || _status.value.statsPausedMetered) publish { it.copy(stats = null, statsPausedMetered = false) }
        step("closing the position server") { gpsd?.close() }
        gpsd = null
        feedWatcher = null
        readsb = null
        test = null
        step("letting the PC sleep again") { keepAwake.set(false) }
        // Stopped, on purpose or not: nothing left for the watchdog to warn about. (It couldn't see a
        // crash loop anyway: no ticks between runs. RestartBudget and crashLoop cover that.)
        if (watchdog.clear() != null) publish { it.copy(feedProblem = null) }
    }

    /**
     * "Feeding stopped" alerts; see [FeedWatchdog]. A problem is shown on the Status screen while it
     * lasts and announced once in the tray; recovery is announced only after an announced problem.
     */
    private fun watchFeed(settings: FeederSettings) {
        val st = _status.value
        val action = if (!settings.feedAlerts) watchdog.clear() else watchdog.tick(
            running = true, feeding = st.feeding, feedConnected = st.feedState == FeedState.CONNECTED,
            messagesTotal = latestMessages, nowMs = System.currentTimeMillis(),
        )
        when (action) {
            is FeedWatchdog.Action.Alert -> {
                log("alert: ${action.problem}")
                publish { it.copy(feedProblem = action.problem) }
                _events.tryEmit(Event.FeedProblem(action.problem))
            }
            FeedWatchdog.Action.Clear -> {
                publish { it.copy(feedProblem = null) }
                // Turning alerts off isn't a recovery.
                if (settings.feedAlerts) {
                    log("alert cleared: feeding again")
                    _events.tryEmit(Event.FeedRecovered)
                }
            }
            null -> Unit
        }
    }

    /**
     * Starts, restarts or stops the MLAT client to match the settings (checked every tick, so e.g.
     * confirming the antenna position starts MLAT within seconds), and publishes its status.
     */
    private fun updateMlat(settings: FeederSettings, uiVisible: Boolean) {
        val t = test
        val st = _status.value
        val readiness = when {
            t?.mlatServer != null -> MlatReadiness.READY
            t != null || !st.feeding -> MlatReadiness.NOT_FEEDING
            else -> settings.mlatReadiness()
        }
        val wanted = if (readiness != MlatReadiness.READY) null else {
            val loc = t?.location ?: settings.location
            val alt = if (t != null) t.altitudeM else settings.mlatAltitudeM()
            if (loc == null || alt == null) null else MlatClient.Config(
                uuid = settings.uuid, user = settings.mlatUser(), lat = loc.lat, lon = loc.lon, altM = alt,
                privacy = settings.mlatPrivacy, server = t?.mlatServer,
                inputPort = st.beastPort ?: FeederSettings.DEFAULT_BEAST_PORT,
            )
        }
        if (wanted != mlatConfig) {
            mlatClient?.stop()
            mlatClient = wanted?.let {
                // Only forward Beast data from our own readsb: any program can listen on loopback,
                // and 30005 is the usual port. The listener's end of our connection must belong to
                // readsb (the gpsd check's lookup, with the ports swapped).
                val ownListener = { listenerPort: Int, clientPort: Int ->
                    val pid = readsb?.pid
                    pid != null && Win32.loopbackClientPid(listenerPort, clientPort) == pid
                }
                MlatClient(it, ownListener) { msg -> log(msg) }.apply { start() }
            }
            mlatConfig = wanted
            mlatLoggedAtMs = System.currentTimeMillis()
            mlatLoggedSyncs = 0
            mlatLoggedMlat = 0
        }
        val s = mlatClient?.stats
        if (s != null) logMlatSync(s)
        val next = MlatStatus(
            readiness = if (wanted == null && readiness == MlatReadiness.READY) MlatReadiness.NO_LOCATION else readiness,
            state = s?.state?.name, server = s?.server, connectedSinceMs = s?.connectedSinceMs,
            peerCount = s?.peerCount, outlierPercent = s?.outlierPercent, badSyncTimeout = s?.badSyncTimeout,
            requested = s?.requested ?: 0, adsbAircraft = s?.adsbAircraft ?: 0, modesAircraft = s?.modesAircraft ?: 0,
            syncsSent = s?.syncsSent ?: 0, mlatSent = s?.mlatSent ?: 0, bytesSent = s?.bytesSent ?: 0,
            loopbackBytes = loopbackAtStats, message = s?.message,
        )
        val prev = st.mlat
        val stateChanged = prev?.readiness != next.readiness || prev.state != next.state ||
            prev.connectedSinceMs != next.connectedSinceMs || prev.message != next.message
        // Counters are only for the screen: while nobody looks, publish state changes only.
        if (stateChanged || (uiVisible && ticks % MLAT_COUNTERS_EVERY_TICKS == 0)) publish { it.copy(mlat = next) }
    }

    /**
     * Every [mlatLogEveryMs] while MLAT is connected, a log line with the server's view of this
     * feeder's timing (nearby feeders in sync, outliers, bad-sync timeout) and what was sent. The
     * Status screen shows these live; the log keeps them, so a long run leaves a record of whether
     * MLAT synchronised (the question raised about MLAT on Windows).
     */
    private fun logMlatSync(s: MlatClient.Stats) {
        val now = System.currentTimeMillis()
        if (s.state != MlatClient.State.CONNECTED || now - mlatLoggedAtMs < mlatLogEveryMs) return
        val syncs = (s.syncsSent - mlatLoggedSyncs).coerceAtLeast(0)
        val mlat = (s.mlatSent - mlatLoggedMlat).coerceAtLeast(0)
        log(mlatSyncLine(s, syncs, mlat, ((now - mlatLoggedAtMs) / 60_000).toInt()))
        mlatLoggedAtMs = now
        mlatLoggedSyncs = s.syncsSent
        mlatLoggedMlat = s.mlatSent
    }

    /**
     * Starts, restarts or stops the stats uploads ([StatsUploader]) to match the settings, as
     * [updateMlat] does for MLAT, and publishes how they're going. They run while this run feeds
     * ADS-B Exchange and the setting is on. The receiver's distance to each aircraft goes too only
     * while MLAT is ready, i.e. while MLAT sends ADS-B Exchange the position anyway.
     */
    private fun updateStats(settings: FeederSettings, uiVisible: Boolean) {
        val t = test
        val now = System.currentTimeMillis()
        if (t == null && now - meteredCheckedMs >= METERED_CHECK_MS) {
            metered = conditions.metered()
            meteredCheckedMs = now
        }
        // On a metered connection (a phone hotspot, say) the stats, 1-10 GB a month, wait; the feed goes on.
        val pausedMetered = t == null && statsPausedMetered(metered, settings, _status.value.feeding)
        val wanted = when {
            t != null -> t.statsUrl?.let {
                StatsUploader.Config(settings.uuid, paths.aircraftJson, includeRange = t.mlatServer != null,
                    url = URI(it), intervalMs = t.statsIntervalMs)
            }
            !_status.value.feeding || !settings.statsUpload || pausedMetered -> null
            else -> StatsUploader.Config(settings.uuid, paths.aircraftJson,
                includeRange = settings.mlatReadiness() == MlatReadiness.READY)
        }
        if (pausedMetered != _status.value.statsPausedMetered) publish { it.copy(statsPausedMetered = pausedMetered) }
        if (wanted != statsConfig) {
            statsUploader?.stop()
            if (wanted == null) log(if (pausedMetered) "stats: paused on a metered connection" else "stats: off")
            statsUploader = wanted?.let { StatsUploader(it) { msg -> log(msg) }.apply { start() } }
            statsConfig = wanted
        }
        val next = statsUploader?.stats?.let { StatsStatus(it.uploads, it.bytesSent, it.lastOkMs, it.error) }
        val prev = _status.value.stats
        // As MLAT: while nobody looks, publish only what changes the state shown.
        val stateChanged = (prev == null) != (next == null) || prev?.error != next?.error ||
            (prev?.lastOkMs == null) != (next?.lastOkMs == null)
        if (stateChanged || (uiVisible && next != prev)) publish { it.copy(stats = next) }
    }

    /**
     * Lifetime history (see [HistoryRecorder]). Feeding time and MLAT counters every tick; readsb's
     * totals and an aircraft snapshot every 10 s while the window is shown, every minute otherwise.
     * Saved every 10 s while shown, every 5 minutes otherwise, and when readsb stops.
     */
    private fun recordHistory(visible: Boolean) {
        val h = history ?: return
        val now = System.currentTimeMillis()
        val st = _status.value
        val contributing = st.feeding && st.feedState == FeedState.CONNECTED
        h.onFeedTick(contributing, st.connectedSinceMs, now)
        mlatClient?.let { c ->
            val s = c.stats
            h.onMlat(c, s.syncsSent, s.mlatSent, s.bytesSent, s.inputBytes, contributing, now)
        }
        // readsb rewrites stats.json every 10 s. Note the loopback count when it does, so bytes_out
        // minus loopback compares the same moment (within a tick).
        val m = paths.statsJson.lastModified()
        if (m != statsMtime) {
            statsMtime = m
            loopbackAtStats = h.loopbackBytes
        }
        if (ticks % (if (visible) HISTORY_SAMPLE_VISIBLE_TICKS else HISTORY_SAMPLE_HIDDEN_TICKS) == 0) {
            HistorySamples.stats(paths)?.let {
                h.onStats(it.session, it.messages, it.bytesOut, it.maxDistanceM, loopbackAtStats, contributing, now)
                latestMessages = it.messages
            }
            val mtime = paths.aircraftJson.lastModified()
            if (mtime != lastAircraftSampleMtime) {
                lastAircraftSampleMtime = mtime
                // The range plot always records here: a PC doesn't move (the phone skips it in mobile mode).
                HistorySamples.aircraft(paths)?.let {
                    h.onAircraft(it.addresses, it.positions, recordRange = true, contributing, now)
                }
            }
        }
        if (ticks % (if (visible) HISTORY_SAVE_VISIBLE_TICKS else HISTORY_SAVE_HIDDEN_TICKS) == 0) saveHistory()
    }

    private fun saveHistory() {
        val store = historyStore ?: return
        runCatching { history?.save(store, System.currentTimeMillis()) }.onFailure { log("history: not saved: $it") }
    }

    /** 30005 as usual, unless another program on this PC (another decoder, say) already has it. */
    private fun pickBeastPort(): Int {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        // Probes only: bound to loopback and closed at once, never carrying data.
        // nosemgrep: kotlin.lang.security.unencrypted-socket.unencrypted-socket - loopback port probe
        val usual = runCatching { ServerSocket(FeederSettings.DEFAULT_BEAST_PORT, 1, loopback).close() }.isSuccess
        if (usual) return FeederSettings.DEFAULT_BEAST_PORT
        // nosemgrep: kotlin.lang.security.unencrypted-socket.unencrypted-socket - loopback port probe
        return ServerSocket(0, 1, loopback).use { it.localPort }.also {
            log("port ${FeederSettings.DEFAULT_BEAST_PORT} is in use; readsb's Beast output is on $it")
        }
    }

    /** What readsb has logged during this run (bounded). */
    private fun logSinceStart(): String = runCatching {
        RandomAccessFile(paths.logFile, "r").use { raf ->
            val start = logStart.coerceIn(0, raf.length()).coerceAtLeast(raf.length() - 64 * 1024)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrDefault("")

    private fun publish(change: (FeederStatus) -> FeederStatus) {
        _status.update { change(it).copy(updatedMs = System.currentTimeMillis()) }
    }

    private fun log(message: String) = appendLog(paths, message)

    companion object {
        private const val TICK_MS = 2_000L
        private const val NO_SDR_RETRY_S = 10L
        /** How often to look for a stick (or its driver) while there is none. */
        private const val SDR_POLL_S = 3L
        /** rtlsdr-driver.exe's messages from driver setup (it runs elevated, with no console). */
        const val DRIVER_LOG = "driver-install.log"
        private const val RESTART_MIN_S = 10L
        private const val RESTART_MAX_S = 60L
        /** A run this long ends a crash loop. */
        const val CRASH_LOOP_CLEAR_MS = 10 * 60_000L
        const val MLAT_LOG_EVERY_MS = 10 * 60_000L
        /** While paused on battery, how often to look for mains power. */
        const val POWER_POLL_MS = 5_000L
        /** How often to ask Windows whether the connection is metered. */
        private const val METERED_CHECK_MS = 30_000L

        /**
         * The MLAT sync log line, e.g. "mlat: synced with 7 nearby feeders, sync outliers 1.5%,
         * bad-sync timeout 0; last 10 min: 925 sync pairs and 463 MLAT messages sent, 6 aircraft requested".
         */
        fun mlatSyncLine(s: MlatClient.Stats, syncs: Long, mlat: Long, minutes: Int): String = buildString {
            append("mlat: ")
            if (s.peerCount == null) append("the server hasn't reported sync yet")
            else append("synced with ${s.peerCount} nearby feeder${if (s.peerCount == 1) "" else "s"}")
            s.outlierPercent?.let { append(", sync outliers %.1f%%".format(Locale.US, it)) }
            s.badSyncTimeout?.let { append(", bad-sync timeout $it") }
            append("; last $minutes min: $syncs sync pairs and $mlat MLAT messages sent, ${s.requested} aircraft requested")
        }

        /** Waiting for readsb to find a stick: check often. After a crash: back off, 10 s doubling to 60 s. */
        /** Stats wait on a metered connection, unless the user said to send them anyway. Only matters while feeding with stats on. */
        fun statsPausedMetered(metered: Boolean, settings: FeederSettings, feeding: Boolean): Boolean =
            metered && feeding && settings.statsUpload && !settings.statsOnMetered

        fun defaultRestartDelayMs(failedStarts: Int, noSdr: Boolean): Long = 1000L *
            if (noSdr) NO_SDR_RETRY_S else (RESTART_MIN_S shl failedStarts.coerceAtMost(3)).coerceAtMost(RESTART_MAX_S)
        private const val MLAT_COUNTERS_EVERY_TICKS = 2
        private const val HISTORY_SAMPLE_VISIBLE_TICKS = 5 // 10 s
        private const val HISTORY_SAMPLE_HIDDEN_TICKS = 30 // 1 min
        private const val HISTORY_SAVE_VISIBLE_TICKS = 5 // 10 s
        private const val HISTORY_SAVE_HIDDEN_TICKS = 150 // 5 min

        /** Appends a `[feeder]` line to readsb.log, so the app's lines and readsb's read in order. */
        fun appendLog(paths: FeederPaths, message: String) = appendAppLog(paths, message)
    }
}
