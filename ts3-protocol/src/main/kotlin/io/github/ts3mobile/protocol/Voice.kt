package io.github.ts3mobile.protocol

enum class VoiceCodec {
    OPUS_VOICE,
    OPUS_MUSIC,
}

data class VoiceFrame(
    val clientId: Int,
    val packetId: Int,
    val codec: VoiceCodec,
    val encodedData: ByteArray,
    val isWhisper: Boolean,
)

interface EncodedVoiceSource {
    fun isReady(): Boolean

    fun pollEncodedFrame(): ByteArray?
}
