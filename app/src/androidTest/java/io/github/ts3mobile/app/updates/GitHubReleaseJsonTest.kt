package io.github.ts3mobile.app.updates

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** All transport is an in-memory fixture; this suite never contacts GitHub. */
class GitHubReleaseJsonTest {
    @Test fun stableAndPrereleaseChannelsSelectTheirOwnPublishedRelease() =
        runBlocking {
            val stable = releaseJson("v1.0.0", "2026-11-08T01:00:00Z")
            val preview = releaseJson("v1.1.0-rc.1", "2026-11-09T01:00:00Z", prerelease = true)
            val draft = releaseJson("v9.0.0", "2026-11-10T01:00:00Z").put("draft", true)
            val calls = mutableListOf<String>()
            val source =
                GitHubUpdateSource { url ->
                    calls += url
                    when (url) {
                        STABLE_RELEASE_API -> stable.toString()
                        ALL_RELEASES_API -> JSONArray(listOf(stable, draft, preview)).toString()
                        "$RELEASES_PAGE_URL/download/v1.0.0/BUILDINFO.json" -> buildInfo(14, "v1.0.0").toString()
                        "$RELEASES_PAGE_URL/download/v1.1.0-rc.1/BUILDINFO.json" -> buildInfo(15, "v1.1.0-rc.1").toString()
                        else -> error("Unexpected fixture URL")
                    }
                }
            assertFalse(source.latest(UpdateChannel.STABLE)!!.prerelease)
            val result = source.latest(UpdateChannel.INCLUDE_PRERELEASES)!!
            assertTrue(result.prerelease)
            assertEquals(15L, result.versionCode)
            assertEquals(
                listOf(
                    STABLE_RELEASE_API,
                    "$RELEASES_PAGE_URL/download/v1.0.0/BUILDINFO.json",
                    STABLE_RELEASE_API,
                    ALL_RELEASES_API,
                    "$RELEASES_PAGE_URL/download/v1.0.0/BUILDINFO.json",
                    "$RELEASES_PAGE_URL/download/v1.1.0-rc.1/BUILDINFO.json",
                ),
                calls,
            )
        }

    @Test fun includingTestsStillOffersAHigherCodeStableEvenWhenPublicationDatesAreOutOfOrder() =
        runBlocking {
            val stable = releaseJson("v1.0.0", "2026-11-01T01:00:00Z")
            val preview = releaseJson("v1.1.0-rc.1", "2026-11-09T01:00:00Z", true)
            val source =
                GitHubUpdateSource { url ->
                    when (url) {
                        STABLE_RELEASE_API -> stable.toString()
                        ALL_RELEASES_API -> JSONArray(listOf(preview)).toString()
                        "$RELEASES_PAGE_URL/download/v1.0.0/BUILDINFO.json" -> buildInfo(20).toString()
                        "$RELEASES_PAGE_URL/download/v1.1.0-rc.1/BUILDINFO.json" -> buildInfo(19, "v1.1.0-rc.1").toString()
                        else -> error("Unexpected fixture URL")
                    }
                }
            val result = source.latest(UpdateChannel.INCLUDE_PRERELEASES)!!
            assertEquals(20L, result.versionCode)
            assertFalse(result.prerelease)
        }

    @Test fun includingPrereleasesCanStillChooseANewerStablePublication() {
        val preview = releaseJson("v1.1.0-rc.1", "2026-11-08T01:00:00Z", true)
        val stable = releaseJson("v1.1.0", "2026-11-09T01:00:00Z")
        val release = parseGitHubRelease(JSONArray(listOf(stable, preview)).toString(), UpdateChannel.INCLUDE_PRERELEASES)!!
        assertEquals("v1.1.0", release.tag)
        assertFalse(release.prerelease)
    }

    @Test fun stable404MeansNoReleaseWhileMissingMetadataIsAFailedCheck() =
        runBlocking {
            assertEquals(null, GitHubUpdateSource { throw UpdateHttpException(404) }.latest(UpdateChannel.STABLE))
            val missing = releaseJson().put("assets", JSONArray())
            val source = GitHubUpdateSource { missing.toString() }
            val failure = runCatching { source.latest(UpdateChannel.STABLE) }.exceptionOrNull() as UpdateCheckException
            assertEquals(UpdateFailure.INVALID_RELEASE, failure.failure)
        }

