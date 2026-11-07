package io.github.ts3mobile.app.identity.documents

import io.github.ts3mobile.app.identity.backup.IdentityBackupCodec
import io.github.ts3mobile.app.identity.backup.IdentityBackupException
import io.github.ts3mobile.protocol.InvalidIdentityMaterial
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import io.github.ts3mobile.protocol.Ts3IdentitySummary
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal enum class IdentityImportFailure { DOCUMENT_UNREADABLE, INVALID_IDENTITY }

internal class IdentityImportException(val failure: IdentityImportFailure) : Exception("Identity import: ${failure.name}")

/** Private validated candidate. No private-key String field, saved state, URI or passphrase. */
internal class ImportedIdentity private constructor(private val plaintext: ByteArray, val summary: Ts3IdentitySummary) : AutoCloseable {
    private var closed = false

    fun materialForCommit(): String {
        if (closed) throw IdentityImportException(IdentityImportFailure.INVALID_IDENTITY)
        return decode(plaintext)
    }

    override fun close() {
        closed = true
        plaintext.fill(0)
    }

    companion object {
        fun read(
            document: BackupDocument,
            passphrase: CharArray,
            codec: IdentityBackupCodec = IdentityBackupCodec(),
        ): ImportedIdentity {
            var plaintext: ByteArray? = null
            try {
                val container = document.openRead().use(codec::readBounded)
                try {
                    plaintext = codec.decrypt(container, passphrase)
                } finally {
                    container.fill(0)
                }
                val summary = Ts3IdentityCodec.inspect(decode(checkNotNull(plaintext)))
                return ImportedIdentity(checkNotNull(plaintext), summary).also { plaintext = null }
            } catch (error: IdentityBackupException) {
                throw error
            } catch (_: CharacterCodingException) {
                throw IdentityImportException(IdentityImportFailure.INVALID_IDENTITY)
            } catch (_: InvalidIdentityMaterial) {
                throw IdentityImportException(IdentityImportFailure.INVALID_IDENTITY)
            } catch (_: Exception) {
                throw IdentityImportException(IdentityImportFailure.DOCUMENT_UNREADABLE)
            } finally {
                plaintext?.fill(0)
            }
        }

        private fun decode(bytes: ByteArray): String =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
    }
}
