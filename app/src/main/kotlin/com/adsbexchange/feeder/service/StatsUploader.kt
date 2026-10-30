package com.adsbexchange.feeder.service

import com.adsbexchange.feeder.data.describeError
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPOutputStream
import kotlin.math.floor
import kotlin.random.Random

/**
 * ADS-B Exchange's stats package (github.com/ADSBexchange/adsbexchange-stats, its `json-status`
 * script) as part of the app: every 5 s, readsb's aircraft.json with the feeder UUID and signal
 * figures added, gzipped and posted to ADS-B Exchange. It's what fills the feeder's own stats page
 * ([statsPageUrl]). The feeder kits make it an optional extra; here it's part of feeding, paused only
 * on a metered connection (FeederSettings.statsOnMetered).
 *
 * Same request as the script: same URL, headers and body, so ADS-B Exchange can't tell the two
 * apart beyond the UUID. One difference: readsb's per-aircraft distance and bearing from the
 * receiver (r_dst, r_dir), from which the receiver's position can be worked out, are only sent
 * when MLAT already sends ADS-B Exchange the position ([Config.includeRange]).
 *
 * Runs on a thread of its own, so a slow upload (up to 10 s) never holds up the feeder engine.
 */
class StatsUploader(private val config: Config, private val log: (String) -> Unit) {
    data class Config(
        val uuid: String,
        val aircraftJson: File,
        /** Keep r_dst and r_dir: only when MLAT already gives ADS-B Exchange the receiver position. */
        val includeRange: Boolean,
        val url: URI = UPLOAD_URL,
        val intervalMs: Long = INTERVAL_MS,
    )

    /** For the Status screen. */
    data class Stats(
        val uploads: Long = 0,
        val bytesSent: Long = 0,
        val lastOkMs: Long? = null,
        /** Why the latest attempt failed; null once one succeeds. */
        val error: String? = null,
    )

