package io.github.ts3mobile.app.identity

import io.github.ts3mobile.app.identity.documents.BackupDocument
import io.github.ts3mobile.app.identity.documents.IdentityImportFailure
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopIdentityRestoreTest {
    @Test fun emptyStoreRemainsEmptyUntilTheUserConfirmsTheDesktopPreview() =
        runTest {
            val fixture = IdentityStoreFixture()
            val desktop = Ts3IdentityCodec.generate(3)
            val controller = restores(fixture)
            controller.beginDesktopImport()
            controller.picked(Document(desktop.toByteArray()))
            runCurrent()
            val preview = checkNotNull(controller.state.value.preview)
            assertEquals(IdentityImportSource.DESKTOP, preview.source)
            assertNull(preview.previous.active)
            assertNull(fixture.storage.snapshot().active)
            assertEquals(0, fixture.writes)
            assertEquals(Ts3IdentityCodec.inspect(desktop), preview.proposed)
            controller.confirm()
            runCurrent()
            assertEquals(IdentityRestoreStatus.DONE, controller.state.value.status)
            assertEquals(preview.proposed, fixture.storage.snapshot().active)
            assertNull(fixture.storage.snapshot().rollback)
        }

    @Test fun sameIdentityAndOffsetDoNotReplaceTheCurrentValueOrRollback() =
        runTest {
            val fixture = IdentityStoreFixture()
            val current = Ts3IdentityCodec.generate(3)
            fixture.storage.getOrCreate()
            fixture.storage.replace(current, fixture.storage.snapshot().revision)
            val before = fixture.storage.snapshot()
            val writes = fixture.writes
            val controller = restores(fixture)
            controller.beginDesktopImport()
            controller.picked(Document(current.toByteArray()))
            runCurrent()
            assertEquals(IdentityRestoreStatus.ALREADY_CURRENT, controller.state.value.status)
            controller.confirm()
            runCurrent()
            assertEquals(writes, fixture.writes)
            assertEquals(before, fixture.storage.snapshot())
        }

    @Test fun lowerSecurityOnTheSameUidRequiresExplicitConsentAndFreshRevision() =
        runTest {
            val fixture = IdentityStoreFixture()
            val high = Ts3IdentityCodec.generate(5)
            val highSummary = Ts3IdentityCodec.inspect(high)
            val fields = Regex("identity=\"[0-9]+V")
            val lower =
                (0..128).asSequence().map { offset -> fields.replace(high, "identity=\"${offset}V") }
                    .first { Ts3IdentityCodec.inspect(it).securityLevel < highSummary.securityLevel }
            fixture.storage.replace(high, fixture.storage.snapshot().revision)
            val controller = restores(fixture)
            controller.beginDesktopImport()
            controller.picked(Document(lower.toByteArray()))
            runCurrent()
            assertTrue(checkNotNull(controller.state.value.preview).isDesktopDowngrade)
            controller.confirm()
            runCurrent()
            assertEquals(highSummary, fixture.storage.snapshot().active)
            val alternate = Ts3IdentityCodec.generate(0)
            fixture.storage.replace(alternate, fixture.storage.snapshot().revision)
            controller.confirm(allowLowerSecurity = true)
            runCurrent()
            assertEquals(IdentityFailure.STALE_CONFIRMATION, controller.state.value.storeFailure)
            assertEquals(Ts3IdentityCodec.inspect(alternate), fixture.storage.snapshot().active)
            controller.refreshConfirmation()
            runCurrent()
            assertFalse(checkNotNull(controller.state.value.preview).isDesktopDowngrade)
            controller.confirm()
            runCurrent()
            assertEquals(Ts3IdentityCodec.inspect(alternate), fixture.storage.snapshot().rollback)
            assertEquals(Ts3IdentityCodec.inspect(lower), fixture.storage.snapshot().active)
        }

    @Test fun readErrorsWrongFormatOversizeAndLateCallbackLeaveBothIdentitiesUntouched() =
        runTest {
            val fixture = IdentityStoreFixture()
            val current = Ts3IdentityCodec.generate(0)
            fixture.storage.getOrCreate()
            fixture.storage.replace(current, fixture.storage.snapshot().revision)
            val before = fixture.storage.snapshot()
            val controller = restores(fixture)
            val maximum = Ts3IdentityCodec.MAX_MATERIAL_BYTES
            val wrong = Document("TS3MBAK\u0000".toByteArray())
            val failed = Document(current.toByteArray(), failClose = true)
            val oversize = Document(ByteArray(maximum + 4096))
            listOf(
                wrong to IdentityImportFailure.INVALID_IDENTITY,
                failed to IdentityImportFailure.DOCUMENT_UNREADABLE,
                oversize to IdentityImportFailure.RESOURCE_LIMIT,
            ).forEach { (document, expected) ->
                controller.beginDesktopImport()
                controller.picked(document)
                runCurrent()
                assertEquals(expected, controller.state.value.importFailure)
                assertTrue(document.closed)
                assertEquals(before, fixture.storage.snapshot())
            }
            assertEquals(maximum + 1, oversize.consumed)
            controller.beginDesktopImport()
            assertTrue(controller.claimPickerRequest(checkNotNull(controller.state.value.pickerRequest)))
            controller.cancel()
            val password = "synthetic backup passphrase".toCharArray()
            controller.beginImport(password)
            assertTrue(password.all { it == '\u0000' })
            val late = Document(current.toByteArray())
            controller.picked(late)
            runCurrent()
            assertEquals(0, late.consumed)
            assertFalse(controller.state.value.pickerOutstanding)
            assertEquals(before, fixture.storage.snapshot())
        }

    private fun TestScope.restores(fixture: IdentityStoreFixture): IdentityRestoreController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return IdentityRestoreController(backgroundScope, IdentityRepository(fixture.storage, dispatcher = dispatcher), {}, dispatcher)
    }

    private class Document(private val bytes: ByteArray, private val failClose: Boolean = false) : BackupDocument {
        var consumed = 0
        var closed = false

        override fun openWrite() = error("Desktop import must not write")

        override fun openRead() =
            object : ByteArrayInputStream(bytes) {
                override fun read(
                    destination: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = super.read(destination, offset, minOf(length, 371)).also { if (it > 0) consumed += it }

                override fun close() {
                    closed = true
                    if (failClose) throw IOException("fixture provider error")
                }
            }
    }
}
