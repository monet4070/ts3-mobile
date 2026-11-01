package io.github.ts3mobile.audio.opus

import android.media.AudioDeviceInfo
import io.github.ts3mobile.protocol.EncodedVoiceSource

/** Android capture boundary, allowing lifecycle verification without recording. */
interface MicrophoneCapture : EncodedVoiceSource, AutoCloseable {
    val isCapturing: Boolean
    val generation: Long
    val isReleased: Boolean get() = !isCapturing

    fun requestStop() {}

    fun configure(
        settings: MicrophoneSettings,
        volumeGate: Boolean,
    )

    fun setPreferredDevice(device: AudioDeviceInfo?)

    /** Output-only acoustic changes also invalidate capture history. Older adapters may ignore this hook. */
    fun invalidateRouting() {}

    fun start()

    fun stop()
}