    @Volatile var stats = Stats()
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::run, "stats-upload").apply { isDaemon = true; start() }
        log("stats: sending ADS-B Exchange this feeder's stats every ${config.intervalMs / 1000} s" +
            if (config.includeRange) "" else " (without aircraft distances from the receiver)")
    }

    fun stop() {
        running = false
        thread?.interrupt()
        runCatching { thread?.join(2_000) }
        thread = null
    }

    private fun run() {
        try {
            // As the script: a random start within the first interval spreads feeders' uploads out.
            Thread.sleep(Random.nextLong(config.intervalMs))
            while (running) {
                val began = System.nanoTime()
                // Anything unexpected counts as a failed round, rather than ending the uploads unseen.
                val ok = try {
                    uploadOnce()
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    fail("unexpected error: ${describeError(e)}")
                    false
                }
                // After a failure, wait a little longer (5-20 s in the script).
                val extra = if (ok) 0 else config.intervalMs + Random.nextLong(config.intervalMs * 3)
                val elapsedMs = (System.nanoTime() - began) / 1_000_000
                Thread.sleep((config.intervalMs + extra - elapsedMs).coerceAtLeast(0))
            }
        } catch (_: InterruptedException) {
            // stop()
        }
    }

    /** One upload. False if it failed (and should be retried after a longer pause). */
    private fun uploadOnce(): Boolean {
        val file = config.aircraftJson
        val age = System.currentTimeMillis() - file.lastModified()
        // Nothing current to send (readsb is starting, or stuck): skip rather than send stale data.
        if (!file.isFile || age > STALE_MS) return true
        val body = try {
            payload(file.readText(), config.uuid, config.includeRange)
        } catch (e: Exception) {
            // Usually readsb replacing the file at that moment; the next round reads the new one.
            return true
        } ?: run {
            fail("aircraft.json not in the expected format; not sent")
            return false
        }
        val request = HttpRequest.newBuilder(config.url)
            .timeout(Duration.ofSeconds(10))
            .header("adsbx-uuid", config.uuid)
            // Spelt as the script spells it. ADS-B Exchange's server expects this request as is.
            .header("Content_Encoding", "gzip")
            // What curl --data-binary sends.
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val error = try {
            post(request)
        } catch (e: IOException) {
            // The HTTP client keeps the connection between uploads (the script's curl connects
            // afresh each time), and ADS-B Exchange's end closes connections every 5 minutes or so.
            // An upload sent just as that happens gets no reply ("header parser received no
            // bytes", about 30 a day). Sending the stats twice is harmless, so try once more
            // straight away: the client has dropped the failed connection, so this one is fresh.
            // HTTP error replies aren't retried.
            try {
                post(request)
            } catch (e2: IOException) {
                e2.message?.takeIf { it.isNotBlank() } ?: e2.javaClass.simpleName
            }
        }
        if (error != null) {
            fail(error)
            return false
        }
        if (stats.error != null) log("stats: sending again")
        stats = stats.copy(uploads = stats.uploads + 1, bytesSent = stats.bytesSent + body.size,
            lastOkMs = System.currentTimeMillis(), error = null)
        return true
    }

    /** One POST: null if accepted, else "HTTP <code>". Network failures throw. */
    private fun post(request: HttpRequest): String? {
        val code = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        return if (code in 200..299) null else "HTTP $code"
    }

    /** Logged once per run of failures, not every 5 s. */
    private fun fail(error: String) {
        if (stats.error == null) log("stats: not sent ($error); trying again")
        stats = stats.copy(error = error)
    }

    companion object {
        /** From adsbexchange-stats' json-status (REMOTE_URL). */
        val UPLOAD_URL: URI = URI("https://adsbexchange.com/api/receive/")
        /** json-status' WAIT_TIME. */
        const val INTERVAL_MS = 5_000L
        /** readsb rewrites aircraft.json every second; older than this, it isn't running properly. */
        private const val STALE_MS = 30_000L

        /** The feeder's stats page, as adsbexchange-stats' `adsbexchange-showurl` prints it. */
        fun statsPageUrl(uuid: String) = "https://www.adsbexchange.com/api/feeders/?feed=$uuid"

        private val client: HttpClient by lazy {
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
        }

        /** readsb's form for these (json_out.c): `,"r_dst":%.3f,"r_dir":%.1f`. */
        private val RANGE_FIELDS = Regex(""","r_dst":-?[0-9.]+,"r_dir":-?[0-9.]+""")

        /**
         * The gzipped upload: [aircraftJson] as readsb wrote it, plus what json-status' jq adds
         * (uuid, v, rssi, rssi-min, rssi-max). null if it isn't readsb's aircraft.json, or if r_dst
         * is to be left out and couldn't be (so a format change can't leak it).
         */
        internal fun payload(aircraftJson: String, uuid: String, includeRange: Boolean): ByteArray? {
            val json = payloadJson(aircraftJson, uuid, includeRange) ?: return null
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            return out.toByteArray()
        }

        /**
         * readsb's text is kept as it is (re-encoding it would change how numbers are written) and
         * the new fields go in before its closing brace, which is what jq's output amounts to.
         */
        internal fun payloadJson(aircraftJson: String, uuid: String, includeRange: Boolean): String? {
            val parsed = JSONObject(aircraftJson)
            val aircraft = parsed.optJSONArray("aircraft") ?: return null
            val text = if (includeRange) aircraftJson else aircraftJson.replace(RANGE_FIELDS, "")
            if (!includeRange && ("\"r_dst\"" in text || "\"r_dir\"" in text)) return null
            val end = text.lastIndexOf('}')
            if (end < 0) return null
            // jq: (.aircraft[].rssi | add / length | floor), min | floor, max | floor; 0 with no aircraft.
            val rssi = (0 until aircraft.length())
                .mapNotNull { aircraft.optJSONObject(it)?.optDouble("rssi")?.takeUnless(Double::isNaN) }
            fun whole(d: Double?) = d?.let { floor(it).toLong() } ?: 0L
            val extra = JSONObject()
                .put("uuid", uuid)
                // The script's Raspberry Pi throttling flags (vcgencmd); a PC has none.
                .put("v", "")
                .put("rssi", whole(rssi.takeIf { it.isNotEmpty() }?.average()))
                .put("rssi-min", whole(rssi.minOrNull()))
                .put("rssi-max", whole(rssi.maxOrNull()))
            return buildString {
                append(text, 0, end)
                append(',').append(extra.toString().removePrefix("{").removeSuffix("}"))
                append(text, end, text.length)
            }
        }
    }
}
