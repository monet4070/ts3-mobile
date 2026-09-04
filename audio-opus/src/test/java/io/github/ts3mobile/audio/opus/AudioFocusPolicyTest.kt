package io.github.ts3mobile.audio.opus

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFocusPolicyTest {
    @Test
    fun gainAllowsPlaybackToRecover() {
        assertEquals(
            AudioFocusSignal.GAIN,
            AudioFocusPolicy.signalFor(AudioManager.AUDIOFOCUS_GAIN),
        )
    }

    @Test
    fun interruptSignalsShareTheMuteAndResetPath() {
        listOf(
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        ).forEach { change ->
            assertEquals(AudioFocusSignal.INTERRUPT, AudioFocusPolicy.signalFor(change))
        }
    }

    @Test
    fun unknownSignalsDoNotChangePlaybackState() {
        assertEquals(AudioFocusSignal.IGNORE, AudioFocusPolicy.signalFor(Int.MIN_VALUE))
    }
}
