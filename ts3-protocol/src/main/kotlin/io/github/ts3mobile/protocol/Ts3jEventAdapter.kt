package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.event.ChannelCreateEvent
import com.github.manevolent.ts3j.event.ChannelDeletedEvent
import com.github.manevolent.ts3j.event.ChannelEditedEvent
import com.github.manevolent.ts3j.event.ChannelListEvent
import com.github.manevolent.ts3j.event.ChannelMovedEvent
import com.github.manevolent.ts3j.event.ClientJoinEvent
import com.github.manevolent.ts3j.event.ClientLeaveEvent
import com.github.manevolent.ts3j.event.ClientMovedEvent
import com.github.manevolent.ts3j.event.ClientUpdatedEvent
import com.github.manevolent.ts3j.event.DisconnectedEvent
import com.github.manevolent.ts3j.event.TS3Listener

/**
 * Adapts ts3j server events into snapshot mutations, gated by the session
 * generation so a stale adapter cannot write into a replacement session's
 * store. The disconnect path is delegated to [onDisconnected] because the
 * session client owns the socket and listener lifecycle. Extracted from
 * Ts3jSessionClient so event mapping is reviewable in isolation.
 */
internal class Ts3jEventAdapter(
    private val token: Long,
    private val generation: SessionGenerationGate,
    private val snapshotStore: SessionSnapshotStore,
    private val client: Ts3jClientSocket,
    private val publishSnapshot: () -> Unit,
    private val handleDisconnected: (DisconnectedEvent) -> Unit,
) : TS3Listener {
    override fun onDisconnected(event: DisconnectedEvent) = handleDisconnected(event)

    override fun onChannelList(event: ChannelListEvent) {
        applySnapshotMutation {
            snapshotStore.putChannel(event.map.toTs3Channel(event.channelId))
        }?.let { publishSnapshotWhenConnected() }
    }

    override fun onClientJoin(event: ClientJoinEvent) {
        if (event.clientType == REGULAR_CLIENT_TYPE) {
            applySnapshotMutation {
                snapshotStore.putParticipant(event.toParticipant())
            }?.let { publishSnapshotWhenConnected() }
        }
    }

    override fun onClientLeave(event: ClientLeaveEvent) {
        applySnapshotMutation {
            snapshotStore.removeParticipant(event.clientId)
        }?.let { publishSnapshotWhenConnected() }
    }

    override fun onClientMoved(event: ClientMovedEvent) {
        applySnapshotMutation {
            snapshotStore.updateParticipant(event.clientId) { it.copy(channelId = event.targetChannelId) }
        }?.let { publishSnapshotWhenConnected() }
    }

    override fun onClientChanged(event: ClientUpdatedEvent) {
        applySnapshotMutation {
            snapshotStore.updateParticipant(event.clientId) { it.withTeamSpeakUpdates(event.map) }
        }?.let { publishSnapshotWhenConnected() }
    }

    override fun onChannelCreate(event: ChannelCreateEvent) {
        applySnapshotMutation {
            snapshotStore.putChannel(event.map.toTs3Channel(event.channelId))
        }?.let { publishSnapshotWhenConnected() }
    }

    override fun onChannelDeleted(event: ChannelDeletedEvent) {
        applySnapshotMutation {
            snapshotStore.removeChannel(event.channelId)
        }?.let { publishSnapshotWhenConnected() }
    }

    override fun onChannelEdit(event: ChannelEditedEvent) {
        applySnapshotMutation {
            snapshotStore.updateChannel(event.channelId) { it.withTeamSpeakUpdates(event.map) }
        }?.let { publishSnapshotWhenConnected() }
    }

    override fun onChannelMoved(event: ChannelMovedEvent) {
        applySnapshotMutation {
            snapshotStore.updateChannel(event.channelId) {
                it.copy(parentId = event.channelParentId, orderAfterId = event.channelOrder)
            }
        }?.let { publishSnapshotWhenConnected() }
    }

    private fun publishSnapshotWhenConnected() {
        if (client.isConnected) publishSnapshot()
    }

    private fun applySnapshotMutation(mutation: () -> Unit): Unit? =
        generation.withCurrent(token) {
            mutation()
        }

    private fun ClientJoinEvent.toParticipant() =
        Ts3Participant(
            id = clientId,
            channelId = clientTargetId,
            nickname = clientNickname,
            isTalking = isClientTalking,
            isInputMuted = isClientInputMuted,
            isOutputMuted = isClientOutputMuted,
            uniqueIdentifier = uniqueClientIdentifier,
        )

    private companion object {
        const val REGULAR_CLIENT_TYPE = 0
    }
}
