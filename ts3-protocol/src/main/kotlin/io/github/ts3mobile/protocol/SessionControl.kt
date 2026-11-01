package io.github.ts3mobile.protocol

data class OwnPresence(
    val away: Boolean = false,
    val awayMessage: String = "",
    val inputMuted: Boolean = false,
    val outputMuted: Boolean = false,
) {
    fun valid(): Boolean = awayMessage.toByteArray(Charsets.UTF_8).size <= 256

    override fun toString() = "OwnPresence(away=$away, inputMuted=$inputMuted, outputMuted=$outputMuted, message=<redacted>)"
}

enum class ControlStatus { ACCEPTED, REJECTED, UNKNOWN, NOT_SENT, BUSY, UNSUPPORTED }

data class ControlOutcome(val status: ControlStatus, val errorCode: Int? = null)

data class ChannelDetails(
    val id: Int,
    val name: String,
    val topic: String?,
    val description: String?,
    val maxClients: Int?,
    val unlimitedClients: Boolean?,
)

data class ChannelInfoResult(val outcome: ControlOutcome, val details: ChannelDetails? = null)

internal fun TextSendOutcome.toControlOutcome() =
    when (this) {
        TextSendOutcome.Accepted -> ControlOutcome(ControlStatus.ACCEPTED)
        is TextSendOutcome.Rejected -> ControlOutcome(ControlStatus.REJECTED, errorCode)
        is TextSendOutcome.NotSent -> ControlOutcome(if (reason == TextNotSentReason.BUSY) ControlStatus.BUSY else ControlStatus.NOT_SENT)
        TextSendOutcome.Unknown -> ControlOutcome(ControlStatus.UNKNOWN)
        TextSendOutcome.Unsupported -> ControlOutcome(ControlStatus.UNSUPPORTED)
    }
