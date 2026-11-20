package io.github.ts3mobile.app.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Installs the process-owned diagnostics repository into one service instance.
 *
 * The closed check and the installation happen in the same turn of [attachDispatcher]
 * (`Dispatchers.Main.immediate` in production), which is also where `Service.onDestroy`
 * runs. A service that is already destroyed therefore cannot attach a late collaborator,
 * and there is no check-then-assign window between the two.
 */
internal class AudioDiagnosticsAttachment(
    private val loading: Deferred<AudioDiagnosticRepository>,
    private val attachDispatcher: CoroutineDispatcher,
    private val isClosed: () -> Boolean,
    private val attach: (AudioDiagnosticRepository) -> Unit,
) {
    /** Carries the outcome rather than failing the deferred, so a lost service is not an uncaught crash. */
    private val ready = CompletableDeferred<Result<Unit>>()

    fun start(scope: CoroutineScope) {
        scope.launch {
            try {
                val repository = loading.await()
                withContext(attachDispatcher) {
                    if (isClosed()) failIfPending() else attachAndReady(repository)
                }
            } catch (error: CancellationException) {
                failIfPending()
                throw error
            } catch (error: Exception) {
                // Fixed category only: storage exceptions can carry private file paths.
                System.err.println("TS3_AUDIO: diagnostics unavailable: ${error.javaClass.simpleName}")
                failIfPending()
            }
        }
    }

    /** Suspends until the repository is installed; never resolves to a half-initialized archive. */
    suspend fun awaitReady() = ready.await().getOrThrow()

    /** Fails waiting callers of a destroyed service instead of leaving them suspended. */
    fun failIfPending() {
        ready.complete(Result.failure(Unavailable()))
    }

    private fun attachAndReady(repository: AudioDiagnosticRepository) {
        attach(repository)
        ready.complete(Result.success(Unit))
    }

    private class Unavailable : IllegalStateException("Audio diagnostics are not available")
}
