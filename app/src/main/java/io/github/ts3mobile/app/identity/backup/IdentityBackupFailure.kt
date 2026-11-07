package io.github.ts3mobile.app.identity.backup

internal enum class IdentityBackupFailure {
    INVALID_PASSPHRASE,
    INVALID_FORMAT,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_ALGORITHM,
    RESOURCE_LIMIT,
    AUTHENTICATION_FAILED,
    CRYPTO_UNAVAILABLE,
}

/** Fixed category only. Never retain provider exception text, input or passphrase. */
internal class IdentityBackupException(val failure: IdentityBackupFailure) : Exception("Identity backup: ${failure.name}")
