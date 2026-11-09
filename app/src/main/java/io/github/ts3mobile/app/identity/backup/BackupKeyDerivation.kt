package io.github.ts3mobile.app.identity.backup

import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Platform primitive only; caller validates work bounds before reaching this function. */
internal fun deriveBackupKey(
    passphrase: CharArray,
    salt: ByteArray,
    iterations: Int,
): SecretKey {
    val specification = PBEKeySpec(passphrase, salt, iterations, 256)
    try {
        val encoded = SecretKeyFactory.getInstance("PBKDF2withHmacSHA256").generateSecret(specification).encoded
        return try {
            SecretKeySpec(encoded, "AES")
        } finally {
            encoded.fill(0)
        }
    } finally {
        specification.clearPassword()
    }
}
