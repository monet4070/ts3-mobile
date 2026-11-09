package io.github.ts3mobile.app.identity.documents

import io.github.ts3mobile.app.identity.backup.IdentityBackupCodec
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import io.github.ts3mobile.protocol.Ts3IdentitySummary
import java.security.MessageDigest

/** Sensitive operation resource; never a data class, observable state or persisted model. */
internal class PreparedIdentityBackup private constructor(
    private val container: ByteArray,
    private val passphrase: CharArray,
    private val expectedDigest: ByteArray,
    val summary: Ts3IdentitySummary,
    private val codec: IdentityBackupCodec,
) : AutoCloseable {
    fun writeAndVerify(document: BackupDocument) {
        try {
            document.openWrite().use { it.write(container) }
            val readback = document.openRead().use(codec::readBounded)
            try {
                val plaintext = codec.decrypt(readback, passphrase)
                try {
                    if (!MessageDigest.isEqual(expectedDigest, digest(plaintext))) throw UnverifiedBackupDocument()
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                readback.fill(0)
            }
        } catch (_: Exception) {
            // A close error is a failure too. Never expose provider/crypto messages or causes.
            throw UnverifiedBackupDocument()
        }
    }

    override fun close() {
        container.fill(0)
        passphrase.fill('\u0000')
        expectedDigest.fill(0)
    }

    companion object {
        fun prepare(
            material: String,
            password: CharArray,
            codec: IdentityBackupCodec = IdentityBackupCodec(),
        ): PreparedIdentityBackup {
            val summary = Ts3IdentityCodec.inspect(material)
            val plaintext = material.toByteArray(Charsets.UTF_8)
            val passphrase = password.copyOf()
            try {
                return PreparedIdentityBackup(codec.encrypt(plaintext, passphrase), passphrase, digest(plaintext), summary, codec)
            } catch (error: Exception) {
                passphrase.fill('\u0000')
                throw error
            } finally {
                plaintext.fill(0)
            }
        }

        private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
