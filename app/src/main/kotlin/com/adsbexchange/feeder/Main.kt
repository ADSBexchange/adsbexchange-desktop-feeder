package com.adsbexchange.feeder

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.adsbexchange.feeder.data.FeedState
import com.adsbexchange.feeder.data.FeedWatchdog
import com.adsbexchange.feeder.data.FeederPaths
import com.adsbexchange.feeder.data.FeederSettings
import com.adsbexchange.feeder.data.FeederStatus
import com.adsbexchange.feeder.data.FeederStatus.Reason
import com.adsbexchange.feeder.data.FeederStatus.State
import com.adsbexchange.feeder.data.RestartBudget
import com.adsbexchange.feeder.data.describeError
import com.adsbexchange.feeder.resources.Res
import com.adsbexchange.feeder.resources.icon
import com.adsbexchange.feeder.service.Autostart
import com.adsbexchange.feeder.service.FeederEngine
import com.adsbexchange.feeder.service.UpdateChecker
import com.adsbexchange.feeder.ui.openUrl
import com.adsbexchange.feeder.ui.FeederApp
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import javax.swing.JOptionPane
import kotlinx.coroutines.delay
import kotlin.system.exitProcess
import org.jetbrains.compose.resources.painterResource

/**
 * The app: a tray icon that lives as long as the feeder, plus a window opened from it.
 * Closing the window leaves the feeder running; "Quit" in the tray menu stops everything.
 *
 * `--background` starts without opening the window (for starting with Windows). Starting it again
 * while it runs shows the running copy's window.
 */
fun main(args: Array<String>) {
    val paths = FeederPaths.default()
    // An exception that escapes one of the app's threads (the stats uploads, the position server,
    // the window) goes in the log rather than vanishing. The engine's own
    // tasks catch theirs and restart the run.
    Thread.setDefaultUncaughtExceptionHandler { thread, e ->
        FeederEngine.appendLog(paths, "error on thread ${thread.name}: ${describeError(e)}")
        e.printStackTrace()
    }
    try {
        runApp(paths, args)
    } catch (e: Throwable) {
        FeederEngine.appendLog(paths, "the app stopped because of an error: ${describeError(e)}")
        runCatching {
            JOptionPane.showMessageDialog(null,
                "The ADS-B Exchange Feeder stopped because of an error:\n${e.message ?: e.javaClass.simpleName}\n\n" +
                    "The details are in its log: ${paths.logFile}",
                "ADS-B Exchange Feeder", JOptionPane.ERROR_MESSAGE)
        }
        exitProcess(1)
    }
}

private fun runApp(paths: FeederPaths, args: Array<String>) {
    val lock = acquireSingleInstanceLock(paths) ?: run {
        // Already running (in the tray, say): have that copy show its window, unless this start
        // was only the start-with-Windows one.
        if ("--background" !in args) requestShow(paths)
        exitProcess(0)
    }
    // A request left over from a start that found no running copy isn't for us.
    showRequestFile(paths).delete()
    FeederEngine.appendLog(paths, "ADS-B Exchange Feeder ${BuildInfo.version} (${BuildInfo.commit}) starting")

    val engine = FeederEngine(paths)
    val updates = UpdateChecker(enabled = { FeederSettings.load(paths).checkForUpdates }) { FeederEngine.appendLog(paths, it) }
        .apply { start() }
    val settings = FeederSettings.load(paths)
    Autostart.apply(settings.startWithWindows)
    if (settings.autoStart && !settings.userStopped) engine.start()
    Runtime.getRuntime().addShutdownHook(Thread { updates.shutdown(); engine.shutdown() })

    application(exitProcessOnExit = true) {
        var windowOpen by remember { mutableStateOf("--background" !in args) }
        var toldAboutTray by remember { mutableStateOf(false) }
        val status by engine.status.collectAsState()
        val update = updates.state.collectAsState().value.available
        val trayState = rememberTrayState()
        val icon = painterResource(Res.drawable.icon)

        var raiseRequests by remember { mutableStateOf(0) }
        fun open() {
            windowOpen = true
            raiseRequests++
        }

        fun quit() {
            updates.shutdown()
            engine.shutdown()
            lock.release()
            exitApplication()
        }

        Tray(
            icon = icon,
            state = trayState,
            tooltip = "${BuildInfo.appTitle}: ${trayText(status)}",
            onAction = { open() },
            menu = {
                Item("Open", onClick = { open() })
                if (status.reason == Reason.NEEDS_DRIVER && status.state == State.STARTING) {
                    Item("Set up the RTL-SDR driver", onClick = { engine.setUpDriver() })
                }
                if (status.state == State.STOPPED) Item("Start feeding", onClick = { engine.start() })
                else Item("Stop feeding", onClick = { engine.stop() })
                update?.let { u -> Item("Download version ${u.version}", onClick = { openUrl(u.url) }) }
                Separator()
                Item("Quit", onClick = ::quit)
            },
        )

        if (windowOpen) {
            Window(
                onCloseRequest = {
                    windowOpen = false
                    if (!toldAboutTray && status.state != State.STOPPED) {
                        toldAboutTray = true
                        trayState.sendNotification(Notification("Still feeding",
                            "The feeder keeps running in the background. Open it again from its icon here.",
                            Notification.Type.Info))
                    }
                },
                title = BuildInfo.appTitle,
                icon = icon,
                state = rememberWindowState(width = 720.dp, height = 760.dp),
            ) {
                LaunchedEffect(Unit) { engine.uiVisible = true }
                // Every time it's opened, including the first: in front of whatever else is open.
                LaunchedEffect(raiseRequests) { bringToFront(window) }
                FeederApp(engine, paths, updates)
            }
        }
        LaunchedEffect(windowOpen) {
            if (!windowOpen) {
                engine.uiVisible = false
                // Closed to the tray: collect once the window's objects are free, so the heap shrinks
                // and Windows gets that memory back (the Serial collector and MaxHeapFreeRatio in
                // build.gradle.kts). A few MB; once per close, so it costs nothing.
                delay(5_000)
                System.gc()
            }
        }

        // Another start of the app (a shortcut, the installer's "Open") asks this one to show its window.
        LaunchedEffect(Unit) {
            while (true) {
                if (consumeShowRequest(paths)) open()
                delay(500)
            }
        }

        // A stick that needs its driver set up can't wait for the user to open the window.
        val needsDriver = status.state == State.STARTING && status.reason == Reason.NEEDS_DRIVER
        LaunchedEffect(needsDriver) {
            if (needsDriver && !windowOpen) {
                trayState.sendNotification(Notification("Your RTL-SDR needs a driver",
                    "Open the feeder to set it up. Windows will ask for your permission once.", Notification.Type.Warning))
            }
        }

        // A new version: once per version, unless the user already put it off ("Not now").
        LaunchedEffect(update) {
            if (update != null && update.version != FeederSettings.load(paths).dismissedUpdate) {
                trayState.sendNotification(Notification("A new version is available",
                    "ADS-B Exchange Feeder ${update.version} is ready to download. Open the feeder, or use the tray menu.",
                    Notification.Type.Info))
            }
        }

        // "Feeding stopped" alerts (Settings > Alert me if feeding stops). Windows can't take a
        // notification back, so recovery gets its own short one; the Status screen shows a
        // problem for as long as it lasts.
        LaunchedEffect(Unit) {
            engine.events.collect { e -> trayState.sendNotification(notificationFor(e)) }
        }
    }
}

