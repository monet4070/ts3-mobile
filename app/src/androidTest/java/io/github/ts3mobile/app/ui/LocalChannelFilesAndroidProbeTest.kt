package io.github.ts3mobile.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.R
import io.github.ts3mobile.audio.opus.AudioDiagnosticEventKind
import io.github.ts3mobile.audio.opus.AudioDiagnosticsObserver
import io.github.ts3mobile.audio.opus.AudioReceiveCounter
import io.github.ts3mobile.audio.opus.AudioReceiveDiagnostics
import io.github.ts3mobile.audio.opus.OpusAudioPlayer
import io.github.ts3mobile.audio.opus.PlaybackStatus
import io.github.ts3mobile.protocol.ChannelFileFailure
import io.github.ts3mobile.protocol.ChannelFileResult
import io.github.ts3mobile.protocol.ChannelFiles
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.EncodedVoiceSource
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import io.github.ts3mobile.protocol.Ts3SessionListener
import io.github.ts3mobile.protocol.Ts3jSessionClient
import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LocalChannelFilesAndroidProbeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun savesVerifiedServerFileWhileSilentOpusReachesAndroidPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val argument = InstrumentationRegistry.getArguments().getString("ts3UsbFixturePort")
        assumeTrue("Explicit USB loopback fixture required", argument != null)
        val bridgePort = checkNotNull(argument).toInt()
        require(bridgePort in 1..65_535)

        val destination = Uri.parse("content://${instrumentation.context.packageName}.backup-documents/${UUID.randomUUID()}")
        val resolver = rule.activity.contentResolver
        checkNotNull(
            resolver.acquireContentProviderClient(destination),
        ) { "Start the test document provider before running the probe" }.use { }
        try {
            UsbLoopbackVoiceRelay(bridgePort).use { receivingRelay ->
                UsbLoopbackVoiceRelay(bridgePort).use { sendingRelay ->
                    OpusAudioPlayer(rule.activity).use { audio ->
                        exercise(receivingRelay, sendingRelay, audio, destination)
                    }
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val size =
                DigestInputStream(checkNotNull(resolver.openInputStream(destination)), digest).use { input ->
                    val buffer = ByteArray(32_768)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                    }
                    total
                }
            assertEquals(VOICE_BYTES, size)
            assertEquals(VOICE_SHA256, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (failure: Throwable) {
            runCatching { resolver.delete(destination, null, null) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        resolver.delete(destination, null, null)
    }

    private fun exercise(
        receivingRelay: UsbLoopbackVoiceRelay,
        sendingRelay: UsbLoopbackVoiceRelay,
        audio: OpusAudioPlayer,
        destination: Uri,
    ) {
        val outputHeadMoved = AtomicBoolean()
        audio.setDiagnosticsObserver(
            object : AudioDiagnosticsObserver {
                override fun onEvent(
                    kind: AudioDiagnosticEventKind,
                    value: Long,
                ) {
                    if (kind == AudioDiagnosticEventKind.FIRST_WRITE_TO_HEAD_US) outputHeadMoved.set(true)
                }

                override fun onStopped(snapshot: AudioReceiveDiagnostics) = Unit
            },
        )
        val senderId = AtomicInteger()
        val receiver =
            Listener { frame ->
                if (frame.clientId == senderId.get() && frame.codec == VoiceCodec.OPUS_VOICE && !frame.isWhisper &&
                    frame.encodedData.contentEquals(SILENCE_FRAME)
                ) {
                    audio.submit(frame)
                }
            }
        val sender = Listener {}
        Ts3jSessionClient().use { reader ->
            Ts3jSessionClient().use { talker ->
                connect(reader, receivingRelay, "Files Android", receiver)
                connect(talker, sendingRelay, "Silence Android", sender)
                val initial = checkNotNull(receiver.snapshot.currentChannelId)
                assertEquals(initial, sender.snapshot.currentChannelId)
                senderId.set(checkNotNull(sender.snapshot.ownClientId))
                val files = checkNotNull(reader.channelFiles(initial))
                val currentFiles = mutableStateOf<ChannelFiles?>(files)
                setBrowser(currentFiles, destination)
                browserClick("ts3mobile-fixture")

                audio.start()
                talker.setVoiceSource(SilenceSource)
                rule.waitUntil(8_000) { outputHeadMoved.get() && audio.status == PlaybackStatus.PLAYING }
                browserClick("voice.bin")
                verifyOverlap(audio)
                browserClick(R.string.channel_files_save)
                browserWait(R.string.channel_files_saved)
                val after = audio.takeReceiveDiagnostics()
                rule.waitUntil(3_000) { audio.takeReceiveDiagnostics().outputSamples() >= after.outputSamples() + 4_800 }
                val final = audio.takeReceiveDiagnostics()
                assertEquals(0L, final.count(AudioReceiveCounter.OUTPUT_FAILURES))
                assertEquals(0L, final.count(AudioReceiveCounter.REBUFFERS))
                assertEquals(PlaybackStatus.PLAYING, audio.status)
                receivingRelay.checkHealthy()
                sendingRelay.checkHealthy()
                talker.setVoiceSource(null)
                audio.stop()
                println("TS3_FILE_DEVICE: output=${final.actualRoute} outputSamples=${final.outputSamples()}")

                browserClick("voice.bin")
                rule.waitUntil(5_000) { stagedBytes() in 1 until VOICE_BYTES }
                browserClick(R.string.action_close)
                rule.waitUntil(5_000) { stagedBytes() == 0L }
                val restricted = receiver.snapshot.channels.single { it.name == "ts3mobile-denied" }.id
                reader.joinChannel(restricted)
                rule.waitUntil(5_000) { receiver.snapshot.currentChannelId == restricted }
                assertEquals(ChannelFileResult.Failed(ChannelFileFailure.SESSION_CHANGED), runBlocking { files.list("/") })
                rule.runOnIdle { currentFiles.value = checkNotNull(reader.channelFiles(restricted)) }
                browserWait(R.string.channel_files_denied)
                reader.joinChannel(initial)
                rule.waitUntil(5_000) { receiver.snapshot.currentChannelId == initial }
                assertEquals(ChannelFileResult.Failed(ChannelFileFailure.SESSION_CHANGED), runBlocking { files.list("/") })
            }
        }
    }

    private fun verifyOverlap(audio: OpusAudioPlayer) {
        rule.waitUntil(5_000) { stagedBytes() in 1 until VOICE_BYTES }
        repeat(3) {
            val bytes = stagedBytes()
            val before = audio.takeReceiveDiagnostics()
            rule.waitUntil(2_000) {
                val after = audio.takeReceiveDiagnostics()
                stagedBytes() in (bytes + 128 * 1024) until VOICE_BYTES &&
                    after.count(AudioReceiveCounter.ACCEPTED) >= before.count(AudioReceiveCounter.ACCEPTED) + 5 &&
                    after.outputSamples() >= before.outputSamples() + 4_800 && audio.status == PlaybackStatus.PLAYING
            }
        }
    }

    private fun connect(
        session: Ts3jSessionClient,
        relay: UsbLoopbackVoiceRelay,
        nickname: String,
        listener: Listener,
    ) {
        session.connect(
            ServerConfig(relay.clientHost, relay.clientPort, nickname),
            Ts3IdentityCodec.generate(),
            listener,
        )
        rule.waitUntil(6_000) {
            relay.checkHealthy()
            listener.phase == ConnectionPhase.CONNECTED && listener.snapshot.currentChannelId != null
        }
    }

    private fun setBrowser(
        currentFiles: androidx.compose.runtime.MutableState<ChannelFiles?>,
        destination: Uri,
    ) {
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry =
                    object : ActivityResultRegistry() {
                        override fun <Input, Output> onLaunch(
                            requestCode: Int,
                            contract: ActivityResultContract<Input, Output>,
                            input: Input,
                            options: ActivityOptionsCompat?,
                        ) {
                            assertTrue(contract is ActivityResultContracts.CreateDocument)
                            assertEquals("voice.bin", input)
                            assertTrue(dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(destination)))
                        }
                    }
            }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                TestVerifiedFileSaveHost { currentFiles.value?.let { ChannelFilesDialog(it) { currentFiles.value = null } } }
            }
        }
    }

    private fun browserClick(text: String) {
        rule.waitUntil(35_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(text).assertIsDisplayed().performClick()
    }

    private fun browserClick(resource: Int) = browserClick(rule.activity.getString(resource))

    private fun browserWait(resource: Int) {
        val text = rule.activity.getString(resource)
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun stagedBytes(): Long =
        rule.activity.cacheDir.listFiles { file -> file.name.startsWith("ts3-download-") }?.sumOf { it.length() } ?: 0L

    private fun AudioReceiveDiagnostics.count(counter: AudioReceiveCounter): Long = counters[counter] ?: 0L

    private fun AudioReceiveDiagnostics.outputSamples(): Long = count(AudioReceiveCounter.OUTPUT_SAMPLES)

    private class Listener(private val onVoice: (VoiceFrame) -> Unit) : Ts3SessionListener {
        @Volatile var phase = ConnectionPhase.DISCONNECTED

        @Volatile var snapshot = SessionSnapshot.Empty

        override fun onStatusChanged(status: ConnectionStatus) {
            phase = status.phase
        }

        override fun onSnapshotChanged(snapshot: SessionSnapshot) {
            this.snapshot = snapshot
        }

        override fun onVoiceFrame(frame: VoiceFrame) = onVoice(frame)
    }

    private object SilenceSource : EncodedVoiceSource {
        override fun isReady(): Boolean = true

        override fun pollEncodedFrame(): ByteArray = SILENCE_FRAME.copyOf()
    }

    private companion object {
        const val VOICE_BYTES = 8_388_608L
        const val VOICE_SHA256 = "7d212b9c884f5c77896de960ae17cc341cda43b14d6a971f34ca29ebd4badf7f"
        val SILENCE_FRAME = byteArrayOf(8, 11, 228.toByte(), 185.toByte(), 160.toByte(), 188.toByte(), 132.toByte())
    }
}
