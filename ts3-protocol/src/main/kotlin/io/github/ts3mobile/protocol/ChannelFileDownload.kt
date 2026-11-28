package io.github.ts3mobile.protocol

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

internal enum class ChannelFileReadFailure { INVALID_SIZE, TOO_LARGE, TRUNCATED, TRAILING_BYTES, SESSION_CHANGED }

internal class ChannelFileReadException(val reason: ChannelFileReadFailure) : java.io.IOException(reason.name)

internal class ChannelFileDownload(private val maximumBytes: Long) {
    init {
        require(maximumBytes >= 0)
    }

    fun validateSize(expectedBytes: Long) {
        if (expectedBytes < 0) throw ChannelFileReadException(ChannelFileReadFailure.INVALID_SIZE)
        if (expectedBytes > maximumBytes) throw ChannelFileReadException(ChannelFileReadFailure.TOO_LARGE)
    }

    fun stageVerified(
        source: InputStream,
        privateDirectory: Path,
        expectedBytes: Long,
        sessionCurrent: () -> Boolean,
    ): Path {
        validateSize(expectedBytes)
        checkSession(sessionCurrent)
        val staged = Files.createTempFile(privateDirectory, "ts3-download-", ".part")
        try {
            Files.newOutputStream(staged).use { copyVerified(source, it, expectedBytes, sessionCurrent) }
            checkSession(sessionCurrent)
            return staged
        } catch (error: Exception) {
            try {
                Files.deleteIfExists(staged)
            } catch (cleanup: java.io.IOException) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    fun copyVerified(
        source: InputStream,
        staging: OutputStream,
        expectedBytes: Long,
        sessionCurrent: () -> Boolean,
    ) {
        validateSize(expectedBytes)
        val buffer = ByteArray(16 * 1024)
        var remaining = expectedBytes
        while (remaining > 0) {
            checkSession(sessionCurrent)
            val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            checkSession(sessionCurrent)
            if (count <= 0) throw ChannelFileReadException(ChannelFileReadFailure.TRUNCATED)
            staging.write(buffer, 0, count)
            remaining -= count
        }
        checkSession(sessionCurrent)
        if (source.read() != -1) throw ChannelFileReadException(ChannelFileReadFailure.TRAILING_BYTES)
        checkSession(sessionCurrent)
    }

    private fun checkSession(sessionCurrent: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || !sessionCurrent()) {
            throw ChannelFileReadException(ChannelFileReadFailure.SESSION_CHANGED)
        }
    }
}
