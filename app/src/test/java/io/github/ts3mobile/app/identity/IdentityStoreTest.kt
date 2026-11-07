package io.github.ts3mobile.app.identity

import androidx.datastore.preferences.core.edit
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

class IdentityStoreTest {
    private val first = Ts3IdentityCodec.generate(0)
    private val second = Ts3IdentityCodec.generate(0)

    @Test fun legacyReadPreservesTheOriginalPayloadAndAddsNoMetadata() =
        runTest {
            val fixture = seed()
            val original = fixture.values.value
            assertEquals(first, fixture.storage.getOrCreate())
            assertEquals(Ts3IdentityCodec.inspect(first), fixture.storage.snapshot().active)
            assertEquals(original, fixture.values.value)
            assertEquals(1, fixture.writes)
        }

    @Test fun replacementAndRollbackKeepOneVerifiedPreviousIdentity() =
        runTest {
            val fixture = seed()
            val before = fixture.storage.snapshot()
            val changed = fixture.storage.replace(second, before.revision)
            assertEquals(Ts3IdentityCodec.inspect(second), changed.active)
            assertEquals(before.active, changed.rollback)
            assertNull(fixture.values.value[IdentityStore.PENDING])
            val restored = fixture.storage.rollback(changed.revision)
            assertEquals(before.active, restored.active)
            assertEquals(changed.active, restored.rollback)
        }

    @Test fun staleConfirmationDoesNotTouchAnyFieldOrCreateAKey() =
        runTest {
            val fixture = seed()
            val old = fixture.storage.snapshot()
            fixture.storage.replace(second, old.revision)
            val original = fixture.values.value
            val keyRequests = fixture.keyCreations
            expect(IdentityFailure.STALE_CONFIRMATION) { fixture.storage.replace(first, old.revision) }
            assertEquals(original, fixture.values.value)
            assertEquals(keyRequests, fixture.keyCreations)
        }

    @Test fun failedTransactionPreservesTheOriginalIdentityAndRollback() =
        runTest {
            val fixture = seed()
            val state = fixture.storage.snapshot()
            val original = fixture.values.value
            fixture.before = { throw IOException("private provider details must not escape") }
            expect(IdentityFailure.STORAGE_FAILED) { fixture.storage.replace(second, state.revision) }
            assertEquals(original, fixture.values.value)
        }

    @Test fun committedBeforeAcknowledgementRecoversPendingOnANewRepository() =
        runTest {
            val fixture = seed()
            val state = fixture.storage.snapshot()
            fixture.after = { throw IOException("simulated process loss after atomic commit") }
            expect(IdentityFailure.STORAGE_FAILED) { fixture.storage.replace(second, state.revision) }
            assertNotNull(fixture.values.value[IdentityStore.PENDING])
            fixture.after = { }
            assertEquals(second, fixture.storage.getOrCreate())
            assertNull(fixture.values.value[IdentityStore.PENDING])
            assertEquals(state.active, fixture.storage.snapshot().rollback)
        }

    @Test fun crashBeforeReadbackAndBeforePendingCleanupBothRecoverIdempotently() =
        runTest {
            for (afterValidation in listOf(false, true)) {
                val fixture = seed()
                val state = fixture.storage.snapshot()
                val readbackCall = fixture.calls + 2
                if (afterValidation) {
                    fixture.after = { if (it == readbackCall) throw IOException() }
                } else {
                    fixture.before = { if (it == readbackCall) throw IOException() }
                }
                expect(IdentityFailure.STORAGE_FAILED) { fixture.storage.replace(second, state.revision) }
                fixture.before = { }
                fixture.after = { }
                assertEquals(second, fixture.storage.getOrCreate())
                val snapshot = fixture.values.value
                assertEquals(second, fixture.storage.getOrCreate())
                assertEquals(snapshot, fixture.values.value)
            }
        }

    @Test fun invalidPendingActiveRestoresValidRollbackAndRetainsFailedBlob() =
        runTest {
            val fixture = seed()
            val old = fixture.values.value[IdentityStore.ACTIVE]!!
            fixture.edit {
                it[IdentityStore.ROLLBACK] = old
                it[IdentityStore.ACTIVE] = "invalid fixture ciphertext"
                it[IdentityStore.PENDING] = "digest fixture"
            }
            assertEquals(first, fixture.storage.getOrCreate())
            assertEquals(old, fixture.values.value[IdentityStore.ACTIVE])
            assertEquals("invalid fixture ciphertext", fixture.values.value[IdentityStore.EVIDENCE])
            assertEquals(Ts3IdentityCodec.inspect(first), fixture.storage.snapshot().rollback)
        }

    @Test fun validButUnexpectedPendingIdentityAlsoFallsBack() =
        runTest {
            val fixture = seed()
            fixture.edit {
                it[IdentityStore.ROLLBACK] = it[IdentityStore.ACTIVE]!!
                it[IdentityStore.ACTIVE] = fixture.cipher.encrypt(second.toByteArray())
                it[IdentityStore.PENDING] =
                    MessageDigest.getInstance("SHA-256").digest(first.toByteArray()).joinToString("") { "%02x".format(it) }
            }
            assertEquals(first, fixture.storage.getOrCreate())
        }

