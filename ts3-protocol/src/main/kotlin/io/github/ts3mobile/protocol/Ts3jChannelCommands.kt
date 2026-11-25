package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.CommandException
import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.util.Ts3Crypt
import java.net.SocketTimeoutException

internal class Ts3jChannelCommands(
    private val runner: BoundedSessionCommands,
    private val clientId: () -> Int,
    private val timeoutMs: Long,
) {
    fun subscribeAll(canSubmit: () -> Boolean) {
        val command = SingleCommand("channelsubscribeall", ProtocolRole.CLIENT)
        when (val outcome = runner.execute(command, timeoutMs, canSubmit)) {
            TextSendOutcome.Accepted -> Unit
            is TextSendOutcome.Rejected -> throw CommandException("Channel subscription rejected", checkNotNull(outcome.errorCode))
            is TextSendOutcome.NotSent, TextSendOutcome.Unsupported -> throw SocketTimeoutException("Channel subscription did not start")
            TextSendOutcome.Unknown -> throw SocketTimeoutException("Channel subscription was not confirmed")
        }
    }

    fun join(
        channelId: Int,
        password: String,
        canSubmit: () -> Boolean,
    ) {
        val command =
            SingleCommand(
                "clientmove",
                ProtocolRole.CLIENT,
                CommandSingleParameter("cid", channelId.toString()),
                CommandSingleParameter("clid", clientId().toString()),
                CommandSingleParameter("cpw", Ts3Crypt.hashPassword(password)),
            )
        when (val outcome = runner.execute(command, timeoutMs, canSubmit)) {
            TextSendOutcome.Accepted -> Unit
            is TextSendOutcome.Rejected -> throw CommandException("Channel move rejected", checkNotNull(outcome.errorCode))
            is TextSendOutcome.NotSent, TextSendOutcome.Unsupported -> throw ChannelMoveNotSubmittedException()
            TextSendOutcome.Unknown -> throw ChannelMoveUnconfirmedException()
        }
    }
}
