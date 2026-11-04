package io.github.ts3mobile.audio.opus

internal object NativeOpusInfo {
    init {
        System.loadLibrary("ts3opus_jni")
    }

    val version: String get() = nativeVersion()

    fun inspect(packet: ByteArray): OpusPacketInfo {
        require(packet.isNotEmpty() && packet.size <= ReceivePolicy.MAX_PACKET_BYTES)
        val result = nativePacketInfo(packet)
        return OpusPacketInfo(result[0], result[1] != 0, packet.size <= 2)
    }

    @JvmStatic private external fun nativePacketInfo(packet: ByteArray): IntArray

    @JvmStatic private external fun nativeVersion(): String
}
