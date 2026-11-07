package io.github.ts3mobile.app.identity

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ts3mobile.app.crypto.AndroidKeystoreKeys
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.flow.first

private val Context.identityDataStore by preferencesDataStore(name = "ts3_identity")

class IdentityVault internal constructor(
    private val store: DataStore<Preferences>,
    private val cipher: KeystorePayloadCipher,
) {
    constructor(context: Context) : this(
        context.identityDataStore,
        KeystorePayloadCipher(AndroidKeystoreKeys("ts3_mobile_identity_v1")),
    )

    suspend fun getOrCreate(): String {
        val stored = store.data.first()[encryptedIdentityKey]
        if (stored != null) return cipher.decrypt(stored).toString(Charsets.UTF_8)

        val generated = Ts3IdentityCodec.generate()
        store.edit { preferences ->
            preferences[encryptedIdentityKey] = cipher.encrypt(generated.toByteArray(Charsets.UTF_8))
        }
        return generated
    }

    private companion object {
        val encryptedIdentityKey = stringPreferencesKey("encrypted_identity")
    }
}
