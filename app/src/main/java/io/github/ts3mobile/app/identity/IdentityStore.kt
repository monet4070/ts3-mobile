package io.github.ts3mobile.app.identity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher
import io.github.ts3mobile.protocol.InvalidIdentityMaterial
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** All durable changes use this one DataStore; unchanged legacy reads do not rewrite it. */
internal class IdentityStore(
    private val store: DataStore<Preferences>,
    private val cipher: KeystorePayloadCipher,
    private val hasPersistedState: () -> Boolean = { false },
) {
    suspend fun getOrCreate(): String =
        durable {
            val stateExisted = hasPersistedState()
            var material: String? = null
            store.edit { preferences ->
                recoverPending(preferences)
                val encrypted = preferences[ACTIVE]
                material =
                    if (encrypted != null) {
                        decode(encrypted)
                    } else {
                        if (stateExisted || FIELDS.any { preferences[it] != null }) fail(IdentityFailure.UNREADABLE)
                        Ts3IdentityCodec.generate().also { preferences[ACTIVE] = encrypt(it) }
                    }
            }
            checkNotNull(material)
        }

    suspend fun readMaterial(): String =
        durable {
            var material: String? = null
            store.edit {
                recoverPending(it)
                material = decode(it[ACTIVE] ?: fail(IdentityFailure.EMPTY))
            }
            checkNotNull(material)
        }

    suspend fun snapshot(): IdentitySnapshot =
        durable {
            var result: IdentitySnapshot? = null
            store.edit {
                // If both pending copies fail, retain all evidence and allow explicit external recovery.
                try {
                    recoverPending(it)
                } catch (_: IdentityOperationException) {
                    // describe records UNREADABLE; only explicit external recovery may replace it.
                }
                result = describe(it)
            }
            checkNotNull(result)
        }

    suspend fun replace(
        material: String,
        expectedRevision: String,
    ): IdentitySnapshot = commit(material, expectedRevision)

    suspend fun rollback(expectedRevision: String): IdentitySnapshot = commit(null, expectedRevision)

    private suspend fun commit(
        replacement: String?,
        expectedRevision: String,
    ): IdentitySnapshot =
        durable {
            replacement?.let {
                try {
                    Ts3IdentityCodec.inspect(it)
                } catch (_: InvalidIdentityMaterial) {
                    fail(IdentityFailure.INVALID_MATERIAL)
                }
            }
            var expectedDigest = ""
            store.edit { preferences ->
                if (revision(preferences) != expectedRevision) fail(IdentityFailure.STALE_CONFIRMATION)
                val next = replacement ?: valid(preferences[ROLLBACK]) ?: fail(IdentityFailure.NO_ROLLBACK)
                // A corrupt original is evidence, never a claimed usable rollback.
                val original = preferences[ACTIVE]
                val previous = valid(original)
                if (previous != null) {
                    preferences[ROLLBACK] = checkNotNull(original)
                } else if (original != null) {
                    preferences[EVIDENCE] = original
                    if (valid(preferences[ROLLBACK]) == null) preferences.remove(ROLLBACK)
                }
                preferences[ACTIVE] = encrypt(next)
                expectedDigest = digest(next.toByteArray(Charsets.UTF_8))
                preferences[PENDING] = expectedDigest
            }
            // Acknowledged transaction precedes readback. If this fails, pending remains recoverable.
            var result: IdentitySnapshot? = null
            store.edit {
                recoverPending(it)
                if (digest(decode(checkNotNull(it[ACTIVE])).toByteArray(Charsets.UTF_8)) != expectedDigest) {
                    fail(IdentityFailure.VERIFICATION_FAILED)
                }
                result = describe(it)
            }
            checkNotNull(result)
        }

    private fun recoverPending(preferences: MutablePreferences) {
        val expected = preferences[PENDING] ?: return
        val active = valid(preferences[ACTIVE])
        if (active != null && digest(active.toByteArray(Charsets.UTF_8)) == expected) {
            preferences.remove(PENDING)
            return
        }
        val rollback = preferences[ROLLBACK]
        if (valid(rollback) == null) fail(IdentityFailure.UNREADABLE)
        preferences[ACTIVE]?.let { preferences[EVIDENCE] = it }
        preferences[ACTIVE] = checkNotNull(rollback)
        preferences.remove(PENDING)
    }

    private fun describe(preferences: Preferences): IdentitySnapshot {
        val active = valid(preferences[ACTIVE])
        return IdentitySnapshot(
            active = active?.let(Ts3IdentityCodec::inspect),
            rollback = valid(preferences[ROLLBACK])?.let(Ts3IdentityCodec::inspect),
            unreadable =
                (preferences[ACTIVE] != null && active == null) || preferences[PENDING] != null ||
                    (preferences[ACTIVE] == null && (hasPersistedState() || FIELDS.any { preferences[it] != null })),
            revision = revision(preferences),
        )
    }

    private fun valid(encrypted: String?): String? =
        try {
            encrypted?.let(::decode)
        } catch (_: IdentityOperationException) {
            null
        }

    private fun decode(encrypted: String): String =
        try {
            val bytes = cipher.decrypt(encrypted)
            try {
                val material =
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                Ts3IdentityCodec.inspect(material)
                material
            } finally {
                bytes.fill(0)
            }
        } catch (_: Exception) {
            fail(IdentityFailure.UNREADABLE)
        }

    private fun encrypt(material: String): String {
        val bytes = material.toByteArray(Charsets.UTF_8)
        return try {
            cipher.encrypt(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun revision(preferences: Preferences): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FIELDS.forEach { field ->
            val bytes = preferences[field]?.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
            bytes?.let(digest::update)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** DataStore actors must acknowledge before cancellation can release the owning lease. */
    private suspend fun <T> durable(block: suspend () -> T): T =
        withContext(NonCancellable) {
            try {
                block()
            } catch (error: IdentityOperationException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                fail(IdentityFailure.STORAGE_FAILED)
            }
        }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fail(failure: IdentityFailure): Nothing = throw IdentityOperationException(failure)

    companion object {
        val ACTIVE = stringPreferencesKey("encrypted_identity")
        val ROLLBACK = stringPreferencesKey("identity_rollback_v1")
        val PENDING = stringPreferencesKey("identity_pending_sha256_v1")
        val EVIDENCE = stringPreferencesKey("identity_recovery_evidence_v1")
        private val FIELDS = listOf(ACTIVE, ROLLBACK, PENDING, EVIDENCE)
    }
}
