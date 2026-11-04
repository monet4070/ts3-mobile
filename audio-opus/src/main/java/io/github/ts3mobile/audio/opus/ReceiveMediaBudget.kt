package io.github.ts3mobile.audio.opus

internal class ReceiveMediaBudget(val stats: ReceiveStats = ReceiveStats()) {
    internal class ClientState(val token: Long) {
        var samples = 0
        var bytes = 0
        var packets = 0
        val leases = linkedSetOf<Lease>()
    }

    inner class Lease internal constructor(
        val clientId: Int,
        internal val state: ClientState,
        internal var remaining: Int,
        internal val bytes: Int,
        val arrivalNanos: Long,
        val transferredAt: Long,
    ) {
        val token: Long get() = state.token
        val valid: Boolean get() = synchronized(this@ReceiveMediaBudget) { clients[clientId] === state && remaining > 0 }

        fun consume(count: Int) =
            synchronized(this@ReceiveMediaBudget) {
                require(count in 0..remaining)
                if (count == 0) return@synchronized
                if (clients[clientId] !== state) return@synchronized
                remaining -= count
                state.samples -= count
                if (remaining == 0) {
                    state.leases.remove(this)
                    state.bytes -= bytes
                    state.packets--
                    totalBytes -= bytes
                }
            }

        fun release() = synchronized(this@ReceiveMediaBudget) { consume(remaining) }
    }

    private val clients = linkedMapOf<Int, ClientState>()
    private var serial = 0L
    private var totalBytes = 0

    @Synchronized fun reserve(
        clientId: Int,
        samples: Int,
        bytes: Int,
        arrival: Long,
        transferred: Long = arrival,
    ): Lease? {
        if (samples !in 1..ReceivePolicy.MAX_CLIENT_SAMPLES || bytes !in 0..ReceivePolicy.MAX_PACKET_BYTES) return null
        var state = clients[clientId]
        if (state != null && (
                state.samples + samples > ReceivePolicy.MAX_CLIENT_SAMPLES ||
                    state.bytes + bytes > ReceivePolicy.MAX_CLIENT_BYTES || state.packets >= ReceivePolicy.MAX_CLIENT_PACKETS
            )
        ) {
            invalidate(clientId)
            stats.budgetResets.incrementAndGet()
            state = null
        }
        while (totalBytes + bytes > ReceivePolicy.MAX_GLOBAL_BYTES ||
            (state == null && clients.size >= ReceivePolicy.MAX_TRACKED_CLIENTS)
        ) {
            val oldest = clients.minBy { (_, client) -> client.leases.minOfOrNull { it.arrivalNanos } ?: Long.MIN_VALUE }.key
            invalidate(oldest)
            stats.budgetResets.incrementAndGet()
            if (oldest == clientId) state = null
        }
        if (state == null) state = ClientState(++serial).also { clients[clientId] = it }
        state.samples += samples
        state.bytes += bytes
        state.packets++
        totalBytes += bytes
        return Lease(clientId, state, samples, bytes, arrival, transferred).also(state.leases::add)
    }

    @Synchronized fun conceal(
        previous: Lease,
        samples: Int,
        now: Long,
    ): Lease? {
        val state = clients[previous.clientId] ?: return null
        if (state !== previous.state || state.samples + samples > ReceivePolicy.MAX_CLIENT_SAMPLES ||
            state.packets >= ReceivePolicy.MAX_CLIENT_PACKETS
        ) {
            return null
        }
        state.samples += samples
        state.packets++
        return Lease(previous.clientId, state, samples, 0, previous.arrivalNanos, now).also(state.leases::add)
    }

    @Synchronized fun invalidate(clientId: Int) {
        val removed = clients.remove(clientId) ?: return
        totalBytes -= removed.bytes
    }

    @Synchronized fun clear() {
        clients.clear()
        totalBytes = 0
    }

    @Synchronized fun samples(clientId: Int): Int = clients[clientId]?.samples ?: 0

    @Synchronized fun generation(clientId: Int): Long? = clients[clientId]?.token
}
