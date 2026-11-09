package io.github.ts3mobile.app.updates

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

internal class UpdateHttpException(val status: Int) : java.io.IOException("Update HTTP $status")

/** No cookies, credentials or app data; bound time, redirects and decoded response bytes. */
internal class GitHubHttpClient(
    private val open: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
    private val clockNanos: () -> Long = System::nanoTime,
) {
    fun get(url: String): String {
        if (!isTrustedUpdateRedirect(url)) throw UpdateCheckException(UpdateFailure.INVALID_RELEASE)
        var target = url
        val deadline = clockNanos() + 12_000_000_000L
        repeat(4) {
            val connection = open(target)
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = remainingMillis(deadline)
                connection.readTimeout = remainingMillis(deadline)
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                connection.setRequestProperty("User-Agent", "TS3-Mobile-update-check")
                connection.setRequestProperty("Cache-Control", "no-cache")
                val status = connection.responseCode
                when {
                    status in setOf(301, 302, 303, 307, 308) -> {
                        val location = connection.getHeaderField("Location") ?: throw UpdateCheckException(UpdateFailure.SERVER)
                        val next =
                            try {
                                URI(target).resolve(location).toString()
                            } catch (_: IllegalArgumentException) {
                                throw UpdateCheckException(UpdateFailure.INVALID_RELEASE)
                            }
                        if (!isTrustedUpdateRedirect(next)) throw UpdateCheckException(UpdateFailure.INVALID_RELEASE)
                        target = next
                    }
                    status == 429 || (
                        status == 403 &&
                            (connection.getHeaderField("X-RateLimit-Remaining") == "0" || connection.getHeaderField("Retry-After") != null)
                    ) ->
                        throw UpdateCheckException(UpdateFailure.RATE_LIMITED)
                    status == 200 -> {
                        if (connection.contentLengthLong > MAX_BYTES) throw UpdateCheckException(UpdateFailure.INVALID_RELEASE)
                        return connection.inputStream.use { input ->
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                connection.readTimeout = remainingMillis(deadline)
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (bytes.size() + read > MAX_BYTES) throw UpdateCheckException(UpdateFailure.INVALID_RELEASE)
                                bytes.write(buffer, 0, read)
                            }
                            bytes.toString(Charsets.UTF_8.name())
                        }
                    }
                    else -> throw UpdateHttpException(status)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateCheckException(UpdateFailure.SERVER)
    }

    private fun remainingMillis(deadline: Long): Int {
        val remaining = (deadline - clockNanos()) / 1_000_000
        if (remaining <= 0) throw SocketTimeoutException("Update request timed out")
        return remaining.coerceAtMost(5_000).toInt().coerceAtLeast(1)
    }

    private companion object {
        const val MAX_BYTES = 1_048_576
    }
}
