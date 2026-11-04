package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneAudioEffectsTest {
    @Test fun reportsActualStateOnlyAfterSuccessfulPlatformAcknowledgement() {
        assertEquals(EchoCancellationState.ENABLED, MicrophoneAudioEffects.effectState(true, 0, true))
        assertEquals(EchoCancellationState.DISABLED, MicrophoneAudioEffects.effectState(false, 0, false))
        assertEquals(EchoCancellationState.FAILED, MicrophoneAudioEffects.effectState(true, -5, true))
        assertEquals(EchoCancellationState.FAILED, MicrophoneAudioEffects.effectState(true, 0, false))
        assertEquals(EchoCancellationState.FAILED, MicrophoneAudioEffects.effectState(false, 0, true))
    }
}
