package io.github.ts3mobile.protocol

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LocalChannelFilesProbeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun fullClientProvesReadOnlyFilesAndConcurrentVoiceOnAnExplicitLocalServer() =
        runBlocking {
            val configured = System.getProperty("ts3.fileProbePort")
            assumeTrue("This probe requires an explicitly configured isolated TS3 server on IPv4 loopback", configured != null)
            val port = checkNotNull(configured?.toIntOrNull()).also { require(it in 1024..65_535) }
            val reader = ProbeListener()
            val talker = ProbeListener()
            Ts3jSessionClient().use { session ->
                Ts3jSessionClient().use { peer ->
                    connect(session, port, "File Probe Reader", reader)
                    connect(peer, port, "File Probe Talker", talker)
                    val snapshot = reader.latest.get()
                    val channelId = checkNotNull(snapshot.currentChannelId)
                    val deniedId = snapshot.channels.single { it.name == DENIED_CHANNEL }.id
                    check(deniedId != channelId && talker.latest.get().currentChannelId == channelId)
                    val files = checkNotNull(session.channelFiles(channelId))
                    val staging = temporary.newFolder().toPath()
                    checkReadableFixture(files, staging)

                    reader.voicePeer.set(checkNotNull(talker.latest.get().ownClientId))
                    peer.setVoiceSource(ProbeVoice())
                    try {
                        await("No valid test voice received before the file transfer") { reader.voiceCount.get() >= 3 }
                        withTimeout(40_000) {
                            val transfer = async { files.stageDownload("/$FIXTURE_DIRECTORY/$VOICE_FILENAME", staging) }
                            var earlierSize = 0L
                            var earlierFrames = 0
                            await("No incomplete staging file observed; check server rate limit and fixture size") {
                                requireIncomplete(transfer)
                                earlierSize = inProgressBytes(staging)
                                earlierFrames = reader.voiceCount.get()
                                earlierSize in 1 until VOICE_BYTES
                            }
                            await("No advancing file bytes and voice observed together") {
                                requireIncomplete(transfer)
                                val frames = reader.voiceCount.get()
                                val bytes = inProgressBytes(staging)
                                bytes in (earlierSize + 1) until VOICE_BYTES && frames >= earlierFrames + 3
                            }
                            val completed = success("voice fixture download", transfer.await())
                            assertEquals(VOICE_BYTES, Files.size(completed))
                            assertEquals(VOICE_SHA256, sha256(completed))
                            Files.delete(completed)
                        }
                        val after = reader.voiceCount.get()
                        await("Voice did not continue after the file transfer") { reader.voiceCount.get() >= after + 3 }
                        assertEquals(ConnectionPhase.CONNECTED, reader.phase.get())
                    } finally {
                        peer.setVoiceSource(null)
                    }

                    join(session, reader, deniedId)
                    assertFailure("stale port after moving", ChannelFileFailure.SESSION_CHANGED, files.list("/"))
                    val denied = checkNotNull(session.channelFiles(deniedId))
                    assertFailure("denied channel list", ChannelFileFailure.REJECTED, denied.list("/"))
                    assertFailure("denied channel download", ChannelFileFailure.REJECTED, denied.stageDownload("/$SMALL_FILENAME", staging))
                    join(session, reader, channelId)
                    assertFailure("old port after returning", ChannelFileFailure.SESSION_CHANGED, files.list("/"))
                    val fresh = checkNotNull(session.channelFiles(channelId))
                    assertEquals(
                        emptyList<ChannelFileEntry>(),
                        success("empty folder after returning", fresh.list("/$FIXTURE_DIRECTORY/empty")),
                    )
                    session.close()
                    assertFailure("port after disconnect", ChannelFileFailure.SESSION_CHANGED, fresh.list("/"))
                    Files.list(staging).use { assertEquals(0L, it.count()) }
                }
            }
        }

    private suspend fun connect(
        session: Ts3SessionClient,
        port: Int,
        nickname: String,
        listener: ProbeListener,
    ) {
        withTimeout(20_000) {
            runInterruptible(Dispatchers.IO) {
                session.connect(ServerConfig(fixtureIpv4Loopback.hostAddress, port, nickname), Ts3IdentityCodec.generate(), listener)
            }
        }
        await("Fresh test client did not publish a connected channel") {
            listener.phase.get() == ConnectionPhase.CONNECTED && listener.latest.get().currentChannelId != null
        }
    }

    private suspend fun join(
        session: Ts3SessionClient,
        listener: ProbeListener,
        channelId: Int,
    ) {
        withTimeout(6_000) { runInterruptible(Dispatchers.IO) { session.joinChannel(channelId) } }
        await("The fixture client did not enter the expected channel") { listener.latest.get().currentChannelId == channelId }
    }

    private suspend fun checkReadableFixture(
        files: ChannelFiles,
        staging: Path,
    ) {
        val root = success("channel root", files.list("/"))
        assertTrue(root.any { it.directory && it.name == FIXTURE_DIRECTORY })
        val nested = success("nested folder", files.list("/$FIXTURE_DIRECTORY"))
        assertEquals(SMALL_BYTES.size.toLong(), nested.single { !it.directory && it.name == SMALL_FILENAME }.size)
        assertEquals(VOICE_BYTES, nested.single { !it.directory && it.name == VOICE_FILENAME }.size)
        assertEquals(emptyList<ChannelFileEntry>(), success("empty folder", files.list("/$FIXTURE_DIRECTORY/empty")))
        val staged = success("small fixture download", files.stageDownload("/$FIXTURE_DIRECTORY/$SMALL_FILENAME", staging))
        assertArrayEquals(SMALL_BYTES, Files.readAllBytes(staged))
        Files.delete(staged)
    }

    private suspend fun inProgressBytes(directory: Path): Long =
        withContext(Dispatchers.IO) {
            try {
                Files.list(directory).use { files -> files.findFirst().map { Files.size(it) }.orElse(0L) }
            } catch (_: NoSuchFileException) {
                0L
            }
        }

    private suspend fun requireIncomplete(transfer: Deferred<ChannelFileResult<Path>>) {
        if (!transfer.isCompleted) return
        success("voice fixture download", transfer.await())
        error("Transfer completed before proving overlap; check the isolated server's rate limit")
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32_768)
        Files.newInputStream(path).use { input ->
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private suspend fun await(
        failure: String,
        predicate: suspend () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 6_000_000_000L
        while (!predicate()) {
            check(System.nanoTime() < deadline) { failure }
            delay(10)
        }
    }

    private fun <Value> success(
        step: String,
        result: ChannelFileResult<Value>,
    ): Value = (result as? ChannelFileResult.Success)?.value ?: error("$step failed: $result")

    private fun assertFailure(
        step: String,
        failure: ChannelFileFailure,
        result: ChannelFileResult<*>,
    ) = assertEquals(step, ChannelFileResult.Failed(failure), result)

    private class ProbeListener : Ts3SessionListener {
        val latest = AtomicReference(SessionSnapshot.Empty)
        val phase = AtomicReference(ConnectionPhase.DISCONNECTED)
        val voicePeer = AtomicInteger()
        val voiceCount = AtomicInteger()
        private val lastPacket = AtomicInteger(-1)

        override fun onStatusChanged(status: ConnectionStatus) {
            phase.set(status.phase)
        }

        override fun onSnapshotChanged(snapshot: SessionSnapshot) {
            latest.set(snapshot)
        }

        override fun onVoiceFrame(frame: VoiceFrame) {
            if (frame.clientId == voicePeer.get() && frame.codec == VoiceCodec.OPUS_VOICE && !frame.isWhisper &&
                frame.encodedData.contentEquals(SILENCE_FRAME) && lastPacket.getAndSet(frame.packetId) != frame.packetId
            ) {
                voiceCount.incrementAndGet()
            }
        }
    }

    private class ProbeVoice : EncodedVoiceSource {
        override fun isReady(): Boolean = true

        override fun pollEncodedFrame(): ByteArray = SILENCE_FRAME.copyOf()
    }

    private companion object {
        const val FIXTURE_DIRECTORY = "ts3mobile-fixture"
        const val DENIED_CHANNEL = "ts3mobile-denied"
        const val SMALL_FILENAME = "probe.txt"
        const val VOICE_FILENAME = "voice.bin"
        const val VOICE_BYTES = 8_388_608L
        const val VOICE_SHA256 = "7d212b9c884f5c77896de960ae17cc341cda43b14d6a971f34ca29ebd4badf7f"
        val SMALL_BYTES = "TS3 Mobile file acceptance\n".toByteArray(Charsets.US_ASCII)
        val SILENCE_FRAME = byteArrayOf(8, 11, 228.toByte(), 185.toByte(), 160.toByte(), 188.toByte(), 132.toByte())
    }
}
