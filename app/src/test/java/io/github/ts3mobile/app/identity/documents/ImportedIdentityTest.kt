package io.github.ts3mobile.app.identity.documents

import io.github.ts3mobile.app.identity.backup.IdentityBackupCodec
import io.github.ts3mobile.app.identity.backup.IdentityBackupException
import io.github.ts3mobile.app.identity.backup.IdentityBackupFailure
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import javax.crypto.spec.SecretKeySpec

class ImportedIdentityTest {
    private val material = Ts3IdentityCodec.generate(3)
    private val password = "synthetic import password".toCharArray()
    private val fast = IdentityBackupCodec(deriveKey = { _, _, _ -> SecretKeySpec(ByteArray(32) { 7 }, "AES") })

    @Test fun validCandidatePreservesExactPrivateMaterialUidOffsetAndSecurityUntilDisposed() {
        ImportedIdentity.read(Document(fast.encrypt(material.toByteArray(), password)), password, fast).use { imported ->
            assertEquals(Ts3IdentityCodec.inspect(material), imported.summary)
            assertEquals(material, imported.materialForCommit())
            assertEquals(20, java.util.Base64.getDecoder().decode(imported.summary.uid).size)
        }
        val disposed = ImportedIdentity.read(Document(fast.encrypt(material.toByteArray(), password)), password, fast)
        disposed.close()
        assertThrows(IdentityImportException::class.java) { disposed.materialForCommit() }
    }

    @Test fun invalidUtf8AndAuthenticatedNonIdentityAreRejectedWithoutSensitiveCauses() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), "fixture private garbage".toByteArray())) {
            val error =
                assertThrows(IdentityImportException::class.java) {
                    ImportedIdentity.read(Document(fast.encrypt(bytes, password)), password, fast)
                }
            assertEquals(IdentityImportFailure.INVALID_IDENTITY, error.failure)
            assertNull(error.cause)
            assertEquals("Identity import: INVALID_IDENTITY", error.message)
        }
    }

    @Test fun unknownVersionMaliciousKdfAndHugeActualStreamFailBeforeDerivingAnyKey() {
        val original = fast.encrypt(material.toByteArray(), password)
        val bounded = IdentityBackupCodec(deriveKey = { _, _, _ -> error("KDF must not run") })
        val variants =
            listOf(
                original.copyOf().also { ByteBuffer.wrap(it).putShort(16, 2) } to IdentityBackupFailure.UNSUPPORTED_VERSION,
                original.copyOf().also { ByteBuffer.wrap(it).putInt(20, Int.MAX_VALUE) } to IdentityBackupFailure.RESOURCE_LIMIT,
                ByteArray(IdentityBackupCodec.MAX_FILE_BYTES + 1) to IdentityBackupFailure.RESOURCE_LIMIT,
            )
        variants.forEach { (bytes, expected) ->
            val error = assertThrows(IdentityBackupException::class.java) { ImportedIdentity.read(Document(bytes), password, bounded) }
            assertEquals(expected, error.failure)
        }
    }

    @Test fun realPasswordAuthenticationFailsBeforeAnyCandidateIsExposed() {
        val codec = IdentityBackupCodec()
        val bytes = codec.encrypt(material.toByteArray(), password)
        val error =
            assertThrows(IdentityBackupException::class.java) {
                ImportedIdentity.read(Document(bytes), "wrong synthetic password".toCharArray(), codec)
            }
        assertEquals(IdentityBackupFailure.AUTHENTICATION_FAILED, error.failure)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(IdentityBackupException::class.java) { ImportedIdentity.read(Document(bytes), password, codec) }
    }

    @Test fun readAndCloseFailuresAreExplicitAndCloseFailureCannotPublishACandidate() {
        for (failClose in listOf(false, true)) {
            var closed = false
            val document =
                object : BackupDocument {
                    override fun openWrite() = error("Import must never write the source")

                    override fun openRead(): InputStream {
                        if (!failClose) throw SecurityException("sensitive provider details")
                        return object : ByteArrayInputStream(fast.encrypt(material.toByteArray(), password)) {
                            override fun close() {
                                closed = true
                                throw java.io.IOException("sensitive provider details")
                            }
                        }
                    }
                }
            val error = assertThrows(IdentityImportException::class.java) { ImportedIdentity.read(document, password, fast) }
            assertEquals(IdentityImportFailure.DOCUMENT_UNREADABLE, error.failure)
            assertNull(error.cause)
            if (failClose) assertTrue(closed)
        }
    }

    private class Document(private val bytes: ByteArray) : BackupDocument {
        override fun openWrite() = error("Import must not write the source")

        override fun openRead() = ByteArrayInputStream(bytes)
    }
}
