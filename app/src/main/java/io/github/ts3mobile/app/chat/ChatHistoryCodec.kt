package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.MAX_TEXT_EVENT_BYTES
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object ChatHistoryCodec {
    const val MAX_RECORDS = 500
    const val MAX_BYTES = 512 * 1_024
    const val LIFETIME_MS = 7 * 24 * 60 * 60 * 1_000L
    private const val HOST_BYTES = 3 * 255
    private const val NAME_BYTES = 256
    private const val VERSION = 1
    private const val HEADER_BYTES = 2 * Int.SIZE_BYTES

    fun retain(
        records: List<ChatHistoryRecord>,
        nowMs: Long,
    ): List<ChatHistoryRecord> {
        val kept = mutableListOf<ChatHistoryRecord>()
        var bytes = HEADER_BYTES
        for (record in records.asReversed()) {
            if (record.receivedAtMs < nowMs - LIFETIME_MS) continue
            if (kept.size == MAX_RECORDS) break
            bytes += record.encodedSize()
            if (bytes > MAX_BYTES) break
            kept += record
        }
        return kept.asReversed()
    }

    fun encode(records: List<ChatHistoryRecord>): ByteArray {
        require(records.size <= MAX_RECORDS)
        val size = HEADER_BYTES + records.sumOf { it.encodedSize() }
        require(size <= MAX_BYTES)
        val bytes = ByteArrayOutputStream(size)
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeInt(records.size)
            records.forEach { record -> out.write(record) }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): List<ChatHistoryRecord> =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(bytes.size <= MAX_BYTES && input.readInt() == VERSION)
            val size = input.readInt().also { require(it in 0..MAX_RECORDS) }
            List(size) { input.readRecord() }.also { require(input.read() == -1) }
        }

    private fun ChatHistoryRecord.encodedSize(): Int {
        val account = conversation.account
        require(receivedAtMs > 0 && ChatHistoryAccount.parse(account.host, account.port, account.ownUid) == account)
        val targetBytes =
            when (val target = conversation.room) {
                is ChatHistoryRoom.Channel -> Int.SIZE_BYTES.also { require(target.id > 0) }
                is ChatHistoryRoom.Peer -> checkNotNull(target.uid.historyUid()).serializedSize(NAME_BYTES)
            }
        val fixedBytes = Int.SIZE_BYTES + Long.SIZE_BYTES + 2 * Byte.SIZE_BYTES
        return fixedBytes + account.host.serializedSize(HOST_BYTES) + account.ownUid.serializedSize(NAME_BYTES) + targetBytes +
            sender.orEmpty().serializedSize(NAME_BYTES) + body.serializedSize(MAX_TEXT_EVENT_BYTES)
    }

    private fun DataOutputStream.write(record: ChatHistoryRecord) {
        val account = record.conversation.account
        writeString(account.host)
        writeInt(account.port)
        writeString(account.ownUid)
        when (val room = record.conversation.room) {
            is ChatHistoryRoom.Channel -> {
                writeByte(0)
                writeInt(room.id)
            }
            is ChatHistoryRoom.Peer -> {
                writeByte(1)
                writeString(room.uid)
            }
        }
        writeLong(record.receivedAtMs)
        writeByte((if (record.outgoing) 1 else 0) or (if (record.truncated) 2 else 0))
        writeString(record.sender.orEmpty())
        writeString(record.body)
    }

    private fun DataInputStream.readRecord(): ChatHistoryRecord {
        val host = readString(HOST_BYTES)
        val account = checkNotNull(ChatHistoryAccount.parse(host, readInt(), readString(NAME_BYTES)))
        require(account.host == host)
        val room =
            when (readUnsignedByte()) {
                0 -> ChatHistoryRoom.Channel(readInt().also { require(it > 0) })
                1 -> ChatHistoryRoom.Peer(checkNotNull(readString(NAME_BYTES).historyUid()))
                else -> error("Unknown history room")
            }
        val time = readLong().also { require(it > 0) }
        val flags = readUnsignedByte().also { require(it in 0..3) }
        val sender = readString(NAME_BYTES).ifEmpty { null }
        val body = readString(MAX_TEXT_EVENT_BYTES)
        return ChatHistoryRecord(ChatHistoryConversation(account, room), time, flags and 1 != 0, sender, body, flags and 2 != 0)
    }

    private fun String.serializedSize(limit: Int): Int = Int.SIZE_BYTES + toByteArray().size.also { require(it <= limit) }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray()
        try {
            writeInt(bytes.size)
            write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataInputStream.readString(limit: Int): String {
        val size = readInt().also { require(it in 0..limit && it <= available()) }
        val bytes = ByteArray(size)
        return try {
            readFully(bytes)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }
}
