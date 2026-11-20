package io.github.ts3mobile.app.invite

import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteEntryTest {
    @Test fun activeSessionAndRepeatedDeliveryRequireOneFreshConfirmation() {
        val entry = InviteEntry()
        val calls = mutableListOf<ServerConfig>()
        val link = fixtureInvite("other.example.invalid?cid=7&password=ignored&nickname=Ignored&token=ignored")
        entry.accept(link)
        val target = entry.preview.value as InvitePreview.Target
        entry.accept(link)
        assertSame(target, entry.preview.value)
        entry.confirm(target, ConnectionPhase.CONNECTED, "Local name", "", "", calls::add)
        entry.confirm(target, ConnectionPhase.DISCONNECTING, "Local name", "", "", calls::add)
        entry.confirm(target, null, "Local name", "", "", calls::add)
        entry.confirm(target, ConnectionPhase.DISCONNECTED, "x", "", "", calls::add)
        assertTrue(calls.isEmpty())
        entry.confirm(target, ConnectionPhase.DISCONNECTED, "Local name", "manual", "manual-channel", calls::add)
        assertNull(entry.preview.value)
        entry.accept(link)
        entry.confirm(target, ConnectionPhase.DISCONNECTED, "Local name", "", "", calls::add)
        assertEquals(listOf(ServerConfig("other.example.invalid", 9987, "Local name", "manual", 7, "manual-channel")), calls)
    }

    @Test fun invalidOrDismissedPreviewNeverConnectsAndExternalCredentialsAreNotAdopted() {
        val entry = InviteEntry()
        val calls = mutableListOf<ServerConfig>()
        entry.accept("https://example.invalid")
        assertSame(InvitePreview.Invalid, entry.preview.value)
        entry.accept(fixtureInvite("other.example.invalid?password=ignored&channelpassword=ignored"))
        val target = entry.preview.value as InvitePreview.Target
        entry.confirm(target, ConnectionPhase.ERROR, " Local name ", "", "stray", calls::add)
        entry.accept(fixtureInvite("other.example.invalid"))
        val dismissed = entry.preview.value as InvitePreview.Target
        entry.dismiss()
        entry.confirm(dismissed, ConnectionPhase.DISCONNECTED, "Local name", "", "", calls::add)
        assertEquals(listOf(ServerConfig("other.example.invalid", 9987, "Local name")), calls)
    }
}
