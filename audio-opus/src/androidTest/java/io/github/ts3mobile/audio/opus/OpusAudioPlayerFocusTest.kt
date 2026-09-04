package io.github.ts3mobile.audio.opus

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class OpusAudioPlayerFocusTest {
    @Test
    fun playbackPausesAndRecoversAcrossFiveFocusInterruptions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(AudioManager::class.java)
        val baselineActiveTracks = activeVoicePlaybackCount(audioManager)
        val encodedSilence =
            NativeOpusEncoder().use { encoder ->
                encoder.encode(ShortArray(OPUS_FRAME_SAMPLES))
            }
        val player = OpusAudioPlayer(context)
        val feederRunning = AtomicBoolean(true)
        var packetId = 0
        val feeder =
            Thread(
                {
                    try {
                        while (feederRunning.get()) {
                            player.submit(
                                VoiceFrame(
                                    clientId = TEST_CLIENT_ID,
                                    packetId = packetId++,
                                    codec = VoiceCodec.OPUS_VOICE,
                                    encodedData = encodedSilence,
                                    isWhisper = false,
                                ),
                            )
                            Thread.sleep(OPUS_FRAME_DURATION_MS)
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                },
                "ts3-focus-test-feeder",
            )
        val interrupterRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(voicePlaybackAudioAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener {}
                .build()

        try {
            player.start()
            feeder.start()
            assertTrue(
                "Playback did not become active",
                waitUntil { activeVoicePlaybackCount(audioManager) > baselineActiveTracks },
            )

            repeat(INTERRUPTION_CYCLES) {
                assertEquals(
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                    audioManager.requestAudioFocus(interrupterRequest),
                )
                assertTrue(
                    "Playback stayed active after focus loss",
                    waitUntil { activeVoicePlaybackCount(audioManager) <= baselineActiveTracks },
                )

                audioManager.abandonAudioFocusRequest(interrupterRequest)

                assertTrue(
                    "Playback did not recover after focus gain",
                    waitUntil { activeVoicePlaybackCount(audioManager) > baselineActiveTracks },
                )
            }
        } finally {
            audioManager.abandonAudioFocusRequest(interrupterRequest)
            feederRunning.set(false)
            feeder.interrupt()
            feeder.join(FEEDER_JOIN_TIMEOUT_MS)
            player.close()
        }
    }

    private fun activeVoicePlaybackCount(audioManager: AudioManager): Int =
        audioManager.activePlaybackConfigurations.count { configuration ->
            configuration.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
        }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_MS * NANOS_PER_MILLISECOND
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private companion object {
        const val TEST_CLIENT_ID = 7
        const val OPUS_FRAME_SAMPLES = 960
        const val OPUS_FRAME_DURATION_MS = 20L
        const val INTERRUPTION_CYCLES = 5
        const val WAIT_TIMEOUT_MS = 2_000L
        const val POLL_INTERVAL_MS = 20L
        const val FEEDER_JOIN_TIMEOUT_MS = 1_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
