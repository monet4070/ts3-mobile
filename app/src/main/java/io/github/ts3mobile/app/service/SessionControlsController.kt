package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ChannelDetails
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ControlOutcome
import io.github.ts3mobile.protocol.ControlStatus
import io.github.ts3mobile.protocol.OwnPresence
import io.github.ts3mobile.protocol.Ts3SessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class PresenceSyncState(
    val away: Boolean = false,
    val awayMessage: String = "",
    val pending: Boolean = false,
    val confirmed: OwnPresence? = null,
    val outcome: ControlOutcome? = null,
) {
    override fun toString() = "PresenceSyncState(away=$away, pending=$pending, content=<redacted>)"
}

data class ChannelInfoState(
    val channelId: Int,
    val loading: Boolean = true,
    val details: ChannelDetails? = null,
    val outcome: ControlOutcome? = null,
)

internal fun desiredPresence(
    state: TeamSpeakServiceState,
    permissionGranted: Boolean,
): OwnPresence =
    OwnPresence(
        state.presence.away,
        if (state.presence.away) state.presence.awayMessage else "",
        state.microphoneMode == MicrophoneMode.OFF || !permissionGranted || state.microphoneError != null,
        state.playbackMuted,
    )

/** Session binding + request revisions prevent old responses from changing a replacement connection. */
internal class SessionControlsController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<TeamSpeakServiceState>,
    private val permissionGranted: () -> Boolean = { true },
) : AutoCloseable {
    private class Binding(val session: Ts3SessionClient)

    private data class Request(val binding: Binding?, val phase: ConnectionPhase, val desired: OwnPresence, val retry: Long)

    private val bindingLock = Any()
    private val binding = MutableStateFlow<Binding?>(null)
    private val retryRevision = MutableStateFlow(0L)
    private val microphonePermission = MutableStateFlow(permissionGranted())
    private val infoRevision = AtomicLong(0)
    private val watch: Job =
        scope.launch {
            combine(
                combine(state, microphonePermission) { current, allowed ->
                    current.status.phase to desiredPresence(current, allowed)
                }.distinctUntilChanged(),
                binding,
                retryRevision,
            ) { input, bound, retry ->
                Request(bound, input.first, input.second, retry)
            }.distinctUntilChanged().collectLatest { request ->
                if (request.binding == null || request.phase != ConnectionPhase.CONNECTED) {
                    state.update {
                        if (binding.value !== request.binding || it.status.phase != request.phase) {
                            it
                        } else {
                            it.copy(
                                presence = it.presence.copy(pending = false, confirmed = null, outcome = null),
                                channelInfo = null,
                            )
                        }
                    }
                    return@collectLatest
                }
                val valid = {
                    retryRevision.value == request.retry && binding.value === request.binding &&
                        state.value.status.phase == ConnectionPhase.CONNECTED &&
                        desiredPresence(
                            state.value,
                            permissionGranted(),
                        ) == request.desired
                }
                synchronized(bindingLock) {
                    state.update { if (valid()) it.copy(presence = it.presence.copy(pending = true, outcome = null)) else it }
                }
                var result: ControlOutcome
                var busyAttempts = 0
                try {
                    do {
                        result = request.binding.session.updatePresence(request.desired, 2000, valid)
                        if (result.status == ControlStatus.BUSY && busyAttempts++ < 5 && valid()) {
                            delay(200)
                        } else {
                            break
                        }
                    } while (true)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    result = ControlOutcome(ControlStatus.UNKNOWN)
                }
                synchronized(bindingLock) {
                    state.update {
                        if (!valid()) {
                            it
                        } else {
                            it.copy(
                                presence =
                                    it.presence.copy(
                                        pending = false,
                                        outcome = result,
                                        confirmed = if (result.status == ControlStatus.ACCEPTED) request.desired else it.presence.confirmed,
                                    ),
                            )
                        }
                    }
                }
            }
        }

    fun attach(session: Ts3SessionClient) {
        synchronized(bindingLock) {
            infoRevision.incrementAndGet()
            state.update { it.copy(channelInfo = null, presence = it.presence.copy(confirmed = null, outcome = null)) }
            binding.value = Binding(session)
        }
    }

    fun setAway(
        away: Boolean,
        message: String,
    ) {
        require(message.toByteArray(Charsets.UTF_8).size <= 256)
        state.update { it.copy(presence = it.presence.copy(away = away, awayMessage = if (away) message else "")) }
    }

    fun retryPresence() {
        retryRevision.update { it + 1 }
    }

    /** Permissions can change without a service-state emission (for example idle PTT). */
    fun refreshPermission() {
        microphonePermission.value = permissionGranted()
    }

    fun showChannelInfo(channelId: Int) {
        val (captured, revision) =
            synchronized(bindingLock) {
                val captured = binding.value ?: return
                if (state.value.status.phase != ConnectionPhase.CONNECTED ||
                    state.value.snapshot.channels.none { it.id == channelId }
                ) {
                    return
                }
                val revision = infoRevision.incrementAndGet()
                state.update { it.copy(channelInfo = ChannelInfoState(channelId)) }
                captured to revision
            }
        val valid = {
            binding.value === captured && infoRevision.get() == revision && state.value.status.phase == ConnectionPhase.CONNECTED &&
                state.value.snapshot.channels.any {
                    it.id == channelId
                }
        }
        scope.launch {
            val result =
                try {
                    captured.session.channelInfo(channelId, 2000, valid)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    io.github.ts3mobile.protocol.ChannelInfoResult(ControlOutcome(ControlStatus.UNKNOWN))
                }
            synchronized(bindingLock) {
                state.update {
                    if (valid()) {
                        it.copy(
                            channelInfo = ChannelInfoState(channelId, false, result.details, result.outcome),
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun dismissChannelInfo() {
        infoRevision.incrementAndGet()
        state.update { it.copy(channelInfo = null) }
    }

    override fun close() {
        binding.value = null
        infoRevision.incrementAndGet()
        watch.cancel()
    }
}
