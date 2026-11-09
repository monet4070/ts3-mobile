package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticipantGainMixerTest {
    @Test fun outputHistoryResetReleasesOldLimiterButPreservesAnotherParticipantsGainRamp() {
        val mixer = ParticipantGainMixer()
        val b = ParticipantGainMixer.Input(99, ShortArray(480) { 1000 }, 1f)
        mixer.mix(listOf(b), 480)
        val loud = (1..8).map { ParticipantGainMixer.Input(it, ShortArray(480) { 30000 }, 1f) }
        mixer.mix(loud + b.copy(gain = 2f), 480)
        loud.forEach { mixer.forget(it.clientId) }
        mixer.resetOutputHistory()
        val resumed = mixer.mix(listOf(b.copy(gain = 2f)), 480)
        assertEquals(1501, resumed.first().toInt())
        assertEquals(2000, resumed.last().toInt())
    }

    @Test fun emptyTicksAdvanceReleaseExactlyLikeSilentSamplesWithoutResettingGain() {
        val emptyMixer = ParticipantGainMixer()
        val silentMixer = ParticipantGainMixer()
        val loud = (1..8).map { ParticipantGainMixer.Input(it, ShortArray(480) { 30000 }, 1f) }
        emptyMixer.mix(loud, 480)
        silentMixer.mix(loud, 480)
        repeat(3) {
            assertEquals(0, emptyMixer.mix(emptyList(), 480).size)
            silentMixer.mix(listOf(ParticipantGainMixer.Input(1, ShortArray(480), 1f)), 480)
        }
        val resumed = listOf(ParticipantGainMixer.Input(1, ShortArray(480) { 1000 }, 1f))
        val actual = emptyMixer.mix(resumed, 480)
        assertArrayEquals(silentMixer.mix(resumed, 480), actual)
        assertTrue(actual.first() < 1000)
    }

    @Test
    fun silentNonemptyInputDoesNotChangeAnotherTalker() {
        val output =
            ParticipantGainMixer().mix(
                listOf(
                    ParticipantGainMixer.Input(1, shortArrayOf(1_000), 1f),
                    ParticipantGainMixer.Input(2, shortArrayOf(0), 1f),
                ),
                1,
            )
        assertArrayEquals(shortArrayOf(1_000), output)
    }

    @Test
    fun shortTailAppearsExactlyOnceAndTheRestOfTheTickIsZero() {
        listOf(120, 240, 479, 480).forEach { tailSize ->
            val tail = ShortArray(tailSize) { (it + 1).toShort() }
            val mixer = ParticipantGainMixer()
            val output = mixer.mix(listOf(ParticipantGainMixer.Input(1, tail, 1f)), 480)
            assertArrayEquals(tail, output.copyOfRange(0, tailSize))
            assertArrayEquals(ShortArray(480 - tailSize), output.copyOfRange(tailSize, 480))
            assertArrayEquals(ShortArray(0), mixer.mix(emptyList(), 480))
        }
    }

    @Test
    fun shortInputDoesNotAttenuateLongInputAfterItsTailEnds() {
        listOf(120, 240, 479, 480).forEach { tailSize ->
            val output =
                ParticipantGainMixer().mix(
                    listOf(
                        ParticipantGainMixer.Input(1, ShortArray(tailSize) { 1_000 }, 1f),
                        ParticipantGainMixer.Input(2, ShortArray(480) { 1_000 }, 1f),
                    ),
                    480,
                )
            assertArrayEquals(ShortArray(tailSize) { 2_000 }, output.copyOfRange(0, tailSize))
            assertArrayEquals(ShortArray(480 - tailSize) { 1_000 }, output.copyOfRange(tailSize, 480))
        }
    }

    @Test
    fun appliesIndependentGainAndIgnoresMutedInputs() {
        val output =
            ParticipantGainMixer().mix(
                inputs =
                    listOf(
                        ParticipantGainMixer.Input(1, shortArrayOf(1_000, -1_000), 1.5f),
                        ParticipantGainMixer.Input(2, shortArrayOf(20_000, 20_000), 0f),
                    ),
                sampleCount = 2,
            )

        assertArrayEquals(shortArrayOf(1_500, -1_500), output)
    }

    @Test
    fun sumsMultipleTalkersAtUnityAndLimitsBoostedSamples() {
        val normalized =
            ParticipantGainMixer().mix(
                inputs =
                    listOf(
                        ParticipantGainMixer.Input(1, shortArrayOf(10_000), 1f),
                        ParticipantGainMixer.Input(2, shortArrayOf(10_000), 1f),
                    ),
                sampleCount = 1,
            )
        val clipped =
            ParticipantGainMixer().mix(
                inputs = listOf(ParticipantGainMixer.Input(1, shortArrayOf(30_000), 2f)),
                sampleCount = 1,
            )

        assertEquals(20_000, normalized.single().toInt())
        assertEquals(PcmLimiter.CEILING.toInt(), clipped.single().toInt())
    }

    @Test fun participantGainRampsAcrossTicksAndMuteDoesNotLeaveBufferedSamples() {
        val mixer = ParticipantGainMixer()

        fun mix(gain: Float) = mixer.mix(listOf(ParticipantGainMixer.Input(1, ShortArray(480) { 1000 }, gain)), 480)
        assertArrayEquals(ShortArray(480) { 1000 }, mix(1f))
        val first = mix(2f)
        val second = mix(2f)
        assertEquals(1001, first.first().toInt())
        assertEquals(1500.0, first.last().toDouble(), 1.0)
        assertEquals(2000, second.last().toInt())
        assertTrue((first + second).toList().zipWithNext().all { (before, after) -> after - before in 0..2 })
        assertEquals(0, mix(0f).size)
        assertTrue(mix(1f).first() <= 2)
        mixer.reset()
        assertArrayEquals(ShortArray(480) { 1000 }, mix(1f))
    }

    @Test fun joiningAndLeavingQuietTalkerDoesNotModulateExistingWaveform() {
        val mixer = ParticipantGainMixer()
        val background = ParticipantGainMixer.Input(1, ShortArray(480) { 1000 }, 1f)
        assertArrayEquals(background.samples, mixer.mix(listOf(background), 480))
        val quietJoin = ParticipantGainMixer.Input(2, ShortArray(480) { it.toShort() }, 1f)
        assertArrayEquals(ShortArray(480) { (1000 + it).toShort() }, mixer.mix(listOf(background, quietJoin), 480))
        val quietLeave = quietJoin.copy(samples = ShortArray(480) { (479 - it).toShort() })
        assertArrayEquals(ShortArray(480) { (1479 - it).toShort() }, mixer.mix(listOf(background, quietLeave), 480))
        assertArrayEquals(background.samples, mixer.mix(listOf(background), 480))
    }

    @Test fun correlatedEightTalkerMixIsBoundedAndSessionResetClearsLimiter() {
        val mixer = ParticipantGainMixer()
        val loud = (1..8).map { ParticipantGainMixer.Input(it, ShortArray(480) { 32767 }, 2f) }
        val output = mixer.mix(loud, 480)
        assertTrue(output.all { it.toInt() in 1..PcmLimiter.CEILING.toInt() })
        mixer.reset()
        val quiet = ParticipantGainMixer.Input(1, ShortArray(120) { 1000 }, 1f)
        assertArrayEquals(ShortArray(120) { 1000 } + ShortArray(360), mixer.mix(listOf(quiet), 480))
    }
}
