package io.github.ts3mobile.app.updates

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    @Test fun openingThePageRestoresTheChoiceAndChecksOnlyThatChannel() =
        runTest {
            val store = Store().apply { channel = UpdateChannel.INCLUDE_PRERELEASES }
            val calls = mutableListOf<UpdateChannel>()
            val controller =
                UpdateController(
                    backgroundScope,
                    UpdateSource {
                        calls += it
                        release()
                    },
                    store,
                )
            runCurrent()
            assertEquals(listOf(UpdateChannel.INCLUDE_PRERELEASES), calls)
            assertEquals(UpdateCheck.Complete(release()), controller.state.value.check)
            assertTrue(store.writes.isEmpty())
        }

    @Test fun duplicateCheckTapsDoNotStartConcurrentRequests() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val controller =
                UpdateController(
                    backgroundScope,
                    UpdateSource {
                        calls++
                        gate.await()
                        release()
                    },
                    Store(),
                )
            runCurrent()
            repeat(5) { controller.check() }
            runCurrent()
            assertEquals(1, calls)
            gate.complete(Unit)
            runCurrent()
            controller.check()
            runCurrent()
            assertEquals(2, calls)
        }

    @Test fun aReplacedChannelCannotPublishAnOldResultAndNetworkCallsStaySerial() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val calls = mutableListOf<UpdateChannel>()
            val source =
                UpdateSource { channel ->
                    calls += channel
                    if (channel == UpdateChannel.STABLE) withContext(NonCancellable) { gate.await() }
                    release(if (channel == UpdateChannel.STABLE) 15 else 16)
                }
            val controller = UpdateController(backgroundScope, source, Store())
            runCurrent()
            controller.select(UpdateChannel.INCLUDE_PRERELEASES)
            runCurrent()
            assertEquals(UpdateCheck.Checking, controller.state.value.check)
            assertEquals(listOf(UpdateChannel.STABLE), calls)
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(UpdateChannel.STABLE, UpdateChannel.INCLUDE_PRERELEASES), calls)
            assertEquals(UpdateCheck.Complete(release(16)), controller.state.value.check)
        }

    @Test fun missingReleasesAndNetworkFailuresNeverClaimTheAppIsCurrent() =
        runTest {
            for ((error, expected) in listOf(
                IOException("fixture") to UpdateFailure.NETWORK,
                SocketTimeoutException() to UpdateFailure.TIMEOUT,
                UpdateCheckException(UpdateFailure.RATE_LIMITED) to UpdateFailure.RATE_LIMITED,
                UpdateCheckException(UpdateFailure.INVALID_RELEASE) to UpdateFailure.INVALID_RELEASE,
            )) {
                val controller = UpdateController(backgroundScope, UpdateSource { throw error }, Store())
                runCurrent()
                assertEquals(UpdateCheck.Failed(expected), controller.state.value.check)
                controller.close()
            }
            val empty = UpdateController(backgroundScope, UpdateSource { null }, Store())
            runCurrent()
            assertEquals(UpdateCheck.NoRelease, empty.state.value.check)
        }

    @Test fun failedPreferenceReadsCannotSilentlyReplaceTheStoredChoice() =
        runTest {
            val store =
                Store().apply {
                    failRead = true
                    channel = UpdateChannel.INCLUDE_PRERELEASES
                }
            var checks = 0
            val controller =
                UpdateController(
                    backgroundScope,
                    UpdateSource {
                        checks++
                        release()
                    },
                    store,
                )
            runCurrent()
            controller.select(UpdateChannel.STABLE)
            controller.check()
            runCurrent()
            assertEquals(UpdatePreferenceStatus.READ_FAILED, controller.state.value.preferenceStatus)
            assertEquals(0, checks)
            assertTrue(store.writes.isEmpty())
            store.failRead = false
            controller.retryPreference()
            runCurrent()
            assertEquals(UpdateChannel.INCLUDE_PRERELEASES, controller.state.value.channel)
            assertEquals(1, checks)
        }

    @Test fun failedChoiceWritesAreVisibleAndRetryPersistsBeforeChecking() =
        runTest {
            val store = Store()
            var checks = 0
            val controller =
                UpdateController(
                    backgroundScope,
                    UpdateSource {
                        checks++
                        release()
                    },
                    store,
                )
            runCurrent()
            store.failWrite = true
            controller.select(UpdateChannel.INCLUDE_PRERELEASES)
            runCurrent()
            assertEquals(UpdatePreferenceStatus.WRITE_FAILED, controller.state.value.preferenceStatus)
            assertEquals(UpdateCheck.Idle, controller.state.value.check)
            assertEquals(1, checks)
            store.failWrite = false
            controller.retryPreference()
            runCurrent()
            assertEquals(UpdateChannel.INCLUDE_PRERELEASES, store.channel)
            assertEquals(2, checks)
        }

    @Test fun closingThePageRejectsLateResultsAndFurtherActions() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val controller =
                UpdateController(
                    backgroundScope,
                    UpdateSource {
                        withContext(NonCancellable) { gate.await() }
                        release()
                    },
                    Store(),
                )
            runCurrent()
            controller.close()
            val before = controller.state.value
            gate.complete(Unit)
            controller.check()
            controller.select(UpdateChannel.INCLUDE_PRERELEASES)
            runCurrent()
            assertEquals(before, controller.state.value)
        }

    private class Store : UpdateChannelStore {
        var channel = UpdateChannel.STABLE
        var failRead = false
        var failWrite = false
        val writes = mutableListOf<UpdateChannel>()

        override suspend fun read(): UpdateChannel {
            if (failRead) throw IOException("fixture read")
            return channel
        }

        override suspend fun write(channel: UpdateChannel) {
            writes += channel
            if (failWrite) throw IOException("fixture write")
            this.channel = channel
        }
    }
}
