package io.github.ts3mobile.app.identity

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.ts3mobile.app.crypto.AndroidKeystoreKeys
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher

internal const val IDENTITY_DATA_STORE_NAME = "ts3_identity"

private val Context.identityDataStore by preferencesDataStore(name = IDENTITY_DATA_STORE_NAME)

class IdentityVault internal constructor(
    store: DataStore<Preferences>,
    cipher: KeystorePayloadCipher,
    hasPersistedState: () -> Boolean = { false },
) {
    constructor(context: Context) : this(
        context.identityDataStore,
        KeystorePayloadCipher(AndroidKeystoreKeys("ts3_mobile_identity_v1")),
        { context.preferencesDataStoreFile(IDENTITY_DATA_STORE_NAME).exists() },
    )

    internal val storage = IdentityStore(store, cipher, hasPersistedState)

    suspend fun getOrCreate(): String = storage.getOrCreate()
}
