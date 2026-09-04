package io.github.ts3mobile.audio.opus

import android.media.AudioManager

/** The small state transition surface needed by the playback focus listener. */
internal enum class AudioFocusSignal {
    GAIN,
    INTERRUPT,
    IGNORE,
}

internal object AudioFocusPolicy {
    fun signalFor(change: Int): AudioFocusSignal =
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> AudioFocusSignal.GAIN
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> AudioFocusSignal.INTERRUPT

            else -> AudioFocusSignal.IGNORE
        }
}
