package io.github.ts3mobile.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ChannelFileDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val download = ChannelFileDownload(50_000)

    @Test fun acceptsEmptyAndMultiChunkFilesWithoutTruncating() {
        for (payload in listOf(ByteArray(0), ByteArray(50_000) { it.toByte() })) {
            val staging = ByteArrayOutputStream()
            download.copyVerified(payload.inputStream(), staging, payload.size.toLong()) { true }
            assertArrayEquals(payload, staging.toByteArray())
        }
    }

    @Test fun refusesNegativeAndOversizedDeclarationsBeforeReading() {
        val neverRead =
            object : InputStream() {
                override fun read(): Int = error("Must validate length before reading")
            }
        for ((size, reason) in listOf(-1L to ChannelFileReadFailure.INVALID_SIZE, 50_001L to ChannelFileReadFailure.TOO_LARGE)) {
            val error =
                assertThrows(ChannelFileReadException::class.java) {
                    download.copyVerified(neverRead, ByteArrayOutputStream(), size) { true }
                }
            assertEquals(reason, error.reason)
        }
    }

    @Test fun shortAndLongReadsNeverReportSuccess() {
        for ((size, reason) in listOf(2L to ChannelFileReadFailure.TRUNCATED, 0L to ChannelFileReadFailure.TRAILING_BYTES)) {
            val error =
                assertThrows(ChannelFileReadException::class.java) {
                    download.copyVerified(byteArrayOf(1).inputStream(), ByteArrayOutputStream(), size) { true }
                }
            assertEquals(reason, error.reason)
        }
    }

    @Test fun dropsTheChunkIfTheSessionChangesDuringRead() {
        val staging = ByteArrayOutputStream()
        var active = true
        val source =
            object : InputStream() {
                override fun read(): Int {
                    active = false
                    return 7
                }
            }
        val error =
            assertThrows(ChannelFileReadException::class.java) {
                download.copyVerified(source, staging, 1) { active }
            }
        assertEquals(ChannelFileReadFailure.SESSION_CHANGED, error.reason)
        assertEquals(0, staging.size())
    }

    @Test fun unsuccessfulStagingLeavesNoPartialFileAndSuccessReturnsVerifiedBytes() {
        val privateDirectory = temporary.newFolder()
        val path = privateDirectory.toPath()
        val payload = byteArrayOf(21, 34)
        assertThrows(ChannelFileReadException::class.java) {
            download.stageVerified(payload.inputStream(), path, 3) { true }
        }
        assertEquals(emptyList<String>(), privateDirectory.list().orEmpty().toList())
        val staged = download.stageVerified(payload.inputStream(), path, 2) { true }
        assertArrayEquals(payload, staged.toFile().readBytes())
    }
}
