package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.DisconnectOutcome
import io.github.ts3mobile.protocol.Ts3SessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Executes an exit captured by the coordinator; it never owns or creates an active session. */
internal class SessionExitRunner(
    private val host: ConnectionCoordinatorHost,
    private val ownership: SessionRequestOwnership,
    private val clockMs: () -> Long,
    private val isClosing: () -> Boolean,
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
        var audioStopped = false
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
                    host.sessionMutex.lock()
                    try {
                        stopAudio()
                        audioStopped = true
                    } finally {
                        host.sessionMutex.unlock()
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
                if (!audioStopped) {
                    withContext(NonCancellable) {
                        try {
                            stopAudio()
                        } catch (_: Throwable) {
                            cleanupFailed = true
                        }
                    }
                }
                ownership.withLatestExit(exitToken) {
                    host.mutableState.value =
                        TeamSpeakServiceState(
                            diagnostics = host.diagnosticsRecorder.snapshot(),
                            backgroundRuntime = host.mutableState.value.backgroundRuntime,
                        )
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

    private suspend fun stopAudio() =
        coroutineScope {
            val capture = async { host.stopMicrophoneCapture() }
            val playback =
                async {
                    runInterruptible {
                        try {
                            host.stopPlayback()
                        } finally {
                            host.clearPlaybackMuted()
                            if (!isClosing()) host.stopAudioRouting()
                        }
                    }
                }
            capture.await()
            playback.await()
        }

    private fun DisconnectOutcome.toDiagnosticKind(): DiagnosticEventKind =
        when (this) {
            DisconnectOutcome.SERVER_CONFIRMED -> DiagnosticEventKind.EXIT_SERVER_CONFIRMED
            DisconnectOutcome.TIMED_OUT -> DiagnosticEventKind.EXIT_TIMED_OUT
            DisconnectOutcome.FAILED -> DiagnosticEventKind.EXIT_FAILED
            DisconnectOutcome.NO_ACTIVE_SESSION -> DiagnosticEventKind.EXIT_NO_ACTIVE_SESSION
            DisconnectOutcome.UNSUPPORTED -> DiagnosticEventKind.EXIT_UNSUPPORTED
        }
}
