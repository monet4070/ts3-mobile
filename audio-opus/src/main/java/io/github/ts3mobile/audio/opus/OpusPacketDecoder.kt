package io.github.ts3mobile.audio.opus

/**
 * Decode port consumed by the talker jitter pipeline. The native decoder is
 * the production implementation; JVM tests substitute a fake so reordering,
 * concealment and pacing logic run without the JNI library.
 */
internal interface OpusPacketDecoder : AutoCloseable {
    fun packetInfo(packet: ByteArray): OpusPacketInfo

    fun decodeFec(
        packet: ByteArray,
        frameSize: Int,
    ): ShortArray

    fun decode(
        packet: ByteArray?,
        frameSize: Int,
    ): ShortArray

    fun reset()

    companion object {
        const val MAX_FRAME_SIZE = 5_760
    }
}
