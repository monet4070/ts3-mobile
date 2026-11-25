package io.github.ts3mobile.app.ui

import io.github.ts3mobile.protocol.ChannelFileFailure
import io.github.ts3mobile.protocol.ChannelFileResult
import io.github.ts3mobile.protocol.ServerImage
import io.github.ts3mobile.protocol.ServerImages
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class ServerImagePreviewCacheTest {
    @Test fun deduplicatesAndNeverCarriesCachedBytesAcrossHandles() =
        runBlocking {
            val cache = ServerImagePreviewCache()
            val key = checkNotNull(ServerImage.icon("1000"))
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val first =
                FakeImages {
                    started.complete(Unit)
                    finish.await()
                    ChannelFileResult.Success(previewPng())
                }
            val second = FakeImages { ChannelFileResult.Success(previewPng(2)) }
            val pending = async { cache.load(key) { first } }
            started.await()
            val duplicate = async { cache.load(key) { first } }
            finish.complete(Unit)
            assertEquals(1, (pending.await() as ImagePreviewResult.Ready).bitmap.width)
            assertEquals(1, (duplicate.await() as ImagePreviewResult.Ready).bitmap.width)
            assertEquals(1, first.requests)
            assertEquals(2, (cache.load(key) { second } as ImagePreviewResult.Ready).bitmap.width)
            assertEquals(1, second.requests)
        }

    @Test fun rejectsOverlargeImageBoundsAndCoolsFailuresButNotBusyTransfers() =
        runBlocking {
            var time = 0L
            val cache = ServerImagePreviewCache { time }
            val key = checkNotNull(ServerImage.icon("1000"))
            var reply: ChannelFileResult<ByteArray> = ChannelFileResult.Failed(ChannelFileFailure.BUSY)
            val files = FakeImages { reply }
            assertEquals(ImagePreviewResult.Busy, cache.load(key) { files })
            reply = ChannelFileResult.Success(previewPng(2049))
            assertEquals(ImagePreviewResult.Unavailable, cache.load(key) { files })
            reply = ChannelFileResult.Success(previewPng(1024))
            assertEquals(ImagePreviewResult.Unavailable, cache.load(key) { files })
            assertEquals(2, files.requests)
            time = TimeUnit.SECONDS.toNanos(11)
            assertEquals(256, (cache.load(key) { files } as ImagePreviewResult.Ready).bitmap.width)
            assertEquals(3, files.requests)
        }

    private class FakeImages(private val response: suspend () -> ChannelFileResult<ByteArray>) : ServerImages {
        var requests = 0

        override suspend fun download(image: ServerImage): ChannelFileResult<ByteArray> {
            requests++
            return response()
        }
    }
}
