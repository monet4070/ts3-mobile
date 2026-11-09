package io.github.ts3mobile.audio.opus

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackRoutePreparationTest {
    @Test fun preparesTheRouteAfterFocusBeforeCreatingAnOutput() {
        val prepared = AtomicBoolean()
        val written = CountDownLatch(1)
        val player =
            OpusAudioPlayer(
                InstrumentationRegistry.getInstrumentation().targetContext,
                null,
                prepareOutputRoute = { prepared.set(true) },
                outputFactory = {
                    assertTrue(prepared.get())
                    object : PlaybackOutput {
                        override val playbackHead = 0L

                        override fun start() = Unit

                        override fun write(
                            samples: ShortArray,
                            offset: Int,
                            count: Int,
                        ): Int {
                            written.countDown()
                            return count
                        }

                        override fun discard() = Unit

                        override fun close() = Unit
                    }
                },
            )
        val packet = NativeOpusEncoder().use { it.encode(ShortArray(960)) }
        try {
            player.start()
            repeat(4) { player.submit(VoiceFrame(1, it, VoiceCodec.OPUS_VOICE, packet, false)) }
            assertTrue(written.await(3, TimeUnit.SECONDS))
        } finally {
            player.close()
        }
    }
}
