package io.github.ts3mobile.app.identity

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.crypto.AndroidKeystoreKeys
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

/** Process-crash test only: UUID scratch state, never the installed/default identity. */
internal class IdentityCrashFixture(val id: String) {
    init {
        require(UUID.fromString(id).toString() == id)
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "ts3_test_identity_crash_$id"
    private val alias = name
    val file = File(context.cacheDir, "$name.preferences_pb")
    val ready = File(context.cacheDir, "$name.ready")
    private val metadata = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val delegate = PreferenceDataStoreFactory.create(scope = scope) { file }
    val keys = AndroidKeystoreKeys(alias)
    val cipher = KeystorePayloadCipher(keys)
    val storage get() = IdentityStore(delegate, cipher) { file.exists() }

    suspend fun seed() {
        check(!file.exists() && !ready.exists() && !metadata.contains("original"))
        check(keys.findExistingKey() == null)
        val original = Ts3IdentityCodec.generate(0)
        val replacement = Ts3IdentityCodec.generate(0)
        delegate.edit { it[IdentityStore.ACTIVE] = cipher.encrypt(original.toByteArray()) }
        check(
            metadata.edit()
                .putString("original", digest(original.toByteArray()))
                .putString("replacement", digest(replacement.toByteArray()))
                .putString("candidate", cipher.encrypt(replacement.toByteArray()))
                .putString("challenge", cipher.encrypt(CHALLENGE))
                .commit(),
        )
    }

    fun replacement(): String = cipher.decrypt(checkNotNull(metadata.getString("candidate", null))).toString(Charsets.UTF_8)

    suspend fun damage(both: Boolean) {
        delegate.edit {
            it[IdentityStore.ACTIVE] = "synthetic damaged active"
            if (both) it[IdentityStore.ROLLBACK] = "synthetic damaged rollback"
        }
    }

    fun deleteKey() {
        check(alias.startsWith("ts3_test_identity_crash_"))
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
    }

    suspend fun verify(point: String) {
        val failed = point in setOf("damaged-both", "missing-key")
        val before = digest(file.readBytes())
        if (failed) {
            val error = runCatching { storage.getOrCreate() }.exceptionOrNull()
            assertTrue(error is IdentityOperationException)
            assertEquals(IdentityFailure.UNREADABLE, (error as IdentityOperationException).failure)
            assertEquals(before, digest(file.readBytes()))
        } else {
            val originalSurvives = point in setOf("before-transaction", "during-transaction", "damaged-active")
            val expected = metadata.getString(if (originalSurvives) "original" else "replacement", null)
            assertEquals(expected, digest(storage.getOrCreate().toByteArray()))
            val values = delegate.data.first()
            assertNull(values[IdentityStore.PENDING])
            if (point !in setOf("before-transaction", "during-transaction")) {
                val rollback = cipher.decrypt(checkNotNull(values[IdentityStore.ROLLBACK]))
                try {
                    assertEquals(metadata.getString("original", null), digest(rollback))
                } finally {
                    rollback.fill(0)
                }
            }
        }
        if (point == "missing-key") {
            assertNull(keys.findExistingKey())
        } else {
            assertNotNull(keys.findExistingKey())
            assertTrue(CHALLENGE.contentEquals(cipher.decrypt(checkNotNull(metadata.getString("challenge", null)))))
        }
    }

    suspend fun close(remove: Boolean) {
        scope.coroutineContext.job.cancelAndJoin()
        if (remove) {
            check(file.name.startsWith("ts3_test_identity_crash_"))
            file.delete()
            ready.delete()
            context.deleteSharedPreferences(name)
            deleteKey()
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val CHALLENGE = "synthetic crash key challenge".toByteArray()
    }
}
