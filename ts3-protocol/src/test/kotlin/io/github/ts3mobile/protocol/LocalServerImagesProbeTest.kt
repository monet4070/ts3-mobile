package io.github.ts3mobile.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64

class LocalServerImagesProbeTest {
    @Test fun downloadsOnlyTypedAssetsFromOfficialLoopbackAndRetiresOnDisconnect() =
        runBlocking {
            val configured = System.getProperty("ts3.fileProbePort")
            assumeTrue("This probe requires the explicit server image fixture on IPv4 loopback", configured != null)
            val port = checkNotNull(configured).toInt().also { require(it in 1024..65_535) }
            val fixture = serverImageFixture()
            val icon = checkNotNull(ServerImage.icon(fixture.getProperty("icon")))
            val avatar = checkNotNull(ServerImage.avatar(fixture.getProperty("uid"), fixture.getProperty("version")))
            val wrongVersion = checkNotNull(ServerImage.avatar(fixture.getProperty("uid"), "f".repeat(32)))
            val expected = Base64.getDecoder().decode(fixture.getProperty("png"))
            val listener = Listener()
            Ts3jSessionClient().use { session ->
                assertNull(session.serverImages())
                withTimeout(20_000) {
                    runInterruptible(Dispatchers.IO) {
                        session.connect(
                            ServerConfig(fixtureIpv4Loopback.hostAddress, port, "Image Probe"),
                            Ts3IdentityCodec.generate(),
                            listener,
                        )
                    }
                }
                withTimeout(6_000) { while (listener.snapshot.currentChannelId == null) delay(10) }
                assertEquals(icon, listener.snapshot.serverIcon)
                assertEquals(icon, listener.snapshot.channels.single { it.isDefault }.icon)
                val images = checkNotNull(session.serverImages())
                assertEquals(images, session.serverImages())
                assertArrayEquals(expected, bytes("icon", images.download(icon)))
                assertArrayEquals(expected, bytes("avatar", images.download(avatar)))
                assertEquals(ChannelFileResult.Failed(ChannelFileFailure.INVALID_RESPONSE), images.download(wrongVersion))
                session.close()
                assertEquals(ChannelFileResult.Failed(ChannelFileFailure.SESSION_CHANGED), images.download(icon))
                assertNull(session.serverImages())
            }
        }

    private fun bytes(
        label: String,
        result: ChannelFileResult<ByteArray>,
    ): ByteArray = (result as? ChannelFileResult.Success)?.value ?: error("$label fixture download failed: $result")

    private class Listener : Ts3SessionListener {
        @Volatile var snapshot = SessionSnapshot.Empty

        override fun onStatusChanged(status: ConnectionStatus) = Unit

        override fun onSnapshotChanged(snapshot: SessionSnapshot) {
            this.snapshot = snapshot
        }

        override fun onVoiceFrame(frame: VoiceFrame) = Unit
    }
}
