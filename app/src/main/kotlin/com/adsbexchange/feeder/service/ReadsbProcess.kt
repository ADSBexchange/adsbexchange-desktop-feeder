package com.adsbexchange.feeder.service

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * readsb.exe as a child process.
 *
 * - Stopping: readsb is built to shut down cleanly when its stdin closes
 *   (READSB_EXIT_ON_STDIN_EOF; see native/compat/win_compat.c). [stop] closes the pipe and waits,
 *   then kills it if it hangs.
 * - If the app dies, Windows closes that pipe and readsb exits by itself. The process is also in
 *   a kill-on-close job ([Win32.endWithThisProcess]), so even a hung readsb can't outlive the app.
 * - readsb's stdout and stderr are appended to [logFile]; the engine appends its own `[feeder]`
 *   lines there too.
 */
internal class ReadsbProcess private constructor(private val process: Process) {
    val pid: Long get() = process.pid()
    val isAlive: Boolean get() = process.isAlive
    /** Null while running. */
    val exitCode: Int? get() = if (process.isAlive) null else process.exitValue()

    fun stop(timeoutMs: Long = STOP_TIMEOUT_MS): Int? {
        runCatching { process.outputStream.close() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        return exitCode
    }

    companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        /** The log starts afresh (keeping one old copy) once it passes this size, at a readsb start. */
        const val LOG_ROTATE_BYTES = 512 * 1024L

        /** readsb.exe: from Gradle's run task (not in the installed app), or next to the packaged app's resources. */
        fun findExecutable(): File? {
            developmentOverride("feeder.readsb")?.let { File(it) }?.takeIf { it.isFile }?.let { return it }
            System.getProperty("compose.application.resources.dir")?.let { File(it, "readsb.exe") }
                ?.takeIf { it.isFile }?.let { return it }
            return null
        }

        /** Runs [exe] in [workDir], which relative paths in [args] are relative to. */
        fun start(exe: File, args: List<String>, logFile: File, workDir: File): ReadsbProcess {
            logFile.parentFile.mkdirs()
            workDir.mkdirs()
            rotate(logFile)
            val pb = ProcessBuilder(listOf(exe.absolutePath) + args)
                .directory(workDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectInput(ProcessBuilder.Redirect.PIPE)
            pb.environment()["READSB_EXIT_ON_STDIN_EOF"] = "1"
            val p = pb.start()
            Win32.endWithThisProcess(p.pid())
            return ReadsbProcess(p)
        }

        private fun rotate(logFile: File) {
            if (logFile.length() > LOG_ROTATE_BYTES) {
                runCatching {
                    Files.move(logFile.toPath(), File(logFile.path + ".1").toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
