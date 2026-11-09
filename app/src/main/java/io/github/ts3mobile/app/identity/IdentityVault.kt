package io.github.ts3mobile.app.identity

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.ts3mobile.app.crypto.AndroidKeystoreKeys
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher

private val Context.identityDataStore by preferencesDataStore(name = "ts3_identity")

class IdentityVault internal constructor(
    store: DataStore<Preferences>,
    cipher: KeystorePayloadCipher,
    hasPersistedState: () -> Boolean = { false },
) {
    constructor(context: Context) : this(
        context.identityDataStore,
        KeystorePayloadCipher(AndroidKeystoreKeys("ts3_mobile_identity_v1")),
        { context.preferencesDataStoreFile("ts3_identity").exists() },
    )

    internal val storage = IdentityStore(store, cipher, hasPersistedState)

    suspend fun getOrCreate(): String = storage.getOrCreate()
}
