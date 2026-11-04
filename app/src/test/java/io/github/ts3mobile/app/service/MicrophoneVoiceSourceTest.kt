package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.EncodedVoiceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneVoiceSourceTest {
    @Test fun closingSendBoundaryImmediatelyHidesCaptureEvenBeforeResourcesStop() {
        var polls = 0
        val capture =
            object : EncodedVoiceSource {
                override val isTransmitting = true

                override fun isReady() = true

                override fun pollEncodedFrame(): ByteArray {
                    polls++
                    return byteArrayOf(1)
                }
            }
        val source = MicrophoneVoiceSource(capture)
        assertFalse(source.isReady())
        assertTrue(source.tryEnable(source.currentRevision()))
        assertTrue(source.isTransmitting)
        assertTrue(source.isReady())
        assertEquals(1, source.pollEncodedFrame()!!.single().toInt())
        source.disable()
        assertFalse(source.isTransmitting)
        assertFalse(source.isReady())
        assertNull(source.pollEncodedFrame())
        assertEquals(1, polls)
    }

    @Test fun offInvalidatesAnInFlightStartEvenWhenSendBoundaryWasAlreadyClosed() {
        val source =
            MicrophoneVoiceSource(
                object : EncodedVoiceSource {
                    override fun isReady() = true

                    override fun pollEncodedFrame() = byteArrayOf(1)
                },
            )
        val startingRevision = source.currentRevision()
        source.disable()
        assertFalse(source.tryEnable(startingRevision))
        assertNull(source.pollEncodedFrame())
        assertTrue(source.tryEnable(source.currentRevision()))
        assertTrue(source.isReady())
    }
}
