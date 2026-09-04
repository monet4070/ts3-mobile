package io.github.ts3mobile.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class DiagnosticsTest {
    @Test
    fun snapshotCountsSessionEventsAndUsesElapsedTime() {
        val now = AtomicLong(1_000L)
        val recorder = DiagnosticsRecorder(now::get)

        recorder.recordConnectionAttempt(reconnecting = false)
        recorder.recordConnectionAttempt(reconnecting = true)
        recorder.recordConnectionSuccess()
        recorder.recordConnectionFailure(retryable = true)
        recorder.recordVoiceFrameReceived()
        recorder.recordVoiceFrameDropped()
        recorder.recordMicrophoneError()
        recorder.recordChannelJoinAttempt()
        recorder.recordChannelJoinFailure()
        now.set(2_500L)

        assertEquals(
            DiagnosticsSnapshot(
                uptimeMs = 1_500L,
                connectionAttempts = 2L,
                reconnectAttempts = 1L,
                successfulConnections = 1L,
                failedConnections = 1L,
                retryableFailures = 1L,
                voiceFramesReceived = 1L,
                voiceFramesDropped = 1L,
                microphoneErrors = 1L,
                channelJoinAttempts = 1L,
                channelJoinFailures = 1L,
                lastFailure = DiagnosticFailureKind.CHANNEL,
            ),
            recorder.snapshot(),
        )
    }

    @Test
    fun redactedExportContainsCountersButNoConnectionIdentity() {
        val snapshot =
            DiagnosticsSnapshot(
                connectionAttempts = 3L,
                lastFailure = DiagnosticFailureKind.TERMINAL_CONNECTION,
            )

        val export = snapshot.toRedactedJson()

        assertTrue(export.contains("ts3-mobile-diagnostics/v1"))
        assertTrue(export.contains("\"connectionAttempts\":3"))
        assertTrue(export.contains("TERMINAL_CONNECTION"))
        assertFalse(export.contains("host"))
        assertFalse(export.contains("password"))
        assertFalse(export.contains("nickname"))
    }

    @Test
    fun resetStartsAFreshSessionWithoutLeakingPreviousCounters() {
        val now = AtomicLong(5_000L)
        val recorder = DiagnosticsRecorder(now::get)
        recorder.recordConnectionAttempt(reconnecting = false)
        now.set(10_000L)

        recorder.resetSession()

        assertEquals(0L, recorder.snapshot().connectionAttempts)
        assertEquals(0L, recorder.snapshot().uptimeMs)
    }

    @Test
    fun concurrentProtocolCallbacksProduceAConsistentSnapshot() {
        val recorder = DiagnosticsRecorder { 0L }
        val workers =
            List(4) {
                Thread {
                    repeat(1_000) {
                        recorder.recordVoiceFrameReceived()
                        recorder.recordConnectionAttempt(reconnecting = true)
                    }
                }
            }

        workers.forEach(Thread::start)
        workers.forEach(Thread::join)

        val snapshot = recorder.snapshot()
        assertEquals(4_000L, snapshot.voiceFramesReceived)
        assertEquals(4_000L, snapshot.connectionAttempts)
        assertEquals(4_000L, snapshot.reconnectAttempts)
    }
}
