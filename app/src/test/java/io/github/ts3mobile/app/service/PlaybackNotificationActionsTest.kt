package io.github.ts3mobile.app.service

import io.github.ts3mobile.app.bookmarks.BookmarkConnection
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackNotificationActionsTest {
    private val connected =
        TeamSpeakServiceState(
            status = ConnectionStatus(ConnectionPhase.CONNECTED),
            bookmarkConnection = BookmarkConnection("fixture-session", "voice.example.invalid", 9987, "Fixture"),
        )

    @Test fun eachCommandIsIdempotentAndCannotReturnAfterToggleCycle() {
        val controls = PlaybackNotificationActions()
        val mute = controls.render(connected) as PlaybackNotificationAction.SetMuted
        assertEquals(mute, controls.render(connected))
        assertEquals(true, controls.consume(mute.id, connected, false))
        assertNull(controls.consume(mute.id, connected, false))
        val muted = connected.copy(playbackMuted = true)
        val resume = controls.render(muted) as PlaybackNotificationAction.SetMuted
        assertEquals(false, controls.consume(resume.id, muted, false))
        assertNotEquals(mute, controls.render(connected))
        assertNull(controls.consume(mute.id, connected, false))
    }

    @Test fun staleActionsCannotChangeReplacementSessionOrRecoveredConnection() {
        val controls = PlaybackNotificationActions()
        val action = controls.render(connected) as PlaybackNotificationAction.SetMuted
        val switching = connected.copy(status = ConnectionStatus(ConnectionPhase.RECONNECTING))
        assertNull(controls.consume(action.id, switching, false))
        assertNull(controls.render(switching))
        assertNotEquals(action, controls.render(connected))
        assertNull(controls.consume(action.id, connected, false))
        val current = controls.render(connected) as PlaybackNotificationAction.SetMuted
        val another = connected.copy(bookmarkConnection = connected.bookmarkConnection!!.copy(token = "second-session"))
        assertNull(controls.consume(current.id, another, false))
        assertNotEquals(current, controls.render(another))
    }

    @Test fun headphoneProtectionOffersOnlyTheAppAndRejectsAlreadyPostedUnmute() {
        val controls = PlaybackNotificationActions()
        val muted = connected.copy(playbackMuted = true)
        val resume = controls.render(muted) as PlaybackNotificationAction.SetMuted
        val guarded = muted.copy(audioRouting = muted.audioRouting.copy(safetyMuted = true))
        assertNull(controls.consume(resume.id, muted, true))
        assertNull(controls.consume(resume.id, guarded, false))
        assertEquals(PlaybackNotificationAction.CheckRoute, controls.render(guarded))
        assertNotEquals(resume, controls.render(muted))
    }
}
