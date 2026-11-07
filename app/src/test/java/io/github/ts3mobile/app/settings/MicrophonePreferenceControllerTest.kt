package io.github.ts3mobile.app.settings

import io.github.ts3mobile.audio.opus.MicrophoneSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MicrophonePreferenceControllerTest {
    @Test fun delayedReadRestoresOnlyUntouchedFieldsAndThenSavesMergedSettings() =
        runTest {
            val store =
                FakeStore().apply {
                    value = MicrophoneSettings(180, false, false, -25)
                    readGate = CompletableDeferred()
                }
            val applied = mutableListOf<MicrophoneSettings>()
            val controller = MicrophonePreferenceController(backgroundScope, store, applied::add)
            runCurrent()
            controller.edit(MicrophonePreferenceField.VOLUME, MicrophoneSettings(volumePercent = 120))
            // Explicitly selecting a displayed default is still an edit.
            controller.edit(MicrophonePreferenceField.GATE_THRESHOLD, MicrophoneSettings(gateThresholdDb = -40))
            controller.commit()
            runCurrent()
            assertTrue(store.writes.isEmpty())
            store.readGate!!.complete(Unit)
            runCurrent()
            val expected = MicrophoneSettings(120, false, false, -40)
            assertEquals(expected, controller.state.value.settings)
            assertEquals(expected, store.value)
            assertEquals(expected, applied.last())
            assertEquals(MicrophonePreferenceStatus.SAVED, controller.state.value.status)
        }

    @Test fun staleUiSnapshotCannotOverwriteAnUnrelatedLoadedField() =
        runTest {
            val store = FakeStore().apply { value = MicrophoneSettings(gateThresholdDb = -22) }
            val controller = MicrophonePreferenceController(backgroundScope, store) {}
            runCurrent()
            controller.edit(MicrophonePreferenceField.VOLUME, MicrophoneSettings(volumePercent = 150))
            assertEquals(-22, controller.state.value.settings.gateThresholdDb)
            assertEquals(150, controller.state.value.settings.volumePercent)
        }

    @Test fun completedOlderWriteCannotMarkANewerEditAsSaved() =
        runTest {
            val store = FakeStore()
            val controller = MicrophonePreferenceController(backgroundScope, store) {}
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            store.writeGate = gate
            controller.edit(MicrophonePreferenceField.VOLUME, MicrophoneSettings(volumePercent = 110))
            controller.commit()
            runCurrent()
            assertEquals(MicrophonePreferenceStatus.SAVING, controller.state.value.status)
            controller.edit(MicrophonePreferenceField.VOLUME, MicrophoneSettings(volumePercent = 160))
            controller.commit()
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(110, 160), store.writes.map { it.volumePercent })
            assertEquals(160, store.value.volumePercent)
            assertEquals(MicrophonePreferenceStatus.SAVED, controller.state.value.status)
        }

    @Test fun slidersCoalesceButCommitDoesNotWaitForTheDebounceDeadline() =
        runTest {
            val store = FakeStore()
            val controller = MicrophonePreferenceController(backgroundScope, store) {}
            runCurrent()
            repeat(50) { controller.edit(MicrophonePreferenceField.VOLUME, MicrophoneSettings(volumePercent = it)) }
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertTrue(store.writes.isEmpty())
            controller.commit()
            runCurrent()
            assertEquals(listOf(49), store.writes.map { it.volumePercent })
        }

    @Test fun writeFailureKeepsTheEditUnsavedAndRetryPersistsIt() =
        runTest {
            val store = FakeStore()
            val controller = MicrophonePreferenceController(backgroundScope, store) {}
            runCurrent()
            store.failWrite = true
            controller.edit(MicrophonePreferenceField.NOISE_SUPPRESSION, MicrophoneSettings(noiseSuppression = false))
            runCurrent()
            assertEquals(MicrophonePreferenceStatus.WRITE_FAILED, controller.state.value.status)
            assertEquals(false, controller.state.value.settings.noiseSuppression)
            store.failWrite = false
            controller.retry()
            runCurrent()
            assertEquals(false, store.value.noiseSuppression)
            assertEquals(MicrophonePreferenceStatus.SAVED, controller.state.value.status)
        }

    @Test fun failedReadBlocksAutomaticWritesUntilRetrySucceeds() =
        runTest {
            val store = FakeStore().apply { failRead = true }
            val controller = MicrophonePreferenceController(backgroundScope, store) {}
            runCurrent()
            controller.edit(MicrophonePreferenceField.VOLUME, MicrophoneSettings(volumePercent = 170))
            controller.commit()
            runCurrent()
            assertTrue(store.writes.isEmpty())
            assertEquals(MicrophonePreferenceStatus.READ_FAILED, controller.state.value.status)
            store.failRead = false
            controller.retry()
            runCurrent()
            assertEquals(170, store.value.volumePercent)
            assertEquals(MicrophonePreferenceStatus.SAVED, controller.state.value.status)
        }

    @Test fun explicitResetRetiresThePendingLoadAndPreservesLaterEdits() =
        runTest {
            val store =
                FakeStore().apply {
                    value = MicrophoneSettings(190, false, false, -18)
                    readGate = CompletableDeferred()
                }
            val controller = MicrophonePreferenceController(backgroundScope, store) {}
            runCurrent()
            controller.reset()
            controller.edit(MicrophonePreferenceField.VOLUME, MicrophoneSettings(volumePercent = 135))
            controller.commit()
            store.readGate!!.complete(Unit)
            runCurrent()
            assertEquals(MicrophoneSettings(volumePercent = 135), store.value)
            assertEquals(listOf(true), store.resets)
            assertEquals(MicrophonePreferenceStatus.SAVED, controller.state.value.status)
        }

    @Test fun closedControllerCannotApplyOrPersistLateEvents() =
        runTest {
            val store = FakeStore().apply { readGate = CompletableDeferred() }
            val applied = mutableListOf<MicrophoneSettings>()
            val controller = MicrophonePreferenceController(backgroundScope, store, applied::add)
            runCurrent()
            controller.close()
            controller.edit(MicrophonePreferenceField.VOLUME, MicrophoneSettings(volumePercent = 180))
            store.readGate!!.complete(Unit)
            runCurrent()
            assertTrue(applied.isEmpty())
            assertTrue(store.writes.isEmpty())
        }

    private class FakeStore : MicrophonePreferenceStore {
        var value = MicrophoneSettings()
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var failRead = false
        var failWrite = false
        val writes = mutableListOf<MicrophoneSettings>()
        val resets = mutableListOf<Boolean>()

        override suspend fun read(): MicrophoneSettings {
            readGate?.await()
            if (failRead) throw IOException()
            return value
        }

        override suspend fun write(
            settings: MicrophoneSettings,
            reset: Boolean,
        ) {
            writes += settings
            resets += reset
            val gate = writeGate
            writeGate = null
            gate?.await()
            if (failWrite) throw IOException()
            value = settings
        }
    }
}
