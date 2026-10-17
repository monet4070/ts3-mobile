package io.github.ts3mobile.app.history

/**
 * Pure text codec for [ServerHistoryEntry] lists. Each entry is one line of
 * `host<TAB>port`, so IPv6 hosts (which contain colons) and domains round-trip
 * without ambiguity. Decoding is tolerant: malformed or out-of-range lines are
 * dropped, duplicates collapse to the first occurrence and the list is capped.
 */
object ServerHistoryCodec {
    private const val ENTRY_SEPARATOR = '\n'
    private const val FIELD_SEPARATOR = '\t'

    fun encode(entries: List<ServerHistoryEntry>): String =
        entries
            .filter { it.isValid() }
            .joinToString(ENTRY_SEPARATOR.toString()) { "${it.host}$FIELD_SEPARATOR${it.port}" }

    fun decode(payload: String?): List<ServerHistoryEntry> {
        if (payload.isNullOrEmpty()) return emptyList()
        val decoded = mutableListOf<ServerHistoryEntry>()
        for (line in payload.split(ENTRY_SEPARATOR)) {
            val entry = decodeLine(line) ?: continue
            if (decoded.none { it.matches(entry) }) decoded += entry
            if (decoded.size >= ServerHistoryPolicy.MAX_ENTRIES) break
        }
        return decoded
    }

    private fun decodeLine(line: String): ServerHistoryEntry? {
        val separator = line.indexOf(FIELD_SEPARATOR)
        if (separator <= 0) return null
        val host = line.substring(0, separator).trim()
        val port = line.substring(separator + 1).trim().toIntOrNull() ?: return null
        return ServerHistoryEntry(host = host, port = port).takeIf { it.isValid() }
    }
}
