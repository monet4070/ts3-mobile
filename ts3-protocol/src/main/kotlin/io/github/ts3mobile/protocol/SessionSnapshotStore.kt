package io.github.ts3mobile.protocol

internal class SessionSnapshotStore {
    private val lock = Any()
    private val channels = linkedMapOf<Int, Ts3Channel>()
    private val participants = linkedMapOf<Int, Ts3Participant>()

    fun clear() =
        synchronized(lock) {
            channels.clear()
            participants.clear()
        }

    fun putChannel(channel: Ts3Channel) =
        synchronized(lock) {
            channels[channel.id] = channel
        }

    fun updateChannel(
        id: Int,
        transform: (Ts3Channel) -> Ts3Channel,
    ) = synchronized(lock) {
        channels[id]?.let { channels[id] = transform(it) }
    }

    fun removeChannel(id: Int) =
        synchronized(lock) {
            channels.remove(id)
        }

    fun putParticipant(participant: Ts3Participant) =
        synchronized(lock) {
            participants[participant.id] = participant
        }

    fun updateParticipant(
        id: Int,
        transform: (Ts3Participant) -> Ts3Participant,
    ) = synchronized(lock) {
        participants[id]?.let { participants[id] = transform(it) }
    }

    fun removeParticipant(id: Int) =
        synchronized(lock) {
            participants.remove(id)
        }

    fun snapshot(): SessionSnapshot =
        synchronized(lock) {
            val countsByChannel = participants.values.groupingBy { it.channelId }.eachCount()
            SessionSnapshot(
                channels =
                    channels.values.map { channel ->
                        channel.copy(clientCount = countsByChannel[channel.id] ?: 0)
                    },
                participants = participants.values.sortedBy { it.nickname.lowercase() },
            )
        }
}
