package io.github.ts3mobile.app.bookmarks

import io.github.ts3mobile.protocol.ServerConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class ServerBookmark(
    val id: String,
    val revision: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 9987,
    val nickname: String = "TS3 Mobile",
    val defaultChannelId: Int? = null,
    val rememberPasswords: Boolean = false,
    val password: String = "",
    val channelPassword: String = "",
) {
    fun validate() {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,64}")) && revision >= 0)
        require(name.isNotBlank() && name.length <= 80 && host.length <= 255)
        require(toConfig().validationError() == null)
        require(defaultChannelId == null || defaultChannelId > 0)
        require(password.toByteArray().size <= 1024 && channelPassword.toByteArray().size <= 1024)
    }

    fun forStorage(): ServerBookmark = if (rememberPasswords) this else copy(password = "", channelPassword = "")

    fun toConfig() = ServerConfig(host, port, nickname, password, defaultChannelId, channelPassword).normalized()

    override fun toString() = "ServerBookmark(<redacted>)"
}

internal object BookmarkCodec {
    const val MAX_ENTRIES = 50

    fun encode(entries: List<ServerBookmark>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            require(entries.size <= MAX_ENTRIES && entries.map { it.id }.distinct().size == entries.size)
            DataOutputStream(bytes).use { out ->
                out.writeInt(1)
                out.writeInt(entries.size)
                entries.forEach { original ->
                    original.validate()
                    val entry = original.forStorage()
                    listOf(entry.id, entry.name, entry.host, entry.nickname, entry.password, entry.channelPassword).forEach(out::writeUTF)
                    out.writeLong(entry.revision)
                    out.writeInt(entry.port)
                    out.writeInt(entry.defaultChannelId ?: 0)
                    out.writeBoolean(entry.rememberPasswords)
                }
            }
            bytes.toByteArray()
        }

    fun decode(bytes: ByteArray): List<ServerBookmark> =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(bytes.size <= 256 * 1024 && input.readInt() == 1)
            val count = input.readInt()
            require(count in 0..MAX_ENTRIES)
            val entries =
                List(count) {
                    val fields = List(6) { input.readUTF() }
                    ServerBookmark(
                        id = fields[0],
                        name = fields[1],
                        host = fields[2],
                        nickname = fields[3],
                        password = fields[4],
                        channelPassword = fields[5],
                        revision = input.readLong(),
                        port = input.readInt(),
                        defaultChannelId = input.readInt().let { if (it == 0) null else it },
                        rememberPasswords = input.readBoolean(),
                    ).also { entry ->
                        entry.validate()
                        require(entry == entry.forStorage())
                    }
                }
            require(entries.map { it.id }.distinct().size == count && input.available() == 0)
            entries
        }

    fun save(
        entries: List<ServerBookmark>,
        requested: ServerBookmark,
    ): List<ServerBookmark> {
        requested.validate()
        val previous = entries.singleOrNull { it.id == requested.id }
        require((previous == null && requested.revision == 0L) || previous?.revision == requested.revision) { "Stale bookmark" }
        require(previous != null || entries.size < MAX_ENTRIES) { "Bookmark limit" }
        val updated = requested.forStorage().copy(revision = Math.addExact(requested.revision, 1))
        return if (previous == null) entries + updated else entries.map { if (it.id == updated.id) updated else it }
    }

    fun remove(
        entries: List<ServerBookmark>,
        requested: ServerBookmark,
    ): List<ServerBookmark> {
        require(entries.singleOrNull { it.id == requested.id }?.revision == requested.revision) { "Stale bookmark" }
        return entries.filterNot { it.id == requested.id }
    }
}
