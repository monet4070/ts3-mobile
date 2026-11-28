package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.protocol.socket.client.AbstractTeamspeakClientSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class LocalChannelAttachmentProbeTest {
    @get:Rule val cache = TemporaryFolder()

    @Test fun pastedReferencesUseCurrentUidAndOnlyTheServersFilePermissions() =
        runBlocking {
            val configured = System.getProperty("ts3.fileProbePort")
            assumeTrue("Explicit isolated loopback fixture required", configured != null)
            val port = checkNotNull(configured).toInt().also { require(it in 1024..65_535) }
            val wire = InitFixtureSocket()
            val listener = Listener()
            val factory = Ts3jClientSocketFactory { LocalTeamspeakClientSocketAdapter(wire, verifyAttachmentIdentity = true) }
            Ts3jSessionClient(factory).use { session ->
                connectFixture(session, port, listener)
                val uid = checkNotNull(wire.wireUid) { "Fixture did not provide a virtual server UID" }
                val readable = listener.snapshot.channels.single { it.isDefault }.id
                val denied = listener.snapshot.channels.single { it.name == "ts3mobile-denied" }.id
                val target = reference(uid, readable, "/ts3mobile-fixture")
                assertEquals(target.serverUid, listener.snapshot.serverUid)
                assertNull(session.attachmentDownload(reference("AAAAAAAAAAAAAAAAAAAAAAAAAAA=", readable, "/")))
                assertNull(session.attachmentDownload(reference(uid, Int.MAX_VALUE, "/")))
                val destination = cache.newFolder().toPath()
                val retained = java.util.concurrent.atomic.AtomicBoolean(true)
                val expired = checkNotNull(session.attachmentDownload(target, retained::get))
                retained.set(false)
                assertEquals(ChannelFileResult.Failed(ChannelFileFailure.SESSION_CHANGED), expired.stage(destination))
                val handle = checkNotNull(session.attachmentDownload(target))
                val rejected = checkNotNull(session.attachmentDownload(reference(uid, denied, "/")))
                assertEquals(ChannelFileResult.Failed(ChannelFileFailure.REJECTED), rejected.stage(destination))
                runInterruptible(Dispatchers.IO) { session.joinChannel(denied) }
                withTimeout(6_000) { while (listener.snapshot.currentChannelId != denied) delay(10) }
                val staged = handle.stage(destination)
                check(staged is ChannelFileResult.Success) { "Fixture download failed: $staged" }
                assertEquals("TS3 Mobile file acceptance\n", Files.readString(staged.value))
                Files.delete(staged.value)
                session.close()
                assertEquals(ChannelFileResult.Failed(ChannelFileFailure.SESSION_CHANGED), handle.stage(destination))
                Files.list(destination).use { assertEquals(0L, it.count()) }
            }
        }

    @Test fun desktopCopyUrlsPreserveOrdinaryName() = verifyDesktopCopyUrl("ordinary.url", "probe.txt")

    @Test fun desktopCopyUrlsPreserveInternationalName() {
        verifyDesktopCopyUrl("international.url", "QA 中 A+100%.txt", "QA 中 A+100\uFFFDxt")
    }

    private fun verifyDesktopCopyUrl(
        sampleFile: String,
        fixtureFile: String,
        desktopLabel: String = fixtureFile,
    ) = runBlocking {
        val workspace = System.getenv("TS3_DESKTOP_COPY_URL_DIR")
        assumeTrue("Explicit local Copy URL sample directory required", workspace != null)
        val port = checkNotNull(System.getProperty("ts3.fileProbePort")?.toIntOrNull()) { "Explicit fixture port required" }
        require(port in 1024..65_535)
        val directory = Path.of(checkNotNull(workspace))
        val target = readCopyUrl(directory.resolve(sampleFile), fixtureFile, desktopLabel)
        val expected = "TS3 Mobile file acceptance\n".toByteArray()
        val destination = cache.newFolder().toPath()
        val listener = Listener()
        val factory = Ts3jClientSocketFactory { LocalTeamspeakClientSocketAdapter(verifyAttachmentIdentity = true) }
        Ts3jSessionClient(factory).use { session ->
            connectFixture(session, port, listener)
            val channel = listener.snapshot.channels.single { it.isDefault }.id
            check(target.channelId == channel && target.serverUid == listener.snapshot.serverUid) {
                "Copy URL does not refer to this isolated server's default channel"
            }
            val download = checkNotNull(session.attachmentDownload(target)) { "Fixture session could not open the attachment" }
            val result = download.stage(destination)
            check(result is ChannelFileResult.Success) { "Fixture attachment download did not succeed: $result" }
            try {
                assertEquals(expected.size.toLong(), Files.size(result.value))
                assertArrayEquals(expected, Files.readAllBytes(result.value))
            } finally {
                Files.deleteIfExists(result.value)
            }
        }
        Files.list(destination).use { assertEquals(0L, it.count()) }
    }

    private fun readCopyUrl(
        path: Path,
        expectedFile: String,
        expectedLabel: String,
    ): ChannelAttachment {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "A regular local sample file is required" }
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(16 * 1024 + 1) }
        require(bytes.size in 1..(16 * 1024)) { "Copy URL sample is empty or too large" }
        val text =
            try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                error("Copy URL sample is not UTF-8")
            }
        val copied = text.removeSuffix("\n").removeSuffix("\r")
        val markup =
            checkNotNull(desktopUrlMarkup.matchEntire(copied)) {
                "Expected one TeamSpeak 3 Copy URL BBCode link"
            }
        check(markup.groupValues[2] == expectedLabel) { "The Copy URL label differs from the observed desktop fixture" }
        val target =
            checkNotNull(ChannelAttachment.parse(markup.groupValues[1])) {
                "The current parser did not accept the Copy URL sample"
            }
        check(target.fileName == expectedFile && target.directory == "/ts3mobile-fixture") {
            "The Copy URL did not preserve the expected fixture path"
        }
        return target
    }

    private suspend fun connectFixture(
        session: Ts3jSessionClient,
        port: Int,
        listener: Listener,
    ) {
        withTimeout(20_000) {
            runInterruptible(Dispatchers.IO) {
                session.connect(
                    ServerConfig(fixtureIpv4Loopback.hostAddress, port, "Attachment Probe"),
                    Ts3IdentityCodec.generate(),
                    listener,
                )
            }
        }
        withTimeout(6_000) { while (listener.snapshot.currentChannelId == null) delay(10) }
    }

    private fun reference(
        uid: String,
        channel: Int,
        path: String,
    ): ChannelAttachment =
        checkNotNull(
            ChannelAttachment.parse(
                "ts3file://not-a-network-target.invalid?port=9987&serverUID=$uid&channel=$channel&path=$path&filename=probe.txt&isDir=0",
            ),
        )

    private class InitFixtureSocket : StateAwareTeamspeakClientSocket() {
        @Volatile var wireUid: String? = null

        override fun process(
            client: AbstractTeamspeakClientSocket,
            command: SingleCommand,
        ) {
            if (client === this) {
                command.parameters.firstOrNull { it.name == "virtualserver_unique_identifier" }?.let { wireUid = it.value }
            }
            super.process(client, command)
        }
    }

    private class Listener : Ts3SessionListener {
        @Volatile var snapshot = SessionSnapshot.Empty

        override fun onStatusChanged(status: ConnectionStatus) = Unit

        override fun onSnapshotChanged(snapshot: SessionSnapshot) {
            this.snapshot = snapshot
        }

        override fun onVoiceFrame(frame: VoiceFrame) = Unit
    }

    private companion object {
        val desktopUrlMarkup = Regex("\\[url=([^\\[\\]\\r\\n]+)\\]([^\\r\\n]+)\\[/url\\]", RegexOption.IGNORE_CASE)
    }
}
