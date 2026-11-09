package io.github.ts3mobile.app.identity

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.Ts3MobileApplication
import io.github.ts3mobile.app.crypto.AndroidKeystoreKeys
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

/** Real Android DataStore/Keystore on UUID scratch files; never mutates the installed identity. */
class IdentityTransactionTest {
    @Test fun processContainerSharesTheRepositoryAndGateBetweenComponentLookups() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Ts3MobileApplication
        assertSame(application.identities, (context.applicationContext as Ts3MobileApplication).identities)
        assertSame(application.identities.gate, (context.applicationContext as Ts3MobileApplication).identities.gate)
    }

    @Test fun legacyReadKeepsRawFileAndAtomicReplaceAndRollbackSurviveReopening() =
        runBlocking {
            val fixture = Scratch()
            try {
                val first = Ts3IdentityCodec.generate(0)
                val second = Ts3IdentityCodec.generate(0)
                fixture.store.edit { it[IdentityStore.ACTIVE] = fixture.cipher.encrypt(first.toByteArray()) }
                val raw = fixture.file.readBytes()
                assertEquals(first, IdentityVault(fixture.store, fixture.cipher).getOrCreate())
                val before = fixture.storage.snapshot()
                assertArrayEquals(raw, fixture.file.readBytes())
                val changed = fixture.storage.replace(second, before.revision)
                assertEquals(Ts3IdentityCodec.inspect(second), changed.active)
                fixture.reopen()
                assertEquals(second, fixture.storage.getOrCreate())
                assertEquals(before.active, fixture.storage.snapshot().rollback)
                val restored = fixture.storage.rollback(fixture.storage.snapshot().revision)
                assertEquals(before.active, restored.active)
                fixture.reopen()
                assertEquals(first, fixture.storage.getOrCreate())
                assertEquals(changed.active, fixture.storage.snapshot().rollback)
            } finally {
                fixture.close()
            }
        }

    @Test fun pendingStateOnDiskValidatesActiveOrRestoresRollbackAfterReopening() =
        runBlocking {
            for (damaged in listOf(false, true)) {
                val fixture = Scratch()
                try {
                    val first = Ts3IdentityCodec.generate(0)
                    val second = Ts3IdentityCodec.generate(0)
                    val original = fixture.cipher.encrypt(first.toByteArray())
                    fixture.store.edit {
                        it[IdentityStore.ROLLBACK] = original
                        it[IdentityStore.ACTIVE] = if (damaged) "bad fixture payload" else fixture.cipher.encrypt(second.toByteArray())
                        it[IdentityStore.PENDING] = digest(second)
                    }
                    fixture.reopen()
                    assertEquals(if (damaged) first else second, fixture.storage.getOrCreate())
                    val snapshot = fixture.storage.snapshot()
                    assertFalse(snapshot.unreadable)
                    assertEquals(Ts3IdentityCodec.inspect(first), snapshot.rollback)
                    fixture.reopen()
                    assertEquals(if (damaged) first else second, fixture.storage.getOrCreate())
                } finally {
                    fixture.close()
                }
            }
        }

    @Test fun lostKeyPreservesOldBlobAndExplicitImportCreatesOnlyANewDataKey() =
        runBlocking {
            val fixture = Scratch()
            try {
                val first = Ts3IdentityCodec.generate(0)
                val second = Ts3IdentityCodec.generate(0)
                val original = fixture.cipher.encrypt(first.toByteArray())
                fixture.store.edit { it[IdentityStore.ACTIVE] = original }
                fixture.deleteKey()
                val raw = fixture.file.readBytes()
                val before = fixture.storage.snapshot()
                assertTrue(before.unreadable)
                assertNull(fixture.keys.findExistingKey())
                assertArrayEquals(raw, fixture.file.readBytes())
                val imported = fixture.storage.replace(second, before.revision)
                assertEquals(Ts3IdentityCodec.inspect(second), imported.active)
                assertNull(imported.rollback)
                assertNotNull(fixture.keys.findExistingKey())
                fixture.reopen()
                assertEquals(second, fixture.storage.getOrCreate())
                var evidence: String? = null
                fixture.store.edit { evidence = it[IdentityStore.EVIDENCE] }
                assertEquals(original, evidence)
            } finally {
                fixture.close()
            }
        }

    @Test fun physicallyUnreadablePreferencesFailWithoutDeletingOrRecreatingTheFileOrKey() =
        runBlocking {
            val fixture = Scratch()
            try {
                fixture.stop()
                val corrupt = byteArrayOf(15)
                fixture.file.writeBytes(corrupt)
                fixture.reopen()
                val failure = runCatching { fixture.storage.snapshot() }.exceptionOrNull()
                assertTrue(failure is IdentityOperationException)
                assertEquals(IdentityFailure.STORAGE_FAILED, (failure as IdentityOperationException).failure)
                assertNull(failure.cause)
                assertArrayEquals(corrupt, fixture.file.readBytes())
                assertNull(fixture.keys.findExistingKey())
            } finally {
                fixture.close()
            }
        }

    @Test fun zeroByteExistingPreferencesNeverGeneratesAnIdentityAndAllowsExplicitVerifiedReplacement() =
        runBlocking {
            val fixture = Scratch()
            try {
                fixture.stop()
                fixture.file.writeBytes(byteArrayOf())
                fixture.reopen()
                val vault = IdentityVault(fixture.store, fixture.cipher) { fixture.file.exists() }
                val before = vault.storage.snapshot()
                assertTrue(before.unreadable)
                val failure = runCatching { vault.getOrCreate() }.exceptionOrNull()
                assertTrue(failure is IdentityOperationException)
                assertEquals(IdentityFailure.UNREADABLE, (failure as IdentityOperationException).failure)
                assertEquals(0L, fixture.file.length())
                assertNull(fixture.keys.findExistingKey())
                val material = Ts3IdentityCodec.generate(0)
                val result = vault.storage.replace(material, before.revision)
                assertEquals(Ts3IdentityCodec.inspect(material), result.active)
            } finally {
                fixture.close()
            }
        }

    private fun digest(material: String): String =
        MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).joinToString("") { "%02x".format(it) }

    private class Scratch {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val alias = "ts3_test_identity_${UUID.randomUUID()}"
        val file = File(context.cacheDir, "identity-${UUID.randomUUID()}.preferences_pb")
        val keys = AndroidKeystoreKeys(alias)
        val cipher = KeystorePayloadCipher(keys)
        private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var store = PreferenceDataStoreFactory.create(scope = scope) { file }
        val storage get() = IdentityStore(store, cipher) { file.exists() }

        suspend fun stop() {
            scope.coroutineContext.job.cancelAndJoin()
        }

        suspend fun reopen() {
            stop()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            store = PreferenceDataStoreFactory.create(scope = scope) { file }
        }

        fun deleteKey() {
            check(alias.startsWith("ts3_test_identity_"))
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }

        suspend fun close() {
            scope.coroutineContext.job.cancelAndJoin()
            assertTrue(file.delete() || !file.exists())
            deleteKey()
        }
    }
}
