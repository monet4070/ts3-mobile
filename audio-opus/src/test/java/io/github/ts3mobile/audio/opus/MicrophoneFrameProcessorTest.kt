package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneFrameProcessorTest {
    private val gated = MicrophoneProcessingOptions(MicrophoneSettings(noiseSuppression = false), volumeGate = true)

    @Test fun unityIsTransparentBoostIsLimitedAndMuteIsImmediate() {
        val processor = MicrophoneFrameProcessor()
        val continuous = gated.copy(volumeGate = false)
        assertArrayEquals(frame(1000), processor.process(frame(1000), continuous).single())
        val boosted = continuous.copy(settings = continuous.settings.copy(volumePercent = 200))
        val output = processor.process(frame(30000), boosted).single()
        assertTrue(output.all { it in 1..PcmLimiter.CEILING.toInt() })
        val muted = boosted.copy(settings = boosted.settings.copy(volumePercent = 0))
        assertArrayEquals(frame(0), processor.process(frame(30000), muted).single())
    }

    @Test fun gainChangesRampAcrossOneBlockWithoutModifyingInput() {
        val processor = MicrophoneFrameProcessor()
        val continuous = gated.copy(volumeGate = false)
        processor.process(frame(1000), continuous)
        val input = frame(1000)
        val changed = continuous.copy(settings = continuous.settings.copy(volumePercent = 200))
        val ramp = processor.process(input, changed).single()
        assertEquals(1001, ramp.first().toInt())
        assertEquals(2000, ramp.last().toInt())
        assertArrayEquals(frame(1000), input)
        assertArrayEquals(frame(2000), processor.process(input, changed).single())
    }

    @Test fun gateReadsPreLimiterBranchEvenDuringHeavyAttenuation() {
        val processor = MicrophoneFrameProcessor()
        val options = gated.copy(settings = gated.settings.copy(volumePercent = 200, gateThresholdDb = -15))
        assertTrue(processor.process(frame(32767), options).isEmpty())
        val onset = processor.process(frame(4000), options)
        assertEquals(2, onset.size)
        assertTrue(processor.isSpeaking)
        assertTrue(VolumeGate.rmsDb(onset.last().map { it.toFloat() }.toFloatArray()) < -15)
    }

    @Test fun boundedOnsetContainsLatestFramesInOrderWithoutAliasingInput() {
        val processor = MicrophoneFrameProcessor()
        val reused = ShortArray(960)
        repeat(20) { index ->
            reused.fill(index.toShort())
            assertTrue(processor.process(reused, gated).isEmpty())
        }
        assertTrue(processor.process(frame(1000), gated).isEmpty())
        val onset = processor.process(frame(2000), gated)
        assertEquals(listOf(16, 17, 18, 19, 1000, 2000), onset.map { it[0].toInt() })
        assertTrue(processor.isSpeaking)
    }

    @Test fun silenceDoesNotEncodeButContinuousModeDoes() {
        val processor = MicrophoneFrameProcessor()
        repeat(1000) { assertTrue(processor.process(frame(0), gated).isEmpty()) }
        assertFalse(processor.isSpeaking)
        assertEquals(1, processor.process(frame(0), gated.copy(volumeGate = false)).size)
        assertTrue(processor.isSpeaking)
    }

    @Test fun gateUsesLevelAfterManualGain() {
        val processor = MicrophoneFrameProcessor()
        // 250/32768 is about -42.4 dBFS, below the default opening threshold.
        repeat(4) { assertTrue(processor.process(frame(250), gated).isEmpty()) }
        val louder = gated.copy(settings = gated.settings.copy(volumePercent = 200))
        assertTrue(processor.process(frame(250), louder).isEmpty())
        assertEquals(2, processor.process(frame(250), louder).size)
        assertTrue(processor.isSpeaking)
    }

    @Test fun changingSettingsDiscardsOldOnsetAndRequiresNewAttack() {
        val processor = MicrophoneFrameProcessor()
        processor.process(frame(1000), gated)
        val changed = gated.copy(settings = gated.settings.copy(gateThresholdDb = -30))
        assertTrue(processor.process(frame(2000), changed).isEmpty())
        assertEquals(listOf(2000, 3000), processor.process(frame(3000), changed).map { it[0].toInt() })
    }

    @Test fun closingIncludesFinalTailBlockThenStopsEmittingUntilNextAttack() {
        val processor = MicrophoneFrameProcessor()
        repeat(2) { processor.process(frame(1000), gated) }
        repeat(15) { assertEquals(1, processor.process(frame(0), gated).size) }
        assertFalse(processor.isSpeaking)
        assertTrue(processor.process(frame(0), gated).isEmpty())
    }

    @Test fun levelsOneDbAboveAndBelowThresholdAndMuteKeepGateSemantics() {
        val processor = MicrophoneFrameProcessor()
        repeat(20) { assertTrue(processor.process(frame(292), gated).isEmpty()) }
        assertTrue(processor.process(frame(368), gated).isEmpty())
        val onset = processor.process(frame(368), gated)
        assertEquals(6, onset.size)
        assertTrue(processor.isSpeaking)
        val muted = gated.copy(settings = gated.settings.copy(volumePercent = 0))
        repeat(20) { assertTrue(processor.process(frame(32767), muted).isEmpty()) }
        assertFalse(processor.isSpeaking)
        assertTrue(processor.process(frame(1000), gated).isEmpty())
        val reopened = processor.process(frame(1000), gated)
        assertEquals(2, reopened.size)
        assertTrue(reopened.first().first() <= 2)
    }

    private fun frame(value: Int) = ShortArray(960) { value.toShort() }
}
