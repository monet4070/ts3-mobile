package io.github.ts3mobile.audio.opus

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

internal data class ParticipantMuteTrace(
    val point: String,
    val bUnmixedBeforeMute: Int,
    val bInputBeforeMute: Int,
    val bEncodedBeforeMute: Int,
    val bPcmBeforeMute: Int,
    val bUnmixedAfterMute: Int,
    val bUnmixedBeforeUnmute: Int,
    val bUnmixedAfterUnmute: Int,
    val bDecoderClosedOnMute: Boolean,
    val bDecoderRebuiltOnMute: Boolean,
    val bDecoderRebuiltOnUnmute: Boolean,
    val oldOutputAcceptedFrames: Long,
    val muteMainNanos: Long,
    val unmuteMainNanos: Long,
    val firstBWriteAfterMuteNanos: Long,
    val firstBWriteAfterUnmuteNanos: Long,
    val outputCreations: Int,
)

/** Drives the real player and native codecs into controlled synthetic output barriers. */
internal object ParticipantMuteProbe {
    fun run(point: MuteProbePoint): ParticipantMuteTrace {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var player: OpusAudioPlayer
        val first = ParticipantMuteProbeOutput(point)
        val second = ParticipantMuteProbeOutput(MuteProbePoint.FULL_BARRIER)
        val finalDecoder = AtomicReference<Any>()
        val third =
            ParticipantMuteProbeOutput(MuteProbePoint.ACCEPT) {
                finalDecoder.set(ParticipantMuteObservation.snapshot(player, B).decoder)
            }
        val creations = AtomicInteger()
        player =
            OpusAudioPlayer(instrumentation.targetContext, null, outputFactory = {
                when (creations.getAndIncrement()) {
                    0 -> first
                    1 -> second
                    2 -> third
                    else -> error("Unexpected synthetic output recreation")
                }
            })
        val aPackets = packets(1000)
        val bPackets = packets(500)
        val feeding = AtomicBoolean()
        val feedingLock = Any()
        val feeder =
            Thread {
                var sequence = 5
                while (feeding.get() && sequence < bPackets.size) {
                    synchronized(feedingLock) {
                        player.submit(frame(B, sequence, bPackets[sequence]))
                        sequence++
                    }
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        try {
            player.start()
            repeat(4) { sequence ->
                player.submit(frame(A, sequence, aPackets[sequence]))
                player.submit(frame(B, sequence, bPackets[sequence]))
            }
            assertTrue(first.entered.await(5, TimeUnit.SECONDS))
            player.submit(frame(B, 4, bPackets[4]))
            val before = ParticipantMuteObservation.snapshot(player, B)
            assertEquals(before.budget, before.input + before.pending + before.pcm)
            assertEquals(5 * 960, before.budget + TalkerJitterPipeline.MIX_TICK_SAMPLES)
            assertFalse(ParticipantMuteObservation.decoderClosed(before.decoder))
            val oldWrites = first.writes.get()
            var muteAt = 0L
            var mainMute = 0L
            instrumentation.runOnMainSync {
                muteAt = System.nanoTime()
                player.replaceParticipantGains(mapOf(A to 0f))
                mainMute = System.nanoTime() - muteAt
            }
            assertTrue(first.release.count > 0)
            assertTrue(mainMute < TimeUnit.MILLISECONDS.toNanos(250))
            val afterMute = ParticipantMuteObservation.budget(player, B)
            first.release.countDown()
            assertTrue(first.closed.await(5, TimeUnit.SECONDS))
            assertEquals("A mute must not start another old mixed write", oldWrites, first.writes.get())
            feeding.set(true)
            feeder.start()
            assertTrue(second.entered.await(5, TimeUnit.SECONDS))
            val during = ParticipantMuteObservation.snapshot(player, B)
            val retired = ParticipantMuteObservation.decoderClosed(before.decoder)
            var beforeUnmute = 0
            var afterUnmute = 0
            var unmuteAt = 0L
            var mainUnmute = 0L
            val secondWrites = second.writes.get()
            // Hold only the synthetic feeder, not the player/worker. Both snapshots have identical input.
            synchronized(feedingLock) {
                beforeUnmute = ParticipantMuteObservation.budget(player, B)
                instrumentation.runOnMainSync {
                    unmuteAt = System.nanoTime()
                    player.replaceParticipantGains(emptyMap())
                    mainUnmute = System.nanoTime() - unmuteAt
                }
                afterUnmute = ParticipantMuteObservation.budget(player, B)
            }
            assertTrue(second.release.count > 0)
            assertTrue(mainUnmute < TimeUnit.MILLISECONDS.toNanos(250))
            second.release.countDown()
            assertTrue(second.closed.await(5, TimeUnit.SECONDS))
            assertEquals("An unmute must not start another old mixed write", secondWrites, second.writes.get())
            assertTrue(third.entered.await(5, TimeUnit.SECONDS))
            return ParticipantMuteTrace(
                point.name, before.budget, before.input, before.pending, before.pcm, afterMute,
                beforeUnmute, afterUnmute, retired, before.decoder !== during.decoder, during.decoder !== finalDecoder.get(),
                first.accepted, mainMute, mainUnmute, second.firstWriteAt - muteAt, third.firstWriteAt - unmuteAt, creations.get(),
            )
        } finally {
            feeding.set(false)
            feeder.interrupt()
            if (feeder.isAlive) feeder.join(1000)
            first.release.countDown()
            second.release.countDown()
            third.release.countDown()
            player.close()
        }
    }

    private fun packets(frequency: Int): List<ByteArray> =
        NativeOpusEncoder().use { encoder ->
            List(48) { sequence ->
                encoder.encode(
                    ShortArray(960) { sample ->
                        (sin(2 * PI * frequency * (sequence * 960 + sample) / 48_000) * 2000).toInt().toShort()
                    },
                )
            }
        }

    private fun frame(
        client: Int,
        sequence: Int,
        packet: ByteArray,
    ) = VoiceFrame(client, sequence, VoiceCodec.OPUS_VOICE, packet, false)

    private const val A = 1
    private const val B = 2
}
