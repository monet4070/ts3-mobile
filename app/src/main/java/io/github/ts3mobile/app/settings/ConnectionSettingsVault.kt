package io.github.ts3mobile.app.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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

// The delegate owns one serialized DataStore per process; all form fields are ciphertext.
private val Context.connectionSettingsDataStore by preferencesDataStore(name = "ts3_connection_settings")

class ConnectionSettingsVault(context: Context) {
    private val store = context.applicationContext.connectionSettingsDataStore

    suspend fun read(): RememberedConnection? =
        withContext(Dispatchers.IO) {
            val payload = store.data.first()[payloadKey] ?: return@withContext null
            val parts = payload.split(':', limit = 3)
            require(parts.size == 3 && parts[0] == "v1") { "Unsupported encrypted connection settings" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
            RememberedConnectionCodec.decode(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)))
        }

    suspend fun write(value: RememberedConnection) =
        withContext(Dispatchers.IO) {
            store.edit { preferences ->
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key())
                val encrypted = cipher.doFinal(RememberedConnectionCodec.encode(value))
                preferences[payloadKey] = listOf("v1", base64(cipher.iv), base64(encrypted)).joinToString(":")
            }
            Unit
        }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        val payloadKey = stringPreferencesKey("encrypted_form")
        const val KEY_ALIAS = "ts3_mobile_connection_settings_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
