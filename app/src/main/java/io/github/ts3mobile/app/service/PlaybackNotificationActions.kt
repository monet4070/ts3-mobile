package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionPhase
import java.util.UUID

internal sealed interface PlaybackNotificationAction {
    data object CheckRoute : PlaybackNotificationAction

    data class SetMuted(val id: String, val muted: Boolean) : PlaybackNotificationAction
}

internal class PlaybackNotificationActions {
    private data class Binding(val token: String, val expectedMuted: Boolean, val routeId: Int)

    private var binding: Binding? = null
    private var action: PlaybackNotificationAction.SetMuted? = null

    @Synchronized
    fun render(state: TeamSpeakServiceState): PlaybackNotificationAction? {
        val next = state.playbackBinding()
        if (next != binding) {
            binding = next
            action = next?.let { PlaybackNotificationAction.SetMuted(UUID.randomUUID().toString(), !it.expectedMuted) }
        }
        return if (state.status.phase == ConnectionPhase.CONNECTED && state.audioRouting.safetyMuted) {
            PlaybackNotificationAction.CheckRoute
        } else {
            action
        }
    }

    @Synchronized
    fun consume(
        id: String?,
        state: TeamSpeakServiceState,
        liveSafetyMuted: Boolean,
    ): Boolean? {
        val current = action ?: return null
        if (current.id != id || binding != state.playbackBinding() || liveSafetyMuted) return null
        binding = null
        action = null
        return current.muted
    }

    @Synchronized
    fun clear() {
        binding = null
        action = null
    }

    private fun TeamSpeakServiceState.playbackBinding(): Binding? =
        if (status.phase == ConnectionPhase.CONNECTED && !audioRouting.safetyMuted && !audioRouting.switching && !hasPlaybackFailure) {
            bookmarkConnection?.let { Binding(it.token, playbackMuted, audioRouting.selectedRouteId) }
        } else {
            null
        }
}
