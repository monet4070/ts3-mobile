package io.github.ts3mobile.app.service

import android.media.AudioDeviceInfo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.audio.opus.MicrophoneCapture
import io.github.ts3mobile.audio.opus.MicrophoneLevel
import io.github.ts3mobile.audio.opus.MicrophoneLevelSubscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fake capture only: collection/disposal cannot trigger microphone I/O. */
class MicrophoneLevelObserverTest {
    @Test fun collectionIsColdClosesSubscriptionAndDoesNotTouchCaptureLifecycle() =
        runBlocking {
            val capture = Capture()
            val levels = observeMicrophoneLevels(capture)
            assertEquals(0, capture.subscriptions)
            val received = CompletableDeferred<Unit>()
            val job =
                launch {
                    levels.collect {
                        assertEquals(null, it)
                        received.complete(Unit)
                    }
                }
            withTimeout(2_000) { received.await() }
            assertEquals(1, capture.subscriptions)
            job.cancelAndJoin()
            assertEquals(1, capture.closes)
            assertEquals(0, capture.starts)
            assertTrue(!capture.isCapturing)
        }

    @Test fun scalarPollingMeetsThe250MillisecondTargetWithSyntheticInput() =
        runBlocking {
            val capture = Capture()
            val seen = CompletableDeferred<MicrophoneLevel>()
            val job = launch { observeMicrophoneLevels(capture).collect { if (it != null) seen.complete(it) } }
            delay(20)
            val changedAt = System.nanoTime()
            capture.scalar = MicrophoneLevel(1, 0, 0, changedAt, -30.0, false, true, true, -40)
            val received = withTimeout(250) { seen.await() }
            val elapsed = System.nanoTime() - changedAt
            assertEquals(-30.0, received.rmsDb, 0.0)
            assertTrue(elapsed < 250_000_000)
            job.cancelAndJoin()
            assertEquals(0, capture.starts)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "microphone-poll-latency.txt").writeText(
                "Synthetic scalar changed to Flow collection: ${elapsed / 1_000_000.0} ms; no physical audio.\n",
            )
        }

    private class Capture : MicrophoneCapture {
        var subscriptions = 0
        var closes = 0
        var starts = 0

        @Volatile var scalar: MicrophoneLevel? = null
        override val isCapturing = false
        override val generation = 0L

        override fun observeLevels(): MicrophoneLevelSubscription {
            subscriptions++
            return object : MicrophoneLevelSubscription {
                override val latest: MicrophoneLevel? get() = scalar

                override fun close() {
                    closes++
                }
            }
        }

        override fun configure(
            settings: io.github.ts3mobile.audio.opus.MicrophoneSettings,
            volumeGate: Boolean,
        ) = Unit

        override fun setPreferredDevice(device: AudioDeviceInfo?) = Unit

        override fun start() {
            starts++
        }

        override fun stop() = Unit

        override fun close() = Unit

        override fun isReady() = false

        override fun pollEncodedFrame(): ByteArray? = null
    }
}
