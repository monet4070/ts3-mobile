package io.github.ts3mobile.app.bookmarks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ts3mobile.app.crypto.AndroidKeystoreKeys
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.bookmarksDataStore by preferencesDataStore(name = "ts3_bookmarks")

/** One independent ciphertext snapshot. Failed decryption never overwrites the file. */
internal class BookmarkVault(
    private val store: DataStore<Preferences>,
    private val cipher: KeystorePayloadCipher,
) : BookmarkStore {
    constructor(
        context: Context,
        store: DataStore<Preferences> = context.applicationContext.bookmarksDataStore,
    ) : this(store, KeystorePayloadCipher(AndroidKeystoreKeys("ts3_mobile_bookmarks_v1")))

    private val payloadKey = stringPreferencesKey("encrypted_bookmarks")

    override suspend fun read(): List<ServerBookmark> = withContext(Dispatchers.IO) { decrypt(store.data.first()[payloadKey]) }

    override suspend fun save(entry: ServerBookmark) = mutate { BookmarkCodec.save(it, entry) }

    override suspend fun saveValidated(
        entry: ServerBookmark,
        contextValid: () -> Boolean,
    ) = mutate {
        if (!contextValid()) throw BookmarkContextChanged()
        BookmarkCodec.save(it, entry)
    }

    override suspend fun remove(entry: ServerBookmark) = mutate { BookmarkCodec.remove(it, entry) }

    private suspend fun mutate(transform: (List<ServerBookmark>) -> List<ServerBookmark>): List<ServerBookmark> =
        withContext(Dispatchers.IO) {
            var updated = emptyList<ServerBookmark>()
            store.edit { preferences ->
                updated = transform(decrypt(preferences[payloadKey]))
                preferences[payloadKey] = cipher.encrypt(BookmarkCodec.encode(updated))
            }
            updated
        }

    private fun decrypt(payload: String?): List<ServerBookmark> {
        if (payload == null) return emptyList()
        require(payload.length <= 512 * 1024)
        return BookmarkCodec.decode(cipher.decrypt(payload))
    }
}
