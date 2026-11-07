package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class MicrophoneLevelSignalTest {
    private val continuous = MicrophoneProcessingOptions(MicrophoneSettings(noiseSuppression = false), volumeGate = false)

    private fun frame(amplitude: Int) = ShortArray(960) { amplitude.toShort() }

    @Test fun silenceAndUnityUseTheExistingPcm16RmsDefinition() {
        val processor = MicrophoneFrameProcessor()
        processor.process(frame(0), continuous, measureLevel = true)
        assertEquals(-96.0, processor.levelDb, 0.0)
        processor.process(frame(1000), continuous, measureLevel = true)
        assertEquals(20 * log10(1000.0 / 32768), processor.levelDb, 0.00001)
        processor.process(frame(1000), continuous)
        assertTrue(processor.levelDb.isNaN())
        processor.reset()
        assertTrue(processor.levelDb.isNaN())
    }

    @Test fun highGainMeasuresBeforeLimiterButSaturatesLikeTheGate() {
        val processor = MicrophoneFrameProcessor()
        val boosted = continuous.copy(settings = continuous.settings.copy(volumePercent = 200))
        repeat(3) { processor.process(frame(30000), boosted, measureLevel = true) }
        assertEquals(VolumeGate.rmsDb(FloatArray(960) { 60000f }), processor.levelDb, 0.0)
        val output = processor.process(frame(30000), boosted, measureLevel = true).single()
        assertTrue(VolumeGate.rmsDb(FloatArray(960) { output[it].toFloat() }) < processor.levelDb)
    }

    @Test fun displayedGateStateUsesActualAttackHysteresisAndHold() {
        val processor = MicrophoneFrameProcessor()
        val gated = continuous.copy(volumeGate = true)
        processor.process(frame(368), gated)
        assertTrue(processor.levelDb > -40)
        assertFalse(processor.isSpeaking)
        processor.process(frame(368), gated)
        assertTrue(processor.isSpeaking)
        repeat(30) { processor.process(frame(200), gated) }
        assertTrue(processor.levelDb < -40 && processor.levelDb > -46)
        assertTrue(processor.isSpeaking)
        repeat(14) { processor.process(frame(0), gated) }
        assertTrue(processor.isSpeaking)
        processor.process(frame(0), gated)
        assertFalse(processor.isSpeaking)
    }

    @Test fun observingCannotChangePcmOrGateOnsetOutput() {
        listOf(continuous, continuous.copy(volumeGate = true)).forEach { options ->
            val observed = MicrophoneFrameProcessor()
            val unobserved = MicrophoneFrameProcessor()
            val input = listOf(0, 250, 368, 368, 1000, 32767, 0, 0, 0) + List(20) { 0 }
            input.forEach { amplitude ->
                val expected = unobserved.process(frame(amplitude), options)
                val actual = observed.process(frame(amplitude), options, measureLevel = true)
                assertEquals(expected.size, actual.size)
                expected.zip(actual).forEach { (before, after) -> assertArrayEquals(before, after) }
                assertEquals(unobserved.isSpeaking, observed.isSpeaking)
            }
        }
    }

    @Test fun pipelineMeasurementIsPostDenoiseAndOnlyChangesOnCompleteBlocks() {
        val pipeline =
            MicrophoneCapturePipeline(
                denoise = { pcm ->
                    pcm.fill(1000)
                    0f
                },
                encode = { byteArrayOf(1) },
                stats = CaptureSessionStats(),
                onConfiguration = {},
            )
        val options = continuous.copy(settings = continuous.settings.copy(noiseSuppression = true))
        assertEquals(null, pipeline.process(ShortArray(480), options, 0, measureLevel = true))
        assertTrue(pipeline.levelDb.isNaN())
        pipeline.process(ShortArray(480), options, 0, measureLevel = true)
        assertEquals(20 * log10(1000.0 / 32768), pipeline.levelDb, 0.00001)
    }
}
