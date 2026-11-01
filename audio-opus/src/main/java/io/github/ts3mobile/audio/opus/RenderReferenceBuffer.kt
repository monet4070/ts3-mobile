package io.github.ts3mobile.audio.opus

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * accepted() and its assembly state have one playback writer; take() has one capture consumer.
 * Other threads may invalidate through the atomic generation. Blocks retain their assembly
 * generation, and consumers must also reject results invalidated during processing.
 */
internal class RenderReferenceBuffer : PlaybackReferenceSink {
    data class Block(
        val generation: Long,
        val firstFrame: Long,
        val acceptedAt: Long,
        val timing: RenderTiming,
        val pcm: FloatArray,
    )

    data class Batch(val generation: Long, val playing: Boolean, val blocks: List<Block>, val overBudget: Boolean)

    private val active = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    private val epoch = AtomicLong()
    private val blocks = ArrayBlockingQueue<Block>(ApmPolicy.REFERENCE_CAPACITY_BLOCKS)
    private var assemblyEpoch = -1L
    private var partial = FloatArray(480)
    private var offset = 0
    private var expectedFrame = -1L
    private var firstFrame = 0L
    private var acceptedAt = 0L
    private var timing = RenderTiming(0L, AudioTimingQuality.UNAVAILABLE)
    val generation: Long get() = epoch.get()

    fun setCaptureActive(enabled: Boolean) {
        if (active.getAndSet(enabled) != enabled) invalidate()
    }

    override fun start() {
        playing.set(true)
        invalidate()
    }

    override fun discard() {
        playing.set(false)
        invalidate()
    }

    fun invalidate() {
        epoch.incrementAndGet()
        blocks.clear()
    }

    override fun accepted(
        samples: ShortArray,
        offset: Int,
        count: Int,
        firstFrame: Long,
        acceptedAt: Long,
        timing: RenderTiming,
    ) {
        require(offset >= 0 && count >= 0 && offset + count <= samples.size)
        if (!active.get() || !playing.get() || count == 0) return
        if (assemblyEpoch == generation && expectedFrame != firstFrame) invalidate()
        if (assemblyEpoch != generation) {
            assemblyEpoch = generation
            this.offset = 0
        }
        expectedFrame = firstFrame + count
        for (index in 0 until count) {
            if (this.offset == 0) {
                this.firstFrame = firstFrame + index
                this.acceptedAt = acceptedAt
                this.timing = timing.copy(presentationNanos = timing.presentationNanos + index * 1_000_000_000L / 48_000)
            } else if (timing.quality.ordinal > this.timing.quality.ordinal) {
                this.timing = this.timing.copy(quality = timing.quality)
            }
            partial[this.offset++] = samples[offset + index] / 32768f
            if (this.offset == partial.size) {
                val block = Block(assemblyEpoch, this.firstFrame, this.acceptedAt, this.timing, partial)
                if (!blocks.offer(block)) {
                    invalidate()
                    this.offset = 0
                    return
                }
                partial = FloatArray(480)
                this.offset = 0
            }
        }
    }

    fun take(now: Long): Batch {
        val current = generation
        val taken = ArrayList<Block>(ApmPolicy.MAX_REFERENCE_BLOCKS_PER_TICK)
        repeat(ApmPolicy.MAX_REFERENCE_BLOCKS_PER_TICK) {
            val block = blocks.poll() ?: return@repeat
            if (block.generation != current || now - block.acceptedAt !in 0L..ApmPolicy.MAX_REFERENCE_AGE_NANOS) {
                invalidate()
                return Batch(generation, playing.get(), emptyList(), true)
            }
            taken += block
        }
        if (blocks.isNotEmpty()) {
            invalidate()
            return Batch(generation, playing.get(), emptyList(), true)
        }
        if (current != generation) return Batch(generation, playing.get(), emptyList(), true)
        return Batch(current, playing.get(), taken, false)
    }
}
