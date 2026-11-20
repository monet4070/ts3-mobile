package io.github.ts3mobile.app.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class GitHubHttpClientTest {
    @Test fun successfulRequestClosesTheConnectionAndSendsNoCredentials() {
        val connection = Connection()
        assertEquals("{}", GitHubHttpClient({ connection }).get(STABLE_RELEASE_API))
        assertTrue(connection.closed)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertNull(connection.getRequestProperty("Authorization"))
        assertNull(connection.getRequestProperty("Cookie"))
        assertEquals("2022-11-28", connection.getRequestProperty("X-GitHub-Api-Version"))
    }

    @Test fun officialRedirectsAreBoundedAndExternalRedirectsAreNotOpened() {
        val first = Connection(status = 302).apply { headers["Location"] = "https://release-assets.githubusercontent.com/fixture" }
        val last = Connection()
        val opened = mutableListOf<String>()
        assertEquals(
            "{}",
            GitHubHttpClient({ url ->
                opened += url
                if (opened.size == 1) first else last
            }).get(STABLE_RELEASE_API),
        )
        assertEquals(2, opened.size)
        assertTrue(first.closed && last.closed)
        first.headers["Location"] = "https://example.com/untrusted"
        var count = 0
        val failure =
            assertThrows(UpdateCheckException::class.java) {
                GitHubHttpClient({
                    count++
                    first
                }).get(STABLE_RELEASE_API)
            }
        assertEquals(UpdateFailure.INVALID_RELEASE, failure.failure)
        assertEquals(1, count)
    }

    @Test fun redirectLoopsStopWithoutUnboundedRequests() {
        var requests = 0
        val connection = Connection(status = 302).apply { headers["Location"] = STABLE_RELEASE_API }
        assertThrows(UpdateCheckException::class.java) {
            GitHubHttpClient({
                requests++
                connection
            }).get(STABLE_RELEASE_API)
        }
        assertEquals(4, requests)
    }

    @Test fun declaredAndStreamingOversizedMetadataAreRejected() {
        val declared = Connection().apply { size = 1_048_577 }
        assertThrows(UpdateCheckException::class.java) { GitHubHttpClient({ declared }).get(STABLE_RELEASE_API) }
        assertFalse(declared.read)
        val streamed = Connection(ByteArray(1_048_577)).apply { size = -1 }
        assertThrows(UpdateCheckException::class.java) { GitHubHttpClient({ streamed }).get(STABLE_RELEASE_API) }
        assertTrue(streamed.closed)
    }

    @Test fun rateLimitsAndOtherHttpFailuresRemainDifferentFromSuccessfulChecks() {
        for (status in listOf(403, 429)) {
            val connection = Connection(status = status).apply { headers["X-RateLimit-Remaining"] = "0" }
            val failure = assertThrows(UpdateCheckException::class.java) { GitHubHttpClient({ connection }).get(STABLE_RELEASE_API) }
            assertEquals(UpdateFailure.RATE_LIMITED, failure.failure)
        }
        val failure =
            assertThrows(UpdateHttpException::class.java) { GitHubHttpClient({ Connection(status = 404) }).get(STABLE_RELEASE_API) }
        assertEquals(404, failure.status)
        val secondary = Connection(status = 403).apply { headers["Retry-After"] = "60" }
        assertEquals(
            UpdateFailure.RATE_LIMITED,
            assertThrows(UpdateCheckException::class.java) { GitHubHttpClient({ secondary }).get(STABLE_RELEASE_API) }.failure,
        )
    }

    @Test fun aSpentSharedDeadlineTimesOutBeforeDialing() {
        var now = 0L
        var dials = 0
        val client =
            GitHubHttpClient({
                dials++
                Connection()
            }) { now }
        assertEquals("{}", client.get(STABLE_RELEASE_API))
        now = UPDATE_CHECK_BUDGET_NANOS + 1L
        assertThrows(SocketTimeoutException::class.java) { client.get(STABLE_RELEASE_API, UPDATE_CHECK_BUDGET_NANOS) }
        assertEquals("An exhausted budget must not dial again", 1, dials)
    }

    @Test fun everyRequestIsCappedSoOneSlowRequestCannotSpendTheWholeBudget() {
        val connection = Connection()
        val client = GitHubHttpClient({ connection }) { 0L }
        assertEquals("{}", client.get(STABLE_RELEASE_API))
        assertTrue(connection.connectTimeout <= UPDATE_REQUEST_CAP_NANOS / 1_000_000)
        assertTrue(connection.readTimeout <= UPDATE_REQUEST_CAP_NANOS / 1_000_000)
    }

    @Test fun redirectsCannotBeFollowedPastTheSharedDeadline() {
        var now = 0L
        val opened = mutableListOf<String>()
        // The 302 arrives in time; the clock only runs out after the redirect branch is entered.
        val redirect =
            Connection(
                status = 302,
                onResponseCode = { now = UPDATE_CHECK_BUDGET_NANOS },
            ).apply { headers["Location"] = "https://release-assets.githubusercontent.com/fixture" }
        val client =
            GitHubHttpClient({ url ->
                opened += url
                redirect
            }) { now }
        assertThrows(SocketTimeoutException::class.java) { client.get(STABLE_RELEASE_API) }
        assertEquals("The follow-up request must not be dialed", 1, opened.size)
        assertTrue(redirect.closed)
    }

    @Test fun accumulatedBodyReadsTimeOutEvenThoughTheHeadersArrivedInTime() {
        var now = 0L
        var reads = 0
        val slowBody =
            object : InputStream() {
                override fun read(): Int = -1

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    reads++
                    now += 2_000_000_000L
                    val count = minOf(length, 16)
                    buffer.fill('a'.code.toByte(), offset, offset + count)
                    return count
                }
            }
        val connection =
            Connection().apply {
                size = -1
                stream = slowBody
            }
        val client = GitHubHttpClient({ connection }) { now }
        assertThrows(SocketTimeoutException::class.java) {
            client.get(STABLE_RELEASE_API, deadlineNanos = UPDATE_REQUEST_CAP_NANOS)
        }
        assertTrue("Several reads must have consumed the deadline, not one", reads >= 4)
        assertTrue(connection.closed)
    }

    private class Connection(
        private val bytes: ByteArray = "{}".toByteArray(),
        private val status: Int = 200,
        private val onResponseCode: () -> Unit = {},
    ) : HttpURLConnection(URL(STABLE_RELEASE_API)) {
        val headers = mutableMapOf<String, String>()
        var closed = false
        var read = false
        var size = bytes.size.toLong()
        var stream: InputStream? = null

        override fun getResponseCode(): Int {
            onResponseCode()
            return status
        }

        override fun getContentLengthLong() = size

        override fun getHeaderField(name: String): String? = headers[name]

        override fun getInputStream() = (stream ?: ByteArrayInputStream(bytes)).also { read = true }

        override fun disconnect() {
            closed = true
        }

        override fun usingProxy() = false

        override fun connect() = Unit
    }
}
