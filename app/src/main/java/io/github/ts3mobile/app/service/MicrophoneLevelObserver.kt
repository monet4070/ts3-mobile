package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.MicrophoneCapture
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/** Cold UI-only observation. Cancellation always releases the scalar subscription. */
internal fun observeMicrophoneLevels(capture: MicrophoneCapture) =
    flow {
        val subscription = capture.observeLevels()
        try {
            while (currentCoroutineContext().isActive) {
                emit(subscription.latest)
                delay(100)
            }
        } finally {
            subscription.close()
        }
    }.distinctUntilChanged()
