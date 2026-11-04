package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.util.Ts3String
import java.util.concurrent.atomic.AtomicReference

internal class Ts3jControlCommands(private val commands: Ts3jTextCommands) {
    fun updatePresence(
        presence: OwnPresence,
        timeoutMs: Long,
        canSubmit: () -> Boolean,
    ): ControlOutcome {
        if (!presence.valid()) return ControlOutcome(ControlStatus.NOT_SENT)
        val command =
            SingleCommand(
                "clientupdate",
                ProtocolRole.CLIENT,
                CommandSingleParameter("client_away", if (presence.away) "1" else "0"),
                escapedParameter("client_away_message", if (presence.away) presence.awayMessage else ""),
                CommandSingleParameter("client_input_muted", if (presence.inputMuted) "1" else "0"),
                CommandSingleParameter("client_output_muted", if (presence.outputMuted) "1" else "0"),
            )
        return commands.execute(command, timeoutMs, canSubmit).toControlOutcome()
    }

    fun channelInfo(
        channelId: Int,
        timeoutMs: Long,
        canSubmit: () -> Boolean,
    ): ChannelInfoResult {
        if (channelId <= 0) return ChannelInfoResult(ControlOutcome(ControlStatus.NOT_SENT))
        val details = AtomicReference<ChannelDetails?>()
        val outcome =
            commands.execute(
                SingleCommand(
                    "channelinfo",
                    ProtocolRole.CLIENT,
                    CommandSingleParameter("cid", channelId.toString()),
                ),
                timeoutMs,
                canSubmit,
            ) { response ->
                val rows = response.toList()
                if (rows.size == 1) {
                    val values = rows.single().parameters.associate { it.name to it.value }
                    if (values["cid"] == null || values["cid"]?.toIntOrNull() == channelId) {
                        details.set(values.toChannelDetails(channelId))
                    }
                }
            }.toControlOutcome()
        return if (outcome.status == ControlStatus.ACCEPTED && details.get() == null) {
            ChannelInfoResult(ControlOutcome(ControlStatus.UNKNOWN))
        } else {
            ChannelInfoResult(outcome, if (outcome.status == ControlStatus.ACCEPTED) details.get() else null)
        }
    }
}

internal fun Map<String, String>.toChannelDetails(id: Int) =
    ChannelDetails(
        id,
        get("channel_name").orEmpty().boundedText(512),
        get("channel_topic")?.boundedText(1024),
        get("channel_description")?.boundedText(16 * 1024),
        get("channel_maxclients")?.toIntOrNull()?.takeIf { it >= 0 },
        get("channel_flag_maxclients_unlimited")?.let {
            when (it) {
                "0" -> false
                "1" -> true
                else -> null
            }
        },
    )

private fun escapedParameter(
    name: String,
    content: String,
): CommandSingleParameter =
    object : CommandSingleParameter(name, content) {
        override fun toString(): String = name + "=" + Ts3String.escape(value)
    }