private fun notificationFor(e: FeederEngine.Event): Notification = when (e) {
    is FeederEngine.Event.FeedProblem -> when (e.problem) {
        FeedWatchdog.Problem.FEED_DOWN -> Notification("Not reaching ADS-B Exchange",
            "This PC hasn't been able to send to ADS-B Exchange for 15 minutes. Check its internet connection.",
            Notification.Type.Warning)
        FeedWatchdog.Problem.NO_AIRCRAFT -> Notification("No aircraft heard for 2 hours",
            "The receiver is running but hasn't heard anything. Check that the antenna is connected and the " +
                "RTL-SDR is firmly plugged in.", Notification.Type.Warning)
    }
    FeederEngine.Event.StickUnplugged -> Notification("RTL-SDR unplugged",
        "Feeding has stopped. Plug the stick back in and feeding starts again by itself.", Notification.Type.Warning)
    FeederEngine.Event.FeedRecovered -> Notification("Feeding again",
        "The feeder is working again.", Notification.Type.Info)
    FeederEngine.Event.SdrFailing -> Notification("The RTL-SDR keeps failing",
        "The receiver stopped ${RestartBudget.MAX_EXITS} times in ${RestartBudget.WINDOW_MS / 60_000} minutes. Close any " +
            "other program using the stick and check its cable. The feeder keeps trying every minute.",
        Notification.Type.Error)
}

private fun trayText(s: FeederStatus): String = if (s.sdrFailing && s.state != State.STOPPED && s.feedState != FeedState.CONNECTED) {
    "the RTL-SDR keeps failing; retrying"
} else when (s.state) {
    State.RUNNING -> when {
        !s.feeding -> "receiving, not feeding"
        s.feedState == FeedState.CONNECTED -> "feeding"
        else -> "connecting"
    }
    State.STARTING -> when (s.reason) {
        Reason.NO_SDR -> "no RTL-SDR found"
        Reason.NEEDS_DRIVER -> "RTL-SDR needs its driver"
        Reason.ON_BATTERY -> "paused on battery"
        Reason.ENGINE_ERROR -> "restarting after an error"
        else -> "starting"
    }
    State.STOPPED -> "stopped"
}

/**
 * Brings the window in front of everything else. The launcher Explorer starts runs the app as a
 * second process, and Windows only lets the process the user started take the foreground, so a
 * plain toFront() leaves the window behind whatever was open. Being briefly always-on-top isn't
 * subject to that rule.
 */
private fun bringToFront(window: java.awt.Window) {
    (window as? java.awt.Frame)?.let { if (it.extendedState and java.awt.Frame.ICONIFIED != 0) it.extendedState = java.awt.Frame.NORMAL }
    window.isAlwaysOnTop = true
    window.toFront()
    window.requestFocus()
    window.isAlwaysOnTop = false
}

/** A second start of the app leaves this file for the running one, which then shows its window. */
private fun showRequestFile(paths: FeederPaths) = java.io.File(paths.base, "show.request")

private fun requestShow(paths: FeederPaths) {
    runCatching { showRequestFile(paths).writeText("show") }
}

private fun consumeShowRequest(paths: FeederPaths): Boolean = showRequestFile(paths).let { it.exists() && it.delete() }

/** One copy per Windows user: two would fight over the SDR and the files. */
private fun acquireSingleInstanceLock(paths: FeederPaths): FileLock? {
    paths.base.mkdirs()
    val channel = RandomAccessFile(paths.lockFile, "rw").channel
    return runCatching { channel.tryLock() }.getOrNull()
}
