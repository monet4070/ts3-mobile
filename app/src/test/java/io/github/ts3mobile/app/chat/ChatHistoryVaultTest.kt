package io.github.ts3mobile.app.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher
import io.github.ts3mobile.app.crypto.PayloadKeyAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class ChatHistoryVaultTest {
    private var now = 1_800_000_000_000L
    private val account = checkNotNull(ChatHistoryAccount.parse(" Voice.Example.Com ", 9987, "fixture-one"))
    private val preferences = MemoryPreferences()
    private val keys = FixtureKeys()
    private val vault = ChatHistoryVault(preferences, KeystorePayloadCipher(keys), { now })

    @Test fun defaultOffCannotCreateCiphertextAndStopRemovesAllHistory() =
        runBlocking {
            val message = channelRecord(1)
            assertFalse(vault.enabled())
            vault.append(listOf(message))
            assertTrue(preferences.data.value.asMap().isEmpty())
            assertEquals(0, keys.generated)
            vault.setEnabled(true)
            vault.append(listOf(message))
            assertTrue(vault.enabled())
            val payload = checkNotNull(preferences.data.value[stringPreferencesKey("encrypted_messages")])
            val raw = Base64.getDecoder().decode(payload.substringAfterLast(':')).decodeToString()
            assertFalse(raw.contains(message.body))
            assertFalse(raw.contains(account.host))
            val reopened = ChatHistoryVault(preferences, KeystorePayloadCipher(keys), { now })
            assertEquals(listOf(message), reopened.read(account.ownUid))
            reopened.setEnabled(false)
            assertTrue(preferences.data.value.asMap().isEmpty())
            reopened.setEnabled(true)
            assertTrue(reopened.read(account.ownUid).isEmpty())
        }

    @Test fun identitiesPortsAddressesAndRoomTypesStayIsolatedDuringDeletion() =
        runBlocking {
            val first = channelRecord(12)
            val sameServerOtherChannel = channelRecord(13)
            val privateRoom = first.copy(conversation = first.conversation.copy(room = ChatHistoryRoom.Peer("fixture-peer")))
            val otherIdentity = withAccount(first, "voice.example.com", 9987, "fixture-two")
            val otherPort = withAccount(first, "voice.example.com", 9988, account.ownUid)
            val otherHost = withAccount(first, "other.example.com", 9987, account.ownUid)
            vault.setEnabled(true)
            vault.append(listOf(first, sameServerOtherChannel, privateRoom, otherIdentity, otherPort, otherHost))
            assertEquals(listOf(first, sameServerOtherChannel, privateRoom, otherPort, otherHost), vault.read(account.ownUid))
            assertEquals(listOf(otherIdentity), vault.read("fixture-two"))
            vault.delete(first.conversation)
            assertEquals(listOf(sameServerOtherChannel, privateRoom, otherPort, otherHost), vault.read(account.ownUid))
            assertEquals(listOf(otherIdentity), vault.read("fixture-two"))
        }

    @Test fun expirationPrunesCiphertextAndSharedBoundsUseEncodedBytes() =
        runBlocking {
            val first = channelRecord(1)
            vault.setEnabled(true)
            vault.append(listOf(first))
            now += ChatHistoryCodec.LIFETIME_MS + 1
            assertTrue(vault.read(account.ownUid).isEmpty())
            assertEquals(1, preferences.data.value.asMap().size)
            val small = (1..510).map { channelRecord(it) }
            assertEquals(small.takeLast(500), ChatHistoryCodec.retain(small, now))
            val large = small.take(70).map { it.copy(body = "😀".repeat(4_096), truncated = true) }
            val retained = ChatHistoryCodec.retain(large, now)
            assertTrue(retained.size in 1 until large.size)
            assertEquals(large.takeLast(retained.size), retained)
            val bytes = ChatHistoryCodec.encode(retained)
            assertTrue(bytes.size <= ChatHistoryCodec.MAX_BYTES)
            assertEquals(retained, ChatHistoryCodec.decode(bytes))
            assertThrows(IllegalArgumentException::class.java) { ChatHistoryCodec.decode(bytes + 0.toByte()) }
            assertThrows(IllegalArgumentException::class.java) { ChatHistoryCodec.encode(large) }
            Unit
        }

    @Test fun damagedOrUnsupportedPayloadIsKeptUntilExplicitClear() =
        runBlocking {
            val payload = stringPreferencesKey("encrypted_messages")
            vault.setEnabled(true)
            val cipher = KeystorePayloadCipher(keys)
            val incompatible = cipher.encrypt(byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0))
            preferences.edit { it[payload] = incompatible }
            assertTrue(runCatching { vault.read(account.ownUid) }.isFailure)
            assertTrue(runCatching { vault.append(listOf(channelRecord(5))) }.isFailure)
            assertEquals(incompatible, preferences.data.value[payload])
            vault.clear()
            val expected = channelRecord(6)
            vault.append(listOf(expected))
            assertEquals(listOf(expected), vault.read(account.ownUid))
        }

    private fun channelRecord(id: Int): ChatHistoryRecord =
        ChatHistoryRecord(ChatHistoryConversation(account, ChatHistoryRoom.Channel(id)), now, false, "Fixture", "Fixture text $id", false)

    private fun withAccount(
        record: ChatHistoryRecord,
        host: String,
        port: Int,
        uid: String,
    ) = record.copy(conversation = record.conversation.copy(account = checkNotNull(ChatHistoryAccount.parse(host, port, uid))))

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class FixtureKeys : PayloadKeyAccess {
        private var key: SecretKey? = null
        var generated = 0
            private set

        override fun findExistingKey(): SecretKey? = key

        override fun getOrCreateKeyForNewData(): SecretKey =
            key ?: SecretKeySpec(ByteArray(32), "AES").also {
                key = it
                generated++
            }
    }
}
