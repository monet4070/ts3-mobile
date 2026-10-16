package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionFailureCategory
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClonesRecoveryPolicyTest {
    private val policy = ClonesRecoveryPolicy()

    @Test
    fun theWindowIsSixtySecondsAndTheBackoffIsOneTwoFourThenEightSeconds() {
        assertEquals(60_000L, policy.totalBudgetMs)
        assertEquals(1_000L, policy.delayForAttempt(1))
        assertEquals(2_000L, policy.delayForAttempt(2))
        assertEquals(4_000L, policy.delayForAttempt(3))
        assertEquals(8_000L, policy.delayForAttempt(4))
        assertEquals(8_000L, policy.delayForAttempt(5))
        assertEquals(8_000L, policy.delayForAttempt(50))
    }

    @Test
    fun onlyTheRefusalOrAnAlreadyRetryableFailureContinuesTheWindow() {
        assertTrue(policy.canContinueWithinWindow(clonesRefusal()))
        assertTrue(
            policy.canContinueWithinWindow(
                ConnectionStatus(ConnectionPhase.ERROR, "offline", retryable = true),
            ),
        )
        assertFalse(
            policy.canContinueWithinWindow(
                ConnectionStatus(ConnectionPhase.ERROR, "invalid password", retryable = false),
            ),
        )
        assertFalse(
            policy.canContinueWithinWindow(
                ConnectionStatus(ConnectionPhase.DISCONNECTED, "kicked", retryable = false),
            ),
        )
    }

    @Test
    fun thePolicyOnlyReadsTheCategoryTheProtocolAlreadyDecided() {
        // The app module never re-parses protocol text: the refusal is the closed
        // category the protocol derived from the observed failure. The text-based
        // classification itself is covered by the protocol test suite, because the
        // ts3j type is not on this module's compile classpath.
        assertTrue(policy.canContinueWithinWindow(ConnectionStatus(category = ConnectionFailureCategory.CLONES_LIMIT)))
        assertFalse(policy.canContinueWithinWindow(ConnectionStatus(category = ConnectionFailureCategory.UNCLASSIFIED)))
    }

    private fun clonesRefusal(): ConnectionStatus =
        ConnectionStatus(
            phase = ConnectionPhase.ERROR,
            detail = "refused",
            retryable = true,
            category = ConnectionFailureCategory.CLONES_LIMIT,
        )
}
