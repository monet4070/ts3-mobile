package io.github.ts3mobile.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal class BoundServerImages(
    private val transport: Ts3jChannelFiles,
    private val current: () -> Boolean,
) : ServerImages {
    override fun equals(other: Any?): Boolean = other is BoundServerImages && transport === other.transport

    override fun hashCode(): Int = System.identityHashCode(transport)

    override suspend fun download(image: ServerImage): ChannelFileResult<ByteArray> {
        val result = runInterruptible(Dispatchers.IO) { transport.downloadImage(image, current) }
        return if (current()) result else ChannelFileResult.Failed(ChannelFileFailure.SESSION_CHANGED)
    }
}
