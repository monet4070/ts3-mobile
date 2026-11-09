package io.github.ts3mobile.app.crypto

import java.security.GeneralSecurityException
import javax.crypto.SecretKey

internal interface PayloadKeyAccess {
    fun findExistingKey(): SecretKey?

    fun getOrCreateKeyForNewData(): SecretKey
}

/** Missing key is unrecoverable from a local ciphertext alone; never create one during a read. */
internal class MissingLocalCipherKey : GeneralSecurityException("Existing local encryption key is unavailable")
