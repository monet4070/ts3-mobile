package io.github.ts3mobile.app.crypto

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Legacy v1 only: keep standard Base64, provider IV, tag and absence of AAD unchanged. */
internal class KeystorePayloadCipher(private val keys: PayloadKeyAccess) {
    fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.getOrCreateKeyForNewData())
        val encoder = Base64.getEncoder()
        return listOf("v1", encoder.encodeToString(cipher.iv), encoder.encodeToString(cipher.doFinal(plaintext))).joinToString(":")
    }

    fun decrypt(payload: String): ByteArray {
        val parts = payload.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == "v1") { "Unsupported local encrypted payload" }
        // Match legacy Android Base64's tolerance of whitespace; writes never wrap.
        val decoder = Base64.getMimeDecoder()
        val iv = decoder.decode(parts[1])
        val encrypted = decoder.decode(parts[2])
        val key = keys.findExistingKey() ?: throw MissingLocalCipherKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }
}
