package io.github.ts3mobile.protocol

import java.io.IOException
import java.util.concurrent.CompletableFuture

internal class FileReplyFailure(val reason: ChannelFileFailure) : IOException(reason.name)

internal class Ts3jDownloadOffer(val port: Int, val size: Long, val key: String, val address: String?)

internal sealed class FileReply<Value> {
    val completion = CompletableFuture<Value>()

    fun fail(reason: ChannelFileFailure) {
        completion.completeExceptionally(FileReplyFailure(reason))
    }

    class Listing(val channelId: Int, val directory: String) : FileReply<List<ChannelFileEntry>>() {
        val entries = linkedMapOf<String, ChannelFileEntry>()
    }

    class Download(val id: Int) : FileReply<Ts3jDownloadOffer>()
}

internal class Ts3jChannelFileReplies {
    private var active: FileReply<*>? = null
    private var retired = false
    private var listUncertain = false
    private var nextId = 1

    @Synchronized
    fun listing(
        channelId: Int,
        directory: String,
    ): FileReply.Listing {
        checkIdle()
        if (listUncertain) throw FileReplyFailure(ChannelFileFailure.RECONNECT_REQUIRED)
        return FileReply.Listing(channelId, directory).also { active = it }
    }

    @Synchronized
    fun download(): FileReply.Download {
        checkIdle()
        if (nextId > 65_535) throw FileReplyFailure(ChannelFileFailure.RECONNECT_REQUIRED)
        return FileReply.Download(nextId++).also { active = it }
    }

    @Synchronized
    fun finish(
        reply: FileReply<*>,
        listMayHaveOutstandingEvents: Boolean,
    ) {
        if (active !== reply) return
        if (reply is FileReply.Listing && listMayHaveOutstandingEvents) listUncertain = true
        active = null
    }

    @Synchronized
    fun emptyDirectory(reply: FileReply.Listing): List<ChannelFileEntry> {
        if (reply.entries.isNotEmpty()) throw FileReplyFailure(ChannelFileFailure.INVALID_RESPONSE)
        return emptyList()
    }

    @Synchronized
    fun accept(
        command: String,
        fields: Map<String, String>,
    ): Boolean {
        when (command) {
            "notifyfilelist", "notifyfilelistfinished" -> (active as? FileReply.Listing)?.accept(command, fields)
            "notifystartdownload", "notifystatusfiletransfer" -> (active as? FileReply.Download)?.accept(command, fields)
            else -> return false
        }
        return true
    }

    @Synchronized
    fun close() {
        retired = true
        active?.fail(ChannelFileFailure.SESSION_CHANGED)
        active = null
    }

    private fun checkIdle() {
        if (retired) throw FileReplyFailure(ChannelFileFailure.SESSION_CHANGED)
        if (active != null) throw FileReplyFailure(ChannelFileFailure.BUSY)
    }

    private fun FileReply.Listing.accept(
        command: String,
        fields: Map<String, String>,
    ) {
        val responsePath = fields["path"]
        val samePath = responsePath == directory || (directory != "/" && responsePath == "$directory/")
        if (completion.isDone || fields["cid"]?.toIntOrNull() != channelId || !samePath) return
        if (command == "notifyfilelistfinished") {
            completion.complete(entries.values.toList())
            return
        }
        val name = fields["name"]
        val size = fields["size"]?.toLongOrNull()
        val type = fields["type"]
        if (name == null || !name.isChannelFileName() || size == null || size < 0 || type !in setOf("0", "1") ||
            name in entries || entries.size >= MAX_DIRECTORY_ENTRIES
        ) {
            fail(ChannelFileFailure.INVALID_RESPONSE)
        } else {
            entries[name] = ChannelFileEntry(name, type == "0", size)
        }
    }

    private fun FileReply.Download.accept(
        command: String,
        fields: Map<String, String>,
    ) {
        if (completion.isDone || fields["clientftfid"]?.toIntOrNull() != id) return
        if (command == "notifystatusfiletransfer") {
            if (fields["status"] != "0") fail(ChannelFileFailure.REJECTED)
            return
        }
        val port = fields["port"]?.toIntOrNull()
        val size = fields["size"]?.toLongOrNull()
        val key = fields["ftkey"]
        if (port == null || port !in 1..65_535 || size == null || size < 0 || key == null ||
            key.length !in 1..256 || key.any { it.code !in 33..126 } || fields["proto"]?.let { it != "1" } == true
        ) {
            fail(ChannelFileFailure.INVALID_RESPONSE)
        } else {
            completion.complete(Ts3jDownloadOffer(port, size, key, fields["ip"]))
        }
    }

    private companion object {
        const val MAX_DIRECTORY_ENTRIES = 512
    }
}
