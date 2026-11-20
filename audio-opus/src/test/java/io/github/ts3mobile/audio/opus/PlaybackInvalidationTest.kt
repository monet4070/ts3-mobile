package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackInvalidationTest {
    @Test fun participantCommandsCoalesceAndStopOldWritesUntilApplied() {
        val invalidation = PlaybackInvalidation()
        assertTrue(invalidation.permitsWrite(0))
        invalidation.invalidateParticipants(setOf(1))
        invalidation.invalidateParticipants(setOf(2, 1))
        assertFalse(invalidation.permitsWrite(0))
        val command = checkNotNull(invalidation.take())
        assertFalse(command.global)
        assertEquals(setOf(1, 2), command.clients)
        assertTrue(invalidation.permitsWrite(command.revision))
        assertNull(invalidation.take())
    }

    @Test fun globalCommandsSupersedeParticipants() {
        val invalidation = PlaybackInvalidation()
        invalidation.invalidateParticipants(setOf(1))
        invalidation.invalidateAll()
        invalidation.invalidateParticipants(setOf(2))
        invalidation.invalidateAll()
        val command = checkNotNull(invalidation.take())
        assertTrue(command.global)
        assertTrue(command.clients.isEmpty())
        invalidation.invalidateParticipants(setOf(3))
        val next = checkNotNull(invalidation.take())
        assertFalse(next.global)
        assertEquals(setOf(3), next.clients)
    }

    @Test fun aNewCommandDuringWorkerApplicationPreventsThatWorkersWrite() {
        val invalidation = PlaybackInvalidation()
        invalidation.invalidateParticipants(setOf(1))
        val applying = checkNotNull(invalidation.take())
        invalidation.invalidateAll()
        assertFalse(invalidation.permitsWrite(applying.revision))
        val next = checkNotNull(invalidation.take())
        assertTrue(invalidation.permitsWrite(next.revision))
    }

    @Test fun latestProfileSurvivesACoalescedRouteChangeAndIsConsumedOnlyOnce() {
        val invalidation = PlaybackInvalidation()
        invalidation.invalidateAll(ReceiveDelayProfile.STABILITY) { assertFalse(invalidation.permitsWrite(0)) }
        invalidation.invalidateAll(ReceiveDelayProfile.BALANCED)
        invalidation.invalidateAll()
        invalidation.invalidateParticipants(setOf(3))
        assertFalse(invalidation.permitsWrite(0))
        assertEquals(ReceiveDelayProfile.BALANCED, checkNotNull(invalidation.take()).profile)

        invalidation.invalidateAll()
        assertNull(checkNotNull(invalidation.take()).profile)
    }

    @Test fun emptyParticipantChangeDoesNotRetireOutput() {
        val invalidation = PlaybackInvalidation()
        invalidation.invalidateParticipants(emptySet())
        assertTrue(invalidation.permitsWrite(0))
        assertNull(invalidation.take())
    }
}
