package io.github.ts3mobile.audio.opus

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PlaybackDiagnosticsTest {
    @Test fun realWorkerPublishesReceiveAndWriteTotalsThenFinalSnapshotAfterMainThreadStop() {
        val head = AtomicLong()
        val wrote = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val final = AtomicReference<AudioReceiveDiagnostics>()
        val output =
            object : PlaybackOutput {
                override val playbackHead: Long get() = head.get()

                override fun start() = Unit

                override fun write(
                    samples: ShortArray,
                    offset: Int,
                    count: Int,
                ): Int {
                    head.addAndGet(count.toLong())
                    wrote.countDown()
                    return count
                }

                override fun discard() = Unit

                override fun close() = Unit
            }
        val player = OpusAudioPlayer(InstrumentationRegistry.getInstrumentation().targetContext, null, outputFactory = { output })
        player.setDiagnosticsObserver(
            object : AudioDiagnosticsObserver {
                override fun onEvent(
                    kind: AudioDiagnosticEventKind,
                    value: Long,
                ) = Unit

                override fun onStopped(snapshot: AudioReceiveDiagnostics) {
                    final.set(snapshot)
                    stopped.countDown()
                }
            },
        )
        val packet = NativeOpusEncoder().use { it.encode(ShortArray(960)) }
        try {
            player.start()
            repeat(4) { player.submit(VoiceFrame(1, it, VoiceCodec.OPUS_VOICE, packet, false)) }
            assertTrue(wrote.await(3, TimeUnit.SECONDS))
            val active = player.takeReceiveDiagnostics()
            assertEquals(4L, active.counters[AudioReceiveCounter.SUBMITTED])
            assertEquals(4L, active.counters[AudioReceiveCounter.ACCEPTED])
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player.stop() }
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            assertFalse(final.get().active)
            assertTrue(final.get().counters.getValue(AudioReceiveCounter.OUTPUT_SAMPLES) > 0)
            assertEquals(1L, final.get().counters[AudioReceiveCounter.OUTPUTS_CREATED])
        } finally {
            player.close()
        }
    }
}
