package io.github.ts3mobile.app.crypto

import android.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class LegacyVaultCipherTest {
    @Test fun allRealVaultsReadOldCiphertextAndWriteNewCiphertextReadableByOldAlgorithm() =
        runBlocking {
            LegacyVaultKind.entries.forEach { kind ->
                val fixture = LegacyVaultFixture(kind)
                try {
                    fixture.seedOld()
                    fixture.readCurrent()
                    fixture.writeNewAndReadThroughOldAlgorithm()
                } finally {
                    fixture.close()
                }
            }
        }

    @Test fun missingKeyDoesNotCreateAliasGenerateIdentityOrRewriteAnyOriginalFile() =
        runBlocking {
            LegacyVaultKind.entries.forEach { kind ->
                val fixture = LegacyVaultFixture(kind)
                try {
                    val payload = fixture.seedOld()
                    val digest = MessageDigest.getInstance("SHA-256").digest(fixture.file.readBytes())
                    fixture.deleteFixtureKey()
                    val failure = runCatching { fixture.readCurrent() }.exceptionOrNull()
                    assertTrue(failure is MissingLocalCipherKey)
                    assertNull(fixture.keys.findExistingKey())
                    assertEquals(payload, fixture.payload())
                    assertTrue(digest.contentEquals(MessageDigest.getInstance("SHA-256").digest(fixture.file.readBytes())))
                } finally {
                    fixture.close()
                }
            }
        }

    @Test fun wrongKeyCorruptTagAndUnknownVersionRemainVisibleWithoutRewritingStorage() =
        runBlocking {
            LegacyVaultKind.entries.forEach { kind ->
                val fixture = LegacyVaultFixture(kind)
                try {
                    val original = fixture.seedOld()
                    val parts = original.split(':')
                    val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                    val corrupt = "v1:${parts[1]}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
                    fixture.writePayload(corrupt)
                    assertTrue(runCatching { fixture.readCurrent() }.exceptionOrNull() is java.security.GeneralSecurityException)
                    assertEquals(corrupt, fixture.payload())
                    val unknown = original.replaceFirst("v1:", "v2:")
                    fixture.writePayload(unknown)
                    assertTrue(runCatching { fixture.readCurrent() }.exceptionOrNull() is IllegalArgumentException)
                    assertEquals(unknown, fixture.payload())
                    fixture.writePayload(original)
                    fixture.deleteFixtureKey()
                    fixture.keys.getOrCreateKeyForNewData()
                    assertTrue(runCatching { fixture.readCurrent() }.exceptionOrNull() is java.security.GeneralSecurityException)
                    assertEquals(original, fixture.payload())
                } finally {
                    fixture.close()
                }
            }
        }
}
