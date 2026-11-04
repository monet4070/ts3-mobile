package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveJitterDelayTest {
    @Test fun discontinuityClearsTrainingWithoutDroppingRaisedTarget() {
        val delay = AdaptiveJitterDelay(ReceivePolicy())
        repeat(12) { delay.noteLate() }
        assertEquals(120, delay.targetMs)
        delay.discontinuity()
        delay.observe(100, 0, OpusPacketInfo(960))
        assertEquals(120, delay.targetMs)
    }

    @Test fun startsAtSixtyAndSettlesNoLowerThanFortyWithStableMedia() {
        val delay = AdaptiveJitterDelay(ReceivePolicy())
        assertEquals(60, delay.targetMs)
        repeat(1000) { delay.observe(it.toLong(), it * 20_000_000L, OpusPacketInfo(960)) }
        assertEquals(40, delay.targetMs)
    }

    @Test fun jitterAndRepeatedLatePacketsIncreaseTargetInTenMillisecondStepsOnly() {
        val delay = AdaptiveJitterDelay(ReceivePolicy())
        repeat(200) { index ->
            delay.observe(index.toLong(), index * 20_000_000L + (index % 2) * 60_000_000L, OpusPacketInfo(960))
            assertTrue(delay.targetMs in 40..120 && delay.targetMs % 10 == 0)
        }
        repeat(100) { delay.noteLate() }
        assertEquals(120, delay.targetMs)
    }

    @Test fun dtxSequenceLossAndChangingDurationsDoNotTrainAFalseJitterEstimate() {
        val delay = AdaptiveJitterDelay(ReceivePolicy())
        repeat(1000) { index ->
            delay.observe(index * 2L, index * 1_000_000L, OpusPacketInfo(if (index % 2 == 0) 120 else 5760))
            delay.observe(index * 2L + 1, index * 1_000_000L + 1, OpusPacketInfo(960, timingUncertain = true))
        }
        assertEquals(60, delay.targetMs)
    }

    @Test fun fixedSixtyModeIgnoresNetworkTrainingAndLateSignals() {
        val delay = AdaptiveJitterDelay(ReceivePolicy(adaptive = false))
        repeat(1000) {
            delay.observe(it.toLong(), it * 20_000_000L, OpusPacketInfo(960))
            delay.noteLate()
        }
        assertEquals(60, delay.targetMs)
    }
}
