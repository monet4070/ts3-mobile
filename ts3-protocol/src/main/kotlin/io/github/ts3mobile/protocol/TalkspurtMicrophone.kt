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

    @Volatile var voiceTiming: Ts3jVoiceTiming? = null

    override fun isReady(): Boolean {
        voiceTiming?.clearOffered()
        return if (failed.get()) {
            false
        } else {
            try {
                source.isTransmitting
            } catch (error: Throwable) {
                reportFailure(error)
                false
            }
        }
    }

    override fun getCodec(): CodecType = CodecType.OPUS_VOICE

    override fun provide(): ByteArray =
        if (failed.get()) {
            EMPTY_FRAME
        } else {
            try {
                val timing = voiceTiming
                if (timing == null) {
                    source.pollEncodedFrame() ?: EMPTY_FRAME
                } else {
                    val frame =
                        if (source is TimedEncodedVoiceSource) {
                            source.pollTimedEncodedFrame()
                        } else {
                            source.pollEncodedFrame()?.let { TimedEncodedVoiceFrame(it, null) }
                        }
                    timing.offer(frame)
                    frame?.bytes ?: EMPTY_FRAME
                }
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
