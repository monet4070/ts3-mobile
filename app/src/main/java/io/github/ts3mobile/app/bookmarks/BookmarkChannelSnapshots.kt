package io.github.ts3mobile.app.bookmarks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ts3mobile.app.crypto.AndroidKeystoreKeys
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher
import io.github.ts3mobile.protocol.boundedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Locale

private val Context.channelSnapshotDataStore by preferencesDataStore(name = "ts3_bookmark_channels")

internal data class BookmarkChannelEndpoint private constructor(val host: String, val port: Int) {
    override fun toString(): String = "BookmarkChannelEndpoint(<redacted>)"

    companion object {
        fun parse(
            host: String,
            port: Int,
        ): BookmarkChannelEndpoint? {
            val canonical = host.trim().lowercase(Locale.ROOT)
            if (port !in 1..65_535 || canonical.length !in 1..255 || canonical.any { it.isWhitespace() || it.isISOControl() }) return null
            return BookmarkChannelEndpoint(canonical, port)
        }
    }
}

internal data class BookmarkChannelSnapshot(val endpoint: BookmarkChannelEndpoint, val choices: List<BookmarkChannelChoice>) {
    override fun toString(): String = "BookmarkChannelSnapshot(count=${choices.size})"
}

internal interface BookmarkChannelSnapshotStore {
    suspend fun read(endpoint: BookmarkChannelEndpoint): BookmarkChannelSnapshot?

    suspend fun save(snapshot: BookmarkChannelSnapshot)

    suspend fun remove(endpoint: BookmarkChannelEndpoint)
}

internal class BookmarkChannelSnapshotVault(
    private val store: DataStore<Preferences>,
    private val cipher: KeystorePayloadCipher,
) : BookmarkChannelSnapshotStore {
    constructor(context: Context) : this(
        context.applicationContext.channelSnapshotDataStore,
        KeystorePayloadCipher(AndroidKeystoreKeys("ts3_mobile_bookmark_channels_v1")),
    )

    override suspend fun read(endpoint: BookmarkChannelEndpoint): BookmarkChannelSnapshot? =
        withContext(Dispatchers.IO) {
            decode(store.data.first()[PAYLOAD]).firstOrNull { it.endpoint == endpoint }
        }

    override suspend fun save(snapshot: BookmarkChannelSnapshot) =
        update { previous ->
            require(snapshot.choices.isNotEmpty())
            (listOf(snapshot) + previous.filterNot { it.endpoint == snapshot.endpoint }).take(BookmarkChannelSnapshotCodec.MAX_ENDPOINTS)
        }

    override suspend fun remove(endpoint: BookmarkChannelEndpoint) = update { previous -> previous.filterNot { it.endpoint == endpoint } }

    private suspend fun update(transform: (List<BookmarkChannelSnapshot>) -> List<BookmarkChannelSnapshot>) {
        withContext(Dispatchers.IO) {
            store.edit { preferences ->
                val updated = transform(decode(preferences[PAYLOAD]))
                if (updated.isEmpty()) {
                    preferences.remove(PAYLOAD)
                } else {
                    val bytes = BookmarkChannelSnapshotCodec.encode(updated)
                    preferences[PAYLOAD] =
                        try {
                            cipher.encrypt(bytes)
                        } finally {
                            bytes.fill(0)
                        }
                }
            }
        }
    }

    private fun decode(payload: String?): List<BookmarkChannelSnapshot> {
        if (payload == null) return emptyList()
        require(payload.length <= 512 * 1024)
        val bytes = cipher.decrypt(payload)
        return try {
            BookmarkChannelSnapshotCodec.decode(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        val PAYLOAD = stringPreferencesKey("encrypted_channel_snapshots")
    }
}

internal object BookmarkChannelSnapshotCodec {
    const val MAX_ENDPOINTS = 6
    private const val MAX_CHOICES = 100
    private const val MAX_PATH_BYTES = 384
    private const val VERSION = 1

    fun encode(snapshots: List<BookmarkChannelSnapshot>): ByteArray {
        require(snapshots.size <= MAX_ENDPOINTS && snapshots.map { it.endpoint }.distinct().size == snapshots.size)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeInt(snapshots.size)
            for ((endpoint, choices) in snapshots) {
                require(choices.size in 1..MAX_CHOICES && choices.map { it.id }.distinct().size == choices.size)
                out.writeUTF(endpoint.host)
                out.writeInt(endpoint.port)
                out.writeInt(choices.size)
                for ((id, path) in choices) {
                    require(id > 0 && path.isNotBlank())
                    out.writeInt(id)
                    val suffix = " (#$id)"
                    out.writeUTF(path.removeSuffix(suffix).boundedText(MAX_PATH_BYTES - suffix.length) + suffix)
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): List<BookmarkChannelSnapshot> =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(bytes.size <= 384 * 1024 && input.readInt() == VERSION)
            val count = input.readInt().also { require(it in 0..MAX_ENDPOINTS) }
            List(count) {
                val endpoint = checkNotNull(BookmarkChannelEndpoint.parse(input.readUTF(), input.readInt()))
                val size = input.readInt().also { require(it in 1..MAX_CHOICES) }
                val choices =
                    List(size) {
                        val id = input.readInt().also { require(it > 0) }
                        val path =
                            input.readUTF().also {
                                require(
                                    it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_PATH_BYTES,
                                )
                            }
                        BookmarkChannelChoice(id, path)
                    }
                require(choices.map { it.id }.distinct().size == choices.size)
                BookmarkChannelSnapshot(endpoint, choices)
            }.also { snapshots ->
                require(snapshots.map { it.endpoint }.distinct().size == snapshots.size && input.read() == -1)
            }
        }
}
