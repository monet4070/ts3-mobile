package io.github.ts3mobile.audio.opus

/** Tickets exist only while a packet is being prepared, so client history cannot accumulate. */
internal class PlaybackAdmission {
    internal class Entry(var revision: Long = 0, var holders: Int = 0)

    internal class Ticket internal constructor(
        internal val clientId: Int,
        internal val entry: Entry,
        internal val global: Long,
        internal val revision: Long,
    )

    private var global = 0L
    private val entries = mutableMapOf<Int, Entry>()

    fun <T> withTicket(
        clientId: Int,
        prepare: (Ticket) -> T,
    ): T {
        val ticket =
            synchronized(this) {
                val entry = entries.getOrPut(clientId) { Entry() }
                entry.holders++
                Ticket(clientId, entry, global, entry.revision)
            }
        try {
            return prepare(ticket)
        } finally {
            synchronized(this) {
                if (--ticket.entry.holders == 0) entries.remove(clientId)
            }
        }
    }

    @Synchronized fun accepts(ticket: Ticket): Boolean =
        global == ticket.global && entries[ticket.clientId] === ticket.entry && ticket.entry.revision == ticket.revision

    @Synchronized fun invalidateAll() {
        global++
    }

    @Synchronized fun invalidateParticipants(clients: Set<Int>) {
        clients.forEach { entries[it]?.let { entry -> entry.revision++ } }
    }
}
