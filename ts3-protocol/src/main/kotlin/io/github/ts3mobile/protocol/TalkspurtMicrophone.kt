package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.audio.Microphone
import com.github.manevolent.ts3j.enums.CodecType
import java.util.concurrent.atomic.AtomicBoolean

/** ts3j skips an empty provided frame, but interprets isReady=false as talkspurt end. */
internal class TalkspurtMicrophone(
    private val source: EncodedVoiceSource,
    private val onFailure: (Throwable) -> Unit,
) : Microphone {
    private val failed = AtomicBoolean(false)

    override fun isReady(): Boolean =
        if (failed.get()) {
            false
        } else {
            try {
                source.isTransmitting
            } catch (error: Throwable) {
                reportFailure(error)
                false
            }
        }

    override fun getCodec(): CodecType = CodecType.OPUS_VOICE

    override fun provide(): ByteArray =
        if (failed.get()) {
            EMPTY_FRAME
        } else {
            try {
                source.pollEncodedFrame() ?: EMPTY_FRAME
            } catch (error: Throwable) {
                reportFailure(error)
                EMPTY_FRAME
            }
        }

    private fun reportFailure(error: Throwable) {
        if (failed.compareAndSet(false, true)) onFailure(error)
    }

    private companion object {
        val EMPTY_FRAME = ByteArray(0)
    }
}
