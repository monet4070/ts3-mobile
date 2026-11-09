package io.github.ts3mobile.app.updates

internal enum class UpdateChannel { STABLE, INCLUDE_PRERELEASES }

internal data class InstalledAppVersion(
    val name: String,
    val code: Long,
    val applicationId: String,
    val sdk: Int,
    val certificates: Set<String>,
)

internal data class UpdateRelease(
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val prerelease: Boolean,
    val publishedAt: String,
    val pageUrl: String,
    val apkUrl: String,
    val notes: String,
    val minSdk: Int,
    val certificates: Set<String>,
    val apkSha256: String,
)

internal enum class UpdateFailure { NETWORK, TIMEOUT, RATE_LIMITED, SERVER, INVALID_RELEASE }

internal class UpdateCheckException(val failure: UpdateFailure) : java.io.IOException(failure.name)

internal sealed interface UpdateCheck {
    data object Idle : UpdateCheck

    data object Checking : UpdateCheck

    data object NoRelease : UpdateCheck

    data class Complete(val release: UpdateRelease) : UpdateCheck

    data class Failed(val failure: UpdateFailure) : UpdateCheck
}

internal enum class UpdatePreferenceStatus { LOADING, SAVING, SAVED, READ_FAILED, WRITE_FAILED }

internal data class UpdateState(
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val preferenceStatus: UpdatePreferenceStatus = UpdatePreferenceStatus.LOADING,
    val check: UpdateCheck = UpdateCheck.Idle,
)

internal fun interface UpdateSource {
    suspend fun latest(channel: UpdateChannel): UpdateRelease?
}

internal interface UpdateChannelStore {
    suspend fun read(): UpdateChannel

    suspend fun write(channel: UpdateChannel)
}
