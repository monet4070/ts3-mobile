package io.github.ts3mobile.app.crypto

import io.github.ts3mobile.app.bookmarks.BookmarkCodec
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import io.github.ts3mobile.app.settings.RememberedConnection
import io.github.ts3mobile.app.settings.RememberedConnectionCodec
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class KeystorePayloadCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private class Keys(var existing: SecretKey?) : PayloadKeyAccess {
        var newDataRequests = 0

        override fun findExistingKey() = existing

        override fun getOrCreateKeyForNewData(): SecretKey {
            newDataRequests++
            return checkNotNull(existing)
        }
    }

    @Test fun allThreeLegacyCodecsRoundTripOldWriteNewReadAndNewWriteOldRead() {
        val identity = Ts3IdentityCodec.generate(0)
        val form = RememberedConnection("voice.example.com", "9987", "Fixture", "fixture-password")
        val bookmarks = listOf(ServerBookmark("fixture", 1, "Fixture", "voice.example.com", defaultChannelId = 42))
        val bytes = listOf(identity.toByteArray(), RememberedConnectionCodec.encode(form), BookmarkCodec.encode(bookmarks))
        val cipher = KeystorePayloadCipher(Keys(key))
        bytes.forEach { plaintext ->
            assertTrue(plaintext.contentEquals(cipher.decrypt(legacyEncrypt(plaintext))))
            assertTrue(plaintext.contentEquals(legacyDecrypt(cipher.encrypt(plaintext))))
        }
        assertEquals(
            Ts3IdentityCodec.inspect(identity),
            Ts3IdentityCodec.inspect(cipher.decrypt(legacyEncrypt(bytes[0])).toString(Charsets.UTF_8)),
        )
        assertEquals(form, RememberedConnectionCodec.decode(legacyDecrypt(cipher.encrypt(bytes[1]))))
        assertEquals(bookmarks, BookmarkCodec.decode(legacyDecrypt(cipher.encrypt(bytes[2]))))
    }

    @Test fun missingKeyReadFailsWithoutAnyGenerationAttempt() {
        val access = Keys(null)
        assertThrows(MissingLocalCipherKey::class.java) { KeystorePayloadCipher(access).decrypt(legacyEncrypt(byteArrayOf(1))) }
        assertEquals(0, access.newDataRequests)
        assertEquals(null, access.existing)
    }

    @Test fun unknownVersionWrongKeyAndTagFailureNeverCreateOrRewriteAKey() {
        val access = Keys(key)
        val cipher = KeystorePayloadCipher(access)
        val payload = legacyEncrypt(byteArrayOf(1, 2, 3))
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(payload.replaceFirst("v1:", "v2:")) }
        access.existing = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        assertThrows(java.security.GeneralSecurityException::class.java) { cipher.decrypt(payload) }
        access.existing = key
        val parts = payload.split(':')
        val changed = Base64.getDecoder().decode(parts[2]).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(java.security.GeneralSecurityException::class.java) {
            cipher.decrypt("v1:${parts[1]}:${Base64.getEncoder().encodeToString(changed)}")
        }
        assertEquals(0, access.newDataRequests)
    }

    @Test fun newWritesKeepPaddedUnwrappedV1AndLegacyWhitespaceReads() {
        val access = Keys(key)
        val cipher = KeystorePayloadCipher(access)
        val plaintext = byteArrayOf(1)
        val written = cipher.encrypt(plaintext)
        assertEquals(1, access.newDataRequests)
        assertTrue(written.startsWith("v1:"))
        assertFalse(written.contains('\n'))
        assertEquals(12, Base64.getDecoder().decode(written.split(':')[1]).size)
        val parts = written.split(':')
        assertTrue(plaintext.contentEquals(cipher.decrypt("v1:${parts[1]}:\n${parts[2]}\n")))
    }

    // Frozen pre-C0 layout/algorithm; a deterministic nonce is for these synthetic fixtures only.
    private fun legacyEncrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12) { it.toByte() }))
        return "v1:${Base64.getEncoder().encodeToString(cipher.iv)}:${Base64.getEncoder().encodeToString(cipher.doFinal(plaintext))}"
    }

    private fun legacyDecrypt(payload: String): ByteArray {
        val parts = payload.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == "v1")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])))
        return cipher.doFinal(Base64.getDecoder().decode(parts[2]))
    }
}
