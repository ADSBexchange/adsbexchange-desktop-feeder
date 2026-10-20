package com.adsbexchange.feeder.service

import com.adsbexchange.feeder.BuildInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Looks for a newer version: the latest release of [repo] on GitHub (drafts and pre-releases
 * never count), shortly after start and then every [everyMs], while [enabled]. The window shows
 * one prominently; the user downloads it from the release page. No automatic installs yet.
 *
 * GitHub only shows a public repository's releases without signing in, and the app holds no
 * credentials, so [repo] must be public; for a private one the check quietly finds nothing.
 * The request tells GitHub this PC's address and the app's version, nothing else.
 */
class UpdateChecker(
    private val enabled: () -> Boolean,
    private val currentVersion: String = BuildInfo.version,
    private val repo: String = BuildInfo.releasesRepo,
    /** GitHub's "latest release" API for [repo]; a local stand-in in tests and the preview. */
    private val latestUrl: URI = URI("https://api.github.com/repos/$repo/releases/latest"),
    private val firstCheckMs: Long = 60_000,
    private val everyMs: Long = 12 * 60 * 60_000L,
    private val log: (String) -> Unit = {},
) {
    /** A release newer than this build. [url] is its GitHub page, built here, never taken from the reply. */
    data class Update(val version: String, val url: String)

    data class State(
        val available: Update? = null,
        val checkedMs: Long? = null,
        val checking: Boolean = false,
        /** The last check's failure, for Settings; null when it worked or there's no release yet. */
        val error: String? = null,
        /** The repository has no published release (or isn't public). */
        val noReleases: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "update-check").apply { isDaemon = true } }
    private var schedule: ScheduledFuture<*>? = null
    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    }

    fun start() {
        schedule = worker.scheduleWithFixedDelay({ if (enabled()) runCatching { check() } }, firstCheckMs, everyMs, TimeUnit.MILLISECONDS)
    }

    /** Settings' "Check now": whatever the setting, and at once. */
    fun checkNow() {
        worker.execute { runCatching { check() } }
    }

    fun shutdown() {
        schedule?.cancel(false)
        worker.shutdownNow()
    }

    private fun check() {
        _state.update { it.copy(checking = true) }
        val result = try {
            val request = HttpRequest.newBuilder(latestUrl)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ADS-B-Exchange-Feeder/$currentVersion")
                .GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> State(available = newerRelease(response.body(), repo, currentVersion))
                // No release published yet, or the repository isn't public: nothing to offer.
                404 -> State(noReleases = true)
                else -> State(error = "GitHub replied ${response.statusCode()}")
            }
        } catch (e: Exception) {
            State(error = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        }
        val before = _state.value.available
        // A check that failed (offline, say) knows nothing new: keep an update already found, rather
        // than hide its notice until the next check that works.
        val kept = if (result.error != null) result.copy(available = before) else result
        _state.value = kept.copy(checkedMs = System.currentTimeMillis())
        if (result.available != null && result.available != before) log("update: version ${result.available.version} is available")
        if (result.error != null) log("update check failed: ${result.error}")
    }

    companion object {
        private val REPO = Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}")
        private val TAG = Regex("[A-Za-z0-9._-]{1,64}")

        /** "v0.6.0", "0.6.0" or "0.6" as numbers; null for anything else (e.g. "dev", "0.6.0-beta"). */
        fun parseVersion(s: String): List<Int>? {
            val parts = s.trim().removePrefix("v").removePrefix("V").split('.')
            if (parts.isEmpty() || parts.size > 4) return null
            return parts.map { p -> p.toIntOrNull()?.takeIf { it >= 0 && p.length <= 6 } ?: return null }
        }

        /** Whether [candidate] is a later version than [current]; never for a build without a version ("dev"). */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = parseVersion(candidate) ?: return false
            val b = parseVersion(current) ?: return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** The release in GitHub's reply, if it's a published, final, newer version; else null. */
        fun newerRelease(json: String, repo: String, currentVersion: String): Update? {
            if (!REPO.matches(repo)) return null
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val tag = o.optString("tag_name").takeIf { TAG.matches(it) } ?: return null
            if (!isNewer(tag, currentVersion)) return null
            val version = parseVersion(tag)!!.joinToString(".")
            return Update(version, "https://github.com/$repo/releases/tag/$tag")
        }
    }
}
