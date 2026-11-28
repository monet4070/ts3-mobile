package io.github.ts3mobile.app.bookmarks

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher
import io.github.ts3mobile.app.crypto.PayloadKeyAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class BookmarkChannelSnapshotsTest {
    @Test fun storesEncryptedEndpointListsAndForgetsOnlyRequestedEndpoint() =
        runBlocking {
            val store = MemoryStore()
            val cipher = KeystorePayloadCipher(TestKeys())
            val vault = BookmarkChannelSnapshotVault(store, cipher)
            val primary = snapshot(endpoint(" VoiCe.Example.Com ", 9987), "Private room")
            val otherPort = snapshot(endpoint("voice.example.com", 9988), "Other port")
            assertNull(vault.read(primary.endpoint))
            assertTrue(store.data.value.asMap().isEmpty())

            vault.save(primary)
            vault.save(otherPort)
            val payload = store.data.value.asMap().values.single() as String
            val ciphertext = Base64.getDecoder().decode(payload.substringAfterLast(':')).decodeToString()
            assertFalse(ciphertext.contains("voice.example.com"))
            assertFalse(ciphertext.contains("Private room"))
            val reopened = BookmarkChannelSnapshotVault(store, cipher)
            assertEquals(primary, reopened.read(endpoint("voice.example.com", 9987)))
            assertEquals(otherPort, reopened.read(endpoint("VOICE.EXAMPLE.COM", 9988)))
            assertNull(reopened.read(endpoint("alt.example.com", 9987)))
            reopened.remove(primary.endpoint)
            assertNull(vault.read(primary.endpoint))
            assertEquals(otherPort, vault.read(otherPort.endpoint))
            reopened.remove(otherPort.endpoint)
            assertTrue(store.data.value.asMap().isEmpty())
        }

    @Test fun resavingAnEndpointReplacesAndKeepsItAheadOfOldestEviction() =
        runBlocking {
            val vault = BookmarkChannelSnapshotVault(MemoryStore(), KeystorePayloadCipher(TestKeys()))
            val records = (1..7).map { snapshot(endpoint("voice.example.com", 9987 + it), "Room $it") }
            records.take(6).forEach { vault.save(it) }
            val refreshed = snapshot(records.first().endpoint, "Updated")
            vault.save(refreshed)
            vault.save(records.last())
            assertEquals(refreshed, vault.read(refreshed.endpoint))
            assertNull(vault.read(records[1].endpoint))
            assertEquals(records.last(), vault.read(records.last().endpoint))
        }

    @Test fun codecBoundsUnicodeAndRejectsMalformedRecords() {
        val endpoint = endpoint("voice.example.com", 9987)
        val entries = (1..100).map { BookmarkChannelChoice(it, "房😀".repeat(90) + " (#$it)") }
        val encoded = BookmarkChannelSnapshotCodec.encode(listOf(BookmarkChannelSnapshot(endpoint, entries)))
        val decoded = BookmarkChannelSnapshotCodec.decode(encoded).single().choices
        assertEquals(entries.map { it.id }, decoded.map { it.id })
        assertTrue(decoded.all { it.path.endsWith(" (#${it.id})") && it.path.toByteArray().size <= 384 })
        assertTrue(decoded.all { '�' !in it.path })
        assertThrows(java.io.EOFException::class.java) { BookmarkChannelSnapshotCodec.decode(encoded.copyOf(encoded.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { BookmarkChannelSnapshotCodec.decode(encoded + 0.toByte()) }
        assertThrows(IllegalArgumentException::class.java) {
            BookmarkChannelSnapshotCodec.encode(listOf(BookmarkChannelSnapshot(endpoint, entries + entries.last())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookmarkChannelSnapshotCodec.encode(listOf(BookmarkChannelSnapshot(endpoint, listOf(entries[0], entries[0]))))
        }
        assertNull(BookmarkChannelEndpoint.parse("voice.example.com", 0))
        assertNull(BookmarkChannelEndpoint.parse("voice .example.com", 9987))
    }

    private fun endpoint(
        host: String,
        port: Int,
    ) = checkNotNull(BookmarkChannelEndpoint.parse(host, port))

    private fun snapshot(
        endpoint: BookmarkChannelEndpoint,
        name: String,
    ) = BookmarkChannelSnapshot(endpoint, listOf(BookmarkChannelChoice(23, "$name (#23)")))

    private class TestKeys : PayloadKeyAccess {
        private val key = SecretKeySpec(ByteArray(32), "AES")

        override fun findExistingKey(): SecretKey = key

        override fun getOrCreateKeyForNewData(): SecretKey = key
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
