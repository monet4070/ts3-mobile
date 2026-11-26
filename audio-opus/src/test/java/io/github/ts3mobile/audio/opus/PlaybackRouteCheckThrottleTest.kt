package io.github.ts3mobile.audio.opus

import android.media.AudioDeviceInfo
import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRouteCheckThrottleTest {
    @Test fun checksInitiallyWhenDisallowedOnIntervalExpiryAndAfterInvalidation() {
        val throttle = PlaybackRouteCheckThrottle(intervalMs = 250L)

        assertTrue(throttle.shouldCheck(nowMs = 100L))
        throttle.record(allowed = false, nowMs = 100L)
        assertTrue("waiting/unsafe route must recheck on next write", throttle.shouldCheck(nowMs = 110L))

        throttle.record(allowed = true, nowMs = 110L)
        assertFalse(throttle.shouldCheck(nowMs = 120L))
        assertFalse(throttle.shouldCheck(nowMs = 359L))
        assertTrue(throttle.shouldCheck(nowMs = 360L))

        throttle.record(allowed = true, nowMs = 360L)
        assertFalse(throttle.shouldCheck(nowMs = 370L))
        throttle.invalidate()
        assertTrue(throttle.shouldCheck(nowMs = 370L))
    }

    @Test fun steadyPlaybackSessionLoopThrottlesRoutedDeviceQueriesAndDeduplicatesVolume() {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        val guard = BluetoothPlaybackGuard().apply { select(1, requireBluetooth = false) }
        var nowMs = 0L
        var deviceReads = 0
        val recordedVolumes = mutableListOf<Float>()
        lateinit var output: ControlledOutput

        PlaybackSession(
            queue,
            PlaybackStreams(queue, mutableSetOf(), emptyMap()) { Decoder() },
            {
                ControlledOutput(
                    controller =
                        PlaybackRouteController(
                            routeGuard = guard,
                            clockMs = { nowMs },
                            readRoutedDeviceType = {
                                deviceReads++
                                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                            },
                            setTrackVolume = { recordedVolumes += it },
                            onUnsafeRoute = {},
                        ),
                ).also { output = it }
            },
        ).use { session ->
            // 1000ms (100 ticks at 10ms) with continuous 20ms voice packets.
            for (tick in 0..105) {
                nowMs = tick * 10L
                val nowNs = nowMs * MS
                if (tick % 2 == 0) {
                    queue.offer(QueuedFrame(VoiceFrame(1, tick / 2, VoiceCodec.OPUS_VOICE, byteArrayOf(1), false), nowNs))
                }
                session.tick(nowNs)
                if (tick >= 6) output.playbackHead = output.acceptedFrames
            }
        }

        // At 100 ticks (each calling both routeReady and write), steady throttling cuts queries to <= 6 and sets 1f once.
        assertTrue("expected throttled routedDevice queries <= 6, got $deviceReads", deviceReads in 4..6)
        assertEquals(listOf(0f, 1f, 0f), recordedVolumes)
    }

    @Test fun routingChangeInsideThrottleWindowMutesImmediatelyAndRetiresOutputBeforeNextWrite() {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        val guard = BluetoothPlaybackGuard().apply { select(7, requireBluetooth = true) }
        var nowMs = 0L
        var deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val recordedVolumes = mutableListOf<Float>()
        var unsafeCallbacks = 0
        lateinit var controller: PlaybackRouteController
        lateinit var output: ControlledOutput

        PlaybackSession(
            queue,
            PlaybackStreams(queue, mutableSetOf(), emptyMap()) { Decoder() },
            {
                controller =
                    PlaybackRouteController(
                        routeGuard = guard,
                        clockMs = { nowMs },
                        readRoutedDeviceType = { deviceType },
                        setTrackVolume = { recordedVolumes += it },
                        onUnsafeRoute = { unsafeCallbacks++ },
                    )
                ControlledOutput(controller).also { output = it }
            },
        ).use { session ->
            for (tick in 0..8) {
                nowMs = tick * 10L
                if (tick % 2 == 0) {
                    queue.offer(QueuedFrame(VoiceFrame(1, tick / 2, VoiceCodec.OPUS_VOICE, byteArrayOf(1), false), nowMs * MS))
                }
                session.tick(nowMs * MS)
                if (tick >= 6) output.playbackHead = output.acceptedFrames
            }
            val writesBeforeDrop = output.writeCalls

            // Inside the 250ms throttle window, Bluetooth drops to built-in speaker and OnRoutingChangedListener fires.
            nowMs = 100L
            deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            assertFalse(controller.onRoutingChanged())

            assertEquals(1, unsafeCallbacks)
            assertEquals(0f, recordedVolumes.last())

            // Next tick sees routeReady == false and retires output without calling write() again.
            nowMs = 110L
            queue.offer(QueuedFrame(VoiceFrame(1, 5, VoiceCodec.OPUS_VOICE, byteArrayOf(1), false), nowMs * MS))
            session.tick(nowMs * MS)
            assertEquals(writesBeforeDrop, output.writeCalls)
            assertTrue(output.closed)
            assertEquals(PlaybackStatus.BUFFERING, session.status)
        }
    }

    @Test fun routeGuardRevisionBumpInsideThrottleWindowBlocksRouteReadyAndWritesImmediately() {
        val guard = BluetoothPlaybackGuard().apply { select(1, requireBluetooth = false) }
        var nowMs = 0L
        val recordedVolumes = mutableListOf<Float>()
        val controller =
            PlaybackRouteController(
                routeGuard = guard,
                clockMs = { nowMs },
                readRoutedDeviceType = { AudioDeviceInfo.TYPE_BUILTIN_SPEAKER },
                setTrackVolume = { recordedVolumes += it },
                onUnsafeRoute = {},
            )

        assertTrue(controller.isRouteReady())
        assertEquals(1f, recordedVolumes.last())

        // Still inside the 250ms throttle window, caller selects a different route (revision bumps).
        nowMs = 20L
        guard.select(7, requireBluetooth = true)
        assertFalse(controller.isRouteReady())
        assertEquals(0f, recordedVolumes.last())
        assertEquals(0, controller.writeGuarded { error("must not write after route revision change") })
    }

    @Test fun bluetoothFallbackWithoutRoutingCallbackImmediatelyMutesAndNeverWritesToSpeaker() {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        val guard = BluetoothPlaybackGuard().apply { select(7, requireBluetooth = true) }
        var nowMs = 0L
        var deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        val recordedVolumes = mutableListOf<Float>()
        var unsafeCallbacks = 0
        lateinit var output: ControlledOutput

        PlaybackSession(
            queue,
            PlaybackStreams(queue, mutableSetOf(), emptyMap()) { Decoder() },
            {
                ControlledOutput(
                    controller =
                        PlaybackRouteController(
                            routeGuard = guard,
                            clockMs = { nowMs },
                            readRoutedDeviceType = { deviceType },
                            setTrackVolume = { recordedVolumes += it },
                            onUnsafeRoute = { unsafeCallbacks++ },
                        ),
                ).also { output = it }
            },
        ).use { session ->
            for (tick in 0..8) {
                nowMs = tick * 10L
                if (tick % 2 == 0) {
                    queue.offer(QueuedFrame(VoiceFrame(1, tick / 2, VoiceCodec.OPUS_VOICE, byteArrayOf(1), false), nowMs * MS))
                }
                session.tick(nowMs * MS)
                if (tick >= 6) output.playbackHead = output.acceptedFrames
            }
            val writesBeforeDrop = output.writeCalls
            assertTrue(writesBeforeDrop > 0)

            // Route falls back to speaker inside the 250ms throttle window with NO routing callback and NO revision change.
            nowMs = 100L
            deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            queue.offer(QueuedFrame(VoiceFrame(1, 5, VoiceCodec.OPUS_VOICE, byteArrayOf(1), false), nowMs * MS))
            session.tick(nowMs * MS)

            assertEquals(writesBeforeDrop, output.writeCalls)
            assertEquals(0f, recordedVolumes.last())
            assertEquals(1, unsafeCallbacks)
            assertTrue(output.closed)
            assertEquals(PlaybackStatus.BUFFERING, session.status)
        }

        // Also cover mid-tick silent fallback between isRouteReady() and writeGuarded() with zero callbacks.
        var midTickDevice = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        val midTickVolumes = mutableListOf<Float>()
        var midTickUnsafe = 0
        nowMs = 0L
        val midController =
            PlaybackRouteController(
                routeGuard = guard,
                clockMs = { nowMs },
                readRoutedDeviceType = { midTickDevice },
                setTrackVolume = { midTickVolumes += it },
                onUnsafeRoute = { midTickUnsafe++ },
            )
        assertTrue(midController.isRouteReady())
        assertEquals(1f, midTickVolumes.last())

        nowMs = 10L
        midTickDevice = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        assertEquals(0, midController.writeGuarded { error("must not write to speaker without callback") })
        assertEquals(0f, midTickVolumes.last())
        assertEquals(1, midTickUnsafe)
        assertFalse(midController.isRouteReady())
        assertEquals(1, midTickUnsafe)
    }

    @Test fun systemRouteBluetoothFallbackWithoutRoutingCallbackImmediatelyMutesAndBlocksSpeakerWrite() {
        val guard = BluetoothPlaybackGuard()
        var nowMs = 0L
        var deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        val recordedVolumes = mutableListOf<Float>()
        var unsafeCallbacks = 0
        val controller =
            PlaybackRouteController(
                routeGuard = guard,
                clockMs = { nowMs },
                readRoutedDeviceType = { deviceType },
                setTrackVolume = { recordedVolumes += it },
                onUnsafeRoute = { unsafeCallbacks++ },
            )

        assertTrue(controller.isRouteReady())
        assertTrue(guard.expectsBluetooth())
        assertEquals(1f, recordedVolumes.last())

        nowMs = 10L
        deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        assertEquals(0, controller.writeGuarded { error("must not write to speaker after system Bluetooth loss") })
        assertEquals(0f, recordedVolumes.last())
        assertEquals(1, unsafeCallbacks)
        assertFalse(controller.isRouteReady())
        assertEquals(1, unsafeCallbacks)
    }

    @Test fun systemRouteStartingOnSpeakerSamplesBluetoothAndBlocksFallbackWriteWithinThrottleWindow() {
        val guard = BluetoothPlaybackGuard()
        var nowMs = 0L
        var deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val recordedVolumes = mutableListOf<Float>()
        var unsafeCallbacks = 0
        val controller =
            PlaybackRouteController(
                routeGuard = guard,
                clockMs = { nowMs },
                readRoutedDeviceType = { deviceType },
                setTrackVolume = { recordedVolumes += it },
                onUnsafeRoute = { unsafeCallbacks++ },
            )

        // Start on speaker under automatic system routing.
        assertTrue(controller.isRouteReady())
        assertEquals(480, controller.writeGuarded { 480 })
        assertEquals(1f, recordedVolumes.last())
        assertFalse(guard.expectsBluetooth())

        // Within 250ms, system switches to Bluetooth then falls back to speaker with NO callbacks.
        nowMs = 50L
        deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        assertEquals(480, controller.writeGuarded { 480 })
        assertTrue(guard.expectsBluetooth())

        nowMs = 120L
        deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        assertEquals(0, controller.writeGuarded { error("must not write to speaker after unsignaled Bluetooth bounce") })
        assertEquals(0f, recordedVolumes.last())
        assertEquals(1, unsafeCallbacks)
        assertFalse(controller.isRouteReady())
        assertEquals(1, unsafeCallbacks)
    }

    private class ControlledOutput(
        private val controller: PlaybackRouteController,
    ) : PlaybackOutput {
        override var playbackHead = 0L
        var acceptedFrames = 0L
        var writeCalls = 0
        var closed = false

        init {
            controller.initializeVolume()
        }

        override val routeReady: Boolean get() = controller.isRouteReady()

        override val routedKind: AudioRouteKind? get() = controller.routedKind

        override fun start() = Unit

        override fun muteImmediately() = controller.muteImmediately()

        override fun write(
            samples: ShortArray,
            offset: Int,
            count: Int,
        ): Int =
            controller.writeGuarded {
                writeCalls++
                acceptedFrames += count
                count
            }

        override fun discard() = controller.discard {}

        override fun close() {
            controller.close(beforeRelease = {}, release = {})
            closed = true
        }
    }

    private class Decoder : OpusPacketDecoder {
        override fun packetInfo(packet: ByteArray) = OpusPacketInfo(960)

        override fun decode(
            packet: ByteArray?,
            frameSize: Int,
        ) = ShortArray(if (packet == null) frameSize else 960) { 1000 }

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
