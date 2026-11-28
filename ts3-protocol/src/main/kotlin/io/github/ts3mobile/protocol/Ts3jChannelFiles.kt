package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.util.Ts3String
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal class Ts3jChannelFiles(
    private val replies: Ts3jChannelFileReplies,
    private val peerAddress: () -> InetAddress?,
    private val execute: (SingleCommand, Long, () -> Boolean) -> TextSendOutcome,
    private val transfer: Ts3jChannelFileTcp = Ts3jChannelFileTcp(),
    private val commandTimeoutMs: Long = 5_000,
) {
    private val busy = AtomicBoolean(false)

    fun list(
        channelId: Int,
        path: String,
        current: () -> Boolean,
    ): ChannelFileResult<List<ChannelFileEntry>> =
        operation(channelId > 0 && path.isChannelFilePath(allowRoot = true), current) {
            val reply = replies.listing(channelId, path)
            var mayHaveOutstandingEvents = true
            try {
                val command = command("ftgetfilelist", channelId, "path" to path)
                val start = System.nanoTime()
                val outcome = execute(command, commandTimeoutMs, current)
                when (outcome) {
                    TextSendOutcome.Accepted -> await(reply, start).also { mayHaveOutstandingEvents = false }
                    is TextSendOutcome.Rejected -> {
                        val entries = if (outcome.errorCode == EMPTY_DIRECTORY) replies.emptyDirectory(reply) else null
                        mayHaveOutstandingEvents = false
                        entries ?: throw FileReplyFailure(ChannelFileFailure.REJECTED)
                    }
                    is TextSendOutcome.NotSent -> {
                        mayHaveOutstandingEvents = false
                        throw FileReplyFailure(outcome.failureReason())
                    }
                    else -> throw FileReplyFailure(outcome.failureReason())
                }
            } finally {
                replies.finish(reply, mayHaveOutstandingEvents)
            }
        }

    fun download(
        channelId: Int,
        path: String,
        stagingDirectory: Path,
        current: () -> Boolean,
    ): ChannelFileResult<Path> =
        operation(channelId > 0 && path.isChannelFilePath(allowRoot = false), current) {
            requestDownload(channelId, path, current) { peer, offer ->
                transfer.stage(peer, offer, stagingDirectory, current)
            }
        }

    fun downloadImage(
        image: ServerImage,
        current: () -> Boolean,
    ): ChannelFileResult<ByteArray> =
        operation(validPath = true, current) {
            requestDownload(0, image.path, current) { peer, offer ->
                val bytes = transfer.readImage(peer, offer, current)
                if (!image.matchesVersion(bytes)) throw FileReplyFailure(ChannelFileFailure.INVALID_RESPONSE)
                bytes
            }
        }

    private fun <Value> requestDownload(
        channelId: Int,
        path: String,
        current: () -> Boolean,
        consume: (InetAddress, Ts3jDownloadOffer) -> Value,
    ): Value {
        val peer = peerAddress() ?: throw FileReplyFailure(ChannelFileFailure.SESSION_CHANGED)
        val reply = replies.download()
        try {
            val command =
                command(
                    "ftinitdownload",
                    channelId,
                    "name" to path,
                    "clientftfid" to reply.id.toString(),
                    "seekpos" to "0",
                    "proto" to "1",
                )
            val start = System.nanoTime()
            val outcome = execute(command, commandTimeoutMs, current)
            if (outcome != TextSendOutcome.Accepted) throw FileReplyFailure(outcome.failureReason())
            return consume(peer, await(reply, start))
        } finally {
            replies.finish(reply, listMayHaveOutstandingEvents = false)
        }
    }

    fun close() {
        replies.close()
        transfer.close()
    }

    private fun <Value> operation(
        validPath: Boolean,
        current: () -> Boolean,
        action: () -> Value,
    ): ChannelFileResult<Value> {
        if (!validPath) return ChannelFileResult.Failed(ChannelFileFailure.INVALID_PATH)
        if (!current()) return ChannelFileResult.Failed(ChannelFileFailure.SESSION_CHANGED)
        if (!busy.compareAndSet(false, true)) return ChannelFileResult.Failed(ChannelFileFailure.BUSY)
        return try {
            ChannelFileResult.Success(action())
        } catch (error: FileReplyFailure) {
            ChannelFileResult.Failed(error.reason)
        } catch (error: ChannelFileReadException) {
            ChannelFileResult.Failed(
                when (error.reason) {
                    ChannelFileReadFailure.SESSION_CHANGED -> ChannelFileFailure.SESSION_CHANGED
                    ChannelFileReadFailure.TOO_LARGE -> ChannelFileFailure.TOO_LARGE
                    else -> ChannelFileFailure.INVALID_RESPONSE
                },
            )
        } catch (_: TimeoutException) {
            ChannelFileResult.Failed(ChannelFileFailure.TIMED_OUT)
        } catch (_: java.net.SocketTimeoutException) {
            ChannelFileResult.Failed(ChannelFileFailure.TIMED_OUT)
        } catch (_: IOException) {
            ChannelFileResult.Failed(if (current()) ChannelFileFailure.IO else ChannelFileFailure.SESSION_CHANGED)
        } finally {
            busy.set(false)
        }
    }

    private fun <Value> await(
        reply: FileReply<Value>,
        started: Long,
    ): Value {
        val remaining = TimeUnit.MILLISECONDS.toNanos(commandTimeoutMs) - (System.nanoTime() - started)
        return try {
            reply.completion.get(remaining.coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } catch (error: ExecutionException) {
            throw (error.cause as? FileReplyFailure ?: FileReplyFailure(ChannelFileFailure.INVALID_RESPONSE))
        }
    }

    private fun command(
        name: String,
        channelId: Int,
        vararg fields: Pair<String, String>,
    ): SingleCommand =
        SingleCommand(
            name,
            ProtocolRole.CLIENT,
            CommandSingleParameter("cid", channelId.toString()),
            CommandSingleParameter("cpw", ""),
        ).apply {
            for ((key, content) in fields) {
                add(
                    object : CommandSingleParameter(key, content) {
                        override fun toString(): String = key + "=" + Ts3String.escape(value)
                    },
                )
            }
        }

    private fun TextSendOutcome.failureReason(): ChannelFileFailure =
        when (this) {
            is TextSendOutcome.Rejected -> ChannelFileFailure.REJECTED
            is TextSendOutcome.NotSent ->
                if (reason == TextNotSentReason.BUSY) ChannelFileFailure.BUSY else ChannelFileFailure.SESSION_CHANGED
            else -> ChannelFileFailure.TIMED_OUT
        }

    private companion object {
        const val EMPTY_DIRECTORY = 1281
    }
}
