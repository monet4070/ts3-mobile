package io.github.ts3mobile.app.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
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

    @Test fun totalDeadlineExpiresAndStillClosesTheConnection() {
        var calls = 0
        val connection = Connection()
        val client = GitHubHttpClient({ connection }) { if (calls++ == 0) 0 else 13_000_000_000L }
        assertThrows(SocketTimeoutException::class.java) { client.get(STABLE_RELEASE_API) }
        assertTrue(connection.closed)
    }

    private class Connection(private val bytes: ByteArray = "{}".toByteArray(), private val status: Int = 200) : HttpURLConnection(
        URL(STABLE_RELEASE_API),
    ) {
        val headers = mutableMapOf<String, String>()
        var closed = false
        var read = false
        var size = bytes.size.toLong()

        override fun getResponseCode() = status

        override fun getContentLengthLong() = size

        override fun getHeaderField(name: String): String? = headers[name]

        override fun getInputStream() = ByteArrayInputStream(bytes).also { read = true }

        override fun disconnect() {
            closed = true
        }

        override fun usingProxy() = false

        override fun connect() = Unit
    }
}
