package io.github.ts3mobile.app.bookmarks

internal data class BookmarkDraft(
    val entry: ServerBookmark,
    val port: String = entry.port.toString(),
    val channel: String = entry.defaultChannelId?.toString().orEmpty(),
    val selection: BookmarkChannelSelection? = null,
    val clearedPasswords: Boolean = false,
) {
    fun withEndpoint(
        host: String = entry.host,
        port: String = this.port,
    ): BookmarkDraft {
        val edited = copy(entry = entry.copy(host = host), port = port)
        val previousPort = this.port.toIntOrNull()
        val samePort = port == this.port || (previousPort != null && previousPort == port.toIntOrNull())
        if (sameBookmarkHost(entry.host, host) && samePort) return edited
        return edited.copy(
            entry = edited.entry.copy(password = "", channelPassword = ""),
            channel = "",
            selection = null,
            clearedPasswords = clearedPasswords || entry.password.isNotEmpty() || entry.channelPassword.isNotEmpty(),
        )
    }

    fun withChannel(
        channel: String,
        selection: BookmarkChannelSelection? = this.selection,
    ): BookmarkDraft {
        val channelId = channel.channelIdForSave()
        val changed = channelId != this.channel.channelIdForSave() || (channelId == null && selection == null && this.selection != null)
        return copy(
            entry = if (changed) entry.copy(channelPassword = "") else entry,
            channel = channel,
            selection = selection?.takeIf { it.channelId == channelId },
            clearedPasswords = clearedPasswords || (changed && entry.channelPassword.isNotEmpty()),
        )
    }

    fun withPasswordStorage(rememberPasswords: Boolean): BookmarkDraft =
        copy(
            entry = entry.copy(rememberPasswords = rememberPasswords).forStorage(),
            clearedPasswords = clearedPasswords && rememberPasswords,
        )

    fun toBookmark(): ServerBookmark = entry.copy(port = port.toIntOrNull() ?: 0, defaultChannelId = channel.channelIdForSave())
}

private fun String.channelIdForSave(): Int? = if (isBlank()) null else toIntOrNull() ?: 0
