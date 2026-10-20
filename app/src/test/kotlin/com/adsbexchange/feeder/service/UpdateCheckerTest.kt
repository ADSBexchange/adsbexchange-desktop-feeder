package com.adsbexchange.feeder.service

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class UpdateCheckerTest {
    private val repo = "ADSBexchange/adsbexchange-desktop-feeder"

    /** A stand-in for GitHub's "latest release" API on 127.0.0.1. */
    private var status = 200
    private var body = release("v0.6.0")
    private val requests = AtomicInteger()
    private var userAgent: String? = null
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
        createContext("/repos/$repo/releases/latest") { ex ->
            requests.incrementAndGet()
            userAgent = ex.requestHeaders.getFirst("User-Agent")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val url = URI("http://127.0.0.1:${server.address.port}/repos/$repo/releases/latest")

    @After
    fun tearDown() = server.stop(0)

    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, htmlUrl: String = "https://github.com/$repo/releases/tag/$tag") =
        """{"tag_name":"$tag","name":"Release $tag","draft":$draft,"prerelease":$prerelease,"html_url":"$htmlUrl"}"""

    private fun checked(current: String = "0.5.16", enabled: Boolean = true): UpdateChecker.State {
        val checker = UpdateChecker(enabled = { enabled }, currentVersion = current, repo = repo, latestUrl = url)
        try {
            checker.checkNow()
            val deadline = System.currentTimeMillis() + 10_000
            while (checker.state.value.checkedMs == null) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("no check")
                Thread.sleep(20)
            }
            return checker.state.value
        } finally {
            checker.shutdown()
        }
    }

    @Test
    fun comparesVersionsAsNumbers() {
        assertTrue(UpdateChecker.isNewer("v0.6.0", "0.5.16"))
        assertTrue("0.5.16 is after 0.5.9", UpdateChecker.isNewer("0.5.16", "0.5.9"))
        assertFalse(UpdateChecker.isNewer("v0.5.9", "0.5.16"))
        assertFalse("same version", UpdateChecker.isNewer("v0.5.16", "0.5.16"))
        assertTrue(UpdateChecker.isNewer("v1.0", "0.9.9"))
        assertFalse("1.0 and 1.0.0 are the same", UpdateChecker.isNewer("1.0.0", "1.0"))
        assertFalse("a development build never nags", UpdateChecker.isNewer("v9.9.9", "dev"))
        assertFalse("pre-release tags don't count", UpdateChecker.isNewer("v0.6.0-beta", "0.5.16"))
        assertFalse(UpdateChecker.isNewer("latest", "0.5.16"))
    }

    @Test
    fun offersOnlyPublishedFinalNewerReleases() {
        val r = UpdateChecker.newerRelease(release("v0.6.0"), repo, "0.5.16")
        assertEquals(UpdateChecker.Update("0.6.0", "https://github.com/$repo/releases/tag/v0.6.0"), r)
        assertNull("draft", UpdateChecker.newerRelease(release("v0.6.0", draft = true), repo, "0.5.16"))
        assertNull("pre-release", UpdateChecker.newerRelease(release("v0.6.0", prerelease = true), repo, "0.5.16"))
        assertNull("not newer", UpdateChecker.newerRelease(release("v0.5.16"), repo, "0.5.16"))
        assertNull("not JSON", UpdateChecker.newerRelease("<html>", repo, "0.5.16"))
        assertNull("odd tag", UpdateChecker.newerRelease(release("v0.6.0/../../x"), repo, "0.5.16"))
    }

    /** The download link is built from the repository and tag, never taken from the reply. */
    @Test
    fun neverOpensALinkFromTheReply() {
        val r = UpdateChecker.newerRelease(release("v0.6.0", htmlUrl = "https://evil.example/installer.msi"), repo, "0.5.16")
        assertEquals("https://github.com/$repo/releases/tag/v0.6.0", r?.url)
    }

    @Test
    fun findsANewReleaseOnTheServer() {
        val s = checked()
        assertEquals("0.6.0", s.available?.version)
        assertNull(s.error)
        assertEquals("GitHub asks for a User-Agent", "ADS-B-Exchange-Feeder/0.5.16", userAgent)
    }

    @Test
    fun saysNothingWhenUpToDateOrNoReleaseOrPrivate() {
        body = release("v0.5.16")
        assertNull(checked().available)
        // No release yet, or a private repository: GitHub says 404. Not an error.
        status = 404
        body = """{"message":"Not Found"}"""
        val s = checked()
        assertNull(s.available)
        assertNull(s.error)
        assertTrue(s.noReleases)
    }

    @Test
    fun reportsOtherFailures() {
        status = 500
        val s = checked()
        assertNull(s.available)
        assertEquals("GitHub replied 500", s.error)
    }

    @Test
    fun aFailedCheckKeepsAnUpdateAlreadyFound() {
        val checker = UpdateChecker(enabled = { true }, currentVersion = "0.5.16", repo = repo, latestUrl = url)
        try {
            fun checkAndWait() {
                val before = checker.state.value.checkedMs
                checker.checkNow()
                val deadline = System.currentTimeMillis() + 10_000
                while (checker.state.value.checkedMs == before || checker.state.value.checking) {
                    if (System.currentTimeMillis() > deadline) throw AssertionError("no check")
                    Thread.sleep(20)
                }
            }
            checkAndWait()
            assertEquals("0.6.0", checker.state.value.available?.version)
            Thread.sleep(5) // a later checkedMs
            status = 500
            checkAndWait()
            assertEquals("GitHub replied 500", checker.state.value.error)
            assertEquals("still offered after a failed check", "0.6.0", checker.state.value.available?.version)
            Thread.sleep(5)
            status = 404
            body = ""
            checkAndWait()
            assertNull("a check that works and finds none clears it", checker.state.value.available)
        } finally {
            checker.shutdown()
        }
    }

    @Test
    fun theScheduledCheckRespectsTheSetting() {
        val off = UpdateChecker(enabled = { false }, currentVersion = "0.5.16", repo = repo, latestUrl = url, firstCheckMs = 50, everyMs = 50)
        off.start()
        Thread.sleep(400)
        off.shutdown()
        assertEquals("no requests while it's off", 0, requests.get())

        val on = UpdateChecker(enabled = { true }, currentVersion = "0.5.16", repo = repo, latestUrl = url, firstCheckMs = 50, everyMs = 60_000)
        on.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (on.state.value.available == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        on.shutdown()
        assertEquals("0.6.0", on.state.value.available?.version)
    }
}
