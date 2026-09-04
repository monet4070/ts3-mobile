package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionPhase

internal object MicrophoneCapturePolicy {
    fun shouldCapture(
        phase: ConnectionPhase,
        mode: MicrophoneMode,
        pushToTalkPressed: Boolean,
    ): Boolean {
        if (phase != ConnectionPhase.CONNECTED) return false
        return when (mode) {
            MicrophoneMode.OFF -> false
            MicrophoneMode.PUSH_TO_TALK -> pushToTalkPressed
            MicrophoneMode.CONTINUOUS -> true
        }
    }

    fun acceptedPushToTalkState(
        mode: MicrophoneMode,
        requestedPressed: Boolean,
    ): Boolean = mode == MicrophoneMode.PUSH_TO_TALK && requestedPressed
}
