package io.github.ts3mobile.app.updates

import io.github.ts3mobile.app.PROJECT_REPOSITORY
import io.github.ts3mobile.app.PROJECT_REPOSITORY_URL
import java.net.URI

internal const val RELEASES_PAGE_URL = "$PROJECT_REPOSITORY_URL/releases"
internal const val STABLE_RELEASE_API = "https://api.github.com/repos/$PROJECT_REPOSITORY/releases/latest"
internal const val ALL_RELEASES_API = "https://api.github.com/repos/$PROJECT_REPOSITORY/releases?per_page=100"
internal const val OFFICIAL_APPLICATION_ID = "io.github.ts3mobile.app"

internal enum class UpdateCompatibility { MATCHING_INSTALL, DIFFERENT_INSTALL, ANDROID_TOO_OLD }

internal fun UpdateRelease.isNewerThan(installed: InstalledAppVersion): Boolean = versionCode > installed.code

internal fun UpdateRelease.compatibilityWith(installed: InstalledAppVersion): UpdateCompatibility =
    when {
        installed.sdk < minSdk -> UpdateCompatibility.ANDROID_TOO_OLD
        installed.applicationId != OFFICIAL_APPLICATION_ID ||
            certificates != installed.certificates.map(String::lowercase).toSet() -> UpdateCompatibility.DIFFERENT_INSTALL
        else -> UpdateCompatibility.MATCHING_INSTALL
    }

/** Only fixed repository destinations become clickable; release descriptions never supply navigation. */
internal fun isOfficialReleasePage(url: String): Boolean {
    val uri = secureUri(url) ?: return false
    return uri.host == "github.com" && uri.rawQuery == null &&
        (uri.path == "/$PROJECT_REPOSITORY/releases" || uri.path.startsWith("/$PROJECT_REPOSITORY/releases/tag/"))
}

internal fun isOfficialReleaseAsset(
    url: String,
    tag: String,
    filename: String,
): Boolean {
    if (!tag.matches(Regex("v?[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?"))) return false
    if (!filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) return false
    val uri = secureUri(url) ?: return false
    return uri.host == "github.com" && uri.rawQuery == null &&
        uri.path == "/$PROJECT_REPOSITORY/releases/download/$tag/$filename"
}

internal fun isTrustedUpdateRedirect(url: String): Boolean {
    val uri = secureUri(url) ?: return false
    return when (uri.host) {
        "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> true
        "github.com" -> uri.path.startsWith("/$PROJECT_REPOSITORY/releases/download/")
        "api.github.com" -> uri.path == "/repos/$PROJECT_REPOSITORY/releases" || uri.path.startsWith("/repos/$PROJECT_REPOSITORY/releases/")
        else -> false
    }
}

private fun secureUri(url: String): URI? =
    try {
        URI(url).takeIf {
            it.scheme == "https" && it.path != null && it.rawUserInfo == null && it.rawFragment == null && it.port in listOf(-1, 443) &&
                !it.path.orEmpty().split('/').any { segment -> segment == "." || segment == ".." }
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.net.URISyntaxException) {
        null
    }
