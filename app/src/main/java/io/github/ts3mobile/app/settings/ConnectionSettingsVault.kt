package io.github.ts3mobile.app.settings

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

// The delegate owns one serialized DataStore per process; all form fields are ciphertext.
private val Context.connectionSettingsDataStore by preferencesDataStore(name = "ts3_connection_settings")

class ConnectionSettingsVault internal constructor(
    private val store: DataStore<Preferences>,
    private val cipher: KeystorePayloadCipher,
) {
    constructor(context: Context) : this(
        context.applicationContext.connectionSettingsDataStore,
        KeystorePayloadCipher(AndroidKeystoreKeys("ts3_mobile_connection_settings_v1")),
    )

    suspend fun read(): RememberedConnection? =
        withContext(Dispatchers.IO) {
            val payload = store.data.first()[payloadKey] ?: return@withContext null
            RememberedConnectionCodec.decode(cipher.decrypt(payload))
        }

    suspend fun write(value: RememberedConnection) =
        withContext(Dispatchers.IO) {
            store.edit { preferences ->
                preferences[payloadKey] = cipher.encrypt(RememberedConnectionCodec.encode(value))
            }
            Unit
        }

    private companion object {
        val payloadKey = stringPreferencesKey("encrypted_form")
    }
}
