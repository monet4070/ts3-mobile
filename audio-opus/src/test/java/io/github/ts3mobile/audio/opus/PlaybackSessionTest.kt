package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionTest {
    @Test fun pendingBluetoothRouteDropsOldSpeechWithoutExhaustingOutputRecovery() {
        Fixture().use { fixture ->
            fixture.routeReady = false
            fixture.feed(0)
            fixture.session.tick(60 * MS)
            val output = fixture.outputs.single()
            for (millis in 80L..4000L step 20) fixture.feed(millis * MS)
            assertEquals(0, output.writeCalls)
            assertEquals(1, fixture.creations)
            assertEquals(0L, fixture.queue.budget.stats.outputFailures.get())
            output.routeReady = true
            fixture.session.tick(4100 * MS)
            assertEquals(0, output.writeCalls)
            fixture.feed(4200 * MS)
            fixture.session.tick(4260 * MS)
            assertTrue(output.accepted.isNotEmpty())
        }
    }

    @Test fun losingBluetoothRouteDuringRecoveryRetiresOutputBeforeAnyFurtherWrite() {
        Fixture().use { fixture ->
            fixture.feed(0)
            fixture.session.tick(60 * MS)
            val output = fixture.outputs.single().apply { routedKind = AudioRouteKind.BLUETOOTH }
            fixture.session.tick(70 * MS)
            fixture.session.tick(80 * MS)
            fixture.session.tick(150 * MS)
            assertFalse(output.closed)
            val writes = output.writeCalls
            output.routeReady = false
            fixture.feed(160 * MS)
            assertEquals(writes, output.writeCalls)
            assertTrue(output.closed)
            assertEquals(PlaybackStatus.BUFFERING, fixture.session.status)
        }
    }

    @Test fun losingBluetoothRouteDuringColdStartupDoesNotWaitOutTheGracePeriod() {
        Fixture().use { fixture ->
            fixture.feed(0L)
            fixture.session.tick(60 * MS)
            val output =
                fixture.outputs.single().apply {
                    routedKind = AudioRouteKind.BLUETOOTH
                    maxWriteSamples = 0
                }
            fixture.session.tick(70 * MS)
            for (millis in 80L..200L step 10) fixture.session.tick(millis * MS)
            assertFalse(output.closed)
            val writes = output.writeCalls

            output.routeReady = false
            fixture.session.tick(210 * MS)
            assertTrue(output.closed)
            assertEquals(writes, output.writeCalls)
            assertEquals(PlaybackStatus.BUFFERING, fixture.session.status)
        }
    }

    @Test fun changingProfileReleasesOldOutputAndAppliesNewDelayToFreshSpeech() {
        Fixture().use { fixture ->
            fixture.feed(0L)
            fixture.session.tick(60 * MS)
            val original = fixture.outputs.single()
            assertTrue(original.accepted.isNotEmpty())
            val oldWriteCount = original.writeCalls

            fixture.session.invalidateOutput(70 * MS, ReceiveDelayProfile.STABILITY)
            assertTrue(original.closed)
            assertEquals(PlaybackStatus.BUFFERING, fixture.session.status)
            fixture.feed(80 * MS)
            fixture.session.tick(140 * MS)
            assertEquals(1, fixture.outputs.size)

            fixture.session.tick(170 * MS)
            assertEquals(2, fixture.outputs.size)
            assertEquals(oldWriteCount, original.writeCalls)
            assertTrue(fixture.outputs.last().accepted.isNotEmpty())
        }
    }

    @Test fun healthyShortTalkspurtsClearHistoricalFailuresAcrossOutputLifetimes() {
        Fixture().use { fixture ->
            repeat(3) { round ->
                val base = round * 10_000L * MS
                fixture.factoryFails = true
                fixture.feed(base)
                fixture.session.tick(base + 60 * MS)
                assertEquals(PlaybackStatus.RETRYING, fixture.session.status)
                fixture.factoryFails = false
                repeat(3) { phrase ->
                    val start = base + (1000 + phrase * 1000L) * MS
                    for (tick in 0L..76L) {
                        val now = start + tick * 10 * MS
                        if (tick < 60 && tick % 2 == 0L) fixture.feed(now) else fixture.session.tick(now)
                        fixture.outputs.lastOrNull()?.let { it.playbackHead = it.acceptedFrames }
                    }
                }
            }
            assertEquals(3L, fixture.queue.budget.stats.outputRecoveries.get())
            assertEquals(0L, fixture.queue.budget.stats.outputRecoveryExhausted.get())
        }
    }

    @Test fun longIdleReleasesLimiterBeforeNewTalkerStarts() {
        Fixture().use { fixture ->
            repeat(8) { client -> fixture.queue.offer(QueuedFrame(VoiceFrame(client, 0, VoiceCodec.OPUS_VOICE, byteArrayOf(2), false), 0)) }
            fixture.session.tick(0)
            for (tick in 6L..16L) {
                fixture.session.tick(tick * 10 * MS)
                fixture.outputs.lastOrNull()?.let { it.playbackHead = it.acceptedFrames }
            }
            fixture.feed(5000 * MS)
            fixture.session.tick(5060 * MS)
            assertTrue(fixture.outputs.last().accepted.first().toInt() >= 999)
        }
    }

    @Test fun generationInvalidationPreventsPendingOldMixFromBeingWritten() {
        Fixture().use { fixture ->
            fixture.blockWrites = true
            fixture.feed(0)
            fixture.session.tick(60 * MS)
            fixture.mayWrite = false
            fixture.outputs.single().maxWriteSamples = Int.MAX_VALUE
            fixture.session.tick(70 * MS)
            assertTrue(fixture.outputs.single().accepted.isEmpty())
            fixture.revokeInputAndInvalidateOutput(80 * MS)
            fixture.mayWrite = true
            fixture.blockWrites = false
            fixture.feed(90 * MS)
            fixture.session.tick(150 * MS)
            assertTrue(fixture.outputs.last().accepted.isNotEmpty())
        }
    }

    @Test fun failedReleaseCanBeRetriedByCloseUntilOutputIsRetired() {
        val fixture = Fixture()
        fixture.feed(0)
        fixture.session.tick(60 * MS)
        fixture.outputs.single().failClose = true
        fixture.session.close()
        assertFalse(fixture.session.retired)
        fixture.outputs.single().failClose = false
        fixture.session.close()
        assertTrue(fixture.session.retired)
        fixture.close()
    }

    @Test fun failedReleasePreventsOverlappingOutputCreation() {
        Fixture().use { fixture ->
            fixture.feed(0)
            fixture.session.tick(60 * MS)
            val first = fixture.outputs.single()
            first.failClose = true
            first.writeResults.add(-6)
            fixture.session.tick(70 * MS)
            assertEquals(PlaybackStatus.FAILED, fixture.session.status)
            fixture.feed(1000 * MS)
            fixture.session.tick(1060 * MS)
            assertEquals(1, fixture.creations)
            first.failClose = false
        }
    }

    @Test fun zeroWritesRetireOutputAndFreshAudioResumesAfterBackoff() {
        Fixture().use { fixture ->
            fixture.blockWrites = true
            fixture.feed(0)
            fixture.session.tick(60 * MS)
            fixture.session.tick(160 * MS)
            assertEquals(PlaybackStatus.RETRYING, fixture.session.status)
            assertTrue(fixture.outputs.single().closed)
            assertEquals(1L, fixture.queue.budget.stats.outputFailures.get())
            fixture.blockWrites = false
            fixture.feed(410 * MS)
            fixture.session.tick(470 * MS)
            assertEquals(PlaybackStatus.PLAYING, fixture.session.status)
            assertEquals(2, fixture.outputs.size)
            assertEquals(480L, fixture.outputs.last().acceptedFrames)
            assertTrue(fixture.outputs.first().accepted.isEmpty())
        }
    }

    @Test fun negativeWriteAlsoRecoversWithoutClosingTheSession() {
        Fixture().use { fixture ->
            fixture.feed(0)
            fixture.session.tick(60 * MS)
            fixture.outputs.single().writeResults.add(-6)
            fixture.session.tick(70 * MS)
            assertEquals(PlaybackStatus.RETRYING, fixture.session.status)
            assertTrue(fixture.outputs.single().closed)
        }
    }

    @Test fun repeatedCreationFailureIsBoundedAndExplicitInvalidationAllowsRetry() {
        Fixture().use { fixture ->
            fixture.factoryFails = true
            for (arrival in listOf(0L, 310L, 870L)) {
                fixture.feed(arrival * MS)
                fixture.session.tick((arrival + 60) * MS)
            }
            assertEquals(3, fixture.creations)
            assertEquals(PlaybackStatus.FAILED, fixture.session.status)
            assertEquals(1L, fixture.queue.budget.stats.outputRecoveryExhausted.get())
            fixture.feed(2000 * MS)
            fixture.session.tick(2060 * MS)
            assertEquals(3, fixture.creations)
            fixture.revokeInputAndInvalidateOutput(2100 * MS)
            fixture.factoryFails = false
            fixture.feed(2100 * MS)
            fixture.session.tick(2160 * MS)
            assertEquals(PlaybackStatus.PLAYING, fixture.session.status)
        }
    }

    @Test fun routeInvalidationDuringPreparationPreventsOutputCreationUntilFreshVoice() {
        Fixture().use { fixture ->
            fixture.onPrepareRoute = { fixture.mayWrite = false }
            fixture.feed(0)
            fixture.session.tick(60 * MS)
            assertTrue(fixture.outputs.isEmpty())
            fixture.revokeInputAndInvalidateOutput(70 * MS)
            fixture.mayWrite = true
            fixture.onPrepareRoute = {}
            fixture.session.tick(80 * MS)
            assertTrue(fixture.outputs.isEmpty())
            fixture.feed(90 * MS)
            fixture.session.tick(150 * MS)
            assertEquals(PlaybackStatus.PLAYING, fixture.session.status)
        }
    }

    @Test fun invalidateAndCloseDiscardBufferedAudioAndCannotReopenClosedSession() {
        Fixture().use { fixture ->
            fixture.feed(0)
            fixture.session.tick(60 * MS)
            fixture.revokeInputAndInvalidateOutput(70 * MS)
            assertTrue(fixture.outputs.single().closed)
            fixture.session.tick(200 * MS)
            assertEquals(1, fixture.outputs.size)
            fixture.session.close()
            fixture.feed(300 * MS)
            fixture.session.tick(360 * MS)
            assertEquals(PlaybackStatus.STOPPED, fixture.session.status)
            assertEquals(1, fixture.outputs.size)
            assertEquals(0, fixture.queue.size)
        }
    }

    @Test fun freshSpeechQueuedBeforeWorkerAppliesRouteChangeStillPlays() {
        Fixture().use { fixture ->
            fixture.feed(0)
            fixture.session.tick(60 * MS)
            val oldOutput = fixture.outputs.single()
            fixture.mayWrite = false
            fixture.queue.clear()
            fixture.feed(70 * MS)

            fixture.session.invalidateOutput(80 * MS)
            assertTrue(oldOutput.closed)
            assertEquals(1, fixture.queue.size)

            fixture.mayWrite = true
            fixture.session.tick(130 * MS)
            assertEquals(2, fixture.outputs.size)
            assertEquals(PlaybackStatus.PLAYING, fixture.session.status)
            assertTrue(fixture.outputs.last().accepted.isNotEmpty())
        }
    }

    @Test fun longTicksAlwaysLeaveAWaitInsteadOfSpinningToCatchUp() {
        val pacer = PlaybackPacer()
        var now = 0L
        for (duration in 10L..59L) {
            now += duration * MS
            pacer.completed(now)
            assertTrue(pacer.waitNanos(now) >= MS)
            assertTrue(pacer.waitNanos(now) <= 10 * MS)
        }
    }

    private class Fixture : AutoCloseable {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        val outputs = mutableListOf<FakePlaybackOutput>()
        var decoders = 0
        var creations = 0
        var onPrepareRoute: () -> Unit = {}
        var factoryFails = false
        var blockWrites = false
        var mayWrite = true
        var routeReady = true
        private var sequence = 0
        val session =
            PlaybackSession(
                queue,
                PlaybackStreams(queue, mutableSetOf(), emptyMap()) {
                    decoders++
                    Decoder()
                },
                {
                    creations++
                    assertTrue(outputs.all { it.closed })
                    check(!factoryFails) { "Synthetic output creation failure" }
                    FakePlaybackOutput().also {
                        it.routeReady = routeReady
                        if (blockWrites) it.maxWriteSamples = 0
                        outputs += it
                    }
                },
                canWrite = { mayWrite },
                prepareOutputRoute = { onPrepareRoute() },
            )

        fun feed(now: Long) {
            queue.offer(QueuedFrame(VoiceFrame(1, sequence++, VoiceCodec.OPUS_VOICE, byteArrayOf(1), false), now))
            session.tick(now)
        }

        fun revokeInputAndInvalidateOutput(now: Long) {
            queue.clear()
            session.invalidateOutput(now)
        }

        override fun close() {
            session.close()
            assertFalse(outputs.any { !it.closed })
        }
    }

    private class Decoder : OpusPacketDecoder {
        override fun packetInfo(packet: ByteArray) = OpusPacketInfo(960)

        override fun decode(
            packet: ByteArray?,
            frameSize: Int,
        ) = ShortArray(if (packet == null) frameSize else 960) { if (packet?.firstOrNull()?.toInt() == 2) 30000 else 1000 }

        override fun decodeFec(
            packet: ByteArray,
            frameSize: Int,
        ) = error("Unexpected FEC")

        override fun reset() = Unit

        override fun close() = Unit
    }

    private companion object {
        const val MS = 1_000_000L
    }
}
