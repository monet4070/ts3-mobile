package io.github.ts3mobile.protocol

internal fun Map<String, String>.toTextEvent(): Ts3TextEvent {
    val mode =
        when (get("targetmode")?.toIntOrNull()) {
            1 -> TextTargetMode.PRIVATE
            2 -> TextTargetMode.CHANNEL
            3 -> TextTargetMode.SERVER
            else -> TextTargetMode.UNKNOWN
        }
    val rawBody = get("msg")
    val body = rawBody.orEmpty().boundedText(MAX_TEXT_EVENT_BYTES)
    val issue =
        when {
            rawBody == null -> TextEventIssue.MISSING_BODY
            mode == TextTargetMode.UNKNOWN -> TextEventIssue.UNKNOWN_MODE
            body.length != rawBody.length -> TextEventIssue.TRUNCATED
            else -> TextEventIssue.NONE
        }
    return Ts3TextEvent(
        mode = mode,
        targetId = get("target")?.toIntOrNull()?.takeIf { it > 0 },
        invokerId = get("invokerid")?.toIntOrNull()?.takeIf { it > 0 },
        invokerUid = get("invokeruid")?.boundedText(256),
        invokerName = get("invokername")?.boundedText(256),
        body = body,
        issue = issue,
    )
}
