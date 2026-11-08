package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ParticipantMuteIsolationTest {
    @Test fun pendingMixedBlockRetirementPreservesOtherParticipantsUnmixedMedia() = verify(MuteProbePoint.PENDING)

    @Test fun partialMixedWriteRetirementPreservesOtherParticipantsUnmixedMedia() = verify(MuteProbePoint.PARTIAL)

    @Test fun alreadyEnteredWriteRetirementPreservesOtherParticipantsUnmixedMedia() = verify(MuteProbePoint.BLOCKED)

    private fun verify(point: MuteProbePoint) {
        val trace = ParticipantMuteProbe.run(point)
        assertEquals(4320, trace.bUnmixedBeforeMute)
        assertEquals(trace.bUnmixedBeforeMute, trace.bUnmixedAfterMute)
        assertEquals(trace.bUnmixedBeforeUnmute, trace.bUnmixedAfterUnmute)
        assertFalse(trace.bDecoderClosedOnMute)
        assertFalse(trace.bDecoderRebuiltOnMute)
        assertFalse(trace.bDecoderRebuiltOnUnmute)
        assertEquals(3, trace.outputCreations)
    }
}
