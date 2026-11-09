package io.github.ts3mobile.app.identity.documents

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.identity.backup.IdentityBackupCodec
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class BackupDocumentTest {
    @Test fun actualResolverWriteCloseReopenAuthenticatesSyntheticIdentityAndDeniesUnverifiedProvider() =
        runBlocking {
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            val material = Ts3IdentityCodec.generate(0)
            val secret = "synthetic provider password".toCharArray()
            for (denyRead in listOf(false, true)) {
                val uri = Uri.parse("content://io.github.ts3mobile.app.test.backup-documents/${UUID.randomUUID()}?denyRead=$denyRead")
                try {
                    withContext(Dispatchers.IO) {
                        PreparedIdentityBackup.prepare(material, secret).use { prepared ->
                            val document = AndroidBackupDocument(resolver, uri)
                            if (denyRead) {
                                assertThrows(UnverifiedBackupDocument::class.java) { prepared.writeAndVerify(document) }
                            } else {
                                prepared.writeAndVerify(document)
                                val codec = IdentityBackupCodec()
                                val bytes = document.openRead().use(codec::readBounded)
                                val restored = codec.decrypt(bytes, secret)
                                try {
                                    assertEquals(
                                        Ts3IdentityCodec.inspect(material),
                                        Ts3IdentityCodec.inspect(restored.toString(Charsets.UTF_8)),
                                    )
                                } finally {
                                    bytes.fill(0)
                                    restored.fill(0)
                                }
                            }
                        }
                    }
                } finally {
                    resolver.delete(uri, null, null)
                }
            }
            secret.fill('\u0000')
        }
}
