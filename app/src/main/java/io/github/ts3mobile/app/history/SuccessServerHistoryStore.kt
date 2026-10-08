package io.github.ts3mobile.app.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists the endpoints of servers that actually reached CONNECTED, newest
 * first. Only host and port are written: no nickname, password or identity
 * material ever reaches disk. The class takes a [DataStore] so it is free of
 * Android APIs and unit-testable against a real file-backed store.
 *
 * Read/write failures reach the application boundary for a visible error and
 * retry. The process-wide store uses a corruption handler for damaged files.
 */
class SuccessServerHistoryStore(private val dataStore: DataStore<Preferences>) {
    val entries: Flow<List<ServerHistoryEntry>> =
        dataStore.data
            .map { preferences -> ServerHistoryCodec.decode(preferences[historyKey]) }

    /** Records a real connection success; an invalid endpoint is ignored. */
    suspend fun recordSuccess(
        host: String,
        port: Int,
    ) {
        val entry = ServerHistoryEntry(host = host.trim(), port = port)
        if (!entry.isValid()) return
        dataStore.edit { preferences ->
            val current = ServerHistoryCodec.decode(preferences[historyKey])
            val updated = ServerHistoryPolicy.remember(current, entry)
            preferences[historyKey] = ServerHistoryCodec.encode(updated)
        }
    }

    suspend fun remove(entry: ServerHistoryEntry) {
        dataStore.edit { preferences ->
            val current = ServerHistoryCodec.decode(preferences[historyKey])
            val updated = ServerHistoryPolicy.remove(current, entry)
            preferences[historyKey] = ServerHistoryCodec.encode(updated)
        }
    }

    private companion object {
        val historyKey = stringPreferencesKey("successful_servers")
    }
}
