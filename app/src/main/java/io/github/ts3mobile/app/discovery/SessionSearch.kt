package io.github.ts3mobile.app.discovery

import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3Channel
import io.github.ts3mobile.protocol.Ts3Participant

data class SessionSearchResults(val channels: List<Ts3Channel>, val participants: List<Ts3Participant>)

fun searchSession(
    snapshot: SessionSnapshot,
    query: String,
): SessionSearchResults {
    val term = query.take(128).trim()
    val channels = snapshot.channels.filter { it.name.contains(term, true) || it.topic?.contains(term, true) == true }.take(50)
    val participants = snapshot.participants.filter { it.nickname.contains(term, true) }.take(50)
    return SessionSearchResults(channels, participants)
}

fun resolveParticipantChannel(
    snapshot: SessionSnapshot,
    clientId: Int,
    expectedUid: String,
): Int? {
    if (expectedUid.isBlank()) return null
    return snapshot.participants.singleOrNull { it.id == clientId && it.uniqueIdentifier == expectedUid }
        ?.channelId?.takeIf { id -> snapshot.channels.any { it.id == id } }
}
