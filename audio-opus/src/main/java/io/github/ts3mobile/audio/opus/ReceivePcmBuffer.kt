package io.github.ts3mobile.audio.opus

internal class ReceivePcmBuffer(private val stats: ReceiveStats) {
    private data class Chunk(val pcm: ShortArray, val lease: ReceiveMediaBudget.Lease, val transferred: Long, var offset: Int = 0)

    private val chunks = ArrayDeque<Chunk>()
    var available = 0
        private set
    var oldestReadArrival: Long? = null
        private set

    fun add(
        pcm: ShortArray,
        lease: ReceiveMediaBudget.Lease,
        transferred: Long,
    ) {
        require(pcm.size == lease.remaining)
        require(pcm.size <= ReceivePolicy.PCM_FIFO_CAPACITY_SAMPLES)
        while (chunks.isNotEmpty() && available + pcm.size > ReceivePolicy.PCM_FIFO_CAPACITY_SAMPLES) {
            val oldest = chunks.removeFirst()
            val dropped = oldest.pcm.size - oldest.offset
            available -= dropped
            oldest.lease.release()
            stats.overflowDroppedPcmSamples.addAndGet(dropped.toLong())
        }
        chunks.addLast(Chunk(pcm, lease, transferred))
        available += pcm.size
    }

    fun read(
        maxSamples: Int,
        now: Long,
    ): ShortArray {
        require(maxSamples >= 0)
        oldestReadArrival = null
        val output = ShortArray(minOf(maxSamples, available))
        var offset = 0
        while (offset < output.size && chunks.isNotEmpty()) {
            val first = chunks.first()
            if (!first.lease.valid) {
                available -= first.pcm.size - first.offset
                chunks.removeFirst()
                continue
            }
            val copied = minOf(output.size - offset, first.pcm.size - first.offset)
            first.pcm.copyInto(output, offset, first.offset, first.offset + copied)
            oldestReadArrival = minOf(oldestReadArrival ?: first.lease.arrivalNanos, first.lease.arrivalNanos)
            first.offset += copied
            first.lease.consume(copied)
            available -= copied
            offset += copied
            stats.maximum(stats.maxTalkerAgeNanos, now - first.transferred)
            stats.maximum(stats.maxCombinedWaitNanos, now - first.lease.arrivalNanos)
            if (first.offset == first.pcm.size) chunks.removeFirst()
        }
        return if (offset == output.size) output else output.copyOf(offset)
    }

    fun clear(): Int {
        val dropped = available
        stats.count(AudioReceiveCounter.PCM_RESET_DROPPED, dropped.toLong())
        chunks.forEach { it.lease.release() }
        chunks.clear()
        available = 0
        return dropped
    }
}
