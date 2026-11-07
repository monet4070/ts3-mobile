package io.github.ts3mobile.app.identity

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.crypto.AndroidKeystoreKeys
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher
import io.github.ts3mobile.app.identity.backup.IdentityBackupFailure
import io.github.ts3mobile.app.identity.documents.AndroidBackupDocument
import io.github.ts3mobile.app.identity.documents.PreparedIdentityBackup
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Two independent scratch files and Keystore aliases; no production alias/file mutation. */
class IndependentIdentityRestoreTest {
    @Test fun authenticatedDocumentRestoresAcrossIndependentKeysOnlyAfterConfirmationAndRollbackPreservesTheTargetIdentity() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val resolver = instrumentation.targetContext.contentResolver
            val source = Scratch()
            val target = Scratch()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val documentUri = Uri.parse("content://io.github.ts3mobile.app.test.backup-documents/${UUID.randomUUID()}")
            val document = AndroidBackupDocument(resolver, documentUri)
            val password = "synthetic cross install password".toCharArray()
            var controller: IdentityRestoreController? = null
            try {
                val original = Ts3IdentityCodec.generate(3)
                val targetOriginal = Ts3IdentityCodec.generate(0)
                source.repository.replace(original, source.repository.snapshot().revision)
                target.repository.replace(targetOriginal, target.repository.snapshot().revision)
                // The target key cannot read the source installation envelope.
                assertThrows(java.security.GeneralSecurityException::class.java) {
                    target.cipher.decrypt(source.cipher.encrypt(byteArrayOf(1)))
                }
                val sourceRaw = source.file.readBytes()
                val targetRaw = target.file.readBytes()
                withContext(Dispatchers.IO) {
                    PreparedIdentityBackup.prepare(source.repository.readForBackup(), password).use { it.writeAndVerify(document) }
                }
                val restores = IdentityRestoreController(scope, target.repository, {})
                controller = restores
                instrumentation.runOnMainSync {
                    restores.beginImport("wrong synthetic password".toCharArray())
                    restores.picked(document)
                }
                withTimeout(10_000) { restores.state.first { it.status == IdentityRestoreStatus.FAILED } }
                assertEquals(IdentityBackupFailure.AUTHENTICATION_FAILED, restores.state.value.backupFailure)
                assertArrayEquals(targetRaw, target.file.readBytes())
                instrumentation.runOnMainSync {
                    restores.beginImport(password.copyOf())
                    restores.picked(document)
                }
                val preview = withTimeout(10_000) { restores.state.first { it.status == IdentityRestoreStatus.PREVIEW } }.preview!!
                assertEquals(Ts3IdentityCodec.inspect(original), preview.proposed)
                assertEquals(Ts3IdentityCodec.inspect(targetOriginal), preview.previous.active)
                assertArrayEquals(targetRaw, target.file.readBytes())
                instrumentation.runOnMainSync { restores.confirm() }
                withTimeout(10_000) { restores.state.first { it.status == IdentityRestoreStatus.DONE } }
                assertEquals(original, target.repository.getOrCreateForConnection())
                assertEquals(Ts3IdentityCodec.inspect(targetOriginal), target.repository.snapshot().rollback)
                instrumentation.runOnMainSync { restores.beginRollback() }
                withTimeout(10_000) { restores.state.first { it.status == IdentityRestoreStatus.PREVIEW } }
                instrumentation.runOnMainSync { restores.confirm() }
                withTimeout(10_000) { restores.state.first { it.status == IdentityRestoreStatus.DONE } }
                assertEquals(targetOriginal, target.repository.getOrCreateForConnection())
                assertEquals(Ts3IdentityCodec.inspect(original), target.repository.snapshot().rollback)
                assertArrayEquals(sourceRaw, source.file.readBytes())
            } finally {
                instrumentation.runOnMainSync { controller?.close() }
                scope.cancel()
                password.fill('\u0000')
                resolver.delete(documentUri, null, null)
                source.close()
                target.close()
            }
        }

    private class Scratch {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val alias = "ts3_test_identity_cross_${UUID.randomUUID()}"
        val file = File(context.cacheDir, "cross-identity-${UUID.randomUUID()}.preferences_pb")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        val cipher = KeystorePayloadCipher(AndroidKeystoreKeys(alias))
        val repository = IdentityRepository(IdentityStore(store, cipher) { file.exists() })

        suspend fun close() {
            scope.coroutineContext.job.cancelAndJoin()
            file.delete()
            check(alias.startsWith("ts3_test_identity_cross_"))
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }
}
