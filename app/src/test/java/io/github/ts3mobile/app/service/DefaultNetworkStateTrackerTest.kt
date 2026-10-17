package io.github.ts3mobile.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity, pending, duplicate-callback and stale-`onLost` rules behind
 * [DefaultNetworkMonitor], verified without the Android framework.
 */
class DefaultNetworkStateTrackerTest {
    @Test
    fun aFreshlyAdoptedNetworkIsPendingAndNotUsable() {
        val tracker = DefaultNetworkStateTracker()

        val state = tracker.onAvailable("network-1")

        assertTrue(state.isPending)
        assertFalse(state.isAvailable)
        assertEquals(1L, state.generation)
    }

    @Test
    fun aRepeatedCapabilityCallbackForTheSameNetworkDoesNotChangeTheState() {
        val tracker = DefaultNetworkStateTracker()
        tracker.onAvailable("network-1")
        val withInternet = tracker.onCapabilitiesChanged("network-1", true)

        val repeated = tracker.onCapabilitiesChanged("network-1", true)

        assertTrue(withInternet.isAvailable)
        assertFalse(withInternet.isPending)
        assertEquals(withInternet, repeated)
    }

    @Test
    fun aNewDefaultNetworkAdvancesTheGeneration() {
        val tracker = DefaultNetworkStateTracker()
        tracker.onAvailable("network-1")
        tracker.onCapabilitiesChanged("network-1", true)

        val switched = tracker.onAvailable("network-2")

        assertEquals(2L, switched.generation)
        assertTrue(switched.isPending)
        assertFalse(switched.isAvailable)
    }

    @Test
    fun aStaleLostCallbackForAReplacedNetworkIsIgnored() {
        val tracker = DefaultNetworkStateTracker()
        tracker.onAvailable("network-1")
        tracker.onCapabilitiesChanged("network-1", true)
        val switched = tracker.onAvailable("network-2")

        val afterStaleLost = tracker.onLost("network-1")

        assertEquals(switched, afterStaleLost)
        assertEquals(2L, afterStaleLost.generation)
    }

    @Test
    fun aBlockedDefaultNetworkIsNotAvailable() {
        val tracker = DefaultNetworkStateTracker()
        tracker.onAvailable("network-1")
        tracker.onCapabilitiesChanged("network-1", true)

        val blocked = tracker.onBlockedStatusChanged("network-1", true)

        assertFalse(blocked.isAvailable)
        assertTrue(blocked.isBlocked)

        val unblocked = tracker.onBlockedStatusChanged("network-1", false)
        assertTrue(unblocked.isAvailable)
    }

    @Test
    fun aNetworkWithoutInternetIsNotAvailable() {
        val tracker = DefaultNetworkStateTracker()
        tracker.onAvailable("network-1")

        val state = tracker.onCapabilitiesChanged("network-1", false)

        assertFalse(state.isAvailable)
        assertFalse(state.isPending)
    }

    @Test
    fun losingTheCurrentNetworkKeepsTheGenerationUntilAReplacementAppears() {
        val tracker = DefaultNetworkStateTracker()
        tracker.onAvailable("network-1")
        tracker.onCapabilitiesChanged("network-1", true)

        val lost = tracker.onLost("network-1")

        assertFalse(lost.isAvailable)
        assertEquals(1L, lost.generation)

        val replacement = tracker.onAvailable("network-2")
        assertEquals(2L, replacement.generation)
    }

    @Test
    fun onUnavailableClearsTheIdentityWithoutAdvancingTheGeneration() {
        val tracker = DefaultNetworkStateTracker()
        tracker.onAvailable("network-1")
        tracker.onCapabilitiesChanged("network-1", true)

        val state = tracker.onUnavailable()

        assertFalse(state.isAvailable)
        assertEquals(null, state.networkKey)
        assertEquals(1L, state.generation)
    }
}
