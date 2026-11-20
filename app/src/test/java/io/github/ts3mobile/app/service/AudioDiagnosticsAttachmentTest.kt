package io.github.ts3mobile.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AudioDiagnosticsAttachmentTest {
    @get:Rule val temporary = TemporaryFolder()

    /** Eager dispatcher: completing the loader immediately runs the attach continuation. */
    private val main = UnconfinedTestDispatcher()

    private fun TestScope.repo() = AudioDiagnosticRepository(temporary.newFile(), backgroundScope, { 1_000 })

    @Test fun readinessWaitsForTheArchiveAndInstallsItOnce() =
        runTest(main) {
            val loading = CompletableDeferred<AudioDiagnosticRepository>()
            val installed = mutableListOf<AudioDiagnosticRepository>()
            val attachment = AudioDiagnosticsAttachment(loading, main, { false }) { installed.add(it) }
            attachment.start(backgroundScope)
            val waiter = async { attachment.awaitReady() }
            assertFalse("Readiness must not resolve before the archive loads", waiter.isCompleted)
            val repository = repo()
            loading.complete(repository)
            assertSame(repository, installed.single())
            waiter.await()
        }

    @Test fun aServiceDestroyedWhileLoadingNeverInstallsAndFailsItsWaiters() =
        runTest(main) {
            val loading = CompletableDeferred<AudioDiagnosticRepository>()
            var closed = false
            var installs = 0
            val attachment = AudioDiagnosticsAttachment(loading, main, { closed }) { installs++ }
            attachment.start(backgroundScope)
            // Capture the outcome: an unobserved failing child would fail the test itself.
            val waiter = backgroundScope.async { runCatching { attachment.awaitReady() } }
            // Teardown runs before the archive is ready; the pending installation is abandoned.
            closed = true
            attachment.failIfPending()
            loading.complete(repo())
            assertEquals(0, installs)
            assertRejected(waiter)
        }

    @Test fun aReplacementServiceInstallsOnlyItsOwnAttachment() =
        runTest(main) {
            val loading = CompletableDeferred<AudioDiagnosticRepository>()
            var firstInstalls = 0
            var secondInstalls = 0
            AudioDiagnosticsAttachment(loading, main, { true }) { firstInstalls++ }.start(backgroundScope)
            val live = AudioDiagnosticsAttachment(loading, main, { false }) { secondInstalls++ }
            live.start(backgroundScope)
            loading.complete(repo())
            assertEquals(0, firstInstalls)
            assertEquals(1, secondInstalls)
            live.awaitReady()
        }

    @Test fun aFailedLoadFailsWaitersWithoutLeakingStorageDetail() =
        runTest(main) {
            val loading = CompletableDeferred<AudioDiagnosticRepository>()
            val attachment =
                AudioDiagnosticsAttachment(loading, main, { false }) {
                    fail("A failed load must not install anything")
                }
            attachment.start(backgroundScope)
            val waiter = backgroundScope.async { runCatching { attachment.awaitReady() } }
            loading.completeExceptionally(IOException("private /data/user/0/secret path"))
            assertRejected(waiter)
        }

    @Test fun everyConcurrentWaiterResolvesWhenReady() =
        runTest(main) {
            val loading = CompletableDeferred<AudioDiagnosticRepository>()
            val attachment = AudioDiagnosticsAttachment(loading, main, { false }) { }
            attachment.start(backgroundScope)
            val waiters = List(4) { async { attachment.awaitReady() } }
            loading.complete(repo())
            waiters.forEach { it.await() }
        }

    private suspend fun assertRejected(waiter: Deferred<Result<Unit>>) {
        val error = waiter.await().exceptionOrNull()
        assertTrue("An unavailable archive must not report readiness", error is IllegalStateException)
        assertFalse(error!!.message.orEmpty().contains("secret"))
    }
}
