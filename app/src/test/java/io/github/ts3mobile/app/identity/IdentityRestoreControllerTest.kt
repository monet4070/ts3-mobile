package io.github.ts3mobile.app.identity

import androidx.datastore.preferences.core.edit
import io.github.ts3mobile.app.identity.backup.IdentityBackupCodec
import io.github.ts3mobile.app.identity.documents.BackupDocument
import io.github.ts3mobile.app.identity.documents.ImportedIdentity
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import javax.crypto.spec.SecretKeySpec

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IdentityRestoreControllerTest {
    private val first = Ts3IdentityCodec.generate(0)
    private val second = Ts3IdentityCodec.generate(0)
    private val third = Ts3IdentityCodec.generate(0)
    private val password = "synthetic import password"
    private val codec = IdentityBackupCodec(deriveKey = { _, _, _ -> SecretKeySpec(ByteArray(32) { 7 }, "AES") })

    @Test fun preflightChangesNoIdentityAndConfirmationAndRollbackUseTheVerifiedRepository() =
        runTest {
            val fixture = fixture()
            val original = fixture.values.value
            val controller = controller(fixture)
            preview(controller)
            assertEquals(original, fixture.values.value)
            assertEquals(Ts3IdentityCodec.inspect(first), controller.state.value.preview!!.previous.active)
            assertEquals(Ts3IdentityCodec.inspect(second), controller.state.value.preview!!.proposed)
            controller.confirm()
            controller.confirm()
            runCurrent()
            assertEquals(IdentityRestoreStatus.DONE, controller.state.value.status)
            assertEquals(second, fixture.storage.getOrCreate())
            controller.beginRollback()
            runCurrent()
            assertEquals(Ts3IdentityCodec.inspect(first), controller.state.value.preview!!.proposed)
            assertTrue(controller.state.value.preview!!.rollback)
            controller.confirm()
            runCurrent()
            assertEquals(first, fixture.storage.getOrCreate())
            assertEquals(Ts3IdentityCodec.inspect(second), fixture.storage.snapshot().rollback)
        }

    @Test fun busyCommitRetainsCandidateButCannotRetryWithoutANewComparisonAndConfirmation() =
        runTest {
            val fixture = fixture()
            val repository = repository(fixture)
            val controller = controller(fixture, repository)
            preview(controller)
            val original = fixture.values.value
            val owner = checkNotNull(repository.gate.beginConnection())
            controller.confirm()
            runCurrent()
            assertEquals(IdentityFailure.BUSY, controller.state.value.storeFailure)
            assertNotNull(controller.state.value.preview)
            assertEquals(original, fixture.values.value)
            repository.gate.finishConnection(owner)
            controller.confirm()
            runCurrent()
            assertEquals(original, fixture.values.value)
            controller.refreshConfirmation()
            runCurrent()
            assertEquals(IdentityRestoreStatus.PREVIEW, controller.state.value.status)
            controller.confirm()
            runCurrent()
            assertEquals(second, fixture.storage.getOrCreate())
        }

    @Test fun staleComparisonNeverOverwritesTheNewActiveUntilRefreshedAndConfirmed() =
        runTest {
            val fixture = fixture()
            val repository = repository(fixture)
            val controller = controller(fixture, repository)
            preview(controller)
            repository.replace(third, repository.snapshot().revision)
            controller.confirm()
            runCurrent()
            assertEquals(IdentityFailure.STALE_CONFIRMATION, controller.state.value.storeFailure)
            assertEquals(third, fixture.storage.getOrCreate())
            controller.refreshConfirmation()
            runCurrent()
            assertEquals(Ts3IdentityCodec.inspect(third), controller.state.value.preview!!.previous.active)
            controller.confirm()
            runCurrent()
            assertEquals(second, fixture.storage.getOrCreate())
            assertEquals(Ts3IdentityCodec.inspect(third), fixture.storage.snapshot().rollback)
        }

    @Test fun invalidTruncatedUnknownAndMaliciousFilesNeverModifyAnyStoredField() =
        runTest {
            val fixture = fixture()
            val original = fixture.values.value
            val valid = codec.encrypt(second.toByteArray(), password.toCharArray())
            val variants =
                listOf(
                    byteArrayOf(),
                    valid.copyOf(valid.size - 1),
                    valid.copyOf().also { ByteBuffer.wrap(it).putShort(16, 2) },
                    valid.copyOf().also { ByteBuffer.wrap(it).putInt(20, Int.MAX_VALUE) },
                    ByteArray(IdentityBackupCodec.MAX_FILE_BYTES + 1),
                )
            for (bytes in variants) {
                val controller = controller(fixture)
                controller.beginImport(password.toCharArray())
                controller.picked(Document(bytes))
                runCurrent()
                assertEquals(IdentityRestoreStatus.FAILED, controller.state.value.status)
                assertNull(controller.state.value.preview)
                assertEquals(original, fixture.values.value)
                controller.close()
            }
        }

    @Test fun cancelAndOrphanPickerResultsCannotReadOrReplaceIdentityOrStealTheNewPicker() =
        runTest {
            val fixture = fixture()
            val original = fixture.values.value
            val controller = controller(fixture)
            val secret = password.toCharArray()
            controller.beginImport(secret)
            assertTrue(secret.all { it == '\u0000' })
            val old = checkNotNull(controller.state.value.pickerRequest)
            assertTrue(controller.claimPickerRequest(old))
            assertFalse(controller.claimPickerRequest(old))
            controller.cancel()
            controller.beginImport(password.toCharArray())
            val late = Document(codec.encrypt(second.toByteArray(), password.toCharArray()))
            controller.picked(late)
            runCurrent()
            assertEquals(0, late.reads)
            controller.beginImport(password.toCharArray())
            assertTrue(checkNotNull(controller.state.value.pickerRequest) > old)
            controller.picked(null)
            assertEquals(original, fixture.values.value)
            val replacement = controller(fixture)
            replacement.picked(late)
            assertEquals(0, late.reads)
        }

    @Test fun cancellationInsidePreflightCannotPublishALatePreviewOrChangeActive() =
        runTest {
            val fixture = fixture()
            val original = fixture.values.value
            val controller = controller(fixture)
            controller.beginImport(password.toCharArray())
            val document = Document(codec.encrypt(second.toByteArray(), password.toCharArray())) { controller.cancel() }
            controller.picked(document)
            runCurrent()
            assertEquals(IdentityRestoreStatus.CANCELLED, controller.state.value.status)
            assertNull(controller.state.value.preview)
            assertEquals(original, fixture.values.value)
        }

    @Test fun cancellingAnUnsettledReadKeepsNewRequestsDisabledUntilTheWorkerActuallyFinishes() =
        runTest {
            val fixture = fixture()
            val original = fixture.values.value
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            fixture.before = {
                entered.complete(Unit)
                finish.await()
            }
            val controller = controller(fixture)
            controller.beginImport(password.toCharArray())
            controller.picked(Document(codec.encrypt(second.toByteArray(), password.toCharArray())))
            entered.await()
            controller.cancel()
            assertEquals(IdentityRestoreStatus.CANCELLING, controller.state.value.status)
            assertTrue(controller.state.value.working)
            val secret = password.toCharArray()
            controller.beginImport(secret)
            assertTrue(secret.all { it == '\u0000' })
            assertNull(controller.state.value.pickerRequest)
            finish.complete(Unit)
            runCurrent()
            assertEquals(IdentityRestoreStatus.CANCELLED, controller.state.value.status)
            assertFalse(controller.state.value.active)
            assertEquals(original, fixture.values.value)
        }

    @Test fun uiDestructionAfterAtomicCommitRetainsQualificationUntilAcknowledgementAndVerification() =
        runTest {
            val fixture = fixture()
            val repository = repository(fixture)
            val controller = controller(fixture, repository)
            preview(controller)
            val entered = CompletableDeferred<Unit>()
            val ack = CompletableDeferred<Unit>()
            val commitCall = fixture.calls + 1
            fixture.after = {
                if (it == commitCall) {
                    entered.complete(Unit)
                    ack.await()
                }
            }
            controller.confirm()
            entered.await()
            controller.close()
            runCurrent()
            assertNull(repository.gate.beginConnection())
            assertNull(repository.gate.beginMaintenance())
            ack.complete(Unit)
            runCurrent()
            assertEquals(second, fixture.storage.getOrCreate())
            assertEquals(IdentityUseState.IDLE, repository.gate.state.value)
            assertNull(fixture.values.value[IdentityStore.PENDING])
        }

    @Test fun cancellingBeforeTheConfirmJobRunsLeavesTheOriginalStateUnchanged() =
        runTest {
            val fixture = fixture()
            val original = fixture.values.value
            val controller = controller(fixture)
            preview(controller)
            controller.confirm()
            controller.close()
            runCurrent()
            assertEquals(original, fixture.values.value)
        }

    @Test fun noRollbackAndUnavailablePickerHaveExplicitSanitizedResults() =
        runTest {
            val fixture = fixture()
            val original = fixture.values.value
            val controller = controller(fixture)
            controller.beginRollback()
            runCurrent()
            assertEquals(IdentityFailure.NO_ROLLBACK, controller.state.value.storeFailure)
            controller.beginImport(password.toCharArray())
            controller.pickerUnavailable()
            assertEquals(IdentityRestoreStatus.PICKER_UNAVAILABLE, controller.state.value.status)
            assertFalse(controller.state.value.toString().contains(password))
            assertFalse(controller.state.value.toString().contains(first))
            assertEquals(original, fixture.values.value)
        }

    private suspend fun fixture() =
        IdentityStoreFixture().also { fixture ->
            fixture.edit { it[IdentityStore.ACTIVE] = fixture.cipher.encrypt(first.toByteArray()) }
        }

    private fun TestScope.repository(fixture: IdentityStoreFixture) =
        IdentityRepository(
            fixture.storage,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    private fun TestScope.controller(
        fixture: IdentityStoreFixture,
        repository: IdentityRepository = repository(fixture),
    ) = IdentityRestoreController(
        backgroundScope,
        repository,
        {},
        StandardTestDispatcher(testScheduler),
    ) { document, passphrase -> ImportedIdentity.read(document, passphrase, codec) }

    private fun TestScope.preview(controller: IdentityRestoreController) {
        controller.beginImport(password.toCharArray())
        controller.picked(Document(codec.encrypt(second.toByteArray(), password.toCharArray())))
        runCurrent()
        assertEquals(IdentityRestoreStatus.PREVIEW, controller.state.value.status)
    }

    private class Document(private val bytes: ByteArray, private val onRead: () -> Unit = {}) : BackupDocument {
        var reads = 0

        override fun openWrite() = error("Import must not write")

        override fun openRead(): ByteArrayInputStream {
            reads++
            onRead()
            return ByteArrayInputStream(bytes)
        }
    }
}
