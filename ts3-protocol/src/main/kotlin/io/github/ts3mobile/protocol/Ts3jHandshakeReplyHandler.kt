package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.CommandProcessException
import com.github.manevolent.ts3j.command.MultiCommand
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState
import com.github.manevolent.ts3j.protocol.packet.handler.client.LocalClientHandlerRetrievingData

internal class Ts3jHandshakeReplyHandler(
    private val socket: StateAwareTeamspeakClientSocket,
) : LocalClientHandlerRetrievingData(socket) {
    override fun handleCommand(command: MultiCommand) {
        if (!command.name.equals("error", ignoreCase = true)) {
            super.handleCommand(command)
            return
        }

        val reply = command.simplifyFirst()
        val error =
            Ts3jHandshakeRejectedException(
                reply?.get("msg")?.value?.takeIf(String::isNotBlank) ?: "TeamSpeak server rejected the connection",
                reply?.get("id")?.value?.toIntOrNull()?.takeIf { it in 1..65535 },
            )
        socket.recordHandshakeRejection(error)
        socket.setState(ClientConnectionState.DISCONNECTED)
        throw error
    }
}

internal class Ts3jHandshakeRejectedException(
    message: String,
    val serverErrorId: Int?,
) : CommandProcessException(message)
