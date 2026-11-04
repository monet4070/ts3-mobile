package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneCapturePipelineTest {
    @Test fun volumeAndGateChangesDoNotReconfigureNativeProcessingButRouteChangesDo() {
        var configurations = 0
        val encoded = mutableListOf<ShortArray>()
        val pipeline =
            MicrophoneCapturePipeline(
                { 0f },
                {
                    encoded += it.copyOf()
                    byteArrayOf(1)
                },
                CaptureSessionStats(),
                { configurations++ },
            )
        val original = MicrophoneProcessingOptions()
        repeat(2) { pipeline.process(frame(1000), original, 0, 0) }
        for (revision in 1L..30L) {
            val changed = original.copy(settings = original.settings.copy(volumePercent = 100 + revision.toInt()))
            repeat(2) { pipeline.process(frame(1000), changed, revision, 0) }
        }
        assertEquals(1, configurations)
        assertTrue(encoded.all { block -> block.all { it > 0 } })
        repeat(2) { pipeline.process(frame(1000), original, 31, 1) }
        assertEquals(2, configurations)
        repeat(2) { pipeline.process(frame(1000), original.copy(settings = original.settings.copy(echoCancellation = false)), 32, 1) }
        assertEquals(3, configurations)
    }

    @Test fun disabledSuppressionBypassesDenoiserAndGainAppliesBeforeEncoder() {
        val captured = mutableListOf<ShortArray>()
        val pipeline =
            MicrophoneCapturePipeline({ error("Suppression must be bypassed") }, {
                captured += it.copyOf()
                byteArrayOf(1)
            }, CaptureSessionStats(), {})
        val options = MicrophoneProcessingOptions(MicrophoneSettings(volumePercent = 150, noiseSuppression = false))
        assertNull(pipeline.process(frame(1000), options, 0))
        assertEquals(1, pipeline.process(frame(2000), options, 0)!!.size)
        assertArrayEquals(ShortArray(480) { 1500 } + ShortArray(480) { 3000 }, captured.single())
    }

    @Test fun suppressionPrecedesVolumeGateAndVadProbabilityDoesNotOverrideRms() {
        var calls = 0
        var encoded = 0
        val pipeline =
            MicrophoneCapturePipeline({ pcm ->
                calls++
                pcm.fill(0)
                1f
            }, {
                encoded++
                byteArrayOf(1)
            }, CaptureSessionStats(), {})
        val options = MicrophoneProcessingOptions(volumeGate = true)
        repeat(100) { pipeline.process(frame(20000), options, 0) }
        assertEquals(100, calls)
        assertEquals(0, encoded)
        assertFalse(pipeline.isSpeaking)
    }

    @Test fun partialFrameCannotEndTalkspurtAndConfigRevisionDiscardsPartialAudio() {
        val encoded = mutableListOf<ShortArray>()
        val pipeline =
            MicrophoneCapturePipeline({ 0f }, {
                encoded += it.copyOf()
                byteArrayOf(1)
            }, CaptureSessionStats(), {})
        val options = MicrophoneProcessingOptions(MicrophoneSettings(noiseSuppression = false))
        pipeline.process(frame(1000), options, 0)
        assertNull(pipeline.process(frame(2000), options, 1))
        pipeline.process(frame(3000), options, 1)
        assertArrayEquals(frame(2000) + frame(3000), encoded.single())
        assertTrue(pipeline.isSpeaking)
        assertNull(pipeline.process(frame(0), options, 1))
        assertTrue(pipeline.isSpeaking)
    }

    @Test fun gateOnsetTailAndStopFlowThroughProductionBuffer() {
        val pipeline = MicrophoneCapturePipeline({ 0f }, { byteArrayOf((it[0] / 100).toByte()) }, CaptureSessionStats(), {})
        val queue = CaptureVoiceBuffer()
        val options = MicrophoneProcessingOptions(MicrophoneSettings(noiseSuppression = false), true)

        fun feed(value: Int) {
            repeat(2) {
                val frames = pipeline.process(frame(value), options, queue.revision())
                if (frames != null) queue.publish(queue.revision(), frames, pipeline.isSpeaking)
            }
        }
        repeat(1000) { feed(0) }
        assertFalse(queue.isTransmitting())
        feed(1000)
        assertFalse(queue.isTransmitting())
        feed(2000)
        assertTrue(queue.isTransmitting())
        assertEquals(listOf(0, 0, 0, 0, 10, 20), generateSequence { queue.poll()?.single()?.toInt() }.toList())
        assertTrue(queue.isTransmitting())
        repeat(15) { feed(0) }
        assertFalse(pipeline.isSpeaking)
        assertTrue(queue.isTransmitting())
        while (queue.poll() != null) Unit
        assertFalse(queue.isTransmitting())
        feed(1000)
        feed(2000)
        queue.stop()
        assertNull(queue.poll())
        assertFalse(queue.isTransmitting())
    }

    @Test fun denoiserAndEncoderFailuresRemainVisible() {
        val options = MicrophoneProcessingOptions()
        val denoiseFailure = MicrophoneCapturePipeline({ error("denoise failure") }, { byteArrayOf() }, CaptureSessionStats(), {})
        assertThrows(IllegalStateException::class.java) { denoiseFailure.process(frame(1), options, 0) }
        val encoderFailure = MicrophoneCapturePipeline({ 0f }, { error("encoder failure") }, CaptureSessionStats(), {})
        encoderFailure.process(frame(1), options, 0)
        assertThrows(IllegalStateException::class.java) { encoderFailure.process(frame(1), options, 0) }
    }

    @Test fun revisionClearsOldOnsetAndPartialFrameWhileGainStillRamps() {
        val encoded = mutableListOf<ShortArray>()
        val pipeline =
            MicrophoneCapturePipeline({ 0f }, {
                encoded += it.copyOf()
                byteArrayOf(1)
            }, CaptureSessionStats(), {})
        val original = MicrophoneProcessingOptions(MicrophoneSettings(noiseSuppression = false), true)
        repeat(2) { pipeline.process(frame(10), original, 0) }
        pipeline.process(frame(30000), original, 0)
        val changed = original.copy(settings = original.settings.copy(volumePercent = 200))
        repeat(4) { pipeline.process(frame(1000), changed, 1) }
        assertEquals(2, encoded.size)
        assertEquals(1001, encoded[0].first().toInt())
        assertEquals(2000, encoded[0].last().toInt())
        assertArrayEquals(frame(2000) + frame(2000), encoded[1])
        assertTrue(encoded.all { block -> block.all { it.toInt() in 1000..2000 } })
    }

    @Test fun sameOptionsNewRevisionClearsLimiterAndOldPartialSamples() {
        val encoded = mutableListOf<ShortArray>()
        var configurations = 0
        val pipeline =
            MicrophoneCapturePipeline({ 0f }, {
                encoded += it.copyOf()
                byteArrayOf(1)
            }, CaptureSessionStats(), { configurations++ })
        val options = MicrophoneProcessingOptions(MicrophoneSettings(volumePercent = 200, noiseSuppression = false))
        repeat(3) { pipeline.process(frame(32767), options, 0) }
        repeat(2) { pipeline.process(frame(1000), options, 1) }
        assertEquals(2, encoded.size)
        assertEquals(2, configurations)
        assertArrayEquals(frame(2000) + frame(2000), encoded.last())
    }

    private fun frame(value: Int) = ShortArray(480) { value.toShort() }
}
