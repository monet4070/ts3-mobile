package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter
import com.github.manevolent.ts3j.protocol.ProtocolRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64

class LocalAvatarFixturePublisherTest {
    @Test fun announcesSyntheticAvatarToAnExplicitAndroidFixture() =
        runBlocking {
            val configured = System.getProperty("ts3.fileProbePort")
            val workspace = System.getenv("TS3_LOCAL_AVATAR_FIXTURE")
            assumeTrue("Explicit disposable Android avatar fixture required", configured != null && workspace != null)
            val voicePort = checkNotNull(configured).toInt().also { require(it in 1024..65_535) }
            val runDirectory = Path.of(checkNotNull(workspace)).toRealPath()
            require(Files.readString(runDirectory.resolve(".avatar-fixture")) == "temporary ts3-mobile avatar fixture\n")
            val assets = runDirectory.resolve("server/files/virtualserver_1/internal")
            require(assets.toRealPath() == assets)
            val fixture = serverImageFixture()
            val version = fixture.getProperty("version")
            val material = Ts3IdentityCodec.generate()
            val uid = Ts3IdentityCodec.inspect(material).uid
            val avatar = checkNotNull(ServerImage.avatar(uid, version))
            val avatarFile = assets.resolve(avatar.path.removePrefix("/"))
            val payload = Base64.getDecoder().decode(fixture.getProperty("png"))
            val abort = runDirectory.resolve("abort-avatar-probe")
            Files.write(avatarFile, payload, StandardOpenOption.CREATE_NEW)
            try {
                val socket = StateAwareTeamspeakClientSocket()
                val clients = Listener()
                Ts3jSessionClient(Ts3jClientSocketFactory { LocalTeamspeakClientSocketAdapter(socket) }).use { publisher ->
                    withTimeout(20_000) {
                        runInterruptible(Dispatchers.IO) {
                            publisher.connect(
                                ServerConfig(fixtureIpv4Loopback.hostAddress, voicePort, "Fixture Avatar Subject"),
                                material,
                                clients,
                            )
                        }
                    }
                    Files.createFile(runDirectory.resolve("avatar-publisher-ready"))
                    withTimeout(60_000) {
                        while (clients.snapshot.participants.none { it.nickname == "Android Attachment" }) {
                            check(!Files.exists(abort)) { "Android fixture did not connect" }
                            delay(25)
                        }
                    }
                    val announce = SingleCommand("clientupdate", ProtocolRole.CLIENT, CommandSingleParameter("client_flag_avatar", version))
                    val result =
                        runInterruptible(Dispatchers.IO) {
                            Ts3jTextCommands(socket, socket::withOperation).execute(announce, 5_000, { socket.transportConnected })
                        }
                    assertEquals("Server did not accept the fixture avatar flag", TextSendOutcome.Accepted, result)
                    Files.createFile(runDirectory.resolve("avatar-announced"))
                    withTimeout(60_000) {
                        while (!Files.exists(runDirectory.resolve("android-avatar-verified"))) {
                            check(!Files.exists(abort)) { "Android did not verify the fixture avatar" }
                            delay(50)
                        }
                    }
                }
            } finally {
                Files.deleteIfExists(avatarFile)
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
}
