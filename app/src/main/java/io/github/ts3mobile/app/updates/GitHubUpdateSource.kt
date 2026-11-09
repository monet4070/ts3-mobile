package io.github.ts3mobile.app.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class GitHubUpdateSource(private val fetch: (String) -> String = GitHubHttpClient()::get) : UpdateSource {
    override suspend fun latest(channel: UpdateChannel): UpdateRelease? =
        withContext(Dispatchers.IO) {
            val stablePayload = fetchApi(STABLE_RELEASE_API, allowMissing = true)
            currentCoroutineContext().ensureActive()
            val recentPayload = if (channel == UpdateChannel.INCLUDE_PRERELEASES) fetchApi(ALL_RELEASES_API) else null
            try {
                currentCoroutineContext().ensureActive()
                val stable = stablePayload?.let { parseGitHubRelease(it, UpdateChannel.STABLE) }
                val recent = recentPayload?.let { parseGitHubRelease(it, UpdateChannel.INCLUDE_PRERELEASES) }
                val releases = listOfNotNull(stable, recent).distinctBy { it.tag }
                releases.map { release ->
                    currentCoroutineContext().ensureActive()
                    val manifest = requireNotNull(release.assets.singleOrNull { it.name == "BUILDINFO.json" })
                    require(isOfficialReleaseAsset(manifest.url, release.tag, manifest.name))
                    parseReleaseBuildInfo(fetch(manifest.url), release)
                }.maxByOrNull { it.versionCode }
            } catch (error: UpdateCheckException) {
                throw error
            } catch (_: org.json.JSONException) {
                throw UpdateCheckException(UpdateFailure.INVALID_RELEASE)
            } catch (_: IllegalArgumentException) {
                throw UpdateCheckException(UpdateFailure.INVALID_RELEASE)
            } catch (_: java.time.DateTimeException) {
                throw UpdateCheckException(UpdateFailure.INVALID_RELEASE)
            } catch (_: UpdateHttpException) {
                throw UpdateCheckException(UpdateFailure.INVALID_RELEASE)
            }
        }

    private fun fetchApi(
        url: String,
        allowMissing: Boolean = false,
    ): String? =
        try {
            fetch(url)
        } catch (error: UpdateHttpException) {
            if (allowMissing && error.status == 404) null else throw UpdateCheckException(UpdateFailure.SERVER)
        }
}
