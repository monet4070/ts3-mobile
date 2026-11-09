package io.github.ts3mobile.app.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val installed = InstalledAppVersion("1.0.0", 14, OFFICIAL_APPLICATION_ID, 35, setOf("b".repeat(64)))

    @Test fun sameNameReissueIsAnUpdateOnlyWhenAndroidVersionCodeIncreases() {
        assertTrue(release(15).isNewerThan(installed))
        assertFalse(release(14).isNewerThan(installed))
        assertFalse(release(13).copy(versionName = "9.0.0").isNewerThan(installed))
    }

    @Test fun compatibilityDoesNotPromiseDebugOrSideLoadUpgradesOrUnsupportedAndroid() {
        assertEquals(UpdateCompatibility.MATCHING_INSTALL, release(15).compatibilityWith(installed))
        assertEquals(
            UpdateCompatibility.DIFFERENT_INSTALL,
            release(15).compatibilityWith(installed.copy(applicationId = "io.github.ts3mobile.fixture")),
        )
        assertEquals(
            UpdateCompatibility.DIFFERENT_INSTALL,
            release(15).compatibilityWith(installed.copy(certificates = setOf("c".repeat(64)))),
        )
        assertEquals(UpdateCompatibility.ANDROID_TOO_OLD, release(15).copy(minSdk = 36).compatibilityWith(installed))
        assertEquals(
            UpdateCompatibility.DIFFERENT_INSTALL,
            release(15).copy(certificates = setOf("b".repeat(64), "c".repeat(64))).compatibilityWith(installed),
        )
    }

    @Test fun onlyOfficialHttpsReleaseDestinationsAreClickable() {
        assertTrue(isOfficialReleasePage(RELEASES_PAGE_URL))
        assertTrue(isOfficialReleasePage("$RELEASES_PAGE_URL/tag/v1.0.0"))
        assertTrue(isOfficialReleaseAsset("$RELEASES_PAGE_URL/download/v1.0.0/app.apk", "v1.0.0", "app.apk"))
        for (url in listOf(
            "http://github.com/monet4070/ts3-mobile/releases/tag/v1.0.0",
            "https://github.com.example.com/monet4070/ts3-mobile/releases/tag/v1.0.0",
            "https://someone@github.com/monet4070/ts3-mobile/releases/tag/v1.0.0",
            "https://github.com/another/repository/releases/tag/v1.0.0",
            "$RELEASES_PAGE_URL/tag/v1.0.0#fragment",
            "https://github.com:8443/monet4070/ts3-mobile/releases/tag/v1.0.0",
        )) assertFalse(url, isOfficialReleasePage(url))
        assertFalse(isOfficialReleaseAsset("$RELEASES_PAGE_URL/download/v1.0.0/other.apk", "v1.0.0", "app.apk"))
        assertFalse(isOfficialReleaseAsset("$RELEASES_PAGE_URL/download/v1.0.0/app.apk", "../v1.0.0", "app.apk"))
    }

    @Test fun assetRedirectsCannotDowngradeTransportOrLeaveGitHubHosts() {
        assertTrue(isTrustedUpdateRedirect("https://release-assets.githubusercontent.com/asset?token=synthetic"))
        assertFalse(isTrustedUpdateRedirect("http://release-assets.githubusercontent.com/asset"))
        assertFalse(isTrustedUpdateRedirect("https://example.com/asset"))
        assertFalse(isTrustedUpdateRedirect("https://github.com/monet4070/ts3-mobile/releases/download/../other"))
    }
}

internal fun release(code: Long = 15) =
    UpdateRelease(
        "Fixture release", "1.0.0", code, false, "2026-11-09T01:00:00Z",
        "$RELEASES_PAGE_URL/tag/v1.0.0", "$RELEASES_PAGE_URL/download/v1.0.0/app.apk",
        "Synthetic release notes", 26, setOf("b".repeat(64)), "a".repeat(64),
    )
