package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.DisconnectOutcome
import io.github.ts3mobile.protocol.Ts3SessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Executes an exit captured by the coordinator; it never owns or creates an active session. */
internal class SessionExitRunner(
    private val host: ConnectionCoordinatorHost,
    private val ownership: SessionRequestOwnership,
    private val clockMs: () -> Long,
    private val isClosing: () -> Boolean,
    private val onDeferredCleanupPending: () -> Unit = {},
    private val onAudioReclaimed: () -> Unit = {},
) {
    suspend fun run(
        trigger: ExitTrigger,
        captured: Ts3SessionClient?,
        handshaking: Boolean,
        deadlineMs: Long,
        exitToken: Long,
        onReleased: () -> Unit,
    ) {
        host.recordDiagnostic(DiagnosticEventKind.EXIT_STARTED)
        var outcome = DisconnectOutcome.FAILED
        var protocolCompleted = false
        var cleanupFailed = false
        var captureStopped = false
        var playbackStopped = false
        var released = false

        fun releaseCaptured() {
            if (released) return
            released = true
            try {
                captured?.close()
            } finally {
                onReleased()
            }
        }
        try {
            if (handshaking) releaseCaptured()
            val completed =
                withTimeoutOrNull((deadlineMs - clockMs()).coerceAtLeast(0)) {
                    // Ownership and the detached voice source already prevent new
                    // transmissions. Notify the server before native audio cleanup
                    // or another session operation can spend the exit budget.
                    outcome =
                        when {
                            handshaking || captured == null -> DisconnectOutcome.NO_ACTIVE_SESSION
                            deadlineMs <= clockMs() -> DisconnectOutcome.TIMED_OUT
                            else ->
                                captured.disconnectBounded(
                                    deadlineMs - clockMs(),
                                    trigger.reasonMessage,
                                ) { host.recordDiagnostic(DiagnosticEventKind.EXIT_REQUEST_SENT) }
                        }
                    protocolCompleted = true
                    releaseCaptured()
                    // New connections still wait for this exit job. Reclaim shared
                    // audio only after any old operation releases the session mutex.
                    host.sessionMutex.withLock {
                        if (stopAudio(
                                stopCapture = !captureStopped,
                                stopPlayback = !playbackStopped,
                                onCaptureStopped = { captureStopped = true },
                                onPlaybackStopped = { playbackStopped = true },
                            )
                        ) {
                            cleanupFailed = true
                        }
                    }
                    true
                } == true
            if (!completed && !protocolCompleted) outcome = DisconnectOutcome.TIMED_OUT
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            if (protocolCompleted) cleanupFailed = true else outcome = DisconnectOutcome.FAILED
        } finally {
            try {
                releaseCaptured()
            } catch (_: Throwable) {
                cleanupFailed = true
            }
            if (!isClosing()) {
                // A missed deadline must not leave capture, playback or a bound
                // UI alive in DISCONNECTING. Native driver/scheduler overruns are
                // recorded explicitly; cleanup never means server confirmation.
                if (!captureStopped || !playbackStopped) {
                    withContext(NonCancellable) {
                        if (host.sessionMutex.tryLockBounded(CLEANUP_MUTEX_TIMEOUT_MS)) {
                            try {
                                if (stopAudio(
                                        stopCapture = !captureStopped,
                                        stopPlayback = !playbackStopped,
                                        onCaptureStopped = { captureStopped = true },
                                        onPlaybackStopped = { playbackStopped = true },
                                    )
                                ) {
                                    cleanupFailed = true
                                }
                            } catch (_: Throwable) {
                                cleanupFailed = true
                            } finally {
                                host.sessionMutex.unlock()
                            }
                        }
                    }
                }
                if (!captureStopped || !playbackStopped) {
                    cleanupFailed = true
                    onDeferredCleanupPending()
                    runCatching { host.stopMicrophoneImmediately() }
                    scheduleDeferredAudioReclamation(exitToken, needCapture = !captureStopped, needPlayback = !playbackStopped)
                } else {
                    onAudioReclaimed()
                }
                ownership.withLatestExit(exitToken) {
                    host.mutableState.update { current ->
                        current.resetForDisconnected(host.diagnosticsRecorder.snapshot())
                    }
                }
                if (clockMs() > deadlineMs) {
                    host.recordDiagnostic(DiagnosticEventKind.EXIT_CLEANUP_OVERRUN, clockMs() - deadlineMs)
                }
                if (cleanupFailed) host.recordDiagnostic(DiagnosticEventKind.EXIT_CLEANUP_FAILED)
                host.recordDiagnostic(outcome.toDiagnosticKind())
                host.finishExitOnMainThread(exitToken)
            }
        }
    }

    private suspend fun stopAudio(
        stopCapture: Boolean,
        stopPlayback: Boolean,
        onCaptureStopped: () -> Unit,
        onPlaybackStopped: () -> Unit,
    ) = coroutineScope {
        val capture =
            if (stopCapture) {
                async {
                    val failure = host.releaseCapture()
                    onCaptureStopped()
                    failure
                }
            } else {
                null
            }
        val playback =
            if (stopPlayback) {
                async {
                    runInterruptible {
                        try {
                            host.stopPlayback()
                        } finally {
                            host.clearPlaybackMuted()
                            if (!isClosing()) host.stopAudioRouting()
                        }
                    }
                    onPlaybackStopped()
                }
            } else {
                null
            }
        val captureFailure = capture?.await()
        playback?.await()
        captureFailure != null
    }

    private fun scheduleDeferredAudioReclamation(
        exitToken: Long,
        needCapture: Boolean,
        needPlayback: Boolean,
    ) {
        host.serviceScope.launch {
            host.sessionMutex.withLock {
                if (isClosing() || !ownership.isLatestExit(exitToken)) return@withLock
                try {
                    if (stopAudio(
                            stopCapture = needCapture,
                            stopPlayback = needPlayback,
                            onCaptureStopped = {},
                            onPlaybackStopped = {},
                        )
                    ) {
                        host.recordDiagnostic(DiagnosticEventKind.EXIT_CLEANUP_FAILED)
                    } else {
                        onAudioReclaimed()
                    }
                } catch (_: Throwable) {
                    host.recordDiagnostic(DiagnosticEventKind.EXIT_CLEANUP_FAILED)
                }
            }
        }
    }

    private fun DisconnectOutcome.toDiagnosticKind(): DiagnosticEventKind =
        when (this) {
            DisconnectOutcome.SERVER_CONFIRMED -> DiagnosticEventKind.EXIT_SERVER_CONFIRMED
            DisconnectOutcome.TIMED_OUT -> DiagnosticEventKind.EXIT_TIMED_OUT
            DisconnectOutcome.FAILED -> DiagnosticEventKind.EXIT_FAILED
            DisconnectOutcome.NO_ACTIVE_SESSION -> DiagnosticEventKind.EXIT_NO_ACTIVE_SESSION
            DisconnectOutcome.UNSUPPORTED -> DiagnosticEventKind.EXIT_UNSUPPORTED
        }

    private companion object {
        const val CLEANUP_MUTEX_TIMEOUT_MS = 500L
    }
}
