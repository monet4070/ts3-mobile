package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.PlaybackStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class PlaybackStatusController(
    private val state: MutableStateFlow<TeamSpeakServiceState>,
    private val recordFailure: () -> Unit,
    private val updateNotification: () -> Unit,
) {
    @Synchronized fun update(status: PlaybackStatus) {
        if (state.value.playbackStatus == status) return
        state.update { it.copy(playbackStatus = status) }
        if (status == PlaybackStatus.FAILED) recordFailure()
        updateNotification()
    }
}
