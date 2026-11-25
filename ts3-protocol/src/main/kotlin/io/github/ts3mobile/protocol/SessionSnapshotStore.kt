package io.github.ts3mobile.protocol

internal class SessionSnapshotStore {
    private val lock = Any()
    private val channels = linkedMapOf<Int, Ts3Channel>()
    private val channelLifetimes = mutableMapOf<Int, Long>()
    private val participants = linkedMapOf<Int, Ts3Participant>()
    private val channelRevisions = mutableMapOf<Int, Long>()
    private var nextChannelRevision = 0L
    private var nextChannelLifetime = 0L

    fun clear() =
        synchronized(lock) {
            channels.clear()
            channelLifetimes.clear()
            participants.clear()
            channelRevisions.clear()
        }

    fun putChannel(channel: Ts3Channel) =
        synchronized(lock) {
            channels[channel.id] = channel
            if (channel.id !in channelLifetimes) channelLifetimes[channel.id] = ++nextChannelLifetime
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
            channelLifetimes.remove(id)
        }

    fun channelLifetime(id: Int): Long? = synchronized(lock) { channelLifetimes[id] }

    fun putParticipant(participant: Ts3Participant) =
        synchronized(lock) {
            putParticipantLocked(participant)
        }

    fun updateParticipant(
        id: Int,
        transform: (Ts3Participant) -> Ts3Participant,
    ) = synchronized(lock) {
        participants[id]?.let { putParticipantLocked(transform(it)) }
    }

    fun removeParticipant(id: Int) =
        synchronized(lock) {
            participants.remove(id)
            channelRevisions.remove(id)
        }

    fun channelRevision(
        clientId: Int,
        channelId: Int,
    ): Long? = synchronized(lock) { channelRevisions[clientId].takeIf { participants[clientId]?.channelId == channelId } }

    private fun putParticipantLocked(participant: Ts3Participant) {
        val previous = participants.put(participant.id, participant)
        if (previous?.channelId != participant.channelId) channelRevisions[participant.id] = ++nextChannelRevision
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
