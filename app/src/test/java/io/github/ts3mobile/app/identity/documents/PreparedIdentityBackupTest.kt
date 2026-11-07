package io.github.ts3mobile.app.identity.documents

import io.github.ts3mobile.app.identity.backup.IdentityBackupCodec
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.spec.SecretKeySpec

class PreparedIdentityBackupTest {
    private val material = Ts3IdentityCodec.generate(0)
    private val password = "synthetic fixture password".toCharArray()
    private val codec = IdentityBackupCodec(deriveKey = { _, _, _ -> SecretKeySpec(ByteArray(32) { 7 }, "AES") })

    @Test fun onlyWriteCloseReopenAndAuthenticationVerifyTheOriginalExactPayload() {
        val document = MemoryDocument()
        PreparedIdentityBackup.prepare(material, password, codec).use { prepared ->
            assertEquals(Ts3IdentityCodec.inspect(material), prepared.summary)
            prepared.writeAndVerify(document)
        }
        assertEquals(listOf("write", "close", "reopen", "read-close"), document.order)
        assertArrayEquals(material.toByteArray(), codec.decrypt(document.bytes, password))
        assertTrue(document.bytes.size > IdentityBackupCodec.HEADER_BYTES)
    }

    @Test fun writeFailureCloseFailureAndDeniedReopenNeverReportVerifiedOrExposeProviderErrors() {
        for (stage in listOf("write", "close", "reopen", "read-close")) {
            val document = MemoryDocument(stage)
            PreparedIdentityBackup.prepare(material, password, codec).use { prepared ->
                val error = assertThrows(UnverifiedBackupDocument::class.java) { prepared.writeAndVerify(document) }
                assertEquals("Backup document could not be verified", error.message)
                assertNull(error.cause)
            }
        }
    }

    @Test fun truncatedTamperedAndAnotherAuthenticatedIdentityAllFailReadback() {
        val changed = Ts3IdentityCodec.generate(0)
        for (kind in 0..2) {
            val document =
                MemoryDocument(transform = { written ->
                    when (kind) {
                        0 -> written.copyOf(written.size - 1)
                        1 -> written.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                        else -> codec.encrypt(changed.toByteArray(), password)
                    }
                })
            PreparedIdentityBackup.prepare(material, password, codec).use { prepared ->
                assertThrows(UnverifiedBackupDocument::class.java) { prepared.writeAndVerify(document) }
            }
        }
    }

    @Test fun readbackIsBoundedByActualBytesAndClosesTheProviderInputOnFailure() {
        var reads = 0
        var closed = false
        val document =
            object : BackupDocument {
                override fun openWrite(): OutputStream = ByteArrayOutputStream()

                override fun openRead(): InputStream =
                    object : InputStream() {
                        override fun read(): Int {
                            reads++
                            return 1
                        }

                        override fun close() {
                            closed = true
                        }
                    }
            }
        PreparedIdentityBackup.prepare(material, password, codec).use { prepared ->
            assertThrows(UnverifiedBackupDocument::class.java) { prepared.writeAndVerify(document) }
        }
        assertEquals(IdentityBackupCodec.MAX_FILE_BYTES + 1, reads)
        assertTrue(closed)
    }

    @Test fun disposedOperationCannotWriteAValidBackupAndDoesNotEraseTheCallersPassword() {
        val secret = password.copyOf()
        val prepared = PreparedIdentityBackup.prepare(material, secret, codec)
        prepared.close()
        assertArrayEquals(password, secret)
        assertThrows(UnverifiedBackupDocument::class.java) { prepared.writeAndVerify(MemoryDocument()) }
    }

    private class MemoryDocument(
        private val failure: String? = null,
        private val transform: (ByteArray) -> ByteArray = { it },
    ) : BackupDocument {
        val order = mutableListOf<String>()
        var bytes = byteArrayOf()

        override fun openWrite(): OutputStream {
            order += "write"
            if (failure == "write") throw IOException("sensitive provider address")
            return object : ByteArrayOutputStream() {
                override fun close() {
                    order += "close"
                    if (failure == "close") throw IOException("sensitive provider address")
                    bytes = transform(toByteArray())
                }
            }
        }

        override fun openRead(): InputStream {
            order += "reopen"
            if (failure == "reopen") throw IOException("sensitive provider address")
            return object : ByteArrayInputStream(bytes) {
                override fun close() {
                    order += "read-close"
                    if (failure == "read-close") throw IOException("sensitive provider address")
                }
            }
        }
    }
}
