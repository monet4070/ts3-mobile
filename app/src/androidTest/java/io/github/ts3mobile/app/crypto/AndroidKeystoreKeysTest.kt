package io.github.ts3mobile.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.InvalidKeyException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.UUID

class AndroidKeystoreKeysTest {
    @Test fun existingLookupNeverCreatesAndExplicitNewDataGenerationCanBeReopened() {
        val alias = "ts3_test_${UUID.randomUUID()}"
        try {
            val keys = AndroidKeystoreKeys(alias)
            assertNull(keys.findExistingKey())
            assertNotNull(keys.getOrCreateKeyForNewData())
            assertNotNull(AndroidKeystoreKeys(alias).findExistingKey())
        } finally {
            removeFixture(alias)
        }
    }

    @Test fun unusableExistingAliasIsNeverReplacedByNewAesKey() {
        val alias = "ts3_test_${UUID.randomUUID()}"
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build(),
                )
            }.generateKeyPair()
            val keys = AndroidKeystoreKeys(alias)
            assertThrows(InvalidKeyException::class.java) { keys.findExistingKey() }
            assertThrows(InvalidKeyException::class.java) { keys.getOrCreateKeyForNewData() }
            assertEquals("RSA", KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null).algorithm)
        } finally {
            removeFixture(alias)
        }
    }

    private fun removeFixture(alias: String) {
        check(alias.startsWith("ts3_test_"))
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
    }
}
