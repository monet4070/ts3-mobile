package io.github.ts3mobile.app.identity.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Bounded, authenticated portable container; no Android/Keystore or identity mutation. */
internal class IdentityBackupCodec(
    private val random: SecureRandom = SecureRandom(),
    private val deriveKey: (CharArray, ByteArray, Int) -> SecretKey = ::deriveBackupKey,
) {
    fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        validateBackupPassphrase(passphrase)
        if (plaintext.isEmpty() || plaintext.size > MAX_PAYLOAD_BYTES) {
            throw IdentityBackupException(IdentityBackupFailure.RESOURCE_LIMIT)
        }
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header =
            ByteBuffer.allocate(HEADER_BYTES)
                .put(MAGIC).putShort(1).put(1).put(1).putInt(EXPORT_ITERATIONS).putInt(plaintext.size)
                .put(salt).put(nonce).array()
        return crypto {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, EXPORT_ITERATIONS), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            header + cipher.doFinal(plaintext)
        }
    }

    fun decrypt(
        container: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        validateBackupPassphrase(passphrase)
        val parsed = parse(container)
        return crypto {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, parsed.salt, parsed.iterations), GCMParameterSpec(128, parsed.nonce))
            cipher.updateAAD(container, 0, HEADER_BYTES)
            cipher.doFinal(container, HEADER_BYTES, container.size - HEADER_BYTES)
        }
    }

    /** Do not close caller-owned document streams. Bound actual reads, not provider metadata. */
    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val remaining = MAX_FILE_BYTES - output.size()
            val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
            if (count < 0) return output.toByteArray()
            if (count == 0) {
                // InputStream implementations may return zero; require actual progress.
                val byte = input.read()
                if (byte < 0) return output.toByteArray()
                if (remaining == 0) throw IdentityBackupException(IdentityBackupFailure.RESOURCE_LIMIT)
                output.write(byte)
            } else {
                if (count > remaining) throw IdentityBackupException(IdentityBackupFailure.RESOURCE_LIMIT)
                output.write(buffer, 0, count)
            }
        }
    }

    private fun parse(container: ByteArray): Header {
        if (container.size > MAX_FILE_BYTES) throw IdentityBackupException(IdentityBackupFailure.RESOURCE_LIMIT)
        if (container.size < HEADER_BYTES + TAG_BYTES) throw IdentityBackupException(IdentityBackupFailure.INVALID_FORMAT)
        val bytes = ByteBuffer.wrap(container)
        val magic = ByteArray(MAGIC.size).also(bytes::get)
        if (!magic.contentEquals(MAGIC)) throw IdentityBackupException(IdentityBackupFailure.INVALID_FORMAT)
        if (bytes.short.toInt() != 1) throw IdentityBackupException(IdentityBackupFailure.UNSUPPORTED_VERSION)
        if (bytes.get().toInt() != 1 || bytes.get().toInt() != 1) {
            throw IdentityBackupException(IdentityBackupFailure.UNSUPPORTED_ALGORITHM)
        }
        val iterations = bytes.int
        val payloadBytes = bytes.int
        if (iterations !in EXPORT_ITERATIONS..MAX_IMPORT_ITERATIONS || payloadBytes !in 1..MAX_PAYLOAD_BYTES) {
            throw IdentityBackupException(IdentityBackupFailure.RESOURCE_LIMIT)
        }
        if (container.size.toLong() != HEADER_BYTES.toLong() + payloadBytes + TAG_BYTES) {
            throw IdentityBackupException(IdentityBackupFailure.INVALID_FORMAT)
        }
        return Header(iterations, ByteArray(16).also(bytes::get), ByteArray(12).also(bytes::get))
    }

    private fun <T> crypto(operation: () -> T): T =
        try {
            operation()
        } catch (_: BadPaddingException) {
            throw IdentityBackupException(IdentityBackupFailure.AUTHENTICATION_FAILED)
        } catch (_: GeneralSecurityException) {
            throw IdentityBackupException(IdentityBackupFailure.CRYPTO_UNAVAILABLE)
        }

    private class Header(val iterations: Int, val salt: ByteArray, val nonce: ByteArray)

    companion object {
        const val HEADER_BYTES = 56
        const val TAG_BYTES = 16
        const val EXPORT_ITERATIONS = 600_000
        const val MAX_IMPORT_ITERATIONS = 2_000_000
        const val MAX_PAYLOAD_BYTES = 64 * 1_024
        const val MAX_FILE_BYTES = 1_024 * 1_024
        private val MAGIC = "TS3M-ID-BACKUP\u0000\u0000".toByteArray(Charsets.US_ASCII)
    }
}
