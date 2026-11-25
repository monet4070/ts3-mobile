package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.sanitizedFailureTypes
import kotlinx.coroutines.CancellationException

internal suspend fun ConnectionCoordinatorHost.releaseCapture(): UserMessage.MicrophoneFailed? =
    try {
        stopMicrophoneCapture()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        UserMessage.MicrophoneFailed(error.sanitizedFailureTypes())
    }
