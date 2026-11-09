package io.github.ts3mobile.audio.opus

internal class VoiceReceiveQueue(
    private val inspect: (ByteArray) -> OpusPacketInfo = NativeOpusInfo::inspect,
    val budget: ReceiveMediaBudget = ReceiveMediaBudget(),
) {
    private val arrivals = linkedMapOf<Int, Long>()
    private val frames = ArrayDeque<QueuedFrame>()
    private val recent = linkedMapOf<Int, ArrayDeque<Int>>()
    val size: Int get() = synchronized(this) { frames.size }

    class PreparedFrame internal constructor(internal val queued: QueuedFrame)

    fun prepare(queued: QueuedFrame): PreparedFrame? {
        val payload = queued.frame.encodedData
        if (payload.isEmpty() || payload.size > ReceivePolicy.MAX_PACKET_BYTES) {
            budget.stats.invalidPackets.incrementAndGet()
            return null
        }
        val owned = payload.copyOf()
        val info =
            try {
                queued.info ?: inspect(owned)
            } catch (_: IllegalArgumentException) {
                budget.stats.invalidPackets.incrementAndGet()
                return null
            }
        return PreparedFrame(queued.copy(frame = queued.frame.copy(encodedData = owned), info = info))
    }

    fun offer(
        queued: QueuedFrame,
        now: Long = queued.arrivalNanos,
    ): Boolean = prepare(queued)?.let { offer(it, now) } ?: false

    @Synchronized fun offer(
        prepared: PreparedFrame,
        now: Long = prepared.queued.arrivalNanos,
    ): Boolean {
        val queued = prepared.queued
        arrivals.put(queued.frame.clientId, queued.arrivalNanos)?.let { previous ->
            val gap = (queued.arrivalNanos - previous).coerceAtLeast(0)
            budget.stats.peak(AudioReceivePeak.ARRIVAL_GAP_NS, gap)
            if (gap > 60_000_000L) budget.stats.count(AudioReceiveCounter.ARRIVAL_GAPS_OVER_60_MS)
        }
        if (arrivals.size > ReceivePolicy.MAX_TRACKED_CLIENTS) arrivals.remove(arrivals.keys.first())
        prune(now)
        if (isExpired(queued, now)) {
            budget.stats.stalePackets.incrementAndGet()
            budget.invalidate(queued.frame.clientId)
            return false
        }
        val payload = queued.frame.encodedData
        if (payload.isEmpty()) return false
        if (recent[queued.frame.clientId]?.contains(queued.frame.packetId and 0xffff) == true) {
            budget.stats.duplicates.incrementAndGet()
            return false
        }
        if (payload.size > ReceivePolicy.MAX_PACKET_BYTES) {
            budget.stats.invalidPackets.incrementAndGet()
            return false
        }
        val info = checkNotNull(queued.info)
        if (frames.size >= ReceivePolicy.MAX_INPUT_PACKETS) {
            budget.invalidate(frames.removeFirst().frame.clientId)
            budget.stats.budgetResets.incrementAndGet()
        }
        val lease = budget.reserve(queued.frame.clientId, info.samples, payload.size, queued.arrivalNanos)
        if (lease == null) {
            budget.stats.invalidPackets.incrementAndGet()
            return false
        }
        val history =
            recent.getOrPut(queued.frame.clientId) {
                if (recent.size >= ReceivePolicy.MAX_TRACKED_CLIENTS) recent.remove(recent.keys.first())
                ArrayDeque()
            }
        if (history.size == ReceivePolicy.DEDUPLICATION_HISTORY_PACKETS) history.removeFirst()
        history.addLast(queued.frame.packetId and 0xffff)
        frames.removeAll { it.lease?.valid != true }
        frames.addLast(queued.copy(lease = lease))
        budget.stats.count(AudioReceiveCounter.ACCEPTED)
        budget.stats.peak(AudioReceivePeak.INPUT_QUEUE_PACKETS, frames.size.toLong())
        return true
    }

    @Synchronized fun poll(now: Long): QueuedFrame? {
        prune(now)
        val queued = frames.removeFirstOrNull() ?: return null
        budget.stats.maximum(budget.stats.maxInputAgeNanos, now - queued.arrivalNanos)
        return queued.copy(transferredAt = now)
    }

    @Synchronized fun removeClient(clientId: Int) {
        budget.invalidate(clientId)
        recent.remove(clientId)
        arrivals.remove(clientId)
        frames.removeAll { it.frame.clientId == clientId }
    }

    @Synchronized fun clear() {
        budget.stats.count(AudioReceiveCounter.INPUT_CLEARED, frames.size.toLong())
        frames.clear()
        recent.clear()
        arrivals.clear()
        budget.clear()
    }

    private fun prune(now: Long) {
        val stale = frames.filter { isExpired(it, now) }
        budget.stats.stalePackets.addAndGet(stale.size.toLong())
        val clients = stale.map { it.frame.clientId }.toSet()
        clients.forEach(budget::invalidate)
        if (clients.isNotEmpty()) {
            val fresh = frames.filterNot { isExpired(it, now) }
            frames.clear()
            for (queued in fresh) {
                if (queued.frame.clientId in clients) {
                    val info = checkNotNull(queued.info)
                    val lease = budget.reserve(queued.frame.clientId, info.samples, queued.frame.encodedData.size, queued.arrivalNanos)
                    if (lease != null) {
                        frames.addLast(queued.copy(lease = lease))
                    } else {
                        budget.stats.invalidPackets.incrementAndGet()
                    }
                } else {
                    frames.addLast(queued)
                }
            }
        }
        frames.removeAll { it.lease?.valid != true }
    }

    private fun isExpired(
        queued: QueuedFrame,
        now: Long,
    ): Boolean = now - queued.arrivalNanos > ReceivePolicy.INPUT_AGE_NANOS
}
