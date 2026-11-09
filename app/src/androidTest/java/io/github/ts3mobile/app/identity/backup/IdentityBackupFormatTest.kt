package io.github.ts3mobile.app.identity.backup

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Properties

/** Synthetic data and platform primitives only; never reads the installed identity or connects. */
class IdentityBackupFormatTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val vector =
        Properties().apply {
            instrumentation.context.assets.open("identity-backup/v1.properties").reader(Charsets.UTF_8).use(::load)
        }
    private val passphrase get() = vector.getProperty("password").toCharArray()

    private fun hex(name: String) = vector.getProperty(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun androidUnicodeExportAndImportMatchIndependentNodeAndJvmBytes() {
        val random =
            object : SecureRandom() {
                var next = 0

                override fun nextBytes(bytes: ByteArray) {
                    bytes.indices.forEach { bytes[it] = next++.toByte() }
                }
            }
        val actual = IdentityBackupCodec(random).encrypt(hex("plaintextHex"), passphrase)
        assertArrayEquals(hex("containerHex"), actual)
        assertArrayEquals(hex("plaintextHex"), IdentityBackupCodec().decrypt(actual, passphrase))
        assertArrayEquals(hex("plaintextHex"), IdentityBackupCodec().decrypt(hex("maxIterationsContainerHex"), passphrase))
    }

    @Test fun portableIdentityPreservesUidOffsetAndSecurityBetweenIndependentCodecInstances() {
        val material = Ts3IdentityCodec.generate(3)
        val before = Ts3IdentityCodec.inspect(material)
        val encrypted = IdentityBackupCodec().encrypt(material.toByteArray(Charsets.UTF_8), passphrase)
        val recovered = IdentityBackupCodec().decrypt(encrypted, passphrase)
        try {
            assertEquals(before, Ts3IdentityCodec.inspect(recovered.toString(Charsets.UTF_8)))
            assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(material))
        } finally {
            recovered.fill(0)
        }
    }

    @Test fun tamperAndWrongPassphraseReturnNoPlaintextAndResourceMetadataNeverStartsKdf() {
        val changed = hex("containerHex").also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val error = assertThrows(IdentityBackupException::class.java) { IdentityBackupCodec().decrypt(changed, passphrase) }
        assertEquals(IdentityBackupFailure.AUTHENTICATION_FAILED, error.failure)
        val wrong =
            assertThrows(IdentityBackupException::class.java) {
                IdentityBackupCodec().decrypt(hex("containerHex"), "wrong fixture password".toCharArray())
            }
        assertEquals(IdentityBackupFailure.AUTHENTICATION_FAILED, wrong.failure)
        var calls = 0
        val bounded =
            IdentityBackupCodec(deriveKey = { _, _, _ ->
                calls++
                error("KDF must not run")
            })
        val malicious = hex("containerHex").also { ByteBuffer.wrap(it).putInt(20, Int.MAX_VALUE) }
        assertThrows(IdentityBackupException::class.java) { bounded.decrypt(malicious, passphrase) }
        assertEquals(0, calls)
    }

    @Test fun recordsActualPlatformKdfTimesWithoutChangingIterationStrength() {
        val report = StringBuilder("API${android.os.Build.VERSION.SDK_INT} ${android.os.Build.SUPPORTED_ABIS.first()} PBKDF2-HMAC-SHA256\n")
        listOf(IdentityBackupCodec.EXPORT_ITERATIONS, IdentityBackupCodec.MAX_IMPORT_ITERATIONS).forEach { iterations ->
            val elapsed =
                LongArray(3) {
                    val start = System.nanoTime()
                    deriveBackupKey(passphrase, ByteArray(16) { it.toByte() }, iterations)
                    System.nanoTime() - start
                }
            report.append("iterations=$iterations milliseconds=${elapsed.joinToString(",") { (it / 1_000_000.0).toString() }}\n")
        }
        File(instrumentation.targetContext.getExternalFilesDir(null), "identity-backup-kdf.txt").writeText(report.toString())
    }
}
