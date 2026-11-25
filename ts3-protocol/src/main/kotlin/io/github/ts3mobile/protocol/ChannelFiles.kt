package io.github.ts3mobile.protocol

import java.nio.file.Path

/** Read-only transport for the current channel; a staged file belongs to its caller. */
interface ChannelFiles {
    suspend fun list(directory: String): ChannelFileResult<List<ChannelFileEntry>>

    suspend fun stageDownload(
        remotePath: String,
        privateDirectory: Path,
    ): ChannelFileResult<Path>
}

data class ChannelFileEntry(val name: String, val directory: Boolean, val size: Long) {
    override fun toString(): String = "ChannelFileEntry(directory=$directory, size=$size)"
}

sealed interface ChannelFileResult<out Value> {
    data class Success<Value>(val value: Value) : ChannelFileResult<Value> {
        override fun toString(): String = "ChannelFileResult.Success(<redacted>)"
    }

    data class Failed(val reason: ChannelFileFailure) : ChannelFileResult<Nothing>
}

enum class ChannelFileFailure {
    INVALID_PATH,
    SESSION_CHANGED,
    BUSY,
    REJECTED,
    TIMED_OUT,
    RECONNECT_REQUIRED,
    INVALID_RESPONSE,
    TOO_LARGE,
    IO,
}

internal fun String.isChannelFileName(): Boolean =
    isNotBlank() && this != "." && this != ".." && length <= 255 && toByteArray(Charsets.UTF_8).size <= 255 &&
        none { it == '/' || it == '\\' || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }

internal fun String.isChannelFilePath(allowRoot: Boolean): Boolean {
    if (this == "/") return allowRoot
    if (!startsWith('/') || length > 1_024 || toByteArray(Charsets.UTF_8).size > 1_024) return false
    val parts = drop(1).split('/')
    return parts.size <= 32 && parts.all { it.isChannelFileName() }
}
