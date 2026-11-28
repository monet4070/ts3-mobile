package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.protocol.ChannelFileFailure
import io.github.ts3mobile.protocol.ChannelFileResult
import io.github.ts3mobile.protocol.ServerImage
import io.github.ts3mobile.protocol.ServerImages
import io.github.ts3mobile.protocol.SessionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal sealed interface ServerImageTarget {
    data object Server : ServerImageTarget

    data class Channel(val id: Int) : ServerImageTarget

    data class Participant(val id: Int) : ServerImageTarget

    fun image(snapshot: SessionSnapshot): ServerImage? =
        when (this) {
            Server -> snapshot.serverIcon
            is Channel -> snapshot.channels.firstOrNull { it.id == id }?.icon
            is Participant -> snapshot.participants.firstOrNull { it.id == id }?.avatar
        }
}

internal class ServerImagePreviewScope(private val handle: () -> ServerImages?) {
    private data class Selection(val target: ServerImageTarget, val image: ServerImage)

    private val cache = ServerImagePreviewCache()
    private var selection by mutableStateOf<Selection?>(null)

    fun open(
        target: ServerImageTarget,
        image: ServerImage,
    ) {
        selection = Selection(target, image)
    }

    suspend fun loadAvatar(image: ServerImage.Avatar): Bitmap? = (cache.load(image, handle) as? ImagePreviewResult.Ready)?.bitmap

    @Composable
    fun Show(snapshot: SessionSnapshot) {
        val chosen = selection ?: return
        if (chosen.target.image(snapshot) != chosen.image) {
            LaunchedEffect(chosen) { if (selection == chosen) selection = null }
            return
        }
        var result by remember(chosen) { mutableStateOf<ImagePreviewResult?>(null) }
        LaunchedEffect(chosen) { result = cache.load(chosen.image, handle) }
        val title = imagePreviewTitle(chosen.target)
        InputDialog(
            onDismissRequest = { selection = null },
            title = { FeatureTitle(UiIcon.Image, title) },
            text = {
                when (val current = result) {
                    null -> FeatureNotice(UiIcon.Refresh, stringResource(R.string.server_image_loading))
                    is ImagePreviewResult.Ready ->
                        Panel(Modifier.fillMaxWidth()) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                Image(
                                    remember(current) { current.bitmap.asImageBitmap() },
                                    title,
                                    Modifier.size(176.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    ImagePreviewResult.Busy -> FeatureNotice(UiIcon.Info, stringResource(R.string.server_image_busy))
                    ImagePreviewResult.Unavailable ->
                        FeatureNotice(
                            UiIcon.Info,
                            stringResource(R.string.server_image_unavailable),
                            error = true,
                        )
                }
            },
            confirmButton = { FeatureAction(UiIcon.Close, stringResource(R.string.action_close), { selection = null }) },
        )
    }
}

@Composable
internal fun ServerImagePreviewButton(
    target: ServerImageTarget,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        AppIcon(UiIcon.Image, imagePreviewTitle(target), Modifier.size(20.dp))
    }
}

@Composable
private fun imagePreviewTitle(target: ServerImageTarget): String =
    stringResource(
        when (target) {
            ServerImageTarget.Server -> R.string.server_image_server_icon
            is ServerImageTarget.Channel -> R.string.server_image_channel_icon
            is ServerImageTarget.Participant -> R.string.server_image_avatar
        },
    )

internal sealed interface ImagePreviewResult {
    class Ready(val bitmap: Bitmap) : ImagePreviewResult

    data object Busy : ImagePreviewResult

    data object Unavailable : ImagePreviewResult
}

internal class ServerImagePreviewCache(private val now: () -> Long = System::nanoTime) {
    private val mutex = Mutex()
    private val bitmaps = LruCache<ServerImage, Bitmap>(16)
    private val failures = LruCache<ServerImage, Long>(32)
    private var owner: ServerImages? = null

    suspend fun load(
        image: ServerImage,
        handle: () -> ServerImages?,
    ): ImagePreviewResult =
        mutex.withLock {
            val images = handle() ?: return@withLock ImagePreviewResult.Unavailable
            if (owner != images) {
                bitmaps.evictAll()
                failures.evictAll()
                owner = images
            }
            bitmaps.get(image)?.let { return@withLock ImagePreviewResult.Ready(it) }
            if (failures.get(image)?.let { now() - it < FAILURE_COOLDOWN } == true) return@withLock ImagePreviewResult.Unavailable
            val result = images.download(image)
            if (handle() != images) return@withLock ImagePreviewResult.Unavailable
            if (result is ChannelFileResult.Failed) {
                if (result.reason == ChannelFileFailure.BUSY) return@withLock ImagePreviewResult.Busy
                if (result.reason != ChannelFileFailure.SESSION_CHANGED) failures.put(image, now())
                return@withLock ImagePreviewResult.Unavailable
            }
            val bitmap = withContext(Dispatchers.Default) { decodePreview((result as ChannelFileResult.Success).value) }
            if (handle() != images) return@withLock ImagePreviewResult.Unavailable
            if (bitmap == null) {
                failures.put(image, now())
                return@withLock ImagePreviewResult.Unavailable
            }
            failures.remove(image)
            bitmaps.put(image, bitmap)
            ImagePreviewResult.Ready(bitmap)
        }

    private fun decodePreview(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty() || bytes.size > 256 * 1024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType !in STATIC_TYPES || bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) return null
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) > 256 * inSampleSize) inSampleSize *= 2
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.takeIf { it.allocationByteCount <= 256 * 256 * 4 }
    }

    private companion object {
        val STATIC_TYPES = setOf("image/png", "image/jpeg")
        val FAILURE_COOLDOWN = TimeUnit.SECONDS.toNanos(10)
    }
}