    @Test fun unreadableBothCopiesMissingKeyAndOrphanMetadataNeverGenerate() =
        runTest {
            repeat(3) { kind ->
                val fixture = seed()
                when (kind) {
                    0 ->
                        fixture.edit {
                            it[IdentityStore.ACTIVE] = "bad"
                            it[IdentityStore.ROLLBACK] = "bad"
                            it[IdentityStore.PENDING] = "bad"
                        }
                    1 -> fixture.existing = null
                    2 ->
                        fixture.edit {
                            it.remove(IdentityStore.ACTIVE)
                            it[IdentityStore.PENDING] = "orphan"
                        }
                }
                val original = fixture.values.value
                val keyRequests = fixture.keyCreations
                expect(IdentityFailure.UNREADABLE) { fixture.storage.getOrCreate() }
                assertEquals(original, fixture.values.value)
                assertEquals(keyRequests, fixture.keyCreations)
                assertTrue(fixture.storage.snapshot().unreadable)
            }
        }

    @Test fun explicitImportPreservesUnreadableOriginalWithoutAdvertisingItAsRollback() =
        runTest {
            val fixture = seed()
            val original = fixture.values.value[IdentityStore.ACTIVE]!!
            fixture.existing = null
            val before = fixture.storage.snapshot()
            val result = fixture.storage.replace(second, before.revision)
            assertEquals(Ts3IdentityCodec.inspect(second), result.active)
            assertNull(result.rollback)
            assertFalse(result.unreadable)
            assertEquals(original, fixture.values.value[IdentityStore.EVIDENCE])
        }

    @Test fun firstCreationSerializesConcurrentCallersAndAcknowledgesBeforeReturning() =
        runTest {
            val fixture = IdentityStoreFixture()
            val ack = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            fixture.after = {
                if (it == 1) {
                    entered.complete(Unit)
                    ack.await()
                }
            }
            val a = async { fixture.storage.getOrCreate() }
            entered.await()
            val b = async { fixture.storage.getOrCreate() }
            assertFalse(a.isCompleted)
            ack.complete(Unit)
            assertEquals(a.await(), b.await())
            assertEquals(1, fixture.keyCreations)
        }

    @Test fun failedReadbackNeverReportsReplacementSuccessAndNextReadRestoresTheValidOriginal() =
        runTest {
            val fixture = seed()
            val state = fixture.storage.snapshot()
            val commitCall = fixture.calls + 1
            fixture.after = { call ->
                if (call == commitCall) {
                    val corrupted = fixture.values.value.toMutablePreferences()
                    corrupted[IdentityStore.ACTIVE] = "bad"
                    fixture.values.value = corrupted.toPreferences()
                }
            }
            expect(IdentityFailure.VERIFICATION_FAILED) { fixture.storage.replace(second, state.revision) }
            assertEquals(first, fixture.storage.getOrCreate())
            assertEquals(state.active, fixture.storage.snapshot().active)
        }

    @Test fun invalidMaterialEmptyExportAndUnavailableRollbackDoNotMutateState() =
        runTest {
            val fixture = IdentityStoreFixture()
            val state = fixture.storage.snapshot()
            expect(IdentityFailure.EMPTY) { fixture.storage.readMaterial() }
            expect(IdentityFailure.NO_ROLLBACK) { fixture.storage.rollback(state.revision) }
            expect(IdentityFailure.INVALID_MATERIAL) { fixture.storage.replace("not an identity", state.revision) }
            assertEquals(0, fixture.writes)
            assertEquals(0, fixture.keyCreations)
        }

    @Test fun anExistingEmptyPreferencesFileIsRecoveryStateRatherThanAFirstInstall() =
        runTest {
            val fixture = IdentityStoreFixture()
            val storage = IdentityStore(fixture, fixture.cipher, hasPersistedState = { true })
            assertTrue(storage.snapshot().unreadable)
            expect(IdentityFailure.UNREADABLE) { storage.getOrCreate() }
            assertEquals(0, fixture.keyCreations)
            assertEquals(0, fixture.writes)
            val imported = storage.replace(second, storage.snapshot().revision)
            assertEquals(Ts3IdentityCodec.inspect(second), imported.active)
        }

    private suspend fun seed(): IdentityStoreFixture =
        IdentityStoreFixture().also { fixture ->
            fixture.edit { it[IdentityStore.ACTIVE] = fixture.cipher.encrypt(first.toByteArray()) }
        }

    private suspend fun expect(
        failure: IdentityFailure,
        operation: suspend () -> Unit,
    ) {
        try {
            operation()
            error("Expected fixed failure")
        } catch (error: IdentityOperationException) {
            assertEquals(failure, error.failure)
            assertNull(error.cause)
            assertEquals("Identity operation: ${failure.name}", error.message)
        }
    }
}
