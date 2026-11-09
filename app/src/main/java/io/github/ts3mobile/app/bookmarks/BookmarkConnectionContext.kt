package io.github.ts3mobile.app.bookmarks

import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.Ts3Channel

/** No passwords; an opaque token distinguishes manual connections even to the same endpoint. */
data class BookmarkConnection(
    val token: String,
    val host: String,
    val port: Int,
    val nickname: String,
) {
    fun matches(entry: ServerBookmark): Boolean = port == entry.port && host.trim().equals(entry.host.trim(), ignoreCase = true)

    override fun toString(): String = "BookmarkConnection(<redacted>)"
}

data class BookmarkChannelSelection(val connectionToken: String, val channelId: Int?)

internal data class BookmarkConnectionContext(
    val connection: BookmarkConnection,
    val channels: List<Ts3Channel>,
    val currentChannelId: Int?,
) {
    fun accepts(
        entry: ServerBookmark,
        selection: BookmarkChannelSelection,
    ): Boolean =
        connection.token == selection.connectionToken && connection.matches(entry) && entry.defaultChannelId == selection.channelId &&
            (selection.channelId == null || channels.count { it.id == selection.channelId } == 1)
}

internal fun TeamSpeakServiceState.bookmarkContext(): BookmarkConnectionContext? =
    bookmarkConnection?.takeIf { status.phase == ConnectionPhase.CONNECTED }?.let {
        BookmarkConnectionContext(it, snapshot.channels, snapshot.currentChannelId)
    }

internal class BookmarkContextChanged : IllegalStateException("Bookmark connection context changed")

internal data class BookmarkChannelChoice(val id: Int, val path: String)

/** Iterative traversal bounds malformed/cyclic paths without depending on remote data order. */
internal fun bookmarkChannelChoices(
    channels: List<Ts3Channel>,
    query: String = "",
): List<BookmarkChannelChoice> {
    val byId = channels.groupBy { it.id }.filterValues { it.size == 1 }.mapValues { it.value.single() }
    val term = query.take(128).trim()
    return byId.values.asSequence().map { channel ->
        val names = ArrayDeque<String>()
        val seen = mutableSetOf<Int>()
        var next: Ts3Channel? = channel
        while (next != null && seen.add(next.id) && names.size < 32) {
            names.addFirst(next.name.take(128))
            next = byId[next.parentId]
        }
        if (next != null) names.addFirst("…")
        BookmarkChannelChoice(channel.id, names.joinToString(" / ") + " (#${channel.id})")
    }.filter { term.isEmpty() || it.path.contains(term, ignoreCase = true) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })
        .take(100).toList()
}
