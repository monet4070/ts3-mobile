package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import com.github.manevolent.ts3j.util.Ts3String
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Orders enqueueing only; response waits never own the submission lock. */
internal class Ts3jTextCommands(
    private val socket: LocalTeamspeakClientSocket,
    private val operation: (() -> Unit) -> Unit,
) {
    private val submissionLock = ReentrantLock()
    private val runner = BoundedSessionCommands(socket, operation, submissionLock)

    fun join(
        channelId: Int,
        password: String,
    ) {
        val response =
            submissionLock.withLock {
                val command =
                    SingleCommand(
                        "clientmove",
                        ProtocolRole.CLIENT,
                        CommandSingleParameter("cid", channelId.toString()),
                        CommandSingleParameter("clid", socket.clientId.toString()),
                    )
                // Preserve pinned joinChannel password hashing and null/empty behavior.
                command.add(CommandSingleParameter("cpw", com.github.manevolent.ts3j.util.Ts3Crypt.hashPassword(password)))
                socket.executeCommand(command)
            }
        response.complete()
    }

    fun send(
        target: TextTarget,
        body: String,
        timeoutMs: Long,
        canSubmit: () -> Boolean,
    ): TextSendOutcome {
        if (body.isBlank() || body.toByteArray(Charsets.UTF_8).size > MAX_TEXT_INPUT_BYTES || target.numericId() <= 0) {
            return TextSendOutcome.NotSent(TextNotSentReason.INVALID_INPUT)
        }
        return execute(textCommand(target, body), timeoutMs, canSubmit)
    }

    fun poke(
        target: TextTarget.Private,
        reason: String,
        timeoutMs: Long,
        canSubmit: () -> Boolean,
    ): TextSendOutcome {
        if (target.clientId <= 0 || reason.toByteArray(Charsets.UTF_8).size > MAX_POKE_INPUT_BYTES) {
            return TextSendOutcome.NotSent(TextNotSentReason.INVALID_INPUT)
        }
        return execute(pokeCommand(target.clientId, reason), timeoutMs, canSubmit)
    }

    internal fun execute(
        command: SingleCommand,
        timeoutMs: Long,
        canSubmit: () -> Boolean,
        onResponse: (Iterable<SingleCommand>) -> Unit = {
        },
    ): TextSendOutcome = runner.execute(command, timeoutMs, canSubmit, onResponse)
}

internal fun channelTextCommand(
    channelId: Int,
    body: String,
): SingleCommand = textCommand(TextTarget.Channel(channelId), body)

internal fun textCommand(
    target: TextTarget,
    body: String,
): SingleCommand =
    SingleCommand(
        "sendtextmessage",
        ProtocolRole.CLIENT,
        CommandSingleParameter("targetmode", if (target is TextTarget.Private) "1" else "2"),
        CommandSingleParameter("target", target.numericId().toString()),
        escapedMessage(body),
    )

internal fun pokeCommand(
    clientId: Int,
    reason: String,
): SingleCommand =
    SingleCommand(
        "clientpoke",
        ProtocolRole.CLIENT,
        CommandSingleParameter("clid", clientId.toString()),
        escapedMessage(reason),
    )

private fun escapedMessage(body: String): CommandSingleParameter =
    object : CommandSingleParameter("msg", body) {
        override fun toString(): String = "msg=" + Ts3String.escape(value)
    }

private fun TextTarget.numericId(): Int =
    when (this) {
        is TextTarget.Channel -> channelId
        is TextTarget.Private -> clientId
    }

internal fun classifyTextRejection(code: Int): TextRejection =
    when (code) {
        2568, 2569 -> TextRejection.PERMISSION
        in 1536..1543 -> TextRejection.INVALID_INPUT
        else -> TextRejection.SERVER
    }
