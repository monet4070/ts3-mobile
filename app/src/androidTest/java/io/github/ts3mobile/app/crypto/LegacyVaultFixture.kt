package io.github.ts3mobile.app.crypto

import android.util.Base64
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.bookmarks.BookmarkCodec
import io.github.ts3mobile.app.bookmarks.BookmarkVault
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import io.github.ts3mobile.app.identity.IdentityVault
import io.github.ts3mobile.app.settings.ConnectionSettingsVault
import io.github.ts3mobile.app.settings.RememberedConnection
import io.github.ts3mobile.app.settings.RememberedConnectionCodec
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

internal enum class LegacyVaultKind(val field: String) {
    IDENTITY("encrypted_identity"),
    FORM("encrypted_form"),
    BOOKMARKS("encrypted_bookmarks"),
}

/** Scratch file and ts3_test_ alias only. Never deletes an installed Vault's alias/file. */
internal class LegacyVaultFixture(val kind: LegacyVaultKind) {
    val alias = "ts3_test_${UUID.randomUUID()}"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "legacy-${UUID.randomUUID()}.preferences_pb")
    val store = PreferenceDataStoreFactory.create(scope = scope) { file }
    val keys = AndroidKeystoreKeys(alias)
    val cipher = KeystorePayloadCipher(keys)
    private val field = stringPreferencesKey(kind.field)
    private val identity = Ts3IdentityCodec.generate(0)
    private val form = RememberedConnection("voice.example.com", "9987", "Fixture", "fixture-form-password")
    private val bookmarks =
        listOf(
            ServerBookmark(
                "fixture",
                1,
                "Fixture",
                "voice.example.com",
                defaultChannelId = 42,
                rememberPasswords = true,
                password = "fixture-server-password",
                channelPassword = "fixture-channel-password",
            ),
        )
    private val bytes get() =
        when (kind) {
            LegacyVaultKind.IDENTITY -> identity.toByteArray()
            LegacyVaultKind.FORM -> RememberedConnectionCodec.encode(form)
            LegacyVaultKind.BOOKMARKS -> BookmarkCodec.encode(bookmarks)
        }

    suspend fun seedOld(): String {
        val legacy = Cipher.getInstance("AES/GCM/NoPadding")
        legacy.init(Cipher.ENCRYPT_MODE, keys.getOrCreateKeyForNewData())
        val payload =
            listOf(
                "v1",
                Base64.encodeToString(legacy.iv, Base64.NO_WRAP),
                Base64.encodeToString(legacy.doFinal(bytes), Base64.NO_WRAP),
            ).joinToString(":")
        writePayload(payload)
        return payload
    }

    suspend fun payload(): String = checkNotNull(store.data.first()[field])

    suspend fun writePayload(payload: String) {
        store.edit { it[field] = payload }
    }

    suspend fun readCurrent() {
        when (kind) {
            LegacyVaultKind.IDENTITY -> assertTrue(identity == IdentityVault(store, cipher).getOrCreate())
            LegacyVaultKind.FORM -> assertEquals(form, ConnectionSettingsVault(store, cipher).read())
            LegacyVaultKind.BOOKMARKS -> assertEquals(bookmarks, BookmarkVault(store, cipher).read())
        }
    }

    suspend fun writeNewAndReadThroughOldAlgorithm() {
        when (kind) {
            LegacyVaultKind.IDENTITY -> {
                store.edit { it.remove(field) }
                val generated = IdentityVault(store, cipher).getOrCreate()
                assertEquals(
                    Ts3IdentityCodec.inspect(generated),
                    Ts3IdentityCodec.inspect(legacyDecrypt(payload()).toString(Charsets.UTF_8)),
                )
            }
            LegacyVaultKind.FORM -> {
                val updated = form.copy(nickname = "Updated fixture")
                ConnectionSettingsVault(store, cipher).write(updated)
                assertEquals(updated, RememberedConnectionCodec.decode(legacyDecrypt(payload())))
            }
            LegacyVaultKind.BOOKMARKS -> {
                val updated = BookmarkVault(store, cipher).save(bookmarks.single().copy(name = "Updated fixture"))
                assertEquals(updated, BookmarkCodec.decode(legacyDecrypt(payload())))
                assertEquals(2L, updated.single().revision)
            }
        }
    }

    private fun legacyDecrypt(payload: String): ByteArray {
        val parts = payload.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == "v1")
        val legacy = Cipher.getInstance("AES/GCM/NoPadding")
        legacy.init(
            Cipher.DECRYPT_MODE,
            checkNotNull(keys.findExistingKey()),
            GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)),
        )
        return legacy.doFinal(Base64.decode(parts[2], Base64.NO_WRAP))
    }

    fun deleteFixtureKey() {
        check(alias.startsWith("ts3_test_"))
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
    }

    suspend fun close() {
        scope.coroutineContext.job.cancelAndJoin()
        file.delete()
        deleteFixtureKey()
    }
}
