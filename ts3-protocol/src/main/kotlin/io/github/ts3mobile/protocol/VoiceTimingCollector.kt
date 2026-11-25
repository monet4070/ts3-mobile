package io.github.ts3mobile.protocol

data class VoiceDelayWindow(val buckets: List<Long>) {
    val p50UpperUs: Long? get() = quantile(50)
    val p95UpperUs: Long? get() = quantile(95)

    private fun quantile(percentile: Int): Long? {
        val total = buckets.sum()
        if (total == 0L) return null
        val rank = (total * percentile + 99L) / 100L
        var seen = 0L
        for (index in buckets.indices) {
            seen += buckets[index]
            if (seen >= rank) return VoiceTimingCollector.delayUpperBoundsUs.getOrNull(index)
        }
        return null
    }
}

data class VoiceTimingWindow(
    val udpTimestampsAvailable: Boolean,
    val udpReadToCallback: VoiceDelayWindow,
    val encodedToUdpSent: VoiceDelayWindow,
    val emptyUplinkReads: Long,
    val captureOverflowFrames: Long,
    val sendGapsBelow10Ms: Long,
)

class VoiceTimingCollector {
    private var generation = 0L
    private var active = false
    private var udpAvailable = false
    private val incoming = DelayHistogram()
    private val outgoing = DelayHistogram()
    private var emptyReads = 0L
    private var bursts = 0L
    private var previousSend: Long? = null

    @Synchronized internal fun open(preciseUdpTimestamps: Boolean): Long {
        active = true
        udpAvailable = preciseUdpTimestamps
        incoming.clear()
        outgoing.clear()
        emptyReads = 0
        bursts = 0
        previousSend = null
        return ++generation
    }

    @Synchronized internal fun close(token: Long) {
        if (token == generation) active = false
    }

    @Synchronized internal fun received(
        token: Long,
        receivedAt: Long,
        callbackAt: Long,
    ) {
        if (accepts(token) && udpAvailable) incoming.add(callbackAt - receivedAt)
    }

    @Synchronized internal fun sent(
        token: Long,
        encodedAt: Long?,
        sentAt: Long,
    ) {
        if (!accepts(token) || !udpAvailable) return
        if (encodedAt != null) outgoing.add(sentAt - encodedAt)
        if (previousSend?.let { sentAt - it in 0 until 10_000_000L } == true) bursts++
        previousSend = sentAt
    }

    @Synchronized internal fun emptyRead(token: Long) {
        if (accepts(token)) emptyReads++
    }

    @Synchronized fun takeWindow(captureOverflowFrames: Long = 0): VoiceTimingWindow? {
        if (generation == 0L && captureOverflowFrames == 0L) return null
        return VoiceTimingWindow(udpAvailable, incoming.take(), outgoing.take(), emptyReads, captureOverflowFrames, bursts).also {
            emptyReads = 0
            bursts = 0
        }
    }

    private fun accepts(token: Long): Boolean = active && token == generation

    private class DelayHistogram {
        private val counts = LongArray(delayUpperBoundsUs.size + 1)

        fun add(nanos: Long) {
            if (nanos < 0) return
            val bucket = delayUpperBoundsUs.indexOfFirst { nanos <= it * 1_000L }
            val index = if (bucket == -1) counts.lastIndex else bucket
            if (counts[index] < MAX_BUCKET_COUNT) counts[index]++
        }

        fun clear() = counts.fill(0L)

        fun take(): VoiceDelayWindow = VoiceDelayWindow(counts.toList()).also { clear() }
    }

    companion object {
        const val MAX_BUCKET_COUNT = 9_999L
        val delayUpperBoundsUs: List<Long> = listOf(500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000, 250_000, 1_000_000)
    }
}
