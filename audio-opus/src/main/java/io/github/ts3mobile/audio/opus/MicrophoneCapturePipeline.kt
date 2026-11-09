package io.github.ts3mobile.audio.opus

/** Worker-confined 10 ms input -> optional suppression -> 20 ms gain/gate/Opus. */
internal class MicrophoneCapturePipeline(
    private val denoise: (ShortArray) -> Float,
    private val encode: (ShortArray) -> ByteArray,
    private val stats: CaptureSessionStats,
    private val onConfiguration: (MicrophoneSettings) -> Unit,
) {
    private val opusPcm = ShortArray(960)
    private var offset = 0
    private var previousOptions: MicrophoneProcessingOptions? = null
    private var previousRevision = -1L
    private var previousRouteRevision = -1L
    private val processor = MicrophoneFrameProcessor()
    val isSpeaking: Boolean get() = processor.isSpeaking
    val levelDb: Double get() = processor.levelDb

    /** Null means a partial Opus block, not the end of a talkspurt. */
    fun process(
        pcm: ShortArray,
        options: MicrophoneProcessingOptions,
        revision: Long,
        routeRevision: Long = revision,
        measureLevel: Boolean = false,
    ): List<ByteArray>? {
        require(pcm.size == 480)
        if (options != previousOptions || revision != previousRevision) {
            val routeChanged = routeRevision != previousRouteRevision
            val previous = previousOptions
            val volumeOnly = options.sameExceptVolume(previous)
            if (routeChanged || !volumeOnly) {
                offset = 0
                processor.reset(preserveLimiter = !routeChanged && options.settings.volumePercent > 0)
            }
            if (routeChanged || previousOptions?.settings?.noiseSuppression != options.settings.noiseSuppression ||
                previousOptions?.settings?.echoCancellation != options.settings.echoCancellation
            ) {
                onConfiguration(options.settings)
            }
            previousOptions = options
            previousRevision = revision
            previousRouteRevision = routeRevision
        }
        stats.noteRawFrame(pcm)
        if (options.settings.noiseSuppression) {
            val started = System.nanoTime()
            val probability = denoise(pcm)
            stats.noteDenoisedFrame(pcm, System.nanoTime() - started, probability)
        } else {
            stats.noteBypassedFrame(pcm)
        }
        pcm.copyInto(opusPcm, destinationOffset = offset)
        offset += pcm.size
        if (offset < opusPcm.size) return null
        offset = 0
        return processor.process(opusPcm, options, measureLevel).map(encode)
    }
}
