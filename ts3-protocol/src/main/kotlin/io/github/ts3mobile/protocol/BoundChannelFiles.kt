package io.github.ts3mobile.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

internal class BoundChannelFiles(
    private val transport: Ts3jChannelFiles,
    private val channelId: Int,
    private val current: () -> Boolean,
) : ChannelFiles {
    override suspend fun list(directory: String): ChannelFileResult<List<ChannelFileEntry>> {
        val result = runInterruptible(Dispatchers.IO) { transport.list(channelId, directory, current) }
        return if (current()) result else ChannelFileResult.Failed(ChannelFileFailure.SESSION_CHANGED)
    }

    override suspend fun stageDownload(
        remotePath: String,
        privateDirectory: Path,
    ): ChannelFileResult<Path> {
        val staged = AtomicReference<Path?>()
        try {
            val result =
                runInterruptible(Dispatchers.IO) {
                    transport.download(channelId, remotePath, privateDirectory, current).also {
                        if (it is ChannelFileResult.Success) staged.set(it.value)
                    }
                }
            if (!current()) return ChannelFileResult.Failed(ChannelFileFailure.SESSION_CHANGED)
            staged.set(null)
            return result
        } finally {
            staged.get()?.let {
                try {
                    Files.deleteIfExists(it)
                } catch (_: IOException) {
                    System.err.println("TS3_FILE: discarding a staged download failed")
                }
            }
        }
    }
}
