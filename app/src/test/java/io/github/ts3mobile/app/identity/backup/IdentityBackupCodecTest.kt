package io.github.ts3mobile.app.identity.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.Properties

class IdentityBackupCodecTest {
    private val vector =
        Properties().apply {
            val input = checkNotNull(IdentityBackupCodecTest::class.java.getResourceAsStream("/identity-backup/v1.properties"))
            input.reader(Charsets.UTF_8).use(::load)
        }
    private val passphrase get() = vector.getProperty("password").toCharArray()
    private val expected get() = hex(vector.getProperty("containerHex"))
    private val plaintext get() = hex(vector.getProperty("plaintextHex"))

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun exactContainerMatchesIndependentOpenSslVectorAndDecryptsInAnotherInstance() {
        val random =
            object : SecureRandom() {
                var next = 0

                override fun nextBytes(bytes: ByteArray) {
                    bytes.indices.forEach { bytes[it] = next++.toByte() }
                }
            }
        val encrypted = IdentityBackupCodec(random).encrypt(plaintext, passphrase)
        assertArrayEquals(expected, encrypted)
        assertArrayEquals(plaintext, IdentityBackupCodec().decrypt(encrypted, passphrase))
    }

    @Test fun everyRandomExportIsIndependentOfLocalInstallationKeys() {
        val first = IdentityBackupCodec().encrypt(plaintext, passphrase)
        val second = IdentityBackupCodec().encrypt(plaintext, passphrase)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plaintext, IdentityBackupCodec().decrypt(first, passphrase))
        assertArrayEquals(plaintext, IdentityBackupCodec().decrypt(second, passphrase))
    }

    @Test fun maximumSupportedImportWorkFactorMatchesIndependentProviderVector() {
        assertArrayEquals(
            plaintext,
            IdentityBackupCodec().decrypt(hex(vector.getProperty("maxIterationsContainerHex")), passphrase),
        )
    }

    @Test fun wrongPasswordTagCiphertextSaltNonceAndAuthenticatedWorkFactorAllFail() {
        val codec = IdentityBackupCodec()
        assertFailure(IdentityBackupFailure.AUTHENTICATION_FAILED) { codec.decrypt(expected, "wrong fixture password".toCharArray()) }
        // Header salt/nonce and body/tag changes are authenticated; no plaintext is returned.
        listOf(28, 44, 56, expected.lastIndex).forEach { offset ->
            val changed = expected.also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertFailure(IdentityBackupFailure.AUTHENTICATION_FAILED) { codec.decrypt(changed, passphrase) }
        }
        val changedIterations = expected.also { ByteBuffer.wrap(it).putInt(20, 600_001) }
        assertFailure(IdentityBackupFailure.AUTHENTICATION_FAILED) { codec.decrypt(changedIterations, passphrase) }
    }

    @Test fun unknownMetadataOverflowAndMaliciousKdfAreRejectedBeforeDerivation() {
        var derivations = 0
        val codec =
            IdentityBackupCodec(deriveKey = { _, _, _ ->
                derivations++
                error("KDF must not run")
            })
        listOf(-1, 0, 599_999, 2_000_001, Int.MAX_VALUE).forEach { iterations ->
            val changed = expected.also { ByteBuffer.wrap(it).putInt(20, iterations) }
            assertFailure(IdentityBackupFailure.RESOURCE_LIMIT) { codec.decrypt(changed, passphrase) }
        }
        listOf(-1, 0, 65_537, Int.MAX_VALUE).forEach { length ->
            val changed = expected.also { ByteBuffer.wrap(it).putInt(24, length) }
            assertFailure(IdentityBackupFailure.RESOURCE_LIMIT) { codec.decrypt(changed, passphrase) }
        }
        assertFailure(IdentityBackupFailure.UNSUPPORTED_VERSION) {
            codec.decrypt(expected.also { ByteBuffer.wrap(it).putShort(16, 2) }, passphrase)
        }
        listOf(18, 19).forEach { offset ->
            assertFailure(IdentityBackupFailure.UNSUPPORTED_ALGORITHM) { codec.decrypt(expected.also { it[offset] = 2 }, passphrase) }
        }
        assertFailure(IdentityBackupFailure.INVALID_FORMAT) { codec.decrypt(expected.also { it[0] = 0 }, passphrase) }
        assertEquals(0, derivations)
    }

    @Test fun everyTruncationAndTrailingByteIsRejectedWithoutKdf() {
        val codec = IdentityBackupCodec(deriveKey = { _, _, _ -> error("KDF must not run") })
        expected.indices.forEach { length ->
            assertFailure(IdentityBackupFailure.INVALID_FORMAT) { codec.decrypt(expected.copyOf(length), passphrase) }
        }
        assertFailure(IdentityBackupFailure.INVALID_FORMAT) { codec.decrypt(expected + byteArrayOf(0), passphrase) }
        assertFailure(IdentityBackupFailure.RESOURCE_LIMIT) { codec.decrypt(ByteArray(IdentityBackupCodec.MAX_FILE_BYTES + 1), passphrase) }
    }

    @Test fun passphrasesPreserveExactUnicodeAndRejectShortOversizedOrMalformedInput() {
        val codec = IdentityBackupCodec()
        listOf("short".toCharArray(), CharArray(12) { ' ' }, CharArray(1_025) { 'x' }, charArrayOf('\uD800') + CharArray(20) { 'x' })
            .forEach { password -> assertFailure(IdentityBackupFailure.INVALID_PASSPHRASE) { codec.encrypt(plaintext, password) } }
        validateBackupPassphrase(("🌏".repeat(12)).toCharArray())
        assertNotEquals(
            vector.getProperty("password"),
            java.text.Normalizer.normalize(vector.getProperty("password"), java.text.Normalizer.Form.NFC),
        )
        assertFailure(IdentityBackupFailure.AUTHENTICATION_FAILED) {
            codec.decrypt(
                expected,
                java.text.Normalizer.normalize(vector.getProperty("password"), java.text.Normalizer.Form.NFC).toCharArray(),
            )
        }
    }

    @Test fun streamReadBoundsActualBytesAndMakesProgressThroughZeroReads() {
        val codec = IdentityBackupCodec()
        assertArrayEquals(expected, codec.readBounded(ByteArrayInputStream(expected)))
        var read = 0
        val infinite =
            object : InputStream() {
                override fun read() = 0.also { read++ }

                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = 0
            }
        assertFailure(IdentityBackupFailure.RESOURCE_LIMIT) { codec.readBounded(infinite) }
        assertEquals(IdentityBackupCodec.MAX_FILE_BYTES + 1, read)
        assertFailure(IdentityBackupFailure.RESOURCE_LIMIT) { codec.encrypt(ByteArray(65_537), passphrase) }
    }

    @Test fun unavailableProviderHasAFixedFailureWithoutFallbackOrSensitiveCauseText() {
        var calls = 0
        val codec =
            IdentityBackupCodec(deriveKey = { _, _, _ ->
                calls++
                throw NoSuchAlgorithmException("sensitive-fixture-provider-detail")
            })
        val error = assertThrows(IdentityBackupException::class.java) { codec.decrypt(expected, passphrase) }
        assertEquals(1, calls)
        assertEquals(IdentityBackupFailure.CRYPTO_UNAVAILABLE, error.failure)
        assertEquals(null, error.cause)
        assertFalse(error.toString().contains("sensitive-fixture-provider-detail"))
    }

    private fun assertFailure(
        failure: IdentityBackupFailure,
        operation: () -> Unit,
    ) {
        val error = assertThrows(IdentityBackupException::class.java, operation)
        assertEquals(failure, error.failure)
        assertFalse(error.message.orEmpty().contains(vector.getProperty("password")))
    }
}
