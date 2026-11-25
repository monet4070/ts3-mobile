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
        icon = get("channel_icon_id")?.let(ServerImage::icon),
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
        icon = if ("channel_icon_id" in values) values["channel_icon_id"]?.let(ServerImage::icon) else icon,
    )

internal fun Ts3Participant.withTeamSpeakUpdates(values: Map<String, String>): Ts3Participant {
    val uid = values["client_unique_identifier"] ?: uniqueIdentifier
    return copy(
        nickname = values["client_nickname"] ?: nickname,
        uniqueIdentifier = uid,
        isTalking =
            values.booleanValue("client_flag_talking")
                ?: values.booleanValue("status")
                ?: isTalking,
        isAway = values.booleanValue("client_away") ?: isAway,
        awayMessage = values["client_away_message"]?.boundedText(256) ?: awayMessage,
        isInputMuted = values.booleanValue("client_input_muted") ?: isInputMuted,
        isOutputMuted = values.booleanValue("client_output_muted") ?: isOutputMuted,
        avatar = if ("client_flag_avatar" in values) values.toAvatar(uid) else avatar.takeIf { uid == uniqueIdentifier },
    )
}

internal fun Map<String, String>.toAvatar(uid: String): ServerImage.Avatar? = get("client_flag_avatar")?.let { ServerImage.avatar(uid, it) }

private fun Map<String, String>.intValue(key: String): Int? = get(key)?.toIntOrNull()

private fun Map<String, String>.booleanValue(key: String): Boolean? =
    get(key)?.let {
        when (it) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }
