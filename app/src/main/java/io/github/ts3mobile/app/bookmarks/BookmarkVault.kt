package io.github.ts3mobile.app.bookmarks

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.bookmarksDataStore by preferencesDataStore(name = "ts3_bookmarks")

/** One independent ciphertext snapshot. Failed decryption never overwrites the file. */
internal class BookmarkVault(
    context: Context,
    private val store: DataStore<Preferences> = context.applicationContext.bookmarksDataStore,
) : BookmarkStore {
    private val payloadKey = stringPreferencesKey("encrypted_bookmarks")

    override suspend fun read(): List<ServerBookmark> = withContext(Dispatchers.IO) { decrypt(store.data.first()[payloadKey]) }

    override suspend fun save(entry: ServerBookmark) = mutate { BookmarkCodec.save(it, entry) }

    override suspend fun remove(entry: ServerBookmark) = mutate { BookmarkCodec.remove(it, entry) }

    private suspend fun mutate(transform: (List<ServerBookmark>) -> List<ServerBookmark>): List<ServerBookmark> =
        withContext(Dispatchers.IO) {
            var updated = emptyList<ServerBookmark>()
            store.edit { preferences ->
                updated = transform(decrypt(preferences[payloadKey]))
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                preferences[payloadKey] =
                    listOf(
                        "v1",
                        base64(cipher.iv),
                        base64(cipher.doFinal(BookmarkCodec.encode(updated))),
                    ).joinToString(":")
            }
            updated
        }

    private fun decrypt(payload: String?): List<ServerBookmark> {
        if (payload == null) return emptyList()
        require(payload.length <= 512 * 1024)
        val parts = payload.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == "v1")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
        return BookmarkCodec.decode(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)))
    }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build(),
            )
            generateKey()
        }
    }

    private fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEY_ALIAS = "ts3_mobile_bookmarks_v1"
    }
}
