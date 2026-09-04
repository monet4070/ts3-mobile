package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceFrame
import java.util.ArrayDeque
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * A single participant's jitter pipeline: reorders 16-bit-wrapped packet
 * sequences, decodes Opus frames in order, conceals up to
 * MAX_CONCEALED_PACKETS missing packets per gap, and exposes decoded PCM
 * through a bounded FIFO. Extracted from OpusAudioPlayer so the buffering
 * logic is testable without an AudioTrack.
 */
internal class TalkerJitterPipeline(
    /** Optional shared decoded-packet counter; null means no accounting. */
    private val decodedPacketCount: AtomicLong? = null,
    private val decoderFactory: () -> OpusPacketDecoder = { NativeOpusDecoder() },
) : AutoCloseable {
    private val pcm = PcmFifo(PCM_FIFO_CAPACITY_SAMPLES)
    private val decoder = decoderFactory()
    private val pending = TreeMap<Long, QueuedFrame>()
    private val sequenceUnwrapper = PacketSequenceUnwrapper()
    private var expectedSequence: Long? = null
    private var firstArrivalNanos: Long? = null
    private var started = false
    private var lastFrameSamples = DEFAULT_PACKET_SAMPLES
    private var missingRun = 0

    var lastArrivalNanos: Long = System.nanoTime()
        private set

    fun offer(queued: QueuedFrame) {
        var extended = sequenceUnwrapper.unwrap(queued.frame.packetId)
        val expected = expectedSequence
        if (expected != null && extended - expected > MAX_FORWARD_PACKET_GAP) {
            resetPipeline(clearSequence = true)
            extended = sequenceUnwrapper.unwrap(queued.frame.packetId)
        }
        if (expectedSequence?.let { extended < it } == true || pending.containsKey(extended)) return
        if (pending.size >= MAX_PENDING_PACKETS) {
            resetPipeline(clearSequence = false)
        }

        pending[extended] = queued
        if (firstArrivalNanos == null) firstArrivalNanos = queued.arrivalNanos
        lastArrivalNanos = queued.arrivalNanos
    }

    fun readyToStart(now: Long): Boolean {
        if (pcm.availableSamples > 0) return true
        if (started) return pending.isNotEmpty()
        val firstArrival = firstArrivalNanos ?: return false
        return pending.isNotEmpty() && now - firstArrival >= INITIAL_HOLD_NANOS
    }

    fun fillPcm(now: Long) {
        if (!started) {
            if (!readyToStart(now)) return
            expectedSequence = pending.firstKey()
            started = true
        }

        while (pcm.availableSamples < PCM_TARGET_SAMPLES) {
            var expected = expectedSequence ?: break
            while (pending.isNotEmpty() && pending.firstKey() < expected) {
                pending.pollFirstEntry()
            }

            val packet = pending.remove(expected)
            if (packet != null) {
                val decoded = decoder.decode(packet.frame.encodedData, OpusPacketDecoder.MAX_FRAME_SIZE)
                if (decoded.isNotEmpty()) {
                    pcm.add(decoded)
                    lastFrameSamples = decoded.size
                    decodedPacketCount?.incrementAndGet()
                }
                expectedSequence = expected + 1
                missingRun = 0
                continue
            }

            val nextSequence = pending.firstKeyOrNull() ?: break
            val gap = nextSequence - expected
            if (gap > MAX_CONCEALED_PACKETS || missingRun >= MAX_CONCEALED_PACKETS) {
                decoder.reset()
                expectedSequence = nextSequence
                missingRun = 0
                continue
            }
            if (pcm.availableSamples >= MIX_TICK_SAMPLES) break

            pcm.add(decoder.decode(null, lastFrameSamples))
            expected++
            expectedSequence = expected
            missingRun++
        }
    }

    fun isFinishedTalkspurt(): Boolean = started && pending.isEmpty() && pcm.availableSamples == 0

    fun readPcm(maxSamples: Int): ShortArray = pcm.read(maxSamples)

    fun finishTalkspurt() {
        decoder.reset()
        expectedSequence = null
        firstArrivalNanos = null
        started = false
        missingRun = 0
    }

    override fun close() {
        pending.clear()
        pcm.clear()
        decoder.close()
    }

    private fun resetPipeline(clearSequence: Boolean) {
        decoder.reset()
        pending.clear()
        pcm.clear()
        expectedSequence = null
        firstArrivalNanos = null
        started = false
        missingRun = 0
        lastFrameSamples = DEFAULT_PACKET_SAMPLES
        if (clearSequence) {
            sequenceUnwrapper.reset()
        }
    }

    internal class PcmFifo(private val capacity: Int) {
        private val chunks = ArrayDeque<ShortArray>()
        private var firstChunkOffset = 0

        var availableSamples: Int = 0
            private set

        fun add(samples: ShortArray) {
            val accepted = min(samples.size, capacity - availableSamples)
            if (accepted <= 0) return
            chunks.addLast(if (accepted == samples.size) samples else samples.copyOf(accepted))
            availableSamples += accepted
        }

        fun read(maxSamples: Int): ShortArray {
            val sampleCount = min(maxSamples, availableSamples)
            if (sampleCount == 0) return ShortArray(0)
            val output = ShortArray(sampleCount)
            var outputOffset = 0
            while (outputOffset < sampleCount) {
                val first = chunks.first()
                val copied = min(sampleCount - outputOffset, first.size - firstChunkOffset)
                first.copyInto(
                    destination = output,
                    destinationOffset = outputOffset,
                    startIndex = firstChunkOffset,
                    endIndex = firstChunkOffset + copied,
                )
                outputOffset += copied
                firstChunkOffset += copied
                availableSamples -= copied
                if (firstChunkOffset == first.size) {
                    chunks.removeFirst()
                    firstChunkOffset = 0
                }
            }
            return output
        }

        fun clear() {
            chunks.clear()
            firstChunkOffset = 0
            availableSamples = 0
        }
    }

    companion object {
        /** PCM samples consumed per 10 ms playback mix tick (48 kHz). */
        internal const val MIX_TICK_SAMPLES = 480
        internal const val DEFAULT_PACKET_SAMPLES = 960
        internal const val PCM_TARGET_SAMPLES = 2_880
        internal const val PCM_FIFO_CAPACITY_SAMPLES = 5_760
        internal const val MAX_PENDING_PACKETS = 64
        internal const val MAX_CONCEALED_PACKETS = 3
        internal const val MAX_FORWARD_PACKET_GAP = 64
        internal val INITIAL_HOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(60)

        private fun <V> TreeMap<Long, V>.firstKeyOrNull(): Long? = if (isEmpty()) null else firstKey()
    }
}

/**
 * A voice frame paired with its arrival timestamp, shared by the player's
 * input queue and the per-talker jitter pipelines.
 */
internal data class QueuedFrame(
    val frame: VoiceFrame,
    val arrivalNanos: Long,
)
