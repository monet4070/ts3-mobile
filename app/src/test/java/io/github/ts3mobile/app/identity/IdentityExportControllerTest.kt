package io.github.ts3mobile.app.identity

import io.github.ts3mobile.app.identity.backup.IdentityBackupCodec
import io.github.ts3mobile.app.identity.documents.BackupDocument
import io.github.ts3mobile.app.identity.documents.PreparedIdentityBackup
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.crypto.spec.SecretKeySpec

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IdentityExportControllerTest {
    private val material = Ts3IdentityCodec.generate(0)
    private val password = "synthetic fixture password"
    private val codec = IdentityBackupCodec(deriveKey = { _, _, _ -> SecretKeySpec(ByteArray(32) { 7 }, "AES") })

    @Test fun refreshAndPasswordValidationNeverGenerateOrReadPrivateIdentity() =
        runTest {
            var reads = 0
            val controller =
                controller {
                    reads++
                    material
                }
            controller.refresh()
            runCurrent()
            val secret = password.toCharArray()
            val confirmation = "not a matching password".toCharArray()
            controller.export(secret, confirmation)
            assertEquals(IdentityExportStatus.PASSWORD_MISMATCH, controller.state.value.status)
            assertTrue(secret.all { it == '\u0000' })
            assertTrue(confirmation.all { it == '\u0000' })
            controller.export("short".toCharArray(), "short".toCharArray())
            assertEquals(IdentityExportStatus.INVALID_PASSWORD, controller.state.value.status)
            assertEquals(0, reads)
        }

    @Test fun sharedInputArrayIsConsumedWithoutErasingTheOwnedPasswordBeforeEncryption() =
        runTest {
            val controller = controller()
            controller.refresh()
            runCurrent()
            val secret = password.toCharArray()
            controller.export(secret, secret)
            runCurrent()
            assertTrue(secret.all { it == '\u0000' })
            assertEquals(IdentityExportStatus.SELECTING, controller.state.value.status)
            controller.picked(MemoryDocument())
            runCurrent()
            assertEquals(IdentityExportStatus.VERIFIED, controller.state.value.status)
        }

    @Test fun pickerIsClaimedOnceAndVerificationAppearsOnlyAfterReopen() =
        runTest {
            val controller = controller()
            controller.refresh()
            runCurrent()
            controller.export(password.toCharArray(), password.toCharArray())
            runCurrent()
            val token = checkNotNull(controller.state.value.pickerRequest)
            assertEquals(IdentityExportStatus.SELECTING, controller.state.value.status)
            assertTrue(controller.claimPickerRequest(token))
            assertFalse(controller.claimPickerRequest(token))
            val document = MemoryDocument()
            controller.picked(document)
            assertEquals(IdentityExportStatus.WRITING, controller.state.value.status)
            runCurrent()
            assertTrue(document.reopened)
            assertEquals(IdentityExportStatus.VERIFIED, controller.state.value.status)
            assertEquals(Ts3IdentityCodec.inspect(material), controller.state.value.exported)
        }

    @Test fun cancellationKeepsTheOldPickerSlotUntilItsResultAndIgnoresLateSelection() =
        runTest {
            var reads = 0
            val controller =
                controller {
                    reads++
                    material
                }
            controller.refresh()
            runCurrent()
            controller.export(password.toCharArray(), password.toCharArray())
            runCurrent()
            val old = checkNotNull(controller.state.value.pickerRequest)
            assertTrue(controller.claimPickerRequest(old))
            controller.cancel()
            controller.export(password.toCharArray(), password.toCharArray())
            runCurrent()
            assertEquals(1, reads)
            val late = MemoryDocument()
            controller.picked(late)
            runCurrent()
            assertFalse(late.written)
            assertEquals(IdentityExportStatus.CANCELLED, controller.state.value.status)
            controller.export(password.toCharArray(), password.toCharArray())
            runCurrent()
            assertTrue(checkNotNull(controller.state.value.pickerRequest) > old)
            controller.close()
        }

    @Test fun cancelledPreflightThatIgnoresCancellationCannotLaunchALatePicker() =
        runTest {
            val finish = CompletableDeferred<Unit>()
            val controller =
                controller {
                    withContext(NonCancellable) {
                        finish.await()
                        material
                    }
                }
            controller.refresh()
            runCurrent()
            val secret = password.toCharArray()
            controller.export(secret, password.toCharArray())
            runCurrent()
            controller.cancel()
            finish.complete(Unit)
            runCurrent()
            assertEquals(IdentityExportStatus.CANCELLED, controller.state.value.status)
            assertNull(controller.state.value.pickerRequest)
            assertTrue(secret.all { it == '\u0000' })
        }

    @Test fun providerFailureIsUnverifiedAndNullRestoredOrDuplicateCallbacksDoNotWrite() =
        runTest {
            val controller = controller()
            controller.refresh()
            runCurrent()
            controller.picked(MemoryDocument())
            assertEquals(IdentityExportStatus.READY, controller.state.value.status)
            controller.export(password.toCharArray(), password.toCharArray())
            runCurrent()
            controller.claimPickerRequest(checkNotNull(controller.state.value.pickerRequest))
            controller.picked(MemoryDocument(denyRead = true))
            runCurrent()
            assertEquals(IdentityExportStatus.UNVERIFIED, controller.state.value.status)
            val late = MemoryDocument()
            controller.picked(late)
            runCurrent()
            assertFalse(late.written)
            controller.export(password.toCharArray(), password.toCharArray())
            runCurrent()
            controller.picked(null)
            assertEquals(IdentityExportStatus.CANCELLED, controller.state.value.status)
        }

    @Test fun busyReadDoesNotLaunchPickerOrExposePrivateMaterialInPublicState() =
        runTest {
            val controller = controller { throw IdentityOperationException(IdentityFailure.BUSY) }
            controller.refresh()
            runCurrent()
            controller.export(password.toCharArray(), password.toCharArray())
            runCurrent()
            assertEquals(IdentityExportStatus.CONNECTION_BUSY, controller.state.value.status)
            assertNull(controller.state.value.pickerRequest)
            assertFalse(controller.state.value.toString().contains(material))
            assertFalse(controller.state.value.toString().contains(password))
            assertFalse(controller.state.value.toString().contains(Ts3IdentityCodec.inspect(material).uid))
        }

    @Test fun missingSystemPickerIsAnExplicitFailureRatherThanAUserCancellation() =
        runTest {
            val controller = controller()
            controller.refresh()
            runCurrent()
            controller.export(password.toCharArray(), password.toCharArray())
            runCurrent()
            controller.claimPickerRequest(checkNotNull(controller.state.value.pickerRequest))
            controller.pickerUnavailable()
            assertEquals(IdentityExportStatus.PICKER_UNAVAILABLE, controller.state.value.status)
            assertFalse(controller.state.value.pickerOutstanding)
            controller.picked(MemoryDocument())
            assertEquals(IdentityExportStatus.PICKER_UNAVAILABLE, controller.state.value.status)
        }

    @Test fun cancelledScopeBeforeExportBodyStartsStillClearsInputArrays() =
        runTest {
            val child = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + StandardTestDispatcher(testScheduler))
            val controller = IdentityExportController(child, { material }, { snapshot() }, StandardTestDispatcher(testScheduler))
            controller.refresh()
            runCurrent()
            child.cancel()
            val secret = password.toCharArray()
            controller.export(secret, password.toCharArray())
            runCurrent()
            assertTrue(secret.all { it == '\u0000' })
            controller.close()
        }

    @Test fun closingControllerCannotPublishASelectionFromAnOldActivityInstance() =
        runTest {
            val old = controller()
            old.refresh()
            runCurrent()
            old.export(password.toCharArray(), password.toCharArray())
            runCurrent()
            old.close()
            val replacement = controller()
            replacement.refresh()
            runCurrent()
            val document = MemoryDocument()
            replacement.picked(document)
            old.picked(document)
            runCurrent()
            assertFalse(document.written)
            assertEquals(IdentityExportStatus.READY, replacement.state.value.status)
        }

    private fun TestScope.controller(read: suspend () -> String = { material }): IdentityExportController =
        IdentityExportController(backgroundScope, read, { snapshot() }, StandardTestDispatcher(testScheduler)) { text, passphrase ->
            PreparedIdentityBackup.prepare(text, passphrase, codec)
        }

    private fun snapshot() = IdentitySnapshot(Ts3IdentityCodec.inspect(material), null, false, "fixture revision")

    private class MemoryDocument(private val denyRead: Boolean = false) : BackupDocument {
        var bytes = byteArrayOf()
        var written = false
        var reopened = false

        override fun openWrite(): OutputStream {
            written = true
            return object : ByteArrayOutputStream() {
                override fun close() {
                    bytes = toByteArray()
                }
            }
        }

        override fun openRead(): ByteArrayInputStream {
            reopened = true
            if (denyRead) throw SecurityException("fixture")
            return ByteArrayInputStream(bytes)
        }
    }
}
