package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneCapturePolicyTest {
    @Test
    fun disconnectedSessionsNeverCapture() {
        MicrophoneMode.entries.forEach { mode ->
            assertFalse(
                MicrophoneCapturePolicy.shouldCapture(
                    phase = ConnectionPhase.DISCONNECTED,
                    mode = mode,
                    pushToTalkPressed = true,
                ),
            )
        }
    }

    @Test
    fun offAndPushToTalkModesRequireExplicitCaptureIntent() {
        assertFalse(shouldCapture(MicrophoneMode.OFF, pushToTalkPressed = true))
        assertFalse(shouldCapture(MicrophoneMode.PUSH_TO_TALK, pushToTalkPressed = false))
        assertTrue(shouldCapture(MicrophoneMode.PUSH_TO_TALK, pushToTalkPressed = true))
    }

    @Test
    fun continuousModeCapturesWheneverConnected() {
        assertTrue(shouldCapture(MicrophoneMode.CONTINUOUS, pushToTalkPressed = false))
    }

    @Test
    fun pushToTalkPressesAreRejectedOutsidePushToTalkMode() {
        assertTrue(
            MicrophoneCapturePolicy.acceptedPushToTalkState(
                MicrophoneMode.PUSH_TO_TALK,
                requestedPressed = true,
            ),
        )
        assertFalse(
            MicrophoneCapturePolicy.acceptedPushToTalkState(
                MicrophoneMode.CONTINUOUS,
                requestedPressed = true,
            ),
        )
        assertFalse(
            MicrophoneCapturePolicy.acceptedPushToTalkState(
                MicrophoneMode.PUSH_TO_TALK,
                requestedPressed = false,
            ),
        )
    }

    private fun shouldCapture(
        mode: MicrophoneMode,
        pushToTalkPressed: Boolean,
    ): Boolean =
        MicrophoneCapturePolicy.shouldCapture(
            phase = ConnectionPhase.CONNECTED,
            mode = mode,
            pushToTalkPressed = pushToTalkPressed,
        )
}
