package io.github.ts3mobile.protocol

const val MAX_POKE_INPUT_BYTES = 100

data class Ts3PokeEvent(
    val invokerId: Int?,
    val invokerUid: String?,
    val invokerName: String?,
    val body: String,
    val issue: TextEventIssue = TextEventIssue.NONE,
) {
    override fun toString(): String = "Ts3PokeEvent(issue=$issue, content=<redacted>)"
}

internal fun Map<String, String>.toPokeEvent(): Ts3PokeEvent {
    val raw = get("msg")
    val body = raw.orEmpty().boundedText(MAX_TEXT_EVENT_BYTES)
    return Ts3PokeEvent(
        get("invokerid")?.toIntOrNull()?.takeIf { it > 0 },
        get("invokeruid")?.takeIf { it.isNotBlank() && it.length <= 256 },
        get("invokername")?.boundedText(256),
        body,
        when {
            raw == null -> TextEventIssue.MISSING_BODY
            body.length != raw.length -> TextEventIssue.TRUNCATED
            else -> TextEventIssue.NONE
        },
    )
}
