package io.github.ts3mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.file.Path
import java.util.Base64
import java.util.Properties

class ServerImagesTest {
    @Test fun assetKeysHaveCanonicalPathsAndAvatarVersions() {
        val fixture = serverImageFixture()
        val icon = checkNotNull(ServerImage.icon(fixture.getProperty("icon")))
        assertEquals(icon, ServerImage.icon(icon.id.toInt().toString()))
        assertEquals("/icon_${fixture.getProperty("icon")}", icon.path)
        listOf("0", "4294967296", "-2147483649", "-1/..", " +3", "3\n").forEach { assertNull(ServerImage.icon(it)) }

        val uid = fixture.getProperty("uid")
        val version = fixture.getProperty("version")
        val avatar = checkNotNull(ServerImage.avatar(uid, version))
        assertEquals(avatar, ServerImage.avatar(uid, version.uppercase()))
        assertEquals("/${fixture.getProperty("avatar")}", avatar.path)
        assertTrue(avatar.matchesVersion(Base64.getDecoder().decode(fixture.getProperty("png"))))
        assertFalse(avatar.matchesVersion(byteArrayOf(42)))
        assertNotEquals(avatar, ServerImage.avatar(uid, "f".repeat(32)))
        listOf(uid.dropLast(1), uid.replace('M', 'N'), "../avatar", "\n$uid").forEach { assertNull(ServerImage.avatar(it, version)) }
        assertNull(ServerImage.avatar(uid, ""))
    }

    @Test fun onlyTypedImagesUseChannelZeroWithTheirOwnSizeLimit() {
        val replies = Ts3jChannelFileReplies()
        val loopback = fixtureIpv4Loopback
        val differentLoopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2)).hostAddress
        var submissions = 0
        val transport =
            Ts3jChannelFiles(replies, { loopback }, { command, _, _ ->
                assertEquals("ftinitdownload", command.name)
                assertEquals("0", command.get("cid").value)
                assertEquals("/icon_4294967295", command.get("name").value)
                val fields =
                    mapOf(
                        "clientftfid" to command.get("clientftfid").value,
                        "port" to "1",
                        "size" to if (submissions == 0) "262145" else "42",
                        "ftkey" to "synthetic",
                        "ip" to if (submissions == 0) loopback.hostAddress else differentLoopback,
                    )
                submissions++
                assertTrue(replies.accept("notifystartdownload", fields))
                TextSendOutcome.Accepted
            })
        val image = checkNotNull(ServerImage.icon("-1"))
        assertEquals(ChannelFileResult.Failed(ChannelFileFailure.INVALID_PATH), transport.list(0, "/") { true })
        val unused = Path.of("not-created-by-invalid-test")
        assertEquals(ChannelFileResult.Failed(ChannelFileFailure.INVALID_PATH), transport.download(0, "/icon_42", unused) { true })
        assertEquals(ChannelFileResult.Failed(ChannelFileFailure.SESSION_CHANGED), transport.downloadImage(image) { false })
        assertEquals(ChannelFileResult.Failed(ChannelFileFailure.TOO_LARGE), transport.downloadImage(image) { true })
        assertEquals(ChannelFileResult.Failed(ChannelFileFailure.INVALID_RESPONSE), transport.downloadImage(image) { true })
        assertEquals(2, submissions)
    }
}

internal fun serverImageFixture(): Properties =
    Properties().apply {
        checkNotNull(ServerImagesTest::class.java.getResourceAsStream("/server-image-fixture.properties")).use(::load)
    }
