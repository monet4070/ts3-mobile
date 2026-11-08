package io.github.ts3mobile.app.appearance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ThemePreferenceControllerTest {
    @Test fun onlyExplicitSystemSelectionFollowsSystemAndUnsetAlwaysStaysLight() {
        for (system in listOf(false, true)) {
            assertFalse(ThemePreferenceState().mode.isDark(system))
            assertFalse(ThemeMode.LIGHT.isDark(system))
            assertTrue(ThemeMode.DARK.isDark(system))
            assertEquals(system, ThemeMode.SYSTEM.isDark(system))
        }
    }

    @Test fun delayedLoadDisablesChoicesRatherThanOverwritingPersistedSelection() =
        runTest {
            val store =
                Store().apply {
                    value = ThemeMode.DARK
                    readGate = CompletableDeferred()
                }
            val controller = ThemePreferenceController(backgroundScope, store)
            runCurrent()
            controller.select(ThemeMode.SYSTEM)
            assertTrue(controller.state.value.busy)
            store.readGate!!.complete(Unit)
            runCurrent()
            assertEquals(ThemePreferenceState(ThemeMode.DARK, ThemePreferenceStatus.SAVED), controller.state.value)
            assertTrue(store.writes.isEmpty())
        }

    @Test fun selectionAppliesWhileSavingAndFailureCanBeRetried() =
        runTest {
            val store = Store()
            val controller = ThemePreferenceController(backgroundScope, store)
            runCurrent()
            store.failWrite = true
            controller.select(ThemeMode.DARK)
            assertEquals(ThemePreferenceState(ThemeMode.DARK, ThemePreferenceStatus.SAVING), controller.state.value)
            runCurrent()
            assertEquals(ThemePreferenceStatus.WRITE_FAILED, controller.state.value.status)
            assertEquals(ThemeMode.LIGHT, store.value)
            store.failWrite = false
            controller.retry()
            runCurrent()
            assertEquals(ThemeMode.DARK, store.value)
            assertEquals(ThemePreferenceStatus.SAVED, controller.state.value.status)
        }

    @Test fun readFailureNeverWritesDefaultsAndRetryRestoresTheStoredMode() =
        runTest {
            val store =
                Store().apply {
                    value = ThemeMode.SYSTEM
                    failRead = true
                }
            val controller = ThemePreferenceController(backgroundScope, store)
            runCurrent()
            controller.select(ThemeMode.DARK)
            runCurrent()
            assertEquals(ThemePreferenceStatus.READ_FAILED, controller.state.value.status)
            assertTrue(store.writes.isEmpty())
            store.failRead = false
            controller.retry()
            runCurrent()
            assertEquals(ThemeMode.SYSTEM, controller.state.value.mode)
        }

    @Test fun explicitResetAuthorizationSurvivesATransientWriteFailureAndRetry() =
        runTest {
            val store =
                Store().apply {
                    failRead = true
                    failWrite = true
                }
            val controller = ThemePreferenceController(backgroundScope, store)
            runCurrent()
            controller.reset()
            runCurrent()
            assertEquals(ThemePreferenceStatus.WRITE_FAILED, controller.state.value.status)
            store.failWrite = false
            controller.retry()
            runCurrent()
            assertEquals(listOf(true, true), store.writes.map { it.second })
            assertEquals(ThemeMode.LIGHT, store.value)
            assertEquals(ThemePreferenceStatus.SAVED, controller.state.value.status)
        }

    @Test fun blockedWriterCannotBeOvertakenByAnotherSelectionOrReset() =
        runTest {
            val store = Store().apply { writeGate = CompletableDeferred() }
            val controller = ThemePreferenceController(backgroundScope, store)
            runCurrent()
            controller.select(ThemeMode.SYSTEM)
            runCurrent()
            controller.select(ThemeMode.DARK)
            controller.reset()
            controller.retry()
            assertEquals(1, store.writes.size)
            store.writeGate!!.complete(Unit)
            runCurrent()
            assertEquals(ThemeMode.SYSTEM, store.value)
            controller.select(ThemeMode.DARK)
            runCurrent()
            assertEquals(ThemeMode.DARK, store.value)
        }

    @Test fun closingCancelsPendingReadAndRejectsLaterChanges() =
        runTest {
            val store = Store().apply { readGate = CompletableDeferred() }
            val controller = ThemePreferenceController(backgroundScope, store)
            runCurrent()
            val before = controller.state.value
            controller.close()
            store.readGate!!.complete(Unit)
            controller.select(ThemeMode.DARK)
            controller.retry()
            controller.reset()
            runCurrent()
            assertEquals(before, controller.state.value)
            assertTrue(store.writes.isEmpty())
        }

    private class Store : ThemePreferenceStore {
        var value = ThemeMode.LIGHT
        var failRead = false
        var failWrite = false
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<Pair<ThemeMode, Boolean>>()

        override suspend fun read(): ThemeMode {
            readGate?.await()
            if (failRead) throw IOException("Synthetic read failure")
            return value
        }

        override suspend fun write(
            mode: ThemeMode,
            reset: Boolean,
        ) {
            writes += mode to reset
            writeGate?.await()
            if (failWrite) throw IOException("Synthetic write failure")
            value = mode
        }
    }
}
