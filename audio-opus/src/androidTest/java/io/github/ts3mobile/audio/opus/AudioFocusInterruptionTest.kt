package io.github.ts3mobile.audio.opus

import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AudioFocusInterruptionTest {
    @Test
    fun fiveTransientInterruptionsDeliverLossThenGain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(AudioManager::class.java)
        repeat(INTERRUPTION_CYCLES) {
            val primaryChanges = LinkedBlockingQueue<Int>()
            val primaryRequest =
                focusRequest(AudioManager.AUDIOFOCUS_GAIN) { change ->
                    primaryChanges.offer(change)
                }
            val interrupterRequest =
                focusRequest(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) {}

            try {
                assertEquals(
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                    audioManager.requestAudioFocus(primaryRequest),
                )
                assertEquals(
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                    audioManager.requestAudioFocus(interrupterRequest),
                )
                assertEquals(
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    primaryChanges.poll(FOCUS_CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )

                audioManager.abandonAudioFocusRequest(interrupterRequest)

                assertEquals(
                    AudioManager.AUDIOFOCUS_GAIN,
                    primaryChanges.poll(FOCUS_CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
            } finally {
                audioManager.abandonAudioFocusRequest(interrupterRequest)
                audioManager.abandonAudioFocusRequest(primaryRequest)
            }
        }
    }

    private fun focusRequest(
        gain: Int,
        onChange: (Int) -> Unit,
    ): AudioFocusRequest =
        AudioFocusRequest.Builder(gain)
            .setAudioAttributes(voicePlaybackAudioAttributes())
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(onChange)
            .build()

    private companion object {
        const val INTERRUPTION_CYCLES = 5
        const val FOCUS_CALLBACK_TIMEOUT_SECONDS = 2L
    }
}
