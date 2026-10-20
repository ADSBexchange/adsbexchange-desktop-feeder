package com.adsbexchange.feeder

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.adsbexchange.feeder.data.FeederPaths
import com.adsbexchange.feeder.data.FeederSettings
import com.adsbexchange.feeder.data.StationLocation
import com.adsbexchange.feeder.service.FeederEngine
import com.adsbexchange.feeder.service.UpdateChecker
import com.sun.net.httpserver.HttpServer
import com.adsbexchange.feeder.ui.FeederApp
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import kotlin.concurrent.thread

/**
 * Development only (test sources; never in the MSI): the real window and engine, running readsb.exe
 * on a synthetic capture so the Status, Aircraft and History screens show live data without a
 * stick. The feed goes to a sink in this process and MLAT to tools/mlat-sim/FakeMlatServer.java,
 * both on 127.0.0.1: nothing reaches ADS-B Exchange. Settings and history go to a temporary folder.
 *
 * .\gradlew.bat :app:runPreview, after tools\build-native.ps1 and making the capture:
 *   java tools/adsb-sim/AdsbSim.java tools/testdata/preview.iq tools/testdata/preview.json 120
 * (or pass any UC8 capture as -Pcapture=<file>).
 *
 * -PpreviewRelease=0.6.0 also runs a stand-in for GitHub's releases API on 127.0.0.1 that offers
 * that version, so the window shows the "new version" notice a few seconds after it opens.
 */
fun main() {
    val capture = File(System.getProperty("feeder.capture"))
    require(capture.isFile) { "No capture at $capture" }
    val javaExe = File(System.getProperty("java.home"), "bin/java.exe").path
    val fakeMlat = File(System.getProperty("feeder.repo"), "tools/mlat-sim/FakeMlatServer.java").path
    val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    val home = Files.createTempDirectory("feeder-preview").toFile()
    val paths = FeederPaths(home)
    FeederSettings.update(paths) {
        it.copy(name = "preview", location = StationLocation.manual(43.0, -76.0, 100.0), antennaConfirmed = true)
    }

    // The feed's stand-in for feed1.adsbexchange.com: accepts and discards.
    val sink = ServerSocket(0, 1, loopback)
    thread(isDaemon = true) { runCatching { sink.accept().use { it.getInputStream().copyTo(java.io.OutputStream.nullOutputStream()) } } }
    // MLAT's stand-in: asks for the simulator's aircraft and reports 7 nearby feeders.
    val mlatPort = ServerSocket(0, 1, loopback).use { it.localPort }
    val mlat = ProcessBuilder(javaExe, fakeMlat, "$mlatPort", File(home, "mlat-summary.json").path, "600",
        "a1b2c3", "a4d5e6", "abcdef", "c0ffee", "0d15ea", "7c1234")
        .redirectErrorStream(true).redirectOutput(File(home, "fake-mlat.log")).start()
    Runtime.getRuntime().addShutdownHook(Thread { mlat.destroyForcibly() })
    Thread.sleep(3_000) // single-file java compiles before it listens

    val engine = FeederEngine(paths)
    engine.start(FeederEngine.TestRun(
        capture, StationLocation.manual(43.0, -76.0, 100.0)!!,
        feedTo = "127.0.0.1,${sink.localPort}", mlatServer = "127.0.0.1" to mlatPort,
    ))

    val updates = System.getProperty("feeder.previewRelease")?.let { previewUpdates(it, loopback) }

    application {
        Window(onCloseRequest = { updates?.shutdown(); engine.shutdown(); mlat.destroyForcibly(); exitApplication() },
            title = "ADS-B Exchange Feeder (preview)", state = rememberWindowState(width = 720.dp, height = 900.dp)) {
            LaunchedEffect(Unit) { engine.uiVisible = true }
            FeederApp(engine, paths, updates)
        }
    }
}

/** GitHub's "latest release" for the app's repository, from a stand-in offering [version]. */
private fun previewUpdates(version: String, loopback: InetAddress): UpdateChecker {
    val repo = BuildInfo.releasesRepo
    val server = HttpServer.create(InetSocketAddress(loopback, 0), 0).apply {
        createContext("/repos/$repo/releases/latest") { ex ->
            val body = """{"tag_name":"v$version","draft":false,"prerelease":false}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    return UpdateChecker(
        enabled = { true }, repo = repo, firstCheckMs = 3_000,
        latestUrl = URI("http://127.0.0.1:${server.address.port}/repos/$repo/releases/latest"),
    ).apply { start() }
}
