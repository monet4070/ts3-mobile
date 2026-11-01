package io.github.ts3mobile.audio.opus

internal class NativeApmProcessor(
    echoCancellation: Boolean = true,
    noiseSuppression: Boolean = true,
) : ApmProcessor {
    private var handle = nativeCreate(echoCancellation, noiseSuppression)

    @Synchronized
    override fun reverse(pcm: FloatArray) {
        validate(pcm)
        nativeReverse(handle, pcm)
    }

    @Synchronized
    override fun capture(
        pcm: FloatArray,
        delayMs: Int,
    ): FloatArray {
        validate(pcm)
        require(delayMs in 0..500)
        return nativeCapture(handle, pcm, delayMs)
    }

    @Synchronized
    override fun reset() {
        check(handle != 0L) { "APM experiment is closed" }
        nativeReset(handle)
    }

    private fun validate(pcm: FloatArray) {
        check(handle != 0L) { "APM experiment is closed" }
        require(pcm.size == 480 && pcm.all { it.isFinite() && it in -1f..1f })
    }

    @Synchronized
    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) nativeDestroy(current)
    }

    private companion object {
        init {
            System.loadLibrary("ts3apm_experiment")
        }

        @JvmStatic private external fun nativeCreate(
            echoCancellation: Boolean,
            noiseSuppression: Boolean,
        ): Long

        @JvmStatic private external fun nativeReverse(
            handle: Long,
            pcm: FloatArray,
        )

        @JvmStatic private external fun nativeCapture(
            handle: Long,
            pcm: FloatArray,
            delayMs: Int,
        ): FloatArray

        @JvmStatic private external fun nativeReset(handle: Long)

        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
