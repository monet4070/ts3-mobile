package io.github.ts3mobile.app.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

internal fun fixtureInvite(
    authorityAndQuery: String,
    scheme: String = "ts3server",
): String = "$scheme://$authorityAndQuery"

class ServerInviteTest {
    @Test fun followsTeamSpeakPortAndChannelConventions() {
        assertEquals(ServerInvite("voice.example.invalid", 9987, null), ServerInvite.parse(fixtureInvite("voice.example.invalid")))
        assertEquals(
            ServerInvite("voice.example.invalid", 10001, 87),
            ServerInvite.parse(fixtureInvite("voice.example.invalid:10001?cid=87")),
        )
        val invite = ServerInvite.parse(" ${fixtureInvite("[2001:db8::2]?port=10001&cid=%38%37", "TS3SERVER")} ")!!
        assertEquals("2001:db8::2", invite.host)
        assertEquals("[2001:db8::2]:10001", invite.endpoint)
        assertEquals(87, invite.channelId)
    }

    @Test fun rejectsAmbiguousInvalidOrNonServerUris() {
        val links =
            listOf(
                "/path", "user:secret@voice.example.invalid", "2001:db8::2", "[fe80::1%25eth0]",
                "voice.example.invalid:", "voice.example.invalid:0", "voice.example.invalid:65536",
                "voice.example.invalid:9987?port=9987", "voice.example.invalid?port=+9987",
                "voice.example.invalid?cid=1&%43ID=2", "voice.example.invalid?cid=2147483648",
                "voice.example.invalid?cid=", "voice.example.invalid?cid=-1", "voice.example.invalid?port=%Q1",
                "voice.example.invalid?token=one&token=two", "voice.example.invalid#channel",
                "voice.example.invalid?token=${"z".repeat(ServerInvite.MAX_LENGTH)}",
            ).map { fixtureInvite(it) } + listOf("https://voice.example.invalid", "ts3server:voice.example.invalid")
        for (link in links) assertNull(ServerInvite.parse(link))
    }

    @Test fun ignoresUnsupportedFieldsAndSharesOnlyTheEndpointAndId() {
        val invite =
            ServerInvite.parse(
                fixtureInvite(
                    "voice.example.invalid/Team?password=fixture&token=fixture&channel=Alpha&channelpassword=fixture" +
                        "&nickname=Other&addbookmark=fixture&extra=fixture&cid=4",
                ),
            )!!
        assertEquals(IgnoredInviteField.entries.toSet(), invite.ignored)
        assertFalse(invite.toString().contains("example"))
        assertEquals(
            fixtureInvite("voice.example.invalid?port=9987&cid=4"),
            ServerInvite.shareText(invite.host, invite.port, invite.channelId),
        )
        assertEquals(fixtureInvite("[2001:db8::2]?port=9990"), ServerInvite.shareText("[2001:db8::2]", 9990))
        assertNull(ServerInvite.shareText("voice.example.invalid?token=fixture", 9987))
        assertNull(ServerInvite.shareText("voice.example.invalid", 9987, 0))
    }
}
