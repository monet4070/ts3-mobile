package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApmCapturePipelineTest {
    private val reference = RenderReferenceBuffer()
    private val engine = Engine()
    private val options = MicrophoneProcessingOptions(MicrophoneSettings(noiseSuppression = false, echoCancellation = false))
    private val encoded = mutableListOf<ShortArray>()

    @Test fun floatFramesKeepOneQuantizationAndUseTheExistingManualGainGate() {
        pipeline().use { pipeline ->
            val gated = options.copy(settings = options.settings.copy(volumePercent = 200), volumeGate = true)
            repeat(3) { pipeline.process(frame(250), NOW, gated, 0) }
            assertTrue(encoded.isEmpty())
            assertEquals(2, pipeline.process(frame(250), NOW, gated, 0)!!.size)
            assertTrue(pipeline.isSpeaking)
            assertTrue(encoded.all { it.all { value -> value.toInt() == 500 } })
        }
    }

    @Test fun outputGenerationChangeDropsOldHalfFrameAndOnset() {
        pipeline().use { pipeline ->
            val gated = options.copy(volumeGate = true)
            repeat(2) { pipeline.process(frame(1000), NOW, gated, 0) }
            pipeline.process(frame(10000), NOW, gated, 0)
            reference.discard()
            repeat(4) { pipeline.process(frame(2000), NOW, gated, 0) }
            assertEquals(2, encoded.size)
            assertTrue(encoded.all { block -> block.all { it.toInt() == 2000 } })
        }
    }

    @Test fun revisionsAndNativeFallbackNeverCombineOldPartialSamplesWithNewBaselineAudio() {
        pipeline().use { pipeline ->
            pipeline.process(frame(10000), NOW, options, 0)
            assertNull(pipeline.process(frame(1000), NOW, options, 1))
            pipeline.process(frame(2000), NOW, options, 1)
            assertArrayEquals(frame(1000) + frame(2000), encoded.single())
            pipeline.process(frame(10000), NOW, options, 1)
            engine.fail = true
            assertNull(pipeline.process(frame(3000), NOW, options, 1))
            pipeline.process(frame(4000), NOW, options, 1)
            assertArrayEquals(frame(3000) + frame(4000), encoded.last())
        }
    }

    @Test fun changingAecOrNsRequiresAnExplicitRebuildAndEncoderFailurePropagates() {
        pipeline().use { pipeline ->
            assertThrows(IllegalArgumentException::class.java) {
                pipeline.process(frame(1000), NOW, options.copy(settings = options.settings.copy(noiseSuppression = true)), 0)
            }
        }
        pipeline { error("Synthetic encoding failure") }.use { pipeline ->
            pipeline.process(frame(1000), NOW, options, 0)
            assertThrows(IllegalStateException::class.java) { pipeline.process(frame(1000), NOW, options, 0) }
        }
    }

    private fun pipeline(
        encode: (ShortArray) -> ByteArray = {
            encoded += it.copyOf()
            byteArrayOf(1)
        },
    ): ApmCapturePipeline {
        val worker =
            ApmExperimentWorker(
                reference,
                ApmExperimentPolicy(ApmRoute.BUILTIN, true, true),
                { engine },
                { { pcm -> FloatArray(480) { pcm[it] / 32768f } } },
                {},
                { NOW },
            )
        return ApmCapturePipeline(worker, options.settings, encode)
    }

    private fun frame(value: Int) = ShortArray(480) { value.toShort() }

    private class Engine : ApmProcessor {
        var fail = false

        override fun reverse(pcm: FloatArray) = Unit

        override fun capture(
            pcm: FloatArray,
            delayMs: Int,
        ): FloatArray {
            if (fail) error("Synthetic native failure")
            return pcm
        }

        override fun reset() = Unit

        override fun close() = Unit
    }

    private companion object {
        const val NOW = 100_000_000L
    }
}
