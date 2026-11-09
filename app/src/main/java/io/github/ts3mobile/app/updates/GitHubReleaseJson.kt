package io.github.ts3mobile.app.updates

import io.github.ts3mobile.app.PROJECT_REPOSITORY
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal data class ReleaseAsset(val name: String, val url: String, val digest: String?)

internal data class GitHubRelease(
    val name: String,
    val tag: String,
    val prerelease: Boolean,
    val publishedAt: String,
    val pageUrl: String,
    val notes: String,
    val assets: List<ReleaseAsset>,
)

internal fun parseGitHubRelease(
    json: String,
    channel: UpdateChannel,
): GitHubRelease? {
    val candidates =
        if (channel == UpdateChannel.STABLE) {
            listOf(JSONObject(json))
        } else {
            val releases = JSONArray(json)
            require(releases.length() <= 100)
            (0 until releases.length()).map(releases::getJSONObject)
        }
    val release =
        candidates.filter {
            !it.getBoolean("draft") && (channel == UpdateChannel.INCLUDE_PRERELEASES || !it.getBoolean("prerelease"))
        }.maxByOrNull { Instant.parse(it.getString("published_at")) } ?: return null
    val tag = release.getString("tag_name")
    val page = release.getString("html_url")
    require(isOfficialReleasePage(page) && uriPath(page) == "/$PROJECT_REPOSITORY/releases/tag/$tag")
    val assets = release.getJSONArray("assets")
    require(assets.length() <= 100)
    return GitHubRelease(
        release.optString("name").takeIf { it.isNotBlank() && it != "null" }?.take(160) ?: tag,
        tag,
        release.getBoolean("prerelease"),
        release.getString("published_at"),
        page,
        release.optString("body").takeUnless { it == "null" }.orEmpty().take(8_000),
        (0 until assets.length()).map { index ->
            val asset = assets.getJSONObject(index)
            ReleaseAsset(
                asset.getString("name"),
                asset.getString("browser_download_url"),
                asset.optString("digest").takeIf {
                    it != "null" && it.isNotEmpty()
                },
            )
        },
    )
}

internal fun parseReleaseBuildInfo(
    json: String,
    release: GitHubRelease,
): UpdateRelease {
    val manifest = JSONObject(json)
    require(manifest.get("schemaVersion").toString() == "1")
    require(manifest.getString("applicationId") == OFFICIAL_APPLICATION_ID)
    val codeText = manifest.get("versionCode").toString()
    require(codeText.matches(Regex("[1-9][0-9]{0,9}")))
    val code = codeText.toLong().also { require(it <= Int.MAX_VALUE) }
    val filename = manifest.getString("apkFile")
    require(filename.endsWith(".apk"))
    val apk = requireNotNull(release.assets.singleOrNull { it.name == filename })
    require(isOfficialReleaseAsset(apk.url, release.tag, filename))
    val digest = manifest.getString("apkSha256").lowercase().also(::requireDigest)
    apk.digest?.let { require(it == "sha256:$digest") }
    val signers = manifest.getJSONArray("signingCertificateSha256")
    require(signers.length() in 1..4)
    val certificates = (0 until signers.length()).map { signers.getString(it).lowercase().also(::requireDigest) }.toSet()
    val versionName = manifest.getString("versionName").also { require(it.isNotBlank() && it.length <= 80) }
    if (manifest.has("releaseTag")) require(manifest.getString("releaseTag") == release.tag)
    val minSdk = requireNotNull(manifest.get("minSdk").toString().toIntOrNull()).also { require(it in 1..10_000) }
    return UpdateRelease(
        release.name, versionName, code, release.prerelease, release.publishedAt, release.pageUrl,
        apk.url, release.notes, minSdk, certificates, digest,
    )
}

private fun requireDigest(value: String) = require(value.matches(Regex("[0-9a-f]{64}")))

private fun uriPath(value: String): String = java.net.URI(value).path
