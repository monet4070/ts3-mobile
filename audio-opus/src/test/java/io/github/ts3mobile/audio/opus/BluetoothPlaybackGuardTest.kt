package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothPlaybackGuardTest {
    private val guard = BluetoothPlaybackGuard()

    @Test fun requestedBluetoothBlocksSpeakerAndUnknownRoutesUntilBluetoothIsReady() {
        guard.select(7, true)
        assertEquals(PlaybackRouteDecision.WAIT_FOR_BLUETOOTH, guard.evaluate(null))
        assertEquals(PlaybackRouteDecision.WAIT_FOR_BLUETOOTH, guard.evaluate(AudioRouteKind.SPEAKER))
        assertEquals(PlaybackRouteDecision.ALLOW, guard.evaluate(AudioRouteKind.BLUETOOTH))
    }

    @Test fun bluetoothFallbackStaysBlockedAcrossTalkspurtsAndRepeatedStateUpdates() {
        guard.select(7, true)
        guard.evaluate(AudioRouteKind.BLUETOOTH)
        guard.select(7, true)
        assertEquals(PlaybackRouteDecision.WAIT_FOR_BLUETOOTH, guard.evaluate(null))
        assertEquals(PlaybackRouteDecision.MUTE, guard.evaluate(AudioRouteKind.SPEAKER))
        guard.acknowledgeSafetyMute()
        assertEquals(PlaybackRouteDecision.MUTE, guard.evaluate(AudioRouteKind.EARPIECE))
    }

    @Test fun systemSelectedBluetoothAlsoCannotSuddenlyFallBackToSpeaker() {
        guard.evaluate(AudioRouteKind.BLUETOOTH)
        assertEquals(PlaybackRouteDecision.MUTE, guard.evaluate(AudioRouteKind.SPEAKER))
        guard.acknowledgeSafetyMute()
        assertEquals(PlaybackRouteDecision.ALLOW, guard.evaluate(AudioRouteKind.SPEAKER))
    }

    @Test fun explicitSelectionAllowsTheChosenBuiltInRoute() {
        guard.select(7, true)
        guard.evaluate(AudioRouteKind.BLUETOOTH)
        guard.select(8, false)
        assertEquals(PlaybackRouteDecision.ALLOW, guard.evaluate(AudioRouteKind.BLUETOOTH))
        assertEquals(PlaybackRouteDecision.ALLOW, guard.evaluate(AudioRouteKind.SPEAKER))
    }

    @Test fun anOldOutputsBluetoothCallbackCannotOverrideANewSpeakerSelection() {
        guard.select(7, true)
        val oldRevision = guard.revision()
        guard.select(8, false)
        assertEquals(PlaybackRouteDecision.WAIT_FOR_BLUETOOTH, guard.evaluate(AudioRouteKind.BLUETOOTH, oldRevision))
        assertEquals(PlaybackRouteDecision.ALLOW, guard.evaluate(AudioRouteKind.SPEAKER))
    }

    @Test fun aFreshBluetoothAttemptWaitsWithoutReusingTheRetiredOutputsConfirmation() {
        guard.select(7, true)
        guard.evaluate(AudioRouteKind.BLUETOOTH)
        val retiredRevision = guard.revision()
        guard.beginBluetoothAttempt()
        assertEquals(PlaybackRouteDecision.WAIT_FOR_BLUETOOTH, guard.evaluate(AudioRouteKind.BLUETOOTH, retiredRevision))
        assertEquals(PlaybackRouteDecision.WAIT_FOR_BLUETOOTH, guard.evaluate(AudioRouteKind.EARPIECE))
        assertEquals(PlaybackRouteDecision.WAIT_FOR_BLUETOOTH, guard.evaluate(AudioRouteKind.SPEAKER))
        assertEquals(PlaybackRouteDecision.ALLOW, guard.evaluate(AudioRouteKind.BLUETOOTH))
        assertEquals(PlaybackRouteDecision.MUTE, guard.evaluate(AudioRouteKind.SPEAKER))
    }
}
