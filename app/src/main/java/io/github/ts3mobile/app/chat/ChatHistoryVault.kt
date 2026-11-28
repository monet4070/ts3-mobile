package io.github.ts3mobile.app.chat

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ts3mobile.app.crypto.AndroidKeystoreKeys
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.chatHistoryPreferences by preferencesDataStore(name = "ts3_chat_history")

internal interface ChatHistoryStore {
    suspend fun enabled(): Boolean

    suspend fun recover(): Boolean

    suspend fun setEnabled(enabled: Boolean)

    suspend fun append(records: List<ChatHistoryRecord>)

    suspend fun read(ownUid: String): List<ChatHistoryRecord>

    suspend fun delete(conversation: ChatHistoryConversation)

    suspend fun clear()
}

internal class ChatHistoryVault(
    private val store: DataStore<Preferences>,
    private val cipher: KeystorePayloadCipher,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChatHistoryStore {
    constructor(context: Context) : this(
        context.applicationContext.chatHistoryPreferences,
        KeystorePayloadCipher(AndroidKeystoreKeys("ts3_mobile_chat_history_v1")),
    )

    override suspend fun enabled(): Boolean = withContext(dispatcher) { store.data.first()[ENABLED] == true }

    override suspend fun recover(): Boolean =
        withContext(dispatcher) {
            val updated =
                store.edit { preferences ->
                    if (preferences[ENABLED] == true) {
                        preferences[PAYLOAD] = encrypt(decode(preferences[PAYLOAD]))
                    }
                }
            updated[ENABLED] == true
        }

    override suspend fun setEnabled(enabled: Boolean) {
        withContext(dispatcher) {
            store.edit {
                if (enabled) {
                    it[ENABLED] = true
                } else {
                    it.remove(ENABLED)
                    it.remove(PAYLOAD)
                }
            }
        }
    }

    override suspend fun append(records: List<ChatHistoryRecord>) {
        edit { it + records }
    }

    override suspend fun read(ownUid: String): List<ChatHistoryRecord> = edit { it }.filter { it.conversation.account.ownUid == ownUid }

    override suspend fun delete(conversation: ChatHistoryConversation) {
        edit { it.filterNot { record -> record.conversation == conversation } }
    }

    override suspend fun clear() {
        withContext(dispatcher) { store.edit { it.remove(PAYLOAD) } }
    }

    private suspend fun edit(transform: (List<ChatHistoryRecord>) -> List<ChatHistoryRecord>): List<ChatHistoryRecord> =
        withContext(dispatcher) {
            var retained = emptyList<ChatHistoryRecord>()
            store.edit update@{ preferences ->
                if (preferences[ENABLED] != true) return@update
                val previous = decode(preferences[PAYLOAD])
                retained = ChatHistoryCodec.retain(transform(previous), clockMs())
                if (retained != previous) {
                    if (retained.isEmpty()) {
                        preferences.remove(PAYLOAD)
                    } else {
                        preferences[PAYLOAD] = encrypt(retained)
                    }
                }
            }
            retained
        }

    private fun encrypt(records: List<ChatHistoryRecord>): String {
        val bytes = ChatHistoryCodec.encode(records)
        return try {
            cipher.encrypt(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun decode(payload: String?): List<ChatHistoryRecord> {
        if (payload == null) return emptyList()
        require(payload.length <= ChatHistoryCodec.MAX_BYTES * 4 / 3 + 128)
        val bytes = cipher.decrypt(payload)
        return try {
            ChatHistoryCodec.decode(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val PAYLOAD = stringPreferencesKey("encrypted_messages")
    }
}
