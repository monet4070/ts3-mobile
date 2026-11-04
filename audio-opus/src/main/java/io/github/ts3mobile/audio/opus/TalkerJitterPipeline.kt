package io.github.ts3mobile.audio.opus

import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

internal class TalkerJitterPipeline(
    private val decodedPacketCount: AtomicLong? = null,
    private val decoderFactory: () -> OpusPacketDecoder = { NativeOpusDecoder() },
    private val policy: ReceivePolicy = ReceivePolicy(),
    private val budget: ReceiveMediaBudget = ReceiveMediaBudget(),
) : AutoCloseable {
    private val pcm = ReceivePcmBuffer(budget.stats)
    private val decoder = decoderFactory()
    private val pending = TreeMap<Long, QueuedFrame>()
    private val sequence = PacketSequenceUnwrapper()
    private val delay = AdaptiveJitterDelay(policy)
    private var expected: Long? = null
    private var retiredBefore: Long? = null
    private var firstArrival: Long? = null
    private var started = false
    private var token: Long? = null
    private var clientId = -1
    private var lastSamples = DEFAULT_PACKET_SAMPLES
    private var missingRun = 0
    private var concealedSamples = 0
    private var lastNow = 0L
    private var holdUntil = 0L
    private var rebufferedSequence: Long? = null
    private var fadeRemaining = 0
    private var framingConfirmed = policy.confirmedPacketSamples != null
    var activeTargetMs = ReceivePolicy.INITIAL_TARGET_MS
        private set
    val nextTargetMs: Int get() = delay.targetMs
    val oldestReadArrival: Long? get() = pcm.oldestReadArrival
    var resetDroppedPcmSamples = 0L
        private set
    var lastArrivalNanos = 0L
        private set

    fun offer(queued: QueuedFrame) {
        if (queued.frame.encodedData.isEmpty()) return
        val info = queued.info ?: decoder.packetInfo(queued.frame.encodedData)
        val lease =
            queued.lease ?: budget.reserve(
                queued.frame.clientId, info.samples,
                queued.frame.encodedData.size, queued.arrivalNanos,
            ) ?: return
        if (!lease.valid) return
        if (token != null && token != lease.token) {
            reset(clearSequence = true)
            fadeRemaining = ReceivePolicy.FADE_SAMPLES
        }
        clientId = queued.frame.clientId
        token = lease.token
        var extended = sequence.unwrap(queued.frame.packetId)
        if (expected?.let { extended - it > MAX_FORWARD_PACKET_GAP } == true) {
            reset(clearSequence = true)
            extended = sequence.unwrap(queued.frame.packetId)
        }
        if ((expected ?: retiredBefore)?.let { extended < it } == true) {
            lease.release()
            budget.stats.latePackets.incrementAndGet()
            delay.noteLate()
            return
        }
        if (pending.containsKey(extended)) {
            lease.release()
            budget.stats.duplicates.incrementAndGet()
            return
        }
        pending[extended] = queued.copy(info = info, lease = lease)
        if (info.samples != policy.confirmedPacketSamples || info.timingUncertain) framingConfirmed = false
        if (firstArrival == null) firstArrival = queued.arrivalNanos
        lastArrivalNanos = maxOf(lastArrivalNanos, queued.arrivalNanos)
        delay.observe(extended, queued.arrivalNanos, info)
    }

    fun readyToStart(now: Long): Boolean {
        if (pcm.available > 0) return true
        if (started) return pending.isNotEmpty()
        val first = firstArrival ?: return false
        return pending.isNotEmpty() && now - first >= activeTargetMs * MILLIS
    }

    fun fillPcm(now: Long) {
        lastNow = now
        if (token != null && budget.generation(clientId) != token) {
            reset(clearSequence = true)
            token = null
            return
        }
        if (!started) {
            if (!readyToStart(now)) return
            expected = pending.firstKey()
            started = true
        }
        if (now < holdUntil) return
        while (pcm.available < PCM_TARGET_SAMPLES) {
            val current = expected ?: break
            val packet = pending.remove(current)
            if (packet != null) {
                val lease = checkNotNull(packet.lease)
                if (!lease.valid) continue
                val info = checkNotNull(packet.info)
                val decoded =
                    try {
                        decoder.decode(packet.frame.encodedData, OpusPacketDecoder.MAX_FRAME_SIZE).also {
                            check(it.size == info.samples) { "Opus duration disagrees with decoded PCM" }
                        }
                    } catch (failure: RuntimeException) {
                        lease.release()
                        throw failure
                    }
                for (index in 0 until minOf(fadeRemaining, decoded.size)) {
                    decoded[index] =
                        (decoded[index].toInt() * (ReceivePolicy.FADE_SAMPLES + 1 - fadeRemaining) / ReceivePolicy.FADE_SAMPLES).toShort()
                    fadeRemaining--
                }
                pcm.add(decoded, lease, packet.transferredAt ?: packet.arrivalNanos)
                lastSamples = decoded.size
                decodedPacketCount?.incrementAndGet()
                expected = current + 1
                missingRun = 0
                concealedSamples = 0
                continue
            }
            val next = pending.firstEntry() ?: break
            val gap = next.key - current
            if (gap > MAX_CONCEALED_PACKETS || missingRun >= MAX_CONCEALED_PACKETS ||
                concealedSamples >= MAX_CONCEALED_SAMPLES
            ) {
                decoder.reset()
                delay.discontinuity()
                expected = next.key
                missingRun = 0
                concealedSamples = 0
                continue
            }
            if (pcm.available >= MIX_TICK_SAMPLES) break
            if (policy.adaptive && pcm.available == 0 && nextTargetMs > activeTargetMs && rebufferedSequence != current) {
                activeTargetMs = minOf(ReceivePolicy.MAX_TARGET_MS, activeTargetMs + ReceivePolicy.TARGET_STEP_MS)
                holdUntil = now + ReceivePolicy.TARGET_STEP_MS * MILLIS
                rebufferedSequence = current
                budget.stats.rebuffers.incrementAndGet()
                return
            }
            val concealSize = min(lastSamples, MAX_CONCEALED_SAMPLES - concealedSamples)
            val source = checkNotNull(next.value.lease)
            val lease = budget.conceal(source, concealSize, now)
            if (lease == null) {
                decoder.reset()
                expected = next.key
                continue
            }
            val info = checkNotNull(next.value.info)
            val confirmed = policy.confirmedPacketSamples
            val useFec =
                gap == 1L && confirmed != null && lastSamples == confirmed &&
                    info.samples == confirmed && info.hasFec && !info.timingUncertain && concealSize == confirmed &&
                    framingConfirmed && next.value.arrivalNanos <= now
            val concealed =
                try {
                    val result =
                        if (useFec) {
                            budget.stats.fecAttempts.incrementAndGet()
                            decoder.decodeFec(next.value.frame.encodedData, concealSize)
                        } else {
                            budget.stats.plcPackets.incrementAndGet()
                            decoder.decode(null, concealSize)
                        }
                    check(result.size == concealSize) { "Opus concealment duration mismatch" }
                    result
                } catch (failure: RuntimeException) {
                    lease.release()
                    if (useFec) {
                        budget.stats.fecFailures.incrementAndGet()
                        decoder.reset()
                        expected = next.key
                        missingRun = 0
                        concealedSamples = 0
                        fadeRemaining = ReceivePolicy.FADE_SAMPLES
                        delay.discontinuity()
                        continue
                    }
                    throw failure
                }
            pcm.add(concealed, lease, now)
            concealedSamples += concealSize
            missingRun++
            expected = current + 1
            delay.discontinuity()
        }
    }

    fun isFinishedTalkspurt(): Boolean = started && pending.isEmpty() && pcm.available == 0

    fun readPcm(maxSamples: Int): ShortArray = pcm.read(maxSamples, lastNow)

    fun finishTalkspurt() {
        retiredBefore = expected
        reset(clearSequence = false)
    }

    override fun close() {
        pending.values.forEach { it.lease?.release() }
        pending.clear()
        pcm.clear()
        decoder.close()
    }

    private fun reset(clearSequence: Boolean) {
        decoder.reset()
        pending.values.forEach { it.lease?.release() }
        pending.clear()
        resetDroppedPcmSamples += pcm.clear()
        expected = null
        firstArrival = null
        started = false
        missingRun = 0
        concealedSamples = 0
        lastSamples = DEFAULT_PACKET_SAMPLES
        holdUntil = 0
        rebufferedSequence = null
        activeTargetMs = delay.targetMs
        framingConfirmed = policy.confirmedPacketSamples != null
        delay.discontinuity()
        if (clearSequence) {
            sequence.reset()
            retiredBefore = null
        }
    }

    companion object {
        internal const val MIX_TICK_SAMPLES = 480
        internal const val DEFAULT_PACKET_SAMPLES = 960
        internal const val PCM_TARGET_SAMPLES = 2_880
        internal const val MAX_CONCEALED_PACKETS = 3
        internal const val MAX_CONCEALED_SAMPLES = ReceivePolicy.MAX_PLC_SAMPLES
        internal const val MAX_FORWARD_PACKET_GAP = 64
        private const val MILLIS = 1_000_000L
    }
}
