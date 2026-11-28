package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingPttSpeakerActivityTest {
    @Test
    fun latestVoiceFrameKeepsEachSpeakerVisibleUntilTheyGoQuiet() {
        var clock = 100L
        val activity = FloatingPttSpeakerActivity { clock }
        activity.setObserving(true)
        activity.record(frame(11))

        clock = 700L
        activity.record(frame(12))
        assertEquals(setOf(11, 12), activity.recentClientIds())

        clock = 1_000L
        activity.record(frame(12))
        clock = 1_100L
        assertEquals(setOf(12), activity.recentClientIds())

        clock = 2_000L
        assertTrue(activity.recentClientIds().isEmpty())
    }

    @Test
    fun ignoresEmptyFramesWhispersAndVoiceOutsideTheCurrentOverlayLease() {
        val activity = FloatingPttSpeakerActivity { 100L }
        activity.record(frame(11))
        activity.setObserving(true)
        activity.record(frame(12).copy(encodedData = byteArrayOf()))
        activity.record(frame(13).copy(isWhisper = true))
        assertTrue(activity.recentClientIds().isEmpty())

        activity.record(frame(14))
        assertEquals(setOf(14), activity.recentClientIds())
        activity.setObserving(false)
        activity.record(frame(15))
        activity.setObserving(true)
        assertTrue(activity.recentClientIds().isEmpty())
    }

    private fun frame(clientId: Int): VoiceFrame = VoiceFrame(clientId, 1, VoiceCodec.OPUS_VOICE, byteArrayOf(1), isWhisper = false)
}
