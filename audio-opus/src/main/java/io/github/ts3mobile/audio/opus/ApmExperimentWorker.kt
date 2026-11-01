package io.github.ts3mobile.audio.opus

internal class ApmExperimentWorker(
    private val reference: RenderReferenceBuffer,
    policy: ApmExperimentPolicy,
    factory: () -> ApmProcessor,
    private val restoreBaseline: (ApmFallbackReason) -> (ShortArray) -> FloatArray,
    private val onStatus: (ApmExperimentStatus) -> Unit,
    private val clock: () -> Long = System::nanoTime,
) : AutoCloseable {
    private var processor: ApmProcessor? = null
    private var baseline: ((ShortArray) -> FloatArray)? = null
    private var epoch = -1L
    private var render: RenderTiming? = null
    private var reversedAt = 0L
    private var referenceFailures = 0
    private var clockFailures = 0
    private var estimatedFrames = 0
    private var overruns = 0
    private var closed = false
    private var requestedFallback = ApmFallbackReason.NONE
    private val delay = ApmDelayEstimator()
    var status = ApmExperimentStatus(false, ApmFallbackReason.NONE)
        private set
    var delayEstimate = ApmDelayEstimator.Estimate(null, AudioTimingQuality.UNAVAILABLE)
        private set
    val generation: Long get() = reference.generation
    var processedGeneration = -1L
        private set

    init {
        if (policy.eligible) {
            try {
                processor = factory()
            } catch (_: RuntimeException) {
                requestedFallback = ApmFallbackReason.NATIVE
            } catch (_: LinkageError) {
                requestedFallback = ApmFallbackReason.NATIVE
            }
            if (processor == null) {
                fallback(ApmFallbackReason.NATIVE)
            } else {
                reference.setCaptureActive(true)
                publish(ApmExperimentStatus(true, ApmFallbackReason.NONE))
            }
        } else {
            fallback(ApmFallbackReason.POLICY)
        }
    }

    @Synchronized fun process(
        pcm: ShortArray,
        captureAt: Long?,
    ): FloatArray? {
        check(!closed)
        require(pcm.size == 480)
        if (baseline != null) return processBaseline(pcm)
        val output =
            try {
                processExperiment(pcm, captureAt)
            } catch (_: RuntimeException) {
                requestedFallback = ApmFallbackReason.NATIVE
                null
            } catch (_: LinkageError) {
                requestedFallback = ApmFallbackReason.NATIVE
                null
            }
        if (requestedFallback != ApmFallbackReason.NONE) fallback(requestedFallback)
        if (baseline != null) return processBaseline(pcm)
        if (output != null) processedGeneration = epoch
        return output
    }

    private fun processBaseline(pcm: ShortArray): FloatArray? {
        val current = generation
        val output = checked(checkNotNull(baseline)(pcm))
        if (current != generation) return null
        processedGeneration = current
        return output
    }

    @Synchronized fun updatePolicy(policy: ApmExperimentPolicy) {
        check(!closed)
        reference.invalidate()
        if (!policy.eligible && baseline == null) fallback(ApmFallbackReason.POLICY)
    }

    @Synchronized fun invalidate() {
        check(!closed)
        reference.invalidate()
    }

    private fun processExperiment(
        pcm: ShortArray,
        captureAt: Long?,
    ): FloatArray? {
        val started = clock()
        val batch = reference.take(started)
        val engine = checkNotNull(processor)
        if (epoch != batch.generation) {
            engine.reset()
            delay.reset()
            delayEstimate = ApmDelayEstimator.Estimate(null, AudioTimingQuality.UNAVAILABLE)
            render = null
            epoch = batch.generation
        }
        if (batch.overBudget) return referenceFailure()
        batch.blocks.forEach { block ->
            reversedAt = clock()
            engine.reverse(block.pcm)
            render = block.timing
        }
        if (!batch.playing) {
            reversedAt = clock()
            engine.reverse(FloatArray(480))
            render = RenderTiming(reversedAt, AudioTimingQuality.ESTIMATED)
        } else if (render == null || started - reversedAt > ApmPolicy.ESTIMATE_GRACE_NANOS) {
            return referenceFailure()
        }
        referenceFailures = 0
        val estimate = delay.estimate(render, reversedAt, captureAt, clock())
        delayEstimate = estimate
        if (batch.playing && estimate.quality == AudioTimingQuality.ESTIMATED) estimatedFrames++ else estimatedFrames = 0
        if (estimatedFrames >= ApmPolicy.MAX_ESTIMATED_FRAMES) {
            requestedFallback = ApmFallbackReason.CLOCK
            return null
        }
        val delayMs = estimate.milliseconds
        if (delayMs == null) {
            clockFailures++
            if (estimate.discontinuity) reference.invalidate()
            if (clockFailures >= ApmPolicy.MAX_CLOCK_FAILURES) {
                requestedFallback = ApmFallbackReason.CLOCK
            }
            return null
        }
        clockFailures = 0
        val output = checked(engine.capture(FloatArray(480) { pcm[it] / 32768f }, delayMs))
        if (clock() - started > ApmPolicy.MAX_PROCESS_NANOS) overruns++ else overruns = 0
        if (overruns >= ApmPolicy.MAX_CONSECUTIVE_OVERRUNS) {
            requestedFallback = ApmFallbackReason.CPU
            return null
        }
        if (epoch != reference.generation) return null
        return output
    }

    private fun referenceFailure(): FloatArray? {
        referenceFailures++
        reference.invalidate()
        delayEstimate = ApmDelayEstimator.Estimate(null, AudioTimingQuality.UNAVAILABLE)
        if (referenceFailures >= ApmPolicy.MAX_REFERENCE_FAILURES) {
            requestedFallback = ApmFallbackReason.REFERENCE
        }
        return null
    }

    private fun fallback(reason: ApmFallbackReason) {
        reference.setCaptureActive(false)
        processor?.close()
        processor = null
        baseline = restoreBaseline(reason)
        delayEstimate = ApmDelayEstimator.Estimate(null, AudioTimingQuality.UNAVAILABLE)
        publish(ApmExperimentStatus(false, reason))
    }

    private fun checked(pcm: FloatArray): FloatArray {
        check(pcm.size == 480 && pcm.all { it.isFinite() && it in -1f..1f }) { "Invalid preprocessor output" }
        return pcm
    }

    private fun publish(updated: ApmExperimentStatus) {
        if (status == updated) return
        status = updated
        onStatus(updated)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        reference.setCaptureActive(false)
        try {
            processor?.close()
        } finally {
            processor = null
            baseline = null
            publish(ApmExperimentStatus(false, ApmFallbackReason.CLOSED))
        }
    }
}
