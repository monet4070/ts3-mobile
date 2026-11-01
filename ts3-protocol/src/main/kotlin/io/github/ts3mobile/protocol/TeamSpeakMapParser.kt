package io.github.ts3mobile.protocol

/** Pure mapping helpers kept separate from the socket/session lifecycle. */
internal fun Map<String, String>.toTs3Channel(id: Int): Ts3Channel =
    Ts3Channel(
        id = id,
        parentId = intValue("pid") ?: intValue("cpid") ?: 0,
        orderAfterId = intValue("channel_order") ?: 0,
        name = get("channel_name").orEmpty(),
        clientCount = 0,
        hasPassword = booleanValue("channel_flag_password") ?: false,
        isDefault = booleanValue("channel_flag_default") ?: false,
        topic = get("channel_topic")?.boundedText(1024),
        maxClients = intValue("channel_maxclients")?.takeIf { it >= 0 },
        unlimitedClients = booleanValue("channel_flag_maxclients_unlimited"),
    )

internal fun Ts3Channel.withTeamSpeakUpdates(values: Map<String, String>): Ts3Channel =
    copy(
        parentId = values.intValue("pid") ?: values.intValue("cpid") ?: parentId,
        orderAfterId = values.intValue("channel_order") ?: orderAfterId,
        name = values["channel_name"] ?: name,
        hasPassword = values.booleanValue("channel_flag_password") ?: hasPassword,
        isDefault = values.booleanValue("channel_flag_default") ?: isDefault,
        topic = values["channel_topic"]?.boundedText(1024) ?: topic,
        maxClients = values.intValue("channel_maxclients")?.takeIf { it >= 0 } ?: maxClients,
        unlimitedClients = values.booleanValue("channel_flag_maxclients_unlimited") ?: unlimitedClients,
    )

internal fun Ts3Participant.withTeamSpeakUpdates(values: Map<String, String>): Ts3Participant =
    copy(
        nickname = values["client_nickname"] ?: nickname,
        uniqueIdentifier = values["client_unique_identifier"] ?: uniqueIdentifier,
        isTalking =
            values.booleanValue("client_flag_talking")
                ?: values.booleanValue("status")
                ?: isTalking,
        isAway = values.booleanValue("client_away") ?: isAway,
        awayMessage = values["client_away_message"]?.boundedText(256) ?: awayMessage,
        isInputMuted = values.booleanValue("client_input_muted") ?: isInputMuted,
        isOutputMuted = values.booleanValue("client_output_muted") ?: isOutputMuted,
    )

private fun Map<String, String>.intValue(key: String): Int? = get(key)?.toIntOrNull()

private fun Map<String, String>.booleanValue(key: String): Boolean? =
    get(key)?.let {
        when (it) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }
