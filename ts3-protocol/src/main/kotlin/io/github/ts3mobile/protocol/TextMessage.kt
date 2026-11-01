package io.github.ts3mobile.protocol

sealed interface TextTarget {
    data class Channel(val channelId: Int) : TextTarget

    data class Private(val clientId: Int, val expectedUid: String? = null) : TextTarget {
        override fun toString(): String = "Private(clientId=$clientId, identity=<redacted>)"
    }
}

enum class TextTargetMode { PRIVATE, CHANNEL, SERVER, UNKNOWN }

enum class TextEventIssue { NONE, MISSING_BODY, UNKNOWN_MODE, TRUNCATED }

data class Ts3TextEvent(
    val mode: TextTargetMode,
    val targetId: Int?,
    val invokerId: Int?,
    val invokerUid: String?,
    val invokerName: String?,
    val body: String,
    val issue: TextEventIssue = TextEventIssue.NONE,
) {
    override fun toString(): String = "Ts3TextEvent(mode=$mode, issue=$issue, content=<redacted>)"
}

enum class TextRejection { PERMISSION, INVALID_INPUT, SERVER }

enum class TextNotSentReason { NOT_CONNECTED, CONTEXT_CHANGED, INVALID_INPUT, BUSY }

sealed interface TextSendOutcome {
    data object Accepted : TextSendOutcome

    data class Rejected(val reason: TextRejection, val errorCode: Int?) : TextSendOutcome

    data class NotSent(val reason: TextNotSentReason) : TextSendOutcome

    data object Unknown : TextSendOutcome

    data object Unsupported : TextSendOutcome
}

const val MAX_TEXT_INPUT_BYTES = 1_024
const val MAX_TEXT_EVENT_BYTES = 16 * 1_024

/** Limits retained UTF-8 bytes without splitting a Unicode code point. */
fun String.boundedText(maxBytes: Int): String {
    var end = 0
    var bytes = 0
    while (end < length) {
        val count = Character.charCount(codePointAt(end))
        val size = substring(end, end + count).toByteArray(Charsets.UTF_8).size
        if (bytes + size > maxBytes) break
        bytes += size
        end += count
    }
    return substring(0, end)
}
