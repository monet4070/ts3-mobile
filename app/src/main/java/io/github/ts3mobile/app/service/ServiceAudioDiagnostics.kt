package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.OpusAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Android service wiring stays separate from storage, format and the player lifecycle. */
internal class ServiceAudioDiagnostics(
    private val repository: AudioDiagnosticRepository,
    scope: CoroutineScope,
    state: MutableStateFlow<TeamSpeakServiceState>,
    network: StateFlow<DefaultNetworkState>,
    player: OpusAudioPlayer,
) : AutoCloseable {
    private val controller =
        AudioDiagnosticController(repository, player::takeReceiveDiagnostics, { audioDiagnosticContext(state.value, network.value) })

    init {
        player.setDiagnosticsObserver(controller)
        controller.start(scope)
        scope.launch { repository.status.collect { status -> state.update { it.copy(audioDiagnostics = status) } } }
        scope.launch {
            combine(
                state.map { audioDiagnosticContext(it, network.value) },
                network,
            ) { _, current -> audioDiagnosticContext(state.value, current) }
                .distinctUntilChanged().collect(controller::observe)
        }
    }

    fun sampleNow() = controller.sampleNow()

    fun export(): String {
        sampleNow()
        return repository.export()
    }

    fun mark(): Boolean {
        sampleNow()
        return repository.mark()
    }

    override fun close() = controller.serviceStopped()
}
