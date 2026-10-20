package com.adsbexchange.feeder.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes [text] to [file] through a temporary file and an atomic replace, so a reader (or a crash)
 * never sees half a file. The temporary file is flushed to disk before the replace, so a power cut
 * can't leave the new name on empty contents (as the phone feeder, b55b130). Not File.renameTo: on
 * Windows it refuses to replace an existing file.
 *
 * Windows also refuses to replace a file another program has open at that moment, as an antivirus
 * scanner or the search indexer briefly does after each write. That passes within moments, so the
 * replace is tried again for up to about a second before giving up (FilesTest).
 */
internal fun writeAtomically(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    FileOutputStream(tmp).use {
        it.write(text.toByteArray(Charsets.UTF_8))
        it.fd.sync()
    }
    var attempt = 0
    while (true) {
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (e: AccessDeniedException) {
            if (++attempt >= REPLACE_ATTEMPTS) throw e
            Thread.sleep(20L * attempt)
        }
    }
}

/** 20, 40, ... 180 ms between tries: about a second in all. */
private const val REPLACE_ATTEMPTS = 10

/**
 * [file]'s text, or null if it doesn't exist. A file that exists but can't be read (another program
 * has it locked, as an antivirus scanner can for a moment) is tried again for about a second, as
 * [writeAtomically] does; after that the IOException is thrown, so the caller can tell "missing"
 * from "unreadable" and never mistake the second for the first.
 */
internal fun readTextIfExists(file: File): String? = readIfExists(file) { it.readText() }

/** As [readTextIfExists], with [read] doing the reading. */
internal fun <T> readIfExists(file: File, read: (File) -> T): T? {
    var attempt = 0
    while (true) {
        if (!file.exists()) return null
        try {
            return read(file)
        } catch (e: IOException) {
            if (++attempt >= READ_ATTEMPTS) throw e
            Thread.sleep(20L * attempt)
        }
    }
}

/** 20, 40, ... 180 ms between tries: about a second in all. */
private const val READ_ATTEMPTS = 10

/**
 * Keeps a copy of [file] beside it as `<name>.unreadable-<date-time>`, before it's replaced because
 * it couldn't be understood, so what was in it can still be recovered. Returns the copy, or null if
 * even copying failed.
 */
internal fun keepUnreadableCopy(file: File): File? = runCatching {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val copy = File(file.parentFile, "${file.name}.unreadable-$stamp")
    Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
    copy
}.getOrNull()

/** Appends a `[feeder]` line to readsb.log, so the app's lines and readsb's read in order. */
fun appendAppLog(paths: FeederPaths, message: String) {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    runCatching {
        paths.root.mkdirs()
        paths.logFile.appendText("[$time] [feeder] $message\n")
    }
}

/** An exception for the log: its type and message, and where it was thrown (a few frames). */
fun describeError(e: Throwable): String = buildString {
    append(e.javaClass.simpleName)
    e.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    e.stackTrace.take(4).forEach { append(" | at ").append(it) }
    e.cause?.takeIf { it !== e }?.let { append(" | caused by ").append(it.javaClass.simpleName).append(": ").append(it.message) }
}