    @Test fun wrongRepositoryAndDuplicateManifestAssetsAreRejected() =
        runBlocking {
            for (json in listOf(
                releaseJson().put("html_url", "https://example.com/releases/tag/v1.0.0"),
                releaseJson().apply { getJSONArray("assets").put(asset("BUILDINFO.json", "v1.0.0")) },
            )) {
                val failure =
                    runCatching {
                        GitHubUpdateSource { json.toString() }.latest(
                            UpdateChannel.STABLE,
                        )
                    }.exceptionOrNull() as UpdateCheckException
                assertEquals(UpdateFailure.INVALID_RELEASE, failure.failure)
            }
        }

    @Test fun inconsistentBuildInformationCannotProduceADownload() {
        val release = parseGitHubRelease(releaseJson().toString(), UpdateChannel.STABLE)!!
        for (manifest in listOf(
            buildInfo().put("versionCode", 14.5),
            buildInfo().put("versionCode", -1),
            buildInfo().put("versionCode", 9_999_999_999L),
            buildInfo().put("applicationId", "another.application"),
            buildInfo().put("apkFile", "missing.apk"),
            buildInfo().put("apkSha256", "bad"),
            buildInfo().put("signingCertificateSha256", JSONArray()),
            buildInfo().put("releaseTag", "v2.0.0"),
            buildInfo().put("schemaVersion", 99),
        )) assertThrows(IllegalArgumentException::class.java) { parseReleaseBuildInfo(manifest.toString(), release) }
    }

    @Test fun assetDigestMismatchAndAnExternalApkUrlAreRejected() {
        val wrongHash = releaseJson().apply { getJSONArray("assets").getJSONObject(1).put("digest", "sha256:" + "f".repeat(64)) }
        val external =
            releaseJson().apply {
                getJSONArray(
                    "assets",
                ).getJSONObject(1).put("browser_download_url", "https://example.com/app.apk")
            }
        for (json in listOf(wrongHash, external)) {
            val release = parseGitHubRelease(json.toString(), UpdateChannel.STABLE)!!
            assertThrows(IllegalArgumentException::class.java) { parseReleaseBuildInfo(buildInfo().toString(), release) }
        }
    }

    @Test fun sameNameHigherCodeAndNullDescriptionsAreHandledWithoutVersionNameComparison() {
        val release =
            parseGitHubRelease(
                releaseJson().put("body", JSONObject.NULL).put("name", JSONObject.NULL).toString(),
                UpdateChannel.STABLE,
            )!!
        val result = parseReleaseBuildInfo(buildInfo(15).toString(), release)
        assertEquals("1.0.0", result.versionName)
        assertEquals(15L, result.versionCode)
        assertEquals("v1.0.0", result.name)
        assertEquals("", result.notes)
    }
}

private fun releaseJson(
    tag: String = "v1.0.0",
    published: String = "2026-11-09T01:00:00Z",
    prerelease: Boolean = false,
) = JSONObject().apply {
    put("tag_name", tag)
    put("name", "Fixture $tag")
    put("published_at", published)
    put("draft", false)
    put("prerelease", prerelease)
    put("html_url", "$RELEASES_PAGE_URL/tag/$tag")
    put("body", "Synthetic notes; never downloaded from a real service")
    put("assets", JSONArray(listOf(asset("BUILDINFO.json", tag), asset("app.apk", tag))))
}

private fun asset(
    name: String,
    tag: String,
) = JSONObject().apply {
    put("name", name)
    put("browser_download_url", "$RELEASES_PAGE_URL/download/$tag/$name")
    if (name.endsWith(".apk")) put("digest", "sha256:" + "a".repeat(64))
}

private fun buildInfo(
    code: Long = 14,
    tag: String = "v1.0.0",
) = JSONObject().apply {
    put("schemaVersion", 1)
    put("applicationId", OFFICIAL_APPLICATION_ID)
    put("versionName", "1.0.0")
    put("versionCode", code)
    put("releaseTag", tag)
    put("apkFile", "app.apk")
    put("apkSha256", "a".repeat(64))
    put("signingCertificateSha256", JSONArray(listOf("b".repeat(64))))
    put("minSdk", 26)
}
