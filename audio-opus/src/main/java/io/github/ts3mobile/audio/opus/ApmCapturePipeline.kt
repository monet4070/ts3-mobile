package io.github.ts3mobile.audio.opus

/** One capture owner; lifecycle calls serialize with processing before native resources close. */
internal class ApmCapturePipeline(
    private val worker: ApmExperimentWorker,
    private val configured: MicrophoneSettings,
    private val encode: (ShortArray) -> ByteArray,
) : AutoCloseable {
    private val assembled = FloatArray(960)
    private val frames = MicrophoneFrameProcessor()
    private var offset = 0
    private var revision = -1L
    private var options: MicrophoneProcessingOptions? = null
    private var generation = -1L
    val isSpeaking: Boolean get() = frames.isSpeaking

    @Synchronized fun process(
        pcm: ShortArray,
        captureAt: Long?,
        current: MicrophoneProcessingOptions,
        currentRevision: Long,
    ): List<ByteArray>? {
        require(
            current.settings.noiseSuppression == configured.noiseSuppression &&
                current.settings.echoCancellation == configured.echoCancellation,
        ) { "Rebuild the experimental processor when AEC/NS intent changes" }
        if (revision != currentRevision || options != current) {
            if (options != null) worker.invalidate()
            resetFrames()
            options = current
            revision = currentRevision
        }
        val processed = worker.process(pcm, captureAt)
        if (processed == null) {
            resetFrames()
            return null
        }
        val processedGeneration = worker.processedGeneration
        if (processedGeneration != worker.generation) {
            resetFrames()
            return null
        }
        if (generation != processedGeneration) {
            resetFrames()
            generation = processedGeneration
        }
        for (index in processed.indices) assembled[offset + index] = processed[index] * 32768f
        offset += processed.size
        if (offset < assembled.size) return null
        offset = 0
        val encoded = frames.process(assembled, current).map(encode)
        if (generation != worker.generation) {
            resetFrames()
            return null
        }
        return encoded
    }

    private fun resetFrames() {
        offset = 0
        frames.reset()
    }

    @Synchronized override fun close() {
        resetFrames()
        worker.close()
    }
}
