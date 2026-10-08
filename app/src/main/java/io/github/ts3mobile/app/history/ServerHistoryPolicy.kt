package io.github.ts3mobile.app.history

/**
 * Pure ordering rules for remembered servers: the most recent success comes
 * first, each host+port appears once, and the list stays bounded.
 */
object ServerHistoryPolicy {
    const val MAX_ENTRIES = 10

    fun remember(
        entries: List<ServerHistoryEntry>,
        entry: ServerHistoryEntry,
    ): List<ServerHistoryEntry> {
        if (!entry.isValid()) return entries
        val withoutDuplicate = entries.filterNot { it.matches(entry) }
        return (listOf(entry) + withoutDuplicate).take(MAX_ENTRIES)
    }

    fun remove(
        entries: List<ServerHistoryEntry>,
        entry: ServerHistoryEntry,
    ): List<ServerHistoryEntry> = entries.filterNot { it.matches(entry) }

    fun mostRecent(entries: List<ServerHistoryEntry>): ServerHistoryEntry? = entries.firstOrNull()
}
