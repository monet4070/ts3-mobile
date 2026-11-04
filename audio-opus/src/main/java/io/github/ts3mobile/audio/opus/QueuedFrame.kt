package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceFrame

internal data class QueuedFrame(
    val frame: VoiceFrame,
    val arrivalNanos: Long,
    val info: OpusPacketInfo? = null,
    val lease: ReceiveMediaBudget.Lease? = null,
    val transferredAt: Long? = null,
)
