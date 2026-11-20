package io.github.ts3mobile.app.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException

/** One user-initiated check: every fetch draws from a single budget. */
internal class GitHubUpdateSource(
    private val fetch: UpdateFetch = defaultUpdateFetch(),
    private val clockNanos: () -> Long = System::nanoTime,
) : UpdateSource {
    override suspend fun latest(channel: UpdateChannel): UpdateRelease? =
        withContext(Dispatchers.IO) {
            val budget = clockNanos() + UPDATE_CHECK_BUDGET_NANOS
            val stablePayload = fetchApi(STABLE_RELEASE_API, budget, allowMissing = true)
            currentCoroutineContext().ensureActive()
            val recentPayload =
                if (channel == UpdateChannel.INCLUDE_PRERELEASES) fetchApi(ALL_RELEASES_API, budget) else null
            try {
                currentCoroutineContext().ensureActive()
                val stable = stablePayload?.let { parseGitHubRelease(it, UpdateChannel.STABLE) }
                val recent = recentPayload?.let { parseGitHubRelease(it, UpdateChannel.INCLUDE_PRERELEASES) }
                val releases = listOfNotNull(stable, recent).distinctBy { it.tag }
                releases.map { release ->
                    currentCoroutineContext().ensureActive()
                    val manifest = requireNotNull(release.assets.singleOrNull { it.name == "BUILDINFO.json" })
                    require(isOfficialReleaseAsset(manifest.url, release.tag, manifest.name))
                    parseReleaseBuildInfo(fetchManifest(manifest.url, budget), release)
                }.maxByOrNull { it.versionCode }
            } catch (error: UpdateCheckException) {
                throw error
            } catch (_: org.json.JSONException) {
                throw UpdateCheckException(UpdateFailure.INVALID_RELEASE)
            } catch (_: IllegalArgumentException) {
                throw UpdateCheckException(UpdateFailure.INVALID_RELEASE)
            } catch (_: java.time.DateTimeException) {
                throw UpdateCheckException(UpdateFailure.INVALID_RELEASE)
            }
        }

    /** A listed asset that vanished is inconsistent metadata; a transport failure is the server's. */
    private fun fetchManifest(
        url: String,
        budget: Long,
    ): String =
        try {
            fetch.get(url, requestDeadline(budget))
        } catch (error: UpdateHttpException) {
            throw UpdateCheckException(failureForManifestStatus(error.status))
        }

    private fun fetchApi(
        url: String,
        budget: Long,
        allowMissing: Boolean = false,
    ): String? =
        try {
            fetch.get(url, requestDeadline(budget))
        } catch (error: UpdateHttpException) {
            if (allowMissing && error.status == 404) null else throw UpdateCheckException(UpdateFailure.SERVER)
        }

    /**
     * Reports an expired budget as a timeout and refuses to start another request, so a slow
     * check fails fast instead of multiplying the per-request wait.
     */
    private fun requestDeadline(budget: Long): Long {
        val now = clockNanos()
        if (budget - now <= 0L) throw SocketTimeoutException("Update check budget exhausted")
        return minOf(budget, now + UPDATE_REQUEST_CAP_NANOS)
    }

    internal companion object {
        fun failureForManifestStatus(status: Int): UpdateFailure =
            when {
                status == 404 || status == 410 -> UpdateFailure.INVALID_RELEASE
                else -> UpdateFailure.SERVER
            }
    }
}

private fun defaultUpdateFetch(): UpdateFetch {
    val client = GitHubHttpClient()
    return UpdateFetch { url, deadline -> client.get(url, deadline) }
}
