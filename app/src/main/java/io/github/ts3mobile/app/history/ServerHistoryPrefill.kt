package io.github.ts3mobile.app.history

/**
 * Pure pre-fill rule for the connection form. Only the first successful
 * history load may seed the address and port, and only while the user has not
 * typed an address or port yet, so a late asynchronous load can never
 * overwrite input that is already on screen.
 */
object ServerHistoryPrefill {
    fun hostAndPort(
        entries: List<ServerHistoryEntry>,
        connectionFieldsEdited: Boolean,
    ): Pair<String, String>? {
        if (connectionFieldsEdited) return null
        val recent = ServerHistoryPolicy.mostRecent(entries) ?: return null
        return recent.host to recent.port.toString()
    }
}
