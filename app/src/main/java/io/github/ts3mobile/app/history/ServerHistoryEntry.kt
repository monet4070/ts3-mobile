package io.github.ts3mobile.app.history

/**
 * A server endpoint that reached a real CONNECTED state. Only the host and port
 * are remembered; nicknames, passwords and identity material are never stored.
 */
data class ServerHistoryEntry(
    val host: String,
    val port: Int,
) {
    val label: String
        get() = if (':' in host && !host.startsWith('[')) "[$host]:$port" else "$host:$port"

    fun isValid(): Boolean = host.isNotBlank() && host.none { it.isWhitespace() } && port in 1..65535

    fun matches(other: ServerHistoryEntry): Boolean = port == other.port && host.equals(other.host, ignoreCase = true)
}
