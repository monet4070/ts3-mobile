package io.github.ts3mobile.audio.opus

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PlaybackLifecycleTest {
    @Test fun changingSelectedRouteRecoversFailedSessionEvenWhenDevicePreferenceStaysNull() {
        val failFactory = AtomicBoolean(true)
        val failed = CountDownLatch(1)
        val output = Output(block = false)
        val player =
            OpusAudioPlayer(
                InstrumentationRegistry.getInstrumentation().targetContext,
                null,
                onStatusChanged = { if (it == PlaybackStatus.FAILED) failed.countDown() },
                outputFactory = {
                    check(!failFactory.get()) { "Synthetic output creation failure" }
                    output
                },
            )
        val feeding = AtomicBoolean(true)
        val packet = NativeOpusEncoder().use { it.encode(ShortArray(960) { 1000 }) }
        val feeder =
            Thread {
                var sequence = 0
                while (feeding.get()) {
                    player.submit(VoiceFrame(1, sequence++, VoiceCodec.OPUS_VOICE, packet, false))
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        try {
            player.start()
            feeder.start()
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            failFactory.set(false)
            player.setPreferredDevice(null, 2)
            assertTrue(output.entered.await(3, TimeUnit.SECONDS))
            assertEquals(PlaybackStatus.PLAYING, player.status)
        } finally {
            feeding.set(false)
            feeder.interrupt()
            feeder.join(1000)
            player.close()
        }
    }

    @Test fun stopOnMainReturnsWhileWorkerIsInOutputAndPreventsFurtherWrites() {
        val output = Output(block = true)
        val player = player(output)
        try {
            startVoice(player)
            assertTrue(output.entered.await(3, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player.stop() }
            assertThrows(IllegalStateException::class.java) { player.start() }
            output.release.countDown()
            assertTrue(output.closed.await(3, TimeUnit.SECONDS))
            player.stop()
            assertEquals(1, output.writes.get())
        } finally {
            output.release.countDown()
            player.close()
        }
    }

    @Test fun failedReleaseBlocksRestartAcrossWorkerExitUntilRetirementIsRetried() {
        val output = Output(block = false)
        val player = player(output)
        try {
            startVoice(player)
            assertTrue(output.entered.await(3, TimeUnit.SECONDS))
            output.rejectClose.set(true)
            player.stop()
            assertEquals(PlaybackStatus.FAILED, player.status)
            assertThrows(IllegalStateException::class.java) { player.start() }
            assertFalse(output.closed.await(20, TimeUnit.MILLISECONDS))
            output.rejectClose.set(false)
            player.stop()
            assertTrue(output.closed.await(3, TimeUnit.SECONDS))
            player.start()
            player.stop()
        } finally {
            output.rejectClose.set(false)
            player.close()
        }
    }

    @Test fun participantMuteInvalidatesPendingMixBeforeAnotherWrite() {
        val output = Output(block = true)
        val player = player(output)
        try {
            startVoice(player)
            assertTrue(output.entered.await(3, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player.replaceParticipantGains(mapOf(1 to 0f)) }
            output.release.countDown()
            assertTrue(output.closed.await(3, TimeUnit.SECONDS))
            assertEquals(1, output.writes.get())
        } finally {
            output.release.countDown()
            player.close()
        }
    }

    private fun player(output: Output) =
        OpusAudioPlayer(InstrumentationRegistry.getInstrumentation().targetContext, null, outputFactory = { output })

    private fun startVoice(player: OpusAudioPlayer) {
        val packet = NativeOpusEncoder().use { it.encode(ShortArray(960) { 1000 }) }
        player.start()
        repeat(4) { player.submit(VoiceFrame(1, it, VoiceCodec.OPUS_VOICE, packet, false)) }
    }

    private class Output(private val block: Boolean) : PlaybackOutput {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val rejectClose = AtomicBoolean()
        val writes = AtomicInteger()
        override val playbackHead = 0L

        override fun start() = Unit

        override fun write(
            samples: ShortArray,
            offset: Int,
            count: Int,
        ): Int {
            writes.incrementAndGet()
            entered.countDown()
            if (block) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (release.count > 0 && System.nanoTime() < deadline) {
                    try {
                        release.await(50, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                    }
                }
            }
            return if (block) 0 else count
        }

        override fun discard() = Unit

        override fun close() {
            check(!rejectClose.get()) { "Synthetic release failure" }
            closed.countDown()
        }
    }
}
