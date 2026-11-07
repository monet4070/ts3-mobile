package io.github.ts3mobile.app.identity.backup

/** Exact Unicode, no trim/normalization; inspect without constructing a password String. */
internal fun validateBackupPassphrase(passphrase: CharArray) {
    if (passphrase.size > 2_048) throw IdentityBackupException(IdentityBackupFailure.INVALID_PASSPHRASE)
    var index = 0
    var points = 0
    var hasNonWhitespace = false
    while (index < passphrase.size) {
        val first = passphrase[index++]
        if (first.isHighSurrogate()) {
            if (index == passphrase.size || !passphrase[index++].isLowSurrogate()) {
                throw IdentityBackupException(IdentityBackupFailure.INVALID_PASSPHRASE)
            }
            hasNonWhitespace = true
        } else {
            if (first.isLowSurrogate()) throw IdentityBackupException(IdentityBackupFailure.INVALID_PASSPHRASE)
            hasNonWhitespace = hasNonWhitespace || !first.isWhitespace()
        }
        points++
    }
    if (points !in 12..1_024 || !hasNonWhitespace) throw IdentityBackupException(IdentityBackupFailure.INVALID_PASSPHRASE)
}
