package io.github.ts3mobile.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun followsBackoffAndCapsAtLastDelay() {
        val policy = ReconnectPolicy()

        assertEquals(1_000L, policy.delayForAttempt(1))
        assertEquals(2_000L, policy.delayForAttempt(2))
        assertEquals(4_000L, policy.delayForAttempt(3))
        assertEquals(8_000L, policy.delayForAttempt(4))
        assertEquals(15_000L, policy.delayForAttempt(5))
        assertEquals(30_000L, policy.delayForAttempt(6))
        assertEquals(30_000L, policy.delayForAttempt(20))
    }

    @Test
    fun remainsBoundedAndMonotonicAcrossRepeatedReconnectCycles() {
        val policy = ReconnectPolicy()
        val delays = (1..100).map(policy::delayForAttempt)

        assertEquals(delays.sorted(), delays)
        assertEquals(30_000L, delays.last())
        assertEquals(95, delays.count { it == 30_000L })
    }
}
